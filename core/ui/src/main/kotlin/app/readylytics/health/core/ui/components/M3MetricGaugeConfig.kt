package app.readylytics.health.core.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import kotlin.math.sqrt

internal data class HorseshoeGaugeGeometry(
    val radius: Float,
    val center: Offset,
    val topLeft: Offset,
    val arcSize: Size,
    val startAngle: Float = 150f,
    val sweepAngle: Float = 240f,
)

internal data class GaugeColors(
    val trackColor: Color,
    val activeColor: Color,
    val markerColor: Color,
    val tickColor: Color,
)

internal data class GaugeDimensions(
    val trackThickness: Dp,
    val markerDiameter: Dp,
    val tickDiameter: Dp,
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

internal fun arcTickCapCoverageFraction(
    activeStrokeWidthPx: Float,
    radius: Float,
    sweepAngle: Float,
): Float {
    if (activeStrokeWidthPx <= 0f || radius <= 0f || sweepAngle <= 0f) return 0f
    val sweepRadians = Math.toRadians(sweepAngle.toDouble()).toFloat()
    return roundCapOverhangFraction(activeStrokeWidthPx, radius * sweepRadians)
}

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
