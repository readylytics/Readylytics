package app.readylytics.health.domain.scoring

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PERF-002/WP-20/UI-002: the TRIMP-series -> ATL/CTL/strain-ratio/load-score assembly extracted out
 * of `ScoringRepositoryImpl.computeDailySummary`, for both the workout-only and everyday-HR
 * variants. Pure -- callers resolve the bucketed `dailyTrimpByDate`/`everydayTrimpByDate` maps
 * (today's freshly computed value already injected) from whatever source (a single-day DB query or
 * a shared walk-forward [app.readylytics.health.domain.repository.WalkForwardTrimpContext] slice);
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

        fun execute(
            targetDate: LocalDate,
            dailyTrimpByDate: Map<LocalDate, Float>,
            everydayTrimpByDate: Map<LocalDate, Float>,
        ): LoadSeriesResult {
            val ctl = scoringCalculator.computeCtlEmaWithDecay(dailyTrimpByDate, targetDate)
            val atl = scoringCalculator.computeAtlEmaWithDecay(dailyTrimpByDate, targetDate)
            val sr = scoringCalculator.computeStrainRatio(atl, ctl)
            val loadScore = scoringCalculator.computeLoadScore(sr)

            val ctlEverydayHr = scoringCalculator.computeCtlEmaWithDecay(everydayTrimpByDate, targetDate)
            val atlEverydayHr = scoringCalculator.computeAtlEmaWithDecay(everydayTrimpByDate, targetDate)
            val srEverydayHr = scoringCalculator.computeStrainRatio(atlEverydayHr, ctlEverydayHr)
            val loadScoreEverydayHr = scoringCalculator.computeLoadScore(srEverydayHr)

            return LoadSeriesResult(
                ctl = ctl,
                atl = atl,
                strainRatio = sr,
                loadScore = loadScore,
                ctlEverydayHr = ctlEverydayHr,
                atlEverydayHr = atlEverydayHr,
                strainRatioEverydayHr = srEverydayHr,
                loadScoreEverydayHr = loadScoreEverydayHr,
            )
        }
    }
