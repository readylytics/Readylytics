package app.readylytics.health.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class TrimpNormalizationMigrationTest {
    @Test
    fun `historical lack of provenance preserves stored 1_35 without overwriting`() {
        val result =
            TrimpMigrationHelper.migrateRasCalibration(
                storedValue = 1.35f,
                alreadyMigrated = false,
            )
        assertEquals(1.35f, result, 0f)
    }

    @Test
    fun `historical lack of provenance preserves stored 1_75 without overwriting`() {
        val result =
            TrimpMigrationHelper.migrateRasCalibration(
                storedValue = 1.75f,
                alreadyMigrated = false,
            )
        assertEquals(1.75f, result, 0f)
    }

    @Test
    fun `historical lack of provenance preserves stored 1_0 without overwriting`() {
        val result =
            TrimpMigrationHelper.migrateRasCalibration(
                storedValue = 1.0f,
                alreadyMigrated = false,
            )
        assertEquals(1.0f, result, 0f)
    }

    @Test
    fun `historical lack of provenance preserves customized 1_50 without overwriting`() {
        val result =
            TrimpMigrationHelper.migrateRasCalibration(
                storedValue = 1.50f,
                alreadyMigrated = false,
            )
        assertEquals(1.50f, result, 0f)
    }

    @Test
    fun `historical lack of provenance preserves customized 2_0 without overwriting`() {
        val result =
            TrimpMigrationHelper.migrateRasCalibration(
                storedValue = 2.0f,
                alreadyMigrated = false,
            )
        assertEquals(2.0f, result, 0f)
    }

    @Test
    fun `already migrated returns stored value unchanged as no-op`() {
        val result =
            TrimpMigrationHelper.migrateRasCalibration(
                storedValue = 1.35f,
                alreadyMigrated = true,
            )
        assertEquals(1.35f, result, 0f)
    }

    @Test
    fun `proto3 zero default migrates to 1_0`() {
        // proto3 float default is 0.0 — a user who never had an explicit calibration set
        val result =
            TrimpMigrationHelper.migrateRasCalibration(
                storedValue = 0.0f,
                alreadyMigrated = false,
            )
        assertEquals(1.0f, result, 0f)
    }
}
