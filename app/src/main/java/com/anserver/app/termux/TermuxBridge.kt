package com.anserver.app.termux

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
import android.util.Log
import com.anserver.core.model.Server

object TermuxBridge {

    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

    data class TermuxSession(
        val serverId: String,
        val executionId: Int,
        val workDirUri: String,
        val termuxPath: String
    )

    fun buildMinecraftCommand(server: Server, termuxPath: String): String {
        val jarName = guessJarName(server)
        val maxM = server.config.maxMemory
        val minM = server.config.minMemory
        // logrotate 5MB keep 1M, direct SAF fallback via input.txt + fifo via tail -F
        return """
            cd "$termuxPath" || exit 1
            [ -d ${'$'}HOME/storage ] || termux-setup-storage
            command -v java >/dev/null 2>&1 || pkg install -y openjdk-21
            [ -f "$jarName" ] || { echo "ERROR jar missing $jarName" | tee -a server.log; exit 1; }
            [ -f server.log ] && [ ${'$'}(stat -c%s server.log 2>/dev/null || echo 0) -gt 5242880 ] && tail -c 1048576 server.log > server.log.tmp && mv server.log.tmp server.log || true
            mkfifo -m 600 input.fifo 2>/dev/null || true
            touch input.txt
            trap 'rm -f input.fifo' EXIT
            tail -q -n 0 -F input.txt input.fifo 2>/dev/null | java -Xmx${maxM}M -Xms${minM}M -XX:+UseSerialGC -Djava.awt.headless=true -Xss256k -jar "$jarName" nogui 2>&1 | tee -a server.log
        """.trimIndent()
    }

    fun buildUbuntuCommand(termuxPath: String): String {
        return """
            cd "$termuxPath" || exit 1
            [ -d ${'$'}HOME/storage ] || termux-setup-storage
            command -v proot-distro >/dev/null 2>&1 || pkg install -y proot-distro
            proot-distro list 2>/dev/null | grep -q "^ubuntu" || proot-distro install ubuntu 2>&1 | tee -a server.log
            mkfifo -m 600 input.fifo 2>/dev/null || true
            touch input.txt
            trap 'rm -f input.fifo' EXIT
            tail -q -n 0 -F input.txt input.fifo 2>/dev/null | proot-distro login ubuntu --shared-tmp -- bash -i 2>&1 | tee -a server.log
        """.trimIndent()
    }

    fun buildStopCommand(termuxPath: String): String {
        return """
            pkill -f "java.*\.jar" 2>/dev/null || true
            proot-distro list 2>/dev/null | grep -q ubuntu && proot-distro kill ubuntu 2>/dev/null || true
            rm -f "$termuxPath/input.fifo" 2>/dev/null || true
            echo "Stopped" | tee -a "$termuxPath/server.log"
        """.trimIndent()
    }

    private fun guessJarName(server: Server): String {
        val loader = server.config.minecraftLoader
        return when (loader) {
            com.anserver.core.model.MinecraftLoader.VANILLA -> "server.jar"
            com.anserver.core.model.MinecraftLoader.FORGE -> "forge.jar"
            com.anserver.core.model.MinecraftLoader.FABRIC -> "fabric-server-launch.jar"
            com.anserver.core.model.MinecraftLoader.NEOFORGE -> "forge.jar"
            com.anserver.core.model.MinecraftLoader.PAPER -> "paper.jar"
            com.anserver.core.model.MinecraftLoader.SPIGOT -> "spigot.jar"
            com.anserver.core.model.MinecraftLoader.BUKKIT -> "craftbukkit.jar"
            com.anserver.core.model.MinecraftLoader.PURPUR -> "purpur.jar"
            null -> "paper.jar"
        }
    }

    fun start(context: Context, server: Server, isBackground: Boolean = true, pendingIntent: PendingIntent? = null): Boolean {
        return try {
            val termuxPath = server.termuxPath.ifEmpty { TermuxPaths.safStringToTermux(context, server.workDirUri) }
            val fullCommand = when (server.type) {
                com.anserver.core.model.ServerType.MINECRAFT -> buildMinecraftCommand(server, termuxPath)
                com.anserver.core.model.ServerType.UBUNTU_SERVER -> buildUbuntuCommand(termuxPath)
            }
            val intent = Intent(ACTION_RUN_COMMAND).apply {
                component = ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", fullCommand))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", termuxPath)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true) // always true for fifo stability
                pendingIntent?.let { putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d("TermuxBridge", "start sent for ${server.id} -> $termuxPath background=$isBackground")
            true
        } catch (e: Exception) {
            Log.e("TermuxBridge", "start failed", e)
            false
        }
    }

    fun stop(context: Context, session: TermuxSession): Boolean {
        return try {
            val cmd = buildStopCommand(session.termuxPath)
            val intent = Intent(ACTION_RUN_COMMAND).apply {
                component = ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", cmd))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", session.termuxPath)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            context.startService(intent)
            true
        } catch (e: Exception) {
            Log.e("TermuxBridge", "stop failed", e)
            false
        }
    }

    fun sendInput(context: Context, session: TermuxSession, data: String) {
        val raw = if (!data.endsWith("\n")) "$data\n" else data
        var runCommandOk = false
        // Try RUN_COMMAND first (needs permission)
        try {
            val b64 = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val cmd = "echo '$b64' | base64 -d > '${session.termuxPath}/input.fifo' 2>/dev/null || echo '$b64' | base64 -d >> '${session.termuxPath}/input.txt'"
            val intent = Intent(ACTION_RUN_COMMAND).apply {
                component = ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", cmd))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", session.termuxPath)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            context.startService(intent)
            runCommandOk = true
            Log.d("TermuxBridge", "sendInput via RUN_COMMAND")
        } catch (e: SecurityException) {
            Log.w("TermuxBridge", "RUN_COMMAND denied, fallback to SAF", e)
        } catch (e: Exception) {
            Log.e("TermuxBridge", "sendInput RUN_COMMAND failed", e)
        }
        // Fallback / parallel: direct SAF write to input.txt (works without RUN_COMMAND, needs SAF persisted)
        try {
            val dirUri = android.net.Uri.parse(session.workDirUri)
            val dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, dirUri)
            if (dir != null && dir.canWrite()) {
                var file = dir.findFile("input.txt")
                if (file == null) file = dir.createFile("text/plain", "input.txt")
                if (file != null) {
                    context.contentResolver.openOutputStream(file.uri, "wa")?.use { out ->
                        out.write(raw.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    Log.d("TermuxBridge", "sendInput via SAF input.txt fallback")
                    runCommandOk = true
                }
            }
        } catch (e: Exception) {
            Log.e("TermuxBridge", "SAF fallback failed", e)
        }
        if (!runCommandOk) {
            Log.e("TermuxBridge", "sendInput both paths failed")
        }
    }

    fun sendRawCommand(context: Context, termuxPath: String, bashCommand: String, pendingIntent: PendingIntent? = null) {
        try {
            val intent = Intent(ACTION_RUN_COMMAND).apply {
                component = ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", bashCommand))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", termuxPath)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                pendingIntent?.let { putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", it) }
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e("TermuxBridge", "sendRawCommand failed", e)
        }
    }

    fun isAliveCheck(context: Context, termuxPath: String, jarHint: String = "java"): String {
        // Command to check alive: ps | grep jarHint; caller polls via ResultsService or direct? For now build command string.
        return "ps aux 2>/dev/null | grep -q \"[j]ava.*$jarHint\" && echo alive || echo dead"
    }
}
