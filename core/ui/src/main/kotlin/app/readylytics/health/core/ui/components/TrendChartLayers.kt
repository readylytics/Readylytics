package app.readylytics.health.core.ui.components

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.ui.common.DailyDataPoint
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent

@Composable
internal fun rememberTrendLineProvider(
    dotColor: Color,
    baselineColor: Color,
    historicalBaseline: List<DailyDataPoint>?,
): LineCartesianLayer.LineProvider {
    val dotComponent = rememberShapeComponent(fill = Fill(dotColor), shape = CircleShape)
    val lineFill = remember(dotColor) { LineCartesianLayer.LineFill.single(Fill(dotColor)) }
    val areaFill =
        remember(dotColor) {
            LineCartesianLayer.AreaFill.single(
                Fill(
                    brush =
                        Brush.verticalGradient(
                            colors = listOf(dotColor.copy(alpha = 0.3f), dotColor.copy(alpha = 0.0f)),
                        ),
                ),
            )
        }
    val line =
        LineCartesianLayer.rememberLine(
            fill = lineFill,
            areaFill = areaFill,
            pointProvider =
                LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.Point(dotComponent, 6.dp),
                ),
            interpolator = LineCartesianLayer.Interpolator.cubic(0.2f),
        )

    val historicalBaselineLine =
        LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(Fill(baselineColor)),
            interpolator = LineCartesianLayer.Interpolator.cubic(0.2f),
        )

    return remember(line, historicalBaselineLine, historicalBaseline) {
        if (!historicalBaseline.isNullOrEmpty()) {
            LineCartesianLayer.LineProvider.series(line, historicalBaselineLine)
        } else {
            LineCartesianLayer.LineProvider.series(line)
        }
    }
}
