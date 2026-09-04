package app.readylytics.health.feature.onboarding

import app.readylytics.health.core.model.data.preferences.Gender
import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import app.readylytics.health.core.model.data.preferences.UnitSystem
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.DisplaySettings
import app.readylytics.health.core.model.domain.preferences.PhysiologySettings
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.preferences.Vo2MaxSourceMode
import app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig
import app.readylytics.health.core.model.domain.scoring.TrimpModel
import app.readylytics.health.core.model.domain.service.BmiService
import app.readylytics.health.core.model.domain.sync.HealthDataRefresh
import app.readylytics.health.core.model.domain.user.UserProfileActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.DayOfWeek
import java.time.LocalDate

class FirstSetupFlowHarness(
    private val advanceUntilIdle: () -> Unit,
) {
    val preferences = MutableStateFlow(UserPreferences())

    val physiologySettings: PhysiologySettings = PhysiologyPort()
    val displaySettings: DisplaySettings = DisplayPort()
    val reader: UserPreferencesReader = Reader()
    val userProfileActions = UserProfileActionsPort()
    val healthDataRefresh = HealthDataRefreshPort()

    fun buildOnboardingViewModel(): OnboardingViewModel =
        OnboardingViewModel(
            physiologySettings = physiologySettings,
            displaySettings = displaySettings,
            bmiService = BmiService(),
        )

    fun seedProfile(
        birthDate: LocalDate,
        heightCm: Float,
        physiologyProfile: PhysiologyProfile,
        unitSystem: UnitSystem,
    ) {
        preferences.update {
            it.copy(
                birthDate = birthDate.toString(),
                isBirthdayConfigured = true,
                heightCm = heightCm,
                physiologyProfile = physiologyProfile,
                unitSystem = unitSystem,
            )
        }
    }

    fun advanceUntilIdle() {
        advanceUntilIdle.invoke()
    }

    private inner class Reader : UserPreferencesReader {
        override val userPreferences: StateFlow<UserPreferences> = preferences
    }

    private inner class PhysiologyPort : PhysiologySettings {
        override suspend fun updateBirthday(date: LocalDate) {
            preferences.update {
                it.copy(
                    birthDate = date.toString(),
                    isBirthdayConfigured = true,
                )
            }
        }

        override suspend fun updateGender(gender: String?) {
            preferences.update { it.copy(gender = Gender.fromStringOrNull(gender)) }
        }

        override suspend fun updateHeight(heightCm: Float?) {
            preferences.update { it.copy(heightCm = heightCm) }
        }

        override suspend fun updatePhysiologyProfile(profile: PhysiologyProfile) {
            preferences.update { it.copy(physiologyProfile = profile) }
        }

        override suspend fun updateVo2MaxSourceMode(mode: Vo2MaxSourceMode) {
            preferences.update { it.copy(vo2MaxSourceMode = mode) }
        }
    }

    private inner class DisplayPort : DisplaySettings {
        override suspend fun updateDynamicColorEnabled(enabled: Boolean) {
            preferences.update { it.copy(dynamicColorEnabled = enabled) }
        }

        override suspend fun updateUnitSystem(unitSystem: UnitSystem) {
            preferences.update { it.copy(unitSystem = unitSystem) }
        }

        override suspend fun updateWeekStartDay(day: DayOfWeek) = error("Unexpected call: updateWeekStartDay")

        override suspend fun updateAppTheme(theme: app.readylytics.health.core.model.data.preferences.AppTheme) =
            error("Unexpected call: updateAppTheme")

        override suspend fun updateFallbackThemeColor(
            color: app.readylytics.health.core.model.data.preferences.FallbackThemeColor,
        ) = error("Unexpected call: updateFallbackThemeColor")

        override suspend fun updateCustomPaletteEnabled(enabled: Boolean) =
            error("Unexpected call: updateCustomPaletteEnabled")

        override suspend fun updateCustomPrimaryColor(color: Long) = error("Unexpected call: updateCustomPrimaryColor")

        override suspend fun updateCustomSecondaryColor(color: Long) =
            error("Unexpected call: updateCustomSecondaryColor")

        override suspend fun updateCustomTertiaryColor(color: Long) =
            error("Unexpected call: updateCustomTertiaryColor")

        override suspend fun updateRasScalingFactor(value: Float) = error("Unexpected call: updateRasScalingFactor")

        override suspend fun updateStepGoal(steps: Int) = error("Unexpected call: updateStepGoal")

        override suspend fun updateRetentionDaysEnabled(enabled: Boolean) =
            error("Unexpected call: updateRetentionDaysEnabled")

        override suspend fun updateRetentionDays(days: Int) = error("Unexpected call: updateRetentionDays")

        override suspend fun updateHrrToleranceSeconds(value: Int) {
            error("Unexpected call: updateHrrToleranceSeconds")
        }

        override suspend fun updateTrimpModel(model: TrimpModel) = error("Unexpected call: updateTrimpModel")

        override suspend fun updateBanisterMultiplier(value: Float) = error("Unexpected call: updateBanisterMultiplier")

        override suspend fun updateChengBeta(value: Float) = error("Unexpected call: updateChengBeta")

        override suspend fun updateItrimB(value: Float) = error("Unexpected call: updateItrimB")

        override suspend fun updateResidualFatigueHalfLifeHours(hours: Float) =
            error("Unexpected call: updateResidualFatigueHalfLifeHours")

        override suspend fun updateResidualFatigueGain(value: Float) =
            error("Unexpected call: updateResidualFatigueGain")

        override suspend fun resetResidualFatigueToDefaults() = error("Unexpected call: resetResidualFatigueToDefaults")

        override suspend fun updateTrainingReadinessParameters(
            scale: Float,
            weight: Float,
        ) = error("Unexpected call: updateTrainingReadinessParameters")

        override suspend fun resetTrainingReadinessToDefaults() =
            error("Unexpected call: resetTrainingReadinessToDefaults")

        override suspend fun updateAppliedTrainingReadinessParameters(config: TrainingReadinessConfig) =
            error("Unexpected call: updateAppliedTrainingReadinessParameters")

        override suspend fun updateBulkDisplayModeNoticeDismissed(dismissed: Boolean) =
            error("Unexpected call: updateBulkDisplayModeNoticeDismissed")

        override suspend fun updateLastGlobalDisplayMode(mode: DashboardCardDisplayMode?) =
            error("Unexpected call: updateLastGlobalDisplayMode")
    }

    inner class UserProfileActionsPort : UserProfileActions {
        override suspend fun updateBirthday(date: LocalDate): Result<Unit> {
            preferences.update {
                it.copy(
                    birthDate = date.toString(),
                    isBirthdayConfigured = true,
                )
            }
            return Result.success(Unit)
        }

        override suspend fun calculateAndSetMaxHr(): Result<Unit> = error("Unexpected call: calculateAndSetMaxHr")
    }

    inner class HealthDataRefreshPort : HealthDataRefresh {
        var refreshCalls = 0
            private set
        var historicalRefreshCalls = 0
            private set

        override suspend fun refreshAffectedWindow() {
            refreshCalls += 1
        }

        override suspend fun refreshHistorical() {
            historicalRefreshCalls += 1
        }

        override suspend fun refreshTrainingReadiness(
            config: app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig,
        ) {
            error("Unexpected call: refreshTrainingReadiness")
        }
    }
}
