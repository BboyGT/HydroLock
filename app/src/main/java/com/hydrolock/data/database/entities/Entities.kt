package com.hydrolock.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey val id: Int = 1,
    val weightKg: Float,
    val dailyGoalMl: Int,
    val startingIntakeMl: Int,
    val currentTargetMl: Int,
    val onboardingComplete: Boolean = false
)

@Entity(tableName = "day_records")
data class DayRecord(
    @PrimaryKey val dateString: String, // "YYYY-MM-DD"
    val targetMl: Int,
    val consumedMl: Int = 0,
    val streak: Int = 0,
    val goalMet: Boolean = false,
    val overridesUsed: Int = 0,
    val trustScore: Float = 1.0f,
    val activeWindowStartMinutes: Int = 360, // 6am default
    val activeWindowEndMinutes: Int = 1380   // 11pm default
)

@Entity(tableName = "drink_windows")
data class DrinkWindow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateString: String,
    val scheduledTimeMinutes: Int, // minutes from midnight
    val targetMl: Int,
    val consumedMl: Int = 0,
    val status: WindowStatus = WindowStatus.PENDING,
    val notificationSentAt: Long? = null,
    val completedAt: Long? = null,
    val overrideReason: String? = null
)

enum class WindowStatus {
    PENDING,
    NOTIFIED,
    LOCKED,
    COMPLETED,
    OVERRIDDEN,
    ROLLED_OVER
}

@Entity(tableName = "drink_sessions")
data class DrinkSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val windowId: Long,
    val dateString: String,
    val startedAt: Long,
    val completedAt: Long? = null,
    val targetMl: Int,
    val verifiedMl: Int = 0,
    val verificationMethod: VerificationMethod = VerificationMethod.PENDING,
    val trustScore: Float = 0f,
    val isTutorial: Boolean = false
)

enum class VerificationMethod {
    PENDING,
    ML_VERIFIED,
    MANUAL_CONFIRMED,
    TUTORIAL
}

@Entity(tableName = "phone_activity")
data class PhoneActivity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val eventType: PhoneEventType
)

enum class PhoneEventType {
    SCREEN_ON,
    SCREEN_OFF,
    USER_INTERACTION
}

@Entity(tableName = "streak_events")
data class StreakEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val streakLost: Int,
    val reason: StreakLossReason,
    val bestStreakAtTime: Int
)

enum class StreakLossReason {
    OVERRIDE_LIMIT_EXCEEDED,
    END_OF_DAY_GOAL_MISSED,
    MISSED_WINDOWS
}
