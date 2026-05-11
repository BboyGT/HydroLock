package com.hydrolock.ui.home

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.hydrolock.data.database.dao.DayRecordDao
import com.hydrolock.data.database.dao.DrinkWindowDao
import com.hydrolock.data.database.dao.UserProfileDao
import com.hydrolock.data.database.entities.DayRecord
import com.hydrolock.data.database.entities.DrinkWindow
import com.hydrolock.data.database.entities.UserProfile
import com.hydrolock.databinding.ActivityHomeBinding
import com.hydrolock.domain.usecases.StreakManager
import com.hydrolock.ui.drink.DrinkSessionActivity
import com.hydrolock.utils.Constants
import com.hydrolock.workers.DayScheduleWorker
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dayRecordDao: DayRecordDao,
    private val drinkWindowDao: DrinkWindowDao,
    private val userProfileDao: UserProfileDao,
    private val streakManager: StreakManager
) : ViewModel() {

    private val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    val todayRecord: StateFlow<DayRecord?> = dayRecordDao.getRecordFlow(today)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val todayWindows: StateFlow<List<DrinkWindow>> =
        drinkWindowDao.getWindowsForDate(today)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val profile: StateFlow<UserProfile?> = userProfileDao.getProfileFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentStreak = MutableStateFlow(0)
    val bestStreak = MutableStateFlow(0)

    init {
        loadStreaks()
        ensureDayScheduled()
    }

    private fun loadStreaks() = viewModelScope.launch {
        currentStreak.value = streakManager.getCurrentStreak()
        bestStreak.value = streakManager.getBestStreak()
    }

    private fun ensureDayScheduled() = viewModelScope.launch {
        val record = dayRecordDao.getRecord(today)
        if (record == null) {
            val request = OneTimeWorkRequestBuilder<DayScheduleWorker>()
                .setInitialDelay(1, TimeUnit.SECONDS)
                .addTag(Constants.WORK_SCHEDULE_REBUILD)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                Constants.WORK_SCHEDULE_REBUILD,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    fun getProgressPercent(): Int {
        val record = todayRecord.value ?: return 0
        return ((record.consumedMl.toFloat() / record.targetMl) * 100).toInt().coerceIn(0, 100)
    }

    fun getRemainingMl(): Int {
        val record = todayRecord.value ?: return 0
        return (record.targetMl - record.consumedMl).coerceAtLeast(0)
    }
}

// ─────────────────────────────────────────────────────────────
// Activity
// ─────────────────────────────────────────────────────────────
@AndroidEntryPoint
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val viewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupProgressChart()
        setupObservers()
        setupClickListeners()
    }

    private fun setupObservers() {
        // Today's record
        viewModel.todayRecord.collectWith { record ->
            if (record != null) {
                updateProgressDisplay(record)
            } else {
                binding.tvConsumed.text = "0ml"
                updateProgressChart(0, 1)
            }
        }

        // Drink windows timeline
        viewModel.todayWindows.collectWith { windows ->
            updateWindowsTimeline(windows)
        }

        // Streaks
        viewModel.currentStreak.collectWith { streak ->
            binding.tvCurrentStreak.text = "🔥 $streak day streak"
        }

        viewModel.bestStreak.collectWith { best ->
            binding.tvBestStreak.text = "Best: $best days"
        }

        // Profile
        viewModel.profile.collectWith { profile ->
            if (profile != null) {
                binding.tvDailyTarget.text = "Daily target: ${profile.currentTargetMl}ml"
                binding.tvGoal.text = "Goal: ${profile.dailyGoalMl}ml"
            }
        }
    }

    private fun updateProgressDisplay(record: DayRecord) {
        binding.tvConsumed.text = "${record.consumedMl}ml"
        binding.tvTarget.text = "of ${record.targetMl}ml"
        updateProgressChart(record.consumedMl, record.targetMl)
        binding.tvRemaining.text = "${viewModel.getRemainingMl()}ml remaining"

        if (record.goalMet) {
            binding.tvStatus.text = "✅ Goal complete!"
            binding.tvStatus.visibility = View.VISIBLE
        }
    }

    private fun updateWindowsTimeline(windows: List<DrinkWindow>) {
        // In production this populates a RecyclerView with window cards
        // Each card shows: time, amount, status (pending/completed/overridden)
        binding.tvWindowsInfo.text = buildString {
            val completed = windows.count { it.status.name == "COMPLETED" }
            val total = windows.size
            append("$completed of $total drink windows complete")
        }
    }

    private fun setupClickListeners() {
        binding.fabDrinkNow.setOnClickListener {
            startActivity(Intent(this, DrinkSessionActivity::class.java).apply {
                putExtra(Constants.EXTRA_TARGET_ML, 250) // manual drink
            })
        }
    }

    private fun setupProgressChart() {
        binding.progressCircle.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setDrawEntryLabels(false)
            setUsePercentValues(false)
            setHoleColor(Color.TRANSPARENT)
            holeRadius = 70f
            transparentCircleRadius = 75f
        }
    }

    private fun updateProgressChart(consumed: Int, target: Int) {
        val safeTarget = target.coerceAtLeast(1)
        val remaining = (safeTarget - consumed).coerceAtLeast(0)

        val entries = listOf(
            PieEntry(consumed.toFloat(), "Consumed"),
            PieEntry(remaining.toFloat(), "Remaining")
        )

        val dataSet = PieDataSet(entries, "").apply {
            colors = listOf(
                ContextCompat.getColor(this@HomeActivity, com.hydrolock.R.color.accent_blue),
                ContextCompat.getColor(this@HomeActivity, com.hydrolock.R.color.text_muted)
            )
            setDrawValues(false)
        }

        binding.progressCircle.data = PieData(dataSet)
        binding.progressCircle.invalidate()
    }

    private fun <T> kotlinx.coroutines.flow.Flow<T>.collectWith(action: (T) -> Unit) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                collect { action(it) }
            }
        }
    }
}
