package com.hydrolock.ui.onboarding

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hydrolock.data.database.dao.UserProfileDao
import com.hydrolock.data.database.entities.UserProfile
import com.hydrolock.domain.usecases.StreakManager
import com.hydrolock.domain.usecases.dataStore
import com.hydrolock.utils.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userProfileDao: UserProfileDao
) : ViewModel() {

    // Temp state during onboarding
    var weightKg: Float = 0f
    var currentIntakeMl: Int = 0
    var suggestedGoalMl: Int = 0
    var confirmedGoalMl: Int = 0

    fun isOnboardingComplete(): Boolean = runBlocking {
        val prefs = context.dataStore.data.first()
        prefs[booleanPreferencesKey(Constants.KEY_ONBOARDING_COMPLETE)] == true
    }

    /**
     * Calculates recommended daily intake based on weight.
     * Standard formula: 35ml per kg of body weight.
     */
    fun calculateRecommendedIntake(weightKg: Float): Int {
        return (weightKg * 35).roundToInt()
    }

    /**
     * The gap between current intake and goal, shown during onboarding.
     */
    fun getGapMl(): Int = (confirmedGoalMl - currentIntakeMl).coerceAtLeast(0)

    /**
     * How many days to reach goal at current ramp rate.
     */
    fun getDaysToGoal(): Int {
        val gap = getGapMl()
        return if (gap <= 0) 0
        else (gap / Constants.DAILY_RAMP_INCREASE_ML)
    }

    fun saveProfile() = viewModelScope.launch {
        val profile = UserProfile(
            weightKg = weightKg,
            dailyGoalMl = confirmedGoalMl,
            startingIntakeMl = currentIntakeMl,
            currentTargetMl = currentIntakeMl,
            onboardingComplete = true
        )
        userProfileDao.saveProfile(profile)

        context.dataStore.edit { prefs ->
            prefs[booleanPreferencesKey(Constants.KEY_ONBOARDING_COMPLETE)] = true
            prefs[intPreferencesKey(Constants.KEY_DAILY_GOAL_ML)] = confirmedGoalMl
            prefs[intPreferencesKey(Constants.KEY_CURRENT_TARGET_ML)] = currentIntakeMl
        }
    }
}
