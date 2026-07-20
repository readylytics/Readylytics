package app.readylytics.health.domain.sync

import app.readylytics.health.domain.model.DomainBloodPressureRecord
import app.readylytics.health.domain.model.DomainBodyFatRecord
import app.readylytics.health.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.domain.model.DomainHeartRateRecord
import app.readylytics.health.domain.model.DomainHrvRecord
import app.readylytics.health.domain.model.DomainOxygenSaturationRecord
import app.readylytics.health.domain.model.DomainSleepSessionRecord
import app.readylytics.health.domain.model.DomainStepsRecord
import app.readylytics.health.domain.model.DomainWeightRecord
import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.util.logD
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeout

/**
 * Reads one Health Connect window, maps + device-filters it, and upserts every record type into
 * Room in a single transaction. Shared by the recent-window [DailySyncUseCase] and the chunked
 * [ResyncRangeUseCase], so both flows ingest through identical mapping/filtering logic.
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
            val (sleepSessions, exerciseRecords, hrRecords, hrvRecords,
                weightRecords, bodyFatRecords, bloodPressureRecords, spo2Records, stepsRecords) =
                withTimeout(windowBudgetMs) {
                    HcFetch(
                        sleepSessions = retryWithBackoff { hcRepo.readSleepSessions(windowStart, windowEnd) },
                        exerciseRecords = retryWithBackoff { hcRepo.readExerciseSessions(windowStart, windowEnd) },
                        hrRecords = retryWithBackoff { hcRepo.readHeartRateSamples(windowStart, windowEnd) },
                        hrvRecords = retryWithBackoff { hcRepo.readHrvSamples(windowStart, windowEnd) },
                        weightRecords = retryWithBackoff { hcRepo.readWeightRecords(windowStart, windowEnd) },
                        bodyFatRecords = retryWithBackoff { hcRepo.readBodyFatRecords(windowStart, windowEnd) },
                        bloodPressureRecords = retryWithBackoff { hcRepo.readBloodPressureRecords(windowStart, windowEnd) },
                        spo2Records = retryWithBackoff { hcRepo.readOxygenSaturationRecords(windowStart, windowEnd) },
                        // Raw steps records aren't used for the daily total (StepCountFetcher's
                        // aggregate/device-filtered reads are) -- persisted purely so a later
                        // changes-path deletion can resolve its own date range (HC-005).
                        stepsRecords = retryWithBackoff { hcRepo.readStepsRecords(windowStart, windowEnd) },
                    )
                }
            val sleepEntities =
                sleepSessions.map {
                    app.readylytics.health.data.healthconnect.SleepDataMapper
                        .mapSleepSession(
                            it,
                        )
                }

            logD("HealthIngestionCoordinator") {
                "Bulk HC fetch complete: sleep=${sleepEntities.size} " +
                    "hrv_rmssd=${hrvRecords.size} hr_records=${hrRecords.size} " +
                    "weight=${weightRecords.size} bodyfat=${bodyFatRecords.size} bp=${bloodPressureRecords.size} spo2=${spo2Records.size}"
            }

            val thresholds =
                app.readylytics.health.data.healthconnect.WorkoutMapper.zoneThresholds(
                    prefs.zone1MinBpm,
                    prefs.zone1MaxBpm,
                    prefs.zone2MaxBpm,
                    prefs.zone3MaxBpm,
                    prefs.zone4MaxBpm,
                )

            val initialWorkouts =
                exerciseRecords.map {
                    app.readylytics.health.data.healthconnect.WorkoutMapper.mapExerciseSession(
                        it,
                        emptyList(),
                        thresholds,
                    )
                }
            val hrEntities =
                app.readylytics.health.data.healthconnect.HeartRateMapper.mapToEntities(
                    hrRecords,
                    sleepEntities,
                    initialWorkouts,
                )
            val hrBySession =
                hrEntities
                    .asSequence()
                    .filter {
                        it.sessionId != null
                    }.groupBy { it.sessionId }
            val workoutEntities =
                exerciseRecords.map { session ->
                    val sessionSamples = hrBySession[session.id] ?: emptyList()
                    app.readylytics.health.data.healthconnect.WorkoutMapper.mapExerciseSession(
                        session,
                        sessionSamples,
                        thresholds,
                    )
                }
            val hrvEntities =
                app.readylytics.health.data.healthconnect.HrvMapper.mapToEntities(
                    hrvRecords,
                    sleepEntities,
                )

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
            val filteredHr =
                DeviceSourceFilter.filterToDevice(
                    hrEntities,
                    deviceFor(HealthDataType.HEART_RATE),
                ) { it.deviceName }
            val filteredHrv =
                DeviceSourceFilter.filterToDevice(
                    hrvEntities,
                    deviceFor(HealthDataType.HRV),
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
                    "hr=${filteredHr.size} hrv=${filteredHrv.size} " +
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

            healthIngestionStore.persist(
                HealthIngestionBatch(
                    sleepSessions = filteredSleep.map { it.toInput() },
                    sleepStages = allStages.map { it.toInput() },
                    heartRateSamples = filteredHr.map { it.toInput() },
                    hrvSamples = filteredHrv.map { it.toInput() },
                    workouts = filteredWorkouts.map { it.toInput() },
                    weights = filteredWeight.map { it.toInput() },
                    bodyFatSamples = filteredBodyFat.map { it.toInput() },
                    bloodPressureSamples = filteredBloodPressure.map { it.toInput() },
                    oxygenSaturationSamples = filteredSpo2.map { it.toInput() },
                    stepRecords = stepRecordInputs,
                ),
            )
        }

        private data class HcFetch(
            val sleepSessions: List<DomainSleepSessionRecord>,
            val exerciseRecords: List<DomainExerciseSessionRecord>,
            val hrRecords: List<DomainHeartRateRecord>,
            val hrvRecords: List<DomainHrvRecord>,
            val weightRecords: List<DomainWeightRecord>,
            val bodyFatRecords: List<DomainBodyFatRecord>,
            val bloodPressureRecords: List<DomainBloodPressureRecord>,
            val spo2Records: List<DomainOxygenSaturationRecord>,
            val stepsRecords: List<DomainStepsRecord>,
        )

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
