package com.gregor.lauritz.healthdashboard.domain.sync

import com.gregor.lauritz.healthdashboard.data.healthconnect.HeartRateMapper
import com.gregor.lauritz.healthdashboard.data.healthconnect.HrvMapper
import com.gregor.lauritz.healthdashboard.data.healthconnect.SleepDataMapper
import com.gregor.lauritz.healthdashboard.data.healthconnect.WorkoutMapper
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepStageDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.domain.repository.ScoringRepository
import com.gregor.lauritz.healthdashboard.domain.util.HeartRateFormulas
import com.gregor.lauritz.healthdashboard.domain.util.logD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        private val dailySummaryDao: DailySummaryDao,
        private val settingsRepo: SettingsRepository,
        private val scoringRepository: ScoringRepository,
        private val transactionRunner: com.gregor.lauritz.healthdashboard.domain.repository.TransactionRunner,
    ) {
        private val syncMutex = Mutex()

        suspend fun sync(windowDays: Int = 8): Result<Unit> =
            syncMutex.withLock {
                withContext(Dispatchers.IO) {
                    runCatching {
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

                        logD("HealthSyncUseCase") {
                            "Bulk HC fetch complete: sleep=${sleepEntities.size} " +
                                "hrv_rmssd=${hrvRecords.size} hr_records=${hrRecords.size}"
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

                        logD("HealthSyncUseCase") {
                            "Device filtering: sleep=${filteredSleep.size} workouts=${filteredWorkouts.size} " +
                                "hr=${filteredHr.size} hrv=${filteredHrv.size}"
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

                        var successCount = 0
                        var failureCount = 0

                        for (i in (windowDays - 1) downTo 0) {
                            val day = today.minusDays(i.toLong())
                            val steps = stepsMap[day] ?: 0L
                            val result = syncDayScoring(day, steps)

                            result
                                .onSuccess {
                                    successCount++
                                    logD("HealthSyncUseCase") { "Day $day: SUCCESS" }
                                }.onFailure { e ->
                                    failureCount++
                                    logD("HealthSyncUseCase") { "Day $day: FAILED - ${e.message}" }
                                }
                        }

                        logD("HealthSyncUseCase") {
                            "Sync complete: $successCount succeeded, $failureCount failed"
                        }
                        settingsRepo.updateLastSyncTimestamp(System.currentTimeMillis())
                    }
                }
            }

        suspend fun catchUpSync(): Result<Unit> = sync(windowDays = 60)

        private suspend fun syncDayScoring(
            day: LocalDate,
            steps: Long,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val afterScoring = scoringRepository.computeDailySummary(day)
                    val stepCountInt = steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val finalSummary = afterScoring.copy(stepCount = stepCountInt)
                    dailySummaryDao.upsert(finalSummary)

                    logD("HealthSyncUseCase") { "Day $day: scored (steps=$steps) and summary persisted" }
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
