package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.DomainBloodPressureRecord
import app.readylytics.health.core.model.domain.model.DomainBodyFatRecord
import app.readylytics.health.core.model.domain.model.DomainBodyTemperatureRecord
import app.readylytics.health.core.model.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.core.model.domain.model.DomainOxygenSaturationRecord
import app.readylytics.health.core.model.domain.model.DomainSleepSessionRecord
import app.readylytics.health.core.model.domain.model.DomainStepsRecord
import app.readylytics.health.core.model.domain.model.DomainVo2MaxRecord
import app.readylytics.health.core.model.domain.model.DomainWeightRecord
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.HealthConnectWindowTimeoutException
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.repository.dataOrEmpty
import app.readylytics.health.core.model.domain.sync.BloodPressureInput
import app.readylytics.health.core.model.domain.sync.BodyFatInput
import app.readylytics.health.core.model.domain.sync.BodyTemperatureInput
import app.readylytics.health.core.model.domain.sync.CompleteTypeScan
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.OxygenSaturationInput
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import app.readylytics.health.core.model.domain.sync.ScanIdentity
import app.readylytics.health.core.model.domain.sync.ScanStagingStore
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.sync.SleepSessionInput
import app.readylytics.health.core.model.domain.sync.SleepStageInput
import app.readylytics.health.core.model.domain.sync.StepRecordInput
import app.readylytics.health.core.model.domain.sync.TypeScanState
import app.readylytics.health.core.model.domain.sync.Vo2MaxInput
import app.readylytics.health.core.model.domain.sync.WeightInput
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import app.readylytics.health.core.model.domain.sync.mappers.HeartRateMapper
import app.readylytics.health.core.model.domain.sync.mappers.HrvMapper
import app.readylytics.health.core.model.domain.sync.mappers.SleepDataMapper
import app.readylytics.health.core.model.domain.sync.mappers.WorkoutMapper
import app.readylytics.health.core.model.domain.util.logD
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/**
 * Reads one Health Connect window, maps + device-filters it, and upserts every record type into
 * Room. Shared by the recent-window [DailySyncUseCase] and the chunked [ResyncRangeUseCase], so
 * both flows ingest through identical mapping/filtering logic.
 *
 * Sleep/exercise sessions and low-cardinality vitals are fetched and persisted up front (small,
 * bounded volume) -- sessions are the one exception kept whole, since HR/HRV page tagging and
 * workout metrics both need the complete, unfiltered session list before any sample page arrives.
 * Heart-rate, HRV, and steps -- the types that can reach into the millions (HR/HRV) or tens of
 * thousands (steps) for a dense chunk -- are streamed page-by-page via [HealthConnectRepository]'s
 * paged reads (HC-001): each page is tagged against this window's already-known sessions (HR/HRV
 * only -- steps carries no session relationship) and persisted immediately, so at most one Health
 * Connect page of samples is ever held in memory at once. A scan only reaches
 * [app.readylytics.health.core.model.domain.sync.TypeScanState.COMPLETE] once its *entire* paged
 * read succeeds; a denied or failed page leaves it incomplete so `StagedDeletionReconciler` never
 * deletes rows against a partial id set (HC-005). Workouts are persisted with zero metrics at this
 * point (mirroring the changes-path pattern in `HealthChangeSynchronizerImpl`, HC-004); the
 * post-ingestion `SessionLinkReconciler.recomputeWorkouts` pass -- which both sync flows always run
 * immediately after ingestion, before any walk-forward recompute reads workout data -- fills in the
 * real TRIMP/zone-minutes once every HR sample in range has been streamed and tagged.
 *
 * [staging] is deliberately required, with no default: a heap-backed store would silently
 * reintroduce PERF-001 and stop `StagedDeletionReconciler` from ever seeing a scan reach
 * `COMPLETE`, so losing the `RoomScanStagingStore` Hilt binding must fail wiring, not degrade.
 */
