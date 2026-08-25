package app.readylytics.health.core.model.data.preferences

import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.model.domain.scoring.TrimpModel
import app.readylytics.health.core.model.domain.sleep.SleepChartConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepChartId
import app.readylytics.health.core.model.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepMetricCardId
import app.readylytics.health.core.model.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepTopCardId
import app.readylytics.health.core.model.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.core.model.domain.vitals.VitalsChartId
import app.readylytics.health.core.model.domain.workouts.WorkoutChartConfiguration
import app.readylytics.health.core.model.domain.workouts.WorkoutChartId
import app.readylytics.health.core.model.domain.workouts.WorkoutHistoryConfiguration
import app.readylytics.health.core.model.domain.workouts.WorkoutHistoryId
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutDetailItemConfiguration
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutDetailItemId
import java.time.DayOfWeek

object SettingsDefaults {
    const val GOAL_SLEEP_HOURS = 8f
    const val SLEEP_SCORE_WEIGHT_PROFILE_NAME = "BALANCED"
    const val HYPERSOMNIA_ONSET_PERCENT = 125
    const val MIN_HYPERSOMNIA_ONSET_PERCENT = 100
    const val MAX_HYPERSOMNIA_ONSET_PERCENT = 125
    const val HYPERSOMNIA_ONSET_PERCENT_STEP = 5
    const val CURRENT_SCORING_VERSION = 1
    const val CORE_MERGE_GAP_MINUTES = 180
    const val MIN_CORE_MERGE_GAP_MINUTES = 30
    const val MAX_CORE_MERGE_GAP_MINUTES = 240
    const val CORE_MERGE_GAP_STEP_MINUTES = 30
    const val SUPPLEMENTAL_CUTOFF_MINUTES_OF_DAY = 1200
    const val MIN_SUPPLEMENTAL_CUTOFF_MINUTES_OF_DAY = 840
    const val MAX_SUPPLEMENTAL_CUTOFF_MINUTES_OF_DAY = 1380
    const val SUPPLEMENTAL_CUTOFF_STEP_MINUTES = 30
    const val MINIMUM_COUNTED_SLEEP_SEGMENT_MINUTES = 15
    const val MIN_MINIMUM_COUNTED_SLEEP_SEGMENT_MINUTES = 5
    const val MAX_MINIMUM_COUNTED_SLEEP_SEGMENT_MINUTES = 60
    const val MINIMUM_COUNTED_SLEEP_SEGMENT_STEP_MINUTES = 5
    const val SUPPLEMENTAL_ARCHITECTURE_COVERAGE_PERCENT = 75
    const val MIN_SUPPLEMENTAL_ARCHITECTURE_COVERAGE_PERCENT = 25
    const val MAX_SUPPLEMENTAL_ARCHITECTURE_COVERAGE_PERCENT = 100
    const val SUPPLEMENTAL_ARCHITECTURE_COVERAGE_STEP_PERCENT = 5
    val HRV_BASELINE_OVERRIDE: Float? = null
    val RHR_BASELINE_OVERRIDE: Float? = null
    val SYNC_PREFERENCE = SyncPreference.BY_TIME
    const val SYNC_INTERVAL_HOURS = 1
    const val LAST_SYNC_TIMESTAMP = 0L
    const val MAX_HEART_RATE = 190
    const val AUTO_CALCULATE_MAX_HR = true
    const val MANUAL_ZONE_EDITING = false
    const val ZONE_1_MIN_PERCENT = 0.50f
    const val ZONE_1_MAX_PERCENT = 0.60f
    const val ZONE_2_MAX_PERCENT = 0.70f
    const val ZONE_3_MAX_PERCENT = 0.80f
    const val ZONE_4_MAX_PERCENT = 0.90f
    const val ZONE_1_MIN_BPM = 95
    const val ZONE_1_MAX_BPM = 114
    const val ZONE_2_MAX_BPM = 133
    const val ZONE_3_MAX_BPM = 152
    const val ZONE_4_MAX_BPM = 171
    const val AGE = 30
    const val BIRTH_DAY = 1
    const val BIRTH_MONTH = 1
    const val BIRTH_YEAR = 1994
    const val IS_BIRTHDAY_CONFIGURED = false
    val GENDER: String? = null
    const val HEIGHT_CM: Float = 175f
    const val HRV_OPTIMAL_THRESHOLD = 1.10f
    const val HRV_WARNING_THRESHOLD = 0.90f
    const val RHR_OPTIMAL_THRESHOLD = 0.90f
    const val RHR_WARNING_THRESHOLD = 1.1f
    const val RESTING_HR_PERCENTILE = 5
    val APP_THEME = AppTheme.SYSTEM
    const val DYNAMIC_COLOR_ENABLED = true
    val FALLBACK_THEME_COLOR = FallbackThemeColor.GREEN_PERFORMANCE
    val BACKUP_SCHEDULE = BackupSchedule.MANUAL
    const val LAST_BACKUP_TIMESTAMP = 0L
    const val CONSISTENCY_THRESHOLD_MINUTES = 30
    const val CONSISTENCY_EVALUATION_DAYS = 7
    const val CONSISTENCY_BASELINE_DAYS = 14
    const val MIN_HRR_TOLERANCE_SECONDS = 15
    const val MAX_HRR_TOLERANCE_SECONDS = 60
    const val HRR_TOLERANCE_SECONDS = 30
    const val BODY_TEMP_ELEVATED_THRESHOLD_CELSIUS = 1.0f
    const val MIN_BODY_TEMP_ELEVATED_THRESHOLD_CELSIUS = 0.25f
    const val MAX_BODY_TEMP_ELEVATED_THRESHOLD_CELSIUS = 1.5f
    const val BODY_TEMP_ELEVATED_THRESHOLD_STEP_CELSIUS = 0.25f

