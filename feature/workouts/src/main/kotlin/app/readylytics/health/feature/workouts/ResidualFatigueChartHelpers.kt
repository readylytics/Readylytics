package app.readylytics.health.feature.workouts

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.model.domain.workouts.FatigueCurvePoint
import app.readylytics.health.core.model.domain.workouts.FatigueCurveRange
import app.readylytics.health.core.ui.components.DataPointTooltipData
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR

internal const val TOTAL_MINUTES_IN_DAY = 1440.0
private const val CHART_CUBIC_INTERPOLATION = 0.2f
private const val GRADIENT_START_ALPHA = 0.35f
internal const val Y_AXIS_GRID_STEP = 25.0
private const val VICO_X_VALUE_PRECISION = 10_000.0
private const val NOW_MARKER_EPSILON_MINUTES = 0.5
private val NOW_MARKER_SIZE = 6.dp

internal data class ResidualFatigueOverlayState(
    val summary: String,
    val selectedValueDesc: String,
    val actions: List<CustomAccessibilityAction>,
    val pointOffset: Offset?,
    val tooltip: DataPointTooltipData?,
)

internal data class ResidualFatigueSelectionData(
    val selectedIndex: Int?,
    val selectedPointOffset: Offset?,
    val points: List<FatigueCurvePoint>,
    val range: FatigueCurveRange,
    val tooltipFormat: String,
    val zoneId: ZoneId = ZoneId.systemDefault(),
)

internal fun residualFatigueSelectedPointOffset(
    isSelectionVisible: Boolean,
    selectedPointOffset: Offset?,
): Offset? = selectedPointOffset.takeIf { isSelectionVisible }

internal fun residualFatigueChartXValues(points: List<FatigueCurvePoint>): List<Double> =
    points.map { point ->
        (point.timeMinutesFromStart.toDouble() * VICO_X_VALUE_PRECISION).roundToInt() / VICO_X_VALUE_PRECISION
    }

internal fun formatFatiguePoint(
    point: FatigueCurvePoint,
    range: FatigueCurveRange,
    tooltipFormat: String,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    // Always read the wall clock off the point's own instant. Deriving it from
    // timeMinutesFromStart assumes a 24-hour day and is an hour out on either DST transition.
    val zdt = Instant.ofEpochMilli(point.timestampMs).atZone(zoneId)
    val timeStr =
        if (range == FatigueCurveRange.ONE_DAY) {
            zdt.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
        } else {
            zdt.format(DateTimeFormatter.ofPattern("EEE, MMM d, HH:mm", Locale.getDefault()))
        }
    return String.format(Locale.getDefault(), tooltipFormat, timeStr, point.fatigueValue)
}

@Composable
internal fun rememberResidualFatigueAccessibilityActions(
    selectedIndex: Int?,
    points: List<FatigueCurvePoint>,
    onSelectIndex: (Int?) -> Unit,
): List<CustomAccessibilityAction> {
    val prevLabel = stringResource(CoreUiR.string.action_previous_point)
    val nextLabel = stringResource(CoreUiR.string.action_next_point)
    val clearLabel = stringResource(CoreUiR.string.action_clear_selection)

    return remember(selectedIndex, points) {
        val list = mutableListOf<CustomAccessibilityAction>()
        if (points.isNotEmpty()) {
            list.add(
                CustomAccessibilityAction(prevLabel) {
                    val curr = selectedIndex ?: -1
                    onSelectIndex(if (curr > 0) curr - 1 else points.lastIndex)
                    true
                },
            )
            list.add(
                CustomAccessibilityAction(nextLabel) {
                    val curr = selectedIndex ?: -1
                    onSelectIndex(if (curr != -1 && curr < points.lastIndex) curr + 1 else 0)
                    true
                },
            )
        }
        if (selectedIndex != null) {
            list.add(
                CustomAccessibilityAction(clearLabel) {
                    onSelectIndex(null)
                    true
                },
            )
        }
        list
    }
}

