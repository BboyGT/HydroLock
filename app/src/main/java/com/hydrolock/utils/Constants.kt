package com.hydrolock.utils

object Constants {
    // DataStore preferences
    const val PREFS_NAME = "hydrolock_prefs"
    const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    const val KEY_WEIGHT_KG = "weight_kg"
    const val KEY_DAILY_GOAL_ML = "daily_goal_ml"
    const val KEY_STARTING_INTAKE_ML = "starting_intake_ml"
    const val KEY_CURRENT_TARGET_ML = "current_target_ml"
    const val KEY_BEST_STREAK = "best_streak"
    const val KEY_CURRENT_STREAK = "current_streak"
    const val KEY_TRUST_SCORE = "trust_score"
    const val KEY_OVERRIDES_USED_TODAY = "overrides_used_today"
    const val KEY_OVERRIDES_RESET_DATE = "overrides_reset_date"
    const val KEY_ACTIVE_WINDOW_START = "active_window_start" // minutes from midnight
    const val KEY_ACTIVE_WINDOW_END = "active_window_end"
    const val KEY_LAST_STREAK_DATE = "last_streak_date"

    // ML & Detection
    const val LIQUID_CONFIDENCE_THRESHOLD = 0.65f
    const val MIN_VERIFIED_SESSIONS_FOR_TRUST = 5
    const val TRUST_DEBT_THRESHOLD = 3 // unverified sessions before penalty

    // Schedule
    const val GRACE_PERIOD_MS = 10 * 60 * 1000L // 10 minutes
    const val DRINK_WINDOW_INTERVAL_MINUTES = 100 // ~1.5 hours
    const val DAILY_RAMP_INCREASE_ML = 125
    const val MAX_OVERRIDES_PER_DAY = 2
    const val END_OF_DAY_SESSION_MAX_ML = 250 // max per end-of-day catch-up session
    const val END_OF_DAY_SESSION_GAP_MS = 5 * 60 * 1000L

    // WorkManager tags
    const val WORK_DRINK_WINDOW = "work_drink_window"
    const val WORK_SCHEDULE_REBUILD = "work_schedule_rebuild"
    const val WORK_ACTIVITY_MONITOR = "work_activity_monitor"
    const val WORK_END_OF_DAY = "work_end_of_day"

    // Notification IDs
    const val NOTIF_DRINK_REMINDER = 1001
    const val NOTIF_LOCK_FOREGROUND = 1002
    const val NOTIF_MONITOR_FOREGROUND = 1003

    // Intent actions
    const val ACTION_OPEN_DRINK_SESSION = "com.hydrolock.action.OPEN_DRINK_SESSION"
    const val ACTION_EMERGENCY_OVERRIDE = "com.hydrolock.action.EMERGENCY_OVERRIDE"
    const val ACTION_LOCK_RELEASED = "com.hydrolock.action.LOCK_RELEASED"

    // Extra keys
    const val EXTRA_WINDOW_ID = "extra_window_id"
    const val EXTRA_TARGET_ML = "extra_target_ml"
    const val EXTRA_LOST_STREAK = "extra_lost_streak"
    const val EXTRA_IS_TUTORIAL = "extra_is_tutorial"
}
