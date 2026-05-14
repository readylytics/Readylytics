package com.gregor.lauritz.healthdashboard.data.sync

import com.gregor.lauritz.healthdashboard.data.healthconnect.HeartRateMapper
import com.gregor.lauritz.healthdashboard.data.healthconnect.HrvMapper
import com.gregor.lauritz.healthdashboard.data.healthconnect.SleepDataMapper
import com.gregor.lauritz.healthdashboard.data.healthconnect.WorkoutMapper
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.model.RecordType
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
        private val sleepDao: SleepSessionDao,
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val workoutDao: WorkoutDao,
        private val dailySummaryDao: DailySummaryDao,
        private val settingsRepo: SettingsRepository,
        private val scoringRepository: ScoringRepository,
    ) {
        private val syncMutex = Mutex()

        suspend fun sync(windowDays: Int = 8): Result<Unit> =
            syncMutex.withLock {
                withContext(Dispatchers.IO) {
                    runCatching {
                        logD("HealthSyncUseCase") { "Starting sync (window=$windowDays days)..." }
                        val to = Instant.now()
                        val zoneId = ZoneId.systemDefault()
                        val from =
                            LocalDate
                                .now(zoneId)
                                .minusDays(windowDays.toLong())
                                .atStartOfDay(zoneId)
                                .toInstant()

                        val prefs = settingsRepo.userPreferences.first()

                        logD("HealthSyncUseCase") { "Reading records from Health Connect..." }
                        val sleepSessions = hcRepo.readSleepSessions(from, to)
                        val sleepEntities = sleepSessions.map { SleepDataMapper.mapSleepSession(it) }
                        val exerciseRecords = hcRepo.readExerciseSessions(from, to)
                        val hrRecords = hcRepo.readHeartRateSamples(from, to)
                        val hrvRecords = hcRepo.readHrvSamples(from, to)

                        val totalSleepStages = sleepSessions.sumOf { it.stages.size }
                        val hrSampleCounts = hrRecords.map { it.samples.size }
                        logD("HealthSyncUseCase") {
                            "Fetched HC: sleep=${sleepEntities.size} stages=$totalSleepStages " +
                                "hrv_rmssd=${hrvRecords.size} " +
                                "hr_records=${hrRecords.size} hr_total_samples=${hrSampleCounts.sum()} " +
                                "from=$from to=$to"
                        }

                        if (hrRecords.isNotEmpty()) {
                            val sources = hrRecords.map { it.metadata.dataOrigin.packageName }.distinct()
                            logD("HealthSyncUseCase") { "HR Sources: $sources per_record_samples=$hrSampleCounts" }
                        }
                        if (hrvRecords.isNotEmpty()) {
                            val newest = hrvRecords.maxByOrNull { it.time }?.time
                            val oldest = hrvRecords.minByOrNull { it.time }?.time
                            logD("HealthSyncUseCase") { "HRV time range in fetch: oldest=$oldest newest=$newest" }
                        }
                        if (sleepEntities.isNotEmpty()) {
                            val latestSession = sleepEntities.maxByOrNull { it.endTime }
                            logD("HealthSyncUseCase") {
                                "Latest sleep session: id=${latestSession?.id} start=${latestSession?.startTime} end=${latestSession?.endTime}"
                            }
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
                        val hrBySession = hrEntities.filter { it.sessionId != null }.groupBy { it.sessionId }
                        val workoutEntities =
                            exerciseRecords.map { session ->
                                val sessionSamples = hrBySession[session.metadata.id] ?: emptyList()
                                WorkoutMapper.mapExerciseSession(session, sessionSamples, thresholds)
                            }

                        val hrvEntities = HrvMapper.mapToEntities(hrvRecords, sleepEntities)

                        val (sleepHrv, restingHrv) = hrvEntities.partition { it.recordType == RecordType.SLEEP.name }
                        logD(
                            "HealthSyncUseCase",
                        ) { "HRV classified: sleep=${sleepHrv.size} resting=${restingHrv.size}" }
                        logD("HealthSyncUseCase") {
                            "HR entities: ${hrEntities.size} sleep=${hrEntities.count {
                                it.recordType == RecordType.SLEEP.name
                            }}"
                        }

                        sleepDao.upsertAll(sleepEntities)
                        workoutDao.upsertAll(workoutEntities)
                        heartRateDao.upsertAll(hrEntities)
                        hrvDao.upsertAll(hrvEntities)

                        updateCalculatedMetrics(prefs)

                        val today = LocalDate.now(zoneId)
                        val stepsMap: Map<LocalDate, Long> =
                            coroutineScope {
                                (windowDays - 1 downTo 0)
                                    .map { i ->
                                        async {
                                            val day = today.minusDays(i.toLong())
                                            val dayStart = day.atStartOfDay(zoneId).toInstant()
                                            val dayEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant()
                                            day to hcRepo.readSteps(dayStart, dayEnd)
                                        }
                                    }.awaitAll()
                                    .toMap()
                            }

                        for (i in (windowDays - 1) downTo 0) {
                            val day = today.minusDays(i.toLong())
                            val steps = stepsMap[day] ?: 0L

                            // Run scoring first
                            val afterScoring = scoringRepository.computeDailySummary(day)
                            val stepCountInt = steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                            val finalSummary =
                                afterScoring.copy(
                                    stepCount = stepCountInt,
                                )

                            // Persist immediately so that the next day's calculation (which depends on previous days)
                            // can find this summary in the database (critical for PAI accumulation).
                            dailySummaryDao.upsert(finalSummary)
                        }

                        settingsRepo.updateLastSyncTimestamp(System.currentTimeMillis())
                    }
                }
            }

        suspend fun catchUpSync(): Result<Unit> = sync(windowDays = 60)

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
