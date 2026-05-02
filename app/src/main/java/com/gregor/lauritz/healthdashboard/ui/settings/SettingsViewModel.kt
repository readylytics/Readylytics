package com.gregor.lauritz.healthdashboard.ui.settings

import android.content.Context
import android.content.Intent
import android.util.Log
import com.gregor.lauritz.healthdashboard.MainActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gregor.lauritz.healthdashboard.data.drive.DriveAuthManager
import com.gregor.lauritz.healthdashboard.data.drive.DriveAuthState
import com.gregor.lauritz.healthdashboard.data.preferences.AppConfigRepository
import com.gregor.lauritz.healthdashboard.data.preferences.AppTheme
import com.gregor.lauritz.healthdashboard.data.preferences.BackupPreferencesRepository
import com.gregor.lauritz.healthdashboard.data.preferences.BackupSchedule
import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsDefaults
import com.gregor.lauritz.healthdashboard.data.preferences.CircadianThresholdPreferences
import com.gregor.lauritz.healthdashboard.domain.backup.BackupUseCase
import com.gregor.lauritz.healthdashboard.domain.backup.RestoreUseCase
import com.gregor.lauritz.healthdashboard.domain.circadian.CircadianThresholdValue
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringRepository
import com.gregor.lauritz.healthdashboard.domain.sync.HealthSyncUseCase
import com.gregor.lauritz.healthdashboard.domain.sync.ResyncHealthConnectUseCase
import com.gregor.lauritz.healthdashboard.domain.user.UserUseCase
import com.gregor.lauritz.healthdashboard.workers.WorkerScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.Period
import java.util.concurrent.TimeUnit
import javax.inject.Inject

// --- Decomposed State Classes (Issue #7) ---

data class ThresholdSettingsState(
    val circadianThresholdOverride: Int? = null,
    val isUpdatingThreshold: Boolean = false,
    val thresholdError: String? = null,
    val hrvOptimalThreshold: Float = SettingsDefaults.HRV_OPTIMAL_THRESHOLD,
    val hrvWarningThreshold: Float = SettingsDefaults.HRV_WARNING_THRESHOLD,
    val rhrOptimalThreshold: Float = SettingsDefaults.RHR_OPTIMAL_THRESHOLD,
    val rhrWarningThreshold: Float = SettingsDefaults.RHR_WARNING_THRESHOLD,
    val consistencyThresholdMinutes: Int = SettingsDefaults.CONSISTENCY_THRESHOLD_MINUTES,
    val consistencyEvaluationDays: Int = SettingsDefaults.CONSISTENCY_EVALUATION_DAYS,
    val consistencyBaselineDays: Int = SettingsDefaults.CONSISTENCY_BASELINE_DAYS,
)

data class SleepSettingsState(
    val goalSleepHours: Float = SettingsDefaults.GOAL_SLEEP_HOURS,
    val hrvBaselineOverride: Float? = SettingsDefaults.HRV_BASELINE_OVERRIDE,
    val rhrBaselineOverride: Float? = SettingsDefaults.RHR_BASELINE_OVERRIDE,
    val restingHrBeforeMinutes: Int = SettingsDefaults.RESTING_HR_BEFORE_MINUTES,
    val restingHrAfterMinutes: Int = SettingsDefaults.RESTING_HR_AFTER_MINUTES,
)

data class PhysiologySettingsState(
    val physiologyProfile: PhysiologyProfile = SettingsDefaults.PHYSIOLOGY_PROFILE,
    val age: Int = SettingsDefaults.AGE,
    val birthDay: Int = SettingsDefaults.BIRTH_DAY,
    val birthMonth: Int = SettingsDefaults.BIRTH_MONTH,
    val birthYear: Int = SettingsDefaults.BIRTH_YEAR,
    val gender: String? = SettingsDefaults.GENDER,
)

