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
    version = 2,
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
    }
}
