package app.readylytics.health.data.preferences

import app.readylytics.health.core.model.data.preferences.LegacyBanisterMultipliers
import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class TrimpNormalizationMigrationTest {
    @Test
    fun `proto3 zero default migrates to 1_0 regardless of profile`() {
        for (profile in PhysiologyProfile.entries) {
            val result =
                TrimpMigrationHelper.migrateRasCalibration(
                    storedValue = 0.0f,
                    profile = profile,
                    alreadyMigrated = false,
                )
            assertEquals("Profile $profile", 1.0f, result, 0f)
        }
    }

    @Test
    fun `legacy default for ACTIVE profile normalizes to 1_0`() {
        val result =
            TrimpMigrationHelper.migrateRasCalibration(
                storedValue = 1.35f,
                profile = PhysiologyProfile.ACTIVE,
                alreadyMigrated = false,
            )
        assertEquals(1.0f, result, 0f)
    }

    @Test
    fun `legacy default for SEDENTARY profile normalizes to 1_0`() {
        val result =
            TrimpMigrationHelper.migrateRasCalibration(
                storedValue = 1.75f,
                profile = PhysiologyProfile.SEDENTARY,
                alreadyMigrated = false,
            )
        assertEquals(1.0f, result, 0f)
    }

    @Test
    fun `legacy default for ATHLETE profile already normalized`() {
        val result =
            TrimpMigrationHelper.migrateRasCalibration(
                storedValue = 1.00f,
                profile = PhysiologyProfile.ATHLETE,
                alreadyMigrated = false,
            )
        assertEquals(1.0f, result, 0f)
    }

    @Test
    fun `stored 1_35 with ATHLETE profile is preserved as override`() {
        val result =
            TrimpMigrationHelper.migrateRasCalibration(
                storedValue = 1.35f,
                profile = PhysiologyProfile.ATHLETE,
                alreadyMigrated = false,
            )
        assertEquals(1.35f, result, 0f)
    }

    @Test
    fun `stored 1_75 with ACTIVE profile is preserved as override`() {
        val result =
            TrimpMigrationHelper.migrateRasCalibration(
                storedValue = 1.75f,
                profile = PhysiologyProfile.ACTIVE,
                alreadyMigrated = false,
            )
        assertEquals(1.75f, result, 0f)
    }

    @Test
    fun `arbitrary override 1_50 is preserved`() {
        for (profile in PhysiologyProfile.entries) {
            val result =
                TrimpMigrationHelper.migrateRasCalibration(
                    storedValue = 1.50f,
                    profile = profile,
                    alreadyMigrated = false,
                )
            assertEquals("Profile $profile", 1.50f, result, 0f)
        }
    }

    @Test
    fun `idempotent already migrated returns stored value unchanged`() {
        val result =
            TrimpMigrationHelper.migrateRasCalibration(
                storedValue = 1.35f,
                profile = PhysiologyProfile.ACTIVE,
                alreadyMigrated = true,
            )
        assertEquals(1.35f, result, 0f)
    }
}
