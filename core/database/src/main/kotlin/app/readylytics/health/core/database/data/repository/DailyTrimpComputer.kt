package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.domain.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.repository.FatigueWorkoutInput
import app.readylytics.health.core.model.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.core.model.domain.scoring.WorkoutHrQuality
import app.readylytics.health.core.model.domain.scoring.WorkoutScoringIdentity
import app.readylytics.health.core.model.domain.sync.HistoricalRunIdentity
import app.readylytics.health.core.model.domain.sync.ScoringRunSnapshot
import app.readylytics.health.core.scoring.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.EverydayHrLoadResult
import app.readylytics.health.core.scoring.domain.scoring.LongInterval
import app.readylytics.health.core.scoring.domain.scoring.WorkoutInputRevision
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

class DailyTrimpComputer(
    private val dataLoader: ScoringDayDataLoader,
    private val heartRateDataLoader: ScoringHeartRateDataLoader,
    private val computeDailyTrimpUseCase: ComputeDailyTrimpUseCase,
    private val assembleEverydayLoadInputUseCase: AssembleEverydayLoadInputUseCase,
) {
    data class ProcessedWorkoutDay(
        val workouts: List<WorkoutRecordEntity>,
        val dailyTrimpRaw: Float?,
        val fatigueInputs: List<FatigueWorkoutInput>,
        val workoutModelTrimpUpdates: List<ComputeDailyTrimpUseCase.WorkoutModelTrimpUpdate> = emptyList(),
    )

    suspend fun processWorkouts(context: ScoringDayContext): ProcessedWorkoutDay {
        val workouts = dataLoader.loadWorkouts(context.dayMidnightMs, context.nextDayMidnightMs)
        val allDayExerciseHrSamples = heartRateDataLoader.loadExerciseHrSamples(workouts)
        val identity = resolveScoringIdentity(context)
        val workoutInputs = buildWorkoutInputs(workouts, allDayExerciseHrSamples)

        val dailyTrimpResult =
            computeDailyTrimpUseCase.execute(
                workouts = workoutInputs,
                prefs = context.prefs,
                rhrBaselineValue = context.initialBaselines.rhrBaselineValue,
                frozenHrMax = context.initialBaselines.frozenHrMax,
                identity = identity,
            )
        return ProcessedWorkoutDay(
            workouts = workouts,
            dailyTrimpRaw = dailyTrimpResult.totalDailyTrimpRaw,
            fatigueInputs =
                dailyTrimpResult.canonicalWorkoutTrimps.mapNotNull {
                    val trimp = it.trimp
                    if (trimp != null && trimp > 0f) {
                        FatigueWorkoutInput(
                            workoutId = it.workoutId,
                            endTimeMs = it.endTimeMs,
                            trimp = trimp,
                        )
                    } else {
                        null
                    }
                },
            workoutModelTrimpUpdates = dailyTrimpResult.workoutModelTrimpUpdates,
        )
    }

    private fun resolveScoringIdentity(context: ScoringDayContext): WorkoutScoringIdentity {
        val hrMax = context.initialBaselines.frozenHrMax ?: context.initialBaselines.hrMax
        val snapshot = ScoringRunSnapshot.capture(context.prefs, hrMax)
        val snapshotJson = Json.encodeToString(snapshot)
        val scoringSnapshotId = HistoricalRunIdentity.sha256Hex(snapshotJson)
        return WorkoutScoringIdentity(
            sourceRevision = 0L,
            scoringSnapshotId = scoringSnapshotId,
            algorithmRevision = SettingsDefaults.CURRENT_SCORING_VERSION,
        )
    }

    private suspend fun buildWorkoutInputs(
        workouts: List<WorkoutRecordEntity>,
        allDayExerciseHrSamples: List<HeartRateRecordEntity>,
    ): List<ComputeDailyTrimpUseCase.WorkoutInput> =
        workouts.map { workout ->
            val loadedSamples = heartRateDataLoader.loadWorkoutSamplesWithQuality(workout, allDayExerciseHrSamples)
            val samples =
                loadedSamples.samples.map { sample ->
                    ComputeWorkoutTrimpUseCase.HeartRateSample(
                        Instant.ofEpochMilli(sample.timestampMs),
                        sample.beatsPerMinute,
                    )
                }
            ComputeDailyTrimpUseCase.WorkoutInput(
                id = workout.id,
                startTime = workout.startTime,
                endTime = workout.endTime,
                currentModelTrimp = workout.modelTrimp,
                currentQuality =
                    workout.modelTrimpQuality?.let {
                        runCatching { WorkoutHrQuality.valueOf(it) }.getOrNull()
                    },
                currentSourceRevision = workout.modelTrimpSourceRevision,
                currentScoringSnapshotId = workout.modelTrimpSnapshotId,
                currentAlgorithmRevision = workout.modelTrimpAlgorithmRevision,
                samples = samples,
                quality = loadedSamples.quality,
                sourceRevision =
                    WorkoutInputRevision.compute(
                        workout.id,
                        workout.startTime,
                        workout.endTime,
                        workout.exerciseType,
                        workout.deviceName,
                        samples,
                    ),
            )
        }

    suspend fun resolveEverydayTrimp(
        context: ScoringDayContext,
        workouts: List<WorkoutRecordEntity>,
        session: SleepSessionEntity?,
        aggregatedSleep: SleepAggregationContext?,
        dailyTrimpRaw: Float,
    ): EverydayHrLoadResult {
        val everydayHrBuckets =
            heartRateDataLoader.loadMergedMinuteBuckets(context.dayMidnightMs, context.nextDayMidnightMs)
        val sleepIntervalsMs =
            aggregatedSleep?.allSleepIntervals
                ?: if (session != null) {
                    listOf(LongInterval(session.startTime, session.endTime))
                } else {
                    emptyList()
                }
        val workoutIntervalsMs = workouts.map { LongInterval(it.startTime, it.endTime) }
        return assembleEverydayLoadInputUseCase.execute(
            dayStartMs = context.dayMidnightMs,
            dayEndMs = context.nextDayMidnightMs,
            hrBuckets = everydayHrBuckets,
            sleepIntervalsMs = sleepIntervalsMs,
            workoutIntervalsMs = workoutIntervalsMs,
            workoutOnlyTrimp = dailyTrimpRaw,
            rhrBaseline = context.initialBaselines.rhrBaselineValue,
            hrMax = context.initialBaselines.hrMax,
            prefs = context.prefs,
        )
    }

    suspend fun resolveEverydayTrimp(
        context: ScoringDayContext,
        processed: ProcessedWorkoutDay,
        session: SleepSessionEntity?,
        aggregatedSleep: SleepAggregationContext?,
    ): EverydayHrLoadResult =
        resolveEverydayTrimp(
            context,
            processed.workouts,
            session,
            aggregatedSleep,
            processed.dailyTrimpRaw ?: 0f,
        )

    fun publishTrimpToContext(
        trimpContext: WalkForwardTrimpContext?,
        targetDate: LocalDate,
        trimpEverydayHr: Float,
        dailyTrimpRaw: Float,
        hasWorkouts: Boolean,
    ) {
        trimpContext?.let { ctx ->
            ctx.everydayTrimpByDate[targetDate] = trimpEverydayHr
            if (hasWorkouts) ctx.dailyTrimpByDate[targetDate] = dailyTrimpRaw
        }
    }
}
