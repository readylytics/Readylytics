package app.readylytics.health.data.local

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_29_30
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Verifies [MIGRATION_29_30] renames the old PAI-related columns to their RAS equivalents
 * and preserves data integrity.
 */
@RunWith(RobolectricTestRunner::class)
class Migration29To30Test {
    private lateinit var helper: SupportSQLiteOpenHelper

    // Mirrors daily_summaries schema at v29 (containing the PAI columns).
    private val createV29TableSql =
        """
        CREATE TABLE IF NOT EXISTS `daily_summaries` (
            `dateMidnightMs` INTEGER NOT NULL,
            `paiScore` REAL,
            `totalPai` REAL,
            `pai_scaling_factor` REAL,
            `paiWorkoutOnly` REAL,
            `paiEverydayHr` REAL,
            `totalPaiWorkoutOnly` REAL,
            `totalPaiEverydayHr` REAL,
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
                            db.execSQL(createV29TableSql)
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
    fun `MIGRATION_29_30 version range is correct`() {
        assertEquals(29, MIGRATION_29_30.startVersion)
        assertEquals(30, MIGRATION_29_30.endVersion)
    }

    @Test
    fun `MIGRATION_29_30 is registered in all array`() {
        assert(DatabaseMigrations.all.any { it === MIGRATION_29_30 })
    }

    @Test
    fun `MIGRATION_29_30 renames PAI columns to RAS columns and preserves data`() {
        val db = helper.writableDatabase

        db.execSQL(
            "INSERT INTO daily_summaries " +
                "(dateMidnightMs, paiScore, totalPai, pai_scaling_factor, " +
                "paiWorkoutOnly, paiEverydayHr, totalPaiWorkoutOnly, totalPaiEverydayHr) " +
                "VALUES (1700000000000, 45.0, 300.0, 1.5, 40.0, 50.0, 280.0, 290.0)",
        )

        MIGRATION_29_30.migrate(db)

        // Verify the old column names no longer exist
        val actualColumns = mutableSetOf<String>()
        db.query("PRAGMA table_info(daily_summaries)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                actualColumns.add(cursor.getString(nameIndex))
            }
        }

        val oldPaiColumns =
            listOf(
                "paiScore",
                "totalPai",
                "pai_scaling_factor",
                "paiWorkoutOnly",
                "paiEverydayHr",
                "totalPaiWorkoutOnly",
                "totalPaiEverydayHr",
            )
        for (oldCol in oldPaiColumns) {
            assertFalse("Column $oldCol should have been renamed", actualColumns.contains(oldCol))
        }

        // Verify renamed columns and data preservation
        db
            .query(
                "SELECT legacyRasScore, legacyTotalRas, ras_scaling_factor, rasWorkoutOnly, " +
                    "rasEverydayHr, totalRasWorkoutOnly, totalRasEverydayHr " +
                    "FROM daily_summaries WHERE dateMidnightMs = 1700000000000",
            ).use { cursor ->
                assertTrue("Migrated row must exist", cursor.moveToFirst())
                assertEquals(45.0, cursor.getDouble(0), 0.001)
                assertEquals(300.0, cursor.getDouble(1), 0.001)
                assertEquals(1.5, cursor.getDouble(2), 0.001)
                assertEquals(40.0, cursor.getDouble(3), 0.001)
                assertEquals(50.0, cursor.getDouble(4), 0.001)
                assertEquals(280.0, cursor.getDouble(5), 0.001)
                assertEquals(290.0, cursor.getDouble(6), 0.001)
            }
    }
}