    /**
     * Authoritative runtime value for RAS scaling.
     * Initialized from PhysiologyProfile default, but user overrides are persistent.
     */
    const val RAS_SCALING_FACTOR = 0.20f
    const val STEP_GOAL = 10000
    const val RETENTION_DAYS_ENABLED = true
    const val RETENTION_DAYS = 360 // 12 months (30 days per month)
    const val COLLAPSE_HEALTH_CONNECT = true
    const val COLLAPSE_BASELINES_THRESHOLDS = true
    const val COLLAPSE_DISPLAY = true
    const val COLLAPSE_ADVANCED = true
    const val ABOUT_DISMISSED = false
    val PHYSIOLOGY_PROFILE = PhysiologyProfile.ACTIVE
    const val INSTALL_DATE = 0L // Set to System.currentTimeMillis() on first app run

    // Empty = un-seeded; UserPreferences.scoringZone() falls back to the device zone until
    // the seed migration captures the IANA zone id. Stored to make scores timezone-deterministic.
    const val SCORING_ZONE_ID = ""
    val CIRCADIAN_THRESHOLD_OVERRIDE: String? = null // null = use profile default
    val TRIMP_MODEL = TrimpModel.BANISTER
    val STRAIN_LOAD_SOURCE_MODE = LoadSourceMode.WORKOUT_ONLY
    val RAS_SOURCE_MODE = LoadSourceMode.EVERYDAY_HEART_RATE
    val UNIT_SYSTEM = UnitSystem.METRIC
    val WEEK_START_DAY = DayOfWeek.MONDAY
    const val BACKGROUND_SYNC_ENABLED = false
    val BACKGROUND_SYNC_INTERVAL = BackgroundSyncInterval.HOUR_1
    const val DEVICE_CHANGE_NOTICE_DISMISSED = false
    const val BULK_DISPLAY_MODE_NOTICE_DISMISSED = false
    val LAST_GLOBAL_DISPLAY_MODE: DashboardCardDisplayMode? = null

    const val IS_CUSTOM_PALETTE_ENABLED = false
    const val CUSTOM_SECONDARY_COLOR = 0xFFCCC2DCL
    const val CUSTOM_TERTIARY_COLOR = 0xFFEFB8C8L
    const val CUSTOM_PRIMARY_COLOR = 0xFF2ECC71L

    val DEFAULT_DASHBOARD_CARDS =
        listOf(
            CardConfiguration(CardId.SLEEP_SCORE, isVisible = true, position = 0),
            CardConfiguration(CardId.READINESS, isVisible = true, position = 1),
            CardConfiguration(CardId.STEPS, isVisible = true, position = 2),
            CardConfiguration(CardId.HRV, isVisible = true, position = 3),
            CardConfiguration(CardId.SLEEP_DURATION, isVisible = true, position = 5),
            CardConfiguration(CardId.RAS_DAILY, isVisible = true, position = 6),
            CardConfiguration(CardId.RESTING_HR, isVisible = true, position = 7),
            CardConfiguration(CardId.CIRCADIAN_CONSISTENCY, isVisible = true, position = 8),
            CardConfiguration(CardId.STRAIN_RATIO, isVisible = true, position = 9),
            CardConfiguration(CardId.SLEEP_EFFICIENCY, isVisible = true, position = 10),
            CardConfiguration(CardId.HEART_RATE, isVisible = true, position = 11),
            CardConfiguration(CardId.WEIGHT, isVisible = true, position = 12),
            CardConfiguration(CardId.BODY_FAT, isVisible = true, position = 13),
            CardConfiguration(CardId.BLOOD_PRESSURE, isVisible = true, position = 14),
            CardConfiguration(CardId.OXYGEN_SATURATION, isVisible = true, position = 15),
            CardConfiguration(CardId.INSIGHTS, isVisible = true, position = 16),
            CardConfiguration(CardId.BODY_TEMPERATURE, isVisible = true, position = 17),
            CardConfiguration(CardId.AI_RECOMMENDATION, isVisible = true, position = 18),
        )

