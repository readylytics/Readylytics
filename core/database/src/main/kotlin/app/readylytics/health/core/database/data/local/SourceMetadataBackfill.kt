package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.dao.SourceRecordDao
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceMetadataBackfill
    @Inject
    constructor(
        private val sourceRecordDao: SourceRecordDao,
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val healthMutationStateDao: HealthMutationStateDao,
        private val transactionRunner: TransactionRunner,
    ) {
        data class BackfillResult(
            val batchesProcessed: Int,
            val sourcesExamined: Int,
            val sourcesUpdated: Int,
            val isComplete: Boolean,
        )

        private data class SourceBoundsUpdate(
            val id: Long,
            val startMs: Long,
            val endExclusiveMs: Long,
        )

        suspend fun backfill(
            batchSize: Int = DEFAULT_BATCH_SIZE,
            maxBatches: Int = Int.MAX_VALUE,
        ): BackfillResult {
            var batches = 0
            var totalExamined = 0
            var totalUpdated = 0
            var complete = false

            while (batches < maxBatches && !complete) {
                currentCoroutineContext().ensureActive()
                val state = healthMutationStateDao.getOrCreate()
                val sources = sourceRecordDao.pageAfter(state.backfillAfterSourceRef, batchSize)
                if (sources.isEmpty()) {
                    complete = true
                } else {
                    totalExamined += sources.size
                    val updates = resolveBatchUpdates(sources)
                    totalUpdated += applyBatch(updates, sources.last().id)
                    batches++
                    if (sources.size < batchSize) {
                        complete = true
                    }
                    currentCoroutineContext().ensureActive()
                    yield()
                }
            }

            return BackfillResult(
                batchesProcessed = batches,
                sourcesExamined = totalExamined,
                sourcesUpdated = totalUpdated,
                isComplete = complete,
            )
        }

        private suspend fun resolveBatchUpdates(
            sources: List<HealthSourceRecordEntity>,
        ): List<SourceBoundsUpdate> {
            val unknownSources = sources.filter { it.metadataState == METADATA_STATE_UNKNOWN }
            if (unknownSources.isEmpty()) return emptyList()

            val hrRefs = unknownSources.filter { it.recordType == TYPE_HEART_RATE }.map { it.id }
            val hrvRefs = unknownSources.filter { it.recordType == TYPE_HRV }.map { it.id }

            val hrBounds =
                if (hrRefs.isNotEmpty()) {
                    heartRateDao.getChildBoundsForRefs(hrRefs).associateBy { it.sourceRecordRef }
                } else {
                    emptyMap()
                }

            val hrvBounds =
                if (hrvRefs.isNotEmpty()) {
                    hrvDao.getChildBoundsForRefs(hrvRefs).associateBy { it.sourceRecordRef }
                } else {
                    emptyMap()
                }

            return unknownSources.mapNotNull { source ->
                val bounds =
                    when (source.recordType) {
                        TYPE_HEART_RATE -> hrBounds[source.id]
                        TYPE_HRV -> hrvBounds[source.id]
                        else -> null
                    }
                bounds?.let {
                    val endExclusiveMs =
                        if (it.maxTimestampMs == Long.MAX_VALUE) {
                            Long.MAX_VALUE
                        } else {
                            it.maxTimestampMs + 1L
                        }
                    SourceBoundsUpdate(source.id, it.minTimestampMs, endExclusiveMs)
                }
            }
        }

        private suspend fun applyBatch(
            updates: List<SourceBoundsUpdate>,
            lastRef: Long,
        ): Int {
            var batchUpdated = 0
            transactionRunner.runInTransaction {
                for (update in updates) {
                    batchUpdated +=
                        sourceRecordDao.updateBackfilledBounds(
                            id = update.id,
                            recordStartMs = update.startMs,
                            recordEndExclusiveMs = update.endExclusiveMs,
                            metadataState = METADATA_STATE_CHILD_BOUNDS,
                        )
                }
                healthMutationStateDao.updateBackfillAfterSourceRef(lastRef)
            }
            return batchUpdated
        }

        companion object {
            const val DEFAULT_BATCH_SIZE = 500
            const val METADATA_STATE_UNKNOWN = "UNKNOWN"
            const val METADATA_STATE_CHILD_BOUNDS = "CHILD_BOUNDS"
            const val METADATA_STATE_AUTHORITATIVE = "AUTHORITATIVE"
            const val TYPE_HEART_RATE = "HEART_RATE"
            const val TYPE_HRV = "HRV"
        }
    }
