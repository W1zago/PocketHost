package com.pockethost.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

/**
 * Result receiver for the PackageInstaller session commit from [AppUpdater].
 * Handles the system consent flow (PENDING_USER_ACTION) and terminal states.
 * No auto-retry, no silent install — everything is user-confirmed.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INSTALL_COMMIT = "com.pockethost.app.UPDATE_INSTALL_COMMIT"
        private const val TAG = "UpdateInstall"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_COMMIT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // System consent dialog: must start as a new task from a receiver.
                try {
                    val confirm: Intent? = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (confirm != null) context.startActivity(confirm)
                } catch (e: Exception) {
                    Log.e(TAG, "consent launch failed", e)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, "Update installed", Toast.LENGTH_LONG).show()
            }
            else -> {
                Log.w(TAG, "install status=$status msg=$msg")
                Toast.makeText(
                    context,
                    "Update failed${if (msg.isNullOrBlank()) "" else ": $msg"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
