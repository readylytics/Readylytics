package app.readylytics.health.feature.onboarding

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
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
    }

    @Test
    fun healthPermissionLabelRes_returnsNullForUnknownPermission() {
        assertNull(healthPermissionLabelRes("android.permission.health.UNKNOWN_PERMISSION"))
    }
}
