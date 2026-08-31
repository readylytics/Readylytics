package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.scoring.domain.scoring.components.RestorationWeights
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles restoration sub-score from HRV and RHR Z-scores with saturation and late-nadir adjustments.
 */
@Singleton
class RestorationScoreAssembler
    @Inject
    constructor(
        private val scoringCalculator: ScoringCalculator,
    ) {
        data class RestorationScoreResult(
            val sRest: Float,
            val hrvScore: Float?,
            val rhrScore: Float?,
        )

        data class RestorationParams(
            val zHrv: Float?,
            val zRhr: Float?,
            val restorationWeights: RestorationWeights? = null,
            val saturationZ: Float = ScoringConstants.HRV_SCORE_SATURATION_Z,
            val isLateNadir: Boolean = false,
        )

        fun assembleRestorationScore(params: RestorationParams): RestorationScoreResult {
            val zHrv = params.zHrv
            val zRhr = params.zRhr

            val rawHrvScore = zHrv?.let { scoringCalculator.computeHrvScore(it, params.saturationZ) }
            val effectiveHrvScore = rawHrvScore ?: scoringCalculator.computeHrvScore(0f, params.saturationZ)

            val rawRhrScore = zRhr?.let { (50f - 25f * it).coerceIn(0f, 100f) }
            val effectiveRhrScore = rawRhrScore ?: 50f

            val weights = params.restorationWeights
            var sRest =
                if (weights != null) {
                    weights.hrvWeight * effectiveHrvScore + weights.rhrWeight * effectiveRhrScore
                } else {
                    ScoringConstants.Restoration.WEIGHT_HRV_SCORE * effectiveHrvScore +
                        ScoringConstants.Restoration.WEIGHT_RHR_SCORE * effectiveRhrScore
                }

            if (params.isLateNadir) {
                sRest *= ScoringConstants.Restoration.LATE_NADIR_PENALTY
            }

            return RestorationScoreResult(
                sRest = sRest,
                hrvScore = rawHrvScore,
                rhrScore = rawRhrScore,
            )
        }
    }