@Singleton
class HealthIngestionCoordinator
    @Inject
    constructor(
        private val hcRepo: HealthConnectRepository,
        private val healthIngestionStore: HealthIngestionStore,
        private val staging: ScanStagingStore,
    ) {
        private val streamer: HeartSampleStreamer = HeartSampleStreamer(hcRepo, healthIngestionStore, staging)

        suspend fun ingestWindow(
            windowStart: Instant,
            windowEnd: Instant,
            prefs: UserPreferences,
            scanIdentity: ScanIdentity = ScanIdentities.daily(windowStart),
            resumeHrScan: Boolean = false,
            resumeHrvScan: Boolean = false,
            windowBudgetMs: Long = 3 * 60_000L,
            onProgress: ((phase: ResyncPhase, current: Int, total: Int) -> Unit)? = null,
            hrStartPageToken: String? = null,
            hrvStartPageToken: String? = null,
            onTokenUpdated: (suspend (hrToken: String?, hrvToken: String?) -> Unit)? = null,
            reconcileDeletions: Boolean = RECONCILE_DELETIONS,
        ): IngestionWindowResult {
            return try {
                ingestWindowWithinBudget(
                    IngestWindowParams(
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        prefs = prefs,
                        scanIdentity = scanIdentity,
                        resumeHrScan = resumeHrScan,
                        resumeHrvScan = resumeHrvScan,
                        windowBudgetMs = windowBudgetMs,
                        onProgress = onProgress,
                        hrStartPageToken = hrStartPageToken,
                        hrvStartPageToken = hrvStartPageToken,
                        onTokenUpdated = onTokenUpdated,
                        reconcileDeletions = reconcileDeletions,
                    ),
                )
            } catch (e: TimeoutCancellationException) {
                // Not a CancellationException from here on -- HC-002: callers (ResyncRangeUseCase)
                // must be able to tell "this window is too dense for its budget" apart from
                // cooperative cancellation, which they must never swallow.
                throw HealthConnectWindowTimeoutException(windowStart, windowEnd, e)
            }
        }

        /**
         * Fix for Task 4 review Finding 1: the daily-sync flow reuses [ScanIdentities.DAILY_RUN_ID]
         * every day with a new chunk id per invocation and -- unlike a historical run, which purges
         * its own chunk's staging right after that chunk completes -- never otherwise clears it, so
         * without this call the staging tables would grow one row-set per day, per bulk type,
         * forever. [DailySyncUseCase] calls this once its own chunk id(s) for the current run are
         * known, so every other `DAILY_SYNC` chunk -- i.e. every past day's leftovers -- is dropped.
         */
        suspend fun clearStaleDailyStaging(keepChunkIds: Set<String>) {
            staging.clearChunksOtherThan(ScanIdentities.DAILY_RUN_ID, keepChunkIds)
        }

        private suspend fun ingestWindowWithinBudget(params: IngestWindowParams): IngestionWindowResult {
            return withTimeout(params.windowBudgetMs) {
                val (rawRecords, sessionContext) =
                    fetchAndPersistBulkRecords(
                        params.windowStart,
                        params.windowEnd,
                        params.prefs,
                        params.retryBudget,
                    )
                streamAndPersistDenseRecords(params, sessionContext)
                val scans = stageBulkScans(params, rawRecords)
                val affectedRange =
                    if (params.reconcileDeletions) {
                        reconcileDeletions(params, scans)
                    } else {
                        null
                    }
                IngestionWindowResult(
                    affectedRange = affectedRange,
                    completedTypes = scans.mapTo(HashSet()) { it.type },
                    completedIntervalTypes = rawRecords.completedIntervalTypes,
                )
            }
        }

        private suspend fun fetchAndPersistBulkRecords(
            windowStart: Instant,
            windowEnd: Instant,
            prefs: UserPreferences,
            retryBudget: ReadRetryBudget,
        ): Pair<RawBulkRecords, IngestionSessionContext> {
            val raw = fetchBulkRecords(windowStart, windowEnd, retryBudget)
            val sleepSessions = raw.sleepSessions.dataOrEmpty()
            val exerciseRecords = raw.exerciseRecords.dataOrEmpty()
            val sleepInputs = sleepSessions.map { SleepDataMapper.mapSleepSession(it) }
            val workoutInputs = exerciseRecords.map { WorkoutMapper.mapExerciseSession(it) }
            val deviceByType = prefs.deviceByDataType
            fun deviceFor(type: HealthDataType): String? = deviceByType[type.name]?.takeIf { it.isNotBlank() }

            val filteredSleep =
                DeviceSourceFilter.filterToDevice(
                    sleepInputs,
                    deviceFor(HealthDataType.SLEEP),
                ) { it.deviceName }
            val filteredWorkouts =
                DeviceSourceFilter.filterToDevice(
                    workoutInputs,
                    deviceFor(HealthDataType.EXERCISE),
                ) { it.deviceName }

            val vitals = VitalsInputMapper.mapAndFilter(raw, prefs)
            val filteredSleepIds = filteredSleep.mapTo(HashSet()) { it.id }
            val allStages =
                sleepSessions
                    .flatMap {
                        SleepDataMapper.mapSleepSessionStages(it)
                    }.filter { it.sessionId in filteredSleepIds }
            val filteredVo2MaxRecords =
                DeviceSourceFilter.filterToDevice(
                    raw.vo2MaxRecords.dataOrEmpty(),
                    deviceFor(HealthDataType.VO2_MAX),
                ) { it.deviceName }

            healthIngestionStore.persist(
                buildBulkBatch(
                    filteredSleep = filteredSleep,
                    allStages = allStages,
                    filteredWorkouts = filteredWorkouts,
                    vitals = vitals,
                    vo2MaxRecords = filteredVo2MaxRecords,
                ),
            )
            logMaxVitalsListSizeInDebug(raw)

            return Pair(
                raw,
                IngestionSessionContext(
                    sleepInputs = sleepInputs,
                    workoutInputs = workoutInputs,
                ),
            )
        }

        // Each read is independent of the others' results, so they run concurrently instead of
        // sequentially -- total latency drops from the sum of all 8 round trips to their max. All 8
        // share one window-scoped ReadRetryBudget (HC-005/PERF-001) rather than each retrying
        // independently, so a provider under quota pressure can't be hit maxAttempts times per read.
        // Steps (dense) is streamed page-by-page alongside HR/HRV instead of bulk-fetched here --
        // see [streamAndPersistDenseRecords] (HC-001).
        private suspend fun fetchBulkRecords(
            windowStart: Instant,
            windowEnd: Instant,
            retryBudget: ReadRetryBudget,
        ): RawBulkRecords =
            coroutineScope {
                val sleepSessions =
                    async {
                        hcRepo.readSleepSessions(windowStart, windowEnd, retryScope = retryBudget)
                    }
                val exerciseRecords =
                    async {
                        hcRepo.readExerciseSessionsWithCompletion(
                            windowStart, windowEnd, retryScope = retryBudget,
                        )
                    }
                val weightRecords =
                    async {
                        hcRepo.readWeightRecords(windowStart, windowEnd, retryScope = retryBudget)
                    }
                val bodyFatRecords =
                    async {
                        hcRepo.readBodyFatRecords(windowStart, windowEnd, retryScope = retryBudget)
                    }
                val bloodPressureRecords =
                    async {
                        hcRepo.readBloodPressureRecords(windowStart, windowEnd, retryScope = retryBudget)
                    }
                val spo2Records =
                    async {
                        hcRepo.readOxygenSaturationRecords(windowStart, windowEnd, retryScope = retryBudget)
                    }
                val bodyTemperatureRecords =
                    async {
                        hcRepo.readBodyTemperatureRecords(windowStart, windowEnd, retryScope = retryBudget)
                    }
                val vo2MaxRecords =
                    async {
                        if (hcRepo.hasVo2MaxPermission()) {
                            hcRepo.readVo2MaxRecords(windowStart, windowEnd, retryScope = retryBudget)
                        } else {
                            ReadOutcome.Denied
                        }
                    }
                RawBulkRecords(
                    sleepSessions = sleepSessions.await(),
                    exerciseRecords = exerciseRecords.await().sessions,
                    completedIntervalTypes = exerciseRecords.await().completedIntervalTypes,
                    weightRecords = weightRecords.await(),
                    bodyFatRecords = bodyFatRecords.await(),
                    bloodPressureRecords = bloodPressureRecords.await(),
                    spo2Records = spo2Records.await(),
                    bodyTemperatureRecords = bodyTemperatureRecords.await(),
                    vo2MaxRecords = vo2MaxRecords.await(),
                )
            }

        private suspend fun streamAndPersistDenseRecords(
            params: IngestWindowParams,
            sessionContext: IngestionSessionContext,
        ) {
            var pagesIngested = 0
            val deviceByType = params.prefs.deviceByDataType
            fun deviceFor(type: HealthDataType): String? = deviceByType[type.name]?.takeIf { it.isNotBlank() }

            streamer.streamHeartRate(
                params = params,
                sessionContext = sessionContext,
                device = deviceFor(HealthDataType.HEART_RATE),
            ) {
                pagesIngested++
                params.onProgress?.invoke(ResyncPhase.INGEST, pagesIngested, 0)
            }

            streamer.streamHrv(
                params = params,
                sessionContext = sessionContext,
                device = deviceFor(HealthDataType.HRV),
            ) {
                pagesIngested++
                params.onProgress?.invoke(ResyncPhase.INGEST, pagesIngested, 0)
            }

            streamSteps(params, device = deviceFor(HealthDataType.STEPS)) {
                pagesIngested++
                params.onProgress?.invoke(ResyncPhase.INGEST, pagesIngested, 0)
            }
        }

        /**
         * Steps can be as dense as HR/HRV (a continuously-recorded day reaches tens of thousands of
         * rows), so it is streamed page-by-page the same way (HC-001) rather than bulk-fetched with
         * the low-cardinality vitals in [fetchBulkRecords]. Unlike HR/HRV, steps rows carry no
         * session relationship, so each page is staged (raw, unfiltered ids -- WP-18) and persisted
         * (device-filtered) directly, with no mapper/session lookup involved. The scan is only ever
         * marked COMPLETE once the whole paged read succeeds (HC-005): a denied or failed page leaves
         * it INCOMPLETE, so [HealthIngestionStore.reconcileWindow] never deletes rows against a
         * partial id set.
         */
        private suspend fun streamSteps(
            params: IngestWindowParams,
            device: String?,
            onPageDone: () -> Unit,
        ): ReadOutcome<Unit> {
            staging.beginTypeScan(params.scanIdentity, HealthDataType.STEPS, resume = false)
            var persisted = 0
            val outcome =
                hcRepo.readStepsRecordsPaged(
                    from = params.windowStart,
                    to = params.windowEnd,
                    retryScope = params.retryBudget,
                ) { page ->
                    staging.stageIds(params.scanIdentity, HealthDataType.STEPS, page.map { it.id })
                    val filtered = DeviceSourceFilter.filterToDevice(page, device) { it.deviceName }
                    healthIngestionStore.persist(stepsOnlyBatch(filtered))
                    persisted += filtered.size
                    onPageDone()
                }
            if (outcome is ReadOutcome.Available) {
                staging.markTypeScanComplete(params.scanIdentity, HealthDataType.STEPS)
            }
            logD(TELEMETRY_TAG) { "[INGESTION] steps records persisted: $persisted" }
            return outcome
        }

        private suspend fun reconcileDeletions(
            params: IngestWindowParams,
            scans: List<CompleteTypeScan>,
        ): ScoreInvalidation.AffectedRange? {
            val zoneId = params.prefs.scoringZone()

            val results =
                scans.associate { scan ->
                    scan.type to healthIngestionStore.reconcileWindow(scan, zoneId)
                }

            logD(TELEMETRY_TAG) {
                val sleepMod = results[HealthDataType.SLEEP] != null
                val workMod = results[HealthDataType.EXERCISE] != null
                val hrMod = results[HealthDataType.HEART_RATE] != null || results[HealthDataType.HRV] != null
                "[INGESTION] reconciled deletes in chunk: " +
                    "sleep=${if (sleepMod) "yes" else "0"} " +
                    "workout=${if (workMod) "yes" else "0"} " +
                    "hr_sources=${if (hrMod) "yes" else "0"}"
            }

            return ScoreInvalidation.merge(results.values)
        }

        private suspend fun stageBulkScans(
            params: IngestWindowParams,
            raw: RawBulkRecords,
        ): List<CompleteTypeScan> {
            val startMs = params.windowStart.toEpochMilli()
            val endExclusiveMs = params.windowEnd.toEpochMilli()
            fun deviceFor(type: HealthDataType) = params.prefs.deviceByDataType[type.name].orEmpty()

            suspend fun MutableList<CompleteTypeScan>.stage(
                outcome: ReadOutcome<List<String>>,
                type: HealthDataType,
            ) {
                staging.beginTypeScan(params.scanIdentity, type, resume = false)
                if (outcome !is ReadOutcome.Available) return
                staging.stageIds(params.scanIdentity, type, outcome.data)
                staging.markTypeScanComplete(params.scanIdentity, type)
                add(
                    CompleteTypeScan(
                        type = type,
                        windowStartMs = startMs,
                        windowEndExclusiveMs = endExclusiveMs,
                        sourceSelectionId = deviceFor(type),
                        scan = params.scanIdentity,
                    ),
                )
            }

            return buildList {
                stage(raw.sleepSessions.toIds { it.id }, HealthDataType.SLEEP)
                stage(raw.exerciseRecords.toIds { it.id }, HealthDataType.EXERCISE)
                // DB-001: vitals rows are keyed "<hcId>_<timestampMs>", so the staged identity is
                // that persisted row id -- no substringBefore('_') parsing anywhere, and a record
                // whose timestamp moved converges instead of leaving a stale duplicate behind.
                stage(raw.weightRecords.toIds { "${it.id}_${it.time.toEpochMilli()}" }, HealthDataType.WEIGHT)
                stage(raw.bodyFatRecords.toIds { "${it.id}_${it.time.toEpochMilli()}" }, HealthDataType.BODY_FAT)
                stage(
                    raw.bloodPressureRecords.toIds { "${it.id}_${it.time.toEpochMilli()}" },
                    HealthDataType.BLOOD_PRESSURE,
                )
                stage(raw.spo2Records.toIds { "${it.id}_${it.time.toEpochMilli()}" }, HealthDataType.OXYGEN_SATURATION)
                stage(
                    raw.bodyTemperatureRecords.toIds { "${it.id}_${it.time.toEpochMilli()}" },
                    HealthDataType.BODY_TEMPERATURE,
                )
                stage(raw.vo2MaxRecords.toIds { it.id }, HealthDataType.VO2_MAX)
                addPagedScans(params, startMs, endExclusiveMs, ::deviceFor)
            }
        }

        /**
         * HEART_RATE, HRV, and STEPS are streamed/staged page-by-page (not bulk-staged by [stage])
         * via [streamAndPersistDenseRecords], so their scan only reaches [TypeScanState.COMPLETE]
         * once the whole paged read has succeeded -- this just reads that already-settled state.
         */
        private suspend fun MutableList<CompleteTypeScan>.addPagedScans(
            params: IngestWindowParams,
            startMs: Long,
            endExclusiveMs: Long,
            deviceFor: (HealthDataType) -> String,
        ) {
            for (type in PAGED_DENSE_TYPES) {
                if (staging.stateOf(params.scanIdentity, type) == TypeScanState.COMPLETE) {
                    add(
                        CompleteTypeScan(
                            type = type,
                            windowStartMs = startMs,
                            windowEndExclusiveMs = endExclusiveMs,
                            sourceSelectionId = deviceFor(type),
                            scan = params.scanIdentity,
                        ),
                    )
                }
            }
        }

        companion object {
            const val RECONCILE_DELETIONS = true
            private const val TELEMETRY_TAG = "ResyncTelemetry"

            /** Record types streamed/staged page-by-page rather than bulk-staged (HC-001). */
            private val PAGED_DENSE_TYPES =
                listOf(HealthDataType.HEART_RATE, HealthDataType.HRV, HealthDataType.STEPS)
        }
    }

