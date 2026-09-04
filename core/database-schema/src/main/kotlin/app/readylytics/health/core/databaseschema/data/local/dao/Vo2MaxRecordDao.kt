package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.readylytics.health.core.databaseschema.data.local.entity.Vo2MaxRecordEntity

@Dao
interface Vo2MaxRecordDao {
    @Upsert
    suspend fun upsertAll(records: List<Vo2MaxRecordEntity>)

    @Query(
        "SELECT * FROM vo2_max_records WHERE timestampMs >= :startMs AND timestampMs < :endMs " +
            "ORDER BY timestampMs DESC",
    )
    suspend fun getByTimeRange(
        startMs: Long,
        endMs: Long,
    ): List<Vo2MaxRecordEntity>

    @Query(
        "SELECT * FROM vo2_max_records WHERE timestampMs <= :maxTimestampMs " +
            "ORDER BY timestampMs DESC LIMIT 1",
    )
    suspend fun getLatestUpTo(maxTimestampMs: Long): Vo2MaxRecordEntity?

    @Query(
        "SELECT * FROM vo2_max_records WHERE timestampMs >= :minTimestampMs AND timestampMs <= :maxTimestampMs " +
            "ORDER BY timestampMs DESC LIMIT 1",
    )
    suspend fun getLatestInWindow(minTimestampMs: Long, maxTimestampMs: Long): Vo2MaxRecordEntity?

    @Query("DELETE FROM vo2_max_records WHERE timestampMs < :cutoffMs")
    suspend fun deleteBefore(cutoffMs: Long): Int

    @Query(
        "SELECT * FROM vo2_max_records " +
            "WHERE timestampMs >= :fromMs AND (" +
            "  timestampMs > :afterTs OR " +
            "  (timestampMs = :afterTs AND id > :afterId)" +
            ") " +
            "ORDER BY timestampMs ASC, id ASC " +
            "LIMIT :limit",
    )
    suspend fun pageAfter(
        fromMs: Long,
        afterTs: Long,
        afterId: String,
        limit: Int,
    ): List<Vo2MaxRecordEntity>

    @Query("SELECT COUNT(*) FROM vo2_max_records")
    suspend fun count(): Int

    @Query("DELETE FROM vo2_max_records")
    suspend fun deleteAll(): Int
}
