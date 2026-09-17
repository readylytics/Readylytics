package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.StagedHeartRateEntity
import app.readylytics.health.core.databaseschema.data.local.entity.StagedSourceMetadataEntity

/**
 * WP-17 Step 4 staging tables. Staged rows are local operational state for an in-flight authorized
 * refresh: they are separate from the live raw tables, carry a textual `sourceId` (no FK into
 * `health_source_records` -- staging must never mutate live authoritative metadata just to obtain
 * one), and are excluded from exported health data.
 */
@Dao
interface HeartRateRefreshStagingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMetadata(metadata: StagedSourceMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSamples(samples: List<StagedHeartRateEntity>)

    @Query("SELECT * FROM staged_hr_sources WHERE runId = :runId AND sourceId = :sourceId")
    suspend fun getMetadata(
        runId: String,
        sourceId: String,
    ): StagedSourceMetadataEntity?

    @Query(
        "SELECT * FROM staged_hr_samples WHERE runId = :runId AND sourceId = :sourceId " +
            "ORDER BY timestampMs ASC",
    )
    suspend fun getSamples(
        runId: String,
        sourceId: String,
    ): List<StagedHeartRateEntity>

    @Query("DELETE FROM staged_hr_sources WHERE runId = :runId AND sourceId = :sourceId")
    suspend fun deleteMetadata(
        runId: String,
        sourceId: String,
    )

    @Query("DELETE FROM staged_hr_samples WHERE runId = :runId AND sourceId = :sourceId")
    suspend fun deleteSamples(
        runId: String,
        sourceId: String,
    )

    @Query("DELETE FROM staged_hr_sources WHERE runId = :runId")
    suspend fun clearRun(runId: String)

    @Query("DELETE FROM staged_hr_samples WHERE runId = :runId")
    suspend fun clearRunSamples(runId: String)
}
