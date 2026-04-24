package com.gregor.lauritz.healthdashboard.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.HrvRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity

@Database(
    entities = [
        SleepSessionEntity::class,
        HeartRateRecordEntity::class,
        HrvRecordEntity::class,
        WorkoutRecordEntity::class,
        DailySummaryEntity::class,
    ],
    version = 9,
)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun sleepSessionDao(): SleepSessionDao

    abstract fun heartRateDao(): HeartRateDao

    abstract fun hrvDao(): HrvDao

    abstract fun workoutDao(): WorkoutDao

    abstract fun dailySummaryDao(): DailySummaryDao

    companion object {
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE daily_summaries ADD COLUMN rhrRatio REAL")
                    db.execSQL("ALTER TABLE daily_summaries ADD COLUMN hrvZScore REAL")
                }

                override fun migrate(connection: SQLiteConnection) {
                    connection.execSQL("ALTER TABLE daily_summaries ADD COLUMN rhrRatio REAL")
                    connection.execSQL("ALTER TABLE daily_summaries ADD COLUMN hrvZScore REAL")
                }
            }

        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE daily_summaries ADD COLUMN hrvBaseline REAL")
                }

                override fun migrate(connection: SQLiteConnection) {
                    connection.execSQL("ALTER TABLE daily_summaries ADD COLUMN hrvBaseline REAL")
                }
            }

        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                private val sql =
                    listOf(
                        """
                        CREATE TABLE daily_summaries_new (
                            dateMidnightMs INTEGER NOT NULL PRIMARY KEY,
                            sleepScore REAL,
                            loadScore REAL,
                            readinessScore REAL,
                            strainRatio REAL,
                            nocturnalRhr REAL,
                            nocturnalHrv REAL,
                            sleepDurationMinutes INTEGER,
                            deepSleepPercent REAL,
                            remSleepPercent REAL,
                            totalTrimp REAL,
                            rhrRatio REAL,
                            hrvBaseline REAL
                        )
                        """.trimIndent(),
                        """
                        INSERT INTO daily_summaries_new
                            (dateMidnightMs, sleepScore, loadScore, readinessScore, strainRatio,
                             nocturnalRhr, nocturnalHrv, sleepDurationMinutes, deepSleepPercent,
                             remSleepPercent, totalTrimp, rhrRatio, hrvBaseline)
                        SELECT dateMidnightMs, sleepScore, loadScore, NULL, strainRatio,
                               nocturnalRhr, nocturnalHrv, sleepDurationMinutes, deepSleepPercent,
                               remSleepPercent, totalTrimp, rhrRatio, hrvBaseline
                        FROM daily_summaries
                        """.trimIndent(),
                        "DROP TABLE daily_summaries",
                        "ALTER TABLE daily_summaries_new RENAME TO daily_summaries",
                    )

                override fun migrate(db: SupportSQLiteDatabase) {
                    sql.forEach { db.execSQL(it) }
                }

                override fun migrate(connection: SQLiteConnection) {
                    sql.forEach { connection.execSQL(it) }
                }
            }

        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE daily_summaries ADD COLUMN restingHeartRate REAL")
                    db.execSQL("ALTER TABLE daily_summaries ADD COLUMN restingHrRatio REAL")
                    db.execSQL("ALTER TABLE daily_summaries ADD COLUMN restingHrBaseline REAL")
                }

                override fun migrate(connection: SQLiteConnection) {
                    connection.execSQL("ALTER TABLE daily_summaries ADD COLUMN restingHeartRate REAL")
                    connection.execSQL("ALTER TABLE daily_summaries ADD COLUMN restingHrRatio REAL")
                    connection.execSQL("ALTER TABLE daily_summaries ADD COLUMN restingHrBaseline REAL")
                }
            }

        val MIGRATION_5_6 =
            object : Migration(5, 6) {
                private val sql =
                    listOf(
                        "DROP TABLE IF EXISTS daily_summaries_new",
                        "CREATE TABLE daily_summaries_new (" +
                            "dateMidnightMs INTEGER NOT NULL PRIMARY KEY, " +
                            "sleepScore REAL, loadScore REAL, readinessScore REAL, " +
                            "strainRatio REAL, nocturnalRhr INTEGER, nocturnalHrv INTEGER, " +
                            "sleepDurationMinutes INTEGER, deepSleepPercent REAL, " +
                            "remSleepPercent REAL, totalTrimp REAL, rhrRatio REAL, " +
                            "hrvBaseline INTEGER, restingHeartRate INTEGER, " +
                            "restingHrRatio REAL, restingHrBaseline INTEGER)",
                        "INSERT INTO daily_summaries_new SELECT * FROM daily_summaries",
                        "DROP TABLE daily_summaries",
                        "ALTER TABLE daily_summaries_new RENAME TO daily_summaries",
                    )

                override fun migrate(db: SupportSQLiteDatabase) {
                    sql.forEach { db.execSQL(it) }
                }

                override fun migrate(connection: SQLiteConnection) {
                    sql.forEach { connection.execSQL(it) }
                }
            }

        val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE workout_records ADD COLUMN avgHr INTEGER NOT NULL DEFAULT 0")
                }

                override fun migrate(connection: SQLiteConnection) {
                    connection.execSQL("ALTER TABLE workout_records ADD COLUMN avgHr INTEGER NOT NULL DEFAULT 0")
                }
            }
        val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_heart_rate_records_timestampMs` ON `heart_rate_records` (`timestampMs`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_heart_rate_records_recordType` ON `heart_rate_records` (`recordType`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_heart_rate_records_sessionId` ON `heart_rate_records` (`sessionId`)")
                }

                override fun migrate(connection: SQLiteConnection) {
                    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_heart_rate_records_timestampMs` ON `heart_rate_records` (`timestampMs`)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_heart_rate_records_recordType` ON `heart_rate_records` (`recordType`)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_heart_rate_records_sessionId` ON `heart_rate_records` (`sessionId`)")
                }
            }

        val MIGRATION_8_9 =
            object : Migration(8, 9) {
                private val sql =
                    listOf(
                        "CREATE INDEX IF NOT EXISTS index_workout_records_startTime ON workout_records (startTime)",
                        "CREATE INDEX IF NOT EXISTS index_hrv_records_timestampMs ON hrv_records (timestampMs)",
                        "CREATE INDEX IF NOT EXISTS index_hrv_records_recordType_timestampMs ON hrv_records (recordType, timestampMs)",
                        "CREATE INDEX IF NOT EXISTS index_hrv_records_sessionId ON hrv_records (sessionId)",
                        "CREATE INDEX IF NOT EXISTS index_sleep_sessions_startTime ON sleep_sessions (startTime)",
                        "CREATE INDEX IF NOT EXISTS index_sleep_sessions_endTime ON sleep_sessions (endTime)",
                        "DROP INDEX IF EXISTS index_heart_rate_records_recordType",
                        "DROP INDEX IF EXISTS index_heart_rate_records_sessionId",
                        "CREATE INDEX IF NOT EXISTS index_heart_rate_records_recordType_timestampMs ON heart_rate_records (recordType, timestampMs)",
                        "CREATE INDEX IF NOT EXISTS index_heart_rate_records_sessionId_recordType_beatsPerMinute ON heart_rate_records (sessionId, recordType, beatsPerMinute)",
                    )

                override fun migrate(db: SupportSQLiteDatabase) {
                    sql.forEach { db.execSQL(it) }
                }

                override fun migrate(connection: SQLiteConnection) {
                    sql.forEach { connection.execSQL(it) }
                }
            }
    }
}
