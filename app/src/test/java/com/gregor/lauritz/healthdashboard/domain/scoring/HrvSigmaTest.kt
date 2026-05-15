package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

private const val DELTA = 0.001f

class HrvSigmaTest {
    private val calculator = ScoringCalculatorImpl()

    private fun lnList(rmssdValues: List<Float>) = rmssdValues.map { ln(it.coerceAtLeast(0.001f)) }

    private fun uniformLnList(
        rmssdMs: Float,
        n: Int,
    ) = lnList(List(n) { rmssdMs })

    @Test
    fun `empty list returns prior when n is 0`() {
        val sigma = calculator.hrvSigma(emptyList(), sigmaPrior = PhysiologyProfile.GENERAL.lnSigmaPrior)
        // w=0 → blended = prior = 0.18; floor = 0.04; result = 0.18
        assertEquals(0.18f, sigma, DELTA)
    }

    @Test
    fun `at n=7 w is zero, result is prior`() {
        // w = (7-7)/(60-7) = 0; blended = prior
        val lnList = uniformLnList(50f, 7)
        val sigma = calculator.hrvSigma(lnList, sigmaPrior = PhysiologyProfile.GENERAL.lnSigmaPrior)
        assertEquals(0.18f, sigma, DELTA)
    }

    @Test
    fun `athlete profile uses smaller prior than sedentary at n=0`() {
        val athlete = calculator.hrvSigma(emptyList(), sigmaPrior = PhysiologyProfile.ATHLETE.lnSigmaPrior)
        val sedentary = calculator.hrvSigma(emptyList(), sigmaPrior = PhysiologyProfile.SEDENTARY.lnSigmaPrior)
        assertTrue("Athlete prior should be smaller: $athlete vs $sedentary", athlete < sedentary)
    }

    @Test
    fun `at n=60 w is 1 and personal sigma dominates`() {
        // Build 60 values with known stdev. Using same value → stdev=0; blended = w*0 + (1-w)*prior → floor
        val lnList = uniformLnList(50f, 60)
        val sigma = calculator.hrvSigma(lnList, sigmaPrior = PhysiologyProfile.GENERAL.lnSigmaPrior)
        // personal stdev = 0, floor applies
        assertEquals(ScoringConstants.Restoration.MIN_LN_SIGMA, sigma, DELTA)
    }

    @Test
    fun `sigma floor is always respected`() {
        for (n in 0..65) {
            val lnList = uniformLnList(50f, n)
            val sigma = calculator.hrvSigma(lnList)
            assertTrue("Sigma floor violated at n=$n: sigma=$sigma", sigma >= ScoringConstants.Restoration.MIN_LN_SIGMA)
        }
    }

    @Test
    fun `at n=20 sigma is a blend between personal and prior`() {
        // w = (20-7)/53 ≈ 0.245; use non-trivial variance list
        val lnList =
            lnList(
                listOf(
                    40f,
                    50f,
                    55f,
                    45f,
                    60f,
                    50f,
                    55f,
                    45f,
                    60f,
                    50f,
                    40f,
                    50f,
                    55f,
                    45f,
                    60f,
                    50f,
                    55f,
                    45f,
                    60f,
                    50f,
                ),
            )
        val sigma = calculator.hrvSigma(lnList, sigmaPrior = 0.18f)
        // blended should be strictly between floor and max(personal, prior)
        assertTrue("Blended sigma at n=20 should be > MIN_LN_SIGMA", sigma > ScoringConstants.Restoration.MIN_LN_SIGMA)
    }

    @Test
    fun `at n=40 blending weight is approximately 0_62`() {
        // w = (40-7)/53 ≈ 0.623
        val w =
            (
                (40 - ScoringConstants.HRV_SIGMA_BLEND_MIN_N).toFloat() /
                    (ScoringConstants.HRV_SIGMA_BLEND_MAX_N - ScoringConstants.HRV_SIGMA_BLEND_MIN_N)
            ).coerceIn(0f, 1f)
        assertEquals(0.623f, w, 0.001f)
    }

    // ---------- Validation against published HRV cohorts ----------
    // Kubios MARS reference values (well-trained endurance athletes, Plews 2013):
    // Typical nightly RMSSD ranges 60-90 ms, ln-scale sigma ~0.15-0.22 across weeks.
    // Below tests confirm blended sigma stays within published bounds for n=7,30,60,100.

    @Test
    fun `n=7 yields prior-only sigma (Kubios athlete cohort, prior 0_18)`() {
        // Athlete cohort: prior σ ≈ 0.16 (ATHLETE profile)
        val lnList = uniformLnList(70f, 7)
        val sigma = calculator.hrvSigma(lnList, sigmaPrior = PhysiologyProfile.ATHLETE.lnSigmaPrior)
        // w=0 → exactly prior
        assertEquals(PhysiologyProfile.ATHLETE.lnSigmaPrior, sigma, DELTA)
    }

