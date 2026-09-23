package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.database.domain.sync.DailyRecomputeSupport
import app.readylytics.health.core.model.di.IoDispatcher
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.core.model.domain.repository.HealthConnectWindowTimeoutException
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.HistoricalRunIdentity
import app.readylytics.health.core.model.domain.sync.ResyncCheckpoint
import app.readylytics.health.core.model.domain.sync.ResyncCheckpointStore
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import app.readylytics.health.core.model.domain.sync.SelectedSourcePruner
import app.readylytics.health.core.model.domain.sync.ScoringRunContext
import app.readylytics.health.core.model.domain.sync.link.SessionLinkReconciler
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private data class ResyncExecutionPlan(
    val requestedRun: HistoricalRunIdentity,
    val runIdentity: HistoricalRunIdentity,
    val effectivePrefs: UserPreferences,
    val runContext: ScoringRunContext,
    val runStartDate: LocalDate,
    val runEndDate: LocalDate,
    val runZoneId: ZoneId,
    val selectionHash: String,
    val checkpoint: ResyncCheckpoint?,
    val baselineChangeTokens: Map<HealthDataType, String>,
    val runCompletedTypes: Set<HealthDataType>,
    val totalDays: Int,
    val totalChunks: Int,
    val recomputeStartDate: LocalDate,
    val completedDays: Int,
    val reconcileStartMs: Long,
    val reconcileEndMs: Long,
    val runIngestion: Boolean,
    val runPruning: Boolean,
    val runReconciliation: Boolean,
) {
    companion object {
        fun create(
            requestedRun: HistoricalRunIdentity,
            activeRun: HistoricalRunIdentity,
            prefs: UserPreferences,
            checkpoint: ResyncCheckpoint?,
            baselineChangeTokens: Map<HealthDataType, String>,
            chunkDays: Int,
            skipIngestAndPrune: Boolean,
        ): ResyncExecutionPlan {
            val effectivePrefs = activeRun.effectivePreferences() ?: prefs
            val runContext =
                ScoringRunContext.capture(
                    effectivePrefs,
                    Instant.ofEpochMilli(activeRun.startedAtEpochMs),
                )
            check(runContext.zoneId.id == activeRun.zoneId) { "Historical run zone mismatch" }
            val runCompletedTypes = when {
                checkpoint?.completedTypesRecorded == true -> checkpoint.completedTypes
                skipIngestAndPrune -> emptySet()
                else -> baselineChangeTokens.keys
            }
            val runStartDate = LocalDate.ofEpochDay(activeRun.startEpochDay)
            val runEndDate = LocalDate.ofEpochDay(activeRun.endEpochDayInclusive)
            val runZoneId = ZoneId.of(activeRun.zoneId)
            val totalDays = (ChronoUnit.DAYS.between(runStartDate, runEndDate) + 1).toInt().coerceAtLeast(0)
            val totalChunks = if (totalDays <= 0) 0 else (totalDays + chunkDays - 1) / chunkDays
            val recomputeStartDate = when {
                checkpoint?.phase == ResyncPhase.RECOMPUTE -> minOf(checkpoint.nextDate, runEndDate.plusDays(1))
                else -> runStartDate
            }
            val completedDays = ChronoUnit.DAYS.between(runStartDate, recomputeStartDate).toInt().coerceIn(0, totalDays)
            val reconcileStartMs = runStartDate.minusDays(1).atStartOfDay(runZoneId).toInstant().toEpochMilli()
            val reconcileEndMs = runEndDate.plusDays(1).atStartOfDay(runZoneId).toInstant().toEpochMilli() - 1
            val currentPhase = checkpoint?.phase
            val canIngest = currentPhase == null || currentPhase == ResyncPhase.INGEST
            val canPrune = canIngest || currentPhase == ResyncPhase.PRUNE

            return ResyncExecutionPlan(
                requestedRun = requestedRun,
                runIdentity = activeRun,
                effectivePrefs = effectivePrefs,
                runContext = runContext,
                runStartDate = runStartDate,
                runEndDate = runEndDate,
                runZoneId = runZoneId,
                selectionHash = activeRun.scoringSnapshotId,
                checkpoint = checkpoint,
                baselineChangeTokens = baselineChangeTokens,
                runCompletedTypes = runCompletedTypes,
                totalDays = totalDays,
                totalChunks = totalChunks,
                recomputeStartDate = recomputeStartDate,
                completedDays = completedDays,
                reconcileStartMs = reconcileStartMs,
                reconcileEndMs = reconcileEndMs,
                runIngestion = !skipIngestAndPrune && canIngest,
                runPruning = !skipIngestAndPrune && canPrune,
                runReconciliation = currentPhase != ResyncPhase.RECOMPUTE,
            )
        }
    }
}

