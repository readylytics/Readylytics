package com.gregor.lauritz.healthdashboard.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gregor.lauritz.healthdashboard.data.drive.DriveAuthManager
import com.gregor.lauritz.healthdashboard.data.drive.DriveAuthState
import com.gregor.lauritz.healthdashboard.data.preferences.AppTheme
import com.gregor.lauritz.healthdashboard.data.preferences.BackupSchedule
import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.domain.backup.BackupUseCase
import com.gregor.lauritz.healthdashboard.domain.backup.RestoreUseCase
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringRepository
import com.gregor.lauritz.healthdashboard.workers.BackupWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class SettingsUiState(
    val goalSleepHours: Float = 8f,
    val hrvBaselineOverride: Float? = null,
    val rhrBaselineOverride: Float? = null,
    val syncPreference: SyncPreference = SyncPreference.BY_TIME,
    val syncIntervalHours: Int = 1,
    val maxHeartRate: Int = 190,
    val hrvOptimalThreshold: Float = 1.00f,
    val hrvWarningThreshold: Float = 0.90f,
    val rhrOptimalThreshold: Float = 0.95f,
    val rhrWarningThreshold: Float = 1.05f,
    val restingHrBeforeMinutes: Int = 5,
    val restingHrAfterMinutes: Int = 15,
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

    data class AppThemeChanged(
        val theme: AppTheme,
    ) : SettingsEvent

    data class DriveSignIn(val activityContext: Context) : SettingsEvent
    data class DriveSignOut(val context: Context) : SettingsEvent
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
        private val prefsRepo: UserPreferencesRepository,
        private val scoringRepository: ScoringRepository,
        private val driveAuthManager: DriveAuthManager,
        private val backupUseCase: BackupUseCase,
        private val restoreUseCase: RestoreUseCase,
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
                            hrvOptimalThreshold = prefs.hrvOptimalThreshold,
                            hrvWarningThreshold = prefs.hrvWarningThreshold,
                            rhrOptimalThreshold = prefs.rhrOptimalThreshold,
                            rhrWarningThreshold = prefs.rhrWarningThreshold,
                            restingHrBeforeMinutes = prefs.restingHrBeforeMinutes,
                            restingHrAfterMinutes = prefs.restingHrAfterMinutes,
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
                        prefsRepo.updateSyncPreference(event.pref)
                    }
                is SettingsEvent.SyncIntervalChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateSyncIntervalHours(event.hours)
                    }
                is SettingsEvent.MaxHeartRateChanged -> {
                    val value = event.text.toIntOrNull()
                    if (value != null) {
                        viewModelScope.launch { prefsRepo.updateMaxHeartRate(value) }
                    }
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
                is SettingsEvent.AppThemeChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateAppTheme(event.theme)
                    }

                is SettingsEvent.DriveSignIn ->
                    viewModelScope.launch {
                        driveAuthManager.signIn(event.activityContext).onFailure { e ->
                            _uiState.update { it.copy(driveError = e.message ?: "Sign-in failed") }
                        }
                    }

                is SettingsEvent.DriveSignOut ->
                    viewModelScope.launch {
                        driveAuthManager.signOut(event.context)
                    }

                is SettingsEvent.BackupScheduleChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateBackupSchedule(event.schedule)
                        rescheduleBackupWorker(event.schedule)
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
                        restoreUseCase.applyRestore(dir)
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

        private fun rescheduleBackupWorker(schedule: BackupSchedule) {
            workManager.cancelUniqueWork(BACKUP_WORK_NAME)
            if (schedule == BackupSchedule.MANUAL) return
            val intervalDays = if (schedule == BackupSchedule.DAILY) 1L else 7L
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            val request =
                PeriodicWorkRequestBuilder<BackupWorker>(intervalDays, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                    .build()
            workManager.enqueueUniquePeriodicWork(
                BACKUP_WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        companion object {
            const val BACKUP_WORK_NAME = "health_backup_periodic"
        }
    }
