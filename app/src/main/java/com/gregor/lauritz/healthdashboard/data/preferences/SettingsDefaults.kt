package com.gregor.lauritz.healthdashboard.data.preferences

import com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId

object SettingsDefaults {
    const val GOAL_SLEEP_HOURS = 8f
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
    val GENDER: String? = null
    const val HRV_OPTIMAL_THRESHOLD = 1.10f
    const val HRV_WARNING_THRESHOLD = 0.90f
    const val RHR_OPTIMAL_THRESHOLD = 0.90f
    const val RHR_WARNING_THRESHOLD = 1.1f
    const val RESTING_HR_BEFORE_MINUTES = 5
    const val RESTING_HR_AFTER_MINUTES = 15
    val APP_THEME = AppTheme.SYSTEM
    const val DYNAMIC_COLOR_ENABLED = true
    val DRIVE_ACCOUNT_EMAIL: String? = null
    val BACKUP_SCHEDULE = BackupSchedule.MANUAL
    const val LAST_BACKUP_TIMESTAMP = 0L
    const val CONSISTENCY_THRESHOLD_MINUTES = 30
    const val CONSISTENCY_EVALUATION_DAYS = 7
    const val CONSISTENCY_BASELINE_DAYS = 14
    const val PAI_SCALING_FACTOR = 0.2f
    const val STEP_GOAL = 10000
    const val RETENTION_DAYS_ENABLED = true
    const val RETENTION_DAYS = 365
    const val COLLAPSE_CLOUD_DATA = true
    const val COLLAPSE_HEALTH_CONNECT = true
    const val COLLAPSE_BASELINES_THRESHOLDS = true
    const val COLLAPSE_DISPLAY = true
    const val COLLAPSE_ADVANCED = true
    const val ABOUT_DISMISSED = false
    val PHYSIOLOGY_PROFILE = PhysiologyProfile.GENERAL
    const val INSTALL_DATE = 0L // Set to System.currentTimeMillis() on first app run
    val CIRCADIAN_THRESHOLD_OVERRIDE: String? = null // null = use profile default

    val DEFAULT_DASHBOARD_CARDS = listOf(
        CardConfiguration(CardId.SLEEP_SCORE, isVisible = true, position = 0),
        CardConfiguration(CardId.READINESS, isVisible = true, position = 1),
        CardConfiguration(CardId.STEPS, isVisible = true, position = 2),
        CardConfiguration(CardId.HRV, isVisible = true, position = 3),
        CardConfiguration(CardId.SLEEP_RHR, isVisible = true, position = 4),
        CardConfiguration(CardId.SLEEP_DURATION, isVisible = true, position = 5),
        CardConfiguration(CardId.LOAD_SCORE, isVisible = true, position = 6),
        CardConfiguration(CardId.STRAIN_RATIO, isVisible = false, position = 7),
        CardConfiguration(CardId.RESTING_HR, isVisible = false, position = 8),
        CardConfiguration(CardId.CIRCADIAN_CONSISTENCY, isVisible = false, position = 9),
    )

    val DEFAULT_SLEEP_CARDS = listOf(
        CardConfiguration(CardId.SLEEP_SCORE, isVisible = true, position = 0),
        CardConfiguration(CardId.SLEEP_DURATION, isVisible = true, position = 1),
        CardConfiguration(CardId.SLEEP_ARCHITECTURE, isVisible = true, position = 2),
        CardConfiguration(CardId.HRV, isVisible = true, position = 3),
        CardConfiguration(CardId.SLEEP_RHR, isVisible = true, position = 4),
        CardConfiguration(CardId.RECOVERY_INDEX, isVisible = false, position = 5),
        CardConfiguration(CardId.CIRCADIAN_CONSISTENCY, isVisible = false, position = 6),
    )

    val DEFAULT_WORKOUT_CARDS = listOf(
        CardConfiguration(CardId.READINESS, isVisible = true, position = 0),
        CardConfiguration(CardId.LOAD_SCORE, isVisible = true, position = 1),
        CardConfiguration(CardId.STRAIN_RATIO, isVisible = true, position = 2),
        CardConfiguration(CardId.PAI_DAILY, isVisible = true, position = 3),
        CardConfiguration(CardId.ACUTE_CHRONIC_RATIO, isVisible = false, position = 4),
    )
}
