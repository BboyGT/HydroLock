package com.hydrolock.workers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.hydrolock.HydroLockApp
import com.hydrolock.R
import com.hydrolock.data.database.dao.DayRecordDao
import com.hydrolock.data.database.dao.DrinkWindowDao
import com.hydrolock.data.database.dao.UserProfileDao
import com.hydrolock.data.database.entities.DayRecord
import com.hydrolock.data.database.entities.StreakLossReason
import com.hydrolock.data.database.entities.WindowStatus
import com.hydrolock.domain.usecases.ActiveWindowDetector
import com.hydrolock.domain.usecases.CalculateScheduleUseCase
import com.hydrolock.domain.usecases.StreakManager
import com.hydrolock.services.OverlayLockService
import com.hydrolock.ui.drink.DrinkSessionActivity
import com.hydrolock.ui.streak.StreakLossActivity
import com.hydrolock.utils.Constants
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Fired when a drink window opens. Sends a notification.
 * If not acknowledged within grace period, escalates to overlay lock.
 */
@HiltWorker
class DrinkWindowWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val drinkWindowDao: DrinkWindowDao,
    private val dayRecordDao: DayRecordDao
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val windowId = inputData.getLong(Constants.EXTRA_WINDOW_ID, -1L)
        val targetMl = inputData.getInt(Constants.EXTRA_TARGET_ML, 0)

        if (windowId == -1L) return Result.failure()

        val window = drinkWindowDao.getWindow(windowId) ?: return Result.failure()
        if (window.status != WindowStatus.PENDING) return Result.success()

        // Mark as notified
        drinkWindowDao.updateWindowStatus(windowId, WindowStatus.NOTIFIED)

        // Send notification
        sendDrinkNotification(windowId, targetMl)

        // Schedule grace period check
        val graceWorkRequest = OneTimeWorkRequestBuilder<GracePeriodWorker>()
            .setInitialDelay(Constants.GRACE_PERIOD_MS, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(
                Constants.EXTRA_WINDOW_ID to windowId,
                Constants.EXTRA_TARGET_ML to targetMl
            ))
            .addTag(Constants.WORK_DRINK_WINDOW)
            .build()

        WorkManager.getInstance(applicationContext).enqueue(graceWorkRequest)

        return Result.success()
    }

    private fun sendDrinkNotification(windowId: Long, targetMl: Int) {
        val drinkIntent = Intent(applicationContext, DrinkSessionActivity::class.java).apply {
            putExtra(Constants.EXTRA_WINDOW_ID, windowId)
            putExtra(Constants.EXTRA_TARGET_ML, targetMl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext, windowId.toInt(), drinkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            applicationContext, HydroLockApp.CHANNEL_DRINK_REMINDER
        )
            .setContentTitle("💧 Time to Drink")
            .setContentText("Drink ${targetMl}ml now. You have 10 minutes.")
            .setSmallIcon(R.drawable.ic_water_drop)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .build()

        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(Constants.NOTIF_DRINK_REMINDER + windowId.toInt(), notification)
        } catch (_: SecurityException) {}
    }
}

/**
 * Fires after the grace period. If window is still NOTIFIED (not completed),
 * escalates to overlay lock.
 */
@HiltWorker
class GracePeriodWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val drinkWindowDao: DrinkWindowDao
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val windowId = inputData.getLong(Constants.EXTRA_WINDOW_ID, -1L)
        val targetMl = inputData.getInt(Constants.EXTRA_TARGET_ML, 0)

        if (windowId == -1L) return Result.failure()

        val window = drinkWindowDao.getWindow(windowId) ?: return Result.failure()

        // Only escalate if still notified (not completed or overridden)
        if (window.status != WindowStatus.NOTIFIED) return Result.success()

        // Update to locked state
        drinkWindowDao.updateWindowStatus(windowId, WindowStatus.LOCKED)

        // Start overlay lock service
        val overlayIntent = Intent(applicationContext, OverlayLockService::class.java).apply {
            putExtra(Constants.EXTRA_WINDOW_ID, windowId)
            putExtra(Constants.EXTRA_TARGET_ML, targetMl)
        }
        applicationContext.startForegroundService(overlayIntent)

        return Result.success()
    }
}

/**
 * Daily schedule builder. Runs at day start (detected wake time).
 * Builds drink windows for the day.
 */
