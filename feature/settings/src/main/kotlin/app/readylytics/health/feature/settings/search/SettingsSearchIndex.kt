package app.readylytics.health.feature.settings.search

import app.readylytics.health.feature.settings.R
import app.readylytics.health.feature.settings.nav.SettingsCategoryId
import app.readylytics.health.core.ui.R as CoreUiR

private val physiologyProfileSearchItems =
    listOf(
        SettingsSearchItem(
            SettingsItemIds.PHYSIOLOGY_PROFILE_PICKER,
            SettingsCategoryId.PHYSIOLOGY_PROFILE,
            R.string.physiology_profile_picker_label,
            listOf("active", "athlete", "profile"),
        ),
        SettingsSearchItem(
            SettingsItemIds.PHYSIOLOGY_HR_ZONES,
            SettingsCategoryId.PHYSIOLOGY_PROFILE,
            R.string.settings_sub_heart_rate_zones,
            listOf("max heart rate", "zone", "bpm"),
        ),
    )

private val sleepSearchItems =
    listOf(
        SettingsSearchItem(
            SettingsItemIds.SLEEP_GOAL,
            SettingsCategoryId.SLEEP,
            R.string.label_sleep_goal,
            listOf("hours", "bedtime"),
        ),
        SettingsSearchItem(
            SettingsItemIds.SLEEP_WEIGHT_PROFILE,
            SettingsCategoryId.SLEEP,
            R.string.settings_sleep_score_emphasis_label,
            listOf("balanced", "duration", "recovery", "architecture", "continuity", "weight profile"),
        ),
        SettingsSearchItem(
            SettingsItemIds.SLEEP_HYPERSOMNIA_ONSET,
            SettingsCategoryId.SLEEP,
            R.string.settings_sleep_hypersomnia_onset_label,
            listOf("oversleep"),
        ),
        SettingsSearchItem(
            SettingsItemIds.SLEEP_RECALCULATE_SCORES,
            SettingsCategoryId.SLEEP,
            R.string.settings_recalculate_scores_title,
            listOf("recompute", "sleep score"),
        ),
        SettingsSearchItem(
            SettingsItemIds.SLEEP_CORE_MERGE_GAP,
            SettingsCategoryId.SLEEP,
            R.string.settings_sleep_core_merge_gap_label,
            listOf("merge", "gap"),
        ),
        SettingsSearchItem(
            SettingsItemIds.SLEEP_SUPPLEMENTAL_CUTOFF,
            SettingsCategoryId.SLEEP,
            R.string.settings_sleep_supplemental_cutoff_label,
            listOf("nap", "cutoff"),
        ),
        SettingsSearchItem(
            SettingsItemIds.SLEEP_MINIMUM_SEGMENT,
            SettingsCategoryId.SLEEP,
            R.string.settings_sleep_minimum_segment_label,
            listOf("minimum", "segment"),
        ),
        SettingsSearchItem(
            SettingsItemIds.SLEEP_ARCHITECTURE_COVERAGE,
            SettingsCategoryId.SLEEP,
            R.string.settings_sleep_architecture_coverage_label,
            listOf("architecture", "coverage", "supplemental"),
        ),
        SettingsSearchItem(
            SettingsItemIds.SLEEP_CIRCADIAN_CONSISTENCY,
            SettingsCategoryId.SLEEP,
            CoreUiR.string.label_circadian_consistency,
            listOf("regularity", "circadian", "consistency"),
        ),
    )

