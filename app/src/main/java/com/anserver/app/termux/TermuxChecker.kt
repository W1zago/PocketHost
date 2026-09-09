package com.anserver.app.termux

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object TermuxChecker {

    private const val TERMUX_PACKAGE = "com.termux"
    private const val MIN_VERSION_CODE = 95L

    fun isInstalled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(TERMUX_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun getVersionCode(context: Context): Long {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(TERMUX_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (_: Exception) {
            -1L
        }
    }

    fun isVersionOk(context: Context): Boolean {
        val code = getVersionCode(context)
        return code >= MIN_VERSION_CODE
    }

    fun isRunCommandGranted(context: Context): Boolean {
        // Check runtime grant of com.termux.permission.RUN_COMMAND (declared in Manifest)
        // This permission on Samsung OneUI appears under "Інше" / "Додаткові дозволи"
        return try {
            // First, check if we hold the permission (most reliable on Android 13+ with targetSdk 34)
            val hasPerm = try {
                androidx.core.content.ContextCompat.checkSelfPermission(context, "com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) { false }
            if (hasPerm) return true

            // Fallback via PackageManager.checkPermission (works without ContextCompat)
            val viaPm = try {
                context.packageManager.checkPermission("com.termux.permission.RUN_COMMAND", context.packageName) == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) { false }
            if (viaPm) return true

            // Last resort: if Termux not installed, service won't resolve, so check service existence only as hint
            // But without permission, RUN_COMMAND will throw SecurityException, so we must return false here
            false
        } catch (_: Exception) {
            false
        }
    }

    /** For diagnostics: returns why permission appears as "Інше" */
    fun getRunCommandDetails(context: Context): String {
        return try {
            val check = androidx.core.content.ContextCompat.checkSelfPermission(context, "com.termux.permission.RUN_COMMAND")
            val viaPm = context.packageManager.checkPermission("com.termux.permission.RUN_COMMAND", context.packageName)
            "checkSelf=${if(check==PackageManager.PERMISSION_GRANTED)"GRANTED" else "DENIED"} pmCheck=${if(viaPm==PackageManager.PERMISSION_GRANTED)"GRANTED" else "DENIED"}"
        } catch (e: Exception) { "error ${e.message}" }
    }

    fun isStorageSetup(context: Context): Boolean {
        // Вбудований термінал не потребує SAF: завжди true, реальна перевірка запису
        // робиться через EmbeddedPaths.isDirWritable перед стартом.
        return try {
            context.contentResolver.persistedUriPermissions
            true
        } catch (_: Exception) { true }
    }

    /**
     * Legacy: вбудований термінал не потребує зовнішнього Termux,
     * тому завжди повертаємо true щоб жоден gate не блокував додаток.
     */
    @Deprecated("Вбудований термінал завжди OK")
    fun isAllOk(context: Context): Boolean = true

    @Deprecated("Вбудований термінал завжди OK")
    fun getGateReason(context: Context): GateReason = GateReason.OK

    enum class GateReason {
        OK,
        NOT_INSTALLED,
        OUTDATED_PLAY_STORE,
        PERMISSION_DENIED,
        STORAGE_NOT_SETUP
    }
}
