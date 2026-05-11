package com.hydrolock.data.database.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.hydrolock.data.database.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profile WHERE id = 1")
    suspend fun getProfile(): UserProfile?

    @Query("SELECT * FROM user_profile WHERE id = 1")
    fun getProfileFlow(): Flow<UserProfile?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProfile(profile: UserProfile)

    @Query("UPDATE user_profile SET currentTargetMl = :newTarget WHERE id = 1")
    suspend fun updateCurrentTarget(newTarget: Int)
}

@Dao
interface DayRecordDao {
    @Query("SELECT * FROM day_records WHERE dateString = :date")
    suspend fun getRecord(date: String): DayRecord?

    @Query("SELECT * FROM day_records WHERE dateString = :date")
    fun getRecordFlow(date: String): Flow<DayRecord?>

    @Query("SELECT * FROM day_records ORDER BY dateString DESC LIMIT 30")
    fun getRecentRecords(): Flow<List<DayRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRecord(record: DayRecord)

    @Query("UPDATE day_records SET consumedMl = consumedMl + :amount WHERE dateString = :date")
    suspend fun addConsumed(date: String, amount: Int)

    @Query("UPDATE day_records SET goalMet = 1 WHERE dateString = :date")
    suspend fun markGoalMet(date: String)

    @Query("SELECT * FROM day_records ORDER BY dateString DESC LIMIT 7")
    suspend fun getLast7Days(): List<DayRecord>
}

@Dao
interface DrinkWindowDao {
    @Query("SELECT * FROM drink_windows WHERE dateString = :date ORDER BY scheduledTimeMinutes ASC")
    fun getWindowsForDate(date: String): Flow<List<DrinkWindow>>

    @Query("SELECT * FROM drink_windows WHERE dateString = :date ORDER BY scheduledTimeMinutes ASC")
    suspend fun getWindowsForDateSync(date: String): List<DrinkWindow>

    @Query("SELECT * FROM drink_windows WHERE id = :id")
    suspend fun getWindow(id: Long): DrinkWindow?

    @Query("SELECT * FROM drink_windows WHERE dateString = :date AND status = 'PENDING' ORDER BY scheduledTimeMinutes ASC LIMIT 1")
    suspend fun getNextPendingWindow(date: String): DrinkWindow?

    @Query("SELECT * FROM drink_windows WHERE dateString = :date AND status = 'NOTIFIED' OR status = 'LOCKED'")
    suspend fun getActiveWindows(date: String): List<DrinkWindow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWindows(windows: List<DrinkWindow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWindow(window: DrinkWindow): Long

    @Update
    suspend fun updateWindow(window: DrinkWindow)

    @Query("UPDATE drink_windows SET status = :status WHERE id = :id")
    suspend fun updateWindowStatus(id: Long, status: WindowStatus)

    @Query("UPDATE drink_windows SET consumedMl = :consumed, status = 'COMPLETED', completedAt = :completedAt WHERE id = :id")
    suspend fun completeWindow(id: Long, consumed: Int, completedAt: Long)

    @Query("DELETE FROM drink_windows WHERE dateString = :date")
    suspend fun clearWindowsForDate(date: String)

    @Query("SELECT SUM(consumedMl) FROM drink_windows WHERE dateString = :date AND status = 'COMPLETED'")
    suspend fun getTotalConsumedForDate(date: String): Int?

    @Query("SELECT SUM(targetMl) FROM drink_windows WHERE dateString = :date AND status IN ('PENDING', 'NOTIFIED', 'LOCKED')")
    suspend fun getRemainingTargetForDate(date: String): Int?
}

@Dao
interface DrinkSessionDao {
    @Insert
    suspend fun insertSession(session: DrinkSession): Long

    @Update
    suspend fun updateSession(session: DrinkSession)

    @Query("SELECT * FROM drink_sessions WHERE id = :id")
    suspend fun getSession(id: Long): DrinkSession?

    @Query("SELECT * FROM drink_sessions WHERE dateString = :date ORDER BY startedAt DESC")
    fun getSessionsForDate(date: String): Flow<List<DrinkSession>>

    @Query("SELECT COUNT(*) FROM drink_sessions WHERE verificationMethod = 'MANUAL_CONFIRMED' AND dateString >= :sinceDate")
    suspend fun countUnverifiedSince(sinceDate: String): Int
}

@Dao
interface PhoneActivityDao {
    @Insert
    suspend fun insertActivity(activity: PhoneActivity)

    @Query("SELECT * FROM phone_activity WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getActivitiesSince(since: Long): List<PhoneActivity>

    @Query("DELETE FROM phone_activity WHERE timestamp < :before")
    suspend fun pruneOlderThan(before: Long)
}

@Dao
interface StreakEventDao {
    @Insert
    suspend fun insertEvent(event: StreakEvent)

    @Query("SELECT * FROM streak_events ORDER BY timestamp DESC LIMIT 20")
    fun getRecentEvents(): Flow<List<StreakEvent>>
}
