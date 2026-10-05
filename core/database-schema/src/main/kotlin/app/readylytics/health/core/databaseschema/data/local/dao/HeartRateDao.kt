package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao

@Dao
interface HeartRateDao :
    HeartRateReadDao,
    VisibleHeartRateDao,
    HeartRateMaintenanceDao,
    TypedHeartRateDao,
    HeartRateWriteDao,
    HeartRateSleepDao,
    HeartRateAdminDao,
    HeartRateRawReadDao