data class HeartRateZonesState(
    val maxHeartRate: Int = SettingsDefaults.MAX_HEART_RATE,
    val autoCalculateMaxHr: Boolean = SettingsDefaults.AUTO_CALCULATE_MAX_HR,
    val manualZoneEditing: Boolean = SettingsDefaults.MANUAL_ZONE_EDITING,
    val zone1MinPercent: Float = SettingsDefaults.ZONE_1_MIN_PERCENT,
    val zone1MaxPercent: Float = SettingsDefaults.ZONE_1_MAX_PERCENT,
    val zone2MaxPercent: Float = SettingsDefaults.ZONE_2_MAX_PERCENT,
    val zone3MaxPercent: Float = SettingsDefaults.ZONE_3_MAX_PERCENT,
    val zone4MaxPercent: Float = SettingsDefaults.ZONE_4_MAX_PERCENT,
    val zone1MinBpm: Int = SettingsDefaults.ZONE_1_MIN_BPM,
    val zone1MaxBpm: Int = SettingsDefaults.ZONE_1_MAX_BPM,
    val zone2MaxBpm: Int = SettingsDefaults.ZONE_2_MAX_BPM,
    val zone3MaxBpm: Int = SettingsDefaults.ZONE_3_MAX_BPM,
    val zone4MaxBpm: Int = SettingsDefaults.ZONE_4_MAX_BPM,
)

data class CloudBackupState(
    val driveEmail: String? = SettingsDefaults.DRIVE_ACCOUNT_EMAIL,
    val backupSchedule: BackupSchedule = SettingsDefaults.BACKUP_SCHEDULE,
    val lastBackupTimestamp: Long = SettingsDefaults.LAST_BACKUP_TIMESTAMP,
    val isBackingUp: Boolean = false,
    val isRestoring: Boolean = false,
    val showRestoreConfirmDialog: Boolean = false,
    val driveError: String? = null,
    val pendingRestoreDir: File? = null,
)

data class UIState(
    val isLoading: Boolean = true,
    val isResyncing: Boolean = false,
    val appTheme: AppTheme = SettingsDefaults.APP_THEME,
    val dynamicColorEnabled: Boolean = SettingsDefaults.DYNAMIC_COLOR_ENABLED,
)

data class SettingsUiState(
    val threshold: ThresholdSettingsState = ThresholdSettingsState(),
    val sleep: SleepSettingsState = SleepSettingsState(),
    val physiology: PhysiologySettingsState = PhysiologySettingsState(),
    val heartRate: HeartRateZonesState = HeartRateZonesState(),
    val cloud: CloudBackupState = CloudBackupState(),
    val ui: UIState = UIState(),
    // Shared simple settings
    val syncPreference: SyncPreference = SettingsDefaults.SYNC_PREFERENCE,
    val syncIntervalHours: Int = SettingsDefaults.SYNC_INTERVAL_HOURS,
    val paiScalingFactor: Float = SettingsDefaults.PAI_SCALING_FACTOR,
    val stepGoal: Int = SettingsDefaults.STEP_GOAL,
    val retentionDaysEnabled: Boolean = SettingsDefaults.RETENTION_DAYS_ENABLED,
    val retentionDays: Int = SettingsDefaults.RETENTION_DAYS,
    val collapseCloudData: Boolean = SettingsDefaults.COLLAPSE_CLOUD_DATA,
    val collapseHealthConnect: Boolean = SettingsDefaults.COLLAPSE_HEALTH_CONNECT,
    val collapseBaselinesThresholds: Boolean = SettingsDefaults.COLLAPSE_BASELINES_THRESHOLDS,
    val collapseDisplay: Boolean = SettingsDefaults.COLLAPSE_DISPLAY,
    val collapseAdvanced: Boolean = SettingsDefaults.COLLAPSE_ADVANCED,
)

