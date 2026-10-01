package com.pockethost.app.termux

import android.content.Context
import com.pockethost.app.embedded.EmbeddedProcessManager

/**
 * Legacy РєРѕРЅСЃРѕР»СЊ - РґРµР»РµРіРѕРІР°РЅРѕ Сѓ РІР±СѓРґРѕРІР°РЅРёР№ С‚РµСЂРјС–РЅР°Р».
 */
@Deprecated("Р’РёРєРѕСЂРёСЃС‚РѕРІСѓР№ EmbeddedProcessManager")
object TermuxConsole {

    fun sendViaFifo(context: Context, session: TermuxBridge.TermuxSession, data: String) {
        try {
            EmbeddedProcessManager.sendInput(session.serverId, data)
        } catch (_: Exception) {}
    }

    fun sendRaw(context: Context, termuxPath: String, command: String) {
        // С–РіРЅРѕСЂСѓС”С‚СЊСЃСЏ: РІР±СѓРґРѕРІР°РЅРёР№ С‚РµСЂРјС–РЅР°Р» РЅРµ РїРѕС‚СЂРµР±СѓС” СЃРёСЂРёС… RUN_COMMAND
    }
}
