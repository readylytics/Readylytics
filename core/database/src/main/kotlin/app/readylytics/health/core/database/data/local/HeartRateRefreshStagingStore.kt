package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateRefreshStagingDao
import app.readylytics.health.core.databaseschema.data.local.entity.StagedHeartRateEntity
import app.readylytics.health.core.databaseschema.data.local.entity.StagedSourceMetadataEntity
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WP-17 Step 4 staging store: persists an incoming complete source payload under
 * `(runId, sourceId)` in the staged child tables, separate from the live raw tables. Staged work
 * is durable but grants no prune or visibility authority by itself -- publication
 * ([MinuteCoveragePublisher]) is what switches coverage.
 *
 * The staged source key stays textual: staging must never allocate or mutate live authoritative
 * `health_source_records` metadata just to obtain an FK.
 */
@Singleton
class HeartRateRefreshStagingStore
    @Inject
    constructor(
        private val stagingDao: HeartRateRefreshStagingDao,
        private val transactionRunner: TransactionRunner,
    ) {
        /**
         * Replaces every staged row of one parent in a single transaction. The metadata row is
         * first written with `payloadComplete = false` and only rewritten with the caller's own
         * [StagedSourceMetadataEntity.payloadComplete] after all children have been inserted, so a
         * crash mid-insert can never leave a partially staged payload marked complete -- and an
         * incomplete payload the caller already knows to be partial stays marked incomplete.
         *
         * An empty complete parent keeps its metadata row.
         */
        suspend fun stage(
            runId: String,
            sourceId: String,
            metadata: StagedSourceMetadataEntity,
            samples: List<StagedHeartRateEntity>,
        ) {
            transactionRunner.runInTransaction {
                stagingDao.deleteMetadata(runId, sourceId)
                stagingDao.deleteSamples(runId, sourceId)
                stagingDao.insertMetadata(metadata.copy(payloadComplete = false))
                if (samples.isNotEmpty()) {
                    stagingDao.insertSamples(samples)
                }
                stagingDao.insertMetadata(metadata)
            }
        }

        suspend fun stagedMetadata(
            runId: String,
            sourceId: String,
        ): StagedSourceMetadataEntity? = stagingDao.getMetadata(runId, sourceId)

        suspend fun stagedSamples(
            runId: String,
            sourceId: String,
        ): List<StagedHeartRateEntity> = stagingDao.getSamples(runId, sourceId)

        suspend fun clearRun(runId: String) {
            transactionRunner.runInTransaction {
                stagingDao.clearRun(runId)
                stagingDao.clearRunSamples(runId)
            }
        }
    }
