package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity

private const val TYPED_HOT_FROM =
    " FROM heart_rate_records h INDEXED BY index_hr_v10_timestamp_source LEFT JOIN minute_coverage c " +
        "ON c.bucketStartMs = " +
        "((h.timestampMs / 60000) - (CASE WHEN h.timestampMs % 60000 < 0 THEN 1 ELSE 0 END)) * 60000 " +
        "WHERE h.recordType = :recordType AND h.timestampMs >= :startMs AND h.timestampMs <= :endMs " +
        "AND (c.bucketStartMs IS NULL OR c.tier = 'HOT' " +
        "OR (c.tier IN ('WARM', 'LEGACY_WARM') AND NOT EXISTS (" +
        "SELECT 1 FROM hr_minute_buckets b2 WHERE b2.bucketStartMs = c.bucketStartMs " +
        "AND b2.generation = c.visibleGeneration))) "

interface TypedHeartRateDao {
    @Query("SELECT COUNT(*)" + TYPED_HOT_FROM)
    suspend fun countVisibleOfType(recordType: String, startMs: Long, endMs: Long): Long

    @Query(
        "SELECT h.*" + TYPED_HOT_FROM +
            "AND (:afterTs IS NULL OR (h.timestampMs, h.sourceRecordRef) > (:afterTs, :afterRef)) " +
            "ORDER BY h.timestampMs, h.sourceRecordRef LIMIT :limit",
    )
    suspend fun visibleTypePage(
        recordType: String,
        startMs: Long,
        endMs: Long,
        afterTs: Long?,
        afterRef: Long?,
        limit: Int,
    ): List<HeartRateRecordEntity>
}
