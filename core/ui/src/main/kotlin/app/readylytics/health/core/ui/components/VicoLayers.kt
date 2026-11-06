package app.readylytics.health.core.ui.components

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore

@Composable
fun rememberCubicLineCartesianLayer(
    lineColor: Color,
    areaGradientTopAlpha: Float = 0.3f,
    pointRadius: Dp = 6.dp,
    pointShape: Shape = CircleShape,
    rangeProvider: CartesianLayerRangeProvider? = null,
    verticalAxisPosition: Axis.Position.Vertical = Axis.Position.Vertical.End,
    cubicCurvature: Float = 0.2f,
): LineCartesianLayer {
    val effectiveRangeProvider = rangeProvider ?: remember { CartesianLayerRangeProvider.auto() }
    return rememberLineCartesianLayer(
        lineProvider =
            LineCartesianLayer.LineProvider.series(
                LineCartesianLayer.rememberLine(
                    fill = LineCartesianLayer.LineFill.single(Fill(lineColor)),
                    areaFill =
                        LineCartesianLayer.AreaFill.single(
                            Fill(
                                brush =
                                    Brush.verticalGradient(
                                        colors =
                                            listOf(
                                                lineColor.copy(alpha = areaGradientTopAlpha),
                                                lineColor.copy(alpha = 0.0f),
                                            ),
                                    ),
                            ),
                        ),
                    pointProvider =
                        LineCartesianLayer.PointProvider.single(
                            LineCartesianLayer.Point(
                                rememberShapeComponent(
                                    fill = Fill(lineColor),
                                    shape = pointShape,
                                ),
                                pointRadius,
                            ),
                        ),
                    interpolator = LineCartesianLayer.Interpolator.cubic(cubicCurvature),
                ),
            ),
        rangeProvider = effectiveRangeProvider,
        verticalAxisPosition = verticalAxisPosition,
    )
}

@Composable
fun rememberRoundedColumnCartesianLayer(
    columnColor: Color,
    thickness: Dp = 8.dp,
    shape: Shape = CircleShape,
    rangeProvider: CartesianLayerRangeProvider? = null,
    verticalAxisPosition: Axis.Position.Vertical = Axis.Position.Vertical.Start,
    mergeMode: ((ExtraStore) -> ColumnCartesianLayer.MergeMode)? = null,
): ColumnCartesianLayer {
    val effectiveRangeProvider = rangeProvider ?: remember { CartesianLayerRangeProvider.auto() }
    return rememberColumnCartesianLayer(
        columnProvider =
            ColumnCartesianLayer.ColumnProvider.series(
                rememberLineComponent(
                    fill = Fill(columnColor),
                    thickness = thickness,
                    shape = shape,
                ),
            ),
        mergeMode = mergeMode ?: { ColumnCartesianLayer.MergeMode.Stacked },
        rangeProvider = effectiveRangeProvider,
        verticalAxisPosition = verticalAxisPosition,
    )
}
