package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.ScanTypeStateEntity

/**
 * `scan_type_state` operations: whether the scan of a `(runId, chunkId, recordType)` is complete
 * (HC-002). Split out of [ScanStagingDao] -- which owns the `scan_seen_ids` rows themselves -- so
 * neither DAO crosses detekt's `TooManyFunctions` interface threshold as staging grows new
 * per-run/per-chunk clear operations.
 */
@Dao
interface ScanTypeStateDao {
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
        "DELETE FROM scan_type_state " +
            "WHERE runId = :runId AND chunkId = :chunkId AND recordType = :recordType",
    )
    suspend fun deleteStateForType(
        runId: String,
        chunkId: String,
        recordType: String,
    )

    @Query("DELETE FROM scan_type_state WHERE runId = :runId")
    suspend fun deleteStateForRun(runId: String)

    @Query("DELETE FROM scan_type_state WHERE runId <> :runId")
    suspend fun deleteStateForOtherRuns(runId: String)

    @Query(
        "DELETE FROM scan_type_state " +
            "WHERE runId = :runId AND chunkId NOT IN (:keepChunkIds)",
    )
    suspend fun deleteStateForOtherChunks(
        runId: String,
        keepChunkIds: List<String>,
    )

    companion object {
        const val STATE_SCANNING: String = "SCANNING"
        const val STATE_COMPLETE: String = "COMPLETE"
    }
}
