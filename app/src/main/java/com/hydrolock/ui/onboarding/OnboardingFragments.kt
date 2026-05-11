package com.hydrolock.ui.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.hydrolock.R
import com.hydrolock.databinding.*

// ─────────────────────────────────────────────────────────────
// Fragment 1: Welcome Screen
// ─────────────────────────────────────────────────────────────
class WelcomeFragment : Fragment() {
    private var _binding: FragmentWelcomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        FragmentWelcomeBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnStart.setOnClickListener {
            findNavController().navigate(R.id.action_welcome_to_weight)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ─────────────────────────────────────────────────────────────
// Fragment 2: Weight Input
// ─────────────────────────────────────────────────────────────
class WeightInputFragment : Fragment() {
    private var _binding: FragmentWeightInputBinding? = null
    private val binding get() = _binding!!
    private val viewModel: OnboardingViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        FragmentWeightInputBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Weight SeekBar 40-150kg
        binding.seekbarWeight.max = 110
        binding.seekbarWeight.progress = 30 // default 70kg

        binding.seekbarWeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val kg = progress + 40
                binding.tvWeightValue.text = "${kg}kg"
                viewModel.weightKg = kg.toFloat()
                val suggested = viewModel.calculateRecommendedIntake(kg.toFloat())
                binding.tvSuggested.text = "Recommended daily intake: ${suggested}ml"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Trigger initial display
        binding.seekbarWeight.progress = 30

        binding.btnNext.setOnClickListener {
            if (viewModel.weightKg > 0) {
                viewModel.suggestedGoalMl = viewModel.calculateRecommendedIntake(viewModel.weightKg)
                findNavController().navigate(R.id.action_weight_to_intake)
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ─────────────────────────────────────────────────────────────
// Fragment 3: Current Intake + Goal Confirmation
// ─────────────────────────────────────────────────────────────
class IntakeGoalFragment : Fragment() {
    private var _binding: FragmentIntakeGoalBinding? = null
    private val binding get() = _binding!!
    private val viewModel: OnboardingViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        FragmentIntakeGoalBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Current intake slider 0-3000ml
        binding.seekbarCurrent.max = 60 // steps of 50ml, max 3000
        binding.seekbarCurrent.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val ml = progress * 50
                binding.tvCurrentValue.text = "${ml}ml per day"
                viewModel.currentIntakeMl = ml
                updateGapDisplay()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Goal - pre-filled with suggestion, user can adjust
        binding.seekbarGoal.max = 60
        binding.seekbarGoal.progress = (viewModel.suggestedGoalMl / 50).coerceIn(0, 60)
        binding.seekbarGoal.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val ml = progress * 50
                binding.tvGoalValue.text = "${ml}ml per day"
                viewModel.confirmedGoalMl = ml
                updateGapDisplay()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        binding.seekbarGoal.progress = (viewModel.suggestedGoalMl / 50).coerceIn(0, 60)

        binding.tvSuggestedLabel.text =
            "We suggest ${viewModel.suggestedGoalMl}ml based on your weight"

        binding.btnNext.setOnClickListener {
            if (viewModel.confirmedGoalMl > viewModel.currentIntakeMl) {
                findNavController().navigate(R.id.action_intake_to_permissions)
            }
        }
    }

    private fun updateGapDisplay() {
        val gap = viewModel.getGapMl()
        val days = viewModel.getDaysToGoal()
        binding.tvRampInfo.text = if (gap > 0) {
            "We'll increase your intake by ${Constants.DAILY_RAMP_INCREASE_ML}ml each day.\nYou'll reach your goal in ~$days days."
        } else {
            "You're already at your goal!"
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    companion object {
        private val Constants = com.hydrolock.utils.Constants
    }
}

// ─────────────────────────────────────────────────────────────
// Fragment 4: Permissions
// ─────────────────────────────────────────────────────────────
class PermissionsFragment : Fragment() {
    private var _binding: FragmentPermissionsBinding? = null
    private val binding get() = _binding!!
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        FragmentPermissionsBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnOverlay.setOnClickListener {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${requireContext().packageName}"))
            startActivity(intent)
        }

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnUsageStats.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        binding.btnNotifications.setOnClickListener {
            requestNotificationPermission()
        }

        binding.btnCamera.setOnClickListener {
            requestCameraPermission()
        }

        binding.btnNext.setOnClickListener {
            findNavController().navigate(R.id.action_permissions_to_tutorial)
        }
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

// ─────────────────────────────────────────────────────────────
// Fragment 5: Tutorial
// ─────────────────────────────────────────────────────────────
class TutorialFragment : Fragment() {
    private var _binding: FragmentTutorialBinding? = null
    private val binding get() = _binding!!
    private val viewModel: OnboardingViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        FragmentTutorialBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnStartTutorial.setOnClickListener {
            // Launch drink session in tutorial mode
            val intent = Intent(requireContext(), com.hydrolock.ui.drink.DrinkSessionActivity::class.java).apply {
                putExtra(com.hydrolock.utils.Constants.EXTRA_IS_TUTORIAL, true)
                putExtra(com.hydrolock.utils.Constants.EXTRA_TARGET_ML, 200)
            }
            startActivity(intent)
        }

        binding.btnSkipTutorial.setOnClickListener {
            completeTutorial()
        }
    }

    private fun completeTutorial() {
        viewModel.saveProfile()
        (requireActivity() as OnboardingActivity).goToHome()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
