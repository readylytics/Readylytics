package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.domain.scoring.LoadMetricsFactory.LoadMetricSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadMetricsFactoryTest {
    private val factory = LoadMetricsFactory()
    private val calculator = ScoringCalculatorImpl()

    @Test
    fun `selectSource returns SLEEP_ONLY when no workout data`() {
        val source =
            factory.selectSource(
                acuteTrimp = 0f,
                chronicTrimp = 0f,
                recentDailyTrimp = emptyList(),
                hasChronicBaseline = false,
                hasAnyWorkoutData = false,
            )
        assertEquals(LoadMetricSource.SLEEP_ONLY, source)
    }

    @Test
    fun `selectSource returns ABSOLUTE when no chronic baseline`() {
        val source =
            factory.selectSource(
                acuteTrimp = 50f,
                chronicTrimp = 0f,
                recentDailyTrimp = listOf(30f, 40f, 50f),
                hasChronicBaseline = false,
                hasAnyWorkoutData = true,
            )
        assertEquals(LoadMetricSource.ABSOLUTE, source)
    }

    @Test
    fun `selectSource returns MONOTONICITY when acute exceeds chronic for 3 days`() {
        // 3 consecutive days above chronic baseline
        val source =
            factory.selectSource(
                acuteTrimp = 60f,
                chronicTrimp = 40f,
                recentDailyTrimp = listOf(20f, 30f, 50f, 60f, 70f), // last 3 > 40
                hasChronicBaseline = true,
                hasAnyWorkoutData = true,
            )
        assertEquals(LoadMetricSource.MONOTONICITY, source)
    }

    @Test
    fun `selectSource returns RATIO_ACWR for typical training day`() {
        val source =
            factory.selectSource(
                acuteTrimp = 50f,
                chronicTrimp = 45f,
                recentDailyTrimp = listOf(20f, 60f, 30f, 70f, 20f),
                hasChronicBaseline = true,
                hasAnyWorkoutData = true,
            )
        assertEquals(LoadMetricSource.RATIO_ACWR, source)
    }

    @Test
    fun `compute returns SLEEP_ONLY score from sleep and hrv inputs`() {
        val r =
            factory.compute(
                acuteTrimp = 0f,
                chronicTrimp = 0f,
                recentDailyTrimp = emptyList(),
                hasChronicBaseline = false,
                hasAnyWorkoutData = false,
                sleepScore = 80f,
                hrvScore = 60f,
                scoringCalculator = calculator,
            )
        assertEquals(LoadMetricSource.SLEEP_ONLY, r.source)
        assertEquals(70f, r.score, 0.001f) // (80+60)/2
        assertEquals(LoadMetricsFactory.CONFIDENCE_SLEEP_ONLY, r.confidence, 0.001f)
    }

    @Test
    fun `compute ABSOLUTE returns rest-day score for low TRIMP`() {
        val r =
            factory.compute(
                acuteTrimp = 0f,
                chronicTrimp = 0f,
                recentDailyTrimp = listOf(0f, 5f, 10f, 15f),
                hasChronicBaseline = false,
                hasAnyWorkoutData = true,
                scoringCalculator = calculator,
            )
        assertEquals(LoadMetricSource.ABSOLUTE, r.source)
        assertEquals(LoadMetricsFactory.SCORE_ABSOLUTE_REST_DAY, r.score, 0.001f)
    }

    @Test
    fun `compute ABSOLUTE returns high-load score for high TRIMP`() {
        val r =
            factory.compute(
                acuteTrimp = 0f,
                chronicTrimp = 0f,
                recentDailyTrimp = listOf(50f, 100f, 200f),
                hasChronicBaseline = false,
                hasAnyWorkoutData = true,
                scoringCalculator = calculator,
            )
        assertEquals(LoadMetricSource.ABSOLUTE, r.source)
        assertEquals(LoadMetricsFactory.SCORE_ABSOLUTE_HIGH_LOAD, r.score, 0.001f)
    }

    @Test
    fun `compute RATIO_ACWR delegates to ScoringCalculator`() {
        val r =
            factory.compute(
                acuteTrimp = 45f,
                chronicTrimp = 45f, // SR = 1.0 → sweet spot
                recentDailyTrimp = listOf(20f, 30f, 25f, 20f, 30f),
                hasChronicBaseline = true,
                hasAnyWorkoutData = true,
                scoringCalculator = calculator,
            )
        assertEquals(LoadMetricSource.RATIO_ACWR, r.source)
        assertEquals(100f, r.score, 0.001f) // sweet spot
        assertTrue(r.notes?.contains("ACWR=") == true)
    }

    @Test
    fun `validateAcwrConsistency warns when ACWR high but no workout`() {
        val warning = factory.validateAcwrConsistency(strainRatio = 1.8f, todayTrimp = 0f)
        assertNotNull(warning)
        assertTrue(warning!!.contains("no workout"))
    }

    @Test
    fun `validateAcwrConsistency warns when ACWR low but high TRIMP`() {
        val warning = factory.validateAcwrConsistency(strainRatio = 0.5f, todayTrimp = 200f)
        assertNotNull(warning)
    }

    @Test
    fun `validateAcwrConsistency returns null when consistent`() {
        val warning = factory.validateAcwrConsistency(strainRatio = 1.1f, todayTrimp = 50f)
        assertNull(warning)
    }

    @Test
    fun `confidence ordering matches documented evidence base`() {
        // RATIO_ACWR > MONOTONICITY > ABSOLUTE > SLEEP_ONLY
        assertTrue(LoadMetricsFactory.CONFIDENCE_RATIO_ACWR > LoadMetricsFactory.CONFIDENCE_MONOTONICITY)
        assertTrue(LoadMetricsFactory.CONFIDENCE_MONOTONICITY > LoadMetricsFactory.CONFIDENCE_ABSOLUTE)
        assertTrue(LoadMetricsFactory.CONFIDENCE_ABSOLUTE > LoadMetricsFactory.CONFIDENCE_SLEEP_ONLY)
    }
}
