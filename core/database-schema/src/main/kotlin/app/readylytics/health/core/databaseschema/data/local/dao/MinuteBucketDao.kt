package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.model.domain.model.HrMinuteBucketRow

// R2-UI-002: retention/pruning/backup-bookkeeping queries live in the sibling
// MinuteBucketMaintenanceDao (same table, same package) -- split out so this interface stays the
// "core" warm-tier read/write surface scoring and UI reconstruction actually depend on, and so
// neither interface trips detekt's TooManyFunctions threshold.
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

    // R2-UI-002: warm-tier equivalent of HeartRateDao.getByTimeRange -- unlike getBucketsForSession
    // (recordType + sessionId keyed), this is a plain overlap query across every bucket type, for
    // callers (HeartRateRepository) that need whatever warm-tier data exists in a time window
    // regardless of which session/record type it came from.
    @Query(
        "SELECT * FROM hr_minute_buckets WHERE bucketStartMs <= :endMs AND bucketEndMs >= :startMs " +
            "ORDER BY bucketStartMs ASC",
    )
    suspend fun getBucketsInTimeRange(startMs: Long, endMs: Long): List<HrMinuteBucketEntity>
}
