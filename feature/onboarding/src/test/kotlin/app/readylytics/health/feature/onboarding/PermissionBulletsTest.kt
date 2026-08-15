package app.readylytics.health.feature.onboarding

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionBulletsTest {
    @Test
    fun healthPermissionLabelRes_mapsKnownPermissionsToCorrectStringResources() {
        assertEquals(
            R.string.onboarding_hc_permission_sleep,
            healthPermissionLabelRes(HealthPermission.getReadPermission(SleepSessionRecord::class)),
        )
        assertEquals(
            R.string.onboarding_hc_permission_heart_rate,
            healthPermissionLabelRes(HealthPermission.getReadPermission(HeartRateRecord::class)),
        )
        assertEquals(
            R.string.onboarding_hc_permission_hrv,
            healthPermissionLabelRes(HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)),
        )
        assertEquals(
            R.string.onboarding_hc_permission_exercise,
            healthPermissionLabelRes(HealthPermission.getReadPermission(ExerciseSessionRecord::class)),
        )
        assertEquals(
            R.string.onboarding_hc_permission_steps,
            healthPermissionLabelRes(HealthPermission.getReadPermission(StepsRecord::class)),
        )
        assertEquals(
            R.string.onboarding_hc_permission_history,
            healthPermissionLabelRes("android.permission.health.READ_HEALTH_DATA_HISTORY"),
        )
        assertEquals(
            R.string.onboarding_hc_permission_weight,
            healthPermissionLabelRes(HealthPermission.getReadPermission(WeightRecord::class)),
        )
        assertEquals(
            R.string.onboarding_hc_permission_body_fat,
            healthPermissionLabelRes(HealthPermission.getReadPermission(BodyFatRecord::class)),
        )
        assertEquals(
            R.string.onboarding_hc_permission_blood_pressure,
            healthPermissionLabelRes(HealthPermission.getReadPermission(BloodPressureRecord::class)),
        )
        assertEquals(
            R.string.onboarding_hc_permission_oxygen_saturation,
            healthPermissionLabelRes(HealthPermission.getReadPermission(OxygenSaturationRecord::class)),
        )
        assertEquals(
            R.string.onboarding_hc_permission_body_temperature,
            healthPermissionLabelRes(HealthPermission.getReadPermission(BodyTemperatureRecord::class)),
        )
    }

    @Test
    fun healthPermissionLabelRes_mapsExerciseRoutesPermission() {
        assertEquals(
            R.string.onboarding_hc_permission_exercise_routes,
            healthPermissionLabelRes("android.permission.health.READ_EXERCISE_ROUTES"),
        )
    }

    @Test
    fun healthPermissionLabelRes_returnsNullForUnknownPermission() {
        assertNull(healthPermissionLabelRes("android.permission.health.UNKNOWN_PERMISSION"))
    }
}
