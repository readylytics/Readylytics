package app.readylytics.health.core.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.readylytics.health.core.designsystem.dimens
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val GAUGE_VALUE_MIN_FONT_SIZE = 16.sp
private val GAUGE_UNIT_MIN_FONT_SIZE = 9.sp

@Composable
fun metricVisualizationTrackColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)

internal data class HorseshoeGaugeGeometry(
    val radius: Float,
    val center: Offset,
    val topLeft: Offset,
    val arcSize: Size,
    val startAngle: Float = 150f,
    val sweepAngle: Float = 240f,
)

internal fun resolveHorseshoeGaugeGeometry(
    canvasSize: Size,
    maximumStrokeWidthPx: Float,
): HorseshoeGaugeGeometry {
    val safeRadius =
        minOf(
            (canvasSize.width - maximumStrokeWidthPx) / 2f,
            (canvasSize.height - maximumStrokeWidthPx) / 1.5f,
        ).coerceAtLeast(0f)
    val center = Offset(canvasSize.width / 2f, safeRadius + maximumStrokeWidthPx / 2f)
    return HorseshoeGaugeGeometry(
        radius = safeRadius,
        center = center,
        topLeft = Offset(center.x - safeRadius, center.y - safeRadius),
        arcSize = Size(safeRadius * 2f, safeRadius * 2f),
    )
}

// The active arc is stroked with a round cap that overhangs its end angle by roughly
// `activeStrokeWidthPx / 2 / radius` radians. Converted to the same 0..1 fraction-of-sweep units
// as tickFractions/progressToDraw so ticks that fall inside that overhang can be hidden (ticks are
// drawn after the fill and would otherwise render on top of it).
internal fun arcTickCapCoverageFraction(
    activeStrokeWidthPx: Float,
    radius: Float,
    sweepAngle: Float,
): Float {
    if (activeStrokeWidthPx <= 0f || radius <= 0f || sweepAngle <= 0f) return 0f
    val overhangRadians = activeStrokeWidthPx / 2f / radius
    return Math.toDegrees(overhangRadians.toDouble()).toFloat() / sweepAngle
}

/**
 * Width/height bounds for the gauge's value/unit overlay, derived from the same
 * [HorseshoeGaugeGeometry] the track is drawn with so the text can never legitimately
 * overlap the stroke. Width is twice the chord half-width at the block's vertical
 * center ([textBlockCenterYOffsetPx] from the circle center), on the inner circle
 * (radius minus [trackInsetPx]); height is the inner circle's diameter. This keeps
 * short values at full size (their natural width is below the chord) while forcing
 * long values to auto-size down to fit. Degenerate/oversized inputs clamp to 0.
 */
internal fun resolveGaugeTextBoundsPx(
    geometry: HorseshoeGaugeGeometry,
    trackInsetPx: Float,
    textBlockCenterYOffsetPx: Float,
): Size {
    val innerRadius = (geometry.radius - trackInsetPx).coerceAtLeast(0f)
    val chordHalfWidth =
        sqrt(maxOf(0f, innerRadius * innerRadius - textBlockCenterYOffsetPx * textBlockCenterYOffsetPx))
    return Size(width = chordHalfWidth * 2f, height = innerRadius * 2f)
}

