package app.readylytics.health.domain.sync

import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.HealthConnectWindowTimeoutException
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

                val sleepEntities =
                    sleepSessions.map {
                        app.readylytics.health.data.healthconnect.SleepDataMapper
                            .mapSleepSession(it)
                    }

                logD("HealthIngestionCoordinator") {
                    "Bulk HC fetch complete: sleep=${sleepEntities.size} exercise=${exerciseRecords.size} " +
                        "weight=${weightRecords.size} bodyfat=${bodyFatRecords.size} " +
                        "bp=${bloodPressureRecords.size} spo2=${spo2Records.size}"
                }

                val thresholds =
                    app.readylytics.health.data.healthconnect.WorkoutMapper.zoneThresholds(
                        prefs.zone1MinBpm,
                        prefs.zone1MaxBpm,
                        prefs.zone2MaxBpm,
                        prefs.zone3MaxBpm,
                        prefs.zone4MaxBpm,
                    )

                val workoutEntities =
                    exerciseRecords.map {
                        app.readylytics.health.data.healthconnect.WorkoutMapper.mapExerciseSession(
                            it,
                            emptyList(),
                            thresholds,
                        )
                    }

                val deviceByType = prefs.deviceByDataType

                fun deviceFor(type: HealthDataType): String? = deviceByType[type.name]?.takeIf { it.isNotBlank() }

                val filteredSleep =
                    DeviceSourceFilter.filterToDevice(
                        sleepEntities,
                        deviceFor(HealthDataType.SLEEP),
                    ) { it.deviceName }
                val filteredWorkouts =
                    DeviceSourceFilter.filterToDevice(
                        workoutEntities,
                        deviceFor(HealthDataType.EXERCISE),
                    ) { it.deviceName }

                val weightEntities =
                    app.readylytics.health.data.mapper.WeightDataMapper
                        .toEntities(weightRecords)
                val filteredWeight =
                    DeviceSourceFilter.filterToDevice(
                        weightEntities,
                        deviceFor(HealthDataType.WEIGHT),
                    ) { it.deviceName }

                val bodyFatEntities =
                    app.readylytics.health.data.mapper.BodyFatDataMapper
                        .toEntities(bodyFatRecords)
                val filteredBodyFat =
                    DeviceSourceFilter.filterToDevice(
                        bodyFatEntities,
                        deviceFor(HealthDataType.BODY_FAT),
                    ) { it.deviceName }

                val bloodPressureEntities =
                    app.readylytics.health.data.mapper.BloodPressureDataMapper
                        .toEntities(bloodPressureRecords)
                val filteredBloodPressure =
                    DeviceSourceFilter.filterToDevice(
                        bloodPressureEntities,
                        deviceFor(HealthDataType.BLOOD_PRESSURE),
                    ) { it.deviceName }

                val spo2Entities =
                    app.readylytics.health.data.mapper.OxygenSaturationDataMapper
                        .toEntities(spo2Records)
                val filteredSpo2 =
                    DeviceSourceFilter.filterToDevice(
                        spo2Entities,
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
                            app.readylytics.health.data.healthconnect.SleepDataMapper
                                .mapSleepSessionStages(it)
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
                        sleepSessions = filteredSleep.map { it.toInput() },
                        sleepStages = allStages.map { it.toInput() },
                        heartRateSamples = emptyList(),
                        hrvSamples = emptyList(),
                        workouts = filteredWorkouts.map { it.toInput() },
                        weights = filteredWeight.map { it.toInput() },
                        bodyFatSamples = filteredBodyFat.map { it.toInput() },
                        bloodPressureSamples = filteredBloodPressure.map { it.toInput() },
                        oxygenSaturationSamples = filteredSpo2.map { it.toInput() },
                        stepRecords = stepRecordInputs,
                    ),
                )

                val hrDevice = deviceFor(HealthDataType.HEART_RATE)
                var hrSampleCount = 0
                retryWithBackoff {
                    hrSampleCount = 0
                    hcRepo.readHeartRateSamplesPaged(windowStart, windowEnd) { page ->
                        val hrEntities =
                            app.readylytics.health.data.healthconnect.HeartRateMapper.mapToEntities(
                                page,
                                sleepEntities,
                                workoutEntities,
                            )
                        val filteredHr = DeviceSourceFilter.filterToDevice(hrEntities, hrDevice) { it.deviceName }
                        healthIngestionStore.persistHeartRateSamples(filteredHr.map { it.toInput() })
                        hrSampleCount += filteredHr.size
                    }
                }

                val hrvDevice = deviceFor(HealthDataType.HRV)
                var hrvSampleCount = 0
                retryWithBackoff {
                    hrvSampleCount = 0
                    hcRepo.readHrvSamplesPaged(windowStart, windowEnd) { page ->
                        val hrvEntities =
                            app.readylytics.health.data.healthconnect.HrvMapper.mapToEntities(
                                page,
                                sleepEntities,
                            )
                        val filteredHrv = DeviceSourceFilter.filterToDevice(hrvEntities, hrvDevice) { it.deviceName }
                        healthIngestionStore.persistHrvSamples(filteredHrv.map { it.toInput() })
                        hrvSampleCount += filteredHrv.size
                    }
                }

                logD("HealthIngestionCoordinator") {
                    "Streamed samples: hr=$hrSampleCount hrv=$hrvSampleCount"
                }
            }
        }

        private fun app.readylytics.health.data.local.entity.SleepSessionEntity.toInput() =
            SleepSessionInput(
                id = id,
                startTime = startTime,
                endTime = endTime,
                durationMinutes = durationMinutes,
                efficiency = efficiency,
                deepSleepMinutes = deepSleepMinutes,
                remSleepMinutes = remSleepMinutes,
                lightSleepMinutes = lightSleepMinutes,
                awakeMinutes = awakeMinutes,
                sleepScore = sleepScore,
                startZoneOffsetSeconds = startZoneOffsetSeconds,
                endZoneOffsetSeconds = endZoneOffsetSeconds,
                deviceName = deviceName,
            )

        private fun app.readylytics.health.data.local.entity.SleepStageEntity.toInput() =
            SleepStageInput(
                sessionId = sessionId,
                stageType = stageType,
                startTime = startTime,
                endTime = endTime,
                durationMinutes = durationMinutes,
            )

        private fun app.readylytics.health.data.local.entity.HeartRateRecordEntity.toInput() =
            HeartRateInput(
                id = id,
                timestampMs = timestampMs,
                beatsPerMinute = beatsPerMinute,
                recordType = recordType,
                sessionId = sessionId,
                deviceName = deviceName,
            )

        private fun app.readylytics.health.data.local.entity.HrvRecordEntity.toInput() =
            HrvInput(
                id = id,
                timestampMs = timestampMs,
                rmssdMs = rmssdMs,
                recordType = recordType,
                sessionId = sessionId,
                deviceName = deviceName,
            )

        private fun app.readylytics.health.data.local.entity.WorkoutRecordEntity.toInput() =
            WorkoutInput(
                id = id,
                startTime = startTime,
                endTime = endTime,
                exerciseType = exerciseType,
                durationMinutes = durationMinutes,
                zone1Minutes = zone1Minutes,
                zone2Minutes = zone2Minutes,
                zone3Minutes = zone3Minutes,
                zone4Minutes = zone4Minutes,
                zone5Minutes = zone5Minutes,
                trimp = trimp,
                avgHr = avgHr,
                deviceName = deviceName,
            )

        private fun app.readylytics.health.data.local.entity.WeightRecordEntity.toInput() =
            WeightInput(
                id = id,
                timestampMs = timestampMs,
                weightKg = weightKg,
                deviceName = deviceName,
            )

        private fun app.readylytics.health.data.local.entity.BodyFatRecordEntity.toInput() =
            BodyFatInput(
                id = id,
                timestampMs = timestampMs,
                bodyFatPercent = bodyFatPercent,
                deviceName = deviceName,
            )

        private fun app.readylytics.health.data.local.entity.BloodPressureRecordEntity.toInput() =
            BloodPressureInput(
                id = id,
                timestampMs = timestampMs,
                systolicMmHg = systolicMmHg,
                diastolicMmHg = diastolicMmHg,
                deviceName = deviceName,
            )

        private fun app.readylytics.health.data.local.entity.OxygenSaturationRecordEntity.toInput() =
            OxygenSaturationInput(
                id = id,
                timestampMs = timestampMs,
                percentage = percentage,
                deviceName = deviceName,
            )
    }
