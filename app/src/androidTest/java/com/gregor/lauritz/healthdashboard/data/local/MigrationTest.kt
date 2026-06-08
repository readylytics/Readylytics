package com.gregor.lauritz.healthdashboard.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gregor.lauritz.healthdashboard.data.local.DatabaseMigrations.MIGRATION_22_23
import com.gregor.lauritz.healthdashboard.data.local.DatabaseMigrations.MIGRATION_25_26
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            ApplicationProvider.getApplicationContext<Context>(),
            HealthDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test
    fun testMigration25To26_baselineVersionDropped() {
        val db = helper.createDatabase(TEST_DB_NAME_5, 25)

        db.query("PRAGMA table_info(daily_summaries)").use { cursor ->
            val columnNames = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columnNames.add(cursor.getString(1))
            }
            assert(columnNames.contains("baseline_version")) { "v25 must have baseline_version column" }
        }

        db.close()

        val migratedDb =
            helper.runMigrationsAndValidate(
                TEST_DB_NAME_5,
                26,
                true,
                MIGRATION_25_26,
            )

        migratedDb.query("PRAGMA table_info(daily_summaries)").use { cursor ->
            val columnNames = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columnNames.add(cursor.getString(1))
            }
            assert(!columnNames.contains("baseline_version")) { "v26 must NOT have baseline_version column" }
        }

        migratedDb.close()
    }

    @Test
    fun testMigration22To23_schemaColumnsAdded() {
        val db = helper.createDatabase(TEST_DB_NAME, 22)

        db.query("PRAGMA table_info(daily_summaries)").use { cursor ->
            val columnNames = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columnNames.add(cursor.getString(1))
            }
            assert(!columnNames.contains("hrv_mu_mssd")) { "v22 should not have hrv_mu_mssd column" }
            assert(!columnNames.contains("rhr_bpm")) { "v22 should not have rhr_bpm column" }
            assert(!columnNames.contains("baseline_calculated_at_date")) {
                "v22 should not have baseline_calculated_at_date column"
            }
        }

        db.close()

        val migratedDb =
            helper.runMigrationsAndValidate(
                TEST_DB_NAME,
                23,
                true,
                MIGRATION_22_23,
            )

        migratedDb.query("PRAGMA table_info(daily_summaries)").use { cursor ->
            val columnNames = mutableMapOf<String, String>()
            while (cursor.moveToNext()) {
                columnNames[cursor.getString(1)] = cursor.getString(2)
            }

            assertNotNull(columnNames["hrv_mu_mssd"], "v23 must have hrv_mu_mssd column")
            assertNotNull(columnNames["hrv_sigma_mssd"], "v23 must have hrv_sigma_mssd column")
            assertNotNull(columnNames["rhr_bpm"], "v23 must have rhr_bpm column")
            assertNotNull(
                columnNames["baseline_calculated_at_date"],
                "v23 must have baseline_calculated_at_date column",
            )
            assertNotNull(columnNames["baseline_version"], "v23 must have baseline_version column")
        }

        migratedDb.close()
    }

    @Test
    fun testMigration22To23_baselineCalculatedAtDate_isNullableText() {
        helper.createDatabase(TEST_DB_NAME_2, 22).close()

        val migratedDb =
            helper.runMigrationsAndValidate(
                TEST_DB_NAME_2,
                23,
                true,
                MIGRATION_22_23,
            )

        migratedDb.query("PRAGMA table_info(daily_summaries)").use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                if (name == "baseline_calculated_at_date") {
                    found = true
                    val type = cursor.getString(2)
                    val notNull = cursor.getInt(3)
                    assertEquals("baseline_calculated_at_date must be TEXT type", "TEXT", type)
                    assertEquals("baseline_calculated_at_date must be nullable (notnull=0)", 0, notNull)
                }
            }
            assert(found) { "baseline_calculated_at_date column not found after migration" }
        }

        migratedDb.close()
    }

    @Test
    fun testMigration22To23_existingDataPreserved() {
        val db = helper.createDatabase(TEST_DB_NAME_3, 22)

        val values =
            ContentValues().apply {
                put("dateMidnightMs", 1_700_000_000_000L)
                put("sleepScore", 78.5f)
                put("loadScore", 55.0f)
                put("readinessScore", 72.0f)
                put("strainRatio", 1.1f)
                put("nocturnalRhr", 52)
                put("nocturnalHrv", 48)
                put("sleepDurationMinutes", 450)
                put("totalTrimp", 120.0f)
            }
        db.insert("daily_summaries", SQLiteDatabase.CONFLICT_REPLACE, values)
        db.close()

        val migratedDb =
            helper.runMigrationsAndValidate(
                TEST_DB_NAME_3,
                23,
                true,
                MIGRATION_22_23,
            )

        migratedDb
            .query(
                "SELECT dateMidnightMs, sleepScore, loadScore, nocturnalHrv, baseline_calculated_at_date, hrv_mu_mssd FROM daily_summaries WHERE dateMidnightMs = 1700000000000",
            ).use { cursor ->
                assert(cursor.moveToFirst()) { "Pre-migration row must exist after migration" }
                assertEquals(1_700_000_000_000L, cursor.getLong(0))
                assertEquals(78.5f, cursor.getFloat(1), 0.01f)
                assertEquals(55.0f, cursor.getFloat(2), 0.01f)
                assertEquals(48, cursor.getInt(3))
                assert(cursor.isNull(4)) { "baseline_calculated_at_date must be NULL after migration" }
                assert(cursor.isNull(5)) { "hrv_mu_mssd must be NULL after migration" }
            }

        migratedDb.close()
    }

    @Test
    fun testMigration22To23_baselineCalculatedAtDate_canBeSetAfterMigration() {
        helper.createDatabase(TEST_DB_NAME_4, 22).close()

        val migratedDb =
            helper.runMigrationsAndValidate(
                TEST_DB_NAME_4,
                23,
                true,
                MIGRATION_22_23,
            )

        migratedDb.execSQL(
            "INSERT INTO daily_summaries (dateMidnightMs, sleepScore, baseline_calculated_at_date, hrv_mu_mssd, hrv_sigma_mssd, rhr_bpm, baseline_version) VALUES (1701000000000, 85.0, '2024-11-26', 42.5, 8.2, 55.0, 1)",
        )

        migratedDb
            .query(
                "SELECT baseline_calculated_at_date, hrv_mu_mssd, rhr_bpm FROM daily_summaries WHERE dateMidnightMs = 1701000000000",
            ).use { cursor ->
                assert(cursor.moveToFirst()) { "Inserted row must be readable" }
                assertEquals("2024-11-26", cursor.getString(0))
                assertEquals(42.5f, cursor.getFloat(1), 0.01f)
                assertEquals(55.0f, cursor.getFloat(2), 0.01f)
            }

        migratedDb.close()
    }

    companion object {
        private const val TEST_DB_NAME = "migration_test.db"
        private const val TEST_DB_NAME_2 = "migration_test_2.db"
        private const val TEST_DB_NAME_3 = "migration_test_3.db"
        private const val TEST_DB_NAME_4 = "migration_test_4.db"
        private const val TEST_DB_NAME_5 = "migration_test_5.db"
    }
}
