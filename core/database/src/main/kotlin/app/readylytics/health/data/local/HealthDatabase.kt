package app.readylytics.health.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.readylytics.health.data.local.dao.AuditEventDao
import app.readylytics.health.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.data.local.dao.BodyFatRecordDao
import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.InsightDismissalDao
import app.readylytics.health.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.dao.SleepStageDao
import app.readylytics.health.data.local.dao.WeightRecordDao
import app.readylytics.health.data.local.dao.WorkoutDao
import app.readylytics.health.data.local.dao.WorkoutRoutePointDao
import app.readylytics.health.data.local.entity.AuditEventEntity
import app.readylytics.health.data.local.entity.BloodPressureRecordEntity
import app.readylytics.health.data.local.entity.BodyFatRecordEntity
import app.readylytics.health.data.local.entity.DailySummaryEntity
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.HrvRecordEntity
import app.readylytics.health.data.local.entity.InsightDismissalEntity
import app.readylytics.health.data.local.entity.OxygenSaturationRecordEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.data.local.entity.SleepStageEntity
import app.readylytics.health.data.local.entity.WeightRecordEntity
import app.readylytics.health.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.data.local.entity.WorkoutRoutePointEntity

@Database(
    entities = [
        SleepSessionEntity::class,
        SleepStageEntity::class,
        HeartRateRecordEntity::class,
        HrvRecordEntity::class,
        WorkoutRecordEntity::class,
        WorkoutRoutePointEntity::class,
        DailySummaryEntity::class,
        WeightRecordEntity::class,
        BodyFatRecordEntity::class,
        BloodPressureRecordEntity::class,
        OxygenSaturationRecordEntity::class,
        InsightDismissalEntity::class,
        AuditEventEntity::class,
    ],
    version = HealthDatabase.DATABASE_VERSION,
)
@TypeConverters(Converters::class)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun sleepSessionDao(): SleepSessionDao

    abstract fun sleepStageDao(): SleepStageDao

    abstract fun heartRateDao(): HeartRateDao

    abstract fun hrvDao(): HrvDao

    abstract fun workoutDao(): WorkoutDao

    abstract fun workoutRoutePointDao(): WorkoutRoutePointDao

    abstract fun dailySummaryDao(): DailySummaryDao

    abstract fun weightRecordDao(): WeightRecordDao

    abstract fun bodyFatRecordDao(): BodyFatRecordDao

    abstract fun bloodPressureRecordDao(): BloodPressureRecordDao

    abstract fun oxygenSaturationRecordDao(): OxygenSaturationRecordDao

    abstract fun insightDismissalDao(): InsightDismissalDao

    abstract fun auditEventDao(): AuditEventDao

    companion object {
        const val DATABASE_VERSION = 6
    }
}
