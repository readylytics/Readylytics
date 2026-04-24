package com.gregor.lauritz.healthdashboard.ui.settings

import android.content.Context
import android.content.Intent
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
import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.domain.backup.BackupUseCase
import com.gregor.lauritz.healthdashboard.domain.backup.RestoreUseCase
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringRepository
import com.gregor.lauritz.healthdashboard.domain.sync.HealthSyncUseCase
import com.gregor.lauritz.healthdashboard.domain.user.UserUseCase
import com.gregor.lauritz.healthdashboard.workers.WorkerScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.Period
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class SettingsUiState(
    val goalSleepHours: Float = 8f,
    val hrvBaselineOverride: Float? = null,
    val rhrBaselineOverride: Float? = null,
    val syncPreference: SyncPreference = SyncPreference.BY_TIME,
    val syncIntervalHours: Int = 1,
    val maxHeartRate: Int = 190,
    val autoCalculateMaxHr: Boolean = true,
    val manualZoneEditing: Boolean = false,
    val zone1MaxPercent: Float = 0.60f,
    val zone2MaxPercent: Float = 0.70f,
    val zone3MaxPercent: Float = 0.80f,
    val zone4MaxPercent: Float = 0.90f,
    val age: Int = 30,
    val birthDay: Int = 1,
    val birthMonth: Int = 1,
    val birthYear: Int = 1994,
    val gender: String? = null,
    val hrvOptimalThreshold: Float = 1.00f,
    val hrvWarningThreshold: Float = 0.90f,
    val rhrOptimalThreshold: Float = 0.95f,
    val rhrWarningThreshold: Float = 1.05f,
    val restingHrBeforeMinutes: Int = 5,
    val restingHrAfterMinutes: Int = 15,
    val consistencyThresholdMinutes: Int = 30,
    val consistencyEvaluationDays: Int = 7,
    val consistencyBaselineDays: Int = 14,
    val appTheme: AppTheme = AppTheme.SYSTEM,
    val isLoading: Boolean = true,
    val driveEmail: String? = null,
    val backupSchedule: BackupSchedule = BackupSchedule.MANUAL,
    val lastBackupTimestamp: Long = 0L,
    val isBackingUp: Boolean = false,
    val isRestoring: Boolean = false,
    val showRestoreConfirmDialog: Boolean = false,
    val driveError: String? = null,
    val pendingRestoreDir: File? = null,
)

sealed interface SettingsEvent {
    data class GoalSleepHoursChanged(
        val hours: Float,
    ) : SettingsEvent

    data class HrvBaselineChanged(
        val text: String,
    ) : SettingsEvent

    data object HrvBaselineCleared : SettingsEvent

    data class RhrBaselineChanged(
        val text: String,
    ) : SettingsEvent

    data object RhrBaselineCleared : SettingsEvent

    data class SyncPreferenceChanged(
        val pref: SyncPreference,
    ) : SettingsEvent

    data class SyncIntervalChanged(
        val hours: Int,
    ) : SettingsEvent

    data class MaxHeartRateChanged(
        val text: String,
    ) : SettingsEvent

    data class AutoCalculateMaxHrChanged(
        val enabled: Boolean,
    ) : SettingsEvent

    data class ManualZoneEditingChanged(
        val enabled: Boolean,
    ) : SettingsEvent

    data class ZonePercentagesChanged(
        val z1: Float,
        val z2: Float,
        val z3: Float,
        val z4: Float,
    ) : SettingsEvent

    data class BirthdayChanged(
        val day: Int,
        val month: Int,
        val year: Int,
    ) : SettingsEvent

    data class GenderChanged(
        val gender: String?,
    ) : SettingsEvent

    data class HrvOptimalThresholdChanged(
        val value: Float,
    ) : SettingsEvent

    data class HrvWarningThresholdChanged(
        val value: Float,
    ) : SettingsEvent

    data class RhrOptimalThresholdChanged(
        val value: Float,
    ) : SettingsEvent

    data class RhrWarningThresholdChanged(
        val value: Float,
    ) : SettingsEvent

    data class RestingHrBeforeMinutesChanged(
        val minutes: Int,
    ) : SettingsEvent

    data class RestingHrAfterMinutesChanged(
        val minutes: Int,
    ) : SettingsEvent

    data class ConsistencyThresholdChanged(
        val minutes: Int,
    ) : SettingsEvent

    data class ConsistencyEvaluationDaysChanged(
        val days: Int,
    ) : SettingsEvent

    data class ConsistencyBaselineDaysChanged(
        val days: Int,
    ) : SettingsEvent

    data class AppThemeChanged(
        val theme: AppTheme,
    ) : SettingsEvent

