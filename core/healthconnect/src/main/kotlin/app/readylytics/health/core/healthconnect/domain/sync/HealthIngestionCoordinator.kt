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
import app.readylytics.health.core.model.domain.sync.*
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.sync.mappers.SleepDataMapper
import app.readylytics.health.core.model.domain.sync.mappers.WorkoutMapper
import app.readylytics.health.core.model.domain.sync.mappers.HeartRateMapper
import app.readylytics.health.core.model.domain.sync.mappers.HrvMapper
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
 * Sessions and low-volume record types are fetched and persisted up front (small, bounded volume).
 * Heart-rate and HRV samples -- the types that can reach into the millions for a dense chunk -- are
 * streamed page-by-page via [HealthConnectRepository]'s paged reads (HC-001): each page is tagged
 * against this window's already-known sessions and persisted immediately, so at most one Health
 * Connect page of samples is ever held in memory at once. Workouts are persisted with zero metrics
 * at this point (mirroring the changes-path pattern in `HealthChangeSynchronizerImpl`, HC-004); the
 * post-ingestion `SessionLinkReconciler.recomputeWorkouts` pass -- which both sync flows always run
 * immediately after ingestion, before any walk-forward recompute reads workout data -- fills in the
 * real TRIMP/zone-minutes once every HR sample in range has been streamed and tagged.
 */
