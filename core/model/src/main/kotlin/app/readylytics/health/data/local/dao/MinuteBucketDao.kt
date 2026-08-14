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

    @Query(
        "SELECT (bucketStartMs - :dayStartMs) / 60000 AS bucketIndex, " +
            "avgBpm AS avgBpm, sampleCount AS sampleCount " +
            "FROM hr_minute_buckets " +
            "WHERE bucketStartMs >= :dayStartMs AND bucketEndMs <= :dayEndMs " +
            "AND avgBpm BETWEEN 30 AND 230 " +
            "ORDER BY bucketStartMs ASC",
    )
    suspend fun getMinuteBuckets(
        dayStartMs: Long,
        dayEndMs: Long,
    ): List<HrMinuteBucketRow>

    @Query("DELETE FROM hr_minute_buckets WHERE bucketEndMs < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

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
