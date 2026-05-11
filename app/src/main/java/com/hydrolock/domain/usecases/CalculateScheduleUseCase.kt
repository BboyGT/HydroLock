package com.hydrolock.domain.usecases

import com.hydrolock.data.database.dao.DrinkWindowDao
import com.hydrolock.data.database.entities.DrinkWindow
import com.hydrolock.data.database.entities.WindowStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.max

/**
 * Calculates and rebuilds the drink window schedule for a given day.
 * The schedule divides the active window into evenly spaced drink sessions,
 * dynamically adjusting for consumed amounts and remaining time.
 */
class CalculateScheduleUseCase @Inject constructor(
    private val drinkWindowDao: DrinkWindowDao
) {
    companion object {
        const val WINDOW_INTERVAL_MINUTES = 100 // ~1h40m between drinks
        const val MIN_SESSION_ML = 150
        const val MAX_SESSION_ML = 400
    }

    /**
     * Build full day schedule from scratch.
     * Called at day start or after significant changes.
     */
    suspend fun buildDaySchedule(
        date: String,
        totalTargetMl: Int,
        activeWindowStartMinutes: Int, // e.g. 360 = 6:00am
        activeWindowEndMinutes: Int    // e.g. 1380 = 11:00pm
    ) {
        // Clear old pending windows
        val existing = drinkWindowDao.getWindowsForDateSync(date)
        val completedMl = existing.filter { it.status == WindowStatus.COMPLETED }
            .sumOf { it.consumedMl }

        drinkWindowDao.clearWindowsForDate(date)

        val remaining = max(0, totalTargetMl - completedMl)
        if (remaining == 0) return

        val windows = distributeWindows(
            remainingMl = remaining,
            windowStartMinutes = activeWindowStartMinutes,
            windowEndMinutes = activeWindowEndMinutes,
            date = date
        )

        drinkWindowDao.insertWindows(windows)
    }

    /**
     * Recalculate remaining windows after a window is completed or rolled over.
     */
    suspend fun recalculateRemaining(
        date: String,
        currentTimeMinutes: Int,
        activeWindowEndMinutes: Int
    ) {
        val windows = drinkWindowDao.getWindowsForDateSync(date)
        val pendingWindows = windows.filter { it.status == WindowStatus.PENDING }

        if (pendingWindows.isEmpty()) return

        // Sum remaining target
        val remainingMl = pendingWindows.sumOf { it.targetMl }

        // Delete pending windows and redistribute
        pendingWindows.forEach {
            drinkWindowDao.updateWindowStatus(it.id, WindowStatus.ROLLED_OVER)
        }

        val redistributed = distributeWindows(
            remainingMl = remainingMl,
            windowStartMinutes = currentTimeMinutes + 5, // start from now + buffer
            windowEndMinutes = activeWindowEndMinutes,
            date = date
        )

        drinkWindowDao.insertWindows(redistributed)
    }

    /**
     * Build end-of-day catch-up sessions for remaining amount.
     */
    suspend fun buildEndOfDaySessions(
        date: String,
        remainingMl: Int,
        startTimeMinutes: Int
    ) {
        val sessionCount = ceil(remainingMl.toDouble() / 250.0).toInt()
        val windows = mutableListOf<DrinkWindow>()

        var currentTime = startTimeMinutes
        var remaining = remainingMl

        for (i in 0 until sessionCount) {
            val amount = minOf(250, remaining)
            windows.add(
                DrinkWindow(
                    dateString = date,
                    scheduledTimeMinutes = currentTime,
                    targetMl = amount,
                    status = WindowStatus.PENDING
                )
            )
            remaining -= amount
            currentTime += 7 // 7 minutes between end-of-day sessions
        }

        drinkWindowDao.insertWindows(windows)
    }

    private fun distributeWindows(
        remainingMl: Int,
        windowStartMinutes: Int,
        windowEndMinutes: Int,
        date: String
    ): List<DrinkWindow> {
        val totalMinutes = windowEndMinutes - windowStartMinutes
        if (totalMinutes <= 0 || remainingMl <= 0) return emptyList()

        val sessionCount = max(1, totalMinutes / WINDOW_INTERVAL_MINUTES)
        val baseAmount = remainingMl / sessionCount
        val remainder = remainingMl % sessionCount

        val windows = mutableListOf<DrinkWindow>()
        val intervalMinutes = totalMinutes / sessionCount

        for (i in 0 until sessionCount) {
            val amount = baseAmount + (if (i == 0) remainder else 0)
            val scheduledTime = windowStartMinutes + (i * intervalMinutes)

            windows.add(
                DrinkWindow(
                    dateString = date,
                    scheduledTimeMinutes = scheduledTime,
                    targetMl = amount.coerceIn(MIN_SESSION_ML, MAX_SESSION_ML),
                    status = WindowStatus.PENDING
                )
            )
        }

        return windows
    }

    fun todayString(): String =
        LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
}
