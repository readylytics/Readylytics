package app.readylytics.health.data.backup

import app.readylytics.health.core.model.data.preferences.BackupSchedule
import app.readylytics.health.core.model.domain.util.logW
import app.readylytics.health.core.model.workers.WorkerScheduler
import app.readylytics.health.data.preferences.BackupScheduleProto
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.WorkoutDetailLayoutMapper
import app.readylytics.health.data.security.EncryptionManager
import javax.inject.Inject

class RestorePreferencesApplier
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val layoutRepositories: RestoreLayoutRepositories,
        private val workerScheduler: WorkerScheduler,
        private val encryptionManager: EncryptionManager,
    ) {
        suspend fun restorePreferences(
            backup: UserPreferencesBackup,
            providedPassword: String?,
        ) {
            val encryptedProvidedPassword =
                providedPassword
                    ?.takeIf { it.isNotBlank() }
                    ?.let { encryptionManager.encrypt(it) }
            settingsRepository.batchUpdate {
                applySyncAndBaselineSettings(backup)
                applyZoneSettings(backup)
                applyBirthdaySettings(backup)
                applyDemographicAndThresholdSettings(backup)
                applyThemeAndBackupSettings(backup)
                applyScalingAndProfileSettings(backup)
                applyDeviceSettings(backup)
                applyScoringSettings(backup)
                backup.lastRecalcGoalSleepHours?.let { lastRecalcGoalSleepHours = it }
                backup.lastRecalcHypersomniaOnsetPercent?.let { lastRecalcHypersomniaOnsetPercent = it }
                encryptedProvidedPassword?.let { backupPasswordHash = it }
            }
            restoreLayouts(backup)
            restoreScheduling(backup)
        }

        private suspend fun restoreLayouts(backup: UserPreferencesBackup) {
            backup.dashboardCards?.let {
                layoutRepositories.cardConfigurationRepository.updateDashboardCardConfigurations(it)
            }
            backup.vitalsCards?.let {
                layoutRepositories.vitalsLayoutRepository.updateVitalsCardConfigurations(it)
            }
            backup.vitalsCharts?.let {
                layoutRepositories.vitalsLayoutRepository.updateVitalsChartConfigurations(it)
            }
            backup.sleepTopCards?.let {
                layoutRepositories.sleepLayoutRepository.updateSleepTopCardConfigurations(it)
            }
            backup.sleepCharts?.let {
                layoutRepositories.sleepLayoutRepository.updateSleepChartConfigurations(it)
            }
            backup.sleepMetricCards?.let {
                layoutRepositories.sleepLayoutRepository.updateSleepMetricCardConfigurations(it)
            }
            backup.workoutCards?.let {
                layoutRepositories.workoutsLayoutRepository.updateWorkoutCardConfigurations(it)
            }
            backup.workoutCharts?.let {
                layoutRepositories.workoutsLayoutRepository.updateWorkoutChartConfigurations(it)
            }
            backup.workoutHistory?.let {
                layoutRepositories.workoutsLayoutRepository.updateWorkoutHistoryConfigurations(it)
            }
            backup.workoutDetailLayouts?.let { layouts ->
                layoutRepositories.workoutDetailLayoutRepository.replaceAll(
                    layouts
                        .mapNotNull { (key, items) ->
                            val type = WorkoutDetailLayoutMapper.typeFromKey(key)
                            if (type == null) {
                                logW("RestorePreferencesApplier") {
                                    "Ignoring unknown workout layout type in backup: $key"
                                }
                                null
                            } else {
                                type to items
                            }
                        }.toMap(),
                )
            }
        }

        private suspend fun restoreScheduling(backup: UserPreferencesBackup) {
            backup.backgroundSyncEnabled?.let { enabled ->
                if (enabled) {
                    backup.backgroundSyncIntervalMinutes?.let { workerScheduler.schedulePeriodicSync(it.toLong()) }
                } else {
                    workerScheduler.cancelPeriodicSync()
                }
            }
            backup.backupSchedule?.let { raw ->
                resolveProtoEnum(raw, "BACKUP_", BackupScheduleProto::valueOf)
                    ?.let { schedule -> workerScheduler.scheduleBackupWorker(schedule.toDomain()) }
            }
        }
    }

private fun BackupScheduleProto.toDomain() =
    when (this) {
        BackupScheduleProto.BACKUP_MANUAL -> BackupSchedule.MANUAL
        BackupScheduleProto.BACKUP_DAILY -> BackupSchedule.DAILY
        BackupScheduleProto.BACKUP_WEEKLY -> BackupSchedule.WEEKLY
        BackupScheduleProto.UNRECOGNIZED -> BackupSchedule.MANUAL
    }
