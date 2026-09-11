package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.DirtyRangeEntity

@Dao
interface DirtyRangeDao {
    @Insert
    suspend fun insert(row: DirtyRangeEntity): Long

    @Query("SELECT * FROM dirty_ranges ORDER BY id LIMIT :limit")
    suspend fun pending(limit: Int): List<DirtyRangeEntity>

    @Query(
        "UPDATE dirty_ranges SET nextEpochDay = :nextDay " +
            "WHERE id = :id AND sourceGeneration = :generation AND nextEpochDay = :expectedDay",
    )
    suspend fun advance(
        id: Long,
        generation: Long,
        expectedDay: Long,
        nextDay: Long,
    ): Int

    @Query(
        "DELETE FROM dirty_ranges " +
            "WHERE id = :id AND sourceGeneration = :generation AND nextEpochDay > endEpochDayInclusive",
    )
    suspend fun deleteCompleted(
        id: Long,
        generation: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM dirty_ranges")
    suspend fun count(): Int

    @Query("DELETE FROM dirty_ranges")
    suspend fun deleteAll(): Int
}
