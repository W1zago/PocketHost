package com.anserver.app.termux

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import com.anserver.app.embedded.EmbeddedPaths
import com.anserver.app.embedded.EmbeddedProcessManager
import com.anserver.core.model.Server

/**
 * Legacy міст сумісності. Раніше слало Intent com.termux.RUN_COMMAND
 * у зовнішній Termux (звідси і запит дозволу "керування").
 *
 * Тепер делегує у ВБУДОВАНИЙ термінал - жодних Intent, жодних дозволів.
 * Сигнатури збережено щоб старий код не ламався.
 */
@Deprecated("Використовуй EmbeddedProcessManager напряму")
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
        return "java -Xmx${maxM}M -Xms${minM}M -XX:+UseSerialGC -Djava.awt.headless=true -Xss256k -jar \"$jarName\" nogui"
    }

    fun buildUbuntuCommand(termuxPath: String): String = "sh -i"

    fun buildStopCommand(termuxPath: String): String = "stop"

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
        // Делегуємо у вбудований термінал через ServerService-логіку? Тут лише fallback:
        // прямий запуск неможливий без java/jar резолву, тому повертаємо true
        // а реальний запуск робить ServerService через EmbeddedProcessManager.
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
                // fallback: дописати у server.log справжньої папки щоб не загубити команду
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
            // termuxPath у legacy = /data/data/com.termux/... або абсолютний шлях.
            // Пробуємо знайти serverId за збігом папки, інакше просто логуємо.
            Log.d("TermuxBridge", "legacy sendRawCommand ignored (embedded): $bashCommand")
        } catch (e: Exception) {
            Log.e("TermuxBridge", "sendRawCommand failed", e)
        }
    }

    fun isAliveCheck(context: Context, termuxPath: String, jarHint: String = "java"): String {
        return "embedded"
    }

    /** Хелпер: побудувати сесію з embedded-даних */
    fun sessionFor(context: Context, serverId: String, workDirUri: String): TermuxSession {
        val dir = EmbeddedPaths.resolveWorkDir(context, serverId, workDirUri)
        return TermuxSession(serverId, -1, workDirUri, dir.absolutePath)
    }
}
