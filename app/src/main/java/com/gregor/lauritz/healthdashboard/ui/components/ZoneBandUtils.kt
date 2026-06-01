package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.ui.graphics.Color
import com.gregor.lauritz.healthdashboard.domain.model.HealthZone
import com.gregor.lauritz.healthdashboard.domain.model.ZoneBand
import com.gregor.lauritz.healthdashboard.ui.theme.ExtendedColors

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
    optimalAlpha: Float = 0.30f,
    neutralAlpha: Float = 0.15f,
    warningAlpha: Float = 0.30f,
    criticalAlpha: Float = 0.30f,
): List<Color> =
    bands.map { band ->
        when (band.zone) {
            HealthZone.OPTIMAL -> primaryContainer.copy(alpha = optimalAlpha)
            HealthZone.NEUTRAL -> extendedColors.neutralContainer.copy(alpha = neutralAlpha)
            HealthZone.WARNING -> extendedColors.warningContainer.copy(alpha = warningAlpha)
            HealthZone.CRITICAL -> errorContainer.copy(alpha = criticalAlpha)
        }
    }
