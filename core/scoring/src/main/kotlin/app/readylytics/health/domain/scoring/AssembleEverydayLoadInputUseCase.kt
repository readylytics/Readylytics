package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.model.HrMinuteBucketRow
import app.readylytics.health.domain.preferences.UserPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PERF-006/WP-21/UI-002: the everyday-HR load assembly extracted out of
 * `ScoringRepositoryImpl.computeDailySummary`. Pure -- the caller resolves the day's already
 * SQL-bucketed [HrMinuteBucketRow] list (`HeartRateDao.getMinuteBuckets`) and the sleep/workout
 * exclusion intervals; this use case only builds the [EverydayHrLoadInput] and runs
 * [EverydayHeartRateLoadCalculator].
 */
@Singleton
class AssembleEverydayLoadInputUseCase
    @Inject
    constructor() {
        fun execute(
            dayStartMs: Long,
            dayEndMs: Long,
            hrBuckets: List<HrMinuteBucketRow>,
            sleepIntervalsMs: List<LongInterval>,
            workoutIntervalsMs: List<LongInterval>,
            workoutOnlyTrimp: Float,
            rhrBaseline: Float,
            hrMax: Float,
            prefs: UserPreferences,
        ): EverydayHrLoadResult =
            EverydayHeartRateLoadCalculator.calculate(
                EverydayHrLoadInput(
                    dayStartMs = dayStartMs,
                    dayEndMs = dayEndMs,
                    hrBuckets = hrBuckets,
                    sleepIntervalsMs = sleepIntervalsMs,
                    workoutIntervalsMs = workoutIntervalsMs,
                    workoutOnlyTrimp = workoutOnlyTrimp,
                    rhrBaseline = rhrBaseline,
                    hrMax = hrMax,
                    prefs = prefs,
                ),
            )
    }
