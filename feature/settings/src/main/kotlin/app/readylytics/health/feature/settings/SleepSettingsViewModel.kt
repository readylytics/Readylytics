package app.readylytics.health.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.di.ApplicationScope
import app.readylytics.health.domain.preferences.SleepSettings
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.domain.validation.SettingsValidators
import app.readylytics.health.domain.validation.ValidationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SleepSettingsViewModel
    @Inject
    constructor(
        private val settingsReader: UserPreferencesReader,
        private val sleepSettings: SleepSettings,
        private val scoringRepository: ScoringRepository,
        @param:ApplicationScope private val appScope: CoroutineScope,
    ) : ViewModel() {
        val uiState: StateFlow<SleepSettingsState> =
            settingsReader.userPreferences
                .map { prefs ->
                    SleepSettingsState(
                        goalSleepHours = prefs.goalSleepHours,
                        hrvBaselineOverride = prefs.hrvBaselineOverride,
                        rhrBaselineOverride = prefs.rhrBaselineOverride,
                        restingHrPercentile = prefs.restingHrPercentile,
                        strainLoadSourceMode = prefs.strainLoadSourceMode,
                        rasSourceMode = prefs.rasSourceMode,
                        coreMergeGapMinutes = prefs.coreMergeGapMinutes,
                        supplementalCutoffMinutesOfDay = prefs.supplementalCutoffMinutesOfDay,
                        minimumCountedSleepSegmentMinutes = prefs.minimumCountedSleepSegmentMinutes,
                        supplementalArchitectureCoveragePercent = prefs.supplementalArchitectureCoveragePercent,
                    )
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = SleepSettingsState(),
                )

        fun onEvent(event: SettingsEvent) {
            when (event) {
                is SettingsEvent.GoalSleepHoursChanged ->
                    appScope.launch {
                        sleepSettings.updateGoalSleepHours(hours = event.hours)
                        scoringRepository.computeAndPersistDailySummary()
                    }

                is SettingsEvent.HrvBaselineChanged -> {
                    val value = event.text.toIntOrNull()?.toFloat()
                    val isValid =
                        SettingsValidators.HRV_BASELINE_RULE.validate(event.text) is ValidationResult.Valid
                    if (isValid) {
                        appScope.launch {
                            sleepSettings.updateHrvBaselineOverride(rmssdMs = value)
                            scoringRepository.computeAndPersistDailySummary()
                        }
                    }
                }

                SettingsEvent.HrvBaselineCleared ->
                    appScope.launch {
                        sleepSettings.updateHrvBaselineOverride(rmssdMs = null)
                        scoringRepository.computeAndPersistDailySummary()
                    }

                is SettingsEvent.CoreMergeGapMinutesChanged -> {
                    val isValid =
                        SettingsValidators.CORE_MERGE_GAP_MINUTES_RULE.validate(event.minutes) is ValidationResult.Valid
                    if (isValid) {
                        appScope.launch {
                            sleepSettings.updateCoreMergeGapMinutes(event.minutes)
                            scoringRepository.computeAndPersistDailySummary()
                        }
                    }
                }

                is SettingsEvent.SupplementalCutoffMinutesOfDayChanged -> {
                    val isValid =
                        SettingsValidators.SUPPLEMENTAL_CUTOFF_MINUTES_OF_DAY_RULE.validate(event.minutes) is
                            ValidationResult.Valid
                    if (isValid) {
                        appScope.launch {
                            sleepSettings.updateSupplementalCutoffMinutesOfDay(event.minutes)
                            scoringRepository.computeAndPersistDailySummary()
                        }
                    }
                }

                is SettingsEvent.MinimumCountedSleepSegmentMinutesChanged -> {
                    val isValid =
                        SettingsValidators.MINIMUM_COUNTED_SLEEP_SEGMENT_MINUTES_RULE.validate(event.minutes) is
                            ValidationResult.Valid
                    if (isValid) {
                        appScope.launch {
                            sleepSettings.updateMinimumCountedSleepSegmentMinutes(event.minutes)
                            scoringRepository.computeAndPersistDailySummary()
                        }
                    }
                }

                is SettingsEvent.SupplementalArchitectureCoveragePercentChanged -> {
                    val isValid =
                        SettingsValidators.SUPPLEMENTAL_ARCHITECTURE_COVERAGE_PERCENT_RULE.validate(event.percent) is
                            ValidationResult.Valid
                    if (isValid) {
                        appScope.launch {
                            sleepSettings.updateSupplementalArchitectureCoveragePercent(event.percent)
                            scoringRepository.computeAndPersistDailySummary()
                        }
                    }
                }

                is SettingsEvent.RhrBaselineChanged -> {
                    val value = event.text.toIntOrNull()?.toFloat()
                    val isValid =
                        SettingsValidators.RHR_BASELINE_RULE.validate(event.text) is ValidationResult.Valid
                    if (isValid) {
                        appScope.launch {
                            sleepSettings.updateRhrBaselineOverride(bpm = value)
                            scoringRepository.computeAndPersistDailySummary()
                        }
                    }
                }

                SettingsEvent.RhrBaselineCleared ->
                    appScope.launch {
                        sleepSettings.updateRhrBaselineOverride(bpm = null)
                        scoringRepository.computeAndPersistDailySummary()
                    }

                is SettingsEvent.RestingHrPercentileChanged -> {
                    val validation =
                        SettingsValidators.RESTING_HR_PERCENTILE_RULE.validate(event.percentile.toString())
                    if (validation is ValidationResult.Valid) {
                        appScope.launch {
                            sleepSettings.updateRestingHrPercentile(percentile = event.percentile)
                            scoringRepository.computeAndPersistDailySummary()
                        }
                    }
                }

                is SettingsEvent.StrainLoadSourceModeChanged ->
                    appScope.launch { sleepSettings.updateStrainLoadSourceMode(event.mode) }

                is SettingsEvent.RasSourceModeChanged ->
                    appScope.launch { sleepSettings.updateRasSourceMode(event.mode) }

                else -> {}
            }
        }
    }
