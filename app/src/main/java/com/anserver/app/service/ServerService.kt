package com.anserver.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import com.anserver.app.ANServerApplication
import com.anserver.app.R
import com.anserver.app.embedded.EmbeddedBootstrap
import com.anserver.app.embedded.EmbeddedJava
import com.anserver.app.embedded.EmbeddedPaths
import com.anserver.app.embedded.EmbeddedProcessManager
import com.anserver.app.jvm.InProcessServer
import com.anserver.app.ui.MainActivity
import com.anserver.app.util.ServerProperties
import com.anserver.core.model.ServerStatus
import com.anserver.core.model.ServerType
import com.anserver.core.util.NetworkUtils
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
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
    private val workDirCache = ConcurrentHashMap<String, File>()
    // Сервери що зараз проходять довге завантаження (jar/JRE) поза локом.
    // Захищено startMutex.
    private val startingIds = mutableSetOf<String>()
    // Уніфіковано з MainActivity/CreateServerActivity: безпечний lazy-доступ.
    // Service не може finish(), тому fallback — локальний репозиторій.
    private val repository by lazy {
        runCatching { ANServerApplication.serverRepository }.getOrElse { e ->
            Log.e("ServerService", "repo not ready, using fallback", e)
            com.anserver.core.repository.ServerRepository(this@ServerService)
        }
    }

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
                    Log.d("ServerService", "startServer embedded $serverId")
                    if (startingIds.contains(serverId)) {
                        log(serverId, "Сервер вже запускається")
                        return@withLock null
                    }
                    val server = repository.getServer(serverId).first()
                    if (server == null) {
                        Log.e("ServerService", "Server not found $serverId")
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
                    // C4: 1 JVM на процес — другий Minecraft з іншим classpath не стартує.
                    // Чесна відмова замість мовчазного FAILED_DEFINITIVE глибоко в InProcessServer.
                    if (server.type == ServerType.MINECRAFT) {
                        val (canStart, reason) = try {
                            EmbeddedProcessManager.canStartMinecraft(serverId)
                        } catch (_: Exception) { true to "" }
                        if (!canStart) {
                            log(serverId, reason)
                            Log.w("ServerService", "canStartMinecraft denied $serverId: $reason")
                            try {
                                android.widget.Toast.makeText(
                                    this@ServerService, reason, android.widget.Toast.LENGTH_LONG
                                ).show()
                            } catch (_: Exception) {}
                            serviceScope.launch { repository.updateServerStatus(serverId, ServerStatus.ERROR) }
                            return@withLock null
                        }
                    }
                    if (EmbeddedProcessManager.isRunning(serverId)) {
                        log(serverId, "Сервер вже запущений")
                        // FAIL 17:44: reserve мовчки блокував рестарт ("Сервер вже
                        // запущений", порт порожній). Факти в logcat показують чому.
                        try {
                            Log.i(
                                "ServerService",
                                "start reserve blocked $serverId facts=${InProcessServer.describeFacts()}"
                            )
                        } catch (_: Exception) {}
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
                    Log.e("ServerService", "startServer reserve error", e)
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
                // Баг A: stale isRunning після попереднього стопа (Done без Closing)
                // блокував рестарт в тому ж процесі. Факти вже виправлені в
                // InProcessServer (свіжий Done + порт-first), тут лише діагностика.
                try {
                    val alive = EmbeddedProcessManager.isRunning(serverId) ||
                        InProcessServer.isRunning(serverId)
                    val portFree = try {
                        NetworkUtils.isPortAvailable(server.port)
                    } catch (_: Exception) { true }
                    Log.i(
                        "ServerService",
                        "startMinecraft pre-check $serverId alive=$alive portFree=$portFree status=${server.status} facts=${InProcessServer.describeFacts()}"
                    )
                } catch (_: Exception) {}
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
                    val stat = StatFs(reservedWorkDir.absolutePath)
                    if (stat.availableBytes < 512L * 1024 * 1024) {
                        log(serverId, "Попередження: мало місця <512MB")
                    }
                } catch (_: Exception) {}

                when (server.type) {
                    ServerType.MINECRAFT -> startMinecraft(serverId, reservedWorkDir)
                    ServerType.UBUNTU_SERVER -> startUbuntu(serverId, reservedWorkDir)
                }
            } catch (e: Exception) {
                Log.e("ServerService", "startServer error", e)
                log(serverId, "EXCEPTION: ${e.message}")
                try { repository.updateServerStatus(serverId, ServerStatus.ERROR) } catch (_: Exception) {}
            } finally {
                startMutex.withLock { startingIds.remove(serverId) }
            }
        }
    }

    private suspend fun startMinecraft(serverId: String, workDir: File) {
        val server = repository.getServer(serverId).first() ?: return

        // eula + properties у внутрішній папці.
        // online-mode / view-distance беремо зі збереженого конфігу ПРИ КОЖНОМУ
        // старті (патчимо існуючий файл, решту ключів не чіпаємо) — так повзунки
        // працюють і для серверів, створених до їх появи.
        try {
            val eula = File(workDir, "eula.txt")
            if (!eula.exists()) eula.writeText("#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://account.mojang.com/documents/minecraft_eula).\neula=true")
            patchServerProperties(
                workDir,
                server.port,
                server.config.effectiveOnlineMode,
                server.config.effectiveViewDistance,
                server.name
            )
        } catch (_: Exception) {}

        // Provision jar у внутрішню папку (звичайний File I/O, без SAF)
        // Default 1.20.4: вбудований JRE17 запускає лише Minecraft <=1.20.4
        // (1.20.5+ потребує Java 21 — див. advisory нижче).
        val version = server.config.minecraftVersion ?: "1.20.4"
        val loader = server.config.minecraftLoader
        if (EmbeddedJava.minJavaForMinecraft(version) > 17) {
            log(serverId, "УВАГА: $version потребує Java 21+, а вбудований JRE — Java 17. Старт швидше за все не вдасться; рекомендовано 1.20.4 (або імпортуй JRE 21 вручну в tools/jre21/ через Files).")
        }
        // Зайнятий порт: якщо це НАШ живий сервер — no-op, інакше ERROR.
        // Без цього повторний Start запускав другий Paper поверх залоченого
        // світу -> OverlappingFileLockException.
        try {
            if (!NetworkUtils.isPortAvailable(server.port)) {
                if (EmbeddedProcessManager.isRunning(serverId) ||
                    InProcessServer.isRunning(serverId)
                ) {
                    log(serverId, "Сервер вже запущений (порт ${server.port} зайнятий ним) — повторний старт пропущено")
                    try {
                        val cur = repository.getServer(serverId).first()
                        if (cur?.status != ServerStatus.RUNNING) {
                            repository.updateServerStatus(serverId, ServerStatus.RUNNING)
                        }
                    } catch (_: Exception) {}
                    return
                }
                log(serverId, "ERROR: порт ${server.port} вже зайнятий іншим процесом. Зміни порт у налаштуваннях сервера.")
                repository.updateServerStatus(serverId, ServerStatus.ERROR)
                return
            }
        } catch (_: Exception) {}
        val manager = MinecraftServerManager(workDir.parent ?: workDir.absolutePath)
        val foundBefore = EmbeddedProcessManager.findExistingJar(
            workDir, EmbeddedProcessManager.guessJarName(loader?.name)
        )
        // Наявність файлу ≠ цілісність: обірваний jar чистимо через provision заново.
        val hasJarBefore = foundBefore != null &&
            MinecraftServerManager.isValidServerJar(File(workDir, foundBefore))
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
        // targetSdk 29+ (error=13). Основний шлях — in-process JVM як у MojoLauncher
        // (dlopen libjvm.so + JNI_CreateJavaVM, без exec взагалі).
        // Legacy exec через системний лінкер лишено як fallback для retryable-помилок.
        val jarFile = File(workDir, jar)
        val inProcessResult = try {
            log(serverId, "Старт in-process JVM (Mojo-підхід, без exec)...")
            InProcessServer.start(
                this@ServerService, serverId, jarFile, workDir,
                server.config.maxMemory, server.config.minMemory,
                server.config.javaArgs, version,
                { msg -> log(serverId, msg) },
                server.port,
                javaPath
            )
        } catch (e: Exception) {
            Log.e("ServerService", "in-process start failed, fallback to exec", e)
            log(serverId, "In-process старт не вдався (${e.message}), пробую legacy exec...")
            InProcessServer.StartResult.FAILED_RETRYABLE
        }
        if (inProcessResult == InProcessServer.StartResult.ALREADY_RUNNING) {
            // No-op: живий сервер вже є (Paperclip main повернувся, але факти
            // живі). Статус лишається RUNNING, другий Paper не стартуємо.
            log(serverId, "Сервер вже запущений — повторний старт пропущено")
            try {
                val cur = repository.getServer(serverId).first()
                if (cur?.status != ServerStatus.RUNNING) {
                    repository.updateServerStatus(serverId, ServerStatus.RUNNING)
                }
            } catch (_: Exception) {}
            startLivenessPoll(serverId)
            return
        }
        if (inProcessResult == InProcessServer.StartResult.STARTED) {
            repository.updateTermuxExecutionId(serverId, (System.currentTimeMillis() % Int.MAX_VALUE).toInt())
            repository.updateServerStatus(serverId, ServerStatus.RUNNING)
            log(serverId, "Сервер запущено (in-process JVM) jar=$jar")
            startLivenessPoll(serverId)
            // Швидкий крах (несумісна Java, кривий світ, повторний бінд порту):
            // не висіти в RUNNING — причина вже в логу вище, фіксуємо ERROR.
            // Paperclip-вікно враховано: isRunning() тепер факти-залежний
            // (порт + Done-маркер), тож живий сервер сюди не потрапить.
            // Затримку збільшено до 15с — повільні телефони довго вантажать світ.
            serviceScope.launch {
                delay(15000)
                try {
                    val s = repository.getServer(serverId).first()
                    if (s?.status == ServerStatus.RUNNING && !EmbeddedProcessManager.isRunning(serverId)) {
                        // Подвійна перевірка фактів перед ERROR: порт або Done
                        // означають "живий, просто повільний старт".
                        var looksAlive = false
                        try {
                            if (!NetworkUtils.isPortAvailable(server.port)) {
                                looksAlive = true
                            }
                        } catch (_: Exception) {}
                        if (!looksAlive) {
                            try {
                                val lf = File(workDir, "server.log")
                                if (lf.isFile) {
                                    val len = lf.length()
                                    val tailLen = if (len > 64 * 1024) 64 * 1024 else len.toInt()
                                    RandomAccessFile(lf, "r").use { raf ->
                                        raf.seek(len - tailLen)
                                        val buf = ByteArray(tailLen)
                                        var tot = 0
                                        while (tot < tailLen) {
                                            val r = raf.read(buf, tot, tailLen - tot)
                                            if (r <= 0) break
                                            tot += r
                                        }
                                        val tail = String(buf, 0, tot)
                                        // Баг A: тільки СВІЖИЙ Done (після останнього
                                        // Closing/stop/смерті), інакше stale Done
                                        // попереднього бута маскує крах як "живий".
                                        val donePos = tail.lastIndexOf("Done (")
                                        fun lastOf(vararg ms: String): Int {
                                            var b = -1
                                            for (m in ms) {
                                                val p = tail.lastIndexOf(m)
                                                if (p > b) b = p
                                            }
                                            return b
                                        }
                                        val stopPos = lastOf(
                                            "Stopping the server", "Stopping server",
                                            "Saving worlds", "All dimensions are saved",
                                            "Closing Server", "Server stopped",
                                            "[зупинено користувачем]",
                                            "[очікування сервера завершено"
                                        )
                                        val deathPos = tail.lastIndexOf("[процес завершено")
                                        if (donePos >= 0 && donePos > stopPos && donePos > deathPos &&
                                            tail.indexOf("For help, type", donePos) > stopPos
                                        ) {
                                            looksAlive = true
                                        } else {
                                            val startPos = lastOf(
                                                "Starting org.bukkit",
                                                "Starting minecraft server",
                                                "Loading libraries",
                                                "This server is running"
                                            )
                                            if (startPos >= 0 && startPos > stopPos &&
                                                startPos > deathPos
                                            ) looksAlive = true
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                        if (!looksAlive) {
                            // STOP->START FIX 2026-09-07: чесна відмова -11 (старий
                            // потік ще виходив, новий Paper не стартував) — це не
                            // крах: STOPPED + просимо ретрай, а не ERROR.
                            var sawBusyPrev = false
                            try {
                                val lf = File(workDir, "server.log")
                                if (lf.isFile && lf.length() > 0) {
                                    val n = if (lf.length() > 8192) 8192 else lf.length().toInt()
                                    RandomAccessFile(lf, "r").use { raf ->
                                        raf.seek(lf.length() - n)
                                        val b = ByteArray(n)
                                        var t = 0
                                        while (t < n) {
                                            val r = raf.read(b, t, n - t)
                                            if (r <= 0) break
                                            t += r
                                        }
                                        if (String(b, 0, t).contains("код -11")) sawBusyPrev = true
                                    }
                                }
                            } catch (_: Exception) {}
                            if (sawBusyPrev) {
                                log(serverId, "Попередній потік ще завершувався — тисни Start ще раз.")
                                repository.updateServerStatus(serverId, ServerStatus.STOPPED)
                            } else {
                                log(serverId, "Сервер завершився одразу після старту — дивись причину вище (часто: зайнятий порт або несумісна Java).")
                                repository.updateServerStatus(serverId, ServerStatus.ERROR)
                            }
                        } else {
                            log(serverId, "Сервер ще стартує (повільний пристрій) — чекаю далі...")
                        }
                    }
                } catch (_: Exception) {}
            }
            return
        }
        if (inProcessResult == InProcessServer.StartResult.FAILED_DEFINITIVE) {
            // Битий jar / стара Java / зайнята JVM — legacy exec не виправить.
            repository.updateServerStatus(serverId, ServerStatus.ERROR)
            return
        }

        log(serverId, "Fallback: legacy exec через системний лінкер...")
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

    /**
     * Створює server.properties якщо нема, інакше точково оновлює ключі,
     * якими керує UI (online-mode, view-distance, server-port).
     * Решту файла (motd гравця, сид світу тощо) не чіпає.
     * Логіка винесена в [com.anserver.app.util.ServerProperties].
     */
    private fun patchServerProperties(
        workDir: File,
        port: Int,
        onlineMode: Boolean,
        viewDistance: Int,
        serverName: String
    ) {
        ServerProperties.patch(
            workDir, port, onlineMode, viewDistance, serverName
        )
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
            // Ввічливий stop через stdin + очікування ПОЗА локом щоб не блокувати start().
            // In-process серверу треба більше часу (збереження світу + System.exit
            // пастка + Paperclip-паркування), тому чекаємо до 60с.
            // "stop" шлемо за фактом живого JVM (isRunning факти-залежний),
            // інакше консоль відмовляла в Paperclip-вікні.
            val wasRunning = EmbeddedProcessManager.isRunning(serverId)
            if (wasRunning) {
                try {
                    Log.i(
                        "ServerService",
                        "stopServer: sending stop to $serverId facts=${InProcessServer.describeFacts()}"
                    )
                    // FAIL 17:44: phase1 МАЄ ставити stopRequested одразу.
                    // sendInput("stop") сам флага не ставив -> 60с stopReq=false,
                    // гілка stopRequested+portFree мертва, fast-path тримав alive=true.
                    try {
                        InProcessServer.requestStop(serverId)
                    } catch (_: Exception) {}
                    EmbeddedProcessManager.sendInput(serverId, "stop")
                    var waited = 0
                    while (waited < 60000 && EmbeddedProcessManager.isRunning(serverId)) {
                        delay(1000)
                        waited += 1000
                        // Діагностика кожні 5с (Баг B + FAIL 17:44): факти з
                        // donePos/stopPos щоб Observer бачив Closing після Done,
                        // а не тільки "alive=true".
                        if (waited % 5000 == 0) {
                            try {
                                Log.i(
                                    "ServerService",
                                    "stopServer wait ${waited}ms $serverId alive=${EmbeddedProcessManager.isRunning(serverId)} facts=${InProcessServer.describeFacts()}"
                                )
                            } catch (_: Exception) {}
                        }
                    }
                    if (EmbeddedProcessManager.isRunning(serverId)) {
                        log(serverId, "Сервер не відповів на stop за 60с — чекаю далі у фоні (світ може зберігатись)")
                        try {
                            Log.w("ServerService", "stopServer timeout $serverId still alive after 60s")
                        } catch (_: Exception) {}
                    } else {
                        try {
                            Log.i("ServerService", "stopServer graceful in ${waited}ms $serverId")
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
            startMutex.withLock {
                try {
                    Log.d("ServerService", "stopServer embedded $serverId")
                    if (!EmbeddedProcessManager.isRunning(serverId)) {
                        // Сюди потрапляємо і після ввічливого стопа в phase1
                        // (порт вільний + Closing): це успіх, а не "не запущений".
                        if (wasRunning) {
                            log(serverId, "Сервер зупинено")
                        } else {
                            log(serverId, "Сервер не запущений")
                        }
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
                    Log.e("ServerService", "stop error", e)
                    log(serverId, "Error stopping: ${e.message}")
                }
            }
        }
    }

    private fun log(serverId: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
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
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) return host
                    }
                }
            }
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress ?: continue
                }
            }
            "192.168.0.100"
        } catch (_: Exception) { "192.168.0.100" }
    }

    private fun startLivenessPoll(serverId: String) {
        // STOP->START FIX 2026-09-07: старий poll вбивав свіжий старт.
        // Два захисти: (а) stale-poll guard за seq — poll попереднього бута
        // мовчки виходить коли seq змінився (новий бут моніторить свій poll);
        // (б) grace 30с — свіжий Paper пише маркери/биндить порт до ~30с, а в
        // перші секунди фактів ще нема (нема Done/порту) — не вбивати.
        // Без цього: тиша нового старту 10с -> "Процес завершено" -> STOPPED.
        val mySeq = try { InProcessServer.currentSeq() } catch (_: Exception) { -1L }
        serviceScope.launch {
            var emptyCount = 0
            var ticks = 0
            while (true) {
                delay(5000)
                try {
                    val server = repository.getServer(serverId).first()
                    if (server == null || server.status != ServerStatus.RUNNING) break
                    try {
                        if (InProcessServer.currentSeq() != mySeq) break
                    } catch (_: Exception) {}
                    ticks++
                    if (!EmbeddedProcessManager.isRunning(serverId)) {
                        if (ticks <= 6) continue
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
        // H6: stopAll ДО cancel — зупинка процесів має встигнути дописати логи/
        // відправити stop до скасування scope (інакше фонові launch вбиваються раніше).
        try { EmbeddedProcessManager.stopAll() } catch (_: Exception) {}
        try { serviceScope.cancel() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START_SERVER = "com.anserver.app.START_SERVER"
        const val ACTION_STOP_SERVER = "com.anserver.app.STOP_SERVER"
        const val EXTRA_SERVER_ID = "server_id"
    }
}
