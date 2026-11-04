package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PERF-002/WP-20/UI-002: the TRIMP-series -> ATL/CTL/strain-ratio/load-score assembly extracted out
 * of `ScoringRepositoryImpl.computeDailySummary`, for both the workout-only and everyday-HR
 * variants. Pure -- callers resolve the bucketed `dailyTrimpByDate`/`everydayTrimpByDate` maps
 * (today's freshly computed value already injected) from whatever source (a single-day DB query or
 * a shared walk-forward [app.readylytics.health.core.model.domain.repository.WalkForwardTrimpContext] slice);
 * this use case never touches persistence.
 */
@Singleton
class BuildLoadSeriesUseCase
    @Inject
    constructor(
        private val scoringCalculator: ScoringCalculator,
    ) {
        data class LoadSeriesResult(
            val ctl: Float,
            val atl: Float,
            val strainRatio: Float,
            val loadScore: Float,
            val ctlEverydayHr: Float,
            val atlEverydayHr: Float,
            val strainRatioEverydayHr: Float,
            val loadScoreEverydayHr: Float,
            val hasSufficientHistoryWorkoutOnly: Boolean = true,
            val hasSufficientHistoryEverydayHr: Boolean = true,
        )

        fun execute(
            targetDate: LocalDate,
            dailyTrimpByDate: Map<LocalDate, Float>,
            everydayTrimpByDate: Map<LocalDate, Float>,
        ): LoadSeriesResult {
            val ctl = scoringCalculator.computeCtlEmaWithDecay(dailyTrimpByDate, targetDate)
            val atl = scoringCalculator.computeAtlEmaWithDecay(dailyTrimpByDate, targetDate)
            val sr = scoringCalculator.computeStrainRatio(atl, ctl)

            val earliestWorkoutDate = dailyTrimpByDate.entries.filter { it.value > 0f }.minOfOrNull { it.key }
            val workoutHistoryDays =
                earliestWorkoutDate?.let {
                    ChronoUnit.DAYS.between(it, targetDate).toInt() + 1
                } ?: 0
            val hasSufficientWorkoutHistory =
                workoutHistoryDays >= ScoringConstants.Strain.MIN_HISTORY_DAYS_FOR_LOAD ||
                    ctl >= ScoringConstants.Strain.MIN_CTL_FLOOR

            val loadScore =
                if (hasSufficientWorkoutHistory) {
                    scoringCalculator.computeLoadScore(sr)
                } else {
                    ScoringConstants.Strain.OPTIMAL_SWEET_SPOT_SCORE
                }

            val ctlEverydayHr = scoringCalculator.computeCtlEmaWithDecay(everydayTrimpByDate, targetDate)
            val atlEverydayHr = scoringCalculator.computeAtlEmaWithDecay(everydayTrimpByDate, targetDate)
            val srEverydayHr = scoringCalculator.computeStrainRatio(atlEverydayHr, ctlEverydayHr)

            val earliestEverydayDate = everydayTrimpByDate.entries.filter { it.value > 0f }.minOfOrNull { it.key }
            val everydayHistoryDays =
                earliestEverydayDate?.let {
                    ChronoUnit.DAYS.between(it, targetDate).toInt() + 1
                } ?: 0
            val hasSufficientEverydayHistory =
                everydayHistoryDays >= ScoringConstants.Strain.MIN_HISTORY_DAYS_FOR_LOAD ||
                    ctlEverydayHr >= ScoringConstants.Strain.MIN_CTL_FLOOR

            val loadScoreEverydayHr =
                if (hasSufficientEverydayHistory) {
                    scoringCalculator.computeLoadScore(srEverydayHr)
                } else {
                    ScoringConstants.Strain.OPTIMAL_SWEET_SPOT_SCORE
                }

            return LoadSeriesResult(
                ctl = ctl,
                atl = atl,
                strainRatio = sr,
                loadScore = loadScore,
                ctlEverydayHr = ctlEverydayHr,
                atlEverydayHr = atlEverydayHr,
                strainRatioEverydayHr = srEverydayHr,
                loadScoreEverydayHr = loadScoreEverydayHr,
                hasSufficientHistoryWorkoutOnly = hasSufficientWorkoutHistory,
                hasSufficientHistoryEverydayHr = hasSufficientEverydayHistory,
            )
        }
    }
