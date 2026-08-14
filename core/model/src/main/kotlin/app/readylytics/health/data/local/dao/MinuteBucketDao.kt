package app.readylytics.health.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.readylytics.health.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.domain.model.HrMinuteBucketRow

@Dao
interface MinuteBucketDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBuckets(buckets: List<HrMinuteBucketEntity>)

    // Weighted average across all (recordType, sessionId) slices of a minute is exactly the plain
    // AVG over the minute's raw samples, so the everyday-HR load calculator sees the same value
    // whether it reads the hot tier (HeartRateDao.getMinuteBuckets) or the warm tier.
    @Query(
        "SELECT (bucketStartMs - :dayStartMs) / 60000 AS bucketIndex, " +
            "SUM(avgBpm * sampleCount) / SUM(sampleCount) AS avgBpm, " +
            "SUM(sampleCount) AS sampleCount " +
            "FROM hr_minute_buckets " +
            "WHERE bucketStartMs >= :dayStartMs AND bucketEndMs <= :dayEndMs " +
            "AND avgBpm BETWEEN 30 AND 230 " +
            "GROUP BY bucketIndex " +
            "ORDER BY bucketIndex ASC",
    )
    suspend fun getMinuteBuckets(
        dayStartMs: Long,
        dayEndMs: Long,
    ): List<HrMinuteBucketRow>

    @Query(
        "SELECT * FROM hr_minute_buckets " +
            "WHERE recordType = :recordType AND sessionId = :sessionId " +
            "ORDER BY bucketStartMs ASC",
    )
    suspend fun getBucketsForSession(
        recordType: String,
        sessionId: String,
    ): List<HrMinuteBucketEntity>

    @Query(
        "SELECT * FROM hr_minute_buckets " +
            "WHERE recordType = :recordType AND sessionId = :sessionId " +
            "AND bucketEndMs > :startMs AND bucketStartMs < :endMs " +
            "ORDER BY bucketStartMs ASC",
    )
    suspend fun getBucketsForSessionInRange(
        recordType: String,
        sessionId: String,
        startMs: Long,
        endMs: Long,
    ): List<HrMinuteBucketEntity>

    @Query("DELETE FROM hr_minute_buckets WHERE bucketEndMs < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

    // Hot→warm rollup: downsample every raw 1s heart-rate sample older than the cutoff into a
    // 1-minute bucket per (recordType, sessionId), then delete the raw rows. INSERT OR REPLACE makes
    // a re-run idempotent (a re-ingested then re-rolled minute overwrites rather than double-counts).
    @Query(
        "INSERT OR REPLACE INTO hr_minute_buckets " +
            "(bucketStartMs, bucketEndMs, minBpm, maxBpm, avgBpm, sampleCount, recordType, sessionId, deviceName) " +
            "SELECT (timestampMs / 60000) * 60000, (timestampMs / 60000) * 60000 + 60000, " +
            "MIN(beatsPerMinute), MAX(beatsPerMinute), AVG(beatsPerMinute), COUNT(*), " +
            "recordType, COALESCE(sessionId, ''), NULL " +
            "FROM heart_rate_records " +
            "WHERE timestampMs < :beforeMs AND beatsPerMinute BETWEEN 30 AND 230 " +
            "GROUP BY (timestampMs / 60000) * 60000, recordType, COALESCE(sessionId, '')",
    )
    suspend fun rollupIntoBucketsBefore(beforeMs: Long)

    @Query("SELECT COUNT(*) FROM hr_minute_buckets")
    suspend fun count(): Int

    @Query("SELECT * FROM hr_minute_buckets ORDER BY bucketStartMs ASC LIMIT :limit OFFSET :offset")
    suspend fun getPaged(
        limit: Int,
        offset: Int,
    ): List<HrMinuteBucketEntity>

    @Query("DELETE FROM hr_minute_buckets")
    suspend fun deleteAll(): Int
}
