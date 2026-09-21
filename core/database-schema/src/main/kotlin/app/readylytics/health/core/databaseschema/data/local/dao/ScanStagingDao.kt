package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.ScanSeenIdEntity
import app.readylytics.health.core.databaseschema.data.local.entity.ScanTypeStateEntity

/** Min/max bounds of the rows an anti-join delete is about to remove, read before deleting them. */
data class StagedDeletionBounds(
    val minMs: Long?,
    val maxMs: Long?,
)

@Dao
interface ScanStagingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeenIds(rows: List<ScanSeenIdEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: ScanTypeStateEntity)

    @Query(
        "SELECT * FROM scan_type_state " +
            "WHERE runId = :runId AND chunkId = :chunkId AND recordType = :recordType",
    )
    suspend fun getState(
        runId: String,
        chunkId: String,
        recordType: String,
    ): ScanTypeStateEntity?

    @Query(
        "SELECT COUNT(*) FROM scan_seen_ids " +
            "WHERE runId = :runId AND chunkId = :chunkId AND recordType = :recordType",
    )
    suspend fun countSeen(
        runId: String,
        chunkId: String,
        recordType: String,
    ): Int

    @Query(
        "DELETE FROM scan_seen_ids " +
            "WHERE runId = :runId AND chunkId = :chunkId AND recordType = :recordType",
    )
    suspend fun deleteSeenForType(
        runId: String,
        chunkId: String,
        recordType: String,
    )

    @Query(
        "DELETE FROM scan_type_state " +
            "WHERE runId = :runId AND chunkId = :chunkId AND recordType = :recordType",
    )
    suspend fun deleteStateForType(
        runId: String,
        chunkId: String,
        recordType: String,
    )

    @Query("DELETE FROM scan_seen_ids WHERE runId = :runId")
    suspend fun deleteSeenForRun(runId: String)

    @Query("DELETE FROM scan_type_state WHERE runId = :runId")
    suspend fun deleteStateForRun(runId: String)

    @Query("DELETE FROM scan_seen_ids WHERE runId <> :runId")
    suspend fun deleteSeenForOtherRuns(runId: String)

    @Query("DELETE FROM scan_type_state WHERE runId <> :runId")
    suspend fun deleteStateForOtherRuns(runId: String)

    companion object {
        const val STATE_SCANNING: String = "SCANNING"
        const val STATE_COMPLETE: String = "COMPLETE"
    }
}