private val trainingSearchItems =
    listOf(
        SettingsSearchItem(
            SettingsItemIds.TRAINING_STEP_GOAL,
            SettingsCategoryId.TRAINING,
            R.string.label_daily_step_goal,
            listOf("steps", "activity"),
        ),
        SettingsSearchItem(
            SettingsItemIds.TRAINING_LOAD_SOURCES,
            SettingsCategoryId.TRAINING,
            R.string.load_sources_section_title,
            listOf("strain", "ras source", "workout only", "everyday heart rate"),
        ),
        SettingsSearchItem(
            SettingsItemIds.TRAINING_RAS_SCALING,
            SettingsCategoryId.TRAINING,
            R.string.advanced_ras_scaling_label,
            listOf("resting autonomic stress", "scaling factor"),
        ),
        SettingsSearchItem(
            SettingsItemIds.TRAINING_ADVANCED_LOAD,
            SettingsCategoryId.TRAINING,
            R.string.advanced_training_load_label,
            listOf("trimp", "banister", "cheng", "itrimp"),
        ),
        SettingsSearchItem(
            SettingsItemIds.TRAINING_RESIDUAL_FATIGUE,
            SettingsCategoryId.TRAINING,
            R.string.advanced_residual_fatigue_title,
            listOf("fatigue decay", "half life", "gain"),
        ),
        SettingsSearchItem(
            SettingsItemIds.TRAINING_READINESS_ADVANCED,
            SettingsCategoryId.TRAINING,
            R.string.advanced_training_readiness_title,
            listOf("readiness scale", "load balance weight"),
        ),
    )

private val vitalsSearchItems =
    listOf(
        SettingsSearchItem(
            SettingsItemIds.VITALS_BASELINE_OVERRIDES,
            SettingsCategoryId.VITALS,
            R.string.advanced_baseline_overrides_title,
            listOf("hrv baseline", "rhr baseline", "override"),
        ),
        SettingsSearchItem(
            SettingsItemIds.VITALS_RESTING_HR_PERCENTILE,
            SettingsCategoryId.VITALS,
            R.string.advanced_resting_hr_percentile_label,
            listOf("resting heart rate", "percentile"),
        ),
        SettingsSearchItem(
            SettingsItemIds.VITALS_HRV_OPTIMAL_THRESHOLD,
            SettingsCategoryId.VITALS,
            R.string.threshold_hrv_optimal_label,
            listOf("hrv", "optimal"),
        ),
        SettingsSearchItem(
            SettingsItemIds.VITALS_HRV_WARNING_THRESHOLD,
            SettingsCategoryId.VITALS,
            R.string.threshold_hrv_warning_label,
            listOf("hrv", "warning"),
        ),
        SettingsSearchItem(
            SettingsItemIds.VITALS_RHR_OPTIMAL_THRESHOLD,
            SettingsCategoryId.VITALS,
            R.string.threshold_rhr_optimal_label,
            listOf("rhr", "resting heart rate", "optimal"),
        ),
        SettingsSearchItem(
            SettingsItemIds.VITALS_RHR_WARNING_THRESHOLD,
            SettingsCategoryId.VITALS,
            R.string.threshold_rhr_warning_label,
            listOf("rhr", "resting heart rate", "warning"),
        ),
        SettingsSearchItem(
            SettingsItemIds.VITALS_BODY_TEMP_THRESHOLD,
            SettingsCategoryId.VITALS,
            R.string.threshold_body_temp_elevated_label,
            listOf("temperature", "elevated"),
        ),
        SettingsSearchItem(
            SettingsItemIds.VITALS_CONSISTENCY_EVALUATION_PERIOD,
            SettingsCategoryId.VITALS,
            R.string.threshold_evaluation_period_label,
            listOf("evaluation", "days"),
        ),
        SettingsSearchItem(
            SettingsItemIds.VITALS_CONSISTENCY_BASELINE_WINDOW,
            SettingsCategoryId.VITALS,
            R.string.threshold_baseline_window_label,
            listOf("baseline window", "sessions"),
        ),
        SettingsSearchItem(
            SettingsItemIds.VITALS_HRR_RECOVERY_TOLERANCE,
            SettingsCategoryId.VITALS,
            R.string.advanced_hrr_tolerance_label,
            listOf("heart rate recovery", "tolerance"),
        ),
    )

