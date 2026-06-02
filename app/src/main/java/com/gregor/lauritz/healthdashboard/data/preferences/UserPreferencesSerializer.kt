package com.gregor.lauritz.healthdashboard.data.preferences

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate

object UserPreferencesSerializer : Serializer<UserPreferencesProto> {
    override val defaultValue: UserPreferencesProto =
        UserPreferencesProto
            .newBuilder()
            .setGoalSleepHours(SettingsDefaults.GOAL_SLEEP_HOURS)
            .setSyncPreference(SyncPreferenceProto.valueOf("SYNC_${SettingsDefaults.SYNC_PREFERENCE.name}"))
            .setSyncIntervalHours(SettingsDefaults.SYNC_INTERVAL_HOURS)
            .setLastSyncTimestamp(SettingsDefaults.LAST_SYNC_TIMESTAMP)
            .setMaxHeartRate(SettingsDefaults.MAX_HEART_RATE)
            .setAutoCalculateMaxHr(SettingsDefaults.AUTO_CALCULATE_MAX_HR)
            .setManualZoneEditing(SettingsDefaults.MANUAL_ZONE_EDITING)
            .setZone1MinPercent(SettingsDefaults.ZONE_1_MIN_PERCENT)
            .setZone1MaxPercent(SettingsDefaults.ZONE_1_MAX_PERCENT)
            .setZone2MaxPercent(SettingsDefaults.ZONE_2_MAX_PERCENT)
            .setZone3MaxPercent(SettingsDefaults.ZONE_3_MAX_PERCENT)
            .setZone4MaxPercent(SettingsDefaults.ZONE_4_MAX_PERCENT)
            .setZone1MinBpm(SettingsDefaults.ZONE_1_MIN_BPM)
            .setZone1MaxBpm(SettingsDefaults.ZONE_1_MAX_BPM)
            .setZone2MaxBpm(SettingsDefaults.ZONE_2_MAX_BPM)
            .setZone3MaxBpm(SettingsDefaults.ZONE_3_MAX_BPM)
            .setZone4MaxBpm(SettingsDefaults.ZONE_4_MAX_BPM)
            .setAge(SettingsDefaults.AGE)
            .setBirthDay(SettingsDefaults.BIRTH_DAY)
            .setBirthMonth(SettingsDefaults.BIRTH_MONTH)
            .setBirthYear(SettingsDefaults.BIRTH_YEAR)
            .setHrvOptimalThreshold(SettingsDefaults.HRV_OPTIMAL_THRESHOLD)
            .setHrvWarningThreshold(SettingsDefaults.HRV_WARNING_THRESHOLD)
            .setRhrOptimalThreshold(SettingsDefaults.RHR_OPTIMAL_THRESHOLD)
            .setRhrWarningThreshold(SettingsDefaults.RHR_WARNING_THRESHOLD)
            .setAppTheme(AppThemeProto.valueOf("THEME_${SettingsDefaults.APP_THEME.name}"))
            .setDynamicColorEnabled(SettingsDefaults.DYNAMIC_COLOR_ENABLED)
            .setBackupSchedule(BackupScheduleProto.valueOf("BACKUP_${SettingsDefaults.BACKUP_SCHEDULE.name}"))
            .setLastBackupTimestamp(SettingsDefaults.LAST_BACKUP_TIMESTAMP)
            .setConsistencyThresholdMinutes(SettingsDefaults.CONSISTENCY_THRESHOLD_MINUTES)
            .setConsistencyEvaluationDays(SettingsDefaults.CONSISTENCY_EVALUATION_DAYS)
            .setConsistencyBaselineDays(SettingsDefaults.CONSISTENCY_BASELINE_DAYS)
            .setPaiScalingFactor(SettingsDefaults.PAI_SCALING_FACTOR)
            .setStepGoal(SettingsDefaults.STEP_GOAL)
            .setRetentionDaysEnabled(SettingsDefaults.RETENTION_DAYS_ENABLED)
            .setRetentionDays(SettingsDefaults.RETENTION_DAYS)
            .setCollapseCloudData(SettingsDefaults.COLLAPSE_CLOUD_DATA)
            .setCollapseHealthConnect(SettingsDefaults.COLLAPSE_HEALTH_CONNECT)
            .setCollapseBaselinesThresholds(SettingsDefaults.COLLAPSE_BASELINES_THRESHOLDS)
            .setCollapseDisplay(SettingsDefaults.COLLAPSE_DISPLAY)
            .setCollapseAdvanced(SettingsDefaults.COLLAPSE_ADVANCED)
            .setAboutDismissed(SettingsDefaults.ABOUT_DISMISSED)
            .setPhysiologyProfile(PhysiologyProfileProto.valueOf("PROFILE_${SettingsDefaults.PHYSIOLOGY_PROFILE.name}"))
            .setInstallDate(SettingsDefaults.INSTALL_DATE)
            .build()