sealed interface SettingsEvent {
    data class GoalSleepHoursChanged(val hours: Float) : SettingsEvent
    data class HrvBaselineChanged(val text: String) : SettingsEvent
    data object HrvBaselineCleared : SettingsEvent
    data class RhrBaselineChanged(val text: String) : SettingsEvent
    data object RhrBaselineCleared : SettingsEvent
    data class SyncPreferenceChanged(val pref: SyncPreference) : SettingsEvent
    data class SyncIntervalChanged(val hours: Int) : SettingsEvent
    data class MaxHeartRateChanged(val text: String) : SettingsEvent
    data class AutoCalculateMaxHrChanged(val enabled: Boolean) : SettingsEvent
    data class ManualZoneEditingChanged(val enabled: Boolean) : SettingsEvent
    data class ZonePercentagesChanged(val z1Min: Float, val z1Max: Float, val z2Max: Float, val z3Max: Float, val z4Max: Float) : SettingsEvent
    data class ZoneBpmsChanged(val z1Min: Int, val z1Max: Int, val z2Max: Int, val z3Max: Int, val z4Max: Int) : SettingsEvent
    data class BirthdayChanged(val day: Int, val month: Int, val year: Int) : SettingsEvent
    data class GenderChanged(val gender: String?) : SettingsEvent
    data class HrvOptimalThresholdChanged(val value: Float) : SettingsEvent
    data class HrvWarningThresholdChanged(val value: Float) : SettingsEvent
    data class RhrOptimalThresholdChanged(val value: Float) : SettingsEvent
    data class RhrWarningThresholdChanged(val value: Float) : SettingsEvent
    data class RestingHrBeforeMinutesChanged(val minutes: Int) : SettingsEvent
    data class RestingHrAfterMinutesChanged(val minutes: Int) : SettingsEvent
    data class ConsistencyThresholdChanged(val minutes: Int) : SettingsEvent
    data class ConsistencyEvaluationDaysChanged(val days: Int) : SettingsEvent
    data class ConsistencyBaselineDaysChanged(val days: Int) : SettingsEvent
    data class PaiScalingFactorChanged(val value: Float) : SettingsEvent
    data class StepGoalChanged(val steps: Int) : SettingsEvent
    data class AppThemeChanged(val theme: AppTheme) : SettingsEvent
    data class DynamicColorEnabledChanged(val enabled: Boolean) : SettingsEvent
    data object DriveSignIn : SettingsEvent
    data object DriveSignOut : SettingsEvent
    data class BackupScheduleChanged(val schedule: BackupSchedule) : SettingsEvent
    data object BackupNow : SettingsEvent
    data object RestoreFromDrive : SettingsEvent
    data object RestoreConfirmed : SettingsEvent
    data object RestoreDismissed : SettingsEvent
    data object DismissDriveError : SettingsEvent
    data class RetentionDaysEnabledChanged(val enabled: Boolean) : SettingsEvent
    data class RetentionDaysChanged(val days: Int) : SettingsEvent
    data object ResyncHealthConnect : SettingsEvent
    data class CollapseCloudDataChanged(val collapsed: Boolean) : SettingsEvent
    data class CollapseHealthConnectChanged(val collapsed: Boolean) : SettingsEvent
    data class CollapseBaselinesThresholdsChanged(val collapsed: Boolean) : SettingsEvent
    data class CollapseDisplayChanged(val collapsed: Boolean) : SettingsEvent
    data class CollapseAdvancedChanged(val collapsed: Boolean) : SettingsEvent
    data class PhysiologyProfileChanged(val profile: PhysiologyProfile) : SettingsEvent
    data class CircadianThresholdOverrideChanged(val minutes: Int?) : SettingsEvent
    data object DismissThresholdError : SettingsEvent
}

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val prefsRepo: UserPreferencesRepository,
        private val appConfigRepo: AppConfigRepository,
        private val backupPrefsRepo: BackupPreferencesRepository,
        private val scoringRepository: ScoringRepository,
        private val healthSyncUseCase: HealthSyncUseCase,
        private val resyncHealthConnectUseCase: ResyncHealthConnectUseCase,
        private val driveAuthManager: DriveAuthManager,
        private val backupUseCase: BackupUseCase,
        private val restoreUseCase: RestoreUseCase,
        private val userUseCase: UserUseCase,
        private val workerScheduler: WorkerScheduler,
        private val workManager: WorkManager,
        private val circadianThresholdPreferences: CircadianThresholdPreferences,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SettingsUiState())
        val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                combine(
                    prefsRepo.userPreferences,
                    circadianThresholdPreferences.overrideMinutesFlow,
                    appConfigRepo.dynamicColorEnabled
                ) { prefs, decryptedOverride, dynamicColor ->
                    Triple(prefs, decryptedOverride, dynamicColor)
                }.collect { (prefs, decryptedOverride, dynamicColor) ->
                    _uiState.update {
                        it.copy(
                            threshold = it.threshold.copy(
                                circadianThresholdOverride = decryptedOverride,
                                hrvOptimalThreshold = prefs.hrvOptimalThreshold,
                                hrvWarningThreshold = prefs.hrvWarningThreshold,
                                rhrOptimalThreshold = prefs.rhrOptimalThreshold,
                                rhrWarningThreshold = prefs.rhrWarningThreshold,
                                consistencyThresholdMinutes = prefs.consistencyThresholdMinutes,
                                consistencyEvaluationDays = prefs.consistencyEvaluationDays,
                                consistencyBaselineDays = prefs.consistencyBaselineDays,
                            ),
                            sleep = it.sleep.copy(
                                goalSleepHours = prefs.goalSleepHours,
                                hrvBaselineOverride = prefs.hrvBaselineOverride,
                                rhrBaselineOverride = prefs.rhrBaselineOverride,
                                restingHrBeforeMinutes = prefs.restingHrBeforeMinutes,
                                restingHrAfterMinutes = prefs.restingHrAfterMinutes,
                            ),
                            physiology = it.physiology.copy(
                                physiologyProfile = prefs.physiologyProfile,
                                age = prefs.age,
                                birthDay = prefs.birthDay,
                                birthMonth = prefs.birthMonth,
                                birthYear = prefs.birthYear,
                                gender = prefs.gender,
                            ),
                            heartRate = it.heartRate.copy(
                                maxHeartRate = prefs.maxHeartRate,
                                autoCalculateMaxHr = prefs.autoCalculateMaxHr,
                                manualZoneEditing = prefs.manualZoneEditing,
                                zone1MinPercent = prefs.zone1MinPercent,
                                zone1MaxPercent = prefs.zone1MaxPercent,
                                zone2MaxPercent = prefs.zone2MaxPercent,
                                zone3MaxPercent = prefs.zone3MaxPercent,
                                zone4MaxPercent = prefs.zone4MaxPercent,
                                zone1MinBpm = prefs.zone1MinBpm,
                                zone1MaxBpm = prefs.zone1MaxBpm,
                                zone2MaxBpm = prefs.zone2MaxBpm,
                                zone3MaxBpm = prefs.zone3MaxBpm,
                                zone4MaxBpm = prefs.zone4MaxBpm,
                            ),
                            cloud = it.cloud.copy(
                                driveEmail = prefs.driveAccountEmail,
                                backupSchedule = prefs.backupSchedule,
                                lastBackupTimestamp = prefs.lastBackupTimestamp,
                            ),
                            ui = it.ui.copy(
                                isLoading = false,
                                appTheme = prefs.appTheme,
                                dynamicColorEnabled = dynamicColor,
                            ),
                            syncPreference = prefs.syncPreference,
                            syncIntervalHours = prefs.syncIntervalHours,
                            paiScalingFactor = prefs.paiScalingFactor,
                            stepGoal = prefs.stepGoal,
                            retentionDaysEnabled = prefs.retentionDaysEnabled,
                            retentionDays = prefs.retentionDays,
                            collapseCloudData = prefs.collapseCloudData,
                            collapseHealthConnect = prefs.collapseHealthConnect,
                            collapseBaselinesThresholds = prefs.collapseBaselinesThresholds,
                            collapseDisplay = prefs.collapseDisplay,
                            collapseAdvanced = prefs.collapseAdvanced,
                        )
                    }
                }
            }
        }

        fun onEvent(event: SettingsEvent) {
            when (event) {
                is SettingsEvent.GoalSleepHoursChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateGoalSleepHours(hours = event.hours)
                        scoringRepository.computeAndPersistDailySummary()
                    }
                is SettingsEvent.HrvBaselineChanged -> {
                    val value = event.text.toIntOrNull()?.toFloat()
                    if (value != null) {
                        viewModelScope.launch {
                            prefsRepo.updateHrvBaselineOverride(rmssdMs = value)
                            scoringRepository.computeAndPersistDailySummary()
                        }
                    }
                }
                SettingsEvent.HrvBaselineCleared ->
                    viewModelScope.launch {
                        prefsRepo.updateHrvBaselineOverride(rmssdMs = null)
                        scoringRepository.computeAndPersistDailySummary()
                    }
                is SettingsEvent.RhrBaselineChanged -> {
                    val value = event.text.toIntOrNull()?.toFloat()
                    if (value != null) {
                        viewModelScope.launch {
                            prefsRepo.updateRhrBaselineOverride(bpm = value)
                            scoringRepository.computeAndPersistDailySummary()
                        }
                    }
                }
                SettingsEvent.RhrBaselineCleared ->
                    viewModelScope.launch {
                        prefsRepo.updateRhrBaselineOverride(bpm = null)
                        scoringRepository.computeAndPersistDailySummary()
                    }
                is SettingsEvent.SyncPreferenceChanged ->
                    viewModelScope.launch {
                        appConfigRepo.updateSyncPreference(preference = event.pref)
                    }
                is SettingsEvent.SyncIntervalChanged ->
                    viewModelScope.launch {
                        appConfigRepo.updateSyncIntervalHours(hours = event.hours)
                    }
                is SettingsEvent.MaxHeartRateChanged -> {
                    val value = event.text.toIntOrNull()
                    if (value != null) {
                        viewModelScope.launch {
                            prefsRepo.updateMaxHeartRate(bpm = value)
                            healthSyncUseCase.sync()
                        }
                    }
                }

                is SettingsEvent.AutoCalculateMaxHrChanged -> {
                    viewModelScope.launch {
                        prefsRepo.updateAutoCalculateMaxHr(enabled = event.enabled)
                        if (event.enabled) {
                            userUseCase.calculateAndSetMaxHr()
                        }
                    }
                }

                is SettingsEvent.ManualZoneEditingChanged -> {
                    viewModelScope.launch {
                        prefsRepo.updateManualZoneEditing(enabled = event.enabled)
                    }
                }

                is SettingsEvent.ZonePercentagesChanged -> {
                    viewModelScope.launch {
                        prefsRepo.updateZonePercentages(
                            z1Min = event.z1Min,
                            z1Max = event.z1Max,
                            z2Max = event.z2Max,
                            z3Max = event.z3Max,
                            z4Max = event.z4Max
                        )
                        healthSyncUseCase.sync()
                    }
                }

                is SettingsEvent.ZoneBpmsChanged -> {
                    viewModelScope.launch {
                        prefsRepo.updateZoneBpms(
                            z1Min = event.z1Min,
                            z1Max = event.z1Max,
                            z2Max = event.z2Max,
                            z3Max = event.z3Max,
                            z4Max = event.z4Max
                        )
                        healthSyncUseCase.sync()
                    }
                }

                is SettingsEvent.BirthdayChanged -> {
                    viewModelScope.launch {
                        userUseCase.updateBirthday(day = event.day, month = event.month, year = event.year)
                    }
                }

                is SettingsEvent.GenderChanged -> {
                    viewModelScope.launch { prefsRepo.updateGender(gender = event.gender) }
                }

                is SettingsEvent.HrvOptimalThresholdChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateHrvOptimalThreshold(value = event.value)
                    }
                is SettingsEvent.HrvWarningThresholdChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateHrvWarningThreshold(value = event.value)
                    }
                is SettingsEvent.RhrOptimalThresholdChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateRhrOptimalThreshold(value = event.value)
                    }
                is SettingsEvent.RhrWarningThresholdChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateRhrWarningThreshold(value = event.value)
                    }
                is SettingsEvent.RestingHrBeforeMinutesChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateRestingHrBeforeMinutes(minutes = event.minutes)
                        scoringRepository.computeAndPersistDailySummary()
                    }
                is SettingsEvent.RestingHrAfterMinutesChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateRestingHrAfterMinutes(minutes = event.minutes)
                        scoringRepository.computeAndPersistDailySummary()
                    }
                is SettingsEvent.ConsistencyThresholdChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateConsistencyThresholdMinutes(minutes = event.minutes)
                    }
                is SettingsEvent.ConsistencyEvaluationDaysChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateConsistencyEvaluationDays(days = event.days)
                    }
                is SettingsEvent.ConsistencyBaselineDaysChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateConsistencyBaselineDays(days = event.days)
                    }
                is SettingsEvent.PaiScalingFactorChanged ->
                    viewModelScope.launch {
                        prefsRepo.updatePaiScalingFactor(value = event.value)
                        scoringRepository.computeAndPersistDailySummary()
                    }
                is SettingsEvent.StepGoalChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateStepGoal(steps = event.steps)
                    }
                is SettingsEvent.AppThemeChanged ->
                    viewModelScope.launch {
                        appConfigRepo.updateAppTheme(theme = event.theme)
                    }

                is SettingsEvent.DynamicColorEnabledChanged ->
                    viewModelScope.launch {
                        appConfigRepo.updateDynamicColorEnabled(enabled = event.enabled)
                        _uiState.update { it.copy(ui = it.ui.copy(dynamicColorEnabled = event.enabled)) }
                    }

                is SettingsEvent.DriveSignIn ->
                    viewModelScope.launch {
                        _uiState.update { it.copy(cloud = it.cloud.copy(driveError = "Sign-in should be handled by the UI layer")) }
                    }

                is SettingsEvent.DriveSignOut ->
                    viewModelScope.launch {
                        _uiState.update { it.copy(cloud = it.cloud.copy(driveError = "Sign-out should be handled by the UI layer")) }
                    }

                is SettingsEvent.BackupScheduleChanged ->
                    viewModelScope.launch {
                        backupPrefsRepo.updateBackupSchedule(schedule = event.schedule)
                        workerScheduler.scheduleBackupWorker(schedule = event.schedule)
                    }

                SettingsEvent.BackupNow ->
                    viewModelScope.launch {
                        _uiState.update { it.copy(cloud = it.cloud.copy(isBackingUp = true, driveError = null)) }
                        backupUseCase.execute()
                            .onFailure { e ->
                                _uiState.update { it.copy(cloud = it.cloud.copy(driveError = e.message ?: "Backup failed")) }
                            }
                        _uiState.update { it.copy(cloud = it.cloud.copy(isBackingUp = false)) }
                    }

                SettingsEvent.RestoreFromDrive ->
                    viewModelScope.launch {
                        _uiState.update { it.copy(cloud = it.cloud.copy(isRestoring = true, driveError = null)) }
                        restoreUseCase.downloadAndValidate()
                            .onSuccess { dir ->
                                _uiState.update {
                                    it.copy(
                                        cloud = it.cloud.copy(
                                            isRestoring = false,
                                            showRestoreConfirmDialog = true,
                                            pendingRestoreDir = dir,
                                        )
                                    )
                                }
                            }.onFailure { e ->
                                _uiState.update {
                                    it.copy(cloud = it.cloud.copy(isRestoring = false, driveError = e.message ?: "Restore download failed"))
                                }
                            }
                    }

                SettingsEvent.RestoreConfirmed -> {
                    val dir = _uiState.value.cloud.pendingRestoreDir ?: return
                    _uiState.update { it.copy(cloud = it.cloud.copy(showRestoreConfirmDialog = false, isRestoring = true)) }
                    viewModelScope.launch {
                        when (val result = restoreUseCase.applyRestore(dir)) {
                            RestoreUseCase.RestoreResult.SuccessRequiresRestart -> {
                                val restartIntent = Intent(context, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                }
                                context.startActivity(restartIntent)
                                // We still need to terminate the current process to ensure
                                // that the app is fully re-initialized from the new database.
                                kotlin.system.exitProcess(0)
                            }
                            is RestoreUseCase.RestoreResult.Failure -> {
                                _uiState.update { it.copy(cloud = it.cloud.copy(isRestoring = false, driveError = result.cause.message)) }
                            }
                            RestoreUseCase.RestoreResult.Success -> {
                                _uiState.update { it.copy(cloud = it.cloud.copy(isRestoring = false)) }
                            }
                        }
                    }
                }

                SettingsEvent.RestoreDismissed -> {
                    _uiState.value.cloud.pendingRestoreDir?.deleteRecursively()
                    _uiState.update { it.copy(cloud = it.cloud.copy(showRestoreConfirmDialog = false, pendingRestoreDir = null)) }
                }

                SettingsEvent.DismissDriveError ->
                    _uiState.update { it.copy(cloud = it.cloud.copy(driveError = null)) }

                is SettingsEvent.RetentionDaysEnabledChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateRetentionDaysEnabled(enabled = event.enabled)
                    }

                is SettingsEvent.RetentionDaysChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateRetentionDays(days = event.days)
                    }

                SettingsEvent.ResyncHealthConnect ->
                    viewModelScope.launch {
                        _uiState.update { it.copy(ui = it.ui.copy(isResyncing = true)) }
                        resyncHealthConnectUseCase.execute()
                        _uiState.update { it.copy(ui = it.ui.copy(isResyncing = false)) }
                    }

                is SettingsEvent.CollapseCloudDataChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateCollapseCloudData(collapsed = event.collapsed)
                    }

                is SettingsEvent.CollapseHealthConnectChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateCollapseHealthConnect(collapsed = event.collapsed)
                    }

                is SettingsEvent.CollapseBaselinesThresholdsChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateCollapseBaselinesThresholds(collapsed = event.collapsed)
                    }

                is SettingsEvent.CollapseDisplayChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateCollapseDisplay(collapsed = event.collapsed)
                    }

                is SettingsEvent.CollapseAdvancedChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateCollapseAdvanced(collapsed = event.collapsed)
                    }
                is SettingsEvent.PhysiologyProfileChanged ->
                    viewModelScope.launch {
                        prefsRepo.updatePhysiologyProfile(profile = event.profile)
                        scoringRepository.computeAndPersistDailySummary()
                    }

                is SettingsEvent.CircadianThresholdOverrideChanged -> {
                    viewModelScope.launch {
                        val previousValue = _uiState.value.threshold.circadianThresholdOverride
                        
                        try {
                            // Step 1: Validate threshold value (Issue #2)
                            val validation = CircadianThresholdValue.tryCreate(minutes = event.minutes)
                            
                            validation.onSuccess { _ ->
                                _uiState.update { it.copy(threshold = it.threshold.copy(isUpdatingThreshold = true, thresholdError = null)) }
                                
                                // Step 2: Update preference (DataStore adapter handles encryption - Issue #1)
                                circadianThresholdPreferences.setOverride(minutes = event.minutes)
                                
                                // Step 3: Recalculate scoring (Issue #4)
                                scoringRepository.computeAndPersistDailySummary()
                                
                                _uiState.update { it.copy(threshold = it.threshold.copy(isUpdatingThreshold = false)) }
                                Log.d("SettingsViewModel", "Threshold updated successfully to ${event.minutes}")
                                
                            }.onFailure { error ->
                                Log.e("SettingsViewModel", "Validation failed", error)
                                _uiState.update { it.updateThresholdError("Invalid threshold value. Range: 0-90 minutes.") }
                            }
                        } catch (e: Exception) {
                            // Step 4: On failure, revert preference to previous value (Issue #4)
                            Log.e("SettingsViewModel", "Failed to update threshold - reverting", e)
                            
                            try {
                                circadianThresholdPreferences.setOverride(minutes = previousValue)
                            } catch (rollbackError: Exception) {
                                Log.e("SettingsViewModel", "Failed to rollback preference update", rollbackError)
                            }
                            
                            _uiState.update {
                                it.copy(
                                    threshold = it.threshold.copy(
                                        isUpdatingThreshold = false,
                                        thresholdError = "Failed to update threshold settings. Changes rolled back.",
                                        circadianThresholdOverride = previousValue
                                    )
                                )
                            }
                        }
                    }
                }

                SettingsEvent.DismissThresholdError -> {
                    _uiState.update { it.copy(threshold = it.threshold.copy(thresholdError = null)) }
                }
            }
        }

        private fun SettingsUiState.updateThresholdError(error: String): SettingsUiState =
            this.copy(threshold = this.threshold.copy(thresholdError = error))

        fun onSignInResult(result: Result<DriveAuthState.SignedIn>) {
            result.onFailure { e ->
                _uiState.update { it.copy(cloud = it.cloud.copy(driveError = e.message ?: "Sign-in failed")) }
            }
        }

        fun signOut(context: Context) {
            viewModelScope.launch {
                driveAuthManager.signOut(context = context)
            }
        }

        fun signIn(context: Context) {
            viewModelScope.launch {
                driveAuthManager.signIn(activityContext = context).onFailure { e ->
                    _uiState.update { it.copy(cloud = it.cloud.copy(driveError = e.message ?: "Sign-in failed")) }
                }
            }
        }
    }
