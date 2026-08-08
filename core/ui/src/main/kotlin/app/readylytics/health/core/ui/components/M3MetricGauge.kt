package app.readylytics.health.core.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.dimens
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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

/**
 * Widest rectangle (width, height) available to the gauge's value/unit overlay,
 * inscribed inside the horseshoe's circle minus the track stroke inset.
 * The block's vertical span is assumed to be centered at [textBlockCenterYOffsetPx]
 * from the circle center, with height [textBlockHeightPx]; the safe width is twice
 * the smaller chord half-width at the block's top and bottom edges, so the text can
 * never legitimately overlap the track. Degenerate/oversized inputs clamp to 0.
 */
internal fun resolveGaugeTextBoundsPx(
    geometry: HorseshoeGaugeGeometry,
    trackInsetPx: Float,
    textBlockCenterYOffsetPx: Float,
    textBlockHeightPx: Float,
): Size {
    val innerRadius = (geometry.radius - trackInsetPx).coerceAtLeast(0f)
    val halfBlockHeight = textBlockHeightPx / 2f
    val halfWidthAt = { dy: Float -> sqrt(maxOf(0f, innerRadius * innerRadius - dy * dy)) }
    val topDy = textBlockCenterYOffsetPx - halfBlockHeight
    val bottomDy = textBlockCenterYOffsetPx + halfBlockHeight
    val safeHalfWidth = minOf(halfWidthAt(topDy), halfWidthAt(bottomDy)).coerceAtLeast(0f)
    val safeHeight =
        (minOf(bottomDy, innerRadius) - maxOf(topDy, -innerRadius)).coerceAtLeast(0f)
    return Size(width = safeHalfWidth * 2f, height = safeHeight)
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

            tickFractions
                .filter { it > progressToDraw }
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
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
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
                    .offset(y = MaterialTheme.dimens.metricGaugeValueVerticalOffset)
                    .testTag("metric_gauge_value_overlay"),
        ) {
            Text(
                text = valueText,
                style =
                    MaterialTheme.typography.headlineMedium.copy(
                        lineHeightStyle =
                            LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim = LineHeightStyle.Trim.Both,
                            ),
                    ),
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (unitText.isNotBlank()) {
                Spacer(Modifier.height(MaterialTheme.dimens.metricGaugeValueUnitSpacing))
                Text(
                    text = unitText,
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            lineHeightStyle =
                                LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.Both,
                                ),
                        ),
                    color = unitColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
