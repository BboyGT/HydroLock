package com.hydrolock.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import com.hydrolock.workers.DayScheduleWorker
import com.hydrolock.utils.Constants
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // Re-schedule day worker on boot
        val request = OneTimeWorkRequestBuilder<DayScheduleWorker>()
            .setInitialDelay(30, TimeUnit.SECONDS) // brief delay after boot
            .addTag(Constants.WORK_SCHEDULE_REBUILD)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                Constants.WORK_SCHEDULE_REBUILD,
                ExistingWorkPolicy.REPLACE,
                request
            )
    }
}