data class IngestionWindowResult(
    val affectedRange: ScoreInvalidation.AffectedRange?,
    val completedTypes: Set<HealthDataType> = emptySet(),
    val completedIntervalTypes: Set<String> = emptySet(),
)

// Steps is deliberately absent -- it is a dense type streamed/staged page-by-page alongside HR/HRV
// (see [HealthIngestionCoordinator.streamSteps]), never bulk-fetched or held in full here (HC-001).
internal data class RawBulkRecords(
    val completedIntervalTypes: Set<String> = emptySet(),
    val sleepSessions: ReadOutcome<List<DomainSleepSessionRecord>>,
    val exerciseRecords: ReadOutcome<List<DomainExerciseSessionRecord>>,
    val weightRecords: ReadOutcome<List<DomainWeightRecord>>,
    val bodyFatRecords: ReadOutcome<List<DomainBodyFatRecord>>,
    val bloodPressureRecords: ReadOutcome<List<DomainBloodPressureRecord>>,
    val spo2Records: ReadOutcome<List<DomainOxygenSaturationRecord>>,
    val bodyTemperatureRecords: ReadOutcome<List<DomainBodyTemperatureRecord>>,
    val vo2MaxRecords: ReadOutcome<List<DomainVo2MaxRecord>> = ReadOutcome.Available(emptyList()),
)