@HiltWorker
class DayScheduleWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val calculateScheduleUseCase: CalculateScheduleUseCase,
    private val userProfileDao: UserProfileDao,
    private val dayRecordDao: DayRecordDao,
    private val activeWindowDetector: ActiveWindowDetector,
    private val streakManager: StreakManager
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val profile = userProfileDao.getProfile() ?: return Result.failure()

        // Detect active window
        val activeWindow = activeWindowDetector.detectActiveWindow()

        // Validate streak continuity
        if (!streakManager.validateStreakAtDayStart()) {
            // Missed yesterday — break streak
            streakManager.breakStreak(StreakLossReason.MISSED_WINDOWS)
        }

        // Ramp up daily target toward goal
        val newTarget = minOf(
            profile.currentTargetMl + Constants.DAILY_RAMP_INCREASE_ML,
            profile.dailyGoalMl
        )
        userProfileDao.updateCurrentTarget(newTarget)

        // Create day record
        val existing = dayRecordDao.getRecord(today)
        if (existing == null) {
            dayRecordDao.saveRecord(
                DayRecord(
                    dateString = today,
                    targetMl = newTarget,
                    activeWindowStartMinutes = activeWindow.startMinutes,
                    activeWindowEndMinutes = activeWindow.endMinutes,
                    streak = streakManager.getCurrentStreak()
                )
            )
        }

        // Build schedule
        calculateScheduleUseCase.buildDaySchedule(
            date = today,
            totalTargetMl = newTarget,
            activeWindowStartMinutes = activeWindow.startMinutes,
            activeWindowEndMinutes = activeWindow.endMinutes
        )

        // Schedule drink window workers
        scheduleWindows(activeWindow.startMinutes, activeWindow.endMinutes, newTarget)

        return Result.success()
    }

    private fun scheduleWindows(
        startMinutes: Int,
        endMinutes: Int,
        totalMl: Int
    ) {
        val now = LocalTime.now()
        val nowMinutes = now.hour * 60 + now.minute
        val wm = WorkManager.getInstance(applicationContext)

        // Cancel previous windows
        wm.cancelAllWorkByTag(Constants.WORK_DRINK_WINDOW)

        val interval = CalculateScheduleUseCase.WINDOW_INTERVAL_MINUTES
        var currentMinutes = maxOf(startMinutes, nowMinutes + 5)

        while (currentMinutes < endMinutes) {
            val delayMs = ((currentMinutes - nowMinutes) * 60 * 1000L).coerceAtLeast(0L)

            // We'll use a placeholder window ID - real IDs come from DB
            // In production this would be coordinated with the DB-inserted windows
            val request = OneTimeWorkRequestBuilder<DrinkWindowWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(
                    Constants.EXTRA_TARGET_ML to (totalMl / ((endMinutes - startMinutes) / interval))
                ))
                .addTag(Constants.WORK_DRINK_WINDOW)
                .build()

            wm.enqueue(request)
            currentMinutes += interval
        }
    }
}

/**
 * End-of-day enforcer. Fires when active window closes.
 * If goal not met, triggers end-of-day lock.
 */
@HiltWorker
class EndOfDayWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val dayRecordDao: DayRecordDao,
    private val drinkWindowDao: DrinkWindowDao,
    private val calculateScheduleUseCase: CalculateScheduleUseCase,
    private val streakManager: StreakManager
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val record = dayRecordDao.getRecord(today) ?: return Result.failure()

        if (record.goalMet) {
            // Goal met — increment streak
            streakManager.incrementStreak()
            return Result.success()
        }

        val consumed = drinkWindowDao.getTotalConsumedForDate(today) ?: 0
        val remaining = record.targetMl - consumed

        if (remaining <= 0) {
            dayRecordDao.markGoalMet(today)
            streakManager.incrementStreak()
            return Result.success()
        }

        // Goal NOT met — build end-of-day catch-up sessions
        val nowMinutes = LocalTime.now().let { it.hour * 60 + it.minute }
        calculateScheduleUseCase.buildEndOfDaySessions(today, remaining, nowMinutes)

        // Trigger lock for first session
        val nextWindow = drinkWindowDao.getNextPendingWindow(today)
        if (nextWindow != null) {
            val overlayIntent = Intent(applicationContext, OverlayLockService::class.java).apply {
                putExtra(Constants.EXTRA_WINDOW_ID, nextWindow.id)
                putExtra(Constants.EXTRA_TARGET_ML, nextWindow.targetMl)
            }
            applicationContext.startForegroundService(overlayIntent)
        }

        // Check if this will cause streak break
        if (remaining > record.targetMl * 0.5f) {
            // Missed more than half the target — streak breaks
            val lossEvent = streakManager.breakStreak(StreakLossReason.END_OF_DAY_GOAL_MISSED)

            // Launch streak loss screen
            val streakIntent = Intent(applicationContext, StreakLossActivity::class.java).apply {
                putExtra(Constants.EXTRA_LOST_STREAK, lossEvent.streakLost)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            applicationContext.startActivity(streakIntent)
        }

        return Result.success()
    }
}
