package com.gregor.lauritz.healthdashboard.domain.sync

import com.gregor.lauritz.healthdashboard.data.healthconnect.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.data.healthconnect.HeartRateMapper
import com.gregor.lauritz.healthdashboard.data.healthconnect.HrvMapper
import com.gregor.lauritz.healthdashboard.data.healthconnect.SleepDataMapper
import com.gregor.lauritz.healthdashboard.data.healthconnect.WorkoutMapper
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.preferences.AppConfigRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringRepository
import com.gregor.lauritz.healthdashboard.domain.util.HeartRateFormulas
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
        private val sleepDao: SleepSessionDao,
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val workoutDao: WorkoutDao,
        private val dailySummaryDao: DailySummaryDao,
        private val prefsRepo: UserPreferencesRepository,
        private val appConfigRepo: AppConfigRepository,
        private val scoringRepository: ScoringRepository,
    ) {
    private val syncMutex = Mutex()

    suspend fun sync(windowDays: Int = 8): Result<Unit> =
        syncMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val to = Instant.now()
                    val zoneId = ZoneId.systemDefault()
                    val from =
                        LocalDate
                            .now(zoneId)
                            .minusDays(windowDays.toLong())
                            .atStartOfDay(zoneId)
                            .toInstant()

                    val prefs = prefsRepo.userPreferences.first()

                    val sleepEntities = hcRepo.readSleepSessions(from, to).map { SleepDataMapper.mapSleepSession(it) }
                    val exerciseRecords = hcRepo.readExerciseSessions(from, to)
                    val hrRecords = hcRepo.readHeartRateSamples(from, to)
                    val hrvRecords = hcRepo.readHrvSamples(from, to)
                    android.util.Log.d(
                        "HealthSyncUseCase",
                        "Fetched HC: sleep=${sleepEntities.size} hrv=${hrvRecords.size} hr=${hrRecords.size} from=$from to=$to"
                    )
                    if (hrvRecords.isNotEmpty()) {
                        val newest = hrvRecords.maxByOrNull { it.time }?.time
                        val oldest = hrvRecords.minByOrNull { it.time }?.time
                        android.util.Log.d("HealthSyncUseCase", "HRV time range in fetch: oldest=$oldest newest=$newest")
                    }
                    if (sleepEntities.isNotEmpty()) {
                        val latestSession = sleepEntities.maxByOrNull { it.endTime }
                        android.util.Log.d(
                            "HealthSyncUseCase",
                            "Latest sleep session: id=${latestSession?.id} start=${latestSession?.startTime} end=${latestSession?.endTime}"
                        )
                    }

                    val thresholds = WorkoutMapper.zoneThresholds(
                        prefs.zone1MinBpm,
                        prefs.zone1MaxBpm,
                        prefs.zone2MaxBpm,
                        prefs.zone3MaxBpm,
                        prefs.zone4MaxBpm,
                    )
                    val initialWorkouts = exerciseRecords.map { WorkoutMapper.mapExerciseSession(it, emptyList(), thresholds) }
                    val hrEntities = HeartRateMapper.mapToEntities(hrRecords, sleepEntities, initialWorkouts)
                    val hrBySession = hrEntities.filter { it.sessionId != null }.groupBy { it.sessionId }
                    val workoutEntities = exerciseRecords.map { session ->
                        WorkoutMapper.mapExerciseSession(session, hrBySession[session.metadata.id] ?: emptyList(), thresholds)
                    }
                    val hrvEntities = HrvMapper.mapToEntities(hrvRecords, sleepEntities)

                    sleepDao.upsertAll(sleepEntities)
                    workoutDao.upsertAll(workoutEntities)
                    heartRateDao.upsertAll(hrEntities)
                    hrvDao.upsertAll(hrvEntities)

                    updateCalculatedMetrics(prefs)

                    val today = LocalDate.now(zoneId)
                    for (i in (windowDays - 1) downTo 0) {
                        val day = today.minusDays(i.toLong())
                        val dayStart = day.atStartOfDay(zoneId).toInstant()
                        val dayEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant()
                        val steps = hcRepo.readSteps(dayStart, dayEnd)

                        // Run scoring first
                        val afterScoring = scoringRepository.computeDailySummary(day)
                        val finalSummary = afterScoring.copy(
                            stepCount = steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().takeIf { it > 0 }
                        )

                        // Persist immediately so that the next day's calculation (which depends on previous days)
                        // can find this summary in the database (critical for PAI accumulation).
                        dailySummaryDao.upsert(finalSummary)
                    }

                    appConfigRepo.updateLastSyncTimestamp(System.currentTimeMillis())
                }
            }
        }

    suspend fun catchUpSync(): Result<Unit> = sync(windowDays = 60)

    private suspend fun updateCalculatedMetrics(prefs: UserPreferences) {
        // Always recalculate Max HR if auto-calculate is enabled (in case age changed via onboarding/settings)
        if (prefs.autoCalculateMaxHr) {
            val calculatedMaxHr = HeartRateFormulas.estimateMaxHr(prefs.age)
            if (calculatedMaxHr != prefs.maxHeartRate) {
                prefsRepo.updateMaxHeartRate(calculatedMaxHr)
            }
        }
    }
}
