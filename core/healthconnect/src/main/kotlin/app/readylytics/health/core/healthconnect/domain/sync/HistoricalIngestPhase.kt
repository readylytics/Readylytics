package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.HealthConnectWindowTimeoutException
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.HistoricalRunIdentity
import app.readylytics.health.core.model.domain.sync.ResyncCheckpoint
import app.readylytics.health.core.model.domain.sync.ResyncCheckpointStore
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import app.readylytics.health.core.model.domain.sync.ScanIdentity
import app.readylytics.health.core.model.domain.sync.ScanStagingStore
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logW
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

data class IngestPhaseContext(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val zoneId: ZoneId,
    val prefs: UserPreferences,
    val chunkDays: Int,
    val totalChunks: Int,
    val reconcileStartMs: Long,
    val reconcileEndMs: Long,
    val selectionHash: String,
    val baselineChangeTokens: Map<HealthDataType, String>,
    val initialCompletedTypes: Set<HealthDataType>,
    val runIdentity: HistoricalRunIdentity,
    val checkpoint: ResyncCheckpoint?,
    val skipIngestAndPrune: Boolean,
)

data class IngestPhaseOutcome(
    val earliestDeletionDate: LocalDate?,
    val completedTypes: Set<HealthDataType>,
    val hrBeforePrune: Int,
    val hrvBeforePrune: Int,
    val sleepBeforePrune: Int,
    val workoutBeforePrune: Int,
)

private data class IngestCounts(
    val hr: Int,
    val hrv: Int,
    val sleep: Int,
    val workout: Int,
)

private sealed interface ChunkIngestResult {
    data class Success(
        val nextChunkStart: LocalDate,
        val affectedStart: LocalDate?,
        val completedTypes: Set<HealthDataType>,
    ) : ChunkIngestResult

    data class Shrunk(val newChunkDays: Int) : ChunkIngestResult
}

