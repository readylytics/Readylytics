package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.domain.scoring.SleepScoreWeightValidator.SyntheticSample
import com.gregor.lauritz.healthdashboard.domain.scoring.SleepScoreWeightValidator.WeightSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SleepScoreWeightValidatorTest {
    private val validator = SleepScoreWeightValidator()

    @Test(expected = IllegalArgumentException::class)
    fun `WeightSet rejects weights not summing to 1`() {
        WeightSet(duration = 0.6f, architecture = 0.3f, restoration = 0.3f, version = 0) // 1.2 → reject
    }

    @Test
    fun `WeightSet accepts default weights`() {
        val w = SleepScoreWeightValidator.DEFAULT_WEIGHTS
        assertEquals(0.50f, w.duration, 0.001f)
        assertEquals(0.25f, w.architecture, 0.001f)
        assertEquals(0.25f, w.restoration, 0.001f)
    }

    @Test
    fun `computeScore is weighted sum of sub-scores`() {
        val sample = SyntheticSample(80f, 60f, 70f, readinessLabel = 75f)
        val score = validator.computeScore(sample, SleepScoreWeightValidator.DEFAULT_WEIGHTS)
        // 0.5*80 + 0.25*60 + 0.25*70 = 40 + 15 + 17.5 = 72.5
        assertEquals(72.5f, score, 0.01f)
    }

    @Test
    fun `evaluate reports perfect correlation when label equals score`() {
        val dataset =
            (0..49).map {
                val dur = Random(it).nextFloat() * 100f
                val arch = Random(it + 1).nextFloat() * 100f
                val rest = Random(it + 2).nextFloat() * 100f
                val label = 0.5f * dur + 0.25f * arch + 0.25f * rest
                SyntheticSample(dur, arch, rest, label)
            }
        val eval = validator.evaluate(dataset, SleepScoreWeightValidator.DEFAULT_WEIGHTS)
        assertEquals(1.0f, eval.pearsonR, 0.01f)
        assertEquals(1.0f, eval.rSquared, 0.01f)
        assertTrue("MAE near zero: ${eval.meanAbsError}", eval.meanAbsError < 0.1f)
    }

    @Test
    fun `selectBest identifies the matching weight set`() {
        // Labels generated from a known weighting → that weight set must be the best fit
        val targetWeights = SleepScoreWeightValidator.DURATION_LIGHT // 0.4/0.3/0.3
        val dataset =
            (0..199).map {
                val dur = Random(it).nextFloat() * 100f
                val arch = Random(it + 7).nextFloat() * 100f
                val rest = Random(it + 13).nextFloat() * 100f
                val label =
                    targetWeights.duration * dur + targetWeights.architecture * arch + targetWeights.restoration * rest
                SyntheticSample(dur, arch, rest, label)
            }
        val best = validator.selectBest(dataset, SleepScoreWeightValidator.STANDARD_CANDIDATES)
        assertEquals(targetWeights.version, best.weightSet.version)
    }

    @Test
    fun `weightsForAge returns default when adjustment disabled`() {
        val w = validator.weightsForAge(age = 25, useAgeAdjustment = false)
        assertEquals(SleepScoreWeightValidator.DEFAULT_WEIGHTS, w)
    }

    @Test
    fun `weightsForAge under 30 emphasises architecture`() {
        val w = validator.weightsForAge(age = 25, useAgeAdjustment = true)
        assertTrue("Architecture should be elevated under 30", w.architecture > 0.30f)
    }

    @Test
    fun `weightsForAge 60 plus emphasises duration`() {
        val w = validator.weightsForAge(age = 65, useAgeAdjustment = true)
        assertTrue("Duration should be elevated at 60+", w.duration >= 0.50f)
    }

    @Test
    fun `weightsForAge middle band returns default`() {
        val w = validator.weightsForAge(age = 40, useAgeAdjustment = true)
        assertEquals(SleepScoreWeightValidator.DEFAULT_WEIGHTS, w)
    }

    @Test
    fun `large synthetic cohort 1000 samples completes evaluation`() {
        val dataset =
            (0..999).map {
                val dur = Random(it).nextFloat() * 100f
                val arch = Random(it + 1).nextFloat() * 100f
                val rest = Random(it + 2).nextFloat() * 100f
                val noise = (Random(it + 3).nextFloat() - 0.5f) * 10f
                val label =
                    (0.5f * dur + 0.25f * arch + 0.25f * rest + noise).coerceIn(0f, 100f)
                SyntheticSample(dur, arch, rest, label)
            }
        val eval = validator.evaluate(dataset, SleepScoreWeightValidator.DEFAULT_WEIGHTS)
        // Noise reduces R² but should remain high (>0.9)
        assertTrue("R² with noise should remain >0.9: ${eval.rSquared}", eval.rSquared > 0.9f)
    }

    @Test
    fun `evaluate throws on empty dataset`() {
        try {
            validator.evaluate(emptyList(), SleepScoreWeightValidator.DEFAULT_WEIGHTS)
            org.junit.Assert.fail("Expected exception")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
