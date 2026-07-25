package app.readylytics.health.domain.sync

import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.HealthConnectWindowTimeoutException
import app.readylytics.health.domain.sync.mappers.SleepDataMapper
import app.readylytics.health.domain.sync.mappers.WorkoutMapper
import app.readylytics.health.domain.sync.mappers.HeartRateMapper
import app.readylytics.health.domain.sync.mappers.HrvMapper
import app.readylytics.health.domain.util.logD
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
        ) {
            try {
                ingestWindowWithinBudget(windowStart, windowEnd, prefs, windowBudgetMs)
            } catch (e: TimeoutCancellationException) {
                // Not a CancellationException from here on -- HC-002: callers (ResyncRangeUseCase)
                // must be able to tell "this window is too dense for its budget" apart from
                // cooperative cancellation, which they must never swallow.
                throw HealthConnectWindowTimeoutException(windowStart, windowEnd, e)
            }
        }

        private suspend fun ingestWindowWithinBudget(
            windowStart: Instant,
            windowEnd: Instant,
            prefs: UserPreferences,
            windowBudgetMs: Long,
        ) {
            withTimeout(windowBudgetMs) {
                val sleepSessions = retryWithBackoff { hcRepo.readSleepSessions(windowStart, windowEnd) }
                val exerciseRecords = retryWithBackoff { hcRepo.readExerciseSessions(windowStart, windowEnd) }
                val weightRecords = retryWithBackoff { hcRepo.readWeightRecords(windowStart, windowEnd) }
                val bodyFatRecords = retryWithBackoff { hcRepo.readBodyFatRecords(windowStart, windowEnd) }
                val bloodPressureRecords =
                    retryWithBackoff { hcRepo.readBloodPressureRecords(windowStart, windowEnd) }
                val spo2Records = retryWithBackoff { hcRepo.readOxygenSaturationRecords(windowStart, windowEnd) }
                // Raw steps records aren't used for the daily total (StepCountFetcher's
                // aggregate/device-filtered reads are) -- persisted purely so a later
                // changes-path deletion can resolve its own date range (HC-005).
                val stepsRecords = retryWithBackoff { hcRepo.readStepsRecords(windowStart, windowEnd) }

                val sleepInputs = sleepSessions.map { SleepDataMapper.mapSleepSession(it) }

                logD("HealthIngestionCoordinator") {
                    "Bulk HC fetch complete: sleep=${sleepInputs.size} exercise=${exerciseRecords.size} " +
                        "weight=${weightRecords.size} bodyfat=${bodyFatRecords.size} " +
                        "bp=${bloodPressureRecords.size} spo2=${spo2Records.size}"
                }

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

                val weightInputs =
                    weightRecords.map { record ->
                        WeightInput(
                            id = "${record.id}_${record.time.toEpochMilli()}",
                            timestampMs = record.time.toEpochMilli(),
                            weightKg = record.weightKg,
                            deviceName = record.deviceName,
                        )
                    }
                val filteredWeight =
                    DeviceSourceFilter.filterToDevice(
                        weightInputs,
                        deviceFor(HealthDataType.WEIGHT),
                    ) { it.deviceName }

                val bodyFatInputs =
                    bodyFatRecords.map { record ->
                        BodyFatInput(
                            id = "${record.id}_${record.time.toEpochMilli()}",
                            timestampMs = record.time.toEpochMilli(),
                            bodyFatPercent = record.percentage,
                            deviceName = record.deviceName,
                        )
                    }
                val filteredBodyFat =
                    DeviceSourceFilter.filterToDevice(
                        bodyFatInputs,
                        deviceFor(HealthDataType.BODY_FAT),
                    ) { it.deviceName }

                val bloodPressureInputs =
                    bloodPressureRecords.map { record ->
                        BloodPressureInput(
                            id = "${record.id}_${record.time.toEpochMilli()}",
                            timestampMs = record.time.toEpochMilli(),
                            systolicMmHg = record.systolicMmHg,
                            diastolicMmHg = record.diastolicMmHg,
                            deviceName = record.deviceName,
                        )
                    }
                val filteredBloodPressure =
                    DeviceSourceFilter.filterToDevice(
                        bloodPressureInputs,
                        deviceFor(HealthDataType.BLOOD_PRESSURE),
                    ) { it.deviceName }

                val spo2Inputs =
                    spo2Records.map { record ->
                        OxygenSaturationInput(
                            id = "${record.id}_${record.time.toEpochMilli()}",
                            timestampMs = record.time.toEpochMilli(),
                            percentage = record.percentage,
                            deviceName = record.deviceName,
                        )
                    }
                val filteredSpo2 =
                    DeviceSourceFilter.filterToDevice(
                        spo2Inputs,
                        deviceFor(HealthDataType.OXYGEN_SATURATION),
                    ) { it.deviceName }

                logD("HealthIngestionCoordinator") {
                    "Device filtering: sleep=${filteredSleep.size} workouts=${filteredWorkouts.size} " +
                        "weight=${filteredWeight.size} bodyfat=${filteredBodyFat.size} " +
                        "bp=${filteredBloodPressure.size} spo2=${filteredSpo2.size}"
                }

                // Only persist stages whose parent session survived device filtering. Stages carry a
                // foreign key (sessionId) to SleepSessionEntity, so emitting stages for a filtered-out
                // session would orphan them (or violate the FK constraint). Filter on the stage's own
                // sessionId so the match is against the exact FK target, not the raw DTO id.
                val filteredSleepIds = filteredSleep.mapTo(HashSet()) { it.id }
                val allStages =
                    sleepSessions
                        .flatMap {
                            SleepDataMapper.mapSleepSessionStages(it)
                        }.filter { it.sessionId in filteredSleepIds }

                // Unlike the other record types, step_records isn't device-filtered: it's never read
                // for scoring (StepCountFetcher's aggregate/device-filtered reads own the visible daily
                // total), only for resolving a future deletion's date range, so storing every device's
                // raw rows is strictly more correct (HC-005).
                val stepRecordInputs =
                    stepsRecords.map { record ->
                        StepRecordInput(
                            id = record.id,
                            startTime = record.startTime.toEpochMilli(),
                            endTime = record.endTime.toEpochMilli(),
                            count = record.count,
                            deviceName = record.deviceName,
                        )
                    }

                // Sessions + low-volume types commit first, in one transaction -- matches this
                // method's previous ordering, where the parent transaction always preceded the
                // heart-rate/HRV batch transactions.
                healthIngestionStore.persist(
                    HealthIngestionBatch(
                        sleepSessions = filteredSleep,
                        sleepStages = allStages,
                        heartRateSamples = emptyList(),
                        hrvSamples = emptyList(),
                        workouts = filteredWorkouts,
                        weights = filteredWeight,
                        bodyFatSamples = filteredBodyFat,
                        bloodPressureSamples = filteredBloodPressure,
                        oxygenSaturationSamples = filteredSpo2,
                        stepRecords = stepRecordInputs,
                    ),
                )

                val hrDevice = deviceFor(HealthDataType.HEART_RATE)
                var hrSampleCount = 0
                retryWithBackoff {
                    hrSampleCount = 0
                    hcRepo.readHeartRateSamplesPaged(windowStart, windowEnd) { page ->
                        val hrInputs =
                            HeartRateMapper.mapToInputs(
                                page,
                                sleepInputs,
                                workoutInputs,
                            )
                        val filteredHr = DeviceSourceFilter.filterToDevice(hrInputs, hrDevice) { it.deviceName }
                        healthIngestionStore.persistHeartRateSamples(filteredHr)
                        hrSampleCount += filteredHr.size
                    }
                }

                val hrvDevice = deviceFor(HealthDataType.HRV)
                var hrvSampleCount = 0
                retryWithBackoff {
                    hrvSampleCount = 0
                    hcRepo.readHrvSamplesPaged(windowStart, windowEnd) { page ->
                        val hrvInputs =
                            HrvMapper.mapToInputs(
                                page,
                                sleepInputs,
                            )
                        val filteredHrv = DeviceSourceFilter.filterToDevice(hrvInputs, hrvDevice) { it.deviceName }
                        healthIngestionStore.persistHrvSamples(filteredHrv)
                        hrvSampleCount += filteredHrv.size
                    }
                }

                logD("HealthIngestionCoordinator") {
                    "Streamed samples: hr=$hrSampleCount hrv=$hrvSampleCount"
                }
            }
        }
    }
