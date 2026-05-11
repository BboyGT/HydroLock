package com.hydrolock.domain.usecases

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hydrolock.data.database.dao.DayRecordDao
import com.hydrolock.data.database.dao.StreakEventDao
import com.hydrolock.data.database.entities.StreakEvent
import com.hydrolock.data.database.entities.StreakLossReason
import com.hydrolock.utils.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore by preferencesDataStore(name = "hydrolock_prefs")

@Singleton
class StreakManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dayRecordDao: DayRecordDao,
    private val streakEventDao: StreakEventDao
) {
    private val KEY_CURRENT_STREAK = intPreferencesKey(Constants.KEY_CURRENT_STREAK)
    private val KEY_BEST_STREAK = intPreferencesKey(Constants.KEY_BEST_STREAK)
    private val KEY_LAST_STREAK_DATE = stringPreferencesKey(Constants.KEY_LAST_STREAK_DATE)

    data class StreakLossEvent(
        val streakLost: Int,
        val bestStreak: Int,
        val reason: StreakLossReason,
        val nearestMilestone: Int? // what was coming up next
    )

    suspend fun getCurrentStreak(): Int {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_CURRENT_STREAK] ?: 0
    }

    suspend fun getBestStreak(): Int {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_BEST_STREAK] ?: 0
    }

    suspend fun incrementStreak(): Int {
        val prefs = context.dataStore.data.first()
        val current = (prefs[KEY_CURRENT_STREAK] ?: 0) + 1
        val best = prefs[KEY_BEST_STREAK] ?: 0
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

        context.dataStore.edit { mutablePrefs ->
            mutablePrefs[KEY_CURRENT_STREAK] = current
            if (current > best) mutablePrefs[KEY_BEST_STREAK] = current
            mutablePrefs[KEY_LAST_STREAK_DATE] = today
        }

        return current
    }

    suspend fun breakStreak(reason: StreakLossReason): StreakLossEvent {
        val prefs = context.dataStore.data.first()
        val current = prefs[KEY_CURRENT_STREAK] ?: 0
        val best = prefs[KEY_BEST_STREAK] ?: 0

        val nearestMilestone = getNextMilestone(current)

        // Record the event
        streakEventDao.insertEvent(
            StreakEvent(
                timestamp = System.currentTimeMillis(),
                streakLost = current,
                reason = reason,
                bestStreakAtTime = best
            )
        )

        // Reset current streak
        context.dataStore.edit { mutablePrefs ->
            mutablePrefs[KEY_CURRENT_STREAK] = 0
        }

        return StreakLossEvent(
            streakLost = current,
            bestStreak = best,
            reason = reason,
            nearestMilestone = nearestMilestone
        )
    }

    /**
     * Check if streak should continue or break at day start.
     * If last streak date was yesterday, continue. Otherwise break.
     */
    suspend fun validateStreakAtDayStart(): Boolean {
        val prefs = context.dataStore.data.first()
        val lastDate = prefs[KEY_LAST_STREAK_DATE] ?: return true
        val yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

        return lastDate == yesterday || lastDate == today
    }

    fun getNextMilestone(current: Int): Int? {
        val milestones = listOf(3, 7, 14, 21, 30, 60, 100)
        return milestones.firstOrNull { it > current }
    }

    fun getMilestoneLabel(milestone: Int): String = when (milestone) {
        3 -> "3-Day Habit Start"
        7 -> "One Full Week"
        14 -> "Two Weeks Strong"
        21 -> "21 Days — New Habit Formed"
        30 -> "30-Day Legend"
        60 -> "Two Month Champion"
        100 -> "100 Day Elite"
        else -> "$milestone Days"
    }
}
