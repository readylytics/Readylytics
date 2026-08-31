package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.model.domain.model.HrMinuteBucketRow

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

    @Query("DELETE FROM hr_minute_buckets WHERE bucketEndMs < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

    @Query("SELECT COUNT(*) FROM hr_minute_buckets")
    suspend fun count(): Int


    @Query(
        "SELECT * FROM hr_minute_buckets WHERE (" +
            "  bucketStartMs > :afterTs OR " +
            "  (bucketStartMs = :afterTs AND recordType > :afterRecordType) OR " +
            "  (bucketStartMs = :afterTs AND recordType = :afterRecordType AND sessionId > :afterSessionId)" +
            ") " +
            "ORDER BY bucketStartMs ASC, recordType ASC, sessionId ASC " +
            "LIMIT :limit",
    )
    suspend fun pageAfter(
        afterTs: Long,
        afterRecordType: String,
        afterSessionId: String,
        limit: Int,
    ): List<HrMinuteBucketEntity>

    @Query("DELETE FROM hr_minute_buckets")
    suspend fun deleteAll(): Int

    // R2-CACHE-001: lets RetentionCleanup report the earliest warm-tier bucket start it is about
    // to delete (before deleting it), so callers can compute the ScoreInvalidation.AffectedRange
    // the deletion touched.
    @Query("SELECT MIN(bucketStartMs) FROM hr_minute_buckets WHERE bucketStartMs < :beforeMs")
    suspend fun minBucketStartBefore(beforeMs: Long): Long?
}
