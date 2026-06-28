package app.readylytics.health.feature.insights

import androidx.annotation.AnyRes
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import app.readylytics.health.feature.insights.R
import app.readylytics.health.domain.insights.detail.InsightConfidence
import app.readylytics.health.domain.insights.detail.InsightDetailType
import app.readylytics.health.domain.model.InsightType

data class InsightDetailResourceSpec(
    val id: InsightType,
    val type: InsightDetailType,
    @StringRes val titleRes: Int,
    @AnyRes val cardDescriptionRes: Int,
    @StringRes val observedSignalTitleRes: Int,
    @StringRes val observedSignalRes: Int,
    @StringRes val meaningTitleRes: Int?,
    @StringRes val meaningRes: Int?,
    val confidence: InsightConfidence?,
    @StringRes val causesTitleRes: Int,
    @ArrayRes val causesArrayRes: Int,
    @StringRes val recommendationsTitleRes: Int,
    @ArrayRes val recommendationsArrayRes: Int,
    @StringRes val caveatsTitleRes: Int?,
    @ArrayRes val caveatsArrayRes: Int?,
    @StringRes val safetyNoteRes: Int?,
) {
    companion object {
        private val specs: Map<InsightType, InsightDetailResourceSpec> =
            listOf(
                physiology(
                    InsightType.BP_ELEVATED_HIGH_STRAIN,
                    R.string.insight_bp_elevated_high_strain_title,
                    R.string.insight_bp_elevated_high_strain_body,
                    R.string.insight_detail_bp_elevated_high_strain_observed,
                    InsightConfidence.MEDIUM,
                    R.array.insight_detail_bp_elevated_high_strain_causes,
                    R.array.insight_detail_bp_elevated_high_strain_recommendations,
                    R.array.insight_detail_bp_elevated_high_strain_caveats,
                    R.string.insight_detail_bp_elevated_high_strain_safety,
                ),
                physiology(
                    InsightType.CIRCADIAN_SHIFT_RECOVERY_MISS,
                    R.string.insight_circadian_shift_title,
                    R.plurals.insight_circadian_shift_body,
                    R.string.insight_detail_circadian_shift_recovery_miss_observed,
                    InsightConfidence.MEDIUM,
                    R.array.insight_detail_circadian_shift_recovery_miss_causes,
                    R.array.insight_detail_circadian_shift_recovery_miss_recommendations,
                    R.array.insight_detail_circadian_shift_recovery_miss_caveats,
                    null,
                ),
                physiology(
                    InsightType.HIGH_STRAIN_SLEEP_DEFICIT,
                    R.string.insight_high_strain_sleep_deficit_title,
                    R.plurals.insight_high_strain_sleep_deficit_body,
                    R.string.insight_detail_high_strain_sleep_deficit_observed,
                    InsightConfidence.MEDIUM,
                    R.array.insight_detail_high_strain_sleep_deficit_causes,
                    R.array.insight_detail_high_strain_sleep_deficit_recommendations,
                    R.array.insight_detail_high_strain_sleep_deficit_caveats,
                    null,
                ),
                physiology(
                    InsightType.HRV_DECLINE_STREAK,
                    R.string.insight_hrv_decline_streak_title,
                    R.plurals.insight_hrv_decline_streak_body,
                    R.string.insight_detail_hrv_decline_streak_observed,
                    InsightConfidence.MEDIUM,
                    R.array.insight_detail_hrv_decline_streak_causes,
                    R.array.insight_detail_hrv_decline_streak_recommendations,
                    R.array.insight_detail_hrv_decline_streak_caveats,
                    null,
                ),
                physiology(
                    InsightType.HRV_DROP_LOW_SPO2,
                    R.string.insight_hrv_drop_low_spo2_title,
                    R.string.insight_hrv_drop_low_spo2_body,
                    R.string.insight_detail_hrv_drop_low_spo2_observed,
                    InsightConfidence.MEDIUM,
                    R.array.insight_detail_hrv_drop_low_spo2_causes,
                    R.array.insight_detail_hrv_drop_low_spo2_recommendations,
                    R.array.insight_detail_hrv_drop_low_spo2_caveats,
                    R.string.insight_detail_hrv_drop_low_spo2_safety,
                ),
                physiology(
                    InsightType.LATE_NADIR,
                    R.string.insight_late_nadir_title,
                    R.string.insight_late_nadir_body,
                    R.string.insight_detail_late_nadir_observed,
                    InsightConfidence.MEDIUM,
                    R.array.insight_detail_late_nadir_causes,
                    R.array.insight_detail_late_nadir_recommendations,
                    R.array.insight_detail_late_nadir_caveats,
                    null,
                ),
                physiology(
                    InsightType.LATE_NADIR_ELEVATED_RHR,
                    R.string.insight_late_nadir_elevated_rhr_title,
                    R.string.insight_late_nadir_elevated_rhr_body,
                    R.string.insight_detail_late_nadir_elevated_rhr_observed,
                    InsightConfidence.MEDIUM_HIGH,
                    R.array.insight_detail_late_nadir_elevated_rhr_causes,
                    R.array.insight_detail_late_nadir_elevated_rhr_recommendations,
                    R.array.insight_detail_late_nadir_elevated_rhr_caveats,
                    null,
                ),
                physiology(
                    InsightType.LATE_NADIR_SHORT_SLEEP,
                    R.string.insight_late_nadir_short_sleep_title,
                    R.plurals.insight_late_nadir_short_sleep_body,
                    R.string.insight_detail_late_nadir_short_sleep_observed,
                    InsightConfidence.MEDIUM,
                    R.array.insight_detail_late_nadir_short_sleep_causes,
                    R.array.insight_detail_late_nadir_short_sleep_recommendations,
                    R.array.insight_detail_late_nadir_short_sleep_caveats,
                    null,
                ),
                physiology(
                    InsightType.LOAD_SPIKE_RECOVERY_STRAIN,
                    R.string.insight_load_spike_recovery_strain_title,
                    R.string.insight_load_spike_recovery_strain_body,
                    R.string.insight_detail_load_spike_recovery_strain_observed,
                    InsightConfidence.MEDIUM,
                    R.array.insight_detail_load_spike_recovery_strain_causes,
                    R.array.insight_detail_load_spike_recovery_strain_recommendations,
                    R.array.insight_detail_load_spike_recovery_strain_caveats,
                    R.string.insight_detail_load_spike_recovery_strain_safety,
                ),
                training(
                    InsightType.RAS_DEPLETION_HIGH_STRAIN,
                    R.string.insight_ras_depletion_high_strain_title,
                    R.string.insight_ras_depletion_high_strain_body,
                    R.string.insight_detail_ras_depletion_high_strain_observed,
                    R.array.insight_detail_ras_depletion_high_strain_causes,
                    R.array.insight_detail_ras_depletion_high_strain_recommendations,
                    R.array.insight_detail_ras_depletion_high_strain_caveats,
                ),
                training(
                    InsightType.RAS_WEEKLY_UNDERPERFORMANCE,
                    R.string.insight_ras_weekly_underperformance_title,
                    R.string.insight_ras_weekly_underperformance_body,
                    R.string.insight_detail_ras_weekly_underperformance_observed,
                    R.array.insight_detail_ras_weekly_underperformance_causes,
                    R.array.insight_detail_ras_weekly_underperformance_recommendations,
                    R.array.insight_detail_ras_weekly_underperformance_caveats,
                ),
                dataQuality(
                    InsightType.RECOVERY_HRV_MISSING,
                    R.string.insight_recovery_hrv_missing_title,
                    R.string.insight_recovery_hrv_missing_body,
                    R.string.insight_detail_recovery_hrv_missing_observed,
                    R.array.insight_detail_recovery_hrv_missing_causes,
                    R.array.insight_detail_recovery_hrv_missing_recommendations,
                ),
                dataQuality(
                    InsightType.RECOVERY_STAGES_MISSING,
                    R.string.insight_recovery_stages_missing_title,
                    R.string.insight_recovery_stages_missing_body,
                    R.string.insight_detail_recovery_stages_missing_observed,
                    R.array.insight_detail_recovery_stages_missing_causes,
                    R.array.insight_detail_recovery_stages_missing_recommendations,
                ),
                physiology(
                    InsightType.REST_DAY_NO_IMPACT,
                    R.string.insight_rest_day_no_impact_title,
                    R.string.insight_rest_day_no_impact_body,
                    R.string.insight_detail_rest_day_no_impact_observed,
                    InsightConfidence.LOW_MEDIUM,
                    R.array.insight_detail_rest_day_no_impact_causes,
                    R.array.insight_detail_rest_day_no_impact_recommendations,
                    R.array.insight_detail_rest_day_no_impact_caveats,
                    null,
                ),
                physiology(
                    InsightType.REST_DAY_SUCCESS,
                    R.string.insight_rest_day_success_title,
                    R.string.insight_rest_day_success_body,
                    R.string.insight_detail_rest_day_success_observed,
                    InsightConfidence.LOW_MEDIUM,
                    R.array.insight_detail_rest_day_success_causes,
                    R.array.insight_detail_rest_day_success_recommendations,
                    R.array.insight_detail_rest_day_success_caveats,
                    null,
                ),
                physiology(
                    InsightType.SICK_INDICATOR,
                    R.string.insight_sick_indicator_title,
                    R.string.insight_sick_indicator_body,
                    R.string.insight_detail_sick_indicator_observed,
                    InsightConfidence.MEDIUM_HIGH,
                    R.array.insight_detail_sick_indicator_causes,
                    R.array.insight_detail_sick_indicator_recommendations,
                    R.array.insight_detail_sick_indicator_caveats,
                    R.string.insight_detail_sick_indicator_safety,
                ),
                training(
                    InsightType.STEP_SHORTFALL,
                    R.string.insight_step_shortfall_title,
                    R.plurals.insight_step_shortfall_body,
                    R.string.insight_detail_step_shortfall_observed,
                    R.array.insight_detail_step_shortfall_causes,
                    R.array.insight_detail_step_shortfall_recommendations,
                    R.array.insight_detail_step_shortfall_caveats,
                ),
                physiology(
                    InsightType.STRONG_RECOVERY_SIGNAL,
                    R.string.insight_strong_recovery_signal_title,
                    R.string.insight_strong_recovery_signal_body,
                    R.string.insight_detail_strong_recovery_signal_observed,
                    InsightConfidence.MEDIUM,
                    R.array.insight_detail_strong_recovery_signal_causes,
                    R.array.insight_detail_strong_recovery_signal_recommendations,
                    R.array.insight_detail_strong_recovery_signal_caveats,
                    null,
                ),
                physiology(
                    InsightType.WEIGHT_DRIFT_TRAINING_LOAD,
                    R.string.insight_weight_drift_training_load_title,
                    R.string.insight_weight_drift_training_load_body,
                    R.string.insight_detail_weight_drift_training_load_observed,
                    InsightConfidence.MEDIUM,
                    R.array.insight_detail_weight_drift_training_load_causes,
                    R.array.insight_detail_weight_drift_training_load_recommendations,
                    R.array.insight_detail_weight_drift_training_load_caveats,
                    null,
                ),
                physiology(
                    InsightType.WORKOUT_IMPACT,
                    R.string.insight_workout_impact_title,
                    R.string.insight_workout_impact_body,
                    R.string.insight_detail_workout_impact_observed,
                    InsightConfidence.MEDIUM,
                    R.array.insight_detail_workout_impact_causes,
                    R.array.insight_detail_workout_impact_recommendations,
                    R.array.insight_detail_workout_impact_caveats,
                    null,
                ),
            ).associateBy { it.id }

        fun forType(type: InsightType): InsightDetailResourceSpec? = specs[type]

        fun all(): Collection<InsightDetailResourceSpec> = specs.values

        private fun physiology(
            id: InsightType,
            @StringRes title: Int,
            @AnyRes card: Int,
            @StringRes observed: Int,
            confidence: InsightConfidence,
            @ArrayRes causes: Int,
            @ArrayRes recommendations: Int,
            @ArrayRes caveats: Int?,
            @StringRes safety: Int?,
        ) = InsightDetailResourceSpec(
            id = id,
            type = InsightDetailType.PHYSIOLOGY,
            titleRes = title,
            cardDescriptionRes = card,
            observedSignalTitleRes = R.string.insight_detail_observed_signal,
            observedSignalRes = observed,
            meaningTitleRes = R.string.insight_detail_what_this_might_mean,
            meaningRes = meaningResFor(id),
            confidence = confidence,
            causesTitleRes = R.string.insight_detail_why_this_might_have_happened,
            causesArrayRes = causes,
            recommendationsTitleRes = R.string.insight_detail_what_you_can_do_today,
            recommendationsArrayRes = recommendations,
            caveatsTitleRes = R.string.insight_detail_what_not_to_infer,
            caveatsArrayRes = caveats,
            safetyNoteRes = safety,
        )

        private fun training(
            id: InsightType,
            @StringRes title: Int,
            @AnyRes card: Int,
            @StringRes observed: Int,
            @ArrayRes causes: Int,
            @ArrayRes recommendations: Int,
            @ArrayRes caveats: Int,
        ) = InsightDetailResourceSpec(
            id = id,
            type = InsightDetailType.TRAINING_BEHAVIOR,
            titleRes = title,
            cardDescriptionRes = card,
            observedSignalTitleRes = R.string.insight_detail_observed_signal,
            observedSignalRes = observed,
            meaningTitleRes = R.string.insight_detail_why_this_matters,
            meaningRes = meaningResFor(id),
            confidence = null,
            causesTitleRes = R.string.insight_detail_what_might_explain_it,
            causesArrayRes = causes,
            recommendationsTitleRes = R.string.insight_detail_what_you_can_do,
            recommendationsArrayRes = recommendations,
            caveatsTitleRes = R.string.insight_detail_what_not_to_infer,
            caveatsArrayRes = caveats,
            safetyNoteRes = null,
        )

        private fun dataQuality(
            id: InsightType,
            @StringRes title: Int,
            @AnyRes card: Int,
            @StringRes missing: Int,
            @ArrayRes causes: Int,
            @ArrayRes recommendations: Int,
        ) = InsightDetailResourceSpec(
            id = id,
            type = InsightDetailType.DATA_QUALITY,
            titleRes = title,
            cardDescriptionRes = card,
            observedSignalTitleRes = R.string.insight_detail_what_data_is_missing,
            observedSignalRes = missing,
            meaningTitleRes = R.string.insight_detail_how_this_affects_your_score,
            meaningRes = meaningResFor(id),
            confidence = null,
            causesTitleRes = R.string.insight_detail_why_this_might_have_happened,
            causesArrayRes = causes,
            recommendationsTitleRes = R.string.insight_detail_what_you_can_check,
            recommendationsArrayRes = recommendations,
            caveatsTitleRes = null,
            caveatsArrayRes = null,
            safetyNoteRes = null,
        )

        @StringRes
        private fun meaningResFor(id: InsightType): Int =
            when (id) {
                InsightType.BP_ELEVATED_HIGH_STRAIN -> R.string.insight_detail_bp_elevated_high_strain_meaning
                InsightType.CIRCADIAN_SHIFT_RECOVERY_MISS ->
                    R.string.insight_detail_circadian_shift_recovery_miss_meaning
                InsightType.HIGH_STRAIN_SLEEP_DEFICIT -> R.string.insight_detail_high_strain_sleep_deficit_meaning
                InsightType.HRV_DECLINE_STREAK -> R.string.insight_detail_hrv_decline_streak_meaning
                InsightType.HRV_DROP_LOW_SPO2 -> R.string.insight_detail_hrv_drop_low_spo2_meaning
                InsightType.LATE_NADIR -> R.string.insight_detail_late_nadir_meaning
                InsightType.LATE_NADIR_ELEVATED_RHR -> R.string.insight_detail_late_nadir_elevated_rhr_meaning
                InsightType.LATE_NADIR_SHORT_SLEEP -> R.string.insight_detail_late_nadir_short_sleep_meaning
                InsightType.LOAD_SPIKE_RECOVERY_STRAIN -> R.string.insight_detail_load_spike_recovery_strain_meaning
                InsightType.RAS_DEPLETION_HIGH_STRAIN -> R.string.insight_detail_ras_depletion_high_strain_meaning
                InsightType.RAS_WEEKLY_UNDERPERFORMANCE ->
                    R.string.insight_detail_ras_weekly_underperformance_meaning
                InsightType.RECOVERY_HRV_MISSING -> R.string.insight_detail_recovery_hrv_missing_meaning
                InsightType.RECOVERY_STAGES_MISSING -> R.string.insight_detail_recovery_stages_missing_meaning
                InsightType.REST_DAY_NO_IMPACT -> R.string.insight_detail_rest_day_no_impact_meaning
                InsightType.REST_DAY_SUCCESS -> R.string.insight_detail_rest_day_success_meaning
                InsightType.SICK_INDICATOR -> R.string.insight_detail_sick_indicator_meaning
                InsightType.STEP_SHORTFALL -> R.string.insight_detail_step_shortfall_meaning
                InsightType.STRONG_RECOVERY_SIGNAL -> R.string.insight_detail_strong_recovery_signal_meaning
                InsightType.WEIGHT_DRIFT_TRAINING_LOAD -> R.string.insight_detail_weight_drift_training_load_meaning
                InsightType.WORKOUT_IMPACT -> R.string.insight_detail_workout_impact_meaning
            }
    }
}
