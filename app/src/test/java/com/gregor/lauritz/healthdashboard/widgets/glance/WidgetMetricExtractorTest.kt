package com.gregor.lauritz.healthdashboard.widgets.glance

import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class WidgetMetricExtractorTest {
    @Test
    fun testExtractHRV_withValue() {
        // Arrange
        val summary = createDailySummary(nocturnalHrv = 45)

        // Act
        val (value, status) = WidgetMetricExtractor.extractMetricData(MetricType.HRV, summary)

        // Assert
        assertEquals(45.0, value, 0.1)
        assertEquals(MetricStatus.OPTIMAL, status) // Updated to match implementation
    }

    @Test
    fun testExtractHRV_noValue() {
        // Arrange
        val summary = createDailySummary()

        // Act
        val (value, status) = WidgetMetricExtractor.extractMetricData(MetricType.HRV, summary)

        // Assert
        assertEquals(0.0, value, 0.1)
        assertEquals(MetricStatus.CALIBRATING, status)
    }

    @Test
    fun testExtractSteps_withValue() {
        // Arrange
        val summary = createDailySummary(stepCount = 8500)

        // Act
        val (value, status) = WidgetMetricExtractor.extractMetricData(MetricType.STEPS, summary)

        // Assert
        assertEquals(8500.0, value, 0.1)
        assertEquals(MetricStatus.NEUTRAL, status)
    }

    @Test
    fun testExtractSleepScore_goodScore() {
        // Arrange
        val summary = createDailySummary(sleepScore = 85f)

        // Act
        val (value, status) = WidgetMetricExtractor.extractMetricData(MetricType.SLEEP_SCORE, summary)

        // Assert
        assertEquals(85.0, value, 0.1)
        assertEquals(MetricStatus.OPTIMAL, status)
    }

    @Test
    fun testExtractSleepScore_noScore() {
        // Arrange
        val summary = createDailySummary()

        // Act
        val (value, status) = WidgetMetricExtractor.extractMetricData(MetricType.SLEEP_SCORE, summary)

        // Assert
        assertEquals(0.0, value, 0.1)
        assertEquals(MetricStatus.CALIBRATING, status)
    }

    @Test
    fun testExtractPAI_optimal() {
        // Arrange
        val summary = createDailySummary(paiScore = 120f)

        // Act
        val (value, status) = WidgetMetricExtractor.extractMetricData(MetricType.PAI, summary)

        // Assert
        assertEquals(120.0, value, 0.1)
        assertEquals(MetricStatus.OPTIMAL, status)
    }

    @Test
    fun testExtractPAI_warning() {
        // Arrange
        val summary = createDailySummary(paiScore = 60f)

        // Act
        val (value, status) = WidgetMetricExtractor.extractMetricData(MetricType.PAI, summary)

        // Assert
        assertEquals(60.0, value, 0.1)
        assertEquals(MetricStatus.WARNING, status)
    }

    @Test
    fun testExtractPAI_poor() {
        // Arrange
        val summary = createDailySummary(paiScore = 25f)

        // Act
        val (value, status) = WidgetMetricExtractor.extractMetricData(MetricType.PAI, summary)

        // Assert
        assertEquals(25.0, value, 0.1)
        assertEquals(MetricStatus.POOR, status)
    }

    @Test
    fun testExtractStress_unimplemented() {
        // Arrange
        val summary = createDailySummary()

        // Act
        val (value, status) = WidgetMetricExtractor.extractMetricData(MetricType.STRESS, summary)

        // Assert
        assertEquals(0.0, value, 0.1)
        assertEquals(MetricStatus.CALIBRATING, status)
    }

    private fun createDailySummary(
        nocturnalHrv: Int? = null,
        nocturnalRhr: Int? = null,
        sleepScore: Float? = null,
        stepCount: Int? = null,
        paiScore: Float? = null,
    ) = DailySummary(
        date = LocalDate.parse("2024-01-01"),
        nocturnalHrv = nocturnalHrv,
        nocturnalRhr = nocturnalRhr,
        sleepScore = sleepScore,
        stepCount = stepCount,
        paiScore = paiScore,
        sleepDurationMinutes = 480,
        deepSleepPercent = 20f,
        remSleepPercent = 25f,
    )
}
