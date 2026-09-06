package com.anserver.app.termux

import android.content.Context
import com.anserver.app.embedded.EmbeddedProcessManager

/**
 * Legacy консоль - делеговано у вбудований термінал.
 */
@Deprecated("Використовуй EmbeddedProcessManager")
object TermuxConsole {

    fun sendViaFifo(context: Context, session: TermuxBridge.TermuxSession, data: String) {
        try {
            EmbeddedProcessManager.sendInput(session.serverId, data)
        } catch (_: Exception) {}
    }

    fun sendRaw(context: Context, termuxPath: String, command: String) {
        // ігнорується: вбудований термінал не потребує сирих RUN_COMMAND
    }
}
