package com.gregor.lauritz.healthdashboard.domain.sync

import com.gregor.lauritz.healthdashboard.data.healthconnect.HeartRateMapper
import com.gregor.lauritz.healthdashboard.data.healthconnect.HrvMapper
import com.gregor.lauritz.healthdashboard.data.healthconnect.SleepDataMapper
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
import com.gregor.lauritz.healthdashboard.domain.model.Result
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.domain.repository.ScoringRepository
import com.gregor.lauritz.healthdashboard.domain.util.HeartRateFormulas
import com.gregor.lauritz.healthdashboard.domain.util.logD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
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

        /**
         * Runs the foreground sync / recalculation.
         *
         * @param onProgress optional reactive hook invoked as the walk-forward recompute advances,
         *   reporting (completedDays, totalDays) so the UI can surface determinate progress instead
         *   of a silent spinner. Invoked off the main thread.
         */
        suspend fun sync(
            windowDays: Int = 8,
            onProgress: ((current: Int, total: Int) -> Unit)? = null,
        ): Result<Unit> =
            syncMutex.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        logD("HealthSyncUseCase") { "Starting sync (window=$windowDays days)..." }
                        val today = LocalDate.now(ZoneId.systemDefault())
                        val zoneId = ZoneId.systemDefault()
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

                        val primaryDevice = prefs.primaryDeviceName
                        val filteredSleep =
                            filterByDevicePreference(sleepEntities, primaryDevice, { it.deviceName }, { it.startTime })
                        val filteredWorkouts =
                            filterByDevicePreference(
                                workoutEntities,
                                primaryDevice,
                                { it.deviceName },
                                { it.startTime },
                            )
                        val filteredHr =
                            filterByDevicePreference(hrEntities, primaryDevice, { it.deviceName }, { it.timestampMs })
                        val filteredHrv =
                            filterByDevicePreference(hrvEntities, primaryDevice, { it.deviceName }, { it.timestampMs })

                        val weightEntities = WeightDataMapper.toEntities(weightRecords)
                        val filteredWeight =
                            filterByDevicePreference(
                                weightEntities,
                                primaryDevice,
                                { it.deviceName },
                                { it.timestampMs },
                            )

                        val bodyFatEntities = BodyFatDataMapper.toEntities(bodyFatRecords)
                        val filteredBodyFat =
                            filterByDevicePreference(
                                bodyFatEntities,
                                primaryDevice,
                                { it.deviceName },
                                { it.timestampMs },
                            )

                        val bloodPressureEntities = BloodPressureDataMapper.toEntities(bloodPressureRecords)
                        val filteredBloodPressure =
                            filterByDevicePreference(
                                bloodPressureEntities,
                                primaryDevice,
                                { it.deviceName },
                                { it.timestampMs },
                            )

                        val spo2Entities = OxygenSaturationDataMapper.toEntities(spo2Records)
                        val filteredSpo2 =
                            filterByDevicePreference(
                                spo2Entities,
                                primaryDevice,
                                { it.deviceName },
                                { it.timestampMs },
                            )

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

                        // Bulk-fetch steps per day using aggregate API to prevent overlap/duplication
                        logD("HealthSyncUseCase") { "Bulk fetching steps for $windowDays days..." }
                        val stepsMap = mutableMapOf<LocalDate, Long>()
                        for (i in (windowDays - 1) downTo 0) {
                            val day = today.minusDays(i.toLong())
                            val dayStart = day.atStartOfDay(zoneId).toInstant()
                            val dayEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant()
                            val daySteps = hcRepo.readSteps(dayStart, dayEnd)
                            stepsMap[day] = daySteps
                        }

                        // One-time migration: all historical days get per-day bounded baselines + recomputed scores.
                        // Stale detection: baseline_version < 2 or NULL means old global-window computation.
                        val staleCount = dailySummaryDao.countRowsWithBaselineVersionBelow(2)
                        val migrationRange =
                            if (staleCount > 0) {
                                dailySummaryDao.getEarliestDateMs()?.let { earliestMs ->
                                    Instant.ofEpochMilli(earliestMs).atZone(zoneId).toLocalDate() to LocalDate.now(zoneId)
                                }
                            } else {
                                null
                            }
                        val migrationDays =
                            migrationRange
                                ?.let { (ChronoUnit.DAYS.between(it.first, it.second) + 1).toInt() }
                                ?.coerceAtLeast(0)
                                ?: 0

                        // Single determinate progress track across both the migration and window loops.
                        val totalDays = migrationDays + windowDays
                        var processedDays = 0
                        onProgress?.invoke(processedDays, totalDays)

                        if (migrationRange != null) {
                            logD("HealthSyncUseCase") { "Migration triggered: $staleCount stale rows found." }
                            // Clear freeze so ScoringRepositoryImpl (now using bounded variants) can recompute.
                            dailySummaryDao.clearFrozenBaselines()
                            val endDate = migrationRange.second
                            var current = migrationRange.first
                            while (!current.isAfter(endDate)) {
                                ensureActive()
                                val steps = stepsMap[current] ?: 0L
                                syncDayScoring(current, steps)
                                current = current.plusDays(1)
                                processedDays++
                                onProgress?.invoke(processedDays, totalDays)
                                // Cooperative yield: keeps the long walk-forward from starving the
                                // dispatcher and makes the recompute cancellable.
                                yield()
                            }
                            dailySummaryDao.setBaselineVersion(2)
                            logD("HealthSyncUseCase") { "Migration complete." }
                        }

                        var successCount = 0
                        var failureCount = 0

                        for (i in (windowDays - 1) downTo 0) {
                            ensureActive()
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
                            processedDays++
                            onProgress?.invoke(processedDays, totalDays)
                            yield()
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

        suspend fun catchUpSync(onProgress: ((current: Int, total: Int) -> Unit)? = null): Result<Unit> =
            sync(windowDays = 60, onProgress = onProgress)

        // Already invoked from the IO context established in [sync]; computeDailySummary
        // switches to Dispatchers.Default internally for the CPU-heavy scoring.
        private suspend fun syncDayScoring(
            day: LocalDate,
            steps: Long,
        ): Result<Unit> =
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

        private suspend fun updateCalculatedMetrics(prefs: UserPreferences) {
            // Always recalculate Max HR if auto-calculate is enabled (in case age changed via onboarding/settings)
            if (prefs.autoCalculateMaxHr) {
                val calculatedMaxHr = HeartRateFormulas.estimateMaxHr(prefs.age)
                if (calculatedMaxHr != prefs.maxHeartRate) {
                    settingsRepo.updateMaxHeartRate(calculatedMaxHr)
                }
            }
        }

        /**
         * Filter [records] so that data from [primaryDevice] is preferred. Records from other
         * devices are only retained for calendar days that have no primary-device coverage,
         * preventing duplicate entries while still allowing graceful fallback when the primary
         * device hasn't reported data for a given day.
         */
        private fun <T> filterByDevicePreference(
            records: List<T>,
            primaryDevice: String?,
            getDeviceName: (T) -> String?,
            getTimestamp: (T) -> Long,
        ): List<T> {
            if (primaryDevice == null || records.isEmpty()) return records

            val (primary, secondary) = records.partition { getDeviceName(it) == primaryDevice }

            if (primary.isEmpty()) {
                logD("DeviceFilter") {
                    "No primary device ($primaryDevice) records found, returning all records"
                }
                return records
            }
            if (secondary.isEmpty()) return primary

            val zoneId = ZoneId.systemDefault()
            var lastTimestamp = -1L
            var lastLocalDate: LocalDate? = null

            val primaryDays =
                primary.mapTo(mutableSetOf()) {
                    val ts = getTimestamp(it)
                    if (ts == lastTimestamp && lastLocalDate != null) {
                        lastLocalDate
                    } else {
                        val date = Instant.ofEpochMilli(ts).atZone(zoneId).toLocalDate()
                        lastTimestamp = ts
                        lastLocalDate = date
                        date
                    }
                }

            // Reset for secondary filtering
            lastTimestamp = -1L
            lastLocalDate = null

            val fallback =
                secondary.filter {
                    val ts = getTimestamp(it)
                    val date =
                        if (ts == lastTimestamp && lastLocalDate != null) {
                            lastLocalDate
                        } else {
                            val d = Instant.ofEpochMilli(ts).atZone(zoneId).toLocalDate()
                            lastTimestamp = ts
                            lastLocalDate = d
                            d
                        }
                    date !in primaryDays
                }

            if (fallback.isNotEmpty()) {
                logD("DeviceFilter") { "Added ${fallback.size} secondary device records as fallback" }
            }
            return primary + fallback
        }
    }
