package app.readylytics.health.feature.dashboard

import androidx.compose.ui.graphics.Color
import app.readylytics.health.core.designsystem.StatusColors
import app.readylytics.health.domain.model.MetricStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardMetricRenderersTest {
    private val testStatusColors =
        StatusColors(
            optimal = Color(0xFF111111),
            neutral = Color(0xFF222222),
            warning = Color(0xFF333333),
            poor = Color(0xFF444444),
        )
    private val surfaceVariantColor = Color(0xFF555555)

    @Test
    fun `metricStatusColor maps status to statusColors theme roles`() {
        assertEquals(
            testStatusColors.optimal,
            metricStatusColor(MetricStatus.OPTIMAL, testStatusColors, surfaceVariantColor),
        )
        assertEquals(
            testStatusColors.neutral,
            metricStatusColor(MetricStatus.NEUTRAL, testStatusColors, surfaceVariantColor),
        )
        assertEquals(
            testStatusColors.warning,
            metricStatusColor(MetricStatus.WARNING, testStatusColors, surfaceVariantColor),
        )
        assertEquals(testStatusColors.poor, metricStatusColor(MetricStatus.POOR, testStatusColors, surfaceVariantColor))
    }

    @Test
    fun `metricStatusColor uses surfaceVariant for no data and calibrating`() {
        assertEquals(
            surfaceVariantColor,
            metricStatusColor(MetricStatus.NO_DATA, testStatusColors, surfaceVariantColor),
        )
        assertEquals(
            surfaceVariantColor,
            metricStatusColor(MetricStatus.CALIBRATING, testStatusColors, surfaceVariantColor),
        )
    }
}
