package app.readylytics.health.data.local

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_28_29
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Verifies [MIGRATION_28_29] adds the workout/everyday load-source columns to
 * `daily_summaries` and performs the one-time copy of legacy columns into the
 * new `*WorkoutOnly` columns, leaving the new `*EverydayHr`/ATL/CTL/everyday
 * columns NULL.
 */
@RunWith(RobolectricTestRunner::class)
class Migration28To29Test {
    private lateinit var helper: SupportSQLiteOpenHelper

    // Mirrors the v28 `daily_summaries` CREATE TABLE statement (subset of columns relevant here).
    private val createV28TableSql =
        """
        CREATE TABLE IF NOT EXISTS `daily_summaries` (
            `dateMidnightMs` INTEGER NOT NULL,
            `loadScore` REAL,
            `readinessScore` REAL,
            `strainRatio` REAL,
            `totalTrimp` REAL,
            `paiScore` REAL,
            `totalPai` REAL,
            PRIMARY KEY(`dateMidnightMs`)
        )
        """.trimIndent()

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val configuration =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(null) // in-memory
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL(createV28TableSql)
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build()
        helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
    }

    @After
    fun tearDown() {
        helper.close()
    }

    @Test
    fun `MIGRATION_28_29 version range is correct`() {
        assertEquals(28, MIGRATION_28_29.startVersion)
        assertEquals(29, MIGRATION_28_29.endVersion)
    }

    @Test
    fun `MIGRATION_28_29 is registered in all array`() {
        assert(DatabaseMigrations.all.any { it === MIGRATION_28_29 })
    }

    @Test
    fun `MIGRATION_28_29 copies legacy columns into WorkoutOnly columns and leaves everyday columns null`() {
        val db = helper.writableDatabase

        db.execSQL(
            "INSERT INTO daily_summaries " +
                "(dateMidnightMs, totalTrimp, paiScore, totalPai, strainRatio, loadScore, readinessScore) " +
                "VALUES (1700000000000, 120.0, 45.0, 300.0, 1.25, 60.0, 80.0)",
        )

        MIGRATION_28_29.migrate(db)

        db
            .query(
                "SELECT trimpWorkoutOnly, paiWorkoutOnly, totalPaiWorkoutOnly, strainRatioWorkoutOnly, " +
                    "loadScoreWorkoutOnly, readinessWorkoutOnly, trimpEverydayHr, paiEverydayHr, " +
                    "atlWorkoutOnly, atlEverydayHr, ctlWorkoutOnly, ctlEverydayHr, " +
                    "everydayCoverageMinutes, everydayLoadConfidence " +
                    "FROM daily_summaries WHERE dateMidnightMs = 1700000000000",
            ).use { cursor ->
                assert(cursor.moveToFirst()) { "Migrated row must exist" }
                assertEquals(120.0, cursor.getDouble(0), 0.001)
                assertEquals(45.0, cursor.getDouble(1), 0.001)
                assertEquals(300.0, cursor.getDouble(2), 0.001)
                assertEquals(1.25, cursor.getDouble(3), 0.001)
                assertEquals(60.0, cursor.getDouble(4), 0.001)
                assertEquals(80.0, cursor.getDouble(5), 0.001)

                assertNull("trimpEverydayHr must be NULL", cursor.getString(6))
                assertNull("paiEverydayHr must be NULL", cursor.getString(7))
                assertNull("atlWorkoutOnly must be NULL", cursor.getString(8))
                assertNull("atlEverydayHr must be NULL", cursor.getString(9))
                assertNull("ctlWorkoutOnly must be NULL", cursor.getString(10))
                assertNull("ctlEverydayHr must be NULL", cursor.getString(11))
                assertNull("everydayCoverageMinutes must be NULL", cursor.getString(12))
                assertNull("everydayLoadConfidence must be NULL", cursor.getString(13))
            }
    }

    @Test
    fun `MIGRATION_28_29 adds all 18 new columns`() {
        val db = helper.writableDatabase
        MIGRATION_28_29.migrate(db)

        val expectedColumns =
            setOf(
                "trimpWorkoutOnly",
                "trimpEverydayHr",
                "paiWorkoutOnly",
                "paiEverydayHr",
                "totalPaiWorkoutOnly",
                "totalPaiEverydayHr",
                "atlWorkoutOnly",
                "atlEverydayHr",
                "ctlWorkoutOnly",
                "ctlEverydayHr",
                "strainRatioWorkoutOnly",
                "strainRatioEverydayHr",
                "loadScoreWorkoutOnly",
                "loadScoreEverydayHr",
                "readinessWorkoutOnly",
                "readinessEverydayHr",
                "everydayCoverageMinutes",
                "everydayLoadConfidence",
            )

        val actualColumns = mutableSetOf<String>()
        db.query("PRAGMA table_info(daily_summaries)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                actualColumns.add(cursor.getString(nameIndex))
            }
        }

        assert(actualColumns.containsAll(expectedColumns)) {
            "Missing columns: ${expectedColumns - actualColumns}"
        }
    }
}
