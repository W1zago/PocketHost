package com.pockethost.app.termux

import android.content.Context
import com.pockethost.app.embedded.EmbeddedBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Legacy installer: previously ran pkg install in external Termux.
 * Now delegates to the built-in bootstrap (no permissions).
 */
@Deprecated("Use EmbeddedBootstrap")
object TermuxInstaller {

    // H6: shared scope with SupervisorJob instead of fire-and-forget CoroutineScope() on every call.
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
