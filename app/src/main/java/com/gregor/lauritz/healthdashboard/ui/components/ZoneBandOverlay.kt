package com.gregor.lauritz.healthdashboard.ui.components

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.gregor.lauritz.healthdashboard.domain.model.ZoneBand
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.decoration.Decoration

/**
 * A Vico [Decoration] that draws semi-transparent colored zone band backgrounds
 * within the chart's actual plotting area (respects axis offsets).
 */
class ZoneBandDecoration(
    private val zoneBands: List<ZoneBand>,
    private val bandColors: List<Color>,
    private val minY: Double,
    private val maxY: Double,
) : Decoration {
    private val paint = Paint().apply { style = Paint.Style.FILL }

    override fun onDrawBehindChart(
        context: CartesianDrawingContext,
        bounds: RectF,
    ) {
        val range = maxY - minY
        if (range <= 0.0) return
        zoneBands.forEachIndexed { index, band ->
            val clampedLower = band.lowerBound.coerceIn(minY, maxY)
            val clampedUpper = band.upperBound.coerceIn(minY, maxY)
            if (clampedLower >= clampedUpper) return@forEachIndexed
            // Y=0 is top of canvas; higher data values → smaller canvas Y
            val topY = bounds.top + bounds.height() * (1f - ((clampedUpper - minY) / range).toFloat())
            val bottomY = bounds.top + bounds.height() * (1f - ((clampedLower - minY) / range).toFloat())
            paint.color = bandColors[index].toArgb()
            context.canvas.drawRect(bounds.left, topY, bounds.right, bottomY, paint)
        }
    }
}
