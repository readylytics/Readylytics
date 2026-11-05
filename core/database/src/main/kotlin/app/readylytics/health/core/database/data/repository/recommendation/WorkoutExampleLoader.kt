package app.readylytics.health.core.database.data.repository.recommendation

import app.readylytics.health.core.model.data.preferences.scoringZone
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationExample
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.repository.WorkoutRepository
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.model.domain.scoring.WorkoutLoadLevel
import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Below this the workout is a warm-up or a stray record, not an example worth showing. Mirrors the
 * eligibility threshold `SelectWorkoutRecommendationExamples` applies, so rows that could never
 * survive selection are dropped before the expensive metrics call rather than after it.
 */
private const val MIN_EXAMPLE_DURATION_MINUTES = 15

/**
 * Loads the user's own past workouts as candidate examples for the morning recommendation.
 *
 * The final load level is [GetWorkoutDisplayMetricsUseCase]'s canonical classification, not a
 * re-derived score, so an example reads exactly as the workout does everywhere else in the app.
 * That call is expensive, so rows are narrowed to the ones that could survive selection *before*
 * it runs, the 42-day summary history it needs is fetched once per window instead of per workout,
 * and classifications are refreshed on every load so corrected workouts, HR samples, and
 * RHR baselines are reflected even when workout ids and preferences are unchanged.
 */
// Injected by Hilt (singleton).
@Singleton
class WorkoutExampleLoader
    @Inject
    constructor(
        private val workoutRepository: WorkoutRepository,
        private val dailySummaryRepository: DailySummaryRepository,
        private val getWorkoutDisplayMetricsUseCase: GetWorkoutDisplayMetricsUseCase,
    ) {
        suspend fun load(
            fromMs: Long,
            throughMs: Long,
            prefs: UserPreferences,
        ): List<WorkoutRecommendationExample> {
            val candidates =
                if (throughMs <= fromMs) {
                    emptyList()
                } else {
                    workoutRepository
                        .getInRange(fromMs, throughMs)
                        .filter { it.isEligibleExample(fromMs, throughMs) }
                }
            if (candidates.isEmpty()) return emptyList()

            val historicalSummaries = loadHistoricalSummaries(candidates, prefs)
            return candidates.mapNotNull { workout ->
                resolveFinalLoad(workout, prefs, historicalSummaries)?.let { workout.toExample(it) }
            }
        }

        /**
         * The `LOAD_LOOKBACK_DAYS` (84-day) EWMA anchor window every candidate needs, fetched once
         * for the whole batch. It must not shrink below that: the stored-versus-chart ATL/CTL
         * equivalence depends on every tier anchoring its EWMA at the same `date - 84`.
         *
         * Bounded at both ends. `GetWorkoutDisplayMetricsUseCase` clamps the lower end per workout
         * anyway and evaluates its ATL/CTL EMA *at* each workout's own date, so a summary dated after
         * the newest candidate can never contribute to any candidate's result -- while an unbounded
         * `getSince` would read, and JSON-decode, every summary row through today. During a
         * historical backfill that turns a fixed 42-day window into a read of the entire remaining
         * history, once per replayed day.
         */
        private suspend fun loadHistoricalSummaries(
            candidates: List<WorkoutData>,
            prefs: UserPreferences,
        ): List<DailySummary> {
            val zone = prefs.scoringZone()
            fun midnightOf(
                epochMs: Long,
                shiftDays: Long,
            ): Long =
                Instant
                    .ofEpochMilli(epochMs)
                    .atZone(zone)
                    .toLocalDate()
                    .minusDays(shiftDays)
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli()
            return dailySummaryRepository.getInRange(
                fromMs = midnightOf(candidates.minOf { it.startTime }, ScoringConstants.LOAD_LOOKBACK_DAYS),
                toMs = midnightOf(candidates.maxOf { it.startTime }, 0L),
            )
        }

        private suspend fun resolveFinalLoad(
            workout: WorkoutData,
            prefs: UserPreferences,
            historicalSummaries: List<DailySummary>,
        ): WorkoutLoadLevel? =
            getWorkoutDisplayMetricsUseCase
                .execute(
                    workout = workout,
                    preferences = prefs,
                    historicalSummaries = historicalSummaries,
                ).classification
                ?.finalLoad

        private fun WorkoutData.isEligibleExample(
            fromMs: Long,
            throughMs: Long,
        ): Boolean =
            durationMinutes > MIN_EXAMPLE_DURATION_MINUTES &&
                exerciseType.isNotBlank() &&
                endTime > startTime &&
                startTime >= fromMs &&
                endTime <= throughMs

        private fun WorkoutData.toExample(finalLoad: WorkoutLoadLevel): WorkoutRecommendationExample =
            WorkoutRecommendationExample(
                workoutId = id,
                exerciseType = exerciseType,
                startTimeMs = startTime,
                endTimeMs = endTime,
                durationMinutes = durationMinutes,
                // A zero or non-finite average is "not recorded", never "resting" — omit it.
                averageHr = avgHr.takeIf { it.isFinite() && it > 0f },
                finalLoad = finalLoad,
            )
    }