@Composable
fun M3MetricGauge(
    markerFraction: Float?,
    activeColor: Color,
    markerColor: Color,
    modifier: Modifier = Modifier,
    animateMarker: Boolean = true,
) {
    val clampedFraction = markerFraction?.coerceIn(0f, 1f)
    val trackColor = metricVisualizationTrackColor()
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    val trackThickness = MaterialTheme.dimens.metricTrackThickness
    val markerDiameter = MaterialTheme.dimens.metricGaugeMarkerDiameter
    val tickDiameter = MaterialTheme.dimens.metricGaugeTickDiameter

    val animatedProgress by animateFloatAsState(
        targetValue = clampedFraction ?: 0f,
        animationSpec =
            if (animateMarker) {
                tween(
                    durationMillis = 800,
                    easing = FastOutSlowInEasing,
                )
            } else {
                tween(durationMillis = 0)
            },
        label = "gauge_progress",
    )

    val progressToDraw = if (animateMarker) animatedProgress else (clampedFraction ?: 0f)

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidthPx = trackThickness.toPx()
            val markerRadiusPx = markerDiameter.toPx() / 2f
            val tickRadiusPx = tickDiameter.toPx() / 2f
            val activeStrokeWidthPx = (trackThickness + 2.dp).toPx()
            val geometry = resolveHorseshoeGaugeGeometry(size, activeStrokeWidthPx)

            drawArc(
                color = trackColor,
                startAngle = geometry.startAngle,
                sweepAngle = geometry.sweepAngle,
                useCenter = false,
                topLeft = geometry.topLeft,
                size = geometry.arcSize,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
            )

            val tickFractions = floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f)
            val activeSweep = geometry.sweepAngle * progressToDraw
            val activeEndAngle = geometry.startAngle + activeSweep

            val capCoverageFraction =
                if (progressToDraw > 0f) {
                    arcTickCapCoverageFraction(activeStrokeWidthPx, geometry.radius, geometry.sweepAngle)
                } else {
                    0f
                }
            tickFractions
                .filter { it > progressToDraw + capCoverageFraction }
                .forEach { fraction ->
                    val angle = Math.toRadians((geometry.startAngle + geometry.sweepAngle * fraction).toDouble())
                    drawCircle(
                        color = tickColor,
                        radius = tickRadiusPx,
                        center =
                            Offset(
                                geometry.center.x + geometry.radius * cos(angle).toFloat(),
                                geometry.center.y + geometry.radius * sin(angle).toFloat(),
                            ),
                    )
                }

            if (markerFraction != null && progressToDraw > 0f) {
                drawArc(
                    color = activeColor,
                    startAngle = geometry.startAngle,
                    sweepAngle = activeSweep,
                    useCenter = false,
                    topLeft = geometry.topLeft,
                    size = geometry.arcSize,
                    style = Stroke(width = activeStrokeWidthPx, cap = StrokeCap.Round),
                )
                val markerAngle = Math.toRadians(activeEndAngle.toDouble())
                drawCircle(
                    color = markerColor,
                    radius = markerRadiusPx,
                    center =
                        Offset(
                            geometry.center.x + geometry.radius * cos(markerAngle).toFloat(),
                            geometry.center.y + geometry.radius * sin(markerAngle).toFloat(),
                        ),
                )
            }
        }
    }
}

@Composable
fun M3MetricGaugeWithValue(
    markerFraction: Float?,
    activeColor: Color,
    markerColor: Color,
    valueText: String,
    unitText: String,
    valueColor: Color,
    unitColor: Color,
    modifier: Modifier = Modifier,
    animateMarker: Boolean = true,
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val trackThickness = MaterialTheme.dimens.metricTrackThickness
        val verticalOffset = MaterialTheme.dimens.metricGaugeValueVerticalOffset
        val unitSpacing = MaterialTheme.dimens.metricGaugeValueUnitSpacing

        val activeStrokeWidthPx = with(density) { (trackThickness + 2.dp).toPx() }
        val canvasSizePx = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val geometry = resolveHorseshoeGaugeGeometry(canvasSizePx, activeStrokeWidthPx)
        val textBlockCenterYOffsetPx =
            with(density) { (maxHeight / 2f + verticalOffset).toPx() } - geometry.center.y
        val textBoundsPx =
            resolveGaugeTextBoundsPx(
                geometry = geometry,
                trackInsetPx = activeStrokeWidthPx,
                textBlockCenterYOffsetPx = textBlockCenterYOffsetPx,
            )
        val textBoundsWidth = with(density) { textBoundsPx.width.toDp() }

        M3MetricGauge(
            markerFraction = markerFraction,
            activeColor = activeColor,
            markerColor = markerColor,
            animateMarker = animateMarker,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier =
                Modifier
                    .offset(y = verticalOffset)
                    .widthIn(max = textBoundsWidth)
                    .testTag("metric_gauge_value_overlay"),
        ) {
            BasicText(
                text = valueText,
                style =
                    MaterialTheme.typography.headlineMedium.copy(
                        lineHeightStyle =
                            LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim = LineHeightStyle.Trim.Both,
                            ),
                    ),
                color = { valueColor },
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f, fill = false),
                autoSize =
                    TextAutoSize.StepBased(
                        minFontSize = GAUGE_VALUE_MIN_FONT_SIZE,
                        maxFontSize = MaterialTheme.typography.headlineMedium.fontSize,
                        stepSize = 1.sp,
                    ),
            )
            if (unitText.isNotBlank()) {
                Spacer(Modifier.height(unitSpacing))
                BasicText(
                    text = unitText,
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            textAlign = TextAlign.Center,
                            lineHeightStyle =
                                LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.Both,
                                ),
                        ),
                    color = { unitColor },
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    autoSize =
                        TextAutoSize.StepBased(
                            minFontSize = GAUGE_UNIT_MIN_FONT_SIZE,
                            maxFontSize = MaterialTheme.typography.labelSmall.fontSize,
                            stepSize = 1.sp,
                        ),
                )
            }
        }
    }
}
