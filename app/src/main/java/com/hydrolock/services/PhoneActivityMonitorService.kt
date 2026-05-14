package com.hydrolock.services

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hydrolock.HydroLockApp
import com.hydrolock.R
import com.hydrolock.utils.Constants

class PhoneActivityMonitorService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Constants.NOTIF_MONITOR_FOREGROUND, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, HydroLockApp.CHANNEL_LOCK_ENFORCEMENT)
            .setContentTitle("HydroLock monitoring")
            .setContentText("Watching for active drink windows.")
            .setSmallIcon(R.drawable.ic_water_drop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
