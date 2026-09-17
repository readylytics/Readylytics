package app.readylytics.health.core.database.data.local

import androidx.room.withTransaction
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.StagedHeartRateEntity
import app.readylytics.health.core.databaseschema.data.local.entity.StagedSourceMetadataEntity

class HeartRateRefreshStagingStore(
    private val database: HealthDatabase
) {
    suspend fun stage(
        runId: String,
        sourceId: String,
        metadata: StagedSourceMetadataEntity,
        samples: List<StagedHeartRateEntity>
    ) {
        database.withTransaction {
            // Delete old if exists for same run/source
            database.heartRateRefreshStagingDao().deleteMetadata(runId, sourceId)
            database.heartRateRefreshStagingDao().deleteSamples(runId, sourceId)

            // Insert metadata as incomplete first
            database.heartRateRefreshStagingDao().insertMetadata(metadata.copy(payloadComplete = false))
            
            // Insert all samples
            val chunked = samples.chunked(999)
            chunked.forEach { 
                database.heartRateRefreshStagingDao().insertSamples(it)
            }

            // Mark complete
            database.heartRateRefreshStagingDao().insertMetadata(metadata.copy(payloadComplete = true))
        }
    }

    suspend fun clearRun(runId: String) {
        database.withTransaction {
            database.heartRateRefreshStagingDao().clearRun(runId)
            database.heartRateRefreshStagingDao().clearRunSamples(runId)
        }
    }
}
