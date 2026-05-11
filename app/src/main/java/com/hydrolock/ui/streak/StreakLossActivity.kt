package com.hydrolock.ui.streak

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AnimationUtils
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hydrolock.R
import com.hydrolock.data.database.dao.StreakEventDao
import com.hydrolock.databinding.ActivityStreakLossBinding
import com.hydrolock.domain.usecases.StreakManager
import com.hydrolock.utils.Constants
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StreakLossViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val streakManager: StreakManager,
    private val streakEventDao: StreakEventDao
) : ViewModel() {

    fun getNextMilestone(streakLost: Int) = streakManager.getNextMilestone(streakLost)
    fun getMilestoneLabel(milestone: Int) = streakManager.getMilestoneLabel(milestone)
}

@AndroidEntryPoint
class StreakLossActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStreakLossBinding
    private val viewModel: StreakLossViewModel by viewModels()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreakLossBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val streakLost = intent.getIntExtra(Constants.EXTRA_LOST_STREAK, 0)

        setupStreakLossScreen(streakLost)
    }

    private fun setupStreakLossScreen(streakLost: Int) {
        // Phase 1: Show the number — large and centered
        binding.tvStreakNumber.text = streakLost.toString()
        binding.tvStreakLabel.text = if (streakLost == 1) "day" else "days"

        // Phase 2: After 1 second, shatter/burn animation
        handler.postDelayed({
            playDestructionAnimation()
        }, 1000)

        // Phase 3: After 2.5s, reveal the loss details
        handler.postDelayed({
            revealLossDetails(streakLost)
        }, 2500)

        // Phase 4: After 5s, show the acknowledge button
        handler.postDelayed({
            showAcknowledgeButton()
        }, 5000)

        binding.btnAcknowledge.setOnClickListener {
            finish()
        }

        // Prevent early dismiss
        binding.btnAcknowledge.isEnabled = false

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Cannot dismiss until button becomes available
                if (binding.btnAcknowledge.isEnabled) {
                    finish()
                }
            }
        })
    }

    private fun playDestructionAnimation() {
        // The streak number burns/shatters
        binding.lottieDestruction.visibility = View.VISIBLE
        binding.lottieDestruction.playAnimation()

        // Shake the number before it disappears
        val shake = AnimationUtils.loadAnimation(this, R.anim.shake)
        binding.tvStreakNumber.startAnimation(shake)

        handler.postDelayed({
            binding.tvStreakNumber.visibility = View.INVISIBLE
            binding.tvStreakLabel.visibility = View.INVISIBLE
        }, 600)
    }

    private fun revealLossDetails(streakLost: Int) {
        binding.lottieDestruction.visibility = View.GONE

        binding.layoutLossDetails.visibility = View.VISIBLE

        // The cold headline
        binding.tvColdHeadline.text = when {
            streakLost == 0 -> "Streak broken."
            streakLost < 3 -> "$streakLost ${if (streakLost == 1) "day" else "days"}. Gone."
            streakLost < 7 -> "$streakLost days. Gone."
            streakLost < 14 -> "$streakLost days of progress. Gone."
            streakLost < 30 -> "$streakLost days of discipline. Gone."
            else -> "$streakLost days. All of it. Gone."
        }

        val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in_slow)
        binding.layoutLossDetails.startAnimation(fadeIn)

        // Show what they were close to losing
        val nextMilestone = viewModel.getNextMilestone(streakLost)
        if (nextMilestone != null) {
            val label = viewModel.getMilestoneLabel(nextMilestone)
            binding.tvMilestoneLost.text = "\"$label\" was ${nextMilestone - streakLost} days away."
            binding.tvMilestoneLost.visibility = View.VISIBLE
        }

        // New streak: zero
        binding.tvNewStreak.text = "Your streak"
        binding.tvNewStreakValue.text = "0"
        binding.tvNewStreakSubLabel.text = "days"
    }

    private fun showAcknowledgeButton() {
        binding.btnAcknowledge.isEnabled = true
        binding.btnAcknowledge.visibility = View.VISIBLE
        binding.btnAcknowledge.text = "I understand"

        val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in_slow)
        binding.btnAcknowledge.startAnimation(fadeIn)
    }

}
