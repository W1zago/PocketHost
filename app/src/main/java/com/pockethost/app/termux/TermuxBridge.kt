package com.pockethost.app.termux

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import com.pockethost.app.embedded.EmbeddedPaths
import com.pockethost.app.embedded.EmbeddedProcessManager
import com.pockethost.core.model.Server

/**
 * Legacy compatibility bridge. Previously sent Intent com.termux.RUN_COMMAND
 * to external Termux (reports and "control" permission requests).
 *
 * Now delegates to the BUILT-IN terminal - no Intents, no permissions.
 * Signatures kept so old code does not break.
 */
@Deprecated("Use EmbeddedProcessManager directly")
object TermuxBridge {

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
        return "java -Xmx${maxM}M -Xms${minM}M -XX:+UseSerialGC -Djava.awt.headless=true -Xss256k -jar \"$jarName\" nogui --nojline"
    }

    fun buildUbuntuCommand(termuxPath: String): String = "sh -i"

    fun buildStopCommand(termuxPath: String): String = "stop"

    private fun guessJarName(server: Server): String {
        val loader = server.config.minecraftLoader
        return when (loader) {
            com.pockethost.core.model.MinecraftLoader.VANILLA -> "server.jar"
            com.pockethost.core.model.MinecraftLoader.FORGE -> "forge.jar"
            com.pockethost.core.model.MinecraftLoader.FABRIC -> "fabric-server-launch.jar"
            com.pockethost.core.model.MinecraftLoader.NEOFORGE -> "neoforge.jar"
            com.pockethost.core.model.MinecraftLoader.PAPER -> "paper.jar"
            com.pockethost.core.model.MinecraftLoader.SPIGOT -> "spigot.jar"
            com.pockethost.core.model.MinecraftLoader.BUKKIT -> "craftbukkit.jar"
            com.pockethost.core.model.MinecraftLoader.PURPUR -> "purpur.jar"
            null -> "paper.jar"
        }
    }

    fun start(context: Context, server: Server, isBackground: Boolean = true, pendingIntent: PendingIntent? = null): Boolean {
        // Delegates to the built-in terminal via ServerService logic? Only fallback here:
        // direct launch is impossible without java/jar resolution, so return true
        // and ServerService performs the real launch via EmbeddedProcessManager.
        Log.d("TermuxBridge", "legacy start() -> delegated to embedded, server=${server.id}")
        return true
    }

    fun stop(context: Context, session: TermuxSession): Boolean {
        return try {
            EmbeddedProcessManager.stop(session.serverId)
            true
        } catch (e: Exception) {
            Log.e("TermuxBridge", "legacy stop failed", e)
            false
        }
    }

    fun sendInput(context: Context, session: TermuxSession, data: String) {
        try {
            val ok = EmbeddedProcessManager.sendInput(session.serverId, data)
            if (!ok) {
                // fallback: append to server.log of the real folder so the command is not lost
                if (session.termuxPath.isNotEmpty()) {
                    try {
                        EmbeddedProcessManager.appendLogToDir(java.io.File(session.termuxPath), data)
                    } catch (_: Exception) {}
                }
            }
            Log.d("TermuxBridge", "legacy sendInput delegated to embedded")
        } catch (e: Exception) {
            Log.e("TermuxBridge", "legacy sendInput failed", e)
        }
    }

    fun sendRawCommand(context: Context, termuxPath: String, bashCommand: String, pendingIntent: PendingIntent? = null) {
        try {
            // termuxPath in legacy = /data/data/com.termux/... or an absolute path.
            // Try to find serverId by folder match, otherwise just log.
            Log.d("TermuxBridge", "legacy sendRawCommand ignored (embedded): $bashCommand")
        } catch (e: Exception) {
            Log.e("TermuxBridge", "sendRawCommand failed", e)
        }
    }

    fun isAliveCheck(context: Context, termuxPath: String, jarHint: String = "java"): String {
        return "embedded"
    }

    /** Helper: build a session from embedded data */
    fun sessionFor(context: Context, serverId: String, workDirUri: String): TermuxSession {
        val dir = EmbeddedPaths.resolveWorkDir(context, serverId, workDirUri)
        return TermuxSession(serverId, -1, workDirUri, dir.absolutePath)
    }
}
