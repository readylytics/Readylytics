package app.readylytics.health.core.scoring.domain.scoring

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes HRV and RHR Z-scores against historical or frozen baselines with user overrides.
 */
@Singleton
class BaselineZScoreComputer
    @Inject
    constructor(
        private val scoringCalculator: ScoringCalculator,
    ) {
        data class ZScoreResults(
            val zHrv: Float?,
            val zRhr: Float?,
            val rhrDeltaBpm: Float?,
        )

        data class HrvZScoreParams(
            val sessionHrvSamples: List<Float>,
            val currentHrvMean: Float,
            val muHrvHistory: List<Float>,
            val effectiveSigmaHistory: List<Float>,
            val sigmaPrior: Float,
            val frozenHrvMu: Float? = null,
            val frozenHrvSigma: Float? = null,
            val hrvBaselineOverride: Float? = null,
        )

        data class RhrZScoreParams(
            val currentNocturnalRhr: Int?,
            val rhrValues: List<Int>,
            val rhrBaselineOverride: Float? = null,
            val frozenRhr: Float? = null,
            val effectiveRhrSigma: Float? = null,
            val baselineRhrValue: Int = 0,
        )

        fun computeHrvZScore(params: HrvZScoreParams): Float? {
            if (params.sessionHrvSamples.isNotEmpty()) {
                return scoringCalculator.computeHrvZScore(
                    params.currentHrvMean,
                    params.muHrvHistory,
                    params.effectiveSigmaHistory,
                    params.sigmaPrior,
                    baselineOverride = params.hrvBaselineOverride,
                    frozenLnMu = params.frozenHrvMu,
                    frozenLnSigma = params.frozenHrvSigma,
                )
            }
            return null
        }

        fun computeRhrZScore(params: RhrZScoreParams): Float? {
            val rhr = params.currentNocturnalRhr ?: return null
            return scoringCalculator.computeRhrZScore(
                rhr.toFloat(),
                params.rhrValues,
                params.frozenRhr ?: params.rhrBaselineOverride,
                params.effectiveRhrSigma,
            )
        }

        fun computeZScores(
            hrvParams: HrvZScoreParams,
            rhrParams: RhrZScoreParams,
        ): ZScoreResults {
            val zHrv = computeHrvZScore(hrvParams)
            val zRhr = computeRhrZScore(rhrParams)
            val rhrDeltaBpm =
                rhrParams.currentNocturnalRhr?.let {
                    it.toFloat() - rhrParams.baselineRhrValue.toFloat()
                }
            return ZScoreResults(
                zHrv = zHrv,
                zRhr = zRhr,
                rhrDeltaBpm = rhrDeltaBpm,
            )
        }
    }
