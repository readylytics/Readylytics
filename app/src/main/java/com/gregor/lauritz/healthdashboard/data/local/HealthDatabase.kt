package com.gregor.lauritz.healthdashboard.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepStageDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.HrvRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepStageEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity

@Database(
    entities = [
        SleepSessionEntity::class,
        SleepStageEntity::class,
        HeartRateRecordEntity::class,
        HrvRecordEntity::class,
        WorkoutRecordEntity::class,
        DailySummaryEntity::class,
    ],
    version = HealthDatabase.DATABASE_VERSION,
)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun sleepSessionDao(): SleepSessionDao

    abstract fun sleepStageDao(): SleepStageDao

    abstract fun heartRateDao(): HeartRateDao

    abstract fun hrvDao(): HrvDao

    abstract fun workoutDao(): WorkoutDao

    abstract fun dailySummaryDao(): DailySummaryDao

    companion object {
        const val DATABASE_VERSION = 19

        fun create(context: Context): HealthDatabase =
            Room
                .databaseBuilder(context, HealthDatabase::class.java, "health_db")
                .addMigrations(*DatabaseMigrations.all)
                .build()
    }
}
