package app.readylytics.health.core.scoring.domain.cardio

import javax.inject.Inject
import kotlin.math.exp

/**
 * Experimental Readylytics adaptation of the Materko (2018) resting-HRV VO2max regression
 * (Open Acc Biostat Bioinform 2(3). OABB.000536, fold #1).
 *
 * Published model: VO2max = -13.05 + 0.05*MeanRR + 0.12*CDR + 0.05*pNN50. This adaptation is NOT
 * the published model, and the published R2=0.76 / SEE=4.40 ml/kg/min do NOT apply. Deviations:
 *  1. CDR is omitted — it needs a raw beat-to-beat tachogram, which Health Connect does not expose
 *     (RMSSD only). No synthetic CDR proxy is added: CDR carries distributional/asymmetry
 *     information RMSSD cannot preserve.
 *  2. pNN50 is approximated from the RMSSD baseline as 200*(1 - Phi(50/rmssd)) under the assumption
 *     that successive NN differences are Normal(0, RMSSD^2); this is not measured pNN50 and the
 *     normality assumption is not guaranteed physiologically.
 *  3. MeanRR is derived as 60000 / rhrBaselineBpm from Readylytics' stable sleep/resting-HR baseline,
 *     which may be percentile-derived rather than a true arithmetic mean resting HR from a
 *     contemporaneous tachogram (possible systematic offset).
 * The original model was developed in young, healthy, physically active men and is not broadly
 * validated. Out-of-domain results return null per application-level supported bounds.
 */
class MaterkoAdaptedVo2MaxCalculator @Inject constructor() {
    private data class SupportedInputs(
        val rhrBaselineBpm: Float,
        val rmssd: Float,
    )

    fun estimate(
        rhrBaselineBpm: Float,
        hrvMuMssd: Float?,
        isCalibrated: Boolean,
    ): Float? {
        val inputs = supportedInputs(rhrBaselineBpm, hrvMuMssd, isCalibrated) ?: return null
        val meanRR = MEAN_RR_CONSTANT / inputs.rhrBaselineBpm
        val approxPnn50 = approxPnn50(inputs.rmssd)
        val raw = INTERCEPT + MEAN_RR_COEFF * meanRR + PNN50_COEFF * approxPnn50
        return raw.takeIf { it in MIN_SUPPORTED_VO2_MAX..MAX_SUPPORTED_VO2_MAX }?.toFloat()
    }

    private fun supportedInputs(
        rhrBaselineBpm: Float,
        hrvMuMssd: Float?,
        isCalibrated: Boolean,
    ): SupportedInputs? {
        val rmssd = hrvMuMssd?.let { exp(it) }
        return when {
            !isCalibrated -> null
            !rhrBaselineBpm.isFinite() || rhrBaselineBpm < MIN_PLAUSIBLE_RHR -> null
            rmssd == null || !rmssd.isFinite() || rmssd !in MIN_RMSSD_MS..MAX_RMSSD_MS -> null
            else -> SupportedInputs(rhrBaselineBpm, rmssd)
        }
    }

    internal fun approxPnn50(rmssd: Float): Float {
        val z = PNN50_THRESHOLD_MS / rmssd
        return (200.0 * (1.0 - standardNormalCdf(z))).toFloat()
    }

    internal fun standardNormalCdf(x: Double): Double = 0.5 * (1.0 + erf(x / SQRT_2))

    private fun erf(x: Double): Double =
        when {
            x == 0.0 -> 0.0
            x < 0.0 -> -erf(-x)
            else -> {
                val t = 1.0 / (1.0 + ERF_P * x)
                val poly = t * (ERF_A1 + t * (ERF_A2 + t * (ERF_A3 + t * (ERF_A4 + t * ERF_A5))))
                1.0 - poly * exp(-x * x)
            }
        }

    companion object {
        // REF: Materko 2018, OABB.000536, fold #1 (original full-model context only).
        private const val INTERCEPT = -13.05
        private const val MEAN_RR_COEFF = 0.05
        private const val PNN50_COEFF = 0.05
        private const val MEAN_RR_CONSTANT = 60_000.0
        private const val PNN50_THRESHOLD_MS = 50.0
        private const val MIN_PLAUSIBLE_RHR = 30.0
        private const val MIN_RMSSD_MS = 1.0
        private const val MAX_RMSSD_MS = 200.0
        // Application-level supported/plausibility bounds (not physiological limits).
        private const val MIN_SUPPORTED_VO2_MAX = 15.0
        private const val MAX_SUPPORTED_VO2_MAX = 95.0
        // Abramowitz–Stegun 7.1.26 erf approximation coefficients.
        private const val ERF_P = 0.3275911
        private const val ERF_A1 = 0.254829592
        private const val ERF_A2 = -0.284496736
        private const val ERF_A3 = 1.421413741
        private const val ERF_A4 = -1.453152027
        private const val ERF_A5 = 1.061405429
        private const val SQRT_2 = 1.4142135623730951
    }
}
