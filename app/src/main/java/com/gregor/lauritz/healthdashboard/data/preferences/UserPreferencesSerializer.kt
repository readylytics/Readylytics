package com.gregor.lauritz.healthdashboard.data.preferences

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

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
            .setRestingHrBeforeMinutes(SettingsDefaults.RESTING_HR_BEFORE_MINUTES)
            .setRestingHrAfterMinutes(SettingsDefaults.RESTING_HR_AFTER_MINUTES)
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