    data object DriveSignIn : SettingsEvent
    data object DriveSignOut : SettingsEvent
    data class BackupScheduleChanged(val schedule: BackupSchedule) : SettingsEvent
    data object BackupNow : SettingsEvent
    data object RestoreFromDrive : SettingsEvent
    data object RestoreConfirmed : SettingsEvent
    data object RestoreDismissed : SettingsEvent
    data object DismissDriveError : SettingsEvent
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
        private val driveAuthManager: DriveAuthManager,
        private val backupUseCase: BackupUseCase,
        private val restoreUseCase: RestoreUseCase,
        private val userUseCase: UserUseCase,
        private val workerScheduler: WorkerScheduler,
        private val workManager: WorkManager,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SettingsUiState())
        val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                prefsRepo.userPreferences.collect { prefs ->
                    _uiState.update {
                        it.copy(
                            goalSleepHours = prefs.goalSleepHours,
                            hrvBaselineOverride = prefs.hrvBaselineOverride,
                            rhrBaselineOverride = prefs.rhrBaselineOverride,
                            syncPreference = prefs.syncPreference,
                            syncIntervalHours = prefs.syncIntervalHours,
                            maxHeartRate = prefs.maxHeartRate,
                            autoCalculateMaxHr = prefs.autoCalculateMaxHr,
                            manualZoneEditing = prefs.manualZoneEditing,
                            zone1MaxPercent = prefs.zone1MaxPercent,
                            zone2MaxPercent = prefs.zone2MaxPercent,
                            zone3MaxPercent = prefs.zone3MaxPercent,
                            zone4MaxPercent = prefs.zone4MaxPercent,
                            age = prefs.age,
                            birthDay = prefs.birthDay,
                            birthMonth = prefs.birthMonth,
                            birthYear = prefs.birthYear,
                            gender = prefs.gender,
                            hrvOptimalThreshold = prefs.hrvOptimalThreshold,
                            hrvWarningThreshold = prefs.hrvWarningThreshold,
                            rhrOptimalThreshold = prefs.rhrOptimalThreshold,
                            rhrWarningThreshold = prefs.rhrWarningThreshold,
                            restingHrBeforeMinutes = prefs.restingHrBeforeMinutes,
                            restingHrAfterMinutes = prefs.restingHrAfterMinutes,
                            consistencyThresholdMinutes = prefs.consistencyThresholdMinutes,
                            consistencyEvaluationDays = prefs.consistencyEvaluationDays,
                            consistencyBaselineDays = prefs.consistencyBaselineDays,
                            appTheme = prefs.appTheme,
                            driveEmail = prefs.driveAccountEmail,
                            backupSchedule = prefs.backupSchedule,
                            lastBackupTimestamp = prefs.lastBackupTimestamp,
                            isLoading = false,
                        )
                    }
                }
            }
        }

        fun onEvent(event: SettingsEvent) {
            when (event) {
                is SettingsEvent.GoalSleepHoursChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateGoalSleepHours(event.hours)
                        scoringRepository.computeAndPersistDailySummary()
                    }
                is SettingsEvent.HrvBaselineChanged -> {
                    val value = event.text.toIntOrNull()?.toFloat()
                    if (value != null) {
                        viewModelScope.launch {
                            prefsRepo.updateHrvBaselineOverride(value)
                            scoringRepository.computeAndPersistDailySummary()
                        }
                    }
                }
                SettingsEvent.HrvBaselineCleared ->
                    viewModelScope.launch {
                        prefsRepo.updateHrvBaselineOverride(null)
                        scoringRepository.computeAndPersistDailySummary()
                    }
                is SettingsEvent.RhrBaselineChanged -> {
                    val value = event.text.toIntOrNull()?.toFloat()
                    if (value != null) {
                        viewModelScope.launch {
                            prefsRepo.updateRhrBaselineOverride(value)
                            scoringRepository.computeAndPersistDailySummary()
                        }
                    }
                }
                SettingsEvent.RhrBaselineCleared ->
                    viewModelScope.launch {
                        prefsRepo.updateRhrBaselineOverride(null)
                        scoringRepository.computeAndPersistDailySummary()
                    }
                is SettingsEvent.SyncPreferenceChanged ->
                    viewModelScope.launch {
                        appConfigRepo.updateSyncPreference(event.pref)
                    }
                is SettingsEvent.SyncIntervalChanged ->
                    viewModelScope.launch {
                        appConfigRepo.updateSyncIntervalHours(event.hours)
                    }
                is SettingsEvent.MaxHeartRateChanged -> {
                    val value = event.text.toIntOrNull()
                    if (value != null) {
                        viewModelScope.launch {
                            prefsRepo.updateMaxHeartRate(value)
                            healthSyncUseCase.sync()
                        }
                    }
                }

                is SettingsEvent.AutoCalculateMaxHrChanged -> {
                    viewModelScope.launch {
                        prefsRepo.updateAutoCalculateMaxHr(event.enabled)
                        if (event.enabled) {
                            userUseCase.calculateAndSetMaxHr()
                        }
                    }
                }

                is SettingsEvent.ManualZoneEditingChanged -> {
                    viewModelScope.launch {
                        prefsRepo.updateManualZoneEditing(event.enabled)
                    }
                }

                is SettingsEvent.ZonePercentagesChanged -> {
                    viewModelScope.launch {
                        prefsRepo.updateZonePercentages(event.z1, event.z2, event.z3, event.z4)
                        healthSyncUseCase.sync()
                    }
                }

                is SettingsEvent.BirthdayChanged -> {
                    viewModelScope.launch {
                        userUseCase.updateBirthday(event.day, event.month, event.year)
                    }
                }

                is SettingsEvent.GenderChanged -> {
                    viewModelScope.launch { prefsRepo.updateGender(event.gender) }
                }

                is SettingsEvent.HrvOptimalThresholdChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateHrvOptimalThreshold(event.value)
                    }
                is SettingsEvent.HrvWarningThresholdChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateHrvWarningThreshold(event.value)
                    }
                is SettingsEvent.RhrOptimalThresholdChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateRhrOptimalThreshold(event.value)
                    }
                is SettingsEvent.RhrWarningThresholdChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateRhrWarningThreshold(event.value)
                    }
                is SettingsEvent.RestingHrBeforeMinutesChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateRestingHrBeforeMinutes(event.minutes)
                        scoringRepository.computeAndPersistDailySummary()
                    }
                is SettingsEvent.RestingHrAfterMinutesChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateRestingHrAfterMinutes(event.minutes)
                        scoringRepository.computeAndPersistDailySummary()
                    }
                is SettingsEvent.ConsistencyThresholdChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateConsistencyThresholdMinutes(event.minutes)
                    }
                is SettingsEvent.ConsistencyEvaluationDaysChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateConsistencyEvaluationDays(event.days)
                    }
                is SettingsEvent.ConsistencyBaselineDaysChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateConsistencyBaselineDays(event.days)
                    }
                is SettingsEvent.AppThemeChanged ->
                    viewModelScope.launch {
                        appConfigRepo.updateAppTheme(event.theme)
                    }

                is SettingsEvent.DriveSignIn ->
                    viewModelScope.launch {
                        _uiState.update { it.copy(driveError = "Sign-in should be handled by the UI layer") }
                    }

                is SettingsEvent.DriveSignOut ->
                    viewModelScope.launch {
                        _uiState.update { it.copy(driveError = "Sign-out should be handled by the UI layer") }
                    }

                is SettingsEvent.BackupScheduleChanged ->
                    viewModelScope.launch {
                        backupPrefsRepo.updateBackupSchedule(event.schedule)
                        workerScheduler.scheduleBackupWorker(event.schedule)
                    }

                SettingsEvent.BackupNow ->
                    viewModelScope.launch {
                        _uiState.update { it.copy(isBackingUp = true, driveError = null) }
                        backupUseCase.execute()
                            .onFailure { e ->
                                _uiState.update { it.copy(driveError = e.message ?: "Backup failed") }
                            }
                        _uiState.update { it.copy(isBackingUp = false) }
                    }

                SettingsEvent.RestoreFromDrive ->
                    viewModelScope.launch {
                        _uiState.update { it.copy(isRestoring = true, driveError = null) }
                        restoreUseCase.downloadAndValidate()
                            .onSuccess { dir ->
                                _uiState.update {
                                    it.copy(
                                        isRestoring = false,
                                        showRestoreConfirmDialog = true,
                                        pendingRestoreDir = dir,
                                    )
                                }
                            }.onFailure { e ->
                                _uiState.update {
                                    it.copy(isRestoring = false, driveError = e.message ?: "Restore download failed")
                                }
                            }
                    }

                SettingsEvent.RestoreConfirmed -> {
                    val dir = _uiState.value.pendingRestoreDir ?: return
                    _uiState.update { it.copy(showRestoreConfirmDialog = false, isRestoring = true) }
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
                                _uiState.update { it.copy(isRestoring = false, driveError = result.cause.message) }
                            }
                            RestoreUseCase.RestoreResult.Success -> {
                                _uiState.update { it.copy(isRestoring = false) }
                            }
                        }
                    }
                }

                SettingsEvent.RestoreDismissed -> {
                    _uiState.value.pendingRestoreDir?.deleteRecursively()
                    _uiState.update { it.copy(showRestoreConfirmDialog = false, pendingRestoreDir = null) }
                }

                SettingsEvent.DismissDriveError ->
                    _uiState.update { it.copy(driveError = null) }
            }
        }

        fun onSignInResult(result: Result<DriveAuthState.SignedIn>) {
            result.onFailure { e ->
                _uiState.update { it.copy(driveError = e.message ?: "Sign-in failed") }
            }
        }

        fun signOut(context: Context) {
            viewModelScope.launch {
                driveAuthManager.signOut(context)
            }
        }

        fun signIn(context: Context) {
            viewModelScope.launch {
                driveAuthManager.signIn(context).onFailure { e ->
                    _uiState.update { it.copy(driveError = e.message ?: "Sign-in failed") }
                }
            }
        }
    }
