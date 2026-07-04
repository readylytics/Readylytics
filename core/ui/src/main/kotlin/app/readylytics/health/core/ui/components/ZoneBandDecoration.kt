package app.readylytics.health.core.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import app.readylytics.health.domain.model.ZoneBand
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration

/**
 * A Vico [Decoration] that draws semi-transparent colored zone band backgrounds
 * within the chart's actual layer bounds (respects axis offsets).
 */
class ZoneBandDecoration(
    private val zoneBands: List<ZoneBand>,
    private val bandColors: List<Color>,
    private val minY: Double,
    private val maxY: Double,
) : Decoration {
    override fun drawUnderLayers(context: CartesianDrawingContext) {
        val range = maxY - minY
        if (range <= 0.0) return
        val bounds = context.layerBounds
        zoneBands.forEachIndexed { index, band ->
            val clampedLower = band.lowerBound.coerceIn(minY, maxY)
            val clampedUpper = band.upperBound.coerceIn(minY, maxY)
            if (clampedLower >= clampedUpper) return@forEachIndexed
            // Y=0 is top of canvas; higher data values → smaller canvas Y
            val topY = bounds.top + bounds.height * (1f - ((clampedUpper - minY) / range).toFloat())
            val bottomY = bounds.top + bounds.height * (1f - ((clampedLower - minY) / range).toFloat())
            context.mutableDrawScope.drawRect(
                color = bandColors[index],
                topLeft = Offset(bounds.left, topY),
                size = Size(bounds.width, bottomY - topY),
            )
        }
    }
}
