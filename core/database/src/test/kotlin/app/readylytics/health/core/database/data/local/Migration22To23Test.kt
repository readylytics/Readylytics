package app.readylytics.health.core.database.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.core.database.data.local.migration.Migration22To23
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration22To23Test {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HealthDatabase::class.java)

    @Test
    fun migrationCreatesEmptyStagingTablesAndPreservesExistingRows() {
        helper.createDatabase(TEST_DATABASE, 22).apply {
            execSQL(
                "INSERT INTO health_source_records " +
                    "(sourceRecordId, recordType, createdAtMs, metadataState, sourceRevision) " +
                    "VALUES ('hc-1', 'HEART_RATE', 1000, 'AUTHORITATIVE', 1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DATABASE, 23, true, Migration22To23)

        db.query("SELECT COUNT(*) FROM health_source_records").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM scan_seen_ids").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM scan_type_state").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }

    @Test
    fun stagingIndexIsCreated() {
        helper.createDatabase(TEST_DATABASE, 22).close()

        val db = helper.runMigrationsAndValidate(TEST_DATABASE, 23, true, Migration22To23)

        val indexNames = mutableListOf<String>()
        db.query("PRAGMA index_list('scan_seen_ids')").use { cursor ->
            while (cursor.moveToNext()) {
                indexNames += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
        }
        assertTrue(
            "expected staging lookup index, got $indexNames",
            indexNames.any { it == "index_scan_seen_ids_runId_chunkId_recordType" },
        )
        db.close()
    }

    private companion object {
        const val TEST_DATABASE = "migration-22-23-test.db"
    }
}