    val DEFAULT_VITALS_CARDS =
        listOf(
            CardConfiguration(
                CardId.RESTING_HR,
                isVisible = true,
                position = 0,
                requestedDisplayMode = DashboardCardDisplayMode.GAUGE,
            ),
            CardConfiguration(
                CardId.HRV,
                isVisible = true,
                position = 1,
                requestedDisplayMode = DashboardCardDisplayMode.GAUGE,
            ),
            CardConfiguration(
                CardId.OXYGEN_SATURATION,
                isVisible = false,
                position = 2,
                requestedDisplayMode = DashboardCardDisplayMode.GAUGE,
            ),
            CardConfiguration(
                CardId.BODY_TEMPERATURE,
                isVisible = false,
                position = 3,
                requestedDisplayMode = DashboardCardDisplayMode.GAUGE,
            ),
        )

    val DEFAULT_VITALS_CHARTS =
        listOf(
            VitalsChartConfiguration(VitalsChartId.HRV_TREND, isVisible = true, position = 0),
            VitalsChartConfiguration(VitalsChartId.RHR_TREND, isVisible = true, position = 1),
            VitalsChartConfiguration(VitalsChartId.SPO2_TREND, isVisible = true, position = 2),
            VitalsChartConfiguration(VitalsChartId.BODY_TEMP_TREND, isVisible = true, position = 3),
        )

    val DEFAULT_SLEEP_TOP_CARDS =
        listOf(
            SleepTopCardConfiguration(SleepTopCardId.SLEEP_SCORE, isVisible = true, position = 0),
            SleepTopCardConfiguration(SleepTopCardId.SLEEP_DURATION_GAUGE, isVisible = true, position = 1),
            SleepTopCardConfiguration(SleepTopCardId.SLEEP_BREAKDOWN_BAR, isVisible = true, position = 2),
            SleepTopCardConfiguration(SleepTopCardId.SLEEP_STAGES_TIMELINE, isVisible = true, position = 3),
            SleepTopCardConfiguration(SleepTopCardId.SLEEP_HR_CHART, isVisible = true, position = 4),
        )

    val DEFAULT_SLEEP_CHARTS =
        listOf(
            SleepChartConfiguration(SleepChartId.SLEEP_DURATION_TREND, isVisible = true, position = 0),
        )

    val DEFAULT_SLEEP_METRIC_CARDS =
        listOf(
            SleepMetricCardConfiguration(SleepMetricCardId.CIRCADIAN_CONSISTENCY, isVisible = true, position = 0),
            SleepMetricCardConfiguration(SleepMetricCardId.SLEEP_EFFICIENCY, isVisible = true, position = 1),
            SleepMetricCardConfiguration(SleepMetricCardId.DEEP_SLEEP, isVisible = true, position = 2),
            SleepMetricCardConfiguration(SleepMetricCardId.REM_SLEEP, isVisible = true, position = 3),
            SleepMetricCardConfiguration(SleepMetricCardId.NAP_DURATION, isVisible = true, position = 4),
            SleepMetricCardConfiguration(SleepMetricCardId.NAP_COUNT, isVisible = true, position = 5),
        )

    val DEFAULT_WORKOUT_CARDS =
        listOf(
            CardConfiguration(
                CardId.STRAIN_RATIO,
                isVisible = true,
                position = 0,
                requestedDisplayMode = DashboardCardDisplayMode.GAUGE,
            ),
            CardConfiguration(
                CardId.READINESS,
                isVisible = true,
                position = 1,
                requestedDisplayMode = DashboardCardDisplayMode.GAUGE,
            ),
            CardConfiguration(
                CardId.RAS_DAILY,
                isVisible = true,
                position = 2,
                requestedDisplayMode = DashboardCardDisplayMode.VALUE,
            ),
        )

    val DEFAULT_WORKOUT_CHARTS =
        listOf(
            WorkoutChartConfiguration(WorkoutChartId.ACWR_TRIMP, isVisible = true, position = 0),
            WorkoutChartConfiguration(WorkoutChartId.WEEKLY_VOLUME_TREND, isVisible = true, position = 1),
            WorkoutChartConfiguration(WorkoutChartId.WEEKLY_TRAINING, isVisible = true, position = 2),
        )

    val DEFAULT_WORKOUT_HISTORY =
        listOf(
            WorkoutHistoryConfiguration(WorkoutHistoryId.WORKOUT_LIST, isVisible = true, position = 0),
        )

