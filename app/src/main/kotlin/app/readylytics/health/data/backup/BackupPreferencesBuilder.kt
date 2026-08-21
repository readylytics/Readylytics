package app.readylytics.health.data.backup

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepChartConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.core.model.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.core.model.domain.workouts.WorkoutChartConfiguration
import app.readylytics.health.core.model.domain.workouts.WorkoutHistoryConfiguration
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutDetailItemConfiguration
import java.time.LocalDate
import java.time.format.DateTimeParseException

internal fun buildUserPreferencesBackup(
    prefs: UserPreferences,
    layouts: BackupLayoutSnapshots,
): UserPreferencesBackup {
    val b1 = buildSyncAndBaselines(prefs)
    val b2 = buildZonesAndDemographics(b1, prefs)
    val b3 = buildThresholdsAndDisplay(b2, prefs)
    val b4 = buildScoringAndDevices(b3, prefs)
    return attachLayouts(b4, layouts)
}

private fun buildSyncAndBaselines(prefs: UserPreferences): UserPreferencesBackup =
    UserPreferencesBackup(
        goalSleepHours = prefs.goalSleepHours,
        hrvBaselineOverride = prefs.hrvBaselineOverride,
        rhrBaselineOverride = prefs.rhrBaselineOverride,
        syncPreference = prefs.syncPreference.name,
        syncIntervalHours = prefs.syncIntervalHours,
        backgroundSyncEnabled = prefs.backgroundSyncEnabled,
        backgroundSyncIntervalMinutes = prefs.backgroundSyncIntervalMinutes,
        lastSyncTimestamp = prefs.lastSyncTimestamp,
        maxHeartRate = prefs.maxHeartRate,
        autoCalculateMaxHr = prefs.autoCalculateMaxHr,
        manualZoneEditing = prefs.manualZoneEditing,
    )

private fun buildZonesAndDemographics(
    base: UserPreferencesBackup,
    prefs: UserPreferences,
): UserPreferencesBackup =
    base.copy(
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
        age = prefs.age,
        birthDate = prefs.birthDate,
        birthDay = parseBirthDay(prefs.birthDate),
        birthMonth = parseBirthMonth(prefs.birthDate),
        birthYear = parseBirthYear(prefs.birthDate),
        gender = prefs.gender?.name,
        heightCm = prefs.heightCm,
    )

private fun buildThresholdsAndDisplay(
    base: UserPreferencesBackup,
    prefs: UserPreferences,
): UserPreferencesBackup =
    base.copy(
        hrvOptimalThreshold = prefs.hrvOptimalThreshold,
        hrvWarningThreshold = prefs.hrvWarningThreshold,
        rhrOptimalThreshold = prefs.rhrOptimalThreshold,
        rhrWarningThreshold = prefs.rhrWarningThreshold,
        hrrToleranceSeconds = prefs.hrrToleranceSeconds,
        appTheme = prefs.appTheme.name,
        backupSchedule = prefs.backupSchedule.name,
        lastBackupTimestamp = prefs.lastBackupTimestamp,
        consistencyThresholdMinutes = prefs.consistencyThresholdMinutes,
        consistencyEvaluationDays = prefs.consistencyEvaluationDays,
        consistencyBaselineDays = prefs.consistencyBaselineDays,
        rasScalingFactor = prefs.rasScalingFactor,
        stepGoal = prefs.stepGoal,
        retentionDaysEnabled = prefs.retentionDaysEnabled,
        retentionDays = prefs.retentionDays,
        collapseHealthConnect = prefs.collapseHealthConnect,
        collapseBaselinesThresholds = prefs.collapseBaselinesThresholds,
        collapseDisplay = prefs.collapseDisplay,
        collapseAdvanced = prefs.collapseAdvanced,
        aboutDismissed = prefs.aboutDismissed,
        physiologyProfile = prefs.physiologyProfile.name,
        installDate = prefs.installDate,
        circadianThresholdOverride = prefs.circadianThresholdOverride,
        dynamicColorEnabled = prefs.dynamicColorEnabled,
    )

private fun buildScoringAndDevices(
    base: UserPreferencesBackup,
    prefs: UserPreferences,
): UserPreferencesBackup =
    base.copy(
        trimpModel = prefs.trimpModel.name,
        banisterMultiplier = prefs.banisterMultiplier,
        chengBeta = prefs.chengBeta,
        itrimB = prefs.itrimB,
        primaryDeviceName = prefs.primaryDeviceName,
        deviceByDataType = prefs.deviceByDataType.takeIf { it.isNotEmpty() },
        backupDirectoryUri = prefs.backupDirectoryUri,
        sleepScoreWeightProfile = prefs.sleepScoreWeightProfile.name,
        hypersomniaOnsetPercent = prefs.hypersomniaOnsetPercent,
        scoringVersion = prefs.scoringVersion,
        lastRecalcSleepScoreWeightProfile = prefs.lastRecalcSleepScoreWeightProfile?.name,
        lastRecalcGoalSleepHours = prefs.lastRecalcGoalSleepHours,
        lastRecalcHypersomniaOnsetPercent = prefs.lastRecalcHypersomniaOnsetPercent,
    )

private fun attachLayouts(
    base: UserPreferencesBackup,
    layouts: BackupLayoutSnapshots,
): UserPreferencesBackup =
    base.copy(
        dashboardCards = layouts.dashboardCards,
        vitalsCards = layouts.vitalsCards,
        vitalsCharts = layouts.vitalsCharts,
        sleepTopCards = layouts.sleepTopCards,
        sleepCharts = layouts.sleepCharts,
        sleepMetricCards = layouts.sleepMetricCards,
        workoutCards = layouts.workoutCards,
        workoutCharts = layouts.workoutCharts,
        workoutHistory = layouts.workoutHistory,
        workoutDetailLayouts = layouts.workoutDetailLayouts,
    )

internal data class BackupLayoutSnapshots(
    val dashboardCards: List<CardConfiguration>?,
    val vitalsCards: List<CardConfiguration>?,
    val vitalsCharts: List<VitalsChartConfiguration>?,
    val sleepTopCards: List<SleepTopCardConfiguration>?,
    val sleepCharts: List<SleepChartConfiguration>?,
    val sleepMetricCards: List<SleepMetricCardConfiguration>?,
    val workoutCards: List<CardConfiguration>?,
    val workoutCharts: List<WorkoutChartConfiguration>?,
    val workoutHistory: List<WorkoutHistoryConfiguration>?,
    val workoutDetailLayouts: Map<String, List<WorkoutDetailItemConfiguration>>?,
)

private fun parseBirthDay(birthDate: String?): Int? =
    birthDate?.let {
        try {
            LocalDate.parse(it).dayOfMonth
        } catch (e: DateTimeParseException) {
            null
        }
    }

private fun parseBirthMonth(birthDate: String?): Int? =
    birthDate?.let {
        try {
            LocalDate.parse(it).monthValue
        } catch (e: DateTimeParseException) {
            null
        }
    }

private fun parseBirthYear(birthDate: String?): Int? =
    birthDate?.let {
        try {
            LocalDate.parse(it).year
        } catch (e: DateTimeParseException) {
            null
        }
    }
