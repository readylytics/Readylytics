package app.readylytics.health.core.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.readylytics.health.core.designsystem.dimens
import kotlin.math.cos
import kotlin.math.sin

private val GAUGE_VALUE_MIN_FONT_SIZE = 16.sp
private val GAUGE_UNIT_MIN_FONT_SIZE = 9.sp

@Composable
fun metricVisualizationTrackColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)

@Composable
fun M3MetricGauge(
    markerFraction: Float?,
    activeColor: Color,
    markerColor: Color,
    modifier: Modifier = Modifier,
    animateMarker: Boolean = true,
) {
    val clampedFraction = markerFraction?.coerceIn(0f, 1f)
    val colors =
        GaugeColors(
            trackColor = metricVisualizationTrackColor(),
            activeColor = activeColor,
            markerColor = markerColor,
            tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        )
    val dimensions =
        GaugeDimensions(
            trackThickness = MaterialTheme.dimens.metricTrackThickness,
            markerDiameter = MaterialTheme.dimens.metricGaugeMarkerDiameter,
            tickDiameter = MaterialTheme.dimens.metricGaugeTickDiameter,
        )

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
            drawGaugeCanvas(
                colors = colors,
                dimensions = dimensions,
                markerFraction = markerFraction,
                progressToDraw = progressToDraw,
            )
        }
    }
}

private fun DrawScope.drawGaugeCanvas(
    colors: GaugeColors,
    dimensions: GaugeDimensions,
    markerFraction: Float?,
    progressToDraw: Float,
) {
    val activeStrokeWidthPx = (dimensions.trackThickness + 2.dp).toPx()
    val geometry = resolveHorseshoeGaugeGeometry(size, activeStrokeWidthPx)

    drawGaugeTrack(colors.trackColor, geometry, dimensions.trackThickness.toPx())
    drawGaugeTicks(colors.tickColor, dimensions.tickDiameter.toPx() / 2f, geometry, progressToDraw, activeStrokeWidthPx)

    if (markerFraction != null && progressToDraw > 0f) {
        drawGaugeActiveArcAndMarker(
            colors = colors,
            markerRadiusPx = dimensions.markerDiameter.toPx() / 2f,
            geometry = geometry,
            progressToDraw = progressToDraw,
            activeStrokeWidthPx = activeStrokeWidthPx,
        )
    }
}

private fun DrawScope.drawGaugeTrack(
    trackColor: Color,
    geometry: HorseshoeGaugeGeometry,
    strokeWidthPx: Float,
) {
    drawArc(
        color = trackColor,
        startAngle = geometry.startAngle,
        sweepAngle = geometry.sweepAngle,
        useCenter = false,
        topLeft = geometry.topLeft,
        size = geometry.arcSize,
        style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawGaugeTicks(
    tickColor: Color,
    tickRadiusPx: Float,
    geometry: HorseshoeGaugeGeometry,
    progressToDraw: Float,
    activeStrokeWidthPx: Float,
) {
    val tickFractions = floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f)
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
}

private fun DrawScope.drawGaugeActiveArcAndMarker(
    colors: GaugeColors,
    markerRadiusPx: Float,
    geometry: HorseshoeGaugeGeometry,
    progressToDraw: Float,
    activeStrokeWidthPx: Float,
) {
    val activeSweep = geometry.sweepAngle * progressToDraw
    val activeEndAngle = geometry.startAngle + activeSweep
    drawArc(
        color = colors.activeColor,
        startAngle = geometry.startAngle,
        sweepAngle = activeSweep,
        useCenter = false,
        topLeft = geometry.topLeft,
        size = geometry.arcSize,
        style = Stroke(width = activeStrokeWidthPx, cap = StrokeCap.Round),
    )
    val markerAngle = Math.toRadians(activeEndAngle.toDouble())
    drawCircle(
        color = colors.markerColor,
        radius = markerRadiusPx,
        center =
            Offset(
                geometry.center.x + geometry.radius * cos(markerAngle).toFloat(),
                geometry.center.y + geometry.radius * sin(markerAngle).toFloat(),
            ),
    )
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

        GaugeValueUnitOverlay(
            valueText = valueText,
            unitText = unitText,
            valueColor = valueColor,
            unitColor = unitColor,
            unitSpacing = unitSpacing,
            modifier =
                Modifier
                    .offset(y = verticalOffset)
                    .widthIn(max = textBoundsWidth)
                    .testTag("metric_gauge_value_overlay"),
        )
    }
}

@Composable
private fun GaugeValueOverlayText(
    valueText: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = valueText,
        modifier = modifier,
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
        autoSize =
            TextAutoSize.StepBased(
                minFontSize = GAUGE_VALUE_MIN_FONT_SIZE,
                maxFontSize = MaterialTheme.typography.headlineMedium.fontSize,
                stepSize = 1.sp,
            ),
    )
}

@Composable
private fun GaugeUnitOverlayText(
    unitText: String,
    unitColor: Color,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = unitText,
        modifier = modifier,
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

@Composable
private fun GaugeValueUnitOverlay(
    valueText: String,
    unitText: String,
    valueColor: Color,
    unitColor: Color,
    unitSpacing: Dp,
    modifier: Modifier = Modifier,
) {
    val hasUnit = unitText.isNotBlank()
    SubcomposeLayout(modifier = modifier) { constraints ->
        val looseConstraints =
            constraints.copy(
                minWidth = 0,
                minHeight = 0,
                maxHeight = Constraints.Infinity,
            )

        val valuePlaceable =
            subcompose("value") {
                GaugeValueOverlayText(valueText = valueText, valueColor = valueColor)
            }.first().measure(looseConstraints)

        val unitPlaceable =
            if (hasUnit) {
                subcompose("unit") {
                    GaugeUnitOverlayText(unitText = unitText, unitColor = unitColor)
                }.first().measure(looseConstraints)
            } else {
                null
            }

        val unitBlockHeightPx =
            if (unitPlaceable != null) unitSpacing.roundToPx() + unitPlaceable.height else 0
        val totalHeight = valuePlaceable.height + 2 * unitBlockHeightPx
        val totalWidth = maxOf(valuePlaceable.width, unitPlaceable?.width ?: 0)

        val boundedTotalHeight =
            if (constraints.maxHeight != Constraints.Infinity) {
                minOf(totalHeight, constraints.maxHeight)
            } else {
                totalHeight
            }

        layout(totalWidth, boundedTotalHeight) {
            valuePlaceable.placeRelative(
                x = (totalWidth - valuePlaceable.width) / 2,
                y = unitBlockHeightPx,
            )
            unitPlaceable?.placeRelative(
                x = (totalWidth - unitPlaceable.width) / 2,
                y = boundedTotalHeight - unitPlaceable.height,
            )
        }
    }
}
