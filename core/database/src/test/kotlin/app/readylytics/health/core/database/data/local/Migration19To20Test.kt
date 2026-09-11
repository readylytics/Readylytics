package app.readylytics.health.core.database.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.core.database.data.local.migration.MIGRATION_19_20
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration19To20Test {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HealthDatabase::class.java)

    @Test
    fun migrationExecutesExpectedSchemaAlter() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)

        MIGRATION_19_20.migrate(db)

        verify {
            db.execSQL("ALTER TABLE health_source_records ADD COLUMN originPackage TEXT")
            db.execSQL("ALTER TABLE health_source_records ADD COLUMN recordStartMs INTEGER")
            db.execSQL("ALTER TABLE health_source_records ADD COLUMN recordEndExclusiveMs INTEGER")
            db.execSQL("ALTER TABLE health_source_records ADD COLUMN lastModifiedMs INTEGER")
            db.execSQL("ALTER TABLE health_source_records ADD COLUMN metadataState TEXT NOT NULL DEFAULT 'UNKNOWN'")
            db.execSQL("ALTER TABLE health_source_records ADD COLUMN sourceRevision INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Test
    fun migration19To20PreservesDataAndInitializesJournalAndMutationState() {
        helper.createDatabase(TEST_DATABASE, 19).apply {
            execSQL(
                "INSERT INTO health_source_records (id, sourceRecordId, recordType, createdAtMs) " +
                    "VALUES (42, 'src-42', 'HEART_RATE', 1000)",
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(TEST_DATABASE, 20, true, *DatabaseMigrations.all)

        database.query(
            "SELECT id, sourceRecordId, metadataState, sourceRevision, " +
                "originPackage, recordStartMs, recordEndExclusiveMs, lastModifiedMs " +
                "FROM health_source_records WHERE id = 42",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(42L, cursor.getLong(0))
            assertEquals("src-42", cursor.getString(1))
            assertEquals("UNKNOWN", cursor.getString(2))
            assertEquals(0L, cursor.getLong(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
            assertTrue(cursor.isNull(6))
            assertTrue(cursor.isNull(7))
        }

        database.query("SELECT COUNT(*) FROM dirty_ranges").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
        }

        database.query("SELECT id, sourceGeneration, backfillAfterSourceRef FROM health_mutation_state").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(0L, cursor.getLong(1))
            assertEquals(0L, cursor.getLong(2))
        }
    }

    private companion object {
        const val TEST_DATABASE = "migration-19-20-test"
    }
}
