package app.readylytics.health.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.R

@Composable
fun ContributorsSection() {
    Column {
        SectionDivider()

        SubHeader(stringResource(R.string.about_header_sleep_score))
        BodyText(stringResource(R.string.about_sleep_score_intro))
        BulletItem(stringResource(R.string.about_sleep_duration_bullet))
        BulletItem(stringResource(R.string.about_sleep_architecture_bullet))
        BulletItem(stringResource(R.string.about_sleep_restoration_bullet))

        Spacer(Modifier.height(8.dp))
        BodyText(stringResource(R.string.about_reading_score_label))
        BulletItem(stringResource(R.string.about_reading_score_excellent))
        BulletItem(stringResource(R.string.about_reading_score_good))
        BulletItem(stringResource(R.string.about_reading_score_fair))
        BulletItem(stringResource(R.string.about_reading_score_poor))

        Spacer(Modifier.height(8.dp))
        BodyText(stringResource(R.string.about_tooltips_label))
        BulletItem(stringResource(R.string.about_tooltip_deep_sleep))
        BulletItem(stringResource(R.string.about_tooltip_rem))
        BulletItem(stringResource(R.string.about_tooltip_efficiency))

        Spacer(Modifier.height(8.dp))
        BodyText(stringResource(R.string.about_hrv_sensitivity_label))
        BodyText(stringResource(R.string.about_hrv_sensitivity_intro))
        BulletItem(stringResource(R.string.about_hrv_sensitivity_athlete))
        BulletItem(stringResource(R.string.about_hrv_sensitivity_active))
        BulletItem(stringResource(R.string.about_hrv_sensitivity_sedentary))
        BodyText(stringResource(R.string.about_hrv_sensitivity_outro))

        SectionDivider()

        SubHeader(stringResource(R.string.about_header_circadian))
        BodyText(stringResource(R.string.about_circadian_intro))
        BodyText(stringResource(R.string.about_circadian_mechanism))

        Spacer(Modifier.height(8.dp))
        BodyText(stringResource(R.string.about_threshold_label))
        BulletItem(stringResource(R.string.about_threshold_athlete))
        BulletItem(stringResource(R.string.about_threshold_active))
        BulletItem(stringResource(R.string.about_threshold_sedentary))
        BulletItem(stringResource(R.string.about_threshold_30))
        BulletItem(stringResource(R.string.about_threshold_30_90))
        BulletItem(stringResource(R.string.about_threshold_90))

        BodyText(stringResource(R.string.about_threshold_tuning))

        Spacer(Modifier.height(8.dp))
        BodyText(stringResource(R.string.about_circadian_caveat))

        SectionDivider()

        SubHeader(stringResource(R.string.about_header_readiness))
        BodyText(stringResource(R.string.about_readiness_intro))
        BodyText(stringResource(R.string.about_readiness_formula))
        BodyText(stringResource(R.string.about_readiness_averages))
        BulletItem(stringResource(R.string.about_atl_bullet))
        BulletItem(stringResource(R.string.about_ctl_bullet))
        BodyText(stringResource(R.string.about_readiness_ratio))

        Spacer(Modifier.height(8.dp))
        BodyText(stringResource(R.string.about_ratio_scoring_label))
        BulletItem(stringResource(R.string.about_ratio_sweet_spot))
        BulletItem(stringResource(R.string.about_ratio_quadratic))

        Spacer(Modifier.height(8.dp))
        BodyText(stringResource(R.string.about_readiness_tooltips_label))
        BulletItem(stringResource(R.string.about_tooltip_peak))
        BulletItem(stringResource(R.string.about_tooltip_maintain))
        BulletItem(stringResource(R.string.about_tooltip_caution))
        BulletItem(stringResource(R.string.about_tooltip_fatigue))

        Spacer(Modifier.height(8.dp))
        BodyText(stringResource(R.string.about_readiness_emergency_label))
        BodyText(stringResource(R.string.about_readiness_emergency_text))

        Spacer(Modifier.height(8.dp))
        BodyText(stringResource(R.string.about_readiness_no_penalty))

        SectionDivider()

        SubHeader(stringResource(R.string.about_header_load_sources))
        BodyText(stringResource(R.string.about_load_sources_intro))
        BulletItem(stringResource(R.string.about_load_sources_strain_bullet))
        BulletItem(stringResource(R.string.about_load_sources_ras_bullet))

        Spacer(Modifier.height(8.dp))
        BodyText(stringResource(R.string.about_load_sources_workout_only))
        BodyText(stringResource(R.string.about_load_sources_everyday))
        BodyText(stringResource(R.string.about_load_sources_zones))

        Spacer(Modifier.height(8.dp))
        BulletItem(stringResource(R.string.about_load_sources_coverage_bullet))
        BulletItem(stringResource(R.string.about_load_sources_valid_bucket_bullet))
        BulletItem(stringResource(R.string.about_load_sources_confidence_bullet))

        Spacer(Modifier.height(8.dp))
        BodyText(stringResource(R.string.about_load_sources_persistence))
        BodyText(stringResource(R.string.about_load_sources_bootstrap))
    }
}
