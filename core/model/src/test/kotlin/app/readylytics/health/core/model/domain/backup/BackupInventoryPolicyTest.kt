package app.readylytics.health.core.model.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupInventoryPolicyTest {
    @Test
    fun requiredTables_belowVersionFiveThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupInventoryPolicy.requiredTables(4)
        }
    }

    @Test
    fun requiredTables_versionFiveThroughNineRequiresCoreOnly() {
        val expectedCore = setOf(
            "sleepSessions",
            "heartRateRecords",
            "hrvRecords",
            "workouts",
            "dailySummaries",
        )
        for (v in 5..9) {
            assertEquals(expectedCore, BackupInventoryPolicy.requiredTables(v))
        }
    }

    @Test
    fun requiredTables_versionTenAddsVitalsSourceRecordsAndMinuteBuckets() {
        val tables = BackupInventoryPolicy.requiredTables(10)
        val expected = setOf(
            "sleepSessions",
            "heartRateRecords",
            "hrvRecords",
            "workouts",
            "dailySummaries",
            "weightRecords",
            "bodyFatRecords",
            "bloodPressureRecords",
            "oxygenSaturationRecords",
            "bodyTemperatureRecords",
            "stepRecords",
            "healthSourceRecords",
            "hrMinuteBuckets",
        )
        assertEquals(expected, tables)
    }

    @Test
    fun requiredTables_versionElevenThroughEighteenAddsWorkoutRoutePoints() {
        for (v in 11..18) {
            val tables = BackupInventoryPolicy.requiredTables(v)
            assertTrue("v$v must contain workoutRoutePoints", "workoutRoutePoints" in tables)
            assertTrue("v$v must not require vo2MaxRecords", "vo2MaxRecords" !in tables)
            assertEquals(14, tables.size)
        }
    }

    @Test
    fun requiredTables_versionNineteenAddsVo2MaxRecords() {
        val tables = BackupInventoryPolicy.requiredTables(19)
        assertEquals(15, tables.size)
        assertTrue("vo2MaxRecords" in tables)
    }

    @Test
    fun validateInventory_acceptsMatchingInventory() {
        val required = setOf("tableA", "tableB")
        val declared = mapOf("tableA" to 5L, "tableB" to 0L)
        val observed = mapOf("tableA" to 5L, "tableB" to 0L)

        // Should not throw
        BackupInventoryPolicy.validateInventory(required, declared, observed)
    }

    @Test
    fun validateInventory_rejectsMissingRequiredTableInObserved() {
        val required = setOf("tableA", "tableB")
        val declared = mapOf("tableA" to 5L, "tableB" to 0L)
        val observed = mapOf("tableA" to 5L)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            BackupInventoryPolicy.validateInventory(required, declared, observed)
        }
        assertTrue(ex.message?.contains("BACKUP_REQUIRED_TABLE_MISSING") == true)
    }

    @Test
    fun validateInventory_rejectsMissingRequiredTableInDeclared() {
        val required = setOf("tableA", "tableB")
        val declared = mapOf("tableA" to 5L)
        val observed = mapOf("tableA" to 5L, "tableB" to 0L)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            BackupInventoryPolicy.validateInventory(required, declared, observed)
        }
        assertTrue(ex.message?.contains("BACKUP_REQUIRED_COUNT_MISSING") == true)
    }

    @Test
    fun validateInventory_rejectsNegativeDeclaredCount() {
        val required = setOf("tableA")
        val declared = mapOf("tableA" to -1L)
        val observed = mapOf("tableA" to 0L)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            BackupInventoryPolicy.validateInventory(required, declared, observed)
        }
        assertTrue(ex.message?.contains("BACKUP_COUNT_INVALID") == true)
    }

    @Test
    fun validateInventory_rejectsCountMismatch() {
        val required = setOf("tableA")
        val declared = mapOf("tableA" to 10L)
        val observed = mapOf("tableA" to 9L)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            BackupInventoryPolicy.validateInventory(required, declared, observed)
        }
        assertTrue(ex.message?.contains("BACKUP_COUNT_MISMATCH") == true)
    }

    @Test
    fun validateInventory_rejectsDeclaredTableMissingInObserved() {
        val required = setOf("tableA")
        val declared = mapOf("tableA" to 5L, "tableOptional" to 2L)
        val observed = mapOf("tableA" to 5L)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            BackupInventoryPolicy.validateInventory(required, declared, observed)
        }
        assertTrue(ex.message?.contains("BACKUP_DECLARED_TABLE_MISSING") == true)
    }
}
