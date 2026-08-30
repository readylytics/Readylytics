package app.readylytics.health.feature.workouts

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
import app.readylytics.health.core.model.domain.workouts.FatigueCurvePoint
import app.readylytics.health.core.model.domain.workouts.FatigueCurveRange
import app.readylytics.health.core.ui.components.DataPointTooltipData
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.common.Fill
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
private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24
private const val MILLIS_PER_MINUTE = 60_000.0

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
)

internal fun formatFatiguePoint(
    point: FatigueCurvePoint,
    range: FatigueCurveRange,
    tooltipFormat: String,
): String {
    val timeStr =
        if (range == FatigueCurveRange.ONE_DAY) {
            val hours = (point.timeMinutesFromStart / MINUTES_PER_HOUR).toInt().coerceIn(0, HOURS_PER_DAY)
            val minutes = (point.timeMinutesFromStart % MINUTES_PER_HOUR).toInt().coerceIn(0, MINUTES_PER_HOUR - 1)
            String.format(Locale.getDefault(), "%02d:%02d", hours, minutes)
        } else {
            val zdt = Instant.ofEpochMilli(point.timestampMs).atZone(ZoneId.systemDefault())
            zdt.format(DateTimeFormatter.ofPattern("EEE, MMM d, HH:mm", Locale.getDefault()))
        }
    return String.format(Locale.getDefault(), tooltipFormat, timeStr, point.fatigueValue)
}

@Composable
internal fun rememberResidualFatigueItemPlacer(range: FatigueCurveRange): HorizontalAxis.ItemPlacer {
    val labels =
        remember(range) {
            when (range) {
                FatigueCurveRange.ONE_DAY -> listOf(0.0, 240.0, 480.0, 720.0, 960.0, 1200.0, 1440.0)
                FatigueCurveRange.THREE_DAYS -> (0..3).map { it * TOTAL_MINUTES_IN_DAY }
                FatigueCurveRange.SEVEN_DAYS -> (0..7).map { it * TOTAL_MINUTES_IN_DAY }
            }
        }
    return remember(labels) {
        val base = HorizontalAxis.ItemPlacer.aligned(spacing = { 1 }, addExtremeLabelPadding = true)
        object : HorizontalAxis.ItemPlacer by base {
            override fun getLabelValues(
                context: CartesianDrawingContext,
                visibleXRange: ClosedFloatingPointRange<Double>,
                fullXRange: ClosedFloatingPointRange<Double>,
                maxLabelWidth: Float,
            ): List<Double> = labels.filter { it in fullXRange }
        }
    }
}

@Composable
internal fun rememberResidualFatigueValueFormatter(
    range: FatigueCurveRange,
    points: List<FatigueCurvePoint>,
): CartesianValueFormatter =
    remember(range, points) {
        val startEpochMs = points.firstOrNull()?.timestampMs
        CartesianValueFormatter { _, v, _ ->
            if (range == FatigueCurveRange.ONE_DAY) {
                val h = (v / MINUTES_PER_HOUR.toDouble()).roundToInt().coerceIn(0, HOURS_PER_DAY)
                String.format(Locale.getDefault(), "%02d:00", h)
            } else if (startEpochMs != null) {
                val tickEpochMs = startEpochMs + (v * MILLIS_PER_MINUTE).toLong()
                val zdt = Instant.ofEpochMilli(tickEpochMs).atZone(ZoneId.systemDefault())
                zdt.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))
            } else {
                ""
            }
        }
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
    ) {
        val idx = selection.selectedIndex
        if (idx != null && idx in selection.points.indices) {
            val point = selection.points[idx]
            val formatted = formatFatiguePoint(point, selection.range, selection.tooltipFormat)
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

@Composable
internal fun rememberResidualFatigueLineLayer(rangeProvider: CartesianLayerRangeProvider): LineCartesianLayer {
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
    return rememberLineCartesianLayer(
        lineProvider =
            LineCartesianLayer.LineProvider.series(
                LineCartesianLayer.rememberLine(
                    fill = lineFill,
                    areaFill = areaFill,
                    interpolator = LineCartesianLayer.Interpolator.cubic(CHART_CUBIC_INTERPOLATION),
                ),
            ),
        rangeProvider = rangeProvider,
    )
}
