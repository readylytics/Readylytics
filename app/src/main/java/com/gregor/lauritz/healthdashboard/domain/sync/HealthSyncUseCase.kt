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
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.preferences.AppConfigRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringRepository
import com.gregor.lauritz.healthdashboard.domain.util.HeartRateFormulas
import kotlinx.coroutines.flow.first
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
        suspend fun sync(windowDays: Int = 8): Result<Unit> =
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

                val thresholds = WorkoutMapper.zoneThresholds(
                    prefs.maxHeartRate,
                    prefs.zone1MaxPercent,
                    prefs.zone2MaxPercent,
                    prefs.zone3MaxPercent,
                    prefs.zone4MaxPercent,
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
                repeat(windowDays) { i ->
                    val day = today.minusDays(i.toLong())
                    val dayStart = day.atStartOfDay(zoneId).toInstant()
                    val dayEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant()
                    val dayMidnightMs = dayStart.toEpochMilli()
                    val steps = hcRepo.readSteps(dayStart, dayEnd)
                    // Run scoring first so its getByDate+copy cycle preserves any existing stepCount,
                    // then patch stepCount onto the result in a single upsert.
                    scoringRepository.computeAndPersistDailySummary(day)
                    val afterScoring = dailySummaryDao.getByDate(dayMidnightMs)
                        ?: DailySummaryEntity(dateMidnightMs = dayMidnightMs)
                    dailySummaryDao.upsert(
                        afterScoring.copy(
                            stepCount = steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().takeIf { it > 0 }
                        )
                    )
                }

                appConfigRepo.updateLastSyncTimestamp(System.currentTimeMillis())
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
