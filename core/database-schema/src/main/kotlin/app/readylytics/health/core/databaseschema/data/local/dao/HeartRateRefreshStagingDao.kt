package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.StagedHeartRateEntity
import app.readylytics.health.core.databaseschema.data.local.entity.StagedSourceMetadataEntity

@Dao
abstract class HeartRateRefreshStagingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertMetadata(metadata: StagedSourceMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertSamples(samples: List<StagedHeartRateEntity>)

    @Query("DELETE FROM staged_hr_sources WHERE runId = :runId AND sourceId = :sourceId")
    abstract fun deleteMetadata(runId: String, sourceId: String)

    @Query("DELETE FROM staged_hr_samples WHERE runId = :runId AND sourceId = :sourceId")
    abstract fun deleteSamples(runId: String, sourceId: String)

    @Query("DELETE FROM staged_hr_sources WHERE runId = :runId")
    abstract fun clearRun(runId: String)

    @Query("DELETE FROM staged_hr_samples WHERE runId = :runId")
    abstract fun clearRunSamples(runId: String)
}
