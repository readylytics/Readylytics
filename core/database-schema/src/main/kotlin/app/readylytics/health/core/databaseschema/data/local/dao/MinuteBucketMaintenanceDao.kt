package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity

/**
 * Retention/pruning/backup-bookkeeping half of the warm-tier `hr_minute_buckets` table, split out
 * of [MinuteBucketDao] (R2-UI-002) purely to stay under detekt's `TooManyFunctions` threshold once
 * [MinuteBucketDao.getBucketsInTimeRange] was added. Nothing scoring- or UI-facing calls anything
 * here -- consumers are `RetentionCleanup`, `SelectedSourcePrunerImpl`, `HealthDeviceRepository`,
 * and the local backup/restore path (`BackupStreamWriter`/`LocalRestoreManager`). Both DAOs are
 * exposed via `HealthDatabase`'s two abstract accessor methods and back the same table -- Room
 * supports multiple `@Dao` interfaces over one entity/table with no extra `@Database` config.
 */
@Dao
interface MinuteBucketMaintenanceDao {
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
}
