package com.anserver.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.anserver.app.ANServerApplication
import com.anserver.app.R
import com.anserver.app.termux.SafHelper
import com.anserver.app.termux.TermuxBridge
import com.anserver.app.termux.TermuxPaths
import com.anserver.app.ui.MainActivity
import com.anserver.core.model.Server
import com.anserver.core.model.ServerStatus
import com.anserver.core.model.ServerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

class ServerService : Service() {

    private val binder = ServerBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningServers = mutableMapOf<String, TermuxBridge.TermuxSession>()
    private val startMutex = Mutex()
    private val executionCounter = AtomicInteger(1000)
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
            startMutex.withLock {
                try {
                    android.util.Log.d("ServerService", "startServer $serverId")
                    val server = repository.getServer(serverId).first()
                    if (server == null) {
                        android.util.Log.e("ServerService", "Server not found $serverId")
                        return@withLock
                    }
                    if (server.status == ServerStatus.RUNNING || server.status == ServerStatus.STARTING) {
                        logToConsole(serverId, "Сервер вже запущений")
                        return@withLock
                    }
                    if (runningServers.size >= 2) {
                        logToConsole(serverId, "Зупини перший сервер (максимум 2)")
                        repository.updateServerStatus(serverId, ServerStatus.ERROR)
                        return@withLock
                    }
                    if (runningServers.containsKey(serverId)) {
                        logToConsole(serverId, "Сервер вже в runningServers")
                        return@withLock
                    }

                    // SAF persisted permission check
                    val uriStr = server.effectiveWorkDirUri.ifEmpty { server.workingDirectory }
                    if (uriStr.isEmpty() || uriStr.startsWith("/")) {
                        logToConsole(serverId, "ERROR: SAF папка не вибрана. Створіть сервер заново в SAF")
                        repository.updateServerStatus(serverId, ServerStatus.ERROR)
                        return@withLock
                    }
                    val uri = try { Uri.parse(uriStr) } catch (_: Exception) { null }
                    if (uri == null) {
                        logToConsole(serverId, "ERROR: невірний URI $uriStr")
                        repository.updateServerStatus(serverId, ServerStatus.ERROR)
                        return@withLock
                    }
                    // Check persisted permission - try to find matching persisted uri
                    val hasPerm = try {
                        val persisted = applicationContext.contentResolver.persistedUriPermissions
                        persisted.any { it.uri.toString() == uriStr || uriStr.startsWith(it.uri.toString()) }
                    } catch (_: Exception) { false }
                    if (!hasPerm) {
                        // Also check via SafHelper for primary storage - if doc can't be opened, treat as revoked
                        val docCheck = DocumentFile.fromTreeUri(this@ServerService, uri)
                        if (docCheck == null || !docCheck.canWrite()) {
                            logToConsole(serverId, "ERROR: Доступ до SAF папки втрачено. Виберіть знову")
                            repository.updateServerStatus(serverId, ServerStatus.ERROR)
                            return@withLock
                        }
                    }
                    val docDir = DocumentFile.fromTreeUri(this@ServerService, uri)
                    if (docDir == null || !docDir.exists()) {
                        logToConsole(serverId, "ERROR: Папка видалена")
                        repository.updateServerStatus(serverId, ServerStatus.ERROR)
                        return@withLock
                    }
                    if (!docDir.canWrite()) {
                        logToConsole(serverId, "ERROR: Нема запису в папку")
                        repository.updateServerStatus(serverId, ServerStatus.ERROR)
                        return@withLock
                    }

                    repository.updateServerStatus(serverId, ServerStatus.STARTING)
                    logToConsole(serverId, "Запуск сервера...")
                    logToConsole(serverId, "Сервер: ${server.name} type=${server.type} port=${server.port}")
                    logToConsole(serverId, "SAF: $uriStr")
                    logToConsole(serverId, "Display: ${server.displayPath}")

                    // Ensure server.log exists
                    try {
                        val existingLog = docDir.findFile("server.log")
                        if (existingLog == null) {
                            docDir.createFile("text/plain", "server.log")
                        }
                        // Clear or rotate? We keep as is, Termux command will rotate if >5MB
                    } catch (_: Exception) {}

                    // Minecraft provisioning check: ensure jar exists if MC
                    if (server.type == ServerType.MINECRAFT) {
                        val expectedJar = when (server.config.minecraftLoader) {
                            com.anserver.core.model.MinecraftLoader.VANILLA -> "server.jar"
                            com.anserver.core.model.MinecraftLoader.PAPER -> "paper.jar"
                            com.anserver.core.model.MinecraftLoader.PURPUR -> "purpur.jar"
                            com.anserver.core.model.MinecraftLoader.FABRIC -> "fabric-server-launch.jar"
                            else -> "paper.jar"
                        }
                        val hasJar = docDir.listFiles().any { it.name == expectedJar || it.name == "server.jar" || it.name == "paper.jar" }
                        if (!hasJar) {
                            logToConsole(serverId, "Попередження: jar $expectedJar не знайдено, спроба завантаження при старті через Termux/Provisioner")
                        }
                    }

                    // Space check >2GB
                    try {
                        val stat = android.os.StatFs(applicationContext.filesDir.absolutePath)
                        if (stat.availableBytes < 2L * 1024 * 1024 * 1024) {
                            logToConsole(serverId, "Попередження: мало місця <2GB")
                        }
                    } catch (_: Exception) {}

                    val termuxPath = server.termuxPath.ifEmpty { TermuxPaths.safToTermux(this@ServerService, uri) }
                    val execId = executionCounter.incrementAndGet()
                    val session = TermuxBridge.TermuxSession(server.id, execId, uriStr, termuxPath)
                    runningServers[serverId] = session
                    repository.updateTermuxExecutionId(serverId, execId)

                    // Termux wake-lock
                    TermuxBridge.sendRawCommand(this@ServerService, termuxPath, "termux-wake-lock 2>/dev/null || true")

                    val ok = TermuxBridge.start(this@ServerService, server.copy(termuxPath = termuxPath), true)
                    if (!ok) {
                        logToConsole(serverId, "ERROR: Не вдалося відправити RUN_COMMAND. Перевір Termux.")
                        runningServers.remove(serverId)
                        repository.updateServerStatus(serverId, ServerStatus.ERROR)
                        return@withLock
                    }
                    repository.updateServerStatus(serverId, ServerStatus.RUNNING)
                    logToConsole(serverId, "Сервер запущено (Termux) exec=$execId path=$termuxPath")
                    startLivenessPoll(serverId, session)
                    if (server.type == ServerType.UBUNTU_SERVER) {
                        val ip = getDeviceIp()
                        logRaw(serverId, "\nUbuntu старт через proot-distro. IP: $ip\n")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ServerService", "startServer error", e)
                    logToConsole(serverId, "EXCEPTION: ${e.message}")
                    try { repository.updateServerStatus(serverId, ServerStatus.ERROR) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun stopServer(serverId: String) {
        serviceScope.launch {
            startMutex.withLock {
                try {
                    android.util.Log.d("ServerService", "stopServer $serverId")
                    val session = runningServers[serverId]
                    if (session == null) {
                        logToConsole(serverId, "Сервер не запущений")
                        repository.updateServerStatus(serverId, ServerStatus.STOPPED)
                        return@withLock
                    }
                    repository.updateServerStatus(serverId, ServerStatus.STOPPING)
                    logToConsole(serverId, "Зупинка сервера...")

                    TermuxBridge.stop(this@ServerService, session)
                    // Additional fifo cleanup via Termux
                    TermuxBridge.sendRawCommand(this@ServerService, session.termuxPath, "rm -f input.fifo; pkill -f java 2>/dev/null; echo stopped >> server.log")

                    runningServers.remove(serverId)
                    repository.updateTermuxExecutionId(serverId, null)
                    repository.updateServerStatus(serverId, ServerStatus.STOPPED)
                    logToConsole(serverId, "Сервер зупинено")
                    // Wake unlock if no servers
                    if (runningServers.isEmpty()) {
                        TermuxBridge.sendRawCommand(this@ServerService, session.termuxPath, "termux-wake-unlock 2>/dev/null || true")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ServerService", "stop error", e)
                    logToConsole(serverId, "Error stopping: ${e.message}")
                }
            }
        }
    }

    private fun logToConsole(serverId: String, message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val line = "[$timestamp] $message\n"
        serviceScope.launch {
            try {
                val server = repository.getServer(serverId).first() ?: return@launch
                val uriStr = server.effectiveWorkDirUri.ifEmpty { server.workingDirectory }
                if (uriStr.isEmpty()) return@launch
                val dirUri = Uri.parse(uriStr)
                val dir = DocumentFile.fromTreeUri(this@ServerService, dirUri) ?: return@launch
                var logFile = dir.findFile("server.log")
                if (logFile == null) logFile = dir.createFile("text/plain", "server.log")
                logFile?.let {
                    try {
                        contentResolver.openOutputStream(it.uri, "wa")?.use { out -> out.write(line.toByteArray()) }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                android.util.Log.e("ServerService", "log write fail", e)
            }
        }
    }

    private fun logRaw(serverId: String, text: String) {
        serviceScope.launch {
            try {
                val server = repository.getServer(serverId).first() ?: return@launch
                val uriStr = server.effectiveWorkDirUri.ifEmpty { server.workingDirectory }
                val dirUri = Uri.parse(uriStr)
                val dir = DocumentFile.fromTreeUri(this@ServerService, dirUri) ?: return@launch
                var logFile = dir.findFile("server.log")
                if (logFile == null) logFile = dir.createFile("text/plain", "server.log")
                logFile?.let {
                    contentResolver.openOutputStream(it.uri, "wa")?.use { out ->
                        out.write(text.toByteArray())
                        if (!text.endsWith("\n")) out.write("\n".toByteArray())
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ServerService", "logRaw fail", e)
            }
        }
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

    private fun startLivenessPoll(serverId: String, session: TermuxBridge.TermuxSession) {
        serviceScope.launch {
            var deadCount = 0
            while (runningServers.containsKey(serverId)) {
                delay(5000)
                // Heuristic: if log file not growing and ps check would be needed, we currently just check if SAF still exists
                try {
                    val server = repository.getServer(serverId).first()
                    if (server == null || server.status != ServerStatus.RUNNING) break
                    val uri = Uri.parse(server.effectiveWorkDirUri)
                    val dir = DocumentFile.fromTreeUri(this@ServerService, uri)
                    if (dir == null || !dir.exists()) {
                        deadCount++
                        if (deadCount >= 3) {
                            logToConsole(serverId, "Process exited (папка недоступна)")
                            runningServers.remove(serverId)
                            repository.updateServerStatus(serverId, ServerStatus.STOPPED)
                            break
                        }
                    } else {
                        deadCount = 0
                    }
                    // Optional: send ps check via TermuxBridge (async, result via ResultsService not yet parsed)
                    // TermuxBridge.sendRawCommand(this@ServerService, session.termuxPath, "ps aux | grep -q '[j]ava' || echo dead >> server.log")
                } catch (_: Exception) {}
            }
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, ANServerApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("PocketHost")
            .setContentText("Server service is running")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        // Stop all via copy to avoid concurrent modification
        val toStop = runningServers.keys.toList()
        toStop.forEach { id ->
            val session = runningServers[id]
            if (session != null) TermuxBridge.stop(this, session)
        }
        runningServers.clear()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START_SERVER = "com.anserver.app.START_SERVER"
        const val ACTION_STOP_SERVER = "com.anserver.app.STOP_SERVER"
        const val EXTRA_SERVER_ID = "server_id"
    }
}
