package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.gregor.lauritz.healthdashboard.domain.model.HealthZone
import com.gregor.lauritz.healthdashboard.domain.model.ZoneBand
import com.gregor.lauritz.healthdashboard.ui.theme.ExtendedColors
import com.gregor.lauritz.healthdashboard.ui.theme.LocalExtendedColors

// Single source of truth for chart zone-background alpha values.
// Edit here to adjust opacity globally across all Canvas and Vico charts.
object ChartZoneAlphas {
    const val RESTING = 0.15f
    const val LOW = 0.20f
    const val MODERATE = 0.25f
    const val HIGH = 0.30f
}

data class HrZoneColors(
    val zone0: Color,
    val zone1: Color,
    val zone2: Color,
    val zone3: Color,
    val zone4: Color,
    val zone5: Color,
)

@Composable
fun hrZoneColors(): HrZoneColors {
    val cs = MaterialTheme.colorScheme
    val ext = LocalExtendedColors.current
    return remember(cs, ext) {
        HrZoneColors(
            zone0 = ext.neutralContainer.copy(alpha = ChartZoneAlphas.RESTING),
            zone1 = cs.secondaryContainer.copy(alpha = ChartZoneAlphas.LOW),
            zone2 = cs.primaryContainer.copy(alpha = ChartZoneAlphas.MODERATE),
            zone3 = cs.tertiaryContainer.copy(alpha = ChartZoneAlphas.MODERATE),
            zone4 = ext.warningContainer.copy(alpha = ChartZoneAlphas.HIGH),
            zone5 = cs.errorContainer.copy(alpha = ChartZoneAlphas.HIGH),
        )
    }
}

/**
 * Utility function to generate background colors for a list of [ZoneBand]s.
 *
 * The function mirrors the colour logic used across the various trend charts
 * (RHR, HRV, Blood Pressure) while keeping the opacity values configurable.
 */
fun zoneBandColors(
    bands: List<ZoneBand>,
    extendedColors: ExtendedColors,
    primaryContainer: Color,
    errorContainer: Color,
    optimalAlpha: Float = ChartZoneAlphas.HIGH,
    neutralAlpha: Float = ChartZoneAlphas.RESTING,
    warningAlpha: Float = ChartZoneAlphas.HIGH,
    criticalAlpha: Float = ChartZoneAlphas.HIGH,
): List<Color> =
    bands.map { band ->
        when (band.zone) {
            HealthZone.OPTIMAL -> primaryContainer.copy(alpha = optimalAlpha)
            HealthZone.NEUTRAL -> extendedColors.neutralContainer.copy(alpha = neutralAlpha)
            HealthZone.WARNING -> extendedColors.warningContainer.copy(alpha = warningAlpha)
            HealthZone.CRITICAL -> errorContainer.copy(alpha = criticalAlpha)
        }
    }
