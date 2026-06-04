package com.gregor.lauritz.healthdashboard.domain.sync

import com.gregor.lauritz.healthdashboard.data.healthconnect.HeartRateMapper
import com.gregor.lauritz.healthdashboard.data.healthconnect.HrvMapper
import com.gregor.lauritz.healthdashboard.data.healthconnect.SleepDataMapper
import com.gregor.lauritz.healthdashboard.data.healthconnect.StepsMapper
import com.gregor.lauritz.healthdashboard.data.healthconnect.WorkoutMapper
import com.gregor.lauritz.healthdashboard.data.local.dao.BloodPressureRecordDao
import com.gregor.lauritz.healthdashboard.data.local.dao.BodyFatRecordDao
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.OxygenSaturationRecordDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepStageDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WeightRecordDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.mapper.BloodPressureDataMapper
import com.gregor.lauritz.healthdashboard.data.mapper.BodyFatDataMapper
import com.gregor.lauritz.healthdashboard.data.mapper.OxygenSaturationDataMapper
import com.gregor.lauritz.healthdashboard.data.mapper.WeightDataMapper
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.model.HealthDataType
import com.gregor.lauritz.healthdashboard.domain.model.Result
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.domain.repository.ScoringRepository
import com.gregor.lauritz.healthdashboard.domain.util.HeartRateFormulas
import com.gregor.lauritz.healthdashboard.domain.util.logD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthSyncUseCase
    @Inject
    constructor(
        private val hcRepo: HealthConnectRepository,
        private val sleepSessionDao: SleepSessionDao,
        private val sleepStageDao: SleepStageDao,
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val workoutDao: WorkoutDao,
        private val weightRecordDao: WeightRecordDao,
        private val bodyFatRecordDao: BodyFatRecordDao,
        private val bloodPressureRecordDao: BloodPressureRecordDao,
        private val oxygenSaturationRecordDao: OxygenSaturationRecordDao,
        private val dailySummaryDao: DailySummaryDao,
        private val settingsRepo: SettingsRepository,
        private val scoringRepository: ScoringRepository,
        private val transactionRunner: com.gregor.lauritz.healthdashboard.domain.repository.TransactionRunner,
    ) {
        private val syncMutex = Mutex()

        private companion object {
            // Max concurrent Health Connect step reads during a catch-up sync.
            const val STEPS_FETCH_CONCURRENCY = 4
        }

        suspend fun sync(windowDays: Int = 8): Result<Unit> =
            syncMutex.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        logD("HealthSyncUseCase") { "Starting sync (window=$windowDays days)..." }
                        val today = LocalDate.now(ZoneId.systemDefault())
                        val zoneId = ZoneId.systemDefault()
                        // Migrate any legacy global "primary device" into the per-data-type map.
                        settingsRepo.migrateDeviceSelectionIfNeeded()
                        val initialPrefs = settingsRepo.userPreferences.first()

                        updateCalculatedMetrics(initialPrefs)
                        // Re-fetch preferences in case they were updated by updateCalculatedMetrics
                        val prefs = settingsRepo.userPreferences.first()

                        val windowStart = today.minusDays((windowDays - 1).toLong()).atStartOfDay(zoneId).toInstant()
                        val windowEnd = today.plusDays(1).atStartOfDay(zoneId).toInstant()

                        logD("HealthSyncUseCase") { "Bulk fetching HC data for $windowDays days..." }

                        val sleepSessions = hcRepo.readSleepSessions(windowStart, windowEnd)
                        val sleepEntities = sleepSessions.map { SleepDataMapper.mapSleepSession(it) }
                        val exerciseRecords = hcRepo.readExerciseSessions(windowStart, windowEnd)
                        val hrRecords = hcRepo.readHeartRateSamples(windowStart, windowEnd)
                        val hrvRecords = hcRepo.readHrvSamples(windowStart, windowEnd)
                        val weightRecords = hcRepo.readWeightRecords(windowStart, windowEnd)
                        val bodyFatRecords = hcRepo.readBodyFatRecords(windowStart, windowEnd)
                        val bloodPressureRecords = hcRepo.readBloodPressureRecords(windowStart, windowEnd)
                        val spo2Records = hcRepo.readOxygenSaturationRecords(windowStart, windowEnd)

                        logD("HealthSyncUseCase") {
                            "Bulk HC fetch complete: sleep=${sleepEntities.size} " +
                                "hrv_rmssd=${hrvRecords.size} hr_records=${hrRecords.size} " +
                                "weight=${weightRecords.size} bodyfat=${bodyFatRecords.size} bp=${bloodPressureRecords.size} spo2=${spo2Records.size}"
                        }

                        val thresholds =
                            WorkoutMapper.zoneThresholds(
                                prefs.zone1MinBpm,
                                prefs.zone1MaxBpm,
                                prefs.zone2MaxBpm,
                                prefs.zone3MaxBpm,
                                prefs.zone4MaxBpm,
                            )

                        val initialWorkouts =
                            exerciseRecords.map {
                                WorkoutMapper.mapExerciseSession(
                                    it,
                                    emptyList(),
                                    thresholds,
                                )
                            }
                        val hrEntities = HeartRateMapper.mapToEntities(hrRecords, sleepEntities, initialWorkouts)
                        val hrBySession =
                            hrEntities
                                .asSequence()
                                .filter {
                                    it.sessionId != null
                                }.groupBy { it.sessionId }
                        val workoutEntities =
                            exerciseRecords.map { session ->
                                val sessionSamples = hrBySession[session.metadata.id] ?: emptyList()
                                WorkoutMapper.mapExerciseSession(session, sessionSamples, thresholds)
                            }
                        val hrvEntities = HrvMapper.mapToEntities(hrvRecords, sleepEntities)

                        val deviceByType = prefs.deviceByDataType
                        fun deviceFor(type: HealthDataType): String? =
                            deviceByType[type.name]?.takeIf { it.isNotBlank() }

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

                        val weightEntities = WeightDataMapper.toEntities(weightRecords)
                        val filteredWeight =
                            DeviceSourceFilter.filterToDevice(
                                weightEntities,
                                deviceFor(HealthDataType.WEIGHT),
                            ) { it.deviceName }

                        val bodyFatEntities = BodyFatDataMapper.toEntities(bodyFatRecords)
                        val filteredBodyFat =
                            DeviceSourceFilter.filterToDevice(
                                bodyFatEntities,
                                deviceFor(HealthDataType.BODY_FAT),
                            ) { it.deviceName }

                        val bloodPressureEntities = BloodPressureDataMapper.toEntities(bloodPressureRecords)
                        val filteredBloodPressure =
                            DeviceSourceFilter.filterToDevice(
                                bloodPressureEntities,
                                deviceFor(HealthDataType.BLOOD_PRESSURE),
                            ) { it.deviceName }

                        val spo2Entities = OxygenSaturationDataMapper.toEntities(spo2Records)
                        val filteredSpo2 =
                            DeviceSourceFilter.filterToDevice(
                                spo2Entities,
                                deviceFor(HealthDataType.OXYGEN_SATURATION),
                            ) { it.deviceName }

                        logD("HealthSyncUseCase") {
                            "Device filtering: sleep=${filteredSleep.size} workouts=${filteredWorkouts.size} " +
                                "hr=${filteredHr.size} hrv=${filteredHrv.size} " +
                                "weight=${filteredWeight.size} bodyfat=${filteredBodyFat.size} " +
                                "bp=${filteredBloodPressure.size} spo2=${filteredSpo2.size}"
                        }

                        transactionRunner.runInTransaction {
                            sleepSessionDao.upsertAll(filteredSleep)
                            val allStages = sleepSessions.flatMap { SleepDataMapper.mapSleepSessionStages(it) }

                            // FILTER STAGES TO MATCH DEVICE-FILTERED SESSIONS (prevents orphaned stages)
                            // Use Set for O(1) lookup instead of O(N) linear search (improves O(N×M) to O(N))
                            val filteredSessionIds = filteredSleep.map { it.id }.toSet()
                            val filteredStages =
                                allStages.filter { stage ->
                                    stage.sessionId in filteredSessionIds
                                }

                            // DELETE OLD STAGES BEFORE UPSERT (prevents stale stage accumulation)
                            sleepStageDao.deleteForSessions(filteredSessionIds.toList())

                            sleepStageDao.upsertAll(filteredStages)
                            workoutDao.upsertAll(filteredWorkouts)
                            heartRateDao.upsertAll(filteredHr)
                            hrvDao.upsertAll(filteredHrv)
                            weightRecordDao.upsertAll(filteredWeight)
                            bodyFatRecordDao.upsertAll(filteredBodyFat)
                            bloodPressureRecordDao.upsertAll(filteredBloodPressure)
                            oxygenSaturationRecordDao.upsertAll(filteredSpo2)
                        }

                        // Fetch steps respecting the per-data-type source-device selection.
                        // When a specific device is selected the aggregate API can't filter by
                        // device, so read raw records and aggregate by day after filtering. When
                        // "All devices" is selected, use the aggregate API which de-duplicates
                        // overlapping records across data origins.
                        logD("HealthSyncUseCase") { "Bulk fetching steps for $windowDays days..." }
                        val stepsDevice = deviceFor(HealthDataType.STEPS)
                        val stepsMap = mutableMapOf<LocalDate, Long>()
                        if (stepsDevice == null) {
                            // Cap concurrent Health Connect IPC calls to avoid rate limiting
                            // (RateLimitExceededException) and memory pressure on large windows.
                            val stepsSemaphore = Semaphore(STEPS_FETCH_CONCURRENCY)
                            coroutineScope {
                                val deferredSteps =
                                    (0 until windowDays).map { i ->
                                        val day = today.minusDays(i.toLong())
                                        val dayStart = day.atStartOfDay(zoneId).toInstant()
                                        val dayEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant()
                                        async {
                                            stepsSemaphore.withPermit {
                                                day to hcRepo.readSteps(dayStart, dayEnd)
                                            }
                                        }
                                    }
                                stepsMap.putAll(deferredSteps.awaitAll())
                            }
                        } else {
                            val stepEntries =
                                DeviceSourceFilter.filterToDevice(
                                    StepsMapper.toStepEntries(hcRepo.readStepsRecords(windowStart, windowEnd)),
                                    stepsDevice,
                                ) { it.deviceName }
                            stepsMap.putAll(StepsMapper.sumByDay(stepEntries, zoneId))
                        }

                        // One-time migration: all historical days get per-day bounded baselines + recomputed scores.
                        // Stale detection: baseline_version < 2 or NULL means old global-window computation.
                        val staleCount = dailySummaryDao.countRowsWithBaselineVersionBelow(2)
                        if (staleCount > 0) {
                            logD("HealthSyncUseCase") { "Migration triggered: $staleCount stale rows found." }
                            // Clear freeze so ScoringRepositoryImpl (now using bounded variants) can recompute.
                            dailySummaryDao.clearFrozenBaselines()
                            val earliestMs = dailySummaryDao.getEarliestDateMs()
                            if (earliestMs != null) {
                                val startDate = Instant.ofEpochMilli(earliestMs).atZone(zoneId).toLocalDate()
                                val endDate = LocalDate.now(zoneId)
                                val windowStartDate = today.minusDays((windowDays - 1).toLong())
                                // Preload historical step counts in one query to avoid an N+1
                                // lookup per day across potentially months/years of data.
                                val historicalSteps =
                                    dailySummaryDao.getAllSummaries()
                                        .associate { it.dateMidnightMs to (it.stepCount ?: 0).toLong() }
                                var current = startDate
                                while (!current.isAfter(endDate)) {
                                    // For days in the sync window, use fresh stepsMap data.
                                    // For historical dates outside window, preserve existing steps from DB.
                                    val steps = if (current >= windowStartDate) {
                                        stepsMap[current] ?: 0L
                                    } else {
                                        val dateMidnightMs = current.atStartOfDay(zoneId).toInstant().toEpochMilli()
                                        historicalSteps[dateMidnightMs] ?: 0L
                                    }
                                    syncDayScoring(current, steps)
                                    current = current.plusDays(1)
                                }
                            }
                            dailySummaryDao.setBaselineVersion(2)
                            logD("HealthSyncUseCase") { "Migration complete." }
                        }

                        var successCount = 0
                        var failureCount = 0

                        for (i in (windowDays - 1) downTo 0) {
                            val day = today.minusDays(i.toLong())
                            val steps = stepsMap[day] ?: 0L
                            val result = syncDayScoring(day, steps)

                            when (result) {
                                is Result.Success -> {
                                    successCount++
                                    logD("HealthSyncUseCase") { "Day $day: SUCCESS" }
                                }
                                is Result.Failure -> {
                                    failureCount++
                                    logD("HealthSyncUseCase") { "Day $day: FAILED - ${result.reason}" }
                                }
                            }
                        }

                        logD("HealthSyncUseCase") {
                            "Sync complete: $successCount succeeded, $failureCount failed"
                        }
                        settingsRepo.updateLastSyncTimestamp(System.currentTimeMillis())
                        Result.success(Unit)
                    } catch (e: Exception) {
                        Result.failure("Sync failed", "SYNC_ERROR")
                    }
                }
            }

        suspend fun catchUpSync(): Result<Unit> = sync(windowDays = 60)

        private suspend fun syncDayScoring(
            day: LocalDate,
            steps: Long,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val afterScoring = scoringRepository.computeDailySummary(day)
                    val stepCountInt = steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val finalSummary = afterScoring.copy(stepCount = stepCountInt)
                    dailySummaryDao.upsert(finalSummary)

                    logD("HealthSyncUseCase") { "Day $day: scored (steps=$steps) and summary persisted" }
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure("Day $day sync failed", "DAY_SYNC_ERROR")
                }
            }

        private suspend fun updateCalculatedMetrics(prefs: UserPreferences) {
            // Always recalculate Max HR if auto-calculate is enabled (in case age changed via onboarding/settings)
            if (prefs.autoCalculateMaxHr) {
                val calculatedMaxHr = HeartRateFormulas.estimateMaxHr(prefs.age)
                if (calculatedMaxHr != prefs.maxHeartRate) {
                    settingsRepo.updateMaxHeartRate(calculatedMaxHr)
                }
            }
        }
    }
