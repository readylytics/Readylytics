package app.readylytics.health.core.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
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
 * - **Per-bucket:** [bucketZoneBands] provides per-month/octad bands. Each zone level is drawn
 *   as a single smooth polygon through bucket midpoints so the bands flow continuously with
 *   the baseline line instead of appearing as disconnected steps.
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
            drawSmoothPerBucket(context, bucketZoneBands, bounds, range)
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

    private fun drawSmoothPerBucket(
        context: CartesianDrawingContext,
        buckets: List<BucketZoneBands>,
        bounds: androidx.compose.ui.geometry.Rect,
        range: Double,
    ) {
        for (run in groupContiguousBuckets(buckets)) {
            drawSmoothRun(context, run, bounds, range)
        }
    }

    /**
     * Splits [buckets] into runs of calendar-contiguous entries (each bucket's start immediately
     * follows the previous one's end). A dropped/uncalibrated month leaves a gap in
     * [BucketZoneBands] (see `bucketBy`, which omits empty buckets rather than fabricating a
     * value), so smoothing must not bridge across it - each run is drawn as its own polygon in
     * [drawSmoothRun], leaving the gap unpainted.
     */
    private fun groupContiguousBuckets(buckets: List<BucketZoneBands>): List<List<BucketZoneBands>> {
        val runs = mutableListOf<MutableList<BucketZoneBands>>()
        for (bucket in buckets) {
            val last = runs.lastOrNull()
            if (last != null && last.last().endDayOffset == bucket.startDayOffset) {
                last.add(bucket)
            } else {
                runs.add(mutableListOf(bucket))
            }
        }
        return runs
    }

    private fun drawSmoothRun(
        context: CartesianDrawingContext,
        buckets: List<BucketZoneBands>,
        bounds: androidx.compose.ui.geometry.Rect,
        range: Double,
    ) {
        fun xForDayOffset(offset: Int): Float = bounds.left + bounds.width * (offset.toFloat() / (rangeDays - 1))

        val zoneCount = buckets.first().bands.size

        for (zoneIndex in 0 until zoneCount) {
            val path = Path()
            val zone = buckets.first().bands[zoneIndex].zone

            val firstUpper =
                buckets
                    .first()
                    .bands[zoneIndex]
                    .upperBound
                    .coerceIn(minY, maxY)
            val firstLower =
                buckets
                    .first()
                    .bands[zoneIndex]
                    .lowerBound
                    .coerceIn(minY, maxY)
            val firstLeft = xForDayOffset(buckets.first().startDayOffset)

            // Top edge — left to right through bucket midpoints
            path.moveTo(firstLeft, yToCanvas(firstUpper, bounds, range))
            for (i in buckets.indices) {
                val centerX = xForDayOffset((buckets[i].startDayOffset + buckets[i].endDayOffset) / 2)
                val upperY = yToCanvas(buckets[i].bands[zoneIndex].upperBound.coerceIn(minY, maxY), bounds, range)
                path.lineTo(centerX, upperY)
            }
            // Right edge — vertical drop
            val lastRight = xForDayOffset(buckets.last().endDayOffset)
            val lastUpper =
                buckets
                    .last()
                    .bands[zoneIndex]
                    .upperBound
                    .coerceIn(minY, maxY)
            path.lineTo(lastRight, yToCanvas(lastUpper, bounds, range))

            val lastLower =
                buckets
                    .last()
                    .bands[zoneIndex]
                    .lowerBound
                    .coerceIn(minY, maxY)
            path.lineTo(lastRight, yToCanvas(lastLower, bounds, range))

            // Bottom edge — right to left through bucket midpoints
            for (i in buckets.indices.reversed()) {
                val centerX = xForDayOffset((buckets[i].startDayOffset + buckets[i].endDayOffset) / 2)
                val lowerY = yToCanvas(buckets[i].bands[zoneIndex].lowerBound.coerceIn(minY, maxY), bounds, range)
                path.lineTo(centerX, lowerY)
            }
            // Left edge — close back to start
            path.lineTo(firstLeft, yToCanvas(firstLower, bounds, range))
            path.close()

            context.mutableDrawScope.drawPath(
                path = path,
                color = zoneColor(zone),
                style = Fill,
            )
        }
    }

    private fun yToCanvas(
        dataY: Double,
        bounds: androidx.compose.ui.geometry.Rect,
        range: Double,
    ): Float = bounds.top + bounds.height * (1f - ((dataY - minY) / range).toFloat())

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
        val topY = yToCanvas(clampedUpper, bounds, range)
        val bottomY = yToCanvas(clampedLower, bounds, range)
        context.mutableDrawScope.drawRect(
            color = color,
            topLeft = Offset(left, topY),
            size = Size(right - left, bottomY - topY),
        )
    }
}
