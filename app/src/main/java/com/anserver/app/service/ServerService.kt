package com.anserver.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.anserver.app.ANServerApplication
import com.anserver.app.R
import com.anserver.app.embedded.EmbeddedBootstrap
import com.anserver.app.embedded.EmbeddedPaths
import com.anserver.app.embedded.EmbeddedProcessManager
import com.anserver.app.ui.MainActivity
import com.anserver.core.model.ServerStatus
import com.anserver.core.model.ServerType
import com.anserver.plugins.minecraft.MinecraftServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Вбудований сервіс запуску серверів.
 * БЕЗ зовнішнього Termux, БЕЗ RUN_COMMAND, БЕЗ SAF.
 * Все виконується в приватній папці додатку через EmbeddedProcessManager.
 */
class ServerService : Service() {

    private val binder = ServerBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    // Кеш робочих папок: логи мають писатись у справжню папку сервера
    // (включно з кастомною), а не вгадуватись через probe.
    private val workDirCache = java.util.concurrent.ConcurrentHashMap<String, File>()
    // Сервери що зараз проходять довге завантаження (jar/JRE) поза локом.
    // Захищено startMutex.
    private val startingIds = mutableSetOf<String>()
    private val repository = ANServerApplication.serverRepository

    inner class ServerBinder : Binder() {
        fun getService(): ServerService = this@ServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> {
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
                serverId?.let { startServer(it) }
            }
            ACTION_STOP_SERVER -> {
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
                serverId?.let { stopServer(it) }
            }
        }
        return START_STICKY
    }

    private fun startServer(serverId: String) {
        serviceScope.launch {
            // 1. Швидка фаза під локом: перевірки + резерв слота, без мережевого I/O.
            val reservedWorkDir: File? = startMutex.withLock {
                try {
                    android.util.Log.d("ServerService", "startServer embedded $serverId")
                    if (startingIds.contains(serverId)) {
                        log(serverId, "Сервер вже запускається")
                        return@withLock null
                    }
                    val server = repository.getServer(serverId).first()
                    if (server == null) {
                        android.util.Log.e("ServerService", "Server not found $serverId")
                        return@withLock null
                    }
                    if (server.status == ServerStatus.RUNNING || server.status == ServerStatus.STARTING) {
                        log(serverId, "Сервер вже запущений")
                        return@withLock null
                    }
                    if (EmbeddedProcessManager.runningCount() >= EmbeddedProcessManager.MAX_SERVERS) {
                        log(serverId, "Зупини перший сервер (максимум ${EmbeddedProcessManager.MAX_SERVERS})")
                        // не блокуємо мʼютекс на persist
                        serviceScope.launch { repository.updateServerStatus(serverId, ServerStatus.ERROR) }
                        return@withLock null
                    }
                    if (EmbeddedProcessManager.isRunning(serverId)) {
                        log(serverId, "Сервер вже запущений")
                        return@withLock null
                    }
                    startingIds.add(serverId)
                    // статус STARTING виставляємо одразу щоб UI відреагував
                    serviceScope.launch { repository.updateServerStatus(serverId, ServerStatus.STARTING) }

                    val workDir = EmbeddedPaths.resolveWorkDir(
                        this@ServerService, serverId,
                        server.effectiveWorkDirUri.ifEmpty { server.workingDirectory }
                    )
                    workDir
                } catch (e: Exception) {
                    android.util.Log.e("ServerService", "startServer reserve error", e)
                    log(serverId, "EXCEPTION: ${e.message}")
                    null
                }
            }
            if (reservedWorkDir == null) return@launch

            // 2. Довга фаза ПОЗА локом: файли, завантаження jar/JRE, запуск процесу.
            try {
                val server = repository.getServer(serverId).first()
                if (server == null) {
                    startMutex.withLock { startingIds.remove(serverId) }
                    return@launch
                }
                reservedWorkDir.mkdirs()
                workDirCache[serverId] = reservedWorkDir
                EmbeddedBootstrap.ensureServerDirReady(reservedWorkDir)

                // Сховище могли втратити після створення сервера (витягнута SD,
                // відкликаний доступ до всіх файлів). Стартувати в чужій/пустій
                // папці не можна — чесно відмовляємо.
                val storedUri = server.effectiveWorkDirUri.ifEmpty { server.workingDirectory }
                when (EmbeddedPaths.kindOfUri(storedUri)) {
                    EmbeddedPaths.StorageKind.EXTERNAL -> {
                        if (!EmbeddedPaths.isExternalStorageAvailable(this@ServerService)) {
                            log(serverId, "ERROR: зовнішнє сховище недоступне (витягнута SD-карта?). Встав карту або перенеси сервер у внутрішнє сховище.")
                            repository.updateServerStatus(serverId, ServerStatus.ERROR)
                            return@launch
                        }
                    }
                    EmbeddedPaths.StorageKind.CUSTOM -> {
                        if (!EmbeddedPaths.hasAllFilesAccess(this@ServerService)) {
                            log(serverId, "ERROR: нема доступу до папки сервера. Надай «доступ до всіх файлів» у системних налаштуваннях і спробуй ще раз.")
                            repository.updateServerStatus(serverId, ServerStatus.ERROR)
                            return@launch
                        }
                    }
                    else -> {}
                }
                if (!EmbeddedPaths.isDirWritable(reservedWorkDir)) {
                    log(serverId, "ERROR: папка недоступна для запису: ${reservedWorkDir.absolutePath}")
                    repository.updateServerStatus(serverId, ServerStatus.ERROR)
                    return@launch
                }

                log(serverId, "Запуск сервера (вбудований термінал)...")
                log(serverId, "Сервер: ${server.name} type=${server.type} port=${server.port}")
                log(serverId, "Папка: ${reservedWorkDir.absolutePath}")

                try {
                    val stat = android.os.StatFs(reservedWorkDir.absolutePath)
                    if (stat.availableBytes < 512L * 1024 * 1024) {
                        log(serverId, "Попередження: мало місця <512MB")
                    }
                } catch (_: Exception) {}

                when (server.type) {
                    ServerType.MINECRAFT -> startMinecraft(serverId, reservedWorkDir)
                    ServerType.UBUNTU_SERVER -> startUbuntu(serverId, reservedWorkDir)
                }
            } catch (e: Exception) {
                android.util.Log.e("ServerService", "startServer error", e)
                log(serverId, "EXCEPTION: ${e.message}")
                try { repository.updateServerStatus(serverId, ServerStatus.ERROR) } catch (_: Exception) {}
            } finally {
                startMutex.withLock { startingIds.remove(serverId) }
            }
        }
    }

    private suspend fun startMinecraft(serverId: String, workDir: File) {
        val server = repository.getServer(serverId).first() ?: return

        // eula + properties у внутрішній папці
        try {
            val eula = File(workDir, "eula.txt")
            if (!eula.exists()) eula.writeText("#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://account.mojang.com/documents/minecraft_eula).\neula=true")
            val props = File(workDir, "server.properties")
            if (!props.exists()) {
                props.writeText(
                    "server-port=${server.port}\nmax-players=20\ngamemode=survival\ndifficulty=normal\n" +
                        "pvp=true\nonline-mode=true\nmotd=A Minecraft Server - ${server.name}\nview-distance=8\n" +
                        "spawn-protection=16\nenable-command-block=false\nallow-flight=false\nwhite-list=false\nenforce-whitelist=false\n"
                )
            }
        } catch (_: Exception) {}

        // Provision jar у внутрішню папку (звичайний File I/O, без SAF)
        val version = server.config.minecraftVersion ?: "1.21.4"
        val loader = server.config.minecraftLoader
        val manager = MinecraftServerManager(workDir.parent ?: workDir.absolutePath)
        val hasJarBefore = EmbeddedProcessManager.findExistingJar(
            workDir, EmbeddedProcessManager.guessJarName(loader?.name)
        ) != null
        if (!hasJarBefore) {
            log(serverId, "Завантаження ${loader ?: "PAPER"} $version (~50-100 MB)...")
            val ok = try {
                manager.provisionIfNeeded(
                    workDir.absolutePath, version, loader,
                    server.config.maxMemory, server.config.minMemory, server.port
                ) { msg -> log(serverId, msg) }
            } catch (e: Exception) {
                log(serverId, "Provision error: ${e.message}")
                false
            }
            if (!ok) {
                val stillMissing = EmbeddedProcessManager.findExistingJar(
                    workDir, EmbeddedProcessManager.guessJarName(loader?.name)
                ) == null
                if (stillMissing) {
                    log(serverId, "ERROR: не вдалося завантажити jar. Відкрий Console/Files щоб побачити причину (HTTP-код) вище. Якщо інтернет є - спробуй VANILLA/PURPUR/FABRIC.")
                    repository.updateServerStatus(serverId, ServerStatus.ERROR)
                    return
                }
            }
        }

        // Java з вбудованого середовища (без Termux!)
        log(serverId, "Пошук вбудованої Java...")
        val javaPath = EmbeddedBootstrap.ensureJava(this@ServerService, version) { msg -> log(serverId, msg) }
        if (javaPath == null) {
            log(serverId, "ERROR: Java недоступна. Дивись причину вище (HTTP-код). Деталі в Console.")
            repository.updateServerStatus(serverId, ServerStatus.ERROR)
            return
        }

        val preferred = EmbeddedProcessManager.guessJarName(loader?.name)
        val jar = EmbeddedProcessManager.findExistingJar(workDir, preferred)
        if (jar == null) {
            log(serverId, "ERROR: jar не знайдено в ${workDir.absolutePath}")
            repository.updateServerStatus(serverId, ServerStatus.ERROR)
            return
        }

        // W^X: прямий exec з папки додатку заборонений політикою Android для
        // targetSdk 29+ (error=13). Підбираємо робочу форму команди на пристрої.
        val launch = com.anserver.app.embedded.EmbeddedJava.probeJava(javaPath) { msg -> log(serverId, msg) }
        if (!launch.works) {
            log(serverId, "ERROR: Java не запускається ні напряму, ні через системний лінкер (деталі вище).")
            log(serverId, "Причина — заборона exec з папки додатку (Android 10+, targetSdk 29+). Варіанти: ручний JRE з системним java або збірка з targetSdk 28.")
            repository.updateServerStatus(serverId, ServerStatus.ERROR)
            return
        }
        if (launch.useLinker) {
            log(serverId, "Java: ${launch.version} (через системний лінкер — прямий запуск заборонений політикою Android)")
        } else {
            log(serverId, "Java: ${launch.version} (прямий запуск)")
        }

        val cmd = EmbeddedProcessManager.buildMinecraftCommand(
            javaPath, jar, server.config.maxMemory, server.config.minMemory,
            server.config.javaArgs, launch.prefix
        )
        val startEnv = mutableMapOf("MC_PORT" to server.port.toString())
        // Оточення, з яким спрацював probe (LD_LIBRARY_PATH/JAVA_HOME для лінкера).
        startEnv.putAll(launch.env)
        log(serverId, "Старт: java -Xmx${server.config.maxMemory}M -jar $jar nogui")

        val ok = EmbeddedProcessManager.start(
            this@ServerService, serverId, cmd, workDir,
            startEnv
        )
        if (!ok) {
            log(serverId, "ERROR: не вдалося запустити процес")
            repository.updateServerStatus(serverId, ServerStatus.ERROR)
            return
        }
        repository.updateTermuxExecutionId(serverId, (System.currentTimeMillis() % Int.MAX_VALUE).toInt())
        repository.updateServerStatus(serverId, ServerStatus.RUNNING)
        log(serverId, "Сервер запущено (вбудований термінал) jar=$jar")
        startLivenessPoll(serverId)
    }

    private suspend fun startUbuntu(serverId: String, workDir: File) {
        val ip = getDeviceIp()
        EmbeddedBootstrap.ensureUbuntu(this@ServerService) { msg -> log(serverId, msg) }
        // Вбудований інтерактивний shell - ввід/вивід через ConsoleActivity без жодних дозволів
        val ok = EmbeddedProcessManager.startInteractiveShell(this@ServerService, serverId, workDir)
        if (!ok) {
            log(serverId, "ERROR: не вдалося запустити shell")
            repository.updateServerStatus(serverId, ServerStatus.ERROR)
            return
        }
        repository.updateTermuxExecutionId(serverId, (System.currentTimeMillis() % Int.MAX_VALUE).toInt())
        repository.updateServerStatus(serverId, ServerStatus.RUNNING)
        log(serverId, "\nВбудований Linux-shell запущено. IP: $ip\n")
        log(serverId, "Вводь команди в консолі нижче (sh -i). Повний Ubuntu rootfs - в наступних оновленнях.\n")
        startLivenessPoll(serverId)
    }

    private fun stopServer(serverId: String) {
        serviceScope.launch {
            // Ввічливий stop через stdin + пауза ПОЗА локом щоб не блокувати start().
            val wasRunning = EmbeddedProcessManager.isRunning(serverId)
            if (wasRunning) {
                try {
                    EmbeddedProcessManager.sendInput(serverId, "stop")
                    delay(2000)
                } catch (_: Exception) {}
            }
            startMutex.withLock {
                try {
                    android.util.Log.d("ServerService", "stopServer embedded $serverId")
                    if (!EmbeddedProcessManager.isRunning(serverId)) {
                        log(serverId, "Сервер не запущений")
                        repository.updateServerStatus(serverId, ServerStatus.STOPPED)
                        return@withLock
                    }
                    repository.updateServerStatus(serverId, ServerStatus.STOPPING)
                    log(serverId, "Зупинка сервера...")
                    EmbeddedProcessManager.stop(serverId)
                    repository.updateTermuxExecutionId(serverId, null)
                    repository.updateServerStatus(serverId, ServerStatus.STOPPED)
                    log(serverId, "Сервер зупинено")
                } catch (e: Exception) {
                    android.util.Log.e("ServerService", "stop error", e)
                    log(serverId, "Error stopping: ${e.message}")
                }
            }
        }
    }

    private fun log(serverId: String, message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val line = "[$timestamp] $message\n"
        // пишемо одразу у файл справжньої робочої папки (без SAF!)
        try {
            val dir = workDirCache[serverId]
                ?: EmbeddedPaths.lookupServerDir(this@ServerService, serverId)
            EmbeddedProcessManager.appendLogToDir(dir, line)
        } catch (_: Exception) {}
    }

    private fun getDeviceIp(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) return host
                    }
                }
            }
            for (iface in java.net.NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) return addr.hostAddress ?: continue
                }
            }
            "192.168.0.100"
        } catch (_: Exception) { "192.168.0.100" }
    }

    private fun startLivenessPoll(serverId: String) {
        serviceScope.launch {
            var emptyCount = 0
            while (true) {
                delay(5000)
                try {
                    val server = repository.getServer(serverId).first()
                    if (server == null || server.status != ServerStatus.RUNNING) break
                    if (!EmbeddedProcessManager.isRunning(serverId)) {
                        emptyCount++
                        if (emptyCount >= 2) {
                            log(serverId, "Процес завершено")
                            repository.updateServerStatus(serverId, ServerStatus.STOPPED)
                            break
                        }
                    } else {
                        emptyCount = 0
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, ANServerApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("PocketHost")
            .setContentText("Вбудований термінал працює")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        EmbeddedProcessManager.stopAll()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START_SERVER = "com.anserver.app.START_SERVER"
        const val ACTION_STOP_SERVER = "com.anserver.app.STOP_SERVER"
        const val EXTRA_SERVER_ID = "server_id"
    }
}
