package app.readylytics.health.feature.settings

import app.readylytics.health.core.model.data.preferences.Gender
import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import app.readylytics.health.core.model.data.preferences.UnitSystem
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.Vo2MaxSourceMode
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.DisplaySettings
import app.readylytics.health.core.model.domain.preferences.PhysiologySettings
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig
import app.readylytics.health.core.model.domain.scoring.TrimpModel
import app.readylytics.health.core.model.domain.sync.HealthDataRefresh
import app.readylytics.health.core.model.domain.user.UserProfileActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsReopenAfterSetupFlowTest {
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun settingsViewModel_readsBirthdayHeight_andUnitSystemSavedDuringSetup() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val harness = LocalSettingsSetupHarness()

            harness.seedProfile(
                birthDate = LocalDate.of(1991, 11, 9),
                heightCm = 176f,
                physiologyProfile = PhysiologyProfile.ACTIVE,
                unitSystem = UnitSystem.IMPERIAL,
            )

            val viewModel = harness.buildPhysiologySettingsViewModel()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.uiState.collect {}
                }
            try {
                advanceUntilIdle()

                val uiState = viewModel.uiState.value
                assertEquals(LocalDate.of(1991, 11, 9), uiState.birthDate)
                assertEquals(176f, uiState.heightCm)
                assertEquals(UnitSystem.IMPERIAL, uiState.unitSystem)
            } finally {
                job.cancel()
            }
        }

    private class LocalSettingsSetupHarness {
        private val preferences = MutableStateFlow(UserPreferences())

        private val reader =
            object : UserPreferencesReader {
                override val userPreferences: StateFlow<UserPreferences> = preferences
            }

        private val physiologySettings =
            object : PhysiologySettings {
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

        private val displaySettings =
            object : DisplaySettings {
                override suspend fun updateDynamicColorEnabled(enabled: Boolean) = Unit

                override suspend fun updateUnitSystem(unitSystem: UnitSystem) {
                    preferences.update { it.copy(unitSystem = unitSystem) }
                }

                override suspend fun updateWeekStartDay(day: DayOfWeek) = error("Unexpected call: updateWeekStartDay")

                override suspend fun updateHrrToleranceSeconds(value: Int) =
                    error("Unexpected call: updateHrrToleranceSeconds")

                override suspend fun updateAppTheme(
                    theme: app.readylytics.health.core.model.data.preferences.AppTheme,
                ) = error("Unexpected call: updateAppTheme")

                override suspend fun updateFallbackThemeColor(
                    color: app.readylytics.health.core.model.data.preferences.FallbackThemeColor,
                ) = error("Unexpected call: updateFallbackThemeColor")

                override suspend fun updateCustomPaletteEnabled(enabled: Boolean) =
                    error("Unexpected call: updateCustomPaletteEnabled")

                override suspend fun updateCustomPrimaryColor(color: Long) =
                    error("Unexpected call: updateCustomPrimaryColor")

                override suspend fun updateCustomSecondaryColor(color: Long) =
                    error("Unexpected call: updateCustomSecondaryColor")

                override suspend fun updateCustomTertiaryColor(color: Long) =
                    error("Unexpected call: updateCustomTertiaryColor")

                override suspend fun updateRasScalingFactor(value: Float) =
                    error("Unexpected call: updateRasScalingFactor")

                override suspend fun updateStepGoal(steps: Int) = error("Unexpected call: updateStepGoal")

                override suspend fun updateRetentionDaysEnabled(enabled: Boolean) =
                    error("Unexpected call: updateRetentionDaysEnabled")

                override suspend fun updateRetentionDays(days: Int) = error("Unexpected call: updateRetentionDays")

                override suspend fun updateTrimpModel(model: TrimpModel) = error("Unexpected call: updateTrimpModel")

                override suspend fun updateBanisterMultiplier(value: Float) =
                    error("Unexpected call: updateBanisterMultiplier")

                override suspend fun updateChengBeta(value: Float) = error("Unexpected call: updateChengBeta")

                override suspend fun updateItrimB(value: Float) = error("Unexpected call: updateItrimB")

                override suspend fun updateResidualFatigueHalfLifeHours(hours: Float) =
                    error("Unexpected call: updateResidualFatigueHalfLifeHours")

                override suspend fun updateResidualFatigueGain(value: Float) =
                    error("Unexpected call: updateResidualFatigueGain")

                override suspend fun resetResidualFatigueToDefaults() =
                    error("Unexpected call: resetResidualFatigueToDefaults")

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

        private val userProfileActions =
            object : UserProfileActions {
                override suspend fun updateBirthday(date: LocalDate): Result<Unit> {
                    preferences.update {
                        it.copy(
                            birthDate = date.toString(),
                            isBirthdayConfigured = true,
                        )
                    }
                    return Result.success(Unit)
                }

                override suspend fun calculateAndSetMaxHr(): Result<Unit> =
                    error("Unexpected call: calculateAndSetMaxHr")
            }

        private val healthDataRefresh =
            object : HealthDataRefresh {
                override suspend fun refreshAffectedWindow() = Unit

                override suspend fun refreshHistorical() = Unit

                override suspend fun refreshTrainingReadiness(
                    config: app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig,
                ) = Unit
            }

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

        fun buildPhysiologySettingsViewModel(): PhysiologySettingsViewModel =
            PhysiologySettingsViewModel(
                settingsRepo = reader,
                physiologySettings = physiologySettings,
                displaySettings = displaySettings,
                userUseCase = userProfileActions,
                healthDataRefresh = healthDataRefresh,
            )
    }
}
