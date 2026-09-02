package com.anserver.app.termux

import android.content.Context
import android.os.StatFs
import android.util.Log

object TermuxInstaller {

    fun hasEnoughSpace(path: String = "/data", requiredBytes: Long = 2L * 1024 * 1024 * 1024): Boolean {
        return try {
            val stat = StatFs(path)
            val available = stat.availableBytes
            available > requiredBytes
        } catch (_: Exception) { true }
    }

    fun ensureJava(context: Context, termuxPath: String, version: String? = null) {
        if (!hasEnoughSpace()) {
            Log.w("TermuxInstaller", "low space <2GB")
            return
        }
        val pkgCmd = "pkg update -y 2>&1 | tee -a server.log; pkg install -y openjdk-21 2>&1 | tee -a server.log; java -version 2>&1 | tee -a server.log"
        TermuxBridge.sendRawCommand(context, termuxPath, pkgCmd)
    }

    fun ensureProotDistro(context: Context, termuxPath: String) {
        val cmd = """
            command -v proot-distro >/dev/null 2>&1 || pkg install -y proot-distro 2>&1 | tee -a server.log
            proot-distro list 2>/dev/null | grep -q "^ubuntu" || proot-distro install ubuntu 2>&1 | tee -a server.log
        """.trimIndent()
        TermuxBridge.sendRawCommand(context, termuxPath, cmd)
    }

    fun installUbuntuFull(context: Context, termuxPath: String) {
        val cmd = "proot-distro login ubuntu --shared-tmp -- bash -c \"apt update && apt install -y ubuntu-server 2>&1 | tee -a /tmp/install.log; cat /tmp/install.log\" 2>&1 | tee -a server.log"
        TermuxBridge.sendRawCommand(context, termuxPath, cmd)
    }
}