internal data class IngestWindowParams(
    val windowStart: Instant,
    val windowEnd: Instant,
    val prefs: UserPreferences,
    val scanIdentity: ScanIdentity,
    val resumeHrScan: Boolean,
    val resumeHrvScan: Boolean,
    val windowBudgetMs: Long,
    val onProgress: ((phase: ResyncPhase, current: Int, total: Int) -> Unit)?,
    val hrStartPageToken: String?,
    val hrvStartPageToken: String?,
    val onTokenUpdated: (suspend (hrToken: String?, hrvToken: String?) -> Unit)?,
    val reconcileDeletions: Boolean,
    // HC-005/PERF-001: one bounded retry budget shared by every read of this window (the 8 bulk
    // fetchBulkRecords reads plus the HR/HRV/steps paged reads), replacing the old per-read +
    // outer-window nested retryWithBackoff wrapping. Always fresh per ingestWindow call.
    val retryBudget: ReadRetryBudget = ReadRetryBudget(),
)

internal data class IngestionSessionContext(
    val sleepInputs: List<SleepSessionInput>,
    val workoutInputs: List<WorkoutInput>,
)

/** Debug-only diagnostic: catches a "low-cardinality" vitals type unexpectedly turning dense. */
private fun logMaxVitalsListSizeInDebug(raw: RawBulkRecords) {
    if (!app.readylytics.health.core.healthconnect.BuildConfig.DEBUG) return
    val maxVitalsListSize =
        listOf(
            raw.sleepSessions.dataOrEmpty().size,
            raw.exerciseRecords.dataOrEmpty().size,
            raw.weightRecords.dataOrEmpty().size,
            raw.bodyFatRecords.dataOrEmpty().size,
            raw.bloodPressureRecords.dataOrEmpty().size,
            raw.spo2Records.dataOrEmpty().size,
            raw.bodyTemperatureRecords.dataOrEmpty().size,
            raw.vo2MaxRecords.dataOrEmpty().size,
        ).maxOrNull() ?: 0
    logD("ResyncTelemetry") { "[INGESTION] max low-cardinality vitals list size this chunk: $maxVitalsListSize" }
}

