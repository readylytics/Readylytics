package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity

@Dao
interface HeartRateRawReadDao {
    @Query(
        "SELECT * FROM heart_rate_records " +
            "WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC, sourceRecordRef ASC",
    )
    suspend fun getSince(fromMs: Long): List<HeartRateRecordEntity>

    @Query(
        "SELECT * FROM heart_rate_records " +
            "WHERE timestampMs >= :fromMs AND (" +
            "  timestampMs > :afterTs OR " +
            "  (timestampMs = :afterTs AND sourceRecordRef > :afterRef)" +
            ") " +
            "ORDER BY timestampMs ASC, sourceRecordRef ASC " +
            "LIMIT :limit",
    )
    suspend fun pageAfter(
        fromMs: Long,
        afterTs: Long,
        afterRef: Long,
        limit: Int,
    ): List<HeartRateRecordEntity>

    @Query(
        "SELECT * FROM heart_rate_records " +
            "WHERE timestampMs >= :startMs AND timestampMs <= :endMs " +
            "AND (timestampMs > :lastTimestampMs OR " +
            "(timestampMs = :lastTimestampMs AND sourceRecordRef > :lastSourceRecordRef)) " +
            "ORDER BY timestampMs ASC, sourceRecordRef ASC LIMIT :limit",
    )
    suspend fun getKeysetPage(
        startMs: Long,
        endMs: Long,
        lastTimestampMs: Long,
        lastSourceRecordRef: Long,
        limit: Int,
    ): List<HeartRateRecordEntity>

    @Query("SELECT * FROM heart_rate_records WHERE sourceRecordRef = :sourceRecordRef")
    suspend fun getByRef(sourceRecordRef: Long): HeartRateRecordEntity?

    @Query(
        "SELECT * FROM heart_rate_records " +
            "WHERE sourceRecordRef = :sourceRecordRef " +
            "ORDER BY timestampMs ASC, sourceRecordRef ASC",
    )
    suspend fun getBySourceRecordRef(sourceRecordRef: Long): List<HeartRateRecordEntity>

    @Query(
        "DELETE FROM heart_rate_records WHERE sourceRecordRef = :sourceRecordRef",
    )
    suspend fun deleteBySourceRecordRef(sourceRecordRef: Long): Int
}
