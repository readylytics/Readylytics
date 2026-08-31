package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.readylytics.health.core.databaseschema.data.local.entity.StepRecordEntity

@Dao
interface StepRecordDao {
    @Upsert
    suspend fun upsertAll(records: List<StepRecordEntity>)


    @Query(
        "SELECT * FROM step_records " +
            "WHERE startTime >= :fromMs AND (" +
            "  startTime > :afterTs OR " +
            "  (startTime = :afterTs AND id > :afterId)" +
            ") " +
            "ORDER BY startTime ASC, id ASC " +
            "LIMIT :limit",
    )
    suspend fun pageAfter(
        fromMs: Long,
        afterTs: Long,
        afterId: String,
        limit: Int,
    ): List<StepRecordEntity>

    @Query("SELECT * FROM step_records WHERE id = :id")
    suspend fun getById(id: String): StepRecordEntity?

    @Query("DELETE FROM step_records WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM step_records WHERE startTime < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

    @Query("SELECT COUNT(*) FROM step_records")
    suspend fun count(): Int

    @Query("DELETE FROM step_records")
    suspend fun deleteAll(): Int

    @Query("SELECT * FROM step_records WHERE startTime >= :startMs AND endTime <= :endMs ORDER BY startTime ASC")
    suspend fun getBetween(startMs: Long, endMs: Long): List<StepRecordEntity>

    @Query("DELETE FROM step_records WHERE startTime >= :startMs AND endTime <= :endMs AND id NOT IN (:validIds)")
    suspend fun deleteNotIn(startMs: Long, endMs: Long, validIds: List<String>): Int

    @Query("DELETE FROM step_records WHERE startTime >= :startMs AND endTime <= :endMs")
    suspend fun deleteBetween(startMs: Long, endMs: Long): Int
}
