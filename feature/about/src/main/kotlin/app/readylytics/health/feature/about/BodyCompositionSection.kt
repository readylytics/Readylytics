package app.readylytics.health.feature.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.feature.about.R

@Composable
fun BodyCompositionSection() {
    Column {
        SectionDivider()

        SubHeader(stringResource(R.string.about_header_bmi_body_fat))
        BodyText(stringResource(R.string.about_bmi_intro))
        BulletItem(stringResource(R.string.about_bmi_bullet_underweight))
        BulletItem(stringResource(R.string.about_bmi_bullet_healthy))
        BulletItem(stringResource(R.string.about_bmi_bullet_overweight))
        BulletItem(stringResource(R.string.about_bmi_bullet_obesity))
        BodyText(stringResource(R.string.about_bmi_reference_gauge))

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
        BodyText(stringResource(R.string.about_body_fat_intro))
        BulletItem(stringResource(R.string.about_body_fat_bullet_male))
        BulletItem(stringResource(R.string.about_body_fat_bullet_female))
        BodyText(stringResource(R.string.about_body_fat_fixed_group))
        BodyText(stringResource(R.string.about_body_fat_midpoint_intro))
        BulletItem(stringResource(R.string.about_body_fat_midpoint_male))
        BulletItem(stringResource(R.string.about_body_fat_midpoint_female))
        BulletItem(stringResource(R.string.about_body_fat_midpoint_fixed))
        BodyText(stringResource(R.string.about_bmi_body_fat_status_meaning))

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
        SubHeader(stringResource(R.string.about_header_blood_pressure))
        BodyText(stringResource(R.string.about_blood_pressure_intro))
        BulletItem(stringResource(R.string.about_blood_pressure_bullet_optimal))
        BulletItem(stringResource(R.string.about_blood_pressure_bullet_neutral))
        BulletItem(stringResource(R.string.about_blood_pressure_bullet_warning))
        BulletItem(stringResource(R.string.about_blood_pressure_bullet_poor))
        BodyText(stringResource(R.string.about_blood_pressure_example))
        BodyText(stringResource(R.string.about_blood_pressure_chart_reference))
    }
}
