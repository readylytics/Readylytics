package app.readylytics.health.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import app.readylytics.health.core.designsystem.spacing

@Composable
internal fun PermissionBulletRow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.hairline)) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = MaterialTheme.spacing.pageSectionGapSmall),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@StringRes
internal fun healthPermissionLabelRes(permission: String): Int? =
    when (permission) {
        HealthPermission.getReadPermission(SleepSessionRecord::class) -> R.string.onboarding_hc_permission_sleep
        HealthPermission.getReadPermission(HeartRateRecord::class) -> R.string.onboarding_hc_permission_heart_rate
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class) ->
            R.string.onboarding_hc_permission_hrv
        HealthPermission.getReadPermission(ExerciseSessionRecord::class) -> R.string.onboarding_hc_permission_exercise
        HealthPermission.getReadPermission(StepsRecord::class) -> R.string.onboarding_hc_permission_steps
        "android.permission.health.READ_HEALTH_DATA_HISTORY" -> R.string.onboarding_hc_permission_history
        HealthPermission.getReadPermission(WeightRecord::class) -> R.string.onboarding_hc_permission_weight
        HealthPermission.getReadPermission(BodyFatRecord::class) -> R.string.onboarding_hc_permission_body_fat
        HealthPermission.getReadPermission(
            BloodPressureRecord::class,
        ),
        -> R.string.onboarding_hc_permission_blood_pressure
        HealthPermission.getReadPermission(
            OxygenSaturationRecord::class,
        ),
        -> R.string.onboarding_hc_permission_oxygen_saturation
        HealthPermission.getReadPermission(
            BodyTemperatureRecord::class,
        ),
        -> R.string.onboarding_hc_permission_body_temperature
        else -> null
    }
