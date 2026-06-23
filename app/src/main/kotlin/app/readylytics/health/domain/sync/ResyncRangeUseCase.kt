package app.readylytics.health.domain.sync

import app.readylytics.health.di.IoDispatcher
import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.preferences.scoringZone
import app.readylytics.health.domain.sync.link.SessionLinkReconciler
import app.readylytics.health.domain.util.logD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Full historical resync over a retention-bounded range. Health Connect is re-read in [chunkDays]-day
 * chunks (with bounded backoff to ride out rate limits), then a single walk-forward recompute
 * rebuilds every day's scores via the unchanged scoring-engine formulas.
 *
 * Idempotent by construction: ingestion upserts by stable Health Connect record id (overlaps
 * replace, never duplicate) and no blanket delete is performed, so a worker killed/failed mid-pass
 * leaves prior valid data intact and a retry re-runs the same range cleanly. Checkpoint-resumable
 * across four phases (INGEST → PRUNE → RECONCILE → RECOMPUTE).
 *
 * Serialized against the daily sync by the shared `syncMutex` owned by [HealthSyncUseCase] — callers
 * must invoke this under that lock.
 */
@Singleton
class ResyncRangeUseCase
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val sessionLinkReconciler: SessionLinkReconciler,
        private val changeSynchronizer: HealthChangeSynchronizer,
        private val selectedSourcePruner: SelectedSourcePruner,
        private val checkpointStore: ResyncCheckpointStore,
        private val healthIngestionStore: HealthIngestionStore,
        private val ingestionCoordinator: HealthIngestionCoordinator,
        private val stepCountFetcher: StepCountFetcher,
        private val recomputeSupport: DailyRecomputeSupport,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * @param onProgress reports (completed, total) across both the ingestion and recompute phases.
         */
        suspend fun run(
            startDate: LocalDate,
            endDate: LocalDate,
            chunkDays: Int,
            onProgress: ((current: Int, total: Int) -> Unit)?,
        ): Result<Unit> =
            withContext(ioDispatcher) {
                try {
                    val zoneId = ZoneId.systemDefault()
                    logD("ResyncRangeUseCase") { "Full resync $startDate..$endDate (chunk=$chunkDays days)" }

                    val initialPrefs = settingsRepo.userPreferences.first()
                    recomputeSupport.refreshAutoMaxHr(initialPrefs)
                    val prefs = settingsRepo.userPreferences.first()
                    val selectionHash =
                        prefs.deviceByDataType.toSortedMap().entries.joinToString(
                            "|",
                        ) { (type, device) -> "$type=${device.orEmpty()}" }
                    val savedCheckpoint = checkpointStore.checkpoint.first()
                    val checkpoint =
                        savedCheckpoint
                            ?.takeIf {
                                it.startDate == startDate &&
                                    it.endDate == endDate &&
                                    it.selectionHash == selectionHash &&
                                    it.baselineChangeTokens.isNotEmpty()
                            }?.also {
                                logD("ResyncRangeUseCase") {
                                    "Resuming resync from ${it.phase} at ${it.nextDate}"
                                }
                            }
                    if (savedCheckpoint != null && checkpoint == null) {
                        checkpointStore.clear()
                    }
                    val baselineChangeTokens =
                        checkpoint?.baselineChangeTokens
                            ?: changeSynchronizer.captureChangesTokens().also { tokens ->
                                checkpointStore.save(
                                    ResyncCheckpoint(
                                        startDate = startDate,
                                        endDate = endDate,
                                        phase = ResyncPhase.INGEST,
                                        nextDate = startDate,
                                        selectionHash = selectionHash,
                                        baselineChangeTokens = tokens,
                                    ),
                                )
                            }

                    val totalDays = (ChronoUnit.DAYS.between(startDate, endDate) + 1).toInt().coerceAtLeast(0)
                    val recomputeStartDate =
                        if (checkpoint?.phase == ResyncPhase.RECOMPUTE) {
                            minOf(checkpoint.nextDate, endDate.plusDays(1))
                        } else {
                            startDate
                        }
                    val completedDays =
                        ChronoUnit
                            .DAYS
                            .between(startDate, recomputeStartDate)
                            .toInt()
                            .coerceIn(0, totalDays)
                    onProgress?.invoke(completedDays, totalDays)

                    // --- Ingestion phase: chunked HC re-fetch + idempotent upsert ---
                    val stepsDevice =
                        prefs.deviceByDataType[HealthDataType.STEPS.name]?.takeIf { it.isNotBlank() }
                    if (checkpoint == null || checkpoint.phase == ResyncPhase.INGEST) {
                        var chunkStart = checkpoint?.nextDate?.coerceAtLeast(startDate) ?: startDate
                        while (!chunkStart.isAfter(endDate)) {
                            ensureActive()
                            val chunkEndExclusive =
                                minOf(chunkStart.plusDays(chunkDays.toLong()), endDate.plusDays(1))
                            val ingestFromDate = chunkStart.minusDays(1)
                            val windowStart = ingestFromDate.atStartOfDay(zoneId).toInstant()
                            val windowEnd = chunkEndExclusive.atStartOfDay(zoneId).toInstant()

                            retryWithBackoff {
                                ingestionCoordinator.ingestWindow(
                                    windowStart = windowStart,
                                    windowEnd = windowEnd,
                                    prefs = prefs,
                                )
                            }
                            val nextPhase =
                                if (chunkEndExclusive.isAfter(endDate)) {
                                    ResyncPhase.PRUNE
                                } else {
                                    ResyncPhase.INGEST
                                }
                            checkpointStore.save(
                                ResyncCheckpoint(
                                    startDate = startDate,
                                    endDate = endDate,
                                    phase = nextPhase,
                                    nextDate =
                                        if (nextPhase ==
                                            ResyncPhase.INGEST
                                        ) {
                                            chunkEndExclusive
                                        } else {
                                            startDate
                                        },
                                    selectionHash = selectionHash,
                                    baselineChangeTokens = baselineChangeTokens,
                                ),
                            )
                            chunkStart = chunkEndExclusive
                        }
                    }

                    // --- Prune phase: remove stale data from non-selected devices ---
                    val prunerSelections =
                        HealthDataType.entries.associateWith { type ->
                            prefs.deviceByDataType[type.name]
                        }
                    if (checkpoint == null ||
                        checkpoint.phase == ResyncPhase.INGEST ||
                        checkpoint.phase == ResyncPhase.PRUNE
                    ) {
                        selectedSourcePruner.prune(
                            start = startDate,
                            endInclusive = endDate,
                            selections = prunerSelections,
                            zoneId = prefs.scoringZone(),
                        )
                        checkpointStore.save(
                            ResyncCheckpoint(
                                startDate = startDate,
                                endDate = endDate,
                                phase = ResyncPhase.RECONCILE,
                                nextDate = startDate,
                                selectionHash = selectionHash,
                                baselineChangeTokens = baselineChangeTokens,
                            ),
                        )
                    }

                    // --- Reconcile phase: chunk-independent session linkage ---
                    // Re-derives (recordType, sessionId) for every HR/HRV sample in range from the
                    // *complete* set of sleep + workout sessions, and recomputes affected workout
                    // metrics. Without this, a night straddling a chunk boundary could have its
                    // samples split across two Health Connect fetch windows, each tagging only the
                    // subset it saw - making linkage (and everything derived from it) depend on
                    // chunk alignment, which itself depends on the retention setting.
                    if (
                        checkpoint == null ||
                        checkpoint.phase == ResyncPhase.INGEST ||
                        checkpoint.phase == ResyncPhase.PRUNE ||
                        checkpoint.phase == ResyncPhase.RECONCILE
                    ) {
                        run {
                            val reconcileStartMs =
                                startDate
                                    .minusDays(
                                        1,
                                    ).atStartOfDay(zoneId)
                                    .toInstant()
                                    .toEpochMilli()
                            val reconcileEndMs =
                                endDate
                                    .plusDays(1)
                                    .atStartOfDay(zoneId)
                                    .toInstant()
                                    .toEpochMilli() - 1
                            val zoneThresholds =
                                app.readylytics.health.data.healthconnect.WorkoutMapper.zoneThresholds(
                                    prefs.zone1MinBpm,
                                    prefs.zone1MaxBpm,
                                    prefs.zone2MaxBpm,
                                    prefs.zone3MaxBpm,
                                    prefs.zone4MaxBpm,
                                )
                            sessionLinkReconciler.reconcile(reconcileStartMs, reconcileEndMs, zoneThresholds)
                        }
                        checkpointStore.save(
                            ResyncCheckpoint(
                                startDate = startDate,
                                endDate = endDate,
                                phase = ResyncPhase.RECOMPUTE,
                                nextDate = startDate,
                                selectionHash = selectionHash,
                                baselineChangeTokens = baselineChangeTokens,
                            ),
                        )
                    }

                    // --- Recompute phase: walk-forward over the full range ---
                    // Clear frozen snapshots for the exact range so bounded baseline variants
                    // recompute per day and recent sync/resync use the same baseline path.
                    val stepsMap =
                        if (!recomputeStartDate.isAfter(endDate)) {
                            stepCountFetcher.fetchRange(
                                startDate = recomputeStartDate,
                                endDate = endDate,
                                chunkDays = chunkDays,
                                stepsDevice = stepsDevice,
                                zoneId = zoneId,
                            )
                        } else {
                            emptyMap()
                        }
                    if (checkpoint == null || checkpoint.phase != ResyncPhase.RECOMPUTE) {
                        healthIngestionStore.clearFrozenBaselines(startDate, endDate.plusDays(1))
                    }
                    var day = recomputeStartDate
                    var recomputedDays = completedDays
                    while (!day.isAfter(endDate)) {
                        ensureActive()
                        val stepsForDay = if (stepsDevice != null) stepsMap[day] ?: 0L else stepsMap[day]
                        val dayResult = recomputeSupport.recomputeDay(day, stepsForDay)
                        if (dayResult is Result.Failure) {
                            return@withContext dayResult
                        }
                        recomputedDays++
                        checkpointStore.save(
                            ResyncCheckpoint(
                                startDate = startDate,
                                endDate = endDate,
                                phase = ResyncPhase.RECOMPUTE,
                                nextDate = day.plusDays(1),
                                selectionHash = selectionHash,
                                baselineChangeTokens = baselineChangeTokens,
                            ),
                        )
                        onProgress?.invoke(recomputedDays, totalDays)
                        day = day.plusDays(1)
                        yield()
                    }

                    changeSynchronizer.commitTokens(baselineChangeTokens)
                    settingsRepo.updateLastSyncTimestamp(System.currentTimeMillis())
                    checkpointStore.clear()
                    logD("ResyncRangeUseCase") { "Full resync complete ($totalDays days)" }
                    Result.success(Unit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure("Full resync failed", "RESYNC_ERROR")
                }
            }
    }
