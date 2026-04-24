package com.gregor.lauritz.healthdashboard.domain.sync

import com.gregor.lauritz.healthdashboard.data.healthconnect.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.data.healthconnect.HeartRateMapper
import com.gregor.lauritz.healthdashboard.data.healthconnect.HrvMapper
import com.gregor.lauritz.healthdashboard.data.healthconnect.SleepDataMapper
import com.gregor.lauritz.healthdashboard.data.healthconnect.WorkoutMapper
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringRepository
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
        private val prefsRepo: UserPreferencesRepository,
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

                val sleepEntities = syncSleep(from, to)
                val workoutEntities = syncWorkouts(from, to)
                syncHeartRate(from, to, sleepEntities, workoutEntities)
                syncHrv(from, to, sleepEntities)
                updateCalculatedMetrics()

                val today = LocalDate.now(zoneId)
                repeat(windowDays) { i ->
                    scoringRepository.computeAndPersistDailySummary(today.minusDays(i.toLong()))
                }

                prefsRepo.updateLastSyncTimestamp(System.currentTimeMillis())
            }

        suspend fun catchUpSync(): Result<Unit> = sync(windowDays = 60)

        private suspend fun syncSleep(
            from: Instant,
            to: Instant,
        ) = hcRepo
            .readSleepSessions(from, to)
            .map { SleepDataMapper.mapSleepSession(it) }
            .also { sleepDao.upsertAll(it) }

        private suspend fun syncWorkouts(
            from: Instant,
            to: Instant,
        ): List<com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity> {
            val prefs = prefsRepo.userPreferences.first()
            val thresholds = WorkoutMapper.zoneThresholds(
                prefs.maxHeartRate,
                prefs.zone1MaxPercent,
                prefs.zone2MaxPercent,
                prefs.zone3MaxPercent,
                prefs.zone4MaxPercent
            )
            return hcRepo
                .readExerciseSessions(from, to)
                .map { WorkoutMapper.mapExerciseSession(it, emptyList(), thresholds) }
                .also { workoutDao.upsertAll(it) }
        }

        private suspend fun syncHeartRate(
            from: Instant,
            to: Instant,
            sleepEntities: List<com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity>,
            workoutEntities: List<com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity>,
        ) {
            val prefs = prefsRepo.userPreferences.first()
            val hrRecords = hcRepo.readHeartRateSamples(from, to)
            val hrEntities = HeartRateMapper.mapToEntities(hrRecords, sleepEntities, workoutEntities)
            heartRateDao.upsertAll(hrEntities)

            // Re-map workouts with actual HR samples for accurate zone/TRIMP calculation
            val thresholds = WorkoutMapper.zoneThresholds(
                prefs.maxHeartRate,
                prefs.zone1MaxPercent,
                prefs.zone2MaxPercent,
                prefs.zone3MaxPercent,
                prefs.zone4MaxPercent
            )
            val workoutSessions = hcRepo.readExerciseSessions(from, to)
            val remappedWorkouts =
                workoutSessions.map { session ->
                    val sessionHr = hrEntities.filter { it.sessionId == session.metadata.id }
                    WorkoutMapper.mapExerciseSession(session, sessionHr, thresholds)
                }
            workoutDao.upsertAll(remappedWorkouts)
        }

        private suspend fun syncHrv(
            from: Instant,
            to: Instant,
            sleepEntities: List<com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity>,
        ) {
            val hrvRecords = hcRepo.readHrvSamples(from, to)
            val hrvEntities = HrvMapper.mapToEntities(hrvRecords, sleepEntities)
            hrvDao.upsertAll(hrvEntities)
        }

        private suspend fun updateCalculatedMetrics() {
            val prefs = prefsRepo.userPreferences.first()
            // Always recalculate Max HR if auto-calculate is enabled (in case age changed via onboarding/settings)
            if (prefs.autoCalculateMaxHr) {
                val calculatedMaxHr = 220 - prefs.age
                if (calculatedMaxHr != prefs.maxHeartRate) {
                    prefsRepo.updateMaxHeartRate(calculatedMaxHr)
                }
            }
        }
    }
