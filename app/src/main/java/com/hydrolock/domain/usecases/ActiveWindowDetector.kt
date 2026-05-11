package com.hydrolock.domain.usecases

import android.app.usage.UsageStatsManager
import android.content.Context
import com.hydrolock.data.database.dao.PhoneActivityDao
import com.hydrolock.data.database.entities.PhoneActivity
import com.hydrolock.data.database.entities.PhoneEventType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Analyzes phone usage patterns over the past several days
 * to determine the user's natural active window (wake to sleep).
 *
 * Strategy: Look at UsageStats for screen-on events over the past 7 days.
 * Cluster the "first interaction of day" and "last interaction of day" times.
 * Return the median of each to get a reliable active window.
 */
@Singleton
class ActiveWindowDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val phoneActivityDao: PhoneActivityDao
) {
    companion object {
        const val DEFAULT_WAKE_MINUTES = 420    // 7:00 AM
        const val DEFAULT_SLEEP_MINUTES = 1380  // 11:00 PM
        const val DAYS_TO_ANALYZE = 7
        const val EARLY_MORNING_CUTOFF = 180    // 3:00 AM - activity before this = night owl
    }

    data class ActiveWindow(
        val startMinutes: Int,
        val endMinutes: Int,
        val confidence: Float // 0-1, how reliable this estimate is
    )

    fun detectActiveWindow(): ActiveWindow {
        return try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as UsageStatsManager

            val endTime = System.currentTimeMillis()
            val startTime = endTime - (DAYS_TO_ANALYZE * 24 * 60 * 60 * 1000L)

            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )

            if (stats.isNullOrEmpty()) {
                return ActiveWindow(DEFAULT_WAKE_MINUTES, DEFAULT_SLEEP_MINUTES, 0f)
            }

            // Get events for finer granularity
            val events = usageStatsManager.queryEvents(startTime, endTime)
            val eventList = mutableListOf<Pair<Long, Int>>() // timestamp, event type

            val event = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.SCREEN_INTERACTIVE ||
                    event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                    eventList.add(Pair(event.timeStamp, event.eventType))
                }
            }

            if (eventList.size < 10) {
                return ActiveWindow(DEFAULT_WAKE_MINUTES, DEFAULT_SLEEP_MINUTES, 0.2f)
            }

            // Group events by day
            val wakeMinutes = mutableListOf<Int>()
            val sleepMinutes = mutableListOf<Int>()

            val eventsByDay = eventList.groupBy { (ts, _) ->
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = ts
                "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.DAY_OF_YEAR)}"
            }

            for ((_, dayEvents) in eventsByDay) {
                if (dayEvents.isEmpty()) continue

                val dayMinutes = dayEvents.map { (ts, _) ->
                    val cal = java.util.Calendar.getInstance()
                    cal.timeInMillis = ts
                    cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
                }

                // First activity of day (after 3am to exclude night owls browsing at 2am)
                val wake = dayMinutes.filter { it > EARLY_MORNING_CUTOFF }.minOrNull()
                val sleep = dayMinutes.filter { it > EARLY_MORNING_CUTOFF }.maxOrNull()

                if (wake != null) wakeMinutes.add(wake)
                if (sleep != null) sleepMinutes.add(sleep)
            }

            if (wakeMinutes.size < 3) {
                return ActiveWindow(DEFAULT_WAKE_MINUTES, DEFAULT_SLEEP_MINUTES, 0.3f)
            }

            val medianWake = wakeMinutes.sorted()[wakeMinutes.size / 2]
            val medianSleep = sleepMinutes.sorted()[sleepMinutes.size / 2]
            val confidence = minOf(1f, wakeMinutes.size.toFloat() / DAYS_TO_ANALYZE)

            ActiveWindow(
                startMinutes = medianWake,
                endMinutes = medianSleep,
                confidence = confidence
            )

        } catch (e: SecurityException) {
            // No usage stats permission granted yet
            ActiveWindow(DEFAULT_WAKE_MINUTES, DEFAULT_SLEEP_MINUTES, 0f)
        } catch (e: Exception) {
            ActiveWindow(DEFAULT_WAKE_MINUTES, DEFAULT_SLEEP_MINUTES, 0f)
        }
    }

    fun minutesToTimeString(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        val amPm = if (h < 12) "AM" else "PM"
        val hour12 = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        return "${hour12}:${m.toString().padStart(2, '0')} $amPm"
    }
}