    override suspend fun readFrom(input: InputStream): UserPreferencesProto {
        try {
            return UserPreferencesProto.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(
        t: UserPreferencesProto,
        output: OutputStream,
    ) {
        t.writeTo(output)
    }
}

fun UserPreferences.toProto(): UserPreferencesProto {
    val domain = this
    val builder = UserPreferencesProto.newBuilder()
    builder
        .setGoalSleepHours(domain.goalSleepHours)
        .setSyncPreference(SyncPreferenceProto.valueOf("SYNC_${domain.syncPreference.name}"))
        .setSyncIntervalHours(domain.syncIntervalHours)
        .setLastSyncTimestamp(domain.lastSyncTimestamp)
        .setMaxHeartRate(domain.maxHeartRate)
        .setAutoCalculateMaxHr(domain.autoCalculateMaxHr)
        .setManualZoneEditing(domain.manualZoneEditing)
        .setZone1MinPercent(domain.zone1MinPercent)
        .setZone1MaxPercent(domain.zone1MaxPercent)
        .setZone2MaxPercent(domain.zone2MaxPercent)
        .setZone3MaxPercent(domain.zone3MaxPercent)
        .setZone4MaxPercent(domain.zone4MaxPercent)
        .setZone1MinBpm(domain.zone1MinBpm)
        .setZone1MaxBpm(domain.zone1MaxBpm)
        .setZone2MaxBpm(domain.zone2MaxBpm)
        .setZone3MaxBpm(domain.zone3MaxBpm)
        .setZone4MaxBpm(domain.zone4MaxBpm)
        .setAge(domain.age)
        // Extract birth day, month, year from the ISO-8601 birthDate string for proto storage
        .apply {
            if (domain.birthDate != null) {
                try {
                    val date = LocalDate.parse(domain.birthDate)
                    setBirthDay(date.dayOfMonth)
                    setBirthMonth(date.monthValue)
                    setBirthYear(date.year)
                } catch (e: Exception) {
                    // If parsing fails, keep default values
                }
            }
        }
        .setHrvOptimalThreshold(domain.hrvOptimalThreshold)
        .setHrvWarningThreshold(domain.hrvWarningThreshold)
        .setRhrOptimalThreshold(domain.rhrOptimalThreshold)
        .setRhrWarningThreshold(domain.rhrWarningThreshold)
        .setAppTheme(AppThemeProto.valueOf("THEME_${domain.appTheme.name}"))
        .setDynamicColorEnabled(domain.dynamicColorEnabled)
        .setBackupSchedule(BackupScheduleProto.valueOf("BACKUP_${domain.backupSchedule.name}"))
        .setLastBackupTimestamp(domain.lastBackupTimestamp)
        .setConsistencyThresholdMinutes(domain.consistencyThresholdMinutes)
        .setConsistencyEvaluationDays(domain.consistencyEvaluationDays)
        .setConsistencyBaselineDays(domain.consistencyBaselineDays)
        .setPaiScalingFactor(domain.paiScalingFactor)
        .setStepGoal(domain.stepGoal)
        .setRetentionDaysEnabled(domain.retentionDaysEnabled)
        .setRetentionDays(domain.retentionDays)
        .setCollapseCloudData(domain.collapseCloudData)
        .setCollapseHealthConnect(domain.collapseHealthConnect)
        .setCollapseBaselinesThresholds(domain.collapseBaselinesThresholds)
        .setCollapseDisplay(domain.collapseDisplay)
        .setCollapseAdvanced(domain.collapseAdvanced)
        .setAboutDismissed(domain.aboutDismissed)
        .setPhysiologyProfile(PhysiologyProfileProto.valueOf("PROFILE_${domain.physiologyProfile.name}"))
        .setInstallDate(domain.installDate)
        .setTrimpMethod(
            when (domain.trimpModel) {
                com.gregor.lauritz.healthdashboard.domain.scoring.TrimpModel.BANISTER -> TrimpMethodProto.TRIMP_BANISTER
                com.gregor.lauritz.healthdashboard.domain.scoring.TrimpModel.I_TRIMP -> TrimpMethodProto.TRIMP_ITRIMP
                com.gregor.lauritz.healthdashboard.domain.scoring.TrimpModel.CHENG -> TrimpMethodProto.TRIMP_CHENG
            },
        ).setPaiCalibration(domain.banisterMultiplier)
        .setChengBeta(domain.chengBeta)
        .setItrimpB(domain.itrimB)

    domain.hrvBaselineOverride?.let { builder.setHrvBaselineOverride(it) }
    domain.rhrBaselineOverride?.let { builder.setRhrBaselineOverride(it) }
    domain.gender?.let { builder.setGender(it.name) }
    domain.heightCm?.let { builder.setHeightCm(it) }
    domain.circadianThresholdOverride?.let { builder.setCircadianThresholdOverride(it) }
    domain.primaryDeviceName?.let { builder.setPrimaryDeviceName(it) }
    domain.backupDirectoryUri?.let { builder.setBackupDirectoryUri(it) }
    domain.backupPasswordHash?.let { builder.setBackupPasswordHash(it) }
    domain.driveAccountEmail?.let { builder.setDriveAccountEmail(it) }
    builder.setIsBirthdayConfigured(domain.isBirthdayConfigured)
    builder.setRestingHrPercentile(domain.restingHrPercentile)
    builder.setUnitSystem(
        when (domain.unitSystem) {
            UnitSystem.METRIC -> UnitSystemProto.UNIT_METRIC
            UnitSystem.IMPERIAL -> UnitSystemProto.UNIT_IMPERIAL
        }
    )

    return builder.build()
}
