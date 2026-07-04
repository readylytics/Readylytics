package app.readylytics.health.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationInstrumentedTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            HealthDatabase::class.java,
        )

    @Test
    fun migrate3To4CreatesAuditSchemaAndPreservesExistingData() {
        helper.createDatabase(TEST_DATABASE, 3).apply {
            execSQL(
                "INSERT INTO insight_dismissals (dateMidnightMs, type) VALUES (?, ?)",
                arrayOf<Any>(1_234L, "REST"),
            )
            close()
        }

        val database =
            helper.runMigrationsAndValidate(
                TEST_DATABASE,
                4,
                true,
                *DatabaseMigrations.all,
            )

        database.query("SELECT dateMidnightMs, type FROM insight_dismissals").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1_234L, cursor.getLong(0))
            assertEquals("REST", cursor.getString(1))
        }
        database.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'audit_events'").use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        database.query(
            "SELECT name FROM sqlite_master " +
                "WHERE type = 'index' AND name = 'index_audit_events_occurredAtEpochMs'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
    }

    private companion object {
        const val TEST_DATABASE = "audit-migration-test"
    }
}
