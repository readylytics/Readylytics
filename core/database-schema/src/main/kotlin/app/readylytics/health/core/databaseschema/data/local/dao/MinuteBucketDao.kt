package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.model.domain.model.HrMinuteBucketRow

// R2-UI-002: TooManyFunctions crossed 10->11 with getBucketsInTimeRange below. Room requires every
// query to be an abstract member of the @Dao interface (no top-level/extension-function escape
// hatch the way a plain Kotlin class allows), and splitting this DAO into two @Dao interfaces to
// stay under the threshold is a cross-cutting change (HealthDatabase wiring, every existing
// consumer/test of MinuteBucketDao) out of scope for this task. No structural fix is viable here.
@Suppress("TooManyFunctions")
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
            "  (bucketStartMs = :afterTs AND recordType = :afterRecordType AND sessionId > :afterSessionId) OR " +
            "  (bucketStartMs = :afterTs AND recordType = :afterRecordType AND sessionId = :afterSessionId AND " +
            "   deviceName > :afterDeviceName)" +
            ") " +
            "ORDER BY bucketStartMs ASC, recordType ASC, sessionId ASC, deviceName ASC " +
            "LIMIT :limit",
    )
    suspend fun pageAfter(
        afterTs: Long,
        afterRecordType: String,
        afterSessionId: String,
        afterDeviceName: String,
        limit: Int,
    ): List<HrMinuteBucketEntity>

    @Query("DELETE FROM hr_minute_buckets")
    suspend fun deleteAll(): Int

    // R2-CACHE-001: lets RetentionCleanup report the earliest warm-tier bucket start it is about
    // to delete (before deleting it), so callers can compute the ScoreInvalidation.AffectedRange
    // the deletion touched.
    @Query("SELECT MIN(bucketStartMs) FROM hr_minute_buckets WHERE bucketStartMs < :beforeMs")
    suspend fun minBucketStartBefore(beforeMs: Long): Long?

    @Query(
        "DELETE FROM hr_minute_buckets " +
            "WHERE bucketStartMs >= :fromMs AND bucketEndMs <= :toMs " +
            "AND (deviceName != :deviceName OR deviceName = '')",
    )
    suspend fun deleteBucketsNotMatchingDevice(fromMs: Long, toMs: Long, deviceName: String): Int

    @Query("SELECT DISTINCT deviceName FROM hr_minute_buckets WHERE deviceName != ''")
    suspend fun getDistinctDeviceNames(): List<String>

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
