package com.hydrolock.ui.lock

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hydrolock.R
import com.hydrolock.ui.drink.DrinkSessionActivity
import com.hydrolock.utils.Constants

class LockActivity : AppCompatActivity() {

    private var windowId: Long = -1L
    private var targetMl: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.overlay_lock)

        windowId = intent.getLongExtra(Constants.EXTRA_WINDOW_ID, -1L)
        targetMl = intent.getIntExtra(Constants.EXTRA_TARGET_ML, 0)

        findViewById<TextView>(R.id.tv_amount).text = "${targetMl}ml"
        findViewById<TextView>(R.id.btn_drink_now).setOnClickListener {
            openDrinkSession(isEmergencyOverride = false)
        }
        findViewById<TextView>(R.id.btn_override).setOnClickListener {
            openDrinkSession(isEmergencyOverride = true)
        }
    }

    private fun openDrinkSession(isEmergencyOverride: Boolean) {
        val drinkIntent = Intent(this, DrinkSessionActivity::class.java).apply {
            putExtra(Constants.EXTRA_WINDOW_ID, windowId)
            putExtra(Constants.EXTRA_TARGET_ML, targetMl)
            putExtra(Constants.ACTION_EMERGENCY_OVERRIDE, isEmergencyOverride)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(drinkIntent)
        finish()
    }
}
