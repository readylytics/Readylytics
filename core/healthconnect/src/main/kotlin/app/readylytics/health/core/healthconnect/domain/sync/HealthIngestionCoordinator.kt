package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.HealthConnectWindowTimeoutException
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
        ): ScoreInvalidation.AffectedRange? {
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

        private suspend fun ingestWindowWithinBudget(params: IngestWindowParams): ScoreInvalidation.AffectedRange? {
            return withTimeout(params.windowBudgetMs) {
                val (rawRecords, sessionContext) =
                    fetchAndPersistBulkRecords(params.windowStart, params.windowEnd, params.prefs)
                val heartIds = streamAndPersistHeartSamples(params, sessionContext)
                if (params.reconcileDeletions) {
                    reconcileDeletions(params, rawRecords, heartIds)
                } else {
                    null
                }
            }
        }

        private suspend fun fetchAndPersistBulkRecords(
            windowStart: Instant,
            windowEnd: Instant,
            prefs: UserPreferences,
        ): Pair<RawBulkRecords, IngestionSessionContext> {
            val raw = fetchBulkRecords(windowStart, windowEnd)
            val sleepInputs = raw.sleepSessions.map { SleepDataMapper.mapSleepSession(it) }
            val workoutInputs = raw.exerciseRecords.map { WorkoutMapper.mapExerciseSession(it) }
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
                raw.sleepSessions
                    .flatMap {
                        SleepDataMapper.mapSleepSessionStages(it)
                    }.filter { it.sessionId in filteredSleepIds }

            healthIngestionStore.persist(
                buildBulkBatch(
                    filteredSleep = filteredSleep,
                    allStages = allStages,
                    filteredWorkouts = filteredWorkouts,
                    vitals = vitals,
                    stepsRecords = raw.stepsRecords,
                    vo2MaxRecords = raw.vo2MaxRecords,
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

        private suspend fun fetchBulkRecords(
            windowStart: Instant,
            windowEnd: Instant,
        ): RawBulkRecords {
            val sleepSessions = retryWithBackoff { hcRepo.readSleepSessions(windowStart, windowEnd) }
            val exerciseRecords =
                retryWithBackoff {
                    hcRepo.readExerciseSessions(windowStart, windowEnd, includeDetails = true)
                }
            val weightRecords = retryWithBackoff { hcRepo.readWeightRecords(windowStart, windowEnd) }
            val bodyFatRecords = retryWithBackoff { hcRepo.readBodyFatRecords(windowStart, windowEnd) }
            val bloodPressureRecords =
                retryWithBackoff { hcRepo.readBloodPressureRecords(windowStart, windowEnd) }
            val spo2Records = retryWithBackoff { hcRepo.readOxygenSaturationRecords(windowStart, windowEnd) }
            val bodyTemperatureRecords =
                retryWithBackoff { hcRepo.readBodyTemperatureRecords(windowStart, windowEnd) }
            val stepsRecords = retryWithBackoff { hcRepo.readStepsRecords(windowStart, windowEnd) }
            val vo2MaxRecords =
                if (hcRepo.hasVo2MaxPermission()) {
                    retryWithBackoff { hcRepo.readVo2MaxRecords(windowStart, windowEnd) }
                } else {
                    emptyList()
                }
            return RawBulkRecords(
                sleepSessions = sleepSessions,
                exerciseRecords = exerciseRecords,
                weightRecords = weightRecords,
                bodyFatRecords = bodyFatRecords,
                bloodPressureRecords = bloodPressureRecords,
                spo2Records = spo2Records,
                bodyTemperatureRecords = bodyTemperatureRecords,
                stepsRecords = stepsRecords,
                vo2MaxRecords = vo2MaxRecords,
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

            val weightInputs =
                raw.weightRecords.map {
                    WeightInput(
                        id = "${it.id}_${it.time.toEpochMilli()}",
                        timestampMs = it.time.toEpochMilli(),
                        weightKg = it.weightKg,
                        deviceName = it.deviceName,
                    )
                }
            val bodyFatInputs =
                raw.bodyFatRecords.map {
                    BodyFatInput(
                        id = "${it.id}_${it.time.toEpochMilli()}",
                        timestampMs = it.time.toEpochMilli(),
                        bodyFatPercent = it.percentage,
                        deviceName = it.deviceName,
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

            val bpInputs =
                raw.bloodPressureRecords.map {
                    BloodPressureInput(
                        id = "${it.id}_${it.time.toEpochMilli()}",
                        timestampMs = it.time.toEpochMilli(),
                        systolicMmHg = it.systolicMmHg,
                        diastolicMmHg = it.diastolicMmHg,
                        deviceName = it.deviceName,
                    )
                }
            val spo2Inputs =
                raw.spo2Records.map {
                    OxygenSaturationInput(
                        id = "${it.id}_${it.time.toEpochMilli()}",
                        timestampMs = it.time.toEpochMilli(),
                        percentage = it.percentage,
                        deviceName = it.deviceName,
                    )
                }
            val tempInputs =
                raw.bodyTemperatureRecords.map {
                    BodyTemperatureInput(
                        id = "${it.id}_${it.time.toEpochMilli()}",
                        timestampMs = it.time.toEpochMilli(),
                        celsius = it.celsius,
                        deviceName = it.deviceName,
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

            val hrDevice = deviceFor(HealthDataType.HEART_RATE)
            var hrSampleCount = 0
            val hrIds = mutableSetOf<String>()
            if (params.hrvStartPageToken == null) {
                hcRepo.readHeartRateSamplesPaged(
                    from = params.windowStart,
                    to = params.windowEnd,
                    startPageToken = params.hrStartPageToken,
                ) { page, nextToken ->
                    logD("HealthSync.Ingest") { "HR page size=${page.size}" }
                    hrIds.addAll(page.map { it.id })
                    val hrInputs =
                        HeartRateMapper.mapToInputs(
                            page,
                            sessionContext.sleepInputs,
                            sessionContext.workoutInputs,
                        )
                    val filteredHr = DeviceSourceFilter.filterToDevice(hrInputs, hrDevice) { it.deviceName }
                    healthIngestionStore.persistHeartRateSamples(filteredHr)
                    hrSampleCount += filteredHr.size
                    pagesIngested++
                    params.onProgress?.invoke(ResyncPhase.INGEST, pagesIngested, 0)
                    params.onTokenUpdated?.invoke(nextToken, null)
                }
            }

            val hrvDevice = deviceFor(HealthDataType.HRV)
            var hrvSampleCount = 0
            val hrvIds = mutableSetOf<String>()
            hcRepo.readHrvSamplesPaged(
                from = params.windowStart,
                to = params.windowEnd,
                startPageToken = params.hrvStartPageToken,
            ) { page, nextToken ->
                logD("HealthSync.Ingest") { "HRV page size=${page.size}" }
                hrvIds.addAll(page.map { it.id })
                val hrvInputs =
                    HrvMapper.mapToInputs(
                        page,
                        sessionContext.sleepInputs,
                    )
                val filteredHrv = DeviceSourceFilter.filterToDevice(hrvInputs, hrvDevice) { it.deviceName }
                healthIngestionStore.persistHrvSamples(filteredHrv)
                hrvSampleCount += filteredHrv.size
                pagesIngested++
                params.onProgress?.invoke(ResyncPhase.INGEST, pagesIngested, 0)
                params.onTokenUpdated?.invoke(null, nextToken)
            }

            logD("HealthIngestionCoordinator") {
                "Streamed samples: hr=$hrSampleCount hrv=$hrvSampleCount"
            }

            return HeartIds(hr = hrIds, hrv = hrvIds)
        }

        private suspend fun reconcileDeletions(
            params: IngestWindowParams,
            raw: RawBulkRecords,
            heartIds: HeartIds,
        ): ScoreInvalidation.AffectedRange? {
            val zoneId = params.prefs.scoringZone()
            val startMs = params.windowStart.toEpochMilli()
            val endMs = params.windowEnd.toEpochMilli() - 1

            val typeToIds = collectReconcilableTypes(params, raw, heartIds)

            val results =
                typeToIds.associate { (type, ids) ->
                    type to healthIngestionStore.reconcileWindow(type, startMs, endMs, ids, zoneId)
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

        private suspend fun collectReconcilableTypes(
            params: IngestWindowParams,
            raw: RawBulkRecords,
            heartIds: HeartIds,
        ): List<Pair<HealthDataType, Set<String>>> =
            buildList {
                add(HealthDataType.SLEEP to raw.sleepSessions.mapTo(HashSet()) { it.id })
                add(HealthDataType.EXERCISE to raw.exerciseRecords.mapTo(HashSet()) { it.id })
                if (params.hrStartPageToken == null && params.hrvStartPageToken == null) {
                    add(HealthDataType.HEART_RATE to heartIds.hr)
                }
                if (params.hrvStartPageToken == null) {
                    add(HealthDataType.HRV to heartIds.hrv)
                }
                if (hcRepo.hasWeightPermission()) {
                    add(HealthDataType.WEIGHT to raw.weightRecords.mapTo(HashSet()) { it.id })
                }
                if (hcRepo.hasBodyFatPermission()) {
                    add(HealthDataType.BODY_FAT to raw.bodyFatRecords.mapTo(HashSet()) { it.id })
                }
                if (hcRepo.hasBloodPressurePermission()) {
                    add(HealthDataType.BLOOD_PRESSURE to raw.bloodPressureRecords.mapTo(HashSet()) { it.id })
                }
                if (hcRepo.hasOxygenSaturationPermission()) {
                    add(HealthDataType.OXYGEN_SATURATION to raw.spo2Records.mapTo(HashSet()) { it.id })
                }
                if (hcRepo.hasBodyTemperaturePermission()) {
                    add(HealthDataType.BODY_TEMPERATURE to raw.bodyTemperatureRecords.mapTo(HashSet()) { it.id })
                }
                if (hcRepo.hasStepsPermission()) {
                    add(HealthDataType.STEPS to raw.stepsRecords.mapTo(HashSet()) { it.id })
                }
            }

        companion object {
            const val RECONCILE_DELETIONS = true
            private const val TELEMETRY_TAG = "ResyncTelemetry"
        }
    }

private data class HeartIds(
    val hr: Set<String>,
    val hrv: Set<String>,
)

private data class RawBulkRecords(
    val sleepSessions: List<app.readylytics.health.core.model.domain.model.DomainSleepSessionRecord>,
    val exerciseRecords: List<app.readylytics.health.core.model.domain.model.DomainExerciseSessionRecord>,
    val weightRecords: List<app.readylytics.health.core.model.domain.model.DomainWeightRecord>,
    val bodyFatRecords: List<app.readylytics.health.core.model.domain.model.DomainBodyFatRecord>,
    val bloodPressureRecords: List<app.readylytics.health.core.model.domain.model.DomainBloodPressureRecord>,
    val spo2Records: List<app.readylytics.health.core.model.domain.model.DomainOxygenSaturationRecord>,
    val bodyTemperatureRecords: List<app.readylytics.health.core.model.domain.model.DomainBodyTemperatureRecord>,
    val stepsRecords: List<app.readylytics.health.core.model.domain.model.DomainStepsRecord>,
    val vo2MaxRecords: List<app.readylytics.health.core.model.domain.model.DomainVo2MaxRecord> = emptyList(),
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
