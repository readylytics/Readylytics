package app.readylytics.health.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class TrimpNormalizationMigrationTest {
    @Test
    fun `ACTIVE user with default 1_35 migrates to 1_0`() {
        val result =
            TrimpMigrationHelper.migrateRasCalibration(
                storedValue = 1.35f,
                alreadyMigrated = false,
            )
        assertEquals(1.0f, result, 0f)
    }

    @Test
    fun `SEDENTARY user with default 1_75 migrates to 1_0`() {
        val result =
            TrimpMigrationHelper.migrateRasCalibration(
                storedValue = 1.75f,
                alreadyMigrated = false,
            )
        assertEquals(1.0f, result, 0f)
    }

    @Test
    fun `ATHLETE user with 1_0 stays at 1_0`() {
        val result =
            TrimpMigrationHelper.migrateRasCalibration(
                storedValue = 1.0f,
                alreadyMigrated = false,
            )
        assertEquals(1.0f, result, 0f)
    }

    @Test
    fun `user who customized to 1_50 keeps 1_50`() {
        val result =
            TrimpMigrationHelper.migrateRasCalibration(
                storedValue = 1.50f,
                alreadyMigrated = false,
            )
        assertEquals(1.50f, result, 0f)
    }

    @Test
    fun `already migrated returns stored value unchanged`() {
        val result =
            TrimpMigrationHelper.migrateRasCalibration(
                storedValue = 1.35f,
                alreadyMigrated = true,
            )
        assertEquals(1.35f, result, 0f)
    }

    @Test
    fun `proto3 zero default migrates to 1_0`() {
        // proto3 float default is 0.0 — a user who never selected a profile
        val result =
            TrimpMigrationHelper.migrateRasCalibration(
                storedValue = 0.0f,
                alreadyMigrated = false,
            )
        assertEquals(1.0f, result, 0f)
    }
}
