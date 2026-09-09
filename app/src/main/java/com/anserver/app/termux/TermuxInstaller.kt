package com.anserver.app.termux

import android.content.Context
import com.anserver.app.embedded.EmbeddedBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Legacy інсталер: раніше слало pkg install у зовнішній Termux.
 * Тепер делегує у вбудований bootstrap (без дозволів).
 */
@Deprecated("Використовуй EmbeddedBootstrap")
object TermuxInstaller {

    // H6: спільний scope з SupervisorJob замість fire-and-forget CoroutineScope() на кожен виклик.
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