    /**
     * Shared default layout for the workout detail screen, used by every
     * [app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType] that has no stored
     * customization. Order reproduces the pre-customization visual order of the screen.
     */
    val DEFAULT_WORKOUT_DETAIL_ITEMS =
        listOf(
            WorkoutDetailItemId.TRAINING_LOAD,
            WorkoutDetailItemId.AVG_PULSE,
            WorkoutDetailItemId.GAINED_STRAIN,
            WorkoutDetailItemId.RAS,
            WorkoutDetailItemId.OVERALL_LOAD,
            WorkoutDetailItemId.INTENSITY,
            WorkoutDetailItemId.DISTANCE,
            WorkoutDetailItemId.AVG_PACE_SPEED,
            WorkoutDetailItemId.ELEVATION_GAIN,
            WorkoutDetailItemId.ZONE_BREAKDOWN,
            WorkoutDetailItemId.ROUTE_CONTOUR,
            WorkoutDetailItemId.PACE_SPEED_CHART,
            WorkoutDetailItemId.ELEVATION_CHART,
            WorkoutDetailItemId.TRIMP_BREAKDOWN,
            WorkoutDetailItemId.RECOVERY_HRR,
        ).mapIndexed { index, id ->
            WorkoutDetailItemConfiguration(itemId = id, isVisible = true, position = index)
        }
}

fun normalizeCoreMergeGapMinutes(value: Int): Int =
    normalizeSteppedPreference(
        value = value,
        defaultValue = SettingsDefaults.CORE_MERGE_GAP_MINUTES,
        minValue = SettingsDefaults.MIN_CORE_MERGE_GAP_MINUTES,
        maxValue = SettingsDefaults.MAX_CORE_MERGE_GAP_MINUTES,
        step = SettingsDefaults.CORE_MERGE_GAP_STEP_MINUTES,
    )

fun normalizeSupplementalCutoffMinutesOfDay(value: Int): Int =
    normalizeSteppedPreference(
        value = value,
        defaultValue = SettingsDefaults.SUPPLEMENTAL_CUTOFF_MINUTES_OF_DAY,
        minValue = SettingsDefaults.MIN_SUPPLEMENTAL_CUTOFF_MINUTES_OF_DAY,
        maxValue = SettingsDefaults.MAX_SUPPLEMENTAL_CUTOFF_MINUTES_OF_DAY,
        step = SettingsDefaults.SUPPLEMENTAL_CUTOFF_STEP_MINUTES,
    )

fun normalizeMinimumCountedSleepSegmentMinutes(value: Int): Int =
    normalizeSteppedPreference(
        value = value,
        defaultValue = SettingsDefaults.MINIMUM_COUNTED_SLEEP_SEGMENT_MINUTES,
        minValue = SettingsDefaults.MIN_MINIMUM_COUNTED_SLEEP_SEGMENT_MINUTES,
        maxValue = SettingsDefaults.MAX_MINIMUM_COUNTED_SLEEP_SEGMENT_MINUTES,
        step = SettingsDefaults.MINIMUM_COUNTED_SLEEP_SEGMENT_STEP_MINUTES,
    )

fun normalizeSupplementalArchitectureCoveragePercent(value: Int): Int =
    normalizeSteppedPreference(
        value = value,
        defaultValue = SettingsDefaults.SUPPLEMENTAL_ARCHITECTURE_COVERAGE_PERCENT,
        minValue = SettingsDefaults.MIN_SUPPLEMENTAL_ARCHITECTURE_COVERAGE_PERCENT,
        maxValue = SettingsDefaults.MAX_SUPPLEMENTAL_ARCHITECTURE_COVERAGE_PERCENT,
        step = SettingsDefaults.SUPPLEMENTAL_ARCHITECTURE_COVERAGE_STEP_PERCENT,
    )

fun normalizeHypersomniaOnsetPercent(value: Int): Int =
    normalizeSteppedPreference(
        value = value,
        defaultValue = SettingsDefaults.HYPERSOMNIA_ONSET_PERCENT,
        minValue = SettingsDefaults.MIN_HYPERSOMNIA_ONSET_PERCENT,
        maxValue = SettingsDefaults.MAX_HYPERSOMNIA_ONSET_PERCENT,
        step = SettingsDefaults.HYPERSOMNIA_ONSET_PERCENT_STEP,
    )

private fun normalizeSteppedPreference(
    value: Int,
    defaultValue: Int,
    minValue: Int,
    maxValue: Int,
    step: Int,
): Int {
    if (value == 0) return defaultValue
    val clamped = value.coerceIn(minValue, maxValue)
    val stepsFromMin = ((clamped - minValue) / step.toFloat()).toInt()
    val lower = minValue + (stepsFromMin * step)
    val upper = (lower + step).coerceAtMost(maxValue)
    return if (upper == lower) {
        lower
    } else if (clamped - lower < upper - clamped) {
        lower
    } else {
        upper
    }
}