private fun buildBulkBatch(
    filteredSleep: List<SleepSessionInput>,
    allStages: List<SleepStageInput>,
    filteredWorkouts: List<WorkoutInput>,
    vitals: FilteredVitals,
    vo2MaxRecords: List<app.readylytics.health.core.model.domain.model.DomainVo2MaxRecord>,
): HealthIngestionBatch =
    HealthIngestionBatch(
        sleepSessions = filteredSleep,
        sleepStages = allStages,
        heartRateSamples = emptyList(),
        hrvSamples = emptyList(),
        workouts = filteredWorkouts,
        weights = vitals.weights,
        bodyFatSamples = vitals.bodyFatSamples,
        bloodPressureSamples = vitals.bloodPressureSamples,
        oxygenSaturationSamples = vitals.oxygenSaturationSamples,
        bodyTemperatureSamples = vitals.bodyTemperatureSamples,
        stepRecords = emptyList(),
        vo2MaxSamples =
            vo2MaxRecords.map { record ->
                Vo2MaxInput(
                    id = record.id,
                    timestampMs = record.time.toEpochMilli(),
                    vo2Max = record.vo2MillilitersPerMinuteKilogram.toFloat(),
                    measurementMethod = record.measurementMethod,
                    deviceName = record.deviceName,
                )
            },
    )

