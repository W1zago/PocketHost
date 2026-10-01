package com.pockethost.app.termux

import android.content.Context
import com.pockethost.app.embedded.EmbeddedBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Legacy С–РЅСЃС‚Р°Р»РµСЂ: СЂР°РЅС–С€Рµ СЃР»Р°Р»Рѕ pkg install Сѓ Р·РѕРІРЅС–С€РЅС–Р№ Termux.
 * РўРµРїРµСЂ РґРµР»РµРіСѓС” Сѓ РІР±СѓРґРѕРІР°РЅРёР№ bootstrap (Р±РµР· РґРѕР·РІРѕР»С–РІ).
 */
@Deprecated("Р’РёРєРѕСЂРёСЃС‚РѕРІСѓР№ EmbeddedBootstrap")
object TermuxInstaller {

    // H6: СЃРїС–Р»СЊРЅРёР№ scope Р· SupervisorJob Р·Р°РјС–СЃС‚СЊ fire-and-forget CoroutineScope() РЅР° РєРѕР¶РµРЅ РІРёРєР»РёРє.
    private val installerJob = SupervisorJob()
    private val installerScope = CoroutineScope(installerJob + Dispatchers.IO)

    fun hasEnoughSpace(path: String = "/data", requiredBytes: Long = 512L * 1024 * 1024): Boolean {
        return try {
            val stat = android.os.StatFs(path)
            stat.availableBytes > requiredBytes
        } catch (_: Exception) { true }
    }

    fun ensureJava(context: Context, termuxPath: String, version: String? = null) {
        installerScope.launch {
            try {
                EmbeddedBootstrap.ensureJava(context) {}
            } catch (_: Exception) {}
        }
    }

    fun ensureProotDistro(context: Context, termuxPath: String) {
        installerScope.launch {
            try {
                EmbeddedBootstrap.ensureUbuntu(context) {}
            } catch (_: Exception) {}
        }
    }

    fun installUbuntuFull(context: Context, termuxPath: String) {
        installerScope.launch {
            try {
                EmbeddedBootstrap.installUbuntuFull(context) {}
            } catch (_: Exception) {}
        }
    }
}
