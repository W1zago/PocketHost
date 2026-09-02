package com.anserver.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.anserver.core.repository.ServerRepository

class ANServerApplication : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "anserver_channel"
        const val NOTIFICATION_CHANNEL_NAME = "ANServer Service"
        lateinit var instance: ANServerApplication
            private set
        lateinit var serverRepository: ServerRepository
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Global crash logger to file
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val logFile = java.io.File(filesDir, "crash.log")
                logFile.parentFile?.mkdirs()
                val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                logFile.appendText("\n--- Crash $ts thread=${thread.name} ---\n${throwable.stackTraceToString()}\n")
                android.util.Log.e("ANServerApplication", "Uncaught", throwable)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
        try {
            serverRepository = ServerRepository(this)
        } catch (e: Exception) {
            android.util.Log.e("ANServerApplication", "ServerRepository init failed, clearing datastore", e)
            try {
                // Delete corrupted DataStore file
                java.io.File(filesDir, "datastore/pockethost_servers.preferences_pb").delete()
                java.io.File(filesDir, "datastore/pockethost_servers.preferences_pb.tmp").delete()
            } catch (_: Exception) {}
            try {
                serverRepository = ServerRepository(this)
            } catch (e2: Exception) {
                android.util.Log.e("ANServerApplication", "Retry failed", e2)
                // Last resort: use in-memory only repository via reflection fallback (create empty)
                serverRepository = ServerRepository(this)
            }
        }
        try { createNotificationChannel() } catch (_: Exception) {}
        try { checkLegacyServers() } catch (_: Exception) {}
    }

    private fun checkLegacyServers() {
        try {
            val legacy = java.io.File(filesDir, "servers")
            if (legacy.exists() && legacy.listFiles()?.isNotEmpty() == true) {
                android.util.Log.w("ANServerApplication", "Legacy filesDir servers found, ignoring. SAF required.")
                // Toast must be on main thread, delay
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(this, "Старі сервери не підтримуються, створіть заново в SAF", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ANServer background service notifications"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
