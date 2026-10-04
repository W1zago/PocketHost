package com.pockethost.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.pockethost.core.repository.ServerRepository

class PocketHostApplication : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "PocketHost_channel"
        const val NOTIFICATION_CHANNEL_NAME = "PocketHost Service"
        lateinit var instance: PocketHostApplication
            private set
        lateinit var serverRepository: ServerRepository
            private set

        /** True if [serverRepository] is already initialized (without throwing UninitializedPropertyAccessException). */
        val isRepositoryInitialized: Boolean
            get() = ::serverRepository.isInitialized

        /** Safe access: null if the repository is not ready or corrupted yet. */
        fun getRepositoryOrNull(): ServerRepository? =
            if (::serverRepository.isInitialized) {
                runCatching { serverRepository }.getOrNull()
            } else null
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
                android.util.Log.e("PocketHostApplication", "Uncaught", throwable)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
        // Single init with sensible fallback: if DataStore is corrupted - wipe
        // files once and retry. If the second attempt also fails - leave
        // uninitialized (getRepositoryOrNull() == null, isRepositoryInitialized == false),
        // callers must handle via fallback/Toast+finish instead of crashing on lateinit.
        runCatching {
            serverRepository = ServerRepository(this)
        }.onFailure { e ->
            android.util.Log.e("PocketHostApplication", "ServerRepository init failed, clearing datastore", e)
            runCatching {
                java.io.File(filesDir, "datastore/pockethost_servers.preferences_pb").delete()
                java.io.File(filesDir, "datastore/pockethost_servers.preferences_pb.tmp").delete()
            }
            runCatching {
                serverRepository = ServerRepository(this)
            }.onFailure { e2 ->
                android.util.Log.e("PocketHostApplication", "ServerRepository retry failed, leaving uninitialized", e2)
            }
        }
        try { createNotificationChannel() } catch (_: Exception) {}
        try { checkLegacyServers() } catch (_: Exception) {}
    }

    private fun checkLegacyServers() {
        // Built-in terminal uses filesDir/servers - now the primary storage, nothing to ignore.
        try {
            val servers = java.io.File(filesDir, "servers")
            if (servers.exists()) {
                android.util.Log.i("PocketHostApplication", "Embedded servers dir: ${servers.absolutePath}, count=${servers.listFiles()?.size ?: 0}")
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
                description = "PocketHost background service notifications"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