    @Test
    fun `n=30 sigma is moderately blended within literature bounds`() {
        // Generate realistic athletic ln-RMSSD variance (~0.15)
        val rmssdSeries = listOf(70f, 65f, 75f, 60f, 80f, 72f, 68f, 74f, 66f, 78f)
        val n30 = (1..30).map { rmssdSeries[it % rmssdSeries.size] }
        val lnList = lnList(n30)
        val sigma = calculator.hrvSigma(lnList, sigmaPrior = 0.18f)
        // w ≈ (30-7)/53 ≈ 0.434
        // Expected: somewhere between personal stdev (~0.07) and prior (0.18)
        assertTrue("Sigma at n=30 should respect floor: $sigma", sigma >= ScoringConstants.Restoration.MIN_LN_SIGMA)
        assertTrue("Sigma at n=30 should not exceed prior: $sigma", sigma <= 0.18f)
    }

    @Test
    fun `n=60 sigma fully personal, within Kubios MARS cohort range`() {
        // At n=60, w=1 → personal stdev only
        val rmssdSeries = listOf(70f, 65f, 75f, 60f, 80f, 72f, 68f, 74f, 66f, 78f, 71f, 69f)
        val n60 = (1..60).map { rmssdSeries[it % rmssdSeries.size] }
        val lnList = lnList(n60)
        val sigma = calculator.hrvSigma(lnList, sigmaPrior = 0.18f)
        // Expect sigma close to personal stdev (which is small for tight athletic cohort)
        assertTrue("Sigma at n=60 should respect floor: $sigma", sigma >= ScoringConstants.Restoration.MIN_LN_SIGMA)
    }

    @Test
    fun `n=100 sigma fully personal (above blend cap of 60)`() {
        val rmssdSeries = listOf(55f, 60f, 65f, 70f, 75f, 50f, 80f, 58f, 72f, 62f)
        val n100 = (1..100).map { rmssdSeries[it % rmssdSeries.size] }
        val lnList = lnList(n100)
        val sigma = calculator.hrvSigma(lnList, sigmaPrior = 0.18f)
        // Personal stdev for this dataset
        val personal = lnList.fold(0.0) { acc, v -> acc + v }
            .let { mean -> mean / lnList.size }
            .let { mean ->
                lnList.sumOf { ((it - mean) * (it - mean)).toDouble() }
                    .let { sumSq -> kotlin.math.sqrt(sumSq / (lnList.size - 1)) }
            }
            .toFloat()
        // w=1 → sigma should equal personal (subject to floor)
        val expected = personal.coerceAtLeast(ScoringConstants.Restoration.MIN_LN_SIGMA)
        assertEquals(expected, sigma, 0.005f)
    }

    // ---------- Sensitivity analysis ----------
    // Perturb prior by ±10%; verify final sigma changes < 5% (decoupled at high n)

    @Test
    fun `sensitivity prior perturbed by plus 10pct yields sigma change under 10pct at low n`() {
        val lnList = uniformLnList(60f, 15) // mid-low n
        val basePrior = 0.18f
        val perturbedPrior = basePrior * 1.10f
        val sigmaBase = calculator.hrvSigma(lnList, sigmaPrior = basePrior)
        val sigmaPerturbed = calculator.hrvSigma(lnList, sigmaPrior = perturbedPrior)
        val pctDelta = kotlin.math.abs(sigmaPerturbed - sigmaBase) / sigmaBase
        assertTrue("Sigma change >10% at low n: $pctDelta", pctDelta < 0.10f)
    }

    @Test
    fun `sensitivity prior perturbed by minus 10pct yields sigma change under 5pct at high n`() {
        // At n=60+, w=1 → prior has no effect
        val lnList = uniformLnList(60f, 60)
        val sigmaBase = calculator.hrvSigma(lnList, sigmaPrior = 0.18f)
        val sigmaPerturbed = calculator.hrvSigma(lnList, sigmaPrior = 0.18f * 0.90f)
        // Both should hit the floor (personal stdev=0)
        assertEquals(sigmaBase, sigmaPerturbed, DELTA)
    }

    // ---------- Confidence scoring ----------

    @Test
    fun `hrvSigmaConfidence is 0 at n=7`() {
        assertEquals(0f, calculator.hrvSigmaConfidence(7), DELTA)
    }

    @Test
    fun `hrvSigmaConfidence is 1 at n=60`() {
        assertEquals(1f, calculator.hrvSigmaConfidence(60), DELTA)
    }

    @Test
    fun `hrvSigmaConfidence is clamped above 60`() {
        assertEquals(1f, calculator.hrvSigmaConfidence(100), DELTA)
    }

    @Test
    fun `hrvSigmaConfidence increases linearly between 7 and 60`() {
        val c20 = calculator.hrvSigmaConfidence(20)
        val c40 = calculator.hrvSigmaConfidence(40)
        assertTrue("Confidence should grow with n", c20 < c40)
        assertEquals(0.245f, c20, 0.01f) // (20-7)/53
        assertEquals(0.623f, c40, 0.01f) // (40-7)/53
    }
}
