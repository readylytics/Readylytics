package com.gregor.lauritz.healthdashboard.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.gregor.lauritz.healthdashboard.data.local.dao.BloodPressureRecordDao
import com.gregor.lauritz.healthdashboard.data.local.dao.BodyFatRecordDao
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.OxygenSaturationRecordDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepStageDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WeightRecordDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.local.entity.BloodPressureRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.BodyFatRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.HrvRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.OxygenSaturationRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepStageEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.WeightRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity

@Database(
    entities = [
        SleepSessionEntity::class,
        SleepStageEntity::class,
        HeartRateRecordEntity::class,
        HrvRecordEntity::class,
        WorkoutRecordEntity::class,
        DailySummaryEntity::class,
        WeightRecordEntity::class,
        BodyFatRecordEntity::class,
        BloodPressureRecordEntity::class,
        OxygenSaturationRecordEntity::class,
    ],
    version = HealthDatabase.DATABASE_VERSION,
    autoMigrations = [
        androidx.room.AutoMigration(from = 26, to = 27),
    ],
)
@TypeConverters(Converters::class)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun sleepSessionDao(): SleepSessionDao

    abstract fun sleepStageDao(): SleepStageDao

    abstract fun heartRateDao(): HeartRateDao

    abstract fun hrvDao(): HrvDao

    abstract fun workoutDao(): WorkoutDao

    abstract fun dailySummaryDao(): DailySummaryDao

    abstract fun weightRecordDao(): WeightRecordDao

    abstract fun bodyFatRecordDao(): BodyFatRecordDao

    abstract fun bloodPressureRecordDao(): BloodPressureRecordDao

    abstract fun oxygenSaturationRecordDao(): OxygenSaturationRecordDao

    companion object {
        const val DATABASE_VERSION = 27

        fun create(context: Context): HealthDatabase =
            Room
                .databaseBuilder(context, HealthDatabase::class.java, "health_db")
                .addMigrations(*DatabaseMigrations.all)
                .build()
    }
}