private val dataSourcesSyncSearchItems =
    listOf(
        SettingsSearchItem(
            SettingsItemIds.DATA_DEVICE_SOURCES,
            SettingsCategoryId.DATA_SOURCES_SYNC,
            R.string.data_sources_title,
            listOf("device", "watch", "phone"),
        ),
        SettingsSearchItem(
            SettingsItemIds.DATA_HEALTH_CONNECT_SYNC,
            SettingsCategoryId.DATA_SOURCES_SYNC,
            R.string.settings_sub_health_connect,
            listOf("sync on open", "background sync"),
        ),
        SettingsSearchItem(
            SettingsItemIds.DATA_RETENTION_RESYNC,
            SettingsCategoryId.DATA_SOURCES_SYNC,
            R.string.settings_sub_data_management,
            listOf("retention", "resync", "historical"),
        ),
    )

private val backupRestoreSearchItems =
    listOf(
        SettingsSearchItem(
            SettingsItemIds.BACKUP_RESTORE,
            SettingsCategoryId.BACKUP_RESTORE,
            R.string.settings_sub_local_backup,
            listOf("export", "import", "restore", "password", "encrypted"),
        ),
    )

private val displaySearchItems =
    listOf(
        SettingsSearchItem(
            SettingsItemIds.DISPLAY_APP_THEME,
            SettingsCategoryId.DISPLAY,
            R.string.settings_label_app_theme,
            listOf("dark mode", "light mode", "system"),
        ),
        SettingsSearchItem(
            SettingsItemIds.DISPLAY_DYNAMIC_COLOR_PALETTE,
            SettingsCategoryId.DISPLAY,
            CoreUiR.string.onboarding_dynamic_color_label,
            listOf("dynamic color", "custom palette", "primary", "secondary", "tertiary"),
        ),
        SettingsSearchItem(
            SettingsItemIds.DISPLAY_UNIT_SYSTEM,
            SettingsCategoryId.DISPLAY,
            CoreUiR.string.unit_system_label,
            listOf("metric", "imperial"),
        ),
        SettingsSearchItem(
            SettingsItemIds.DISPLAY_WEEK_START_DAY,
            SettingsCategoryId.DISPLAY,
            CoreUiR.string.week_start_day_label,
            listOf("monday", "sunday", "calendar"),
        ),
        SettingsSearchItem(
            SettingsItemIds.DISPLAY_DASHBOARD_CARDS,
            SettingsCategoryId.DISPLAY,
            R.string.dashboard_cards_section_header,
            listOf("card display mode", "dashboard"),
        ),
        SettingsSearchItem(
            SettingsItemIds.DISPLAY_WORKOUT_LAYOUT,
            SettingsCategoryId.DISPLAY,
            R.string.workout_detail_layouts_section_header,
            listOf("workout detail", "layout"),
        ),
    )

private val supportAboutSearchItems =
    listOf(
        SettingsSearchItem(
            SettingsItemIds.SUPPORT_ISSUE_REPORTING,
            SettingsCategoryId.SUPPORT_ABOUT,
            R.string.settings_section_issue_reporting,
            listOf("bug", "feature request", "report"),
        ),
        SettingsSearchItem(
            SettingsItemIds.SUPPORT_ABOUT,
            SettingsCategoryId.SUPPORT_ABOUT,
            R.string.settings_about_button,
            listOf("licenses", "privacy policy", "source code", "version"),
        ),
    )

data class SettingsSearchItem(
    val id: String,
    val categoryId: SettingsCategoryId,
    val labelRes: Int,
    val keywords: List<String> = emptyList(),
)

val allSettingsSearchItems: List<SettingsSearchItem> =
    physiologyProfileSearchItems +
        sleepSearchItems +
        trainingSearchItems +
        vitalsSearchItems +
        dataSourcesSyncSearchItems +
        backupRestoreSearchItems +
        displaySearchItems +
        supportAboutSearchItems
