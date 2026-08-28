package app.readylytics.health.core.database.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.readylytics.health.core.database.data.local.dao.AuditEventDao
import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyTemperatureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.dao.InsightDismissalDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepStageDao
import app.readylytics.health.core.databaseschema.data.local.dao.SourceRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.StepRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutRoutePointDao
import app.readylytics.health.core.database.data.local.entity.AuditEventEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BloodPressureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BodyFatRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BodyTemperatureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.databaseschema.data.local.entity.InsightDismissalEntity
import app.readylytics.health.core.databaseschema.data.local.entity.OxygenSaturationRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepStageEntity
import app.readylytics.health.core.databaseschema.data.local.entity.StepRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WeightRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRoutePointEntity

@Database(
    entities = [
        SleepSessionEntity::class,
        SleepStageEntity::class,
        HeartRateRecordEntity::class,
        HrvRecordEntity::class,
        HealthSourceRecordEntity::class,
        WorkoutRecordEntity::class,
        DailySummaryEntity::class,
        WeightRecordEntity::class,
        BodyFatRecordEntity::class,
        BloodPressureRecordEntity::class,
        OxygenSaturationRecordEntity::class,
        BodyTemperatureRecordEntity::class,
        InsightDismissalEntity::class,
        AuditEventEntity::class,
        StepRecordEntity::class,
        HrMinuteBucketEntity::class,
        WorkoutRoutePointEntity::class,
    ],
    version = HealthDatabase.DATABASE_VERSION,
)
@TypeConverters(Converters::class)
@Suppress("TooManyFunctions") // Room requires every DAO accessor on the single @Database class for
// this physical .db file; there is no supported way to split them across multiple database
// classes, so this count cannot be reduced without removing a working DAO.
abstract class HealthDatabase : RoomDatabase() {
    abstract fun sleepSessionDao(): SleepSessionDao

    abstract fun sleepStageDao(): SleepStageDao

    abstract fun heartRateDao(): HeartRateDao

    abstract fun hrvDao(): HrvDao

    abstract fun sourceRecordDao(): SourceRecordDao

    abstract fun workoutDao(): WorkoutDao

    abstract fun dailySummaryDao(): DailySummaryDao

    abstract fun weightRecordDao(): WeightRecordDao

    abstract fun bodyFatRecordDao(): BodyFatRecordDao

    abstract fun bloodPressureRecordDao(): BloodPressureRecordDao

    abstract fun oxygenSaturationRecordDao(): OxygenSaturationRecordDao

    abstract fun bodyTemperatureRecordDao(): BodyTemperatureRecordDao

    abstract fun insightDismissalDao(): InsightDismissalDao

    abstract fun auditEventDao(): AuditEventDao

    abstract fun stepRecordDao(): StepRecordDao

    abstract fun minuteBucketDao(): MinuteBucketDao

    abstract fun workoutRoutePointDao(): WorkoutRoutePointDao

    companion object {
        const val DATABASE_VERSION = 13
    }
}
