package app.readylytics.health.feature.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.feature.about.R

@Composable
fun LicenseSection() {
    Column {
        SectionDivider()

        SubHeader(stringResource(R.string.about_header_needs))
        BodyText(stringResource(R.string.about_hc_reads))
        BulletItem(stringResource(R.string.about_bullet_sleep_sessions))
        BulletItem(stringResource(R.string.about_bullet_sleep_hr))
        BulletItem(stringResource(R.string.about_bullet_sleep_hrv))
        BulletItem(stringResource(R.string.about_bullet_exercise_hr))

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
        BodyText(stringResource(R.string.about_reads_only))
        BodyText(stringResource(R.string.about_missing_data))
        BulletItem(stringResource(R.string.about_missing_data_partial))
        BulletItem(stringResource(R.string.about_missing_data_skip))

        SectionDivider()

        SubHeader(stringResource(R.string.about_header_stabilise))
        BodyText(stringResource(R.string.about_baselines_intro))
        BulletItem(stringResource(R.string.about_phase_calibration))
        BulletItem(stringResource(R.string.about_phase_provisional))
        BulletItem(stringResource(R.string.about_phase_maturing))
        BulletItem(stringResource(R.string.about_phase_mature))

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
        BodyText(stringResource(R.string.about_tracker_frequency))

        SectionDivider()

        SubHeader(stringResource(R.string.about_header_glossary))
        BulletItem(stringResource(R.string.about_glossary_hrv))
        BulletItem(stringResource(R.string.about_glossary_rmssd))
        BulletItem(stringResource(R.string.about_glossary_rhr))
        BulletItem(stringResource(R.string.about_glossary_deep))
        BulletItem(stringResource(R.string.about_glossary_rem))
        BulletItem(stringResource(R.string.about_glossary_trimp))
        BulletItem(stringResource(R.string.about_glossary_atl_ctl))

        SectionDivider()

        SubHeader(stringResource(R.string.about_header_limitations))
        BulletItem(stringResource(R.string.about_limit_1))
        BulletItem(stringResource(R.string.about_limit_2))
        BulletItem(stringResource(R.string.about_limit_3))
        BulletItem(stringResource(R.string.about_limit_4))
        BulletItem(stringResource(R.string.about_limit_5))
        BulletItem(stringResource(R.string.about_limit_6))

        BodyText(
            stringResource(R.string.about_sources_footer),
            fontStyle = FontStyle.Italic,
        )

        SectionDivider()

        HighlightBox {
            SubHeader(stringResource(R.string.about_header_disclaimer))
            BodyText(stringResource(R.string.about_disclaimer_p1))
            BodyText(stringResource(R.string.about_disclaimer_p2))
        }
    }
}
