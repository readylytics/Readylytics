package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.ScanSeenIdEntity

/** Min/max bounds of the rows an anti-join delete is about to remove, read before deleting them. */
data class StagedDeletionBounds(
    val minMs: Long?,
    val maxMs: Long?,
)

/**
 * `scan_seen_ids` operations. Scan completeness state lives on the separate [ScanTypeStateDao] --
 * split out so neither DAO crosses detekt's `TooManyFunctions` interface threshold as staging grows
 * new per-run/per-chunk clear operations.
 */
@Dao
interface ScanStagingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeenIds(rows: List<ScanSeenIdEntity>)

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

    @Query("DELETE FROM scan_seen_ids WHERE runId = :runId")
    suspend fun deleteSeenForRun(runId: String)

    @Query("DELETE FROM scan_seen_ids WHERE runId <> :runId")
    suspend fun deleteSeenForOtherRuns(runId: String)

    @Query(
        "DELETE FROM scan_seen_ids " +
            "WHERE runId = :runId AND chunkId NOT IN (:keepChunkIds)",
    )
    suspend fun deleteSeenForOtherChunks(
        runId: String,
        keepChunkIds: List<String>,
    )
}
