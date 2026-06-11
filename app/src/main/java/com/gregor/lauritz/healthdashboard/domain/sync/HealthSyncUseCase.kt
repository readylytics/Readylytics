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
import com.gregor.lauritz.healthdashboard.domain.sync.link.SessionLinkReconciler
import com.gregor.lauritz.healthdashboard.domain.util.HeartRateFormulas
import com.gregor.lauritz.healthdashboard.domain.util.logD
import com.gregor.lauritz.healthdashboard.domain.util.logE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
        private val sessionLinkReconciler: SessionLinkReconciler,
    ) {
        private val syncMutex = Mutex()

        private companion object {
            // Max concurrent Health Connect step reads during a catch-up sync.
            const val STEPS_FETCH_CONCURRENCY = 4
        }

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
                        // Migrate any legacy global "primary device" into the per-data-type map.
                        settingsRepo.migrateDeviceSelectionIfNeeded()
                        val initialPrefs = settingsRepo.userPreferences.first()

                        updateCalculatedMetrics(initialPrefs)
                        // Re-fetch preferences in case they were updated by updateCalculatedMetrics
                        val prefs = settingsRepo.userPreferences.first()

                        val windowStart = today.minusDays((windowDays - 1).toLong()).atStartOfDay(zoneId).toInstant()
                        val windowEnd = today.plusDays(1).atStartOfDay(zoneId).toInstant()

                        // Overnight sleep sessions cross midnight: a session ending today began the
                        // previous evening. Reach the raw-sample fetch back one extra day so the
                        // pre-midnight HR/HRV samples of the earliest in-window night are captured
                        // (windowDays = 1 → ingest today + yesterday). Scoring scope stays = windowDays
                        // (current-day-only refresh is unchanged); only the ingestion read widens.
                        val ingestStart = today.minusDays(windowDays.toLong()).atStartOfDay(zoneId).toInstant()

                        ingestWindow(ingestStart, windowEnd, prefs)
                        sessionLinkReconciler.reconcile(
                            startMs = ingestStart.toEpochMilli(),
                            endMs = windowEnd.toEpochMilli() - 1,
                            zoneThresholds =
                                WorkoutMapper.zoneThresholds(
                                    prefs.zone1MinBpm,
                                    prefs.zone1MaxBpm,
                                    prefs.zone2MaxBpm,
                                    prefs.zone3MaxBpm,
                                    prefs.zone4MaxBpm,
                                ),
                        )

                        val deviceByType = prefs.deviceByDataType

                        fun deviceFor(type: HealthDataType): String? =
                            deviceByType[type.name]?.takeIf { it.isNotBlank() }

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

                        // Single determinate progress track across both the migration and window loops.
                        val totalDays = windowDays
                        var processedDays = 0
                        onProgress?.invoke(processedDays, totalDays)

                        var successCount = 0
                        var failureCount = 0

                        val scoringStartMs =
                            today
                                .minusDays((windowDays - 1).coerceAtLeast(0).toLong())
                                .atStartOfDay(zoneId)
                                .toInstant()
                                .toEpochMilli()
                        dailySummaryDao.clearFrozenBaselinesBetween(scoringStartMs, windowEnd.toEpochMilli())

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

        /**
         * Full historical resync over [startDate]..[endDate] (inclusive), bounded by the caller from
         * the user's data-retention setting. Health Connect is re-read in [chunkDays]-day chunks (with
         * bounded backoff to ride out rate limits), then a single walk-forward recompute rebuilds every
         * day's scores via the unchanged [ScoringRepository.computeDailySummary] path.
         *
         * Idempotent by construction: ingestion upserts by stable Health Connect record id (overlaps
         * replace, never duplicate) and no blanket delete is performed, so a worker killed/failed
         * mid-pass leaves prior valid data intact and a retry re-runs the same range cleanly.
         *
         * @param onProgress reports (completed, total) across both the ingestion and recompute phases.
         */
        suspend fun resyncRange(
            startDate: LocalDate,
            endDate: LocalDate,
            chunkDays: Int = 30,
            onProgress: ((current: Int, total: Int) -> Unit)? = null,
        ): Result<Unit> =
            syncMutex.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        val zoneId = ZoneId.systemDefault()
                        logD("HealthSyncUseCase") { "Full resync $startDate..$endDate (chunk=$chunkDays days)" }

                        val initialPrefs = settingsRepo.userPreferences.first()
                        updateCalculatedMetrics(initialPrefs)
                        val prefs = settingsRepo.userPreferences.first()

                        val totalDays = (ChronoUnit.DAYS.between(startDate, endDate) + 1).toInt().coerceAtLeast(0)
                        onProgress?.invoke(0, totalDays)

                        // --- Ingestion phase: chunked HC re-fetch + idempotent upsert ---
                        val stepsMap = mutableMapOf<LocalDate, Long>()
                        val deviceByType = prefs.deviceByDataType

                        fun deviceFor(type: HealthDataType): String? =
                            deviceByType[type.name]?.takeIf { it.isNotBlank() }

                        val stepsDevice = deviceFor(HealthDataType.STEPS)
                        var chunkStart = startDate
                        while (!chunkStart.isAfter(endDate)) {
                            ensureActive()
                            // Exclusive upper bound, capped at the day after endDate.
                            val chunkEndExclusive = minOf(chunkStart.plusDays(chunkDays.toLong()), endDate.plusDays(1))

                            // Ingest from the previous day for every chunk to capture overnight sleep
                            // sessions that began before this chunk's lower boundary.
                            val ingestFromDate = chunkStart.minusDays(1)
                            val windowStart = ingestFromDate.atStartOfDay(zoneId).toInstant()
                            val windowEnd = chunkEndExclusive.atStartOfDay(zoneId).toInstant()

                            retryWithBackoff {
                                ingestWindow(
                                    windowStart = windowStart,
                                    windowEnd = windowEnd,
                                    prefs = prefs,
                                )
                            }
                            if (stepsDevice != null) {
                                val stepsWindowStart = chunkStart.atStartOfDay(zoneId).toInstant()
                                val stepsRecords =
                                    retryWithBackoff {
                                        hcRepo.readStepsRecords(stepsWindowStart, windowEnd)
                                    }
                                val stepEntries =
                                    DeviceSourceFilter.filterToDevice(
                                        StepsMapper.toStepEntries(stepsRecords),
                                        stepsDevice,
                                    ) { it.deviceName }
                                stepsMap.putAll(StepsMapper.sumByDay(stepEntries, zoneId))
                            }

                            var day = chunkStart
                            while (day.isBefore(chunkEndExclusive)) {
                                ensureActive()
                                if (stepsDevice == null) {
                                    val dayStart = day.atStartOfDay(zoneId).toInstant()
                                    val dayEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant()
                                    stepsMap[day] = retryWithBackoff { hcRepo.readSteps(dayStart, dayEnd) }
                                }
                                day = day.plusDays(1)
                                yield()
                            }
                            chunkStart = chunkEndExclusive
                        }

                        // --- Reconcile phase: chunk-independent session linkage ---
                        // Re-derives (recordType, sessionId) for every HR/HRV sample in range from the
                        // *complete* set of sleep + workout sessions, and recomputes affected workout
                        // metrics. Without this, a night straddling a chunk boundary could have its
                        // samples split across two Health Connect fetch windows, each tagging only the
                        // subset it saw - making linkage (and everything derived from it) depend on
                        // chunk alignment, which itself depends on the retention setting.
                        run {
                            val reconcileStartMs = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
                            val reconcileEndMs =
                                endDate
                                    .plusDays(1)
                                    .atStartOfDay(zoneId)
                                    .toInstant()
                                    .toEpochMilli() - 1
                            val zoneThresholds =
                                WorkoutMapper.zoneThresholds(
                                    prefs.zone1MinBpm,
                                    prefs.zone1MaxBpm,
                                    prefs.zone2MaxBpm,
                                    prefs.zone3MaxBpm,
                                    prefs.zone4MaxBpm,
                                )
                            sessionLinkReconciler.reconcile(reconcileStartMs, reconcileEndMs, zoneThresholds)
                        }

                        // --- Recompute phase: walk-forward over the full range ---
                        // Clear frozen snapshots for the exact range so bounded baseline variants
                        // recompute per day and recent sync/resync use the same baseline path.
                        dailySummaryDao.clearFrozenBaselinesBetween(
                            fromMs = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                            toExclusiveMs =
                                endDate
                                    .plusDays(1)
                                    .atStartOfDay(zoneId)
                                    .toInstant()
                                    .toEpochMilli(),
                        )
                        var day = startDate
                        var recomputedDays = 0
                        while (!day.isAfter(endDate)) {
                            ensureActive()
                            val stepsForDay = if (stepsDevice != null) stepsMap[day] ?: 0L else stepsMap[day]
                            syncDayScoring(day, stepsForDay)
                            recomputedDays++
                            onProgress?.invoke(recomputedDays, totalDays)
                            day = day.plusDays(1)
                            yield()
                        }

                        settingsRepo.updateLastSyncTimestamp(System.currentTimeMillis())
                        logD("HealthSyncUseCase") { "Full resync complete ($totalDays days)" }
                        Result.success(Unit)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure("Full resync failed", "RESYNC_ERROR")
                    }
                }
            }

        /**
         * Retries [block] with bounded exponential backoff. Used to ride out transient Health Connect
         * rate-limit / IO failures during a chunked resync. Cancellation is never swallowed.
         */
        private suspend fun <T> retryWithBackoff(
            maxAttempts: Int = 4,
            initialDelayMs: Long = 1_000,
            block: suspend () -> T,
        ): T {
            var attempt = 0
            var delayMs = initialDelayMs
            while (true) {
                try {
                    return block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attempt++
                    if (attempt >= maxAttempts) throw e
                    logD("HealthSyncUseCase") { "Resync chunk failed (attempt $attempt), backing off ${delayMs}ms" }
                    delay(delayMs)
                    delayMs *= 2
                }
            }
        }

        /**
         * Reads one Health Connect window, maps + device-filters it, and upserts every record type into
         * Room in a single transaction. Shared by the recent-window [sync] and the chunked [resyncRange].
         */
        private suspend fun ingestWindow(
            windowStart: Instant,
            windowEnd: Instant,
            prefs: UserPreferences,
        ) {
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
        }

        // Already invoked from the IO context established in [sync]; computeDailySummary
        // switches to Dispatchers.Default internally for the CPU-heavy scoring.
        //
        // [steps] is null when no fresh step count is available for [day] (older historical days
        // outside the sync window). In that case the existing step count is preserved — note that
        // computeDailySummary copies the stored summary forward without touching stepCount — so a
        // historical recompute never zeroes out the user's step history.
        private suspend fun syncDayScoring(
            day: LocalDate,
            steps: Long?,
        ): Result<Unit> =
            try {
                val afterScoring = scoringRepository.computeDailySummary(day)
                val finalSummary =
                    if (steps != null) {
                        afterScoring.copy(stepCount = steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    } else {
                        afterScoring
                    }
                dailySummaryDao.upsert(finalSummary)

                logD("HealthSyncUseCase") {
                    "Day $day: scored (steps=${steps?.toString() ?: "preserved"}) and summary persisted"
                }
                Result.success(Unit)
            } catch (e: Exception) {
                logE("HealthSyncUseCase", e) { "Day $day sync failed" }
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
    }
