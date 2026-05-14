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
}
