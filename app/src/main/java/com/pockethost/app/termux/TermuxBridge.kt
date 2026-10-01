package com.pockethost.app.termux

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import com.pockethost.app.embedded.EmbeddedPaths
import com.pockethost.app.embedded.EmbeddedProcessManager
import com.pockethost.core.model.Server

/**
 * Legacy РјС–СЃС‚ СЃСѓРјС–СЃРЅРѕСЃС‚С–. Р Р°РЅС–С€Рµ СЃР»Р°Р»Рѕ Intent com.termux.RUN_COMMAND
 * Сѓ Р·РѕРІРЅС–С€РЅС–Р№ Termux (Р·РІС–РґСЃРё С– Р·Р°РїРёС‚ РґРѕР·РІРѕР»Сѓ "РєРµСЂСѓРІР°РЅРЅСЏ").
 *
 * РўРµРїРµСЂ РґРµР»РµРіСѓС” Сѓ Р’Р‘РЈР”РћР’РђРќРР™ С‚РµСЂРјС–РЅР°Р» - Р¶РѕРґРЅРёС… Intent, Р¶РѕРґРЅРёС… РґРѕР·РІРѕР»С–РІ.
 * РЎРёРіРЅР°С‚СѓСЂРё Р·Р±РµСЂРµР¶РµРЅРѕ С‰РѕР± СЃС‚Р°СЂРёР№ РєРѕРґ РЅРµ Р»Р°РјР°РІСЃСЏ.
 */
@Deprecated("Р’РёРєРѕСЂРёСЃС‚РѕРІСѓР№ EmbeddedProcessManager РЅР°РїСЂСЏРјСѓ")
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
            com.pockethost.core.model.MinecraftLoader.VANILLA -> "server.jar"
            com.pockethost.core.model.MinecraftLoader.FORGE -> "forge.jar"
            com.pockethost.core.model.MinecraftLoader.FABRIC -> "fabric-server-launch.jar"
            com.pockethost.core.model.MinecraftLoader.NEOFORGE -> "forge.jar"
            com.pockethost.core.model.MinecraftLoader.PAPER -> "paper.jar"
            com.pockethost.core.model.MinecraftLoader.SPIGOT -> "spigot.jar"
            com.pockethost.core.model.MinecraftLoader.BUKKIT -> "craftbukkit.jar"
            com.pockethost.core.model.MinecraftLoader.PURPUR -> "purpur.jar"
            null -> "paper.jar"
        }
    }

    fun start(context: Context, server: Server, isBackground: Boolean = true, pendingIntent: PendingIntent? = null): Boolean {
        // Р”РµР»РµРіСѓС”РјРѕ Сѓ РІР±СѓРґРѕРІР°РЅРёР№ С‚РµСЂРјС–РЅР°Р» С‡РµСЂРµР· ServerService-Р»РѕРіС–РєСѓ? РўСѓС‚ Р»РёС€Рµ fallback:
        // РїСЂСЏРјРёР№ Р·Р°РїСѓСЃРє РЅРµРјРѕР¶Р»РёРІРёР№ Р±РµР· java/jar СЂРµР·РѕР»РІСѓ, С‚РѕРјСѓ РїРѕРІРµСЂС‚Р°С”РјРѕ true
        // Р° СЂРµР°Р»СЊРЅРёР№ Р·Р°РїСѓСЃРє СЂРѕР±РёС‚СЊ ServerService С‡РµСЂРµР· EmbeddedProcessManager.
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
                // fallback: РґРѕРїРёСЃР°С‚Рё Сѓ server.log СЃРїСЂР°РІР¶РЅСЊРѕС— РїР°РїРєРё С‰РѕР± РЅРµ Р·Р°РіСѓР±РёС‚Рё РєРѕРјР°РЅРґСѓ
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
            // termuxPath Сѓ legacy = /data/data/com.termux/... Р°Р±Рѕ Р°Р±СЃРѕР»СЋС‚РЅРёР№ С€Р»СЏС….
            // РџСЂРѕР±СѓС”РјРѕ Р·РЅР°Р№С‚Рё serverId Р·Р° Р·Р±С–РіРѕРј РїР°РїРєРё, С–РЅР°РєС€Рµ РїСЂРѕСЃС‚Рѕ Р»РѕРіСѓС”РјРѕ.
            Log.d("TermuxBridge", "legacy sendRawCommand ignored (embedded): $bashCommand")
        } catch (e: Exception) {
            Log.e("TermuxBridge", "sendRawCommand failed", e)
        }
    }

    fun isAliveCheck(context: Context, termuxPath: String, jarHint: String = "java"): String {
        return "embedded"
    }

    /** РҐРµР»РїРµСЂ: РїРѕР±СѓРґСѓРІР°С‚Рё СЃРµСЃС–СЋ Р· embedded-РґР°РЅРёС… */
    fun sessionFor(context: Context, serverId: String, workDirUri: String): TermuxSession {
        val dir = EmbeddedPaths.resolveWorkDir(context, serverId, workDirUri)
        return TermuxSession(serverId, -1, workDirUri, dir.absolutePath)
    }
}