/**
 * Minimal [HealthIngestionBatch] carrying only one page of steps rows -- used by
 * [HealthIngestionCoordinator.streamSteps] so each page commits independently instead of waiting
 * for the whole window's steps to be read (HC-001).
 */
private fun stepsOnlyBatch(records: List<DomainStepsRecord>): HealthIngestionBatch =
    HealthIngestionBatch(
        sleepSessions = emptyList(),
        sleepStages = emptyList(),
        workouts = emptyList(),
        weights = emptyList(),
        bodyFatSamples = emptyList(),
        bloodPressureSamples = emptyList(),
        oxygenSaturationSamples = emptyList(),
        bodyTemperatureSamples = emptyList(),
        stepRecords =
            records.map { record ->
                StepRecordInput(
                    id = record.id,
                    startTime = record.startTime.toEpochMilli(),
                    endTime = record.endTime.toEpochMilli(),
                    count = record.count,
                    deviceName = record.deviceName,
                )
            },
    )

private inline fun <T> ReadOutcome<List<T>>.toIds(crossinline idSelector: (T) -> String): ReadOutcome<List<String>> =
    when (this) {
        is ReadOutcome.Available -> ReadOutcome.Available(data.map { idSelector(it) })
        ReadOutcome.Denied -> ReadOutcome.Denied
        ReadOutcome.Unsupported -> ReadOutcome.Unsupported
    }