@Singleton
class HealthIngestionCoordinator
    @Inject
    constructor(
        private val hcRepo: HealthConnectRepository,
        private val healthIngestionStore: HealthIngestionStore,
    ) {
        suspend fun ingestWindow(
            windowStart: Instant,
            windowEnd: Instant,
            prefs: UserPreferences,
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

        private suspend fun ingestWindowWithinBudget(params: IngestWindowParams): IngestionWindowResult {
            return withTimeout(params.windowBudgetMs) {
                val (rawRecords, sessionContext) =
                    fetchAndPersistBulkRecords(params.windowStart, params.windowEnd, params.prefs)
                val heartIds = streamAndPersistHeartSamples(params, sessionContext)
                val scans = collectCompleteTypeScans(params, rawRecords, heartIds)
                val affectedRange =
                    if (params.reconcileDeletions) {
                        reconcileDeletions(params, scans)
                    } else {
                        null
                    }
                IngestionWindowResult(
                    affectedRange = affectedRange,
                    completedTypes = scans.mapTo(HashSet()) { it.type },
                )
            }
        }

        private suspend fun fetchAndPersistBulkRecords(
            windowStart: Instant,
            windowEnd: Instant,
            prefs: UserPreferences,
        ): Pair<RawBulkRecords, IngestionSessionContext> {
            val raw = fetchBulkRecords(windowStart, windowEnd)
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

            val vitals = mapAndFilterVitals(raw, prefs)
            val filteredSleepIds = filteredSleep.mapTo(HashSet()) { it.id }
            val allStages =
                sleepSessions
                    .flatMap {
                        SleepDataMapper.mapSleepSessionStages(it)
                    }.filter { it.sessionId in filteredSleepIds }

            healthIngestionStore.persist(
                buildBulkBatch(
                    filteredSleep = filteredSleep,
                    allStages = allStages,
                    filteredWorkouts = filteredWorkouts,
                    vitals = vitals,
                    stepsRecords = raw.stepsRecords.dataOrEmpty(),
                    vo2MaxRecords = raw.vo2MaxRecords.dataOrEmpty(),
                ),
            )

            return Pair(
                raw,
                IngestionSessionContext(
                    sleepInputs = sleepInputs,
                    workoutInputs = workoutInputs,
                ),
            )
        }

        // Each read is independent of the others' results, so they run concurrently instead of
        // sequentially -- total latency drops from the sum of all 9 round trips to their max.
        private suspend fun fetchBulkRecords(
            windowStart: Instant,
            windowEnd: Instant,
        ): RawBulkRecords =
            coroutineScope {
                val sleepSessions = async { retryWithBackoff { hcRepo.readSleepSessions(windowStart, windowEnd) } }
                val exerciseRecords =
                    async {
                        retryWithBackoff {
                            hcRepo.readExerciseSessions(windowStart, windowEnd, includeDetails = true)
                        }
                    }
                val weightRecords = async { retryWithBackoff { hcRepo.readWeightRecords(windowStart, windowEnd) } }
                val bodyFatRecords =
                    async { retryWithBackoff { hcRepo.readBodyFatRecords(windowStart, windowEnd) } }
                val bloodPressureRecords =
                    async { retryWithBackoff { hcRepo.readBloodPressureRecords(windowStart, windowEnd) } }
                val spo2Records =
                    async { retryWithBackoff { hcRepo.readOxygenSaturationRecords(windowStart, windowEnd) } }
                val bodyTemperatureRecords =
                    async { retryWithBackoff { hcRepo.readBodyTemperatureRecords(windowStart, windowEnd) } }
                val stepsRecords = async { retryWithBackoff { hcRepo.readStepsRecords(windowStart, windowEnd) } }
                val vo2MaxRecords =
                    async {
                        if (hcRepo.hasVo2MaxPermission()) {
                            retryWithBackoff { hcRepo.readVo2MaxRecords(windowStart, windowEnd) }
                        } else {
                            ReadOutcome.Denied
                        }
                    }
                RawBulkRecords(
                    sleepSessions = sleepSessions.await(),
                    exerciseRecords = exerciseRecords.await(),
                    weightRecords = weightRecords.await(),
                    bodyFatRecords = bodyFatRecords.await(),
                    bloodPressureRecords = bloodPressureRecords.await(),
                    spo2Records = spo2Records.await(),
                    bodyTemperatureRecords = bodyTemperatureRecords.await(),
                    stepsRecords = stepsRecords.await(),
                    vo2MaxRecords = vo2MaxRecords.await(),
                )
            }

        private fun mapAndFilterVitals(
            raw: RawBulkRecords,
            prefs: UserPreferences,
        ): FilteredVitals {
            val (weights, bodyFat) = mapAndFilterBodyComp(raw, prefs)
            val (bp, spo2, temp) = mapAndFilterCardioVitals(raw, prefs)
            return FilteredVitals(
                weights = weights,
                bodyFatSamples = bodyFat,
                bloodPressureSamples = bp,
                oxygenSaturationSamples = spo2,
                bodyTemperatureSamples = temp,
            )
        }

        private fun mapAndFilterBodyComp(
            raw: RawBulkRecords,
            prefs: UserPreferences,
        ): Pair<List<WeightInput>, List<BodyFatInput>> {
            val deviceByType = prefs.deviceByDataType
            fun deviceFor(type: HealthDataType): String? = deviceByType[type.name]?.takeIf { it.isNotBlank() }

            val weightRecords = raw.weightRecords.dataOrEmpty()
            val bodyFatRecords = raw.bodyFatRecords.dataOrEmpty()
            val weightInputs =
                weightRecords.map {
                    WeightInput(
                        id = "${it.id}_${it.time.toEpochMilli()}",
                        timestampMs = it.time.toEpochMilli(),
                        weightKg = it.weightKg,
                        deviceName = it.deviceName,
                        sourceId = it.id,
                    )
                }
            val bodyFatInputs =
                bodyFatRecords.map {
                    BodyFatInput(
                        id = "${it.id}_${it.time.toEpochMilli()}",
                        timestampMs = it.time.toEpochMilli(),
                        bodyFatPercent = it.percentage,
                        deviceName = it.deviceName,
                        sourceId = it.id,
                    )
                }

            val filteredWeights =
                DeviceSourceFilter.filterToDevice(weightInputs, deviceFor(HealthDataType.WEIGHT)) { it.deviceName }
            val filteredBodyFat =
                DeviceSourceFilter.filterToDevice(bodyFatInputs, deviceFor(HealthDataType.BODY_FAT)) { it.deviceName }
            return Pair(filteredWeights, filteredBodyFat)
        }

        private fun mapAndFilterCardioVitals(
            raw: RawBulkRecords,
            prefs: UserPreferences,
        ): Triple<List<BloodPressureInput>, List<OxygenSaturationInput>, List<BodyTemperatureInput>> {
            val deviceByType = prefs.deviceByDataType
            fun deviceFor(type: HealthDataType): String? = deviceByType[type.name]?.takeIf { it.isNotBlank() }

            val bpRecords = raw.bloodPressureRecords.dataOrEmpty()
            val spo2Records = raw.spo2Records.dataOrEmpty()
            val tempRecords = raw.bodyTemperatureRecords.dataOrEmpty()
            val bpInputs =
                bpRecords.map {
                    BloodPressureInput(
                        id = "${it.id}_${it.time.toEpochMilli()}",
                        timestampMs = it.time.toEpochMilli(),
                        systolicMmHg = it.systolicMmHg,
                        diastolicMmHg = it.diastolicMmHg,
                        deviceName = it.deviceName,
                        sourceId = it.id,
                    )
                }
            val spo2Inputs =
                spo2Records.map {
                    OxygenSaturationInput(
                        id = "${it.id}_${it.time.toEpochMilli()}",
                        timestampMs = it.time.toEpochMilli(),
                        percentage = it.percentage,
                        deviceName = it.deviceName,
                        sourceId = it.id,
                    )
                }
            val tempInputs =
                tempRecords.map {
                    BodyTemperatureInput(
                        id = "${it.id}_${it.time.toEpochMilli()}",
                        timestampMs = it.time.toEpochMilli(),
                        celsius = it.celsius,
                        deviceName = it.deviceName,
                        sourceId = it.id,
                    )
                }

            val filteredBp =
                DeviceSourceFilter.filterToDevice(bpInputs, deviceFor(HealthDataType.BLOOD_PRESSURE)) { it.deviceName }
            val filteredSpo2 =
                DeviceSourceFilter.filterToDevice(
                    spo2Inputs,
                    deviceFor(HealthDataType.OXYGEN_SATURATION),
                ) { it.deviceName }
            val filteredTemp =
                DeviceSourceFilter.filterToDevice(
                    tempInputs,
                    deviceFor(HealthDataType.BODY_TEMPERATURE),
                ) { it.deviceName }
            return Triple(filteredBp, filteredSpo2, filteredTemp)
        }



        private suspend fun streamAndPersistHeartSamples(
            params: IngestWindowParams,
            sessionContext: IngestionSessionContext,
        ): HeartIds {
            var pagesIngested = 0
            val deviceByType = params.prefs.deviceByDataType
            fun deviceFor(type: HealthDataType): String? = deviceByType[type.name]?.takeIf { it.isNotBlank() }

            val (hrIds, hrSampleCount) =
                streamHeartRateSamples(
                    hcRepo = hcRepo,
                    healthIngestionStore = healthIngestionStore,
                    params = params,
                    sessionContext = sessionContext,
                    device = deviceFor(HealthDataType.HEART_RATE),
                ) {
                    pagesIngested++
                    params.onProgress?.invoke(ResyncPhase.INGEST, pagesIngested, 0)
                }

            val (hrvIds, hrvSampleCount) =
                streamHrvSamples(
                    hcRepo = hcRepo,
                    healthIngestionStore = healthIngestionStore,
                    params = params,
                    sessionContext = sessionContext,
                    device = deviceFor(HealthDataType.HRV),
                ) {
                    pagesIngested++
                    params.onProgress?.invoke(ResyncPhase.INGEST, pagesIngested, 0)
                }

            logD("HealthIngestionCoordinator") {
                "Streamed samples: hr=$hrSampleCount hrv=$hrvSampleCount"
            }

            return HeartIds(hr = hrIds, hrv = hrvIds)
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

        private fun collectCompleteTypeScans(
            params: IngestWindowParams,
            raw: RawBulkRecords,
            heartIds: HeartIds,
        ): List<CompleteTypeScan> {
            val startMs = params.windowStart.toEpochMilli()
            val endExclusiveMs = params.windowEnd.toEpochMilli()
            fun deviceFor(type: HealthDataType) = params.prefs.deviceByDataType[type.name].orEmpty()

            fun MutableList<CompleteTypeScan>.addScan(outcome: ReadOutcome<Set<String>>, type: HealthDataType) {
                if (outcome is ReadOutcome.Available) {
                    add(CompleteTypeScan(type, startMs, endExclusiveMs, deviceFor(type), outcome.data))
                }
            }

            return buildList {
                addScan(raw.sleepSessions.toIds { it.id }, HealthDataType.SLEEP)
                addScan(raw.exerciseRecords.toIds { it.id }, HealthDataType.EXERCISE)
                addScan(heartIds.hr, HealthDataType.HEART_RATE)
                addScan(heartIds.hrv, HealthDataType.HRV)
                addScan(raw.weightRecords.toIds { it.id }, HealthDataType.WEIGHT)
                addScan(raw.bodyFatRecords.toIds { it.id }, HealthDataType.BODY_FAT)
                addScan(raw.bloodPressureRecords.toIds { it.id }, HealthDataType.BLOOD_PRESSURE)
                addScan(raw.spo2Records.toIds { it.id }, HealthDataType.OXYGEN_SATURATION)
                addScan(raw.bodyTemperatureRecords.toIds { it.id }, HealthDataType.BODY_TEMPERATURE)
                addScan(raw.stepsRecords.toIds { it.id }, HealthDataType.STEPS)
            }
        }

        companion object {
            const val RECONCILE_DELETIONS = true
            private const val TELEMETRY_TAG = "ResyncTelemetry"
        }
    }

data class IngestionWindowResult(
    val affectedRange: ScoreInvalidation.AffectedRange?,
    val completedTypes: Set<HealthDataType> = emptySet(),
)

private data class HeartIds(
    val hr: ReadOutcome<Set<String>>,
    val hrv: ReadOutcome<Set<String>>,
)

private data class RawBulkRecords(
    val sleepSessions: ReadOutcome<List<DomainSleepSessionRecord>>,
    val exerciseRecords: ReadOutcome<List<DomainExerciseSessionRecord>>,
    val weightRecords: ReadOutcome<List<DomainWeightRecord>>,
    val bodyFatRecords: ReadOutcome<List<DomainBodyFatRecord>>,
    val bloodPressureRecords: ReadOutcome<List<DomainBloodPressureRecord>>,
    val spo2Records: ReadOutcome<List<DomainOxygenSaturationRecord>>,
    val bodyTemperatureRecords: ReadOutcome<List<DomainBodyTemperatureRecord>>,
    val stepsRecords: ReadOutcome<List<DomainStepsRecord>>,
    val vo2MaxRecords: ReadOutcome<List<DomainVo2MaxRecord>> = ReadOutcome.Available(emptyList()),
)

private data class FilteredVitals(
    val weights: List<WeightInput>,
    val bodyFatSamples: List<BodyFatInput>,
    val bloodPressureSamples: List<BloodPressureInput>,
    val oxygenSaturationSamples: List<OxygenSaturationInput>,
    val bodyTemperatureSamples: List<BodyTemperatureInput>,
)

private data class IngestWindowParams(
    val windowStart: Instant,
    val windowEnd: Instant,
    val prefs: UserPreferences,
    val windowBudgetMs: Long,
    val onProgress: ((phase: ResyncPhase, current: Int, total: Int) -> Unit)?,
    val hrStartPageToken: String?,
    val hrvStartPageToken: String?,
    val onTokenUpdated: (suspend (hrToken: String?, hrvToken: String?) -> Unit)?,
    val reconcileDeletions: Boolean,
)

private data class IngestionSessionContext(
    val sleepInputs: List<SleepSessionInput>,
    val workoutInputs: List<WorkoutInput>,
)

private fun buildBulkBatch(
    filteredSleep: List<SleepSessionInput>,
    allStages: List<SleepStageInput>,
    filteredWorkouts: List<WorkoutInput>,
    vitals: FilteredVitals,
    stepsRecords: List<app.readylytics.health.core.model.domain.model.DomainStepsRecord>,
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
        stepRecords =
            stepsRecords.map { record ->
                StepRecordInput(
                    id = record.id,
                    startTime = record.startTime.toEpochMilli(),
                    endTime = record.endTime.toEpochMilli(),
                    count = record.count,
                    deviceName = record.deviceName,
                )
            },
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

private suspend fun streamHeartRateSamples(
    hcRepo: HealthConnectRepository,
    healthIngestionStore: HealthIngestionStore,
    params: IngestWindowParams,
    sessionContext: IngestionSessionContext,
    device: String?,
    onPageDone: () -> Unit,
): Pair<ReadOutcome<Set<String>>, Int> {
    val hrIds = mutableSetOf<String>()
    var hrSampleCount = 0
    val outcome =
        hcRepo.readHeartRateSamplesPaged(
            from = params.windowStart,
            to = params.windowEnd,
            startPageToken = params.hrStartPageToken,
        ) { page, nextToken ->
            logD("HealthSync.Ingest") { "HR page size=${page.size}" }
            hrIds.addAll(page.map { it.id })
            val hrSources =
                HeartRateMapper.mapToInputs(
                    page,
                    sessionContext.sleepInputs,
                    sessionContext.workoutInputs,
                )
            val filteredHr =
                hrSources.map { source ->
                    source.copy(
                        rows = DeviceSourceFilter.filterToDevice(source.rows, device) { it.deviceName },
                    )
                }
            healthIngestionStore.replaceHeartRateSources(filteredHr)
            hrSampleCount += filteredHr.sumOf { it.rows.size }
            onPageDone()
            params.onTokenUpdated?.invoke(nextToken, null)
        }
    val readOutcome =
        when (outcome) {
            is ReadOutcome.Available -> ReadOutcome.Available(hrIds)
            ReadOutcome.Denied -> ReadOutcome.Denied
            ReadOutcome.Unsupported -> ReadOutcome.Unsupported
        }
    return readOutcome to hrSampleCount
}

private suspend fun streamHrvSamples(
    hcRepo: HealthConnectRepository,
    healthIngestionStore: HealthIngestionStore,
    params: IngestWindowParams,
    sessionContext: IngestionSessionContext,
    device: String?,
    onPageDone: () -> Unit,
): Pair<ReadOutcome<Set<String>>, Int> {
    val hrvIds = mutableSetOf<String>()
    var hrvSampleCount = 0
    val outcome =
        hcRepo.readHrvSamplesPaged(
            from = params.windowStart,
            to = params.windowEnd,
            startPageToken = params.hrvStartPageToken,
        ) { page, nextToken ->
            logD("HealthSync.Ingest") { "HRV page size=${page.size}" }
            hrvIds.addAll(page.map { it.id })
            val hrvSources =
                HrvMapper.mapToInputs(
                    page,
                    sessionContext.sleepInputs,
                )
            val filteredHrv =
                hrvSources.map { source ->
                    source.copy(
                        rows = DeviceSourceFilter.filterToDevice(source.rows, device) { it.deviceName },
                    )
                }
            healthIngestionStore.replaceHrvSources(filteredHrv)
            hrvSampleCount += filteredHrv.sumOf { it.rows.size }
            onPageDone()
            params.onTokenUpdated?.invoke(null, nextToken)
        }
    val readOutcome =
        when (outcome) {
            is ReadOutcome.Available -> ReadOutcome.Available(hrvIds)
            ReadOutcome.Denied -> ReadOutcome.Denied
            ReadOutcome.Unsupported -> ReadOutcome.Unsupported
        }
    return readOutcome to hrvSampleCount
}

private inline fun <T> ReadOutcome<List<T>>.toIds(crossinline idSelector: (T) -> String): ReadOutcome<Set<String>> =
    when (this) {
        is ReadOutcome.Available -> ReadOutcome.Available(data.mapTo(HashSet()) { idSelector(it) })
        ReadOutcome.Denied -> ReadOutcome.Denied
        ReadOutcome.Unsupported -> ReadOutcome.Unsupported
    }
