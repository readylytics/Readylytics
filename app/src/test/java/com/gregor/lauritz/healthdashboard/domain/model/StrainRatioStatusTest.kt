package com.gregor.lauritz.healthdashboard.domain.model

import org.junit.Test
import org.junit.Assert.assertEquals

class StrainRatioStatusTest {

    // Optimal Range: 0.8–1.3
    @Test
    fun `strain ratio 0.8 returns OPTIMAL`() {
        assertEquals(MetricStatus.OPTIMAL, 0.8f.strainRatioStatus())
    }

    @Test
    fun `strain ratio 1.0 returns OPTIMAL`() {
        assertEquals(MetricStatus.OPTIMAL, 1.0f.strainRatioStatus())
    }

    @Test
    fun `strain ratio 1.15 returns OPTIMAL`() {
        assertEquals(MetricStatus.OPTIMAL, 1.15f.strainRatioStatus())
    }

    @Test
    fun `strain ratio 1.3 returns OPTIMAL`() {
        assertEquals(MetricStatus.OPTIMAL, 1.3f.strainRatioStatus())
    }

    // Neutral Range: 1.3–1.5
    @Test
    fun `strain ratio 1.31 returns NEUTRAL`() {
        assertEquals(MetricStatus.NEUTRAL, 1.31f.strainRatioStatus())
    }

    @Test
    fun `strain ratio 1.4 returns NEUTRAL`() {
        assertEquals(MetricStatus.NEUTRAL, 1.4f.strainRatioStatus())
    }

    @Test
    fun `strain ratio 1.5 returns NEUTRAL`() {
        assertEquals(MetricStatus.NEUTRAL, 1.5f.strainRatioStatus())
    }

    // Warning Range (Overtraining): 1.5–2.0
    @Test
    fun `strain ratio 1.51 returns WARNING`() {
        assertEquals(MetricStatus.WARNING, 1.51f.strainRatioStatus())
    }

    @Test
    fun `strain ratio 1.75 returns WARNING`() {
        assertEquals(MetricStatus.WARNING, 1.75f.strainRatioStatus())
    }

    @Test
    fun `strain ratio 2.0 returns WARNING`() {
        assertEquals(MetricStatus.WARNING, 2.0f.strainRatioStatus())
    }

    // Poor Range (Severe Overtraining): >2.0
    @Test
    fun `strain ratio 2.01 returns POOR`() {
        assertEquals(MetricStatus.POOR, 2.01f.strainRatioStatus())
    }

    @Test
    fun `strain ratio 2.5 returns POOR`() {
        assertEquals(MetricStatus.POOR, 2.5f.strainRatioStatus())
    }

    @Test
    fun `strain ratio 5.0 returns POOR`() {
        assertEquals(MetricStatus.POOR, 5.0f.strainRatioStatus())
    }

    // Warning Range (Under-training): 0.5–0.8
    @Test
    fun `strain ratio 0.5 returns NEUTRAL`() {
        assertEquals(MetricStatus.NEUTRAL, 0.5f.strainRatioStatus())
    }

    @Test
    fun `strain ratio 0.65 returns NEUTRAL`() {
        assertEquals(MetricStatus.NEUTRAL, 0.65f.strainRatioStatus())
    }

    @Test
    fun `strain ratio 0.79 returns NEUTRAL`() {
        assertEquals(MetricStatus.NEUTRAL, 0.79f.strainRatioStatus())
    }

    // Poor Range (Severe Under-training): <0.5
    @Test
    fun `strain ratio 0.49 returns WARNING`() {
        assertEquals(MetricStatus.WARNING, 0.49f.strainRatioStatus())
    }

    @Test
    fun `strain ratio 0.3 returns WARNING`() {
        assertEquals(MetricStatus.WARNING, 0.3f.strainRatioStatus())
    }

    @Test
    fun `strain ratio 0.0 returns WARNING`() {
        assertEquals(MetricStatus.WARNING, 0.0f.strainRatioStatus())
    }

    // Edge Cases (Invalid/Negative Values)
    @Test
    fun `negative strain ratio returns CALIBRATING`() {
        assertEquals(MetricStatus.CALIBRATING, (-0.5f).strainRatioStatus())
    }

    @Test
    fun `negative one returns CALIBRATING`() {
        assertEquals(MetricStatus.CALIBRATING, (-1.0f).strainRatioStatus())
    }

    @Test
    fun `very large strain ratio returns POOR`() {
        assertEquals(MetricStatus.POOR, 10.0f.strainRatioStatus())
    }

    @Test
    fun `zero strain ratio returns WARNING`() {
        assertEquals(MetricStatus.WARNING, 0.0f.strainRatioStatus())
    }
}