@Composable
internal fun ObserveParentScroll(
    parentScrollInProgress: () -> Boolean,
    onResetSelection: () -> Unit,
) {
    val currentParentScrollInProgress by rememberUpdatedState(parentScrollInProgress)
    LaunchedEffect(Unit) {
        snapshotFlow { currentParentScrollInProgress() }.collect { inProgress ->
            if (inProgress) onResetSelection()
        }
    }
}

@Composable
internal fun ObserveTooltipSelection(
    selection: ResidualFatigueSelectionData,
    onUpdateTooltip: (DataPointTooltipData?) -> Unit,
) {
    LaunchedEffect(
        selection.selectedIndex,
        selection.points,
        selection.range,
        selection.selectedPointOffset,
        selection.zoneId,
    ) {
        val idx = selection.selectedIndex
        if (idx != null && idx in selection.points.indices) {
            val point = selection.points[idx]
            val formatted = formatFatiguePoint(point, selection.range, selection.tooltipFormat, selection.zoneId)
            val offset =
                selection.selectedPointOffset?.let {
                    IntOffset(it.x.toInt(), it.y.toInt())
                } ?: IntOffset(0, 0)
            onUpdateTooltip(DataPointTooltipData(valueText = formatted, dateText = "", offset = offset))
        } else {
            onUpdateTooltip(null)
        }
    }
}

/**
 * X value (minutes from range start) at which to draw the "you are here" dot, or null when the
 * curve is not truncated.
 *
 * The curve is generated only up to the present, so a series that stops short of the axis maximum
 * is one whose last sample *is* now. For a fully elapsed range the last point sits at the range end
 * and no marker is warranted.
 */
internal fun residualFatigueNowMarkerX(
    points: List<FatigueCurvePoint>,
    maxX: Double,
): Double? {
    val last = points.lastOrNull() ?: return null
    val lastX = last.timeMinutesFromStart.toDouble()
    return lastX.takeIf { it < maxX - NOW_MARKER_EPSILON_MINUTES }
}

/** Draws [point] only on the entry sitting at [targetX]; every other sample stays bare. */
private class SingleEntryPointProvider(
    private val point: LineCartesianLayer.Point,
    private val targetX: Double,
) : LineCartesianLayer.PointProvider {
    override fun getPoint(
        entry: LineCartesianLayerModel.Entry,
        seriesIndex: Int,
        extraStore: ExtraStore,
    ): LineCartesianLayer.Point? = point.takeIf { kotlin.math.abs(entry.x - targetX) <= NOW_MARKER_EPSILON_MINUTES }

    override fun getLargestPoint(extraStore: ExtraStore): LineCartesianLayer.Point = point
}

@Composable
internal fun rememberResidualFatigueLineLayer(
    rangeProvider: CartesianLayerRangeProvider,
    nowMarkerX: Double? = null,
): LineCartesianLayer {
    val primaryColor = MaterialTheme.colorScheme.primary
    val lineFill = LineCartesianLayer.LineFill.single(Fill(primaryColor))
    val areaFill =
        LineCartesianLayer.AreaFill.single(
            Fill(
                Brush.verticalGradient(
                    listOf(
                        primaryColor.copy(alpha = GRADIENT_START_ALPHA),
                        primaryColor.copy(alpha = 0.0f),
                    ),
                ),
            ),
        )
    val nowDotComponent = rememberShapeComponent(fill = Fill(primaryColor), shape = CircleShape)
    val pointProvider =
        remember(nowMarkerX, nowDotComponent) {
            nowMarkerX?.let {
                SingleEntryPointProvider(LineCartesianLayer.Point(nowDotComponent, NOW_MARKER_SIZE), it)
            }
        }
    return rememberLineCartesianLayer(
        lineProvider =
            LineCartesianLayer.LineProvider.series(
                LineCartesianLayer.rememberLine(
                    fill = lineFill,
                    areaFill = areaFill,
                    pointProvider = pointProvider,
                    interpolator = LineCartesianLayer.Interpolator.cubic(CHART_CUBIC_INTERPOLATION),
                ),
            ),
        rangeProvider = rangeProvider,
    )
}
