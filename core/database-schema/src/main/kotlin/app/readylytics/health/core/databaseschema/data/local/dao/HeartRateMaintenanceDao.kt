package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import app.readylytics.health.core.model.domain.model.HrMinuteBucketRow
import app.readylytics.health.core.model.domain.model.HrRangeAggregate
import kotlinx.coroutines.flow.Flow

@Dao
interface HeartRateMaintenanceDao : HeartRateRollupDao {
    @Query(
        "SELECT minBpm, maxBpm, avgBpm, sampleCount FROM (" +
            "SELECT MIN(beatsPerMinute) AS minBpm, MAX(beatsPerMinute) AS maxBpm, " +
            "AVG(beatsPerMinute) AS avgBpm, COUNT(*) AS sampleCount " +
            "FROM heart_rate_records " +
            "WHERE timestampMs >= :startMs AND timestampMs < :endMs" +
            ") WHERE sampleCount > 0",
    )
    fun observeAggregateByTimeRange(
        startMs: Long,
        endMs: Long,
    ): Flow<HrRangeAggregate?>

    @Query(
        "SELECT (timestampMs - :dayStartMs) / 60000 AS bucketIndex, " +
            "AVG(beatsPerMinute) AS avgBpm, COUNT(*) AS sampleCount " +
            "FROM heart_rate_records " +
            "WHERE timestampMs >= :dayStartMs AND timestampMs < :dayEndMs " +
            "AND beatsPerMinute BETWEEN 30 AND 230 " +
            "GROUP BY bucketIndex " +
            "ORDER BY bucketIndex ASC",
    )
    suspend fun getMinuteBuckets(
        dayStartMs: Long,
        dayEndMs: Long,
    ): List<HrMinuteBucketRow>

    @Query("SELECT MIN(timestampMs) FROM heart_rate_records")
    suspend fun getEarliestTimestampMs(): Long?

    @Query("SELECT MIN(timestampMs) FROM heart_rate_records WHERE timestampMs < :beforeMs")
    suspend fun minTimestampBefore(beforeMs: Long): Long?

    @Query(
        "SELECT sourceRecordRef, MIN(timestampMs) AS minTimestampMs, MAX(timestampMs) AS maxTimestampMs " +
            "FROM heart_rate_records WHERE sourceRecordRef IN (:sourceRecordRefs) " +
            "GROUP BY sourceRecordRef",
    )
    suspend fun getChildBoundsForRefs(sourceRecordRefs: List<Long>): List<RefChildBounds>

    @Query(
        "SELECT sourceRecordRef, MIN(timestampMs) AS minTimestampMs, MAX(timestampMs) AS maxTimestampMs " +
            "FROM heart_rate_records WHERE sourceRecordRef = :sourceRecordRef " +
            "GROUP BY sourceRecordRef",
    )
    suspend fun getChildBoundsForRef(sourceRecordRef: Long): RefChildBounds?

    @Query(
        "SELECT timestampMs FROM heart_rate_records " +
            "WHERE sourceRecordRef = :sourceRecordRef AND timestampMs > :afterTimestampMs " +
            "ORDER BY timestampMs ASC LIMIT :limit",
    )
    suspend fun getTimestampsBySourceRecordRef(
        sourceRecordRef: Long,
        afterTimestampMs: Long,
        limit: Int,
    ): List<Long>

    @Query(
        "DELETE FROM heart_rate_records " +
            "WHERE sourceRecordRef = :sourceRecordRef AND timestampMs IN (:timestamps)",
    )
    suspend fun deleteBySourceRecordRefAndTimestamps(
        sourceRecordRef: Long,
        timestamps: List<Long>,
    ): Int
}
