package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import app.readylytics.health.core.databaseschema.data.local.entity.DirtyRangeEntity

@Dao
interface DirtyRangeDao : DirtyRangeRetentionQueries {
    @Insert
    suspend fun insert(row: DirtyRangeEntity): Long

    @Query("SELECT * FROM dirty_ranges ORDER BY id LIMIT :limit")
    suspend fun pending(limit: Int): List<DirtyRangeEntity>

    @Query(
        "SELECT * FROM dirty_ranges WHERE nextEpochDay = :epochDay AND endEpochDayInclusive >= :epochDay ORDER BY id",
    )
    suspend fun pendingForDay(epochDay: Long): List<DirtyRangeEntity>

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

    @Transaction
    suspend fun discardBefore(cutoffDay: Long) {
        deleteExpired(cutoffDay)
        trimExpiredPrefixes(cutoffDay)
    }

    @Query("SELECT COUNT(*) FROM dirty_ranges")
    suspend fun count(): Int

    @Query(
        "UPDATE dirty_ranges SET scoringSnapshotId = :newSnapshotId " +
            "WHERE id = :id AND sourceGeneration = :generation",
    )
    suspend fun updateSnapshotId(
        id: Long,
        generation: Long,
        newSnapshotId: String,
    ): Int

    @Query("DELETE FROM dirty_ranges")
    suspend fun deleteAll(): Int
}

/** Retention removes expired work without acknowledging any retained day. */
interface DirtyRangeRetentionQueries {
    @Query("DELETE FROM dirty_ranges WHERE endEpochDayInclusive < :cutoffDay")
    suspend fun deleteExpired(cutoffDay: Long)

    @Query("UPDATE dirty_ranges SET nextEpochDay = :cutoffDay WHERE nextEpochDay < :cutoffDay")
    suspend fun trimExpiredPrefixes(cutoffDay: Long)
}
