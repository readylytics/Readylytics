package app.readylytics.health.core.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import app.readylytics.health.core.model.domain.model.BucketZoneBands
import app.readylytics.health.core.model.domain.model.HealthZone
import app.readylytics.health.core.model.domain.model.ZoneBand
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
                context = context,
                band = band,
                color = colors[index],
                bounds = bounds,
                range = range,
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
            val zone = buckets.first().bands[zoneIndex].zone
            val path =
                buildBandPath(
                    buckets = buckets,
                    zoneIndex = zoneIndex,
                    xForDayOffset = ::xForDayOffset,
                    bounds = bounds,
                    range = range,
                )

            context.mutableDrawScope.drawPath(
                path = path,
                color = zoneColor(zone),
                style = Fill,
            )
        }
    }

    private fun buildBandPath(
        buckets: List<BucketZoneBands>,
        zoneIndex: Int,
        xForDayOffset: (Int) -> Float,
        bounds: androidx.compose.ui.geometry.Rect,
        range: Double,
    ): Path {
        val path = Path()
        val pathContext = BandPathContext(buckets, zoneIndex, xForDayOffset, bounds, range)

        buildTopAndRightEdges(path, pathContext)
        buildBottomAndLeftEdges(path, pathContext)
        path.close()

        return path
    }

    private data class BandPathContext(
        val buckets: List<BucketZoneBands>,
        val zoneIndex: Int,
        val xForDayOffset: (Int) -> Float,
        val bounds: androidx.compose.ui.geometry.Rect,
        val range: Double,
    )

    private fun buildTopAndRightEdges(
        path: Path,
        context: BandPathContext,
    ) {
        val firstUpper =
            context.buckets
                .first()
                .bands[context.zoneIndex]
                .upperBound
                .coerceIn(minY, maxY)
        val firstLeft = context.xForDayOffset(context.buckets.first().startDayOffset)

        path.moveTo(firstLeft, yToCanvas(firstUpper, context.bounds, context.range))
        for (i in context.buckets.indices) {
            val centerX =
                context.xForDayOffset(
                    (context.buckets[i].startDayOffset + context.buckets[i].endDayOffset) / 2,
                )
            val upperY =
                yToCanvas(
                    context.buckets[i]
                        .bands[context.zoneIndex]
                        .upperBound
                        .coerceIn(minY, maxY),
                    context.bounds,
                    context.range,
                )
            path.lineTo(centerX, upperY)
        }

        val lastRight = context.xForDayOffset(context.buckets.last().endDayOffset)
        val lastUpper =
            context.buckets
                .last()
                .bands[context.zoneIndex]
                .upperBound
                .coerceIn(minY, maxY)
        path.lineTo(lastRight, yToCanvas(lastUpper, context.bounds, context.range))

        val lastLower =
            context.buckets
                .last()
                .bands[context.zoneIndex]
                .lowerBound
                .coerceIn(minY, maxY)
        path.lineTo(lastRight, yToCanvas(lastLower, context.bounds, context.range))
    }

    private fun buildBottomAndLeftEdges(
        path: Path,
        context: BandPathContext,
    ) {
        val firstLower =
            context.buckets
                .first()
                .bands[context.zoneIndex]
                .lowerBound
                .coerceIn(minY, maxY)
        val firstLeft = context.xForDayOffset(context.buckets.first().startDayOffset)

        for (i in context.buckets.indices.reversed()) {
            val centerX =
                context.xForDayOffset(
                    (context.buckets[i].startDayOffset + context.buckets[i].endDayOffset) / 2,
                )
            val lowerY =
                yToCanvas(
                    context.buckets[i]
                        .bands[context.zoneIndex]
                        .lowerBound
                        .coerceIn(minY, maxY),
                    context.bounds,
                    context.range,
                )
            path.lineTo(centerX, lowerY)
        }

        path.lineTo(firstLeft, yToCanvas(firstLower, context.bounds, context.range))
    }

    private fun yToCanvas(
        dataY: Double,
        bounds: androidx.compose.ui.geometry.Rect,
        range: Double,
    ): Float = bounds.top + bounds.height * (1f - ((dataY - minY) / range).toFloat())

    private fun drawBandRect(
        context: CartesianDrawingContext,
        band: ZoneBand,
        color: Color,
        bounds: androidx.compose.ui.geometry.Rect,
        range: Double,
    ) {
        val clampedLower = band.lowerBound.coerceIn(minY, maxY)
        val clampedUpper = band.upperBound.coerceIn(minY, maxY)
        if (clampedLower >= clampedUpper) return
        val topY = yToCanvas(clampedUpper, bounds, range)
        val bottomY = yToCanvas(clampedLower, bounds, range)
        context.mutableDrawScope.drawRect(
            color = color,
            topLeft = Offset(bounds.left, topY),
            size = Size(bounds.width, bottomY - topY),
        )
    }
}
