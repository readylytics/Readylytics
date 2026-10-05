package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity

@Dao
interface HeartRateReadDao : HeartRateObservationDao {
    @Query(
        "SELECT * FROM heart_rate_records WHERE timestampMs >= :startMs AND timestampMs <= :endMs " +
            "ORDER BY timestampMs ASC, sourceRecordRef ASC",
    )
    suspend fun getByTimeRange(
        startMs: Long,
        endMs: Long,
    ): List<HeartRateRecordEntity>

    @Query(
        "SELECT * FROM heart_rate_records " +
            "WHERE recordType = :recordType AND timestampMs >= :startMs AND timestampMs <= :endMs " +
            "ORDER BY timestampMs ASC, sourceRecordRef ASC",
    )
    suspend fun getByTypeAndTimeRange(
        recordType: String,
        startMs: Long,
        endMs: Long,
    ): List<HeartRateRecordEntity>
}
