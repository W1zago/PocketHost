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

        /** True якщо [serverRepository] вже проініціалізовано (без кидання UninitializedPropertyAccessException). */
        val isRepositoryInitialized: Boolean
            get() = ::serverRepository.isInitialized

        /** Безпечний доступ: null якщо репозиторій ще не готовий або пошкоджений. */
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
                android.util.Log.e("ANServerApplication", "Uncaught", throwable)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
        // Single init з зрозумілим fallback: якщо DataStore пошкоджено — чистимо
        // файли один раз і пробуємо ще раз. Якщо і другий раз впав — лишаємо
        // неініціалізованим (getRepositoryOrNull() == null, isRepositoryInitialized == false),
        // викликачі мають обробити через fallback/Toast+finish, а не крашитись на lateinit.
        runCatching {
            serverRepository = ServerRepository(this)
        }.onFailure { e ->
            android.util.Log.e("ANServerApplication", "ServerRepository init failed, clearing datastore", e)
            runCatching {
                java.io.File(filesDir, "datastore/pockethost_servers.preferences_pb").delete()
                java.io.File(filesDir, "datastore/pockethost_servers.preferences_pb.tmp").delete()
            }
            runCatching {
                serverRepository = ServerRepository(this)
            }.onFailure { e2 ->
                android.util.Log.e("ANServerApplication", "ServerRepository retry failed, leaving uninitialized", e2)
            }
        }
        try { createNotificationChannel() } catch (_: Exception) {}
        try { checkLegacyServers() } catch (_: Exception) {}
    }

    private fun checkLegacyServers() {
        // Вбудований термінал використовує filesDir/servers - це тепер основне сховище, нічого ігнорувати не треба.
        try {
            val servers = java.io.File(filesDir, "servers")
            if (servers.exists()) {
                android.util.Log.i("ANServerApplication", "Embedded servers dir: ${servers.absolutePath}, count=${servers.listFiles()?.size ?: 0}")
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
