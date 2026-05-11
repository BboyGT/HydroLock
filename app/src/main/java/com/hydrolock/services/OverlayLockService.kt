package com.hydrolock.services

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.hydrolock.HydroLockApp
import com.hydrolock.R
import com.hydrolock.ui.drink.DrinkSessionActivity
import com.hydrolock.utils.Constants
import dagger.hilt.android.AndroidEntryPoint

/**
 * Foreground service that draws a full-screen overlay over all other apps.
 * Started when the user ignores a drink reminder past the grace period.
 *
 * The overlay intercepts all touch events except the "Drink Now" button
 * and the emergency override. It stays active until DrinkSessionActivity
 * sends ACTION_LOCK_RELEASED.
 */
@AndroidEntryPoint
class OverlayLockService : Service() {

    private var overlayView: View? = null
    private lateinit var windowManager: WindowManager

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val windowId = intent?.getLongExtra(Constants.EXTRA_WINDOW_ID, -1L) ?: -1L
        val targetMl = intent?.getIntExtra(Constants.EXTRA_TARGET_ML, 0) ?: 0

        startForeground(Constants.NOTIF_LOCK_FOREGROUND, buildForegroundNotification())
        showOverlay(windowId, targetMl)

        return START_REDELIVER_INTENT
    }

    private fun showOverlay(windowId: Long, targetMl: Int) {
        if (overlayView != null) removeOverlay()

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_lock, null)

        // Set up overlay content
        overlayView?.findViewById<TextView>(R.id.tv_amount)?.text = "${targetMl}ml"
        overlayView?.findViewById<TextView>(R.id.btn_drink_now)?.setOnClickListener {
            launchDrinkSession(windowId, targetMl)
        }
        overlayView?.findViewById<TextView>(R.id.btn_override)?.setOnClickListener {
            launchOverrideFlow(windowId)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(overlayView, params)
    }

    private fun launchDrinkSession(windowId: Long, targetMl: Int) {
        val intent = Intent(this, DrinkSessionActivity::class.java).apply {
            putExtra(Constants.EXTRA_WINDOW_ID, windowId)
            putExtra(Constants.EXTRA_TARGET_ML, targetMl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        removeOverlay()
        stopSelf()
    }

    private fun launchOverrideFlow(windowId: Long) {
        val intent = Intent(this, DrinkSessionActivity::class.java).apply {
            putExtra(Constants.EXTRA_WINDOW_ID, windowId)
            putExtra(Constants.ACTION_EMERGENCY_OVERRIDE, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        removeOverlay()
        stopSelf()
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            overlayView = null
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, HydroLockApp.CHANNEL_LOCK_ENFORCEMENT)
            .setContentTitle("HydroLock Active")
            .setContentText("Complete your drink to unlock your phone.")
            .setSmallIcon(R.drawable.ic_water_drop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_EMERGENCY_OVERRIDE = "com.hydrolock.EMERGENCY_OVERRIDE"
    }
}
