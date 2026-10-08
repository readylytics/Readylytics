package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity

@Dao
interface HeartRateRollupDao {
    @Query(
        "SELECT * FROM heart_rate_records " +
            "WHERE timestampMs >= :fromMs AND timestampMs < :toMs AND beatsPerMinute BETWEEN 30 AND 230 " +
            "ORDER BY recordType ASC, sessionId ASC, timestampMs ASC",
    )
    suspend fun getPlausibleSamplesInRangeForRollup(
        fromMs: Long,
        toMs: Long,
    ): List<HeartRateRecordEntity>

    @Query(
        "SELECT * FROM heart_rate_records " +
            "WHERE timestampMs >= :fromMs AND timestampMs < :toMs " +
            "AND beatsPerMinute BETWEEN 30 AND 230 " +
            "AND (timestampMs > :afterTs OR (timestampMs = :afterTs AND sourceRecordRef > :afterRef)) " +
            "ORDER BY timestampMs ASC, sourceRecordRef ASC " +
            "LIMIT :limit",
    )
    suspend fun pagePlausibleSamplesForRollup(
        fromMs: Long,
        toMs: Long,
        afterTs: Long,
        afterRef: Long,
        limit: Int,
    ): List<HeartRateRecordEntity>

    @Query(
        "DELETE FROM heart_rate_records " +
            "WHERE timestampMs >= :fromMs AND timestampMs < :toMs " +
            "AND (timestampMs / 60000) * 60000 NOT IN (" +
            "  SELECT bucketStartMs FROM minute_coverage " +
            "  WHERE bucketStartMs >= :fromMs AND bucketStartMs < :toMs " +
            "  AND quality = 'LEGACY_UNKNOWN')",
    )
    suspend fun deleteConsumedSamplesInRange(
        fromMs: Long,
        toMs: Long,
    )
}
