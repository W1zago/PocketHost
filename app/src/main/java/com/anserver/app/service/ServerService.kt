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
import com.anserver.app.ui.MainActivity
import com.anserver.app.util.ServerPaths
import com.anserver.app.util.UbuntuInstaller
import com.anserver.core.model.Server
import com.anserver.core.model.ServerStatus
import com.anserver.core.model.ServerType
import com.anserver.native.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class ServerService : Service() {

    private val binder = ServerBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningServers = mutableMapOf<String, Int>()
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
            try {
                android.util.Log.d("ServerService", "startServer called for: $serverId")
                val server = repository.getServer(serverId).first()
                android.util.Log.d("ServerService", "Server retrieved: ${server?.name}")

                server?.let {
                    android.util.Log.d("ServerService", "Server status: ${it.status}")
                    if (it.status == ServerStatus.STOPPED || it.status == ServerStatus.ERROR) {
                        val workingDir = ServerPaths.resolve(this@ServerService, it.workingDirectory).absolutePath
                        try {
                            val lf = File(workingDir, "server.log")
                            lf.parentFile?.mkdirs()
                            if (lf.exists()) lf.writeText("") else lf.createNewFile()
                        } catch (_: Exception) {
                        }
                        repository.updateServerStatus(serverId, ServerStatus.STARTING)
                        android.util.Log.d("ServerService", "Status updated to STARTING")
                        logToConsole(serverId, "Starting server...")
                        logToConsole(serverId, "Server name: ${it.name}")
                        logToConsole(serverId, "Server type: ${it.type}")
                        val command = buildStartCommand(it)
                        val environment = buildEnvironment(it, workingDir)

                        logToConsole(serverId, "Working directory: $workingDir")
                        logToConsole(serverId, "Command: $command")
                        logToConsole(serverId, "Environment variables: ${environment.size}")

                        if (it.type == ServerType.UBUNTU_SERVER) {
                            val installDir = File(workingDir)
                            if (!UbuntuInstaller.isInstalled(installDir)) {
                                logToConsole(serverId, "Ubuntu rootfs not found. Installing (requires internet)...")
                                try {
                                    UbuntuInstaller.install(installDir, cacheDir) { message ->
                                        logToConsole(serverId, message)
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("ServerService", "Ubuntu installation failed", e)
                                    logToConsole(serverId, "ERROR: Ubuntu installation failed: ${e.message}")
                                    repository.updateServerStatus(serverId, ServerStatus.ERROR)
                                    return@launch
                                }
                            }
                        }

                        // Check if command contains Java and verify Java availability
                        if (command.contains("java") && it.type == ServerType.MINECRAFT) {
                            val javaInfo = com.anserver.core.util.JavaUtils.checkJavaAvailability()
                            if (javaInfo == null) {
                                val error = "ERROR: Java runtime not found. Please install Termux and run: pkg install openjdk-17"
                                android.util.Log.e("ServerService", error)
                                logToConsole(serverId, error)
                                logToConsole(serverId, "Termux installation: https://f-droid.org/packages/com.termux/")
                                repository.updateServerStatus(serverId, ServerStatus.ERROR)
                                return@launch
                            } else {
                                logToConsole(serverId, "Java found: ${javaInfo.path}")
                                logToConsole(serverId, "Java version: ${javaInfo.version ?: "unknown"}")
                            }
                        }

                        // Create working directory if it doesn't exist
                        val dir = File(workingDir)
                        if (!dir.exists()) {
                            val created = dir.mkdirs()
                            android.util.Log.d("ServerService", "Created directory: $workingDir, success: $created")
                            logToConsole(serverId, "Created directory: $workingDir (success: $created)")
                        } else {
                            logToConsole(serverId, "Directory exists: $workingDir")
                        }

                        // Check if directory is writable
                        if (!dir.canWrite()) {
                            val error = "ERROR: Directory is not writable: $workingDir"
                            android.util.Log.e("ServerService", error)
                            logToConsole(serverId, error)
                            repository.updateServerStatus(serverId, ServerStatus.ERROR)
                            return@launch
                        }

                        // Create log file if it doesn't exist
                        val logFile = File(workingDir, "server.log")
                        if (!logFile.exists()) {
                            try {
                                logFile.createNewFile()
                                android.util.Log.d("ServerService", "Created log file: ${logFile.absolutePath}")
                                logToConsole(serverId, "Created log file: ${logFile.name}")
                            } catch (e: Exception) {
                                android.util.Log.e("ServerService", "Failed to create log file", e)
                                logToConsole(serverId, "ERROR creating log file: ${e.message}")
                            }
                        }

                        logToConsole(serverId, "Attempting to start process...")

                        val pid = NativeBridge.startProcess(
                            command,
                            workingDir,
                            environment.toTypedArray()
                        )

                        logToConsole(serverId, "Process start returned PID: $pid")

                        if (pid > 0) {
                            runningServers[serverId] = pid
                            repository.updateServerPid(serverId, pid)
                            repository.updateServerStatus(serverId, ServerStatus.RUNNING)
                            logToConsole(serverId, "Server started successfully with PID: $pid")
                            android.util.Log.d("ServerService", "Server started with PID: $pid")
                            if (it.type == ServerType.UBUNTU_SERVER) {
                                val motdDir = File(workingDir, "ubuntu-fs").takeIf { d -> d.exists() } ?: File(workingDir)
                                val ip = getDeviceIp()
                                val motd = com.anserver.app.util.MotdGenerator.generate(this@ServerService, motdDir, ip)
                                logRaw(serverId, "\n" + motd + "\n")
                                logRaw(serverId, "root@" + android.os.Build.MODEL.replace(" ", "-").lowercase() + ":~# \n")
                            }
                            startOutputDrain(serverId, pid)
                        } else {
                            repository.updateServerStatus(serverId, ServerStatus.ERROR)
                            val errorMsg = when (pid) {
                                -1 -> "Failed to fork process"
                                -2 -> "Failed to execute command"
                                -3 -> "Command not found"
                                else -> "Unknown error code: $pid"
                            }
                            logToConsole(serverId, "ERROR: Failed to start server. $errorMsg")
                            android.util.Log.e("ServerService", "Failed to start server. Error: $errorMsg")
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ServerService", "Error starting server", e)
                logToConsole(serverId, "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                logToConsole(serverId, "Stack trace: ${e.stackTraceToString()}")
                repository.updateServerStatus(serverId, ServerStatus.ERROR)
            }
        }
    }

    private fun stopServer(serverId: String) {
        serviceScope.launch {
            try {
                android.util.Log.d("ServerService", "stopServer called for: $serverId")
                val pid = runningServers[serverId]
                android.util.Log.d("ServerService", "PID found: $pid")

                if (pid != null) {
                    repository.updateServerStatus(serverId, ServerStatus.STOPPING)
                    logToConsole(serverId, "Stopping server with PID: $pid")

                    if (NativeBridge.stopProcess(pid)) {
                        runningServers.remove(serverId)
                        repository.updateServerStatus(serverId, ServerStatus.STOPPED)
                        logToConsole(serverId, "Server stopped successfully")
                    } else {
                        logToConsole(serverId, "Force killing server...")
                        NativeBridge.killProcess(pid)
                        runningServers.remove(serverId)
                        repository.updateServerStatus(serverId, ServerStatus.STOPPED)
                        logToConsole(serverId, "Server killed")
                    }
                } else {
                    logToConsole(serverId, "Server is not running (no PID found)")
                    repository.updateServerStatus(serverId, ServerStatus.STOPPED)
                }
            } catch (e: Exception) {
                android.util.Log.e("ServerService", "Error stopping server", e)
                logToConsole(serverId, "Error stopping server: ${e.message}")
            }
        }
    }

    private fun logToConsole(serverId: String, message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val logMessage = "[$timestamp] $message\n"

        android.util.Log.d("ServerService", "logToConsole: $logMessage")

        // Write to log file
        serviceScope.launch {
            try {
                val server = repository.getServer(serverId).first()
                server?.let {
                    val logFile = File(ServerPaths.resolve(this@ServerService, it.workingDirectory), "server.log")

                    // Create parent directories if they don't exist
                    logFile.parentFile?.mkdirs()

                    // Create file if it doesn't exist
                    if (!logFile.exists()) {
                        logFile.createNewFile()
                        android.util.Log.d("ServerService", "Created log file: ${logFile.absolutePath}")
                    }

                    logFile.appendText(logMessage)
                    android.util.Log.d("ServerService", "Wrote to log file: ${logFile.absolutePath}, size: ${logFile.length()}")
                }
            } catch (e: Exception) {
                android.util.Log.e("ServerService", "Error writing to log file", e)
                e.printStackTrace()
            }
        }
    }

    private fun logRaw(serverId: String, text: String) {
        serviceScope.launch {
            try {
                val server = repository.getServer(serverId).first()
                server?.let {
                    val logFile = File(ServerPaths.resolve(this@ServerService, it.workingDirectory), "server.log")
                    logFile.parentFile?.mkdirs()
                    if (!logFile.exists()) logFile.createNewFile()
                    logFile.appendText(text)
                    if (!text.endsWith("\n")) logFile.appendText("\n")
                }
            } catch (e: Exception) {
                android.util.Log.e("ServerService", "Error writing raw log", e)
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
        } catch (_: Exception) {
            "192.168.0.100"
        }
    }

    private fun startOutputDrain(serverId: String, pid: Int) {
        serviceScope.launch {
            while (true) {
                val alive = NativeBridge.isProcessAlive(pid)
                val output = NativeBridge.readProcessOutput(pid)
                if (output.isNotEmpty()) {
                    logRaw(serverId, output)
                }
                if (!alive && output.isEmpty()) {
                    break
                }
                delay(100)
            }
            logToConsole(serverId, "Process $pid exited")
            runningServers.remove(serverId)
            val server = repository.getServer(serverId).first()
            if (server?.status == ServerStatus.RUNNING) {
                repository.updateServerStatus(serverId, ServerStatus.STOPPED)
            }
        }
    }

    private fun buildStartCommand(server: Server): String {
        return when {
            server.config.startCommand.isNotEmpty() -> server.config.startCommand
            else -> "sh start.sh"
        }
    }

    private fun buildEnvironment(server: Server, workingDir: String): List<String> {
        val env = mutableListOf<String>()
        if (server.type == ServerType.UBUNTU_SERVER) {
            env.add("TERM=xterm-256color")
            env.add("HOME=/root")
            env.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            env.add("LANG=C.UTF-8")
            env.add("PROOT_NO_SECCOMP=1")
            env.add("PROOT_TMP_DIR=$workingDir")
            env.add("TMPDIR=$workingDir")
        }
        server.config.environment.forEach { (key, value) ->
            env.add("$key=$value")
        }
        return env
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

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
        runningServers.keys.forEach { stopServer(it) }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START_SERVER = "com.anserver.app.START_SERVER"
        const val ACTION_STOP_SERVER = "com.anserver.app.STOP_SERVER"
        const val EXTRA_SERVER_ID = "server_id"
    }
}
