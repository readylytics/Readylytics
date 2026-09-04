package app.readylytics.health.feature.workouts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import app.readylytics.health.core.ui.common.ChartUtils
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.core.ui.components.DataPointTooltipData
import app.readylytics.health.core.ui.components.rememberChartMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import java.util.Locale
import kotlin.math.roundToInt

internal data class TsbTooltipState(
    val markerVisibilityListener: CartesianMarkerVisibilityListener,
    val selectedPointOffset: Offset?,
    val tooltipData: DataPointTooltipData?,
    val onDismissTooltip: () -> Unit,
)

internal data class TsbTooltipParams(
    val tsbValue: Float,
    val dayOffset: Int,
    val rangeStartMs: Long,
    val granularity: TrendGranularity,
    val periodLabels: List<String>,
    val canvasX: Float,
    val canvasY: Float,
    val tsbFormat: String,
    val avgTsbFormat: String,
    val zoneText: String,
)

internal fun tsbZoneLabelRes(tsb: Float): Int =
    when {
        tsb > VERY_FRESH_THRESHOLD -> R.string.tsb_zone_very_fresh
        tsb >= FRESH_THRESHOLD -> R.string.tsb_zone_fresh
        tsb >= FATIGUED_THRESHOLD -> R.string.tsb_zone_optimal
        tsb >= HIGH_RISK_THRESHOLD -> R.string.tsb_zone_fatigued
        else -> R.string.tsb_zone_overreached
    }

internal fun TsbTooltipParams.toTooltipData(): DataPointTooltipData {
    val formattedTsb = String.format(Locale.US, "%+d", tsbValue.roundToInt())
    val offset = IntOffset(canvasX.toInt(), canvasY.toInt())
    return if (granularity == TrendGranularity.DAILY) {
        val date = ChartUtils.dayOffsetToLocalDate(dayOffset, rangeStartMs)
        DataPointTooltipData(
            valueText = tsbFormat.format(formattedTsb),
            dateText = zoneText,
            extraLine = ChartUtils.formatTooltipDate(date),
            offset = offset,
        )
    } else {
        val periodLabel = periodLabels.getOrElse(dayOffset) { "" }
        DataPointTooltipData(
            valueText = periodLabel,
            dateText = avgTsbFormat.format(formattedTsb),
            extraLine = zoneText,
            offset = offset,
        )
    }
}

@Composable
internal fun rememberTsbTooltipState(
    remappedPoints: List<DailyDataPoint>,
    rangeStartMs: Long,
    granularity: TrendGranularity,
    periodLabels: List<String>,
    scrollState: VicoScrollState,
    parentScrollInProgress: () -> Boolean,
): TsbTooltipState {
    var selectedPointOffset by remember(remappedPoints, rangeStartMs) { mutableStateOf<Offset?>(null) }
    var tooltipData by remember(remappedPoints, rangeStartMs) { mutableStateOf<DataPointTooltipData?>(null) }
    val clearTooltip = {
        selectedPointOffset = null
        tooltipData = null
    }

    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value }.collect { clearTooltip() }
    }
    val currentParentScrollInProgress by rememberUpdatedState(parentScrollInProgress)
    LaunchedEffect(Unit) {
        snapshotFlow { currentParentScrollInProgress() }.collect { inProgress ->
            if (inProgress) clearTooltip()
        }
    }

    val tsbFormat = stringResource(R.string.tsb_tooltip_format)
    val avgTsbFormat = stringResource(R.string.tsb_tooltip_avg_format)
    val context = LocalContext.current

    val markerVisibilityListener =
        rememberChartMarkerVisibilityListener { x, y, canvasX, canvasY ->
            val dayOffset = x.toInt()
            val point = remappedPoints.firstOrNull { it.dayOffset == dayOffset }
            val tsbVal = point?.value ?: y.toFloat()
            val zoneText = context.getString(tsbZoneLabelRes(tsbVal))
            selectedPointOffset = Offset(canvasX, canvasY)
            tooltipData =
                TsbTooltipParams(
                    tsbValue = tsbVal,
                    dayOffset = dayOffset,
                    rangeStartMs = rangeStartMs,
                    granularity = granularity,
                    periodLabels = periodLabels,
                    canvasX = canvasX,
                    canvasY = canvasY,
                    tsbFormat = tsbFormat,
                    avgTsbFormat = avgTsbFormat,
                    zoneText = zoneText,
                ).toTooltipData()
        }

    return TsbTooltipState(
        markerVisibilityListener = markerVisibilityListener,
        selectedPointOffset = selectedPointOffset,
        tooltipData = tooltipData,
        onDismissTooltip = clearTooltip,
    )
}
