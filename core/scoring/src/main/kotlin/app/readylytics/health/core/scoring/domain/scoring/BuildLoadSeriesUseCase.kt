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
        )

        /**
         * [trackingStart] is the true first-data day (earliest workout or daily summary, not
         * bounded by the 84-day TRIMP window); `null` means no data exists yet.
         *
         * The guard gates [LoadSeriesResult.loadScore] only; ATL/CTL/strain ratio stay the raw
         * physiological values so every consumer sees the same series. Load is neutral until
         * [ScoringConstants.Strain.MIN_HISTORY_DAYS_FOR_LOAD] days of history exist, and stays
         * neutral while CTL is below [ScoringConstants.Strain.MIN_CTL_FOR_LOAD] (ACWR is
         * unstable when chronic load is near zero, e.g. one session after a long layoff).
         */
        fun execute(
            targetDate: LocalDate,
            dailyTrimpByDate: Map<LocalDate, Float>,
            everydayTrimpByDate: Map<LocalDate, Float>,
            trackingStart: LocalDate?,
        ): LoadSeriesResult {
            val ctl = scoringCalculator.computeCtlEmaWithDecay(dailyTrimpByDate, targetDate)
            val atl = scoringCalculator.computeAtlEmaWithDecay(dailyTrimpByDate, targetDate)
            val sr = scoringCalculator.computeStrainRatio(atl, ctl)

            val ctlEverydayHr = scoringCalculator.computeCtlEmaWithDecay(everydayTrimpByDate, targetDate)
            val atlEverydayHr = scoringCalculator.computeAtlEmaWithDecay(everydayTrimpByDate, targetDate)
            val srEverydayHr = scoringCalculator.computeStrainRatio(atlEverydayHr, ctlEverydayHr)

            val hasHistory = hasMinimumHistory(trackingStart, targetDate)
            return LoadSeriesResult(
                ctl = ctl,
                atl = atl,
                strainRatio = sr,
                loadScore = guardedLoadScore(hasHistory, ctl, sr),
                ctlEverydayHr = ctlEverydayHr,
                atlEverydayHr = atlEverydayHr,
                strainRatioEverydayHr = srEverydayHr,
                loadScoreEverydayHr = guardedLoadScore(hasHistory, ctlEverydayHr, srEverydayHr),
            )
        }

        private fun hasMinimumHistory(
            trackingStart: LocalDate?,
            targetDate: LocalDate,
        ): Boolean {
            val historyDays = trackingStart?.let { ChronoUnit.DAYS.between(it, targetDate).toInt() + 1 } ?: 0
            return historyDays >= ScoringConstants.Strain.MIN_HISTORY_DAYS_FOR_LOAD
        }

        private fun guardedLoadScore(
            hasHistory: Boolean,
            ctl: Float,
            strainRatio: Float,
        ): Float =
            if (hasHistory && ctl >= ScoringConstants.Strain.MIN_CTL_FOR_LOAD) {
                scoringCalculator.computeLoadScore(strainRatio)
            } else {
                ScoringConstants.Strain.OPTIMAL_SWEET_SPOT_SCORE
            }
    }
