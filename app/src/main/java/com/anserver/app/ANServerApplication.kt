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
        serverRepository = ServerRepository()
        createNotificationChannel()
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
