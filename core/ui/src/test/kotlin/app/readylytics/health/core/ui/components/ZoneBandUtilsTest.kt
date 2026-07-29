package app.readylytics.health.core.ui.components

import androidx.compose.ui.graphics.Color
import app.readylytics.health.core.designsystem.ExtendedColors
import app.readylytics.health.domain.model.HealthZone
import app.readylytics.health.domain.model.ZoneBand
import org.junit.Assert.assertEquals
import org.junit.Test

// Note: rememberZoneBandColors is a pure wrapper around zoneBandColors; its logic is covered by testing the underlying zoneBandColors function here.
class ZoneBandUtilsTest {
    private val dummyExtendedColors =
        ExtendedColors(
            success = Color.Green,
            onSuccess = Color.White,
            successContainer = Color.LightGray,
            onSuccessContainer = Color.Black,
            warning = Color.Yellow,
            onWarning = Color.Black,
            warningContainer = Color.Magenta,
            onWarningContainer = Color.White,
            neutralContainer = Color.Cyan,
            onNeutralContainer = Color.Black,
        )

    private val primaryContainer = Color.Blue
    private val errorContainer = Color.Red

    @Test
    fun `zoneBandColors maps health zones to correct colors and alphas`() {
        val bands =
            listOf(
                ZoneBand(80.0, 100.0, HealthZone.OPTIMAL),
                ZoneBand(60.0, 80.0, HealthZone.NEUTRAL),
                ZoneBand(40.0, 60.0, HealthZone.WARNING),
                ZoneBand(0.0, 40.0, HealthZone.CRITICAL),
            )

        val colors =
            zoneBandColors(
                bands = bands,
                extendedColors = dummyExtendedColors,
                primaryContainer = primaryContainer,
                errorContainer = errorContainer,
            )

        assertEquals(4, colors.size)
        assertEquals(primaryContainer.copy(alpha = ChartZoneAlphas.HIGH), colors[0])
        assertEquals(dummyExtendedColors.neutralContainer.copy(alpha = ChartZoneAlphas.RESTING), colors[1])
        assertEquals(dummyExtendedColors.warningContainer.copy(alpha = ChartZoneAlphas.HIGH), colors[2])
        assertEquals(errorContainer.copy(alpha = ChartZoneAlphas.HIGH), colors[3])
    }

    @Test
    fun `zoneBandColors respects custom alpha values`() {
        val bands =
            listOf(
                ZoneBand(80.0, 100.0, HealthZone.OPTIMAL),
                ZoneBand(60.0, 80.0, HealthZone.NEUTRAL),
                ZoneBand(40.0, 60.0, HealthZone.WARNING),
                ZoneBand(0.0, 40.0, HealthZone.CRITICAL),
            )

        val colors =
            zoneBandColors(
                bands = bands,
                extendedColors = dummyExtendedColors,
                primaryContainer = primaryContainer,
                errorContainer = errorContainer,
                optimalAlpha = 0.5f,
                neutralAlpha = 0.6f,
                warningAlpha = 0.7f,
                criticalAlpha = 0.8f,
            )

        assertEquals(primaryContainer.copy(alpha = 0.5f), colors[0])
        assertEquals(dummyExtendedColors.neutralContainer.copy(alpha = 0.6f), colors[1])
        assertEquals(dummyExtendedColors.warningContainer.copy(alpha = 0.7f), colors[2])
        assertEquals(errorContainer.copy(alpha = 0.8f), colors[3])
    }
}
