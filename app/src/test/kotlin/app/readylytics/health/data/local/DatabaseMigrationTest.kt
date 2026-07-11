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
class DatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            HealthDatabase::class.java,
        )

    @Test
    fun `database version matches latest migration`() {
        assertEquals(6, HealthDatabase.DATABASE_VERSION)
    }

    @Test
    fun `database migrations are registered`() {
        assertTrue(DatabaseMigrations.all.isNotEmpty())
    }

    @Test
    fun `future migrations are registered in sequential order`() {
        val migrations = DatabaseMigrations.all.filter { it.startVersion < it.endVersion }
        migrations.zipWithNext { current, next ->
            assertEquals(
                "Gap between migration ${current.endVersion} and ${next.startVersion}",
                current.endVersion,
                next.startVersion,
            )
        }
    }

    @Test
    fun `future migration chain starts at baseline version`() {
        val migrations = DatabaseMigrations.all.filter { it.startVersion < it.endVersion }
        if (migrations.isNotEmpty()) {
            assertEquals(1, migrations.first().startVersion)
        }
    }

    @Test
    fun `future migration chain ends at database version`() {
        val migrations = DatabaseMigrations.all.filter { it.startVersion < it.endVersion }
        if (migrations.isNotEmpty()) {
            assertEquals(HealthDatabase.DATABASE_VERSION, migrations.last().endVersion)
        }
    }

    @Test
    fun migrate5To6_verifiesTablesCreatedAndFieldsAdded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbPath = context.getDatabasePath("test-db").absolutePath

        // Create DB with version 5
        var db = helper.createDatabase(dbPath, 5)
        db.execSQL(
            "INSERT INTO workout_records (id, startTime, endTime, exerciseType, durationMinutes, zone1Minutes, zone2Minutes, zone3Minutes, zone4Minutes, zone5Minutes, trimp, avgHr) VALUES ('w1', 1000, 2000, '56', 15, 0, 0, 0, 0, 0, 0, 0)",
        )
        db.close()

        // Run migration 5 to 6
        db = helper.runMigrationsAndValidate(dbPath, 6, true, DatabaseMigrations.MIGRATION_5_6)

        // Query to verify new fields
        val cursor = db.query("SELECT routeState, avgSpeedKmh FROM workout_records WHERE id = 'w1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("NOT_AVAILABLE", cursor.getString(0))
        assertTrue(cursor.isNull(1))
        cursor.close()
        db.close()
    }
}
