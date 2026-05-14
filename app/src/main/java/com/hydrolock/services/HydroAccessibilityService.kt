package com.hydrolock.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.hydrolock.ui.drink.DrinkSessionActivity

/**
 * Accessibility Service that monitors foreground app changes.
 * When the phone is locked (lock state is ACTIVE), it detects
 * if the user has navigated away from the drink session and
 * immediately relaunches it.
 *
 * This is the core of the "lock" mechanic on Android.
 */
class HydroAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var isLockActive: Boolean = false
        @Volatile
        var lockedWindowId: Long = -1L
        @Volatile
        var lockedTargetMl: Int = 0
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isLockActive) return
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // Don't interfere with our own app or system UI
        val exemptPackages = setOf(
            "com.hydrolock",
            "com.android.systemui",
            "android",
            "com.google.android.inputmethod.latin"
        )

        if (packageName in exemptPackages) return

        // User navigated away — bring drink session back
        val intent = Intent(this, DrinkSessionActivity::class.java).apply {
            putExtra("window_id", lockedWindowId)
            putExtra("target_ml", lockedTargetMl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        // Service interrupted — nothing to clean up
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Service ready
    }
}
