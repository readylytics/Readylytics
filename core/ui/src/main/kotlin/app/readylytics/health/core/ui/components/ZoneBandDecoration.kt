package app.readylytics.health.core.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import app.readylytics.health.domain.model.BucketZoneBands
import app.readylytics.health.domain.model.HealthZone
import app.readylytics.health.domain.model.ZoneBand
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration

/**
 * A Vico [Decoration] that draws semi-transparent colored zone band backgrounds
 * within the chart's actual layer bounds (respects axis offsets).
 *
 * Two modes:
 * - **Flat:** a single set of [zoneBands] colored by indexed [bandColors], drawn full-width.
 * - **Per-bucket:** [bucketZoneBands] provides per-month/octad bands with x-axis clamping,
 *   colored via [zoneColor] lookup on each band's [HealthZone].
 */
class ZoneBandDecoration(
    private val zoneBands: List<ZoneBand>,
    private val bandColors: List<Color>,
    private val minY: Double,
    private val maxY: Double,
    private val bucketZoneBands: List<BucketZoneBands>? = null,
    private val rangeDays: Int = 1,
    private val zoneColor: (HealthZone) -> Color = { _ -> Color.Transparent },
) : Decoration {
    override fun drawUnderLayers(context: CartesianDrawingContext) {
        val range = maxY - minY
        if (range <= 0.0) return
        val bounds = context.layerBounds

        if (bucketZoneBands.isNullOrEmpty()) {
            drawFlat(context, zoneBands, bandColors, bounds, range)
        } else {
            drawPerBucket(context, bucketZoneBands, bounds, range)
        }
    }

    private fun drawFlat(
        context: CartesianDrawingContext,
        bands: List<ZoneBand>,
        colors: List<Color>,
        bounds: androidx.compose.ui.geometry.Rect,
        range: Double,
    ) {
        bands.forEachIndexed { index, band ->
            drawBandRect(
                context,
                band.lowerBound,
                band.upperBound,
                colors[index],
                bounds.left,
                bounds.right,
                bounds,
                range,
            )
        }
    }

    private fun drawPerBucket(
        context: CartesianDrawingContext,
        buckets: List<BucketZoneBands>,
        bounds: androidx.compose.ui.geometry.Rect,
        range: Double,
    ) {
        for (bucket in buckets) {
            val left = bounds.left + bounds.width * (bucket.startDayOffset.toFloat() / (rangeDays - 1))
            val right = bounds.left + bounds.width * (bucket.endDayOffset.toFloat() / (rangeDays - 1))
            if (right <= left) continue
            bucket.bands.forEach { band ->
                drawBandRect(
                    context,
                    band.lowerBound,
                    band.upperBound,
                    zoneColor(band.zone),
                    left,
                    right,
                    bounds,
                    range,
                )
            }
        }
    }

    private fun drawBandRect(
        context: CartesianDrawingContext,
        lowerBound: Double,
        upperBound: Double,
        color: Color,
        left: Float,
        right: Float,
        bounds: androidx.compose.ui.geometry.Rect,
        range: Double,
    ) {
        val clampedLower = lowerBound.coerceIn(minY, maxY)
        val clampedUpper = upperBound.coerceIn(minY, maxY)
        if (clampedLower >= clampedUpper) return
        // Y=0 is top of canvas; higher data values -> smaller canvas Y
        val topY = bounds.top + bounds.height * (1f - ((clampedUpper - minY) / range).toFloat())
        val bottomY = bounds.top + bounds.height * (1f - ((clampedLower - minY) / range).toFloat())
        context.mutableDrawScope.drawRect(
            color = color,
            topLeft = Offset(left, topY),
            size = Size(right - left, bottomY - topY),
        )
    }
}
