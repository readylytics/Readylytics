package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.database.data.mapper.DailySummaryMapper
import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.repository.FatigueWorkoutInput
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.scoring.DayAssembly
import app.readylytics.health.core.model.domain.scoring.summaryOrNull
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScoringDayDataLoader
    @Inject
    constructor(
        private val workoutDao: WorkoutDao,
        private val sleepSessionDao: SleepSessionDao,
        private val dailySummaryDao: DailySummaryDao,
        private val transactionRunner: TransactionRunner? = null,
    ) {

        // from processWorkouts L298
        suspend fun loadWorkouts(dayMidnightMs: Long, nextDayMidnightMs: Long): List<WorkoutRecordEntity> =
            workoutDao.getWorkoutsInRange(dayMidnightMs, nextDayMidnightMs)

        // from processWorkouts L325-328
        suspend fun persistModelTrimp(
            workouts: List<WorkoutRecordEntity>,
            updates: List<ComputeDailyTrimpUseCase.WorkoutModelTrimpUpdate>,
        ) {
            if (updates.isEmpty()) return
            val updateMap = updates.associateBy { it.workoutId }
            workoutDao.upsertAll(
                workouts.filter { it.id in updateMap }.map { workout ->
                    val update = updateMap.getValue(workout.id)
                    workout.copy(
                        modelTrimp = update.modelTrimp,
                        modelTrimpQuality = update.quality.name,
                        modelTrimpSourceRevision = update.sourceRevision,
                        modelTrimpSnapshotId = update.scoringSnapshotId,
                        modelTrimpAlgorithmRevision = update.algorithmRevision,
                    )
                },
            )
        }

        // from resolveSleepAggregation L682
        suspend fun loadOverlappingSessions(fetchStartMs: Long, fetchEndMs: Long): List<SleepSessionEntity> =
            sleepSessionDao.getOverlapping(fetchStartMs, fetchEndMs)

        // from computeDailySummary L197
        suspend fun loadSessionEndingInRange(dayMidnightMs: Long, nextDayMidnightMs: Long): SleepSessionEntity? =
            sleepSessionDao.getSessionEndingInRange(dayMidnightMs, nextDayMidnightMs)

        // from single-day residual-fatigue fallback
        suspend fun loadCanonicalFatigueInputsThrough(evaluationTimeMs: Long): List<FatigueWorkoutInput> =
            workoutDao.getCanonicalFatigueInputsThrough(evaluationTimeMs)

        // from fetchWalkForwardFatigueContext
        suspend fun loadCanonicalFatigueSeed(startBeforeMs: Long): List<FatigueWorkoutInput> =
            workoutDao.getCanonicalFatigueSeed(startBeforeMs)

        suspend fun loadUnbackfilledCountBefore(
            retentionStartMs: Long,
            startBeforeMs: Long,
        ): Int = workoutDao.countUnbackfilledBefore(retentionStartMs, startBeforeMs)

        suspend fun loadUnbackfilledCountThrough(
            retentionStartMs: Long,
            evaluationTimeMs: Long,
        ): Int = workoutDao.countUnbackfilledThrough(retentionStartMs, evaluationTimeMs)

        // from persist L626
        suspend fun persistDailySummary(summary: DailySummary, zoneId: ZoneId) {
            dailySummaryDao.upsert(DailySummaryMapper.toEntity(summary, zoneId))
        }

        /**
         * C3 (WP-13): gates persisting on [assembly] having produced a genuine candidate.
         * [DayAssembly.Unavailable] is a deliberate no-op -- no write happens, and whatever was
         * previously persisted for this day is left entirely untouched. Returns whether a write
         * happened, so the caller can tell "no candidate this pass" apart from "persisted successfully."
         */
        suspend fun persistDayAssembly(
            assembly: DayAssembly,
            zoneId: ZoneId,
            workouts: List<WorkoutRecordEntity>,
            updates: List<ComputeDailyTrimpUseCase.WorkoutModelTrimpUpdate>,
        ): Boolean {
            val summary = assembly.summaryOrNull() ?: return false
            val persistAction: suspend () -> Unit = {
                persistDailySummary(summary, zoneId)
                persistModelTrimp(workouts, updates)
            }
            if (transactionRunner != null) {
                transactionRunner.runInTransaction { persistAction() }
            } else {
                persistAction()
            }
            return true
        }
    }
