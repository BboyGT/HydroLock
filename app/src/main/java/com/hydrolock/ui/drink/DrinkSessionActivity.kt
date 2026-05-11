package com.hydrolock.ui.drink

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.hydrolock.R
import com.hydrolock.data.database.dao.DayRecordDao
import com.hydrolock.data.database.dao.DrinkSessionDao
import com.hydrolock.data.database.dao.DrinkWindowDao
import com.hydrolock.data.database.entities.*
import com.hydrolock.databinding.ActivityDrinkSessionBinding
import com.hydrolock.ml.LiquidLevelEstimator
import com.hydrolock.services.HydroAccessibilityService
import com.hydrolock.services.OverlayLockService
import com.hydrolock.utils.Constants
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────
// State
// ─────────────────────────────────────────────────────────────
enum class DrinkSessionState {
    INITIALIZING,
    BEFORE_SCAN,      // Point camera at full container
    READY_TO_DRINK,   // Container scanned, drink now
    AFTER_SCAN,       // Point camera at container after drinking
    VERIFYING,        // Processing the delta
    SUCCESS,          // Drink verified
    MANUAL_CONFIRM,   // Camera couldn't verify — manual fallback
    OVERRIDE_PROMPT   // Emergency override flow
}

// ─────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────
@HiltViewModel
class DrinkSessionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val liquidLevelEstimator: LiquidLevelEstimator,
    private val drinkWindowDao: DrinkWindowDao,
    private val drinkSessionDao: DrinkSessionDao,
    private val dayRecordDao: DayRecordDao
) : ViewModel() {

    val sessionState = MutableStateFlow(DrinkSessionState.INITIALIZING)
    val feedbackText = MutableStateFlow("Hold up your container")
    val beforeReading = MutableStateFlow<LiquidLevelEstimator.LiquidReading?>(null)
    val afterReading = MutableStateFlow<LiquidLevelEstimator.LiquidReading?>(null)
    val verifiedMl = MutableStateFlow(0)
    val confidence = MutableStateFlow(0f)
    val overridesUsed = MutableStateFlow(0)

    private var windowId: Long = -1L
    private var targetMl: Int = 0
    private var sessionId: Long = -1L
    private var isTutorial: Boolean = false
    private val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    fun initialize(windowId: Long, targetMl: Int, isTutorial: Boolean) {
        this.windowId = windowId
        this.targetMl = targetMl
        this.isTutorial = isTutorial

        viewModelScope.launch {
            // Create session record
            val sessionRecord = DrinkSession(
                windowId = windowId,
                dateString = today,
                startedAt = System.currentTimeMillis(),
                targetMl = targetMl,
                isTutorial = isTutorial
            )
            sessionId = drinkSessionDao.insertSession(sessionRecord)

            // Enable accessibility lock (not for tutorial)
            if (!isTutorial && windowId != -1L) {
                HydroAccessibilityService.isLockActive = true
                HydroAccessibilityService.lockedWindowId = windowId
                HydroAccessibilityService.lockedTargetMl = targetMl
            }

            sessionState.value = DrinkSessionState.BEFORE_SCAN
            feedbackText.value = "Point camera at your drink. Hold steady."
        }
    }

    fun processBitmap(bitmap: Bitmap) {
        viewModelScope.launch {
            when (sessionState.value) {
                DrinkSessionState.BEFORE_SCAN -> {
                    val reading = liquidLevelEstimator.analyzeFrame(bitmap) ?: return@launch
                    if (reading.confidence > LiquidLevelEstimator.CONFIDENCE_LOW) {
                        beforeReading.value = reading
                        feedbackText.value = "Got it! ${reading.estimatedCurrentMl}ml detected. Drink now."
                        sessionState.value = DrinkSessionState.READY_TO_DRINK
                    } else {
                        feedbackText.value = "Move closer. Better lighting helps."
                    }
                }
                DrinkSessionState.AFTER_SCAN -> {
                    val reading = liquidLevelEstimator.analyzeFrame(bitmap) ?: return@launch
                    val before = beforeReading.value ?: return@launch

                    if (reading.confidence > LiquidLevelEstimator.CONFIDENCE_LOW) {
                        afterReading.value = reading
                        sessionState.value = DrinkSessionState.VERIFYING
                        verifyDrink(before, reading)
                    }
                }
                else -> {}
            }
        }
    }

    fun readyForAfterScan() {
        sessionState.value = DrinkSessionState.AFTER_SCAN
        feedbackText.value = "Now point camera at your drink again."
    }

    private suspend fun verifyDrink(
        before: LiquidLevelEstimator.LiquidReading,
        after: LiquidLevelEstimator.LiquidReading
    ) {
        delay(500) // brief processing pause for UX

        val estimate = liquidLevelEstimator.calculateConsumed(before, after)
        verifiedMl.value = estimate.consumedMl
        confidence.value = estimate.confidence

        if (estimate.isVerified && estimate.consumedMl >= targetMl * 0.8f) {
            // Success — ML verified
            completeSession(estimate.consumedMl, VerificationMethod.ML_VERIFIED)
            sessionState.value = DrinkSessionState.SUCCESS
        } else if (!estimate.isVerified) {
            // Low confidence — ask manual confirm
            sessionState.value = DrinkSessionState.MANUAL_CONFIRM
            feedbackText.value = "Camera wasn't sure. Did you drink ${targetMl}ml?"
        } else {
            // Didn't drink enough
            feedbackText.value = "Only ~${estimate.consumedMl}ml detected. Need ${targetMl}ml."
            sessionState.value = DrinkSessionState.BEFORE_SCAN
        }
    }

    fun manualConfirm() = viewModelScope.launch {
        completeSession(targetMl, VerificationMethod.MANUAL_CONFIRMED)
        sessionState.value = DrinkSessionState.SUCCESS
    }

    fun manualDeny() {
        sessionState.value = DrinkSessionState.BEFORE_SCAN
        feedbackText.value = "Try again. Point camera at your drink."
    }

    private suspend fun completeSession(consumed: Int, method: VerificationMethod) {
        // Update session
        drinkSessionDao.getSession(sessionId)?.let { session ->
            drinkSessionDao.updateSession(
                session.copy(
                    completedAt = System.currentTimeMillis(),
                    verifiedMl = consumed,
                    verificationMethod = method,
                    trustScore = confidence.value
                )
            )
        }

        // Update window if real session
        if (windowId != -1L) {
            drinkWindowDao.completeWindow(windowId, consumed, System.currentTimeMillis())
        }

        // Update day record
        dayRecordDao.addConsumed(today, consumed)

        // Check if day goal met
        val record = dayRecordDao.getRecord(today)
        if (record != null && record.consumedMl + consumed >= record.targetMl) {
            dayRecordDao.markGoalMet(today)
        }

        // Release accessibility lock
        HydroAccessibilityService.isLockActive = false
    }

    fun requestOverride() {
        sessionState.value = DrinkSessionState.OVERRIDE_PROMPT
    }

    suspend fun executeOverride(reason: String): Boolean {
        val record = dayRecordDao.getRecord(today) ?: return false

        return if (record.overridesUsed < Constants.MAX_OVERRIDES_PER_DAY) {
            // Update window as overridden
            if (windowId != -1L) {
                drinkWindowDao.getWindow(windowId)?.let { window ->
                    drinkWindowDao.updateWindow(
                        window.copy(
                            status = WindowStatus.OVERRIDDEN,
                            overrideReason = reason
                        )
                    )
                }
            }

            // Mark override used
            dayRecordDao.saveRecord(
                record.copy(overridesUsed = record.overridesUsed + 1)
            )

            // Release lock
            HydroAccessibilityService.isLockActive = false
            true
        } else {
            false // Max overrides exceeded
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Activity
// ─────────────────────────────────────────────────────────────
@AndroidEntryPoint
class DrinkSessionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDrinkSessionBinding
    private val viewModel: DrinkSessionViewModel by viewModels()
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDrinkSessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val windowId = intent.getLongExtra(Constants.EXTRA_WINDOW_ID, -1L)
        val targetMl = intent.getIntExtra(Constants.EXTRA_TARGET_ML, 250)
        val isTutorial = intent.getBooleanExtra(Constants.EXTRA_IS_TUTORIAL, false)
        val isOverride = intent.getBooleanExtra(Constants.ACTION_EMERGENCY_OVERRIDE, false)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (isTutorial) {
            binding.tvTutorialBadge.visibility = View.VISIBLE
            binding.btnOverride.visibility = View.GONE
        }

        viewModel.initialize(windowId, targetMl, isTutorial)

        binding.tvTargetAmount.text = "Drink ${targetMl}ml"

        if (isOverride) {
            viewModel.requestOverride()
        }

        setupObservers()
        setupCamera()
        setupButtons()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Swallow back press if lock is active
                if (!HydroAccessibilityService.isLockActive) {
                    finish()
                }
            }
        })
    }

    private fun setupObservers() {
        collectFlow(viewModel.sessionState) { state ->
            handleStateChange(state)
        }

        collectFlow(viewModel.feedbackText) { text ->
            binding.tvFeedback.text = text
        }

        collectFlow(viewModel.verifiedMl) { ml ->
            if (ml > 0) binding.tvVerifiedAmount.text = "${ml}ml verified"
        }
    }

    private fun handleStateChange(state: DrinkSessionState) {
        when (state) {
            DrinkSessionState.BEFORE_SCAN -> {
                binding.cameraOverlayGuide.visibility = View.VISIBLE
                binding.btnDrinkDone.visibility = View.GONE
                binding.btnOverride.visibility = View.VISIBLE
                startAutoCapture()
            }
            DrinkSessionState.READY_TO_DRINK -> {
                binding.btnDrinkDone.visibility = View.VISIBLE
                binding.btnDrinkDone.text = "I've Finished Drinking"
            }
            DrinkSessionState.AFTER_SCAN -> {
                binding.btnDrinkDone.visibility = View.GONE
                startAutoCapture()
            }
            DrinkSessionState.VERIFYING -> {
                binding.progressVerifying.visibility = View.VISIBLE
                stopAutoCapture()
            }
            DrinkSessionState.SUCCESS -> {
                binding.progressVerifying.visibility = View.GONE
                showSuccessState()
            }
            DrinkSessionState.MANUAL_CONFIRM -> {
                binding.progressVerifying.visibility = View.GONE
                showManualConfirmDialog()
            }
            DrinkSessionState.OVERRIDE_PROMPT -> {
                showOverrideDialog()
            }
            else -> {}
        }
    }

    private fun showSuccessState() {
        binding.cameraPreview.visibility = View.GONE
        binding.successView.visibility = View.VISIBLE
        binding.btnClose.visibility = View.VISIBLE
        binding.successView.playAnimation() // Lottie animation
        handler.postDelayed({
            releaseAndFinish()
        }, 2500)
    }

    private fun showManualConfirmDialog() {
        AlertDialog.Builder(this, R.style.HydroAlertDialog)
            .setTitle("Did you drink it?")
            .setMessage("Camera wasn't confident enough to verify automatically.")
            .setPositiveButton("Yes, I drank it") { _, _ -> viewModel.manualConfirm() }
            .setNegativeButton("No, try again") { _, _ -> viewModel.manualDeny() }
            .setCancelable(false)
            .show()
    }

    private fun showOverrideDialog() {
        val reasons = arrayOf("Driving", "In a meeting", "Medical reason", "Other")
        AlertDialog.Builder(this, R.style.HydroAlertDialog)
            .setTitle("⚠️ Emergency Override")
            .setMessage("This will cost your streak. Use only if you absolutely can't drink right now.")
            .setSingleChoiceItems(reasons, 0, null)
            .setPositiveButton("Use Override") { dialog, _ ->
                val listView = (dialog as AlertDialog).listView
                val reason = reasons[listView.checkedItemPosition]
                lifecycleScope.launch {
                    val success = viewModel.executeOverride(reason)
                    if (!success) {
                        showOverrideLimitReached()
                    } else {
                        releaseAndFinish()
                    }
                }
            }
            .setNegativeButton("Cancel — I'll Drink") { _, _ ->
                viewModel.sessionState.value = DrinkSessionState.BEFORE_SCAN
            }
            .setCancelable(false)
            .show()
    }

    private fun showOverrideLimitReached() {
        AlertDialog.Builder(this, R.style.HydroAlertDialog)
            .setTitle("No Overrides Left")
            .setMessage("You've used all 2 overrides for today. You must drink now.")
            .setPositiveButton("OK — I'll Drink") { _, _ ->
                viewModel.sessionState.value = DrinkSessionState.BEFORE_SCAN
            }
            .setCancelable(false)
            .show()
    }

    private fun setupCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            binding.tvFeedback.text = "Camera permission needed"
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                    imageAnalysis
                )
            } catch (e: Exception) {
                binding.tvFeedback.text = "Camera setup failed: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap()
        imageProxy.close()

        val state = viewModel.sessionState.value
        if (state == DrinkSessionState.BEFORE_SCAN || state == DrinkSessionState.AFTER_SCAN) {
            viewModel.processBitmap(bitmap)
        }
    }

    private var autoCaptureRunnable: Runnable? = null

    private fun startAutoCapture() {
        stopAutoCapture()
        autoCaptureRunnable = object : Runnable {
            override fun run() {
                // Analysis is continuous via ImageAnalysis
                handler.postDelayed(this, 500)
            }
        }
        handler.post(autoCaptureRunnable!!)
    }

    private fun stopAutoCapture() {
        autoCaptureRunnable?.let { handler.removeCallbacks(it) }
        autoCaptureRunnable = null
    }

    private fun setupButtons() {
        binding.btnDrinkDone.setOnClickListener {
            viewModel.readyForAfterScan()
        }

        binding.btnOverride.setOnClickListener {
            viewModel.requestOverride()
        }

        binding.btnClose.setOnClickListener {
            releaseAndFinish()
        }
    }

    private fun releaseAndFinish() {
        HydroAccessibilityService.isLockActive = false
        // Stop overlay service if running
        stopService(Intent(this, OverlayLockService::class.java))
        finish()
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        stopAutoCapture()
        super.onDestroy()
    }

    private fun <T> kotlinx.coroutines.flow.Flow<T>.collectWith(action: (T) -> Unit) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                collect { action(it) }
            }
        }
    }

    private fun <T> collectFlow(flow: kotlinx.coroutines.flow.Flow<T>, action: (T) -> Unit) {
        flow.collectWith(action)
    }
}
