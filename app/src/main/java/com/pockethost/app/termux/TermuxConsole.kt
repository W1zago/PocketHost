package com.pockethost.app.termux

import android.content.Context
import com.pockethost.app.embedded.EmbeddedProcessManager

/**
 * Legacy console - delegated to the built-in terminal.
 */
@Deprecated("Use EmbeddedProcessManager")
object TermuxConsole {

    fun sendViaFifo(context: Context, session: TermuxBridge.TermuxSession, data: String) {
        try {
            EmbeddedProcessManager.sendInput(session.serverId, data)
        } catch (_: Exception) {}
    }

    fun sendRaw(context: Context, termuxPath: String, command: String) {
        // ignored: built-in terminal does not need raw RUN_COMMAND
    }
}
