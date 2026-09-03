package app.readylytics.health.data.preferences

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.readylytics.health.core.model.data.preferences.FallbackThemeColor
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.data.preferences.UserPreferences
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream
import java.time.DayOfWeek

object UserPreferencesSerializer : Serializer<UserPreferencesProto> {
    override val defaultValue: UserPreferencesProto =
        UserPreferencesProto
            .newBuilder()
            .setGoalSleepHours(SettingsDefaults.GOAL_SLEEP_HOURS)
            .setCoreMergeGapMinutes(SettingsDefaults.CORE_MERGE_GAP_MINUTES)
            .setSupplementalCutoffMinutesOfDay(SettingsDefaults.SUPPLEMENTAL_CUTOFF_MINUTES_OF_DAY)
            .setMinimumCountedSleepSegmentMinutes(SettingsDefaults.MINIMUM_COUNTED_SLEEP_SEGMENT_MINUTES)
            .setSupplementalArchitectureCoveragePercent(
                SettingsDefaults.SUPPLEMENTAL_ARCHITECTURE_COVERAGE_PERCENT,
            ).setSyncPreference(SyncPreferenceProto.valueOf("SYNC_${SettingsDefaults.SYNC_PREFERENCE.name}"))
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
            .setFallbackThemeColor(SettingsDefaults.FALLBACK_THEME_COLOR.toProto())
            .setBackupSchedule(BackupScheduleProto.valueOf("BACKUP_${SettingsDefaults.BACKUP_SCHEDULE.name}"))
            .setLastBackupTimestamp(SettingsDefaults.LAST_BACKUP_TIMESTAMP)
            .setConsistencyThresholdMinutes(SettingsDefaults.CONSISTENCY_THRESHOLD_MINUTES)
            .setConsistencyEvaluationDays(SettingsDefaults.CONSISTENCY_EVALUATION_DAYS)
            .setConsistencyBaselineDays(SettingsDefaults.CONSISTENCY_BASELINE_DAYS)
            .setHrrToleranceSeconds(SettingsDefaults.HRR_TOLERANCE_SECONDS)
            .setRasScalingFactor(SettingsDefaults.RAS_SCALING_FACTOR)
            .setStepGoal(SettingsDefaults.STEP_GOAL)
            .setRetentionDaysEnabled(SettingsDefaults.RETENTION_DAYS_ENABLED)
            .setRetentionDays(SettingsDefaults.RETENTION_DAYS)
            .setAboutDismissed(SettingsDefaults.ABOUT_DISMISSED)
            .setPhysiologyProfile(PhysiologyProfileProto.valueOf("PROFILE_${SettingsDefaults.PHYSIOLOGY_PROFILE.name}"))
            .setInstallDate(SettingsDefaults.INSTALL_DATE)
            .setBackgroundSyncEnabled(SettingsDefaults.BACKGROUND_SYNC_ENABLED)
            .setBackgroundSyncIntervalMinutes(SettingsDefaults.BACKGROUND_SYNC_INTERVAL.minutes)
            .setIsCustomPaletteEnabled(SettingsDefaults.IS_CUSTOM_PALETTE_ENABLED)
            .setCustomSecondaryColor(SettingsDefaults.CUSTOM_SECONDARY_COLOR)
            .setCustomTertiaryColor(SettingsDefaults.CUSTOM_TERTIARY_COLOR)
            .setCustomPrimaryColor(SettingsDefaults.CUSTOM_PRIMARY_COLOR)
            .setBodyTempElevatedThresholdCelsius(SettingsDefaults.BODY_TEMP_ELEVATED_THRESHOLD_CELSIUS)
            .setSleepScoreWeightProfile(SleepScoreWeightProfileProto.SLEEP_WEIGHT_PROFILE_BALANCED)
            .setHypersomniaOnsetPercent(SettingsDefaults.HYPERSOMNIA_ONSET_PERCENT)
            .setScoringVersion(0)
            .setWeekStartDay(SettingsDefaults.WEEK_START_DAY.toProto())
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

fun FallbackThemeColor.toProto(): FallbackThemeColorProto =
    when (this) {
        FallbackThemeColor.GREEN_PERFORMANCE -> FallbackThemeColorProto.FALLBACK_GREEN_PERFORMANCE
        FallbackThemeColor.BLUE_TRUST -> FallbackThemeColorProto.FALLBACK_BLUE_TRUST
        FallbackThemeColor.PURPLE_INSIGHT -> FallbackThemeColorProto.FALLBACK_PURPLE_INSIGHT
        FallbackThemeColor.ICON_SIGNATURE -> FallbackThemeColorProto.FALLBACK_ICON_SIGNATURE
        FallbackThemeColor.ICON_ELEMENTS -> FallbackThemeColorProto.FALLBACK_ICON_ELEMENTS
    }

fun DayOfWeek.toProto(): DayOfWeekProto =
    when (this) {
        DayOfWeek.MONDAY -> DayOfWeekProto.DAY_OF_WEEK_MONDAY
        DayOfWeek.TUESDAY -> DayOfWeekProto.DAY_OF_WEEK_TUESDAY
        DayOfWeek.WEDNESDAY -> DayOfWeekProto.DAY_OF_WEEK_WEDNESDAY
        DayOfWeek.THURSDAY -> DayOfWeekProto.DAY_OF_WEEK_THURSDAY
        DayOfWeek.FRIDAY -> DayOfWeekProto.DAY_OF_WEEK_FRIDAY
        DayOfWeek.SATURDAY -> DayOfWeekProto.DAY_OF_WEEK_SATURDAY
        DayOfWeek.SUNDAY -> DayOfWeekProto.DAY_OF_WEEK_SUNDAY
    }

fun UserPreferences.toProto(): UserPreferencesProto =
    UserPreferencesProto
        .newBuilder()
        .applySyncAndBaselineFields(this)
        .applyZoneAndDemographicFields(this)
        .applyThresholdAndDisplayFields(this)
        .applyPaletteAndUiFields(this)
        .applyScoringAndRecalcFields(this)
        .build()
