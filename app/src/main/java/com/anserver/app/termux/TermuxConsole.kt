package com.anserver.app.termux

import android.content.Context
import android.util.Base64

object TermuxConsole {

    fun sendViaFifo(context: Context, session: TermuxBridge.TermuxSession, data: String) {
        TermuxBridge.sendInput(context, session, data)
    }

    fun sendRaw(context: Context, termuxPath: String, command: String) {
        TermuxBridge.sendRawCommand(context, termuxPath, command)
    }
}