@Singleton
class HistoricalIngestPhase
    @Inject
    constructor(
        private val healthIngestionStore: HealthIngestionStore,
        private val ingestion: ResyncIngestionDependencies,
        private val checkpointStore: ResyncCheckpointStore,
        private val clock: Clock = Clock.systemDefaultZone(),
        private val staging: ScanStagingStore = ingestion.staging,
    ) {
        suspend fun execute(
            context: IngestPhaseContext,
            onProgress: ((phase: ResyncPhase, current: Int, total: Int) -> Unit)?,
        ): IngestPhaseOutcome {
            staging.clearRunsOtherThan(context.runIdentity.runId)
            val beforeCounts = readCounts(context.reconcileStartMs, context.reconcileEndMs)
            val ingestStart = clock.millis()
            var chunkStart = context.checkpoint?.nextDate?.coerceAtLeast(context.startDate) ?: context.startDate
            var chunksCompleted =
                (ChronoUnit.DAYS.between(context.startDate, chunkStart) / context.chunkDays)
                    .toInt()
                    .coerceIn(0, context.totalChunks)
            var effectiveChunkDays = context.checkpoint?.chunkDaysOverride ?: context.chunkDays
            var runCompletedTypes = context.initialCompletedTypes

            var earliestDeletionDate: LocalDate? = null

            while (!chunkStart.isAfter(context.endDate)) {
                currentCoroutineContext().ensureActive()
                val chunkResult = processChunk(context, chunkStart, effectiveChunkDays, runCompletedTypes)
                when (chunkResult) {
                    is ChunkIngestResult.Shrunk -> {
                        effectiveChunkDays = chunkResult.newChunkDays
                    }
                    is ChunkIngestResult.Success -> {
                        runCompletedTypes = chunkResult.completedTypes
                        if (chunkResult.affectedStart != null) {
                            earliestDeletionDate =
                                minOf(earliestDeletionDate ?: chunkResult.affectedStart, chunkResult.affectedStart)
                        }
                        effectiveChunkDays = context.chunkDays
                        chunksCompleted++
                        onProgress?.invoke(ResyncPhase.INGEST, chunksCompleted, context.totalChunks)
                        chunkStart = chunkResult.nextChunkStart
                    }
                }
            }

            val afterCounts = readCounts(context.reconcileStartMs, context.reconcileEndMs)
            logCompletionTelemetry(ingestStart, beforeCounts, afterCounts)

            return IngestPhaseOutcome(
                earliestDeletionDate = earliestDeletionDate,
                completedTypes = runCompletedTypes,
                hrBeforePrune = afterCounts.hr,
                hrvBeforePrune = afterCounts.hrv,
                sleepBeforePrune = afterCounts.sleep,
                workoutBeforePrune = afterCounts.workout,
            )
        }

        private suspend fun readCounts(startMs: Long, endMs: Long) = IngestCounts(
            hr = healthIngestionStore.countHeartRateInRange(startMs, endMs),
            hrv = healthIngestionStore.countHrvInRange(startMs, endMs),
            sleep = healthIngestionStore.countSleepSessionsInRange(startMs, endMs),
            workout = healthIngestionStore.countWorkoutsInRange(startMs, endMs),
        )

        private data class ChunkWindowParams(
            val windowStart: Instant,
            val windowEnd: Instant,
            val scanIdentity: ScanIdentity,
            val chunkStart: LocalDate,
            val chunkOverride: Int?,
            val runCompletedTypes: Set<HealthDataType>,
        )

        private suspend fun processChunk(
            context: IngestPhaseContext,
            chunkStart: LocalDate,
            effectiveChunkDays: Int,
            runCompletedTypes: Set<HealthDataType>,
        ): ChunkIngestResult {
            val chunkEndExclusive =
                minOf(chunkStart.plusDays(effectiveChunkDays.toLong()), context.endDate.plusDays(1))
            val windowStart = chunkStart.minusDays(1).atStartOfDay(context.zoneId).toInstant()
            val windowEnd = chunkEndExclusive.atStartOfDay(context.zoneId).toInstant()
            val chunkOverride = if (effectiveChunkDays != context.chunkDays) effectiveChunkDays else null

            val scanIdentity = ScanIdentities.historical(context.runIdentity.runId, chunkStart)
            val resumingThisChunk =
                context.checkpoint?.phase == ResyncPhase.INGEST &&
                    context.checkpoint.nextDate == chunkStart &&
                    context.checkpoint.runIdentity?.runId == context.runIdentity.runId
            val hrStartPageToken = if (resumingThisChunk) context.checkpoint.hrPageToken else null
            val hrvStartPageToken = if (resumingThisChunk) context.checkpoint.hrvPageToken else null

            val params =
                ChunkWindowParams(
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    scanIdentity = scanIdentity,
                    chunkStart = chunkStart,
                    chunkOverride = chunkOverride,
                    runCompletedTypes = runCompletedTypes,
                )

            val ingestResult =
                try {
                    executeChunkIngest(
                        context = context,
                        params = params,
                        hrStartPageToken = hrStartPageToken,
                        hrvStartPageToken = hrvStartPageToken,
                    )
                } catch (e: HealthConnectWindowTimeoutException) {
                    return handleChunkTimeout(
                        e,
                        context,
                        chunkStart,
                        effectiveChunkDays,
                        runCompletedTypes,
                        windowStart,
                        windowEnd,
                    )
                }

            val updatedCompletedTypes = runCompletedTypes.intersect(ingestResult.completedTypes)
            saveChunkCompleted(context, chunkEndExclusive, updatedCompletedTypes)
            for (type in HealthDataType.entries) {
                staging.clearTypeScan(scanIdentity, type)
            }
            return ChunkIngestResult.Success(
                nextChunkStart = chunkEndExclusive,
                affectedStart = ingestResult.affectedRange?.start,
                completedTypes = updatedCompletedTypes,
            )
        }

        private suspend fun executeChunkIngest(
            context: IngestPhaseContext,
            params: ChunkWindowParams,
            hrStartPageToken: String?,
            hrvStartPageToken: String?,
        ): IngestionWindowResult =
            if (hrStartPageToken != null || hrvStartPageToken != null) {
                runWithTokenFallback(
                    context = context,
                    params = params,
                    hrStartPageToken = hrStartPageToken,
                    hrvStartPageToken = hrvStartPageToken,
                )
            } else {
                runIngestWindow(
                    context = context,
                    params = params,
                    hrStartPageToken = null,
                    hrvStartPageToken = null,
                )
            }

        private suspend fun runWithTokenFallback(
            context: IngestPhaseContext,
            params: ChunkWindowParams,
            hrStartPageToken: String?,
            hrvStartPageToken: String?,
        ): IngestionWindowResult =
            try {
                runIngestWindow(
                    context = context,
                    params = params,
                    hrStartPageToken = hrStartPageToken,
                    hrvStartPageToken = hrvStartPageToken,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: HealthConnectWindowTimeoutException) {
                throw e
            } catch (e: Exception) {
                logW(TELEMETRY_TAG, e) {
                    "[INGESTION] Resumed token rejected for chunk ${params.chunkStart}; " +
                        "replaying chunk without tokens."
                }
                runIngestWindow(
                    context = context,
                    params = params,
                    hrStartPageToken = null,
                    hrvStartPageToken = null,
                )
            }

        // Fix for Task 4 review Finding 2: resume is derived independently per type from whether
        // that type's own stored page token is present, rather than from one shared "is this chunk
        // being resumed" boolean. HR always streams to completion before HRV starts within a chunk
        // attempt, so the common post-interruption-during-HRV case checkpoints hrPageToken = null
        // (HR already complete) alongside hrvPageToken = <token> (HRV mid-stream) -- a single shared
        // flag would then resume-keep HR's prior staged ids through a fresh full HR re-read, letting
        // an HC-side deletion in the gap between attempts survive deletion reconciliation.
        //
        // HC-005/PERF-001: no outer retryWithBackoff here anymore -- ingestWindow's reads now share
        // one bounded ReadRetryBudget (HealthIngestionCoordinator/HeartSampleStreamer), so exhaustion
        // propagates straight out; WorkManager's EXPONENTIAL Result.retry() is the sole outer retry.
        private suspend fun runIngestWindow(
            context: IngestPhaseContext,
            params: ChunkWindowParams,
            hrStartPageToken: String?,
            hrvStartPageToken: String?,
        ): IngestionWindowResult =
            ingestion.ingestionCoordinator.ingestWindow(
                windowStart = params.windowStart,
                windowEnd = params.windowEnd,
                prefs = context.prefs,
                scanIdentity = params.scanIdentity,
                resumeHrScan = hrStartPageToken != null,
                resumeHrvScan = hrvStartPageToken != null,
                hrStartPageToken = hrStartPageToken,
                hrvStartPageToken = hrvStartPageToken,
                onTokenUpdated = { hrToken, hrvToken ->
                    saveChunkProgress(
                        context,
                        params.chunkStart,
                        params.chunkOverride,
                        hrToken,
                        hrvToken,
                        params.runCompletedTypes,
                    )
                },
                reconcileDeletions = !context.skipIngestAndPrune,
            )

        private suspend fun handleChunkTimeout(
            e: HealthConnectWindowTimeoutException,
            context: IngestPhaseContext,
            chunkStart: LocalDate,
            effectiveChunkDays: Int,
            completedTypes: Set<HealthDataType>,
            windowStart: Instant,
            windowEnd: Instant,
        ): ChunkIngestResult {
            if (effectiveChunkDays <= MIN_CHUNK_DAYS) {
                logD(TELEMETRY_TAG) {
                    "[INGESTION] Window $windowStart..$windowEnd timed out even at the " +
                        "$MIN_CHUNK_DAYS-day floor; giving up."
                }
                throw e
            }
            val shrunkDays = (effectiveChunkDays / 2).coerceAtLeast(MIN_CHUNK_DAYS)
            logD(TELEMETRY_TAG) {
                "[INGESTION] Window $windowStart..$windowEnd timed out; shrinking chunk " +
                    "$effectiveChunkDays -> $shrunkDays days and retrying $chunkStart."
            }
            checkpointStore.save(
                ResyncCheckpoint(
                    startDate = context.startDate,
                    endDate = context.endDate,
                    phase = ResyncPhase.INGEST,
                    nextDate = chunkStart,
                    selectionHash = context.selectionHash,
                    baselineChangeTokens = context.baselineChangeTokens,
                    chunkDaysOverride = shrunkDays,
                    hrPageToken = null,
                    hrvPageToken = null,
                    completedTypes = completedTypes,
                    runIdentity = context.runIdentity,
                ),
            )
            return ChunkIngestResult.Shrunk(shrunkDays)
        }

        private suspend fun saveChunkProgress(
            context: IngestPhaseContext,
            chunkStart: LocalDate,
            chunkOverride: Int?,
            hrToken: String?,
            hrvToken: String?,
            completedTypes: Set<HealthDataType>,
        ) {
            checkpointStore.save(
                ResyncCheckpoint(
                    startDate = context.startDate,
                    endDate = context.endDate,
                    phase = ResyncPhase.INGEST,
                    nextDate = chunkStart,
                    selectionHash = context.selectionHash,
                    baselineChangeTokens = context.baselineChangeTokens,
                    chunkDaysOverride = chunkOverride,
                    hrPageToken = hrToken,
                    hrvPageToken = hrvToken,
                    completedTypes = completedTypes,
                    runIdentity = context.runIdentity,
                ),
            )
        }

        private suspend fun saveChunkCompleted(
            context: IngestPhaseContext,
            chunkEndExclusive: LocalDate,
            completedTypes: Set<HealthDataType>,
        ) {
            val nextPhase = if (chunkEndExclusive.isAfter(context.endDate)) ResyncPhase.PRUNE else ResyncPhase.INGEST
            checkpointStore.save(
                ResyncCheckpoint(
                    startDate = context.startDate,
                    endDate = context.endDate,
                    phase = nextPhase,
                    nextDate = if (nextPhase == ResyncPhase.INGEST) chunkEndExclusive else context.startDate,
                    selectionHash = context.selectionHash,
                    baselineChangeTokens = context.baselineChangeTokens,
                    chunkDaysOverride = null,
                    hrPageToken = null,
                    hrvPageToken = null,
                    completedTypes = completedTypes,
                    runIdentity = context.runIdentity,
                ),
            )
        }

        private fun logCompletionTelemetry(startMs: Long, before: IngestCounts, after: IngestCounts) {
            val duration = clock.millis() - startMs
            logD(TELEMETRY_TAG) {
                "[INGESTION] Completed in ${duration}ms. " +
                    "HeartRate: ${before.hr} -> ${after.hr} (delta: ${after.hr - before.hr}), " +
                    "HRV: ${before.hrv} -> ${after.hrv} (delta: ${after.hrv - before.hrv}), " +
                    "Sleep: ${before.sleep} -> ${after.sleep} (delta: ${after.sleep - before.sleep}), " +
                    "Workout: ${before.workout} -> ${after.workout} (delta: ${after.workout - before.workout})"
            }
        }

        companion object {
            private const val TELEMETRY_TAG = "ResyncTelemetry"
            private const val MIN_CHUNK_DAYS = 1
        }
    }
