package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.core.scoring.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.EverydayHrLoadResult
import app.readylytics.health.core.scoring.domain.scoring.LongInterval
import java.time.Instant
import java.time.LocalDate

class DailyTrimpComputer(
    private val dataLoader: ScoringDayDataLoader,
    private val computeDailyTrimpUseCase: ComputeDailyTrimpUseCase,
    private val assembleEverydayLoadInputUseCase: AssembleEverydayLoadInputUseCase,
) {
    suspend fun processWorkouts(context: ScoringDayContext): Pair<List<WorkoutRecordEntity>, Float> {
        val workouts = dataLoader.loadWorkouts(context.dayMidnightMs, context.nextDayMidnightMs)
        val allDayExerciseHrSamples = dataLoader.loadExerciseHrSamples(workouts)
        val workoutInputs =
            workouts.map { workout ->
                val workoutHrSamples = dataLoader.loadWorkoutSamples(workout, allDayExerciseHrSamples)
                ComputeDailyTrimpUseCase.WorkoutInput(
                    id = workout.id,
                    startTime = workout.startTime,
                    endTime = workout.endTime,
                    storedTrimp = workout.trimp,
                    currentModelTrimp = workout.modelTrimp,
                    samples = workoutHrSamples.map { sample ->
                        ComputeWorkoutTrimpUseCase.HeartRateSample(
                            Instant.ofEpochMilli(sample.timestampMs),
                            sample.beatsPerMinute,
                        )
                    },
                )
            }
        val dailyTrimpResult =
            computeDailyTrimpUseCase.execute(
                workoutInputs,
                context.prefs,
                context.initialBaselines.rhrBaselineValue,
                context.initialBaselines.frozenHrMax,
            )
        dataLoader.persistModelTrimp(workouts, dailyTrimpResult.workoutModelTrimpUpdates)
        return workouts to dailyTrimpResult.totalDailyTrimpRaw
    }

    suspend fun resolveEverydayTrimp(
        context: ScoringDayContext,
        workouts: List<WorkoutRecordEntity>,
        session: SleepSessionEntity?,
        aggregatedSleep: SleepAggregationContext?,
        dailyTrimpRaw: Float,
    ): EverydayHrLoadResult {
        val everydayHrBuckets = dataLoader.loadMergedMinuteBuckets(context.dayMidnightMs, context.nextDayMidnightMs)
        val sleepIntervalsMs = aggregatedSleep?.allSleepIntervals
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
