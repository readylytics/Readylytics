package app.readylytics.health.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.readylytics.health.data.local.entity.StepRecordEntity

@Dao
interface StepRecordDao {
    @Upsert
    suspend fun upsertAll(records: List<StepRecordEntity>)

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
}
