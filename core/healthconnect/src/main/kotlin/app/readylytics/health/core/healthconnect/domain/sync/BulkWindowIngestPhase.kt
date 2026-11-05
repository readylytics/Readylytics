package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.DomainBloodPressureRecord
import app.readylytics.health.core.model.domain.model.DomainBodyFatRecord
import app.readylytics.health.core.model.domain.model.DomainBodyTemperatureRecord
import app.readylytics.health.core.model.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.core.model.domain.model.DomainOxygenSaturationRecord
import app.readylytics.health.core.model.domain.model.DomainSleepSessionRecord
import app.readylytics.health.core.model.domain.model.DomainVo2MaxRecord
import app.readylytics.health.core.model.domain.model.DomainWeightRecord
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.repository.dataOrEmpty
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.SleepSessionInput
import app.readylytics.health.core.model.domain.sync.SleepStageInput
import app.readylytics.health.core.model.domain.sync.Vo2MaxInput
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import app.readylytics.health.core.model.domain.sync.mappers.SleepDataMapper
import app.readylytics.health.core.model.domain.sync.mappers.WorkoutMapper
import app.readylytics.health.core.model.domain.util.logD
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Bulk (low-cardinality) phase of one window's ingestion: concurrently reads sleep/exercise
 * sessions and the low-cardinality vitals, maps + device-filters them, and persists them up front.
 * Returns the raw outcomes (needed later for scan staging) plus the unfiltered session context that
 * the dense HR/HRV page-tagging and workout metrics depend on.
 */
internal class BulkWindowIngestPhase
    @Inject
    constructor(
        private val hcRepo: HealthConnectRepository,
        private val healthIngestionStore: HealthIngestionStore,
    ) {
        suspend fun fetchAndPersist(
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
        // see [DenseWindowStreamPhase.streamAll] (HC-001).
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
            logD("ResyncTelemetry") {
                "[INGESTION] max low-cardinality vitals list size this chunk: $maxVitalsListSize"
            }
        }

        private fun buildBulkBatch(
            filteredSleep: List<SleepSessionInput>,
            allStages: List<SleepStageInput>,
            filteredWorkouts: List<WorkoutInput>,
            vitals: FilteredVitals,
            vo2MaxRecords: List<DomainVo2MaxRecord>,
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
    }

// Steps is deliberately absent -- it is a dense type streamed/staged page-by-page alongside HR/HRV
// (see [DenseWindowStreamPhase.streamSteps]), never bulk-fetched or held in full here (HC-001).
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

internal data class IngestionSessionContext(
    val sleepInputs: List<SleepSessionInput>,
    val workoutInputs: List<WorkoutInput>,
)