@Singleton
class ResyncRangeUseCase
    @Inject
    constructor(
        private val changeSynchronizer: HealthChangeSynchronizer,
        private val selectedSourcePruner: SelectedSourcePruner,
        private val sessionLinkReconciler: SessionLinkReconciler,
        private val checkpointStore: ResyncCheckpointStore,
        private val settingsRepo: SettingsRepository,
        private val healthIngestionStore: HealthIngestionStore,
        private val recomputeSupport: DailyRecomputeSupport,
        private val ingestion: ResyncIngestionDependencies,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        private val clock: Clock = Clock.systemDefaultZone(),
        private val ingestPhase: HistoricalIngestPhase =
            HistoricalIngestPhase(
                healthIngestionStore = healthIngestionStore,
                ingestion = ingestion,
                checkpointStore = checkpointStore,
                clock = clock,
            ),
        private val prunePhase: HistoricalPrunePhase =
            HistoricalPrunePhase(
                selectedSourcePruner = selectedSourcePruner,
                healthIngestionStore = healthIngestionStore,
                checkpointStore = checkpointStore,
                clock = clock,
            ),
        private val recomputePhase: HistoricalRecomputePhase =
            HistoricalRecomputePhase(
                healthIngestionStore = healthIngestionStore,
                recomputeSupport = recomputeSupport,
                checkpointStore = checkpointStore,
                clock = clock,
            ),
    ) {
        suspend fun run(
            startDate: LocalDate,
            endDate: LocalDate,
            chunkDays: Int,
            onProgress: ((phase: ResyncPhase, current: Int, total: Int) -> Unit)?,
            skipIngestAndPrune: Boolean = false,
            requestedRunId: String? = null,
        ): Result<Unit> =
            withContext(ioDispatcher) {
                try {
                    val plan = preparePlan(startDate, endDate, chunkDays, skipIngestAndPrune, requestedRunId)
                    var runCompletedTypes = plan.runCompletedTypes
                    var earliestDeletionDate: LocalDate? = null
                    var initialCounts = PruneCounts(0, 0, 0, 0)

                    if (plan.runIngestion) {
                        val outcome = executeIngestion(plan, chunkDays, skipIngestAndPrune, onProgress)
                        earliestDeletionDate = outcome.earliestDeletionDate
                        runCompletedTypes = outcome.completedTypes
                        initialCounts =
                            PruneCounts(
                                hr = outcome.hrBeforePrune,
                                hrv = outcome.hrvBeforePrune,
                                sleep = outcome.sleepBeforePrune,
                                workout = outcome.workoutBeforePrune,
                            )
                    }

                    if (plan.runPruning) {
                        executePruning(plan, runCompletedTypes, initialCounts, onProgress)
                    }

                    if (plan.runReconciliation) {
                        executeReconciliation(plan, runCompletedTypes, onProgress)
                    }

                    val recomputeResult =
                        executeRecompute(
                            plan,
                            chunkDays,
                            skipIngestAndPrune,
                            runCompletedTypes,
                            earliestDeletionDate,
                            onProgress,
                        )
                    if (recomputeResult is Result.Failure) return@withContext recomputeResult

                    finalizeRun(plan, skipIngestAndPrune, runCompletedTypes)
                    Result.success(Unit)
                } catch (e: CancellationException) {
                    logI(TELEMETRY_TAG) { "Resync cancelled." }
                    throw e
                } catch (e: HealthConnectPermissionRevokedException) {
                    logI(TELEMETRY_TAG) { "Resync stopped by Health Connect permission failure: ${e.message}" }
                    throw e
                } catch (e: HealthConnectWindowTimeoutException) {
                    logI(TELEMETRY_TAG) {
                        "Resync failed: window read timed out even at the minimum chunk size (${e.message})"
                    }
                    Result.failure("Full resync failed: window read timeout", "RESYNC_WINDOW_TIMEOUT")
                } catch (e: Exception) {
                    logI(TELEMETRY_TAG) { "Resync failed with exception: ${e.message}" }
                    Result.failure("Full resync failed", "RESYNC_ERROR")
                }
            }

        private suspend fun preparePlan(
            startDate: LocalDate,
            endDate: LocalDate,
            chunkDays: Int,
            skipIngestAndPrune: Boolean,
            requestedRunId: String?,
        ): ResyncExecutionPlan {
            logD(TAG) {
                if (skipIngestAndPrune) {
                    "Recompute-only $startDate..$endDate"
                } else {
                    "Full resync $startDate..$endDate (chunk=$chunkDays days)"
                }
            }
            val (requestedRun, prefs) =
                prepareRequestedRun(startDate, endDate, skipIngestAndPrune, requestedRunId)

            val savedCheckpoint = checkpointStore.checkpoint.first()
            val activeRun = HistoricalRunResolver.resolve(savedCheckpoint?.runIdentity, requestedRun)
            val isSameRun = savedCheckpoint != null && activeRun == savedCheckpoint.runIdentity
            val runStartDate = LocalDate.ofEpochDay(activeRun.startEpochDay)
            val runEndDate = LocalDate.ofEpochDay(activeRun.endEpochDayInclusive)
            val runZoneId = ZoneId.of(activeRun.zoneId)

            val checkpoint =
                HistoricalRunResolver.resolveEffectiveCheckpoint(
                    savedCheckpoint = savedCheckpoint,
                    runIdentity = activeRun,
                    isSameRun = isSameRun,
                    skipIngestAndPrune = skipIngestAndPrune,
                    runStartDate = runStartDate,
                )
            if (savedCheckpoint != null && checkpoint == null) {
                checkpointStore.clear()
            }

            val baselineChangeTokens = resolveBaselineTokens(checkpoint, skipIngestAndPrune)
            if (checkpoint == null) {
                checkpointStore.save(
                    ResyncCheckpoint(
                        startDate = runStartDate,
                        endDate = runEndDate,
                        phase = if (skipIngestAndPrune) ResyncPhase.RECONCILE else ResyncPhase.INGEST,
                        nextDate = runStartDate,
                        selectionHash = activeRun.scoringSnapshotId,
                        baselineChangeTokens = baselineChangeTokens,
                        completedTypes = if (skipIngestAndPrune) emptySet() else baselineChangeTokens.keys,
                        runIdentity = activeRun,
                    ),
                )
            }

            return ResyncExecutionPlan.create(
                requestedRun = requestedRun,
                activeRun = activeRun,
                prefs = prefs,
                checkpoint = checkpoint,
                baselineChangeTokens = baselineChangeTokens,
                chunkDays = chunkDays,
                skipIngestAndPrune = skipIngestAndPrune,
            )
        }

        private suspend fun prepareRequestedRun(
            startDate: LocalDate,
            endDate: LocalDate,
            skipIngestAndPrune: Boolean,
            requestedRunId: String?,
        ): Pair<HistoricalRunIdentity, UserPreferences> {
            val initialPrefs = settingsRepo.userPreferences.first()
            recomputeSupport.refreshAutoMaxHr(initialPrefs)
            val prefs = settingsRepo.userPreferences.first()
            val zoneId = prefs.scoringZone()
            val resolvedHrMax =
                if (prefs.autoCalculateMaxHr) {
                    (TANAKA_BASE - TANAKA_FACTOR * prefs.age).toFloat()
                } else {
                    prefs.maxHeartRate.toFloat()
                }
            val mode =
                if (skipIngestAndPrune) {
                    HistoricalRunIdentity.MODE_RECOMPUTE_ONLY
                } else {
                    HistoricalRunIdentity.MODE_FULL_INGEST
                }
            val requestedRun =
                HistoricalRunIdentity.create(
                    runId = requestedRunId ?: UUID.randomUUID().toString(),
                    mode = mode,
                    startDate = startDate,
                    endDate = endDate,
                    zoneId = zoneId,
                    prefs = prefs,
                    resolvedHrMax = resolvedHrMax,
                    startedAtEpochMs = clock.millis(),
                    algorithmRevision = SettingsDefaults.CURRENT_SCORING_VERSION,
                )
            return Pair(requestedRun, prefs)
        }

        private suspend fun resolveBaselineTokens(
            checkpoint: ResyncCheckpoint?,
            skipIngestAndPrune: Boolean,
        ): Map<HealthDataType, String> =
            checkpoint?.baselineChangeTokens
                ?: if (skipIngestAndPrune) {
                    emptyMap()
                } else {
                    changeSynchronizer.captureChangesTokens()
                }

        private suspend fun executeIngestion(
            plan: ResyncExecutionPlan,
            chunkDays: Int,
            skipIngestAndPrune: Boolean,
            onProgress: ((phase: ResyncPhase, current: Int, total: Int) -> Unit)?,
        ): IngestPhaseOutcome =
            ingestPhase.execute(
                IngestPhaseContext(
                    startDate = plan.runStartDate,
                    endDate = plan.runEndDate,
                    zoneId = plan.runZoneId,
                    prefs = plan.effectivePrefs,
                    chunkDays = chunkDays,
                    totalChunks = plan.totalChunks,
                    reconcileStartMs = plan.reconcileStartMs,
                    reconcileEndMs = plan.reconcileEndMs,
                    selectionHash = plan.selectionHash,
                    baselineChangeTokens = plan.baselineChangeTokens,
                    initialCompletedTypes = plan.runCompletedTypes,
                    runIdentity = plan.runIdentity,
                    checkpoint = plan.checkpoint,
                    skipIngestAndPrune = skipIngestAndPrune,
                ),
                onProgress,
            )

        private suspend fun executePruning(
            plan: ResyncExecutionPlan,
            runCompletedTypes: Set<HealthDataType>,
            initialCounts: PruneCounts,
            onProgress: ((phase: ResyncPhase, current: Int, total: Int) -> Unit)?,
        ) {
            prunePhase.execute(
                PrunePhaseContext(
                    startDate = plan.runStartDate,
                    endDate = plan.runEndDate,
                    zoneId = plan.runZoneId,
                    prefs = plan.effectivePrefs,
                    reconcileStartMs = plan.reconcileStartMs,
                    reconcileEndMs = plan.reconcileEndMs,
                    selectionHash = plan.selectionHash,
                    baselineChangeTokens = plan.baselineChangeTokens,
                    runCompletedTypes = runCompletedTypes,
                    runIdentity = plan.runIdentity,
                    runIngestion = plan.runIngestion,
                    initialCounts = initialCounts,
                ),
                onProgress,
            )
        }

        /**
         * Phase 3 of the four resumable phases: one session-link reconcile over the **complete**
         * range, after all ingest and prune. Chunk-independent by construction -- the reconciler
         * sees the full session list, so a session straddling an ingest-chunk boundary resolves the
         * same way regardless of how the range happened to be chunked.
         *
         * **WP-17 Step 4:** this is also where warm-tier minutes get re-keyed. A warm minute stores
         * its session link inside its primary key, so `SessionLinkReconciler` re-derives every
         * `SOURCE_BACKED` minute's projection from stable per-source evidence and republishes the
         * ones whose assignment changed, and retires minutes whose evidence a Health Connect
         * deletion removed. That makes this phase the convergence point for rolled-up-source
         * deletions too: the recompute-only resync a deletion's dirty range schedules runs with
         * `skipIngestAndPrune = true`, whose checkpoint starts at [ResyncPhase.RECONCILE], so the
         * relink always runs before the days that depend on it are recomputed. `runReconciliation`
         * is false only when a killed run is resuming *inside* the recompute phase, i.e. after this
         * pass already committed.
         */
        private suspend fun executeReconciliation(
            plan: ResyncExecutionPlan,
            runCompletedTypes: Set<HealthDataType>,
            onProgress: ((phase: ResyncPhase, current: Int, total: Int) -> Unit)?,
        ) {
            onProgress?.invoke(ResyncPhase.RECONCILE, 0, 0)
            val reconcileStart = clock.millis()
            val zoneThresholds =
                app.readylytics.health.core.model.domain.heartrate.ZoneThresholds.create(
                    plan.effectivePrefs.zone1MinBpm,
                    plan.effectivePrefs.zone1MaxBpm,
                    plan.effectivePrefs.zone2MaxBpm,
                    plan.effectivePrefs.zone3MaxBpm,
                    plan.effectivePrefs.zone4MaxBpm,
                )
            sessionLinkReconciler.reconcile(plan.reconcileStartMs, plan.reconcileEndMs, zoneThresholds)

            checkpointStore.save(
                ResyncCheckpoint(
                    startDate = plan.runStartDate,
                    endDate = plan.runEndDate,
                    phase = ResyncPhase.RECOMPUTE,
                    nextDate = plan.runStartDate,
                    selectionHash = plan.selectionHash,
                    baselineChangeTokens = plan.baselineChangeTokens,
                    completedTypes = runCompletedTypes,
                    runIdentity = plan.runIdentity,
                ),
            )
            val reconcileEnd = clock.millis()
            logD(TELEMETRY_TAG) {
                "[RECONCILIATION] Completed in ${reconcileEnd - reconcileStart}ms."
            }
        }

        private suspend fun executeRecompute(
            plan: ResyncExecutionPlan,
            chunkDays: Int,
            skipIngestAndPrune: Boolean,
            runCompletedTypes: Set<HealthDataType>,
            earliestDeletionDate: LocalDate?,
            onProgress: ((phase: ResyncPhase, current: Int, total: Int) -> Unit)?,
        ): Result<Unit> {
            var recomputeStartDate = plan.recomputeStartDate
            var completedDays = plan.completedDays
            if (earliestDeletionDate != null && earliestDeletionDate.isBefore(recomputeStartDate)) {
                recomputeStartDate = earliestDeletionDate
                completedDays =
                    ChronoUnit
                        .DAYS
                        .between(plan.runStartDate, recomputeStartDate)
                        .toInt()
                        .coerceIn(0, plan.totalDays)
            }

            val stepsDevice =
                plan.effectivePrefs.deviceByDataType[HealthDataType.STEPS.name]?.takeIf { it.isNotBlank() }

            val recomputeContext =
                RecomputePhaseContext(
                    startDate = plan.runStartDate,
                    endDate = plan.runEndDate,
                    zoneId = plan.runZoneId,
                    prefs = plan.effectivePrefs,
                    runContext = plan.runContext,
                    recomputeStartDate = recomputeStartDate,
                    completedDays = completedDays,
                    totalDays = plan.totalDays,
                    chunkDays = chunkDays,
                    skipIngestAndPrune = skipIngestAndPrune,
                    stepsDevice = stepsDevice,
                    stepCountFetcher = ingestion.stepCountFetcher,
                    selectionHash = plan.selectionHash,
                    baselineChangeTokens = plan.baselineChangeTokens,
                    runCompletedTypes = runCompletedTypes,
                    runIdentity = plan.runIdentity,
                    checkpoint = plan.checkpoint,
                )

            return recomputePhase.execute(recomputeContext, onProgress)
        }

        private suspend fun finalizeRun(
            plan: ResyncExecutionPlan,
            skipIngestAndPrune: Boolean,
            runCompletedTypes: Set<HealthDataType>,
        ) {
            if (!skipIngestAndPrune) {
                val tokensToPromote = plan.baselineChangeTokens.filterKeys { it in runCompletedTypes }
                changeSynchronizer.commitTokens(tokensToPromote)
                settingsRepo.updateLastSyncTimestamp(clock.millis())
            }
            checkpointStore.clear()
            logI(TAG) {
                if (skipIngestAndPrune) {
                    "Recompute-only complete (${plan.totalDays} days)"
                } else {
                    "Full resync complete (${plan.totalDays} days)"
                }
            }
        }

        companion object {
            private const val TAG = "ResyncRangeUseCase"
            private const val TELEMETRY_TAG = "ResyncTelemetry"
            private const val TANAKA_BASE = 208
            private const val TANAKA_FACTOR = 0.7
        }
    }
