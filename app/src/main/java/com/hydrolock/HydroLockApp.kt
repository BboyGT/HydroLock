package com.hydrolock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HydroLockApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun getWorkManagerConfiguration(): Configuration {
        return Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Drink reminder channel
            NotificationChannel(
                CHANNEL_DRINK_REMINDER,
                "Drink Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders to drink water"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
                manager.createNotificationChannel(this)
            }

            // Lock enforcement channel
            NotificationChannel(
                CHANNEL_LOCK_ENFORCEMENT,
                "HydroLock Active",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Running while phone lock is active"
                manager.createNotificationChannel(this)
            }

            // General notifications
            NotificationChannel(
                CHANNEL_GENERAL,
                "General",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "General app notifications"
                manager.createNotificationChannel(this)
            }
        }
    }

    companion object {
        const val CHANNEL_DRINK_REMINDER = "drink_reminder"
        const val CHANNEL_LOCK_ENFORCEMENT = "lock_enforcement"
        const val CHANNEL_GENERAL = "general"
    }
}
