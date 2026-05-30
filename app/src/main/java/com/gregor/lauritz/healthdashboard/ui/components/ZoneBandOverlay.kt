package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.gregor.lauritz.healthdashboard.domain.model.HealthZone
import com.gregor.lauritz.healthdashboard.domain.model.ZoneBand
import com.gregor.lauritz.healthdashboard.ui.theme.LocalExtendedColors

@Composable
fun ZoneBandOverlay(
    zoneBands: List<ZoneBand>,
    minY: Double,
    maxY: Double,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current
    val errorContainer = MaterialTheme.colorScheme.errorContainer

    val bandColors =
        zoneBands.map { band ->
            when (band.zone) {
                HealthZone.OPTIMAL -> extendedColors.successContainer.copy(alpha = 0.30f)
                HealthZone.NEUTRAL -> extendedColors.neutralContainer.copy(alpha = 0.20f)
                HealthZone.WARNING -> extendedColors.warningContainer.copy(alpha = 0.30f)
                HealthZone.CRITICAL -> errorContainer.copy(alpha = 0.30f)
            }
        }

    Canvas(modifier = modifier) {
        val range = maxY - minY
        if (range <= 0.0) return@Canvas
        zoneBands.forEachIndexed { index, band ->
            val clampedLower = band.lowerBound.coerceIn(minY, maxY)
            val clampedUpper = band.upperBound.coerceIn(minY, maxY)
            if (clampedLower >= clampedUpper) return@forEachIndexed
            // Y=0 is top of canvas, so higher data values → smaller canvas Y
            val topY = (size.height * (1.0 - (clampedUpper - minY) / range)).toFloat()
            val bottomY = (size.height * (1.0 - (clampedLower - minY) / range)).toFloat()
            drawRect(
                color = bandColors[index],
                topLeft = Offset(0f, topY),
                size = Size(size.width, bottomY - topY),
            )
        }
    }
}
