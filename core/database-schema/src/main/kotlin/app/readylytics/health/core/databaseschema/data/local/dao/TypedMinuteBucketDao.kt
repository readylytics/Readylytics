package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity

private const val TYPED_WARM_FROM =
    " FROM hr_minute_buckets b LEFT JOIN minute_coverage c ON c.bucketStartMs = b.bucketStartMs " +
        "WHERE b.recordType = :recordType AND b.bucketStartMs <= :endMs AND b.bucketEndMs >= :startMs " +
        "AND ((c.tier IN ('WARM', 'LEGACY_WARM') AND c.visibleGeneration = b.generation) " +
        "OR (c.bucketStartMs IS NULL AND NOT EXISTS (" +
        "SELECT 1 FROM heart_rate_records h WHERE h.timestampMs >= b.bucketStartMs " +
        "AND h.timestampMs < b.bucketStartMs + 60000))) "

interface TypedMinuteBucketDao {
    @Query("SELECT COALESCE(SUM(b.sampleCount), 0)" + TYPED_WARM_FROM)
    suspend fun countVisibleOfType(recordType: String, startMs: Long, endMs: Long): Long

    @Query("SELECT b.*" + TYPED_WARM_FROM + "ORDER BY b.bucketStartMs, b.recordType, b.sessionId, b.deviceName")
    suspend fun visibleOfType(recordType: String, startMs: Long, endMs: Long): List<HrMinuteBucketEntity>

    @Query(
        "SELECT b.*" + TYPED_WARM_FROM +
            "AND (:afterStart IS NULL OR (b.bucketStartMs, b.recordType, b.sessionId, b.deviceName) > " +
            "(:afterStart, :recordType, :afterSession, :afterDevice)) " +
            "ORDER BY b.bucketStartMs, b.recordType, b.sessionId, b.deviceName LIMIT :limit",
    )
    suspend fun visibleTypePage(
        recordType: String,
        startMs: Long,
        endMs: Long,
        afterStart: Long?,
        afterSession: String?,
        afterDevice: String?,
        limit: Int,
    ): List<HrMinuteBucketEntity>
}
