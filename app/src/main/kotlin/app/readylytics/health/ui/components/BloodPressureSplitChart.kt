package app.readylytics.health.ui.components

import app.readylytics.health.core.ui.components.DataPointTooltipData
import app.readylytics.health.core.ui.components.DataPointTooltip
import app.readylytics.health.core.ui.components.EmptyChartPlaceholder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.ui.common.ChartUtils
import app.readylytics.health.core.ui.common.DailyDataPoint
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import kotlin.math.roundToInt

/**
 * A split‑view blood pressure chart that displays systolic and diastolic series in two stacked charts.
 * The two charts share the same scroll and zoom state for synchronized navigation.
 * A combined legend is shown below the charts.
 */
@Composable
fun BloodPressureSplitChart(
    systolicPoints: List<DailyDataPoint>,
    diastolicPoints: List<DailyDataPoint>,
    rangeStartMs: Long,
    rangeDays: Int,
    modifier: Modifier = Modifier,
    scrollState: VicoScrollState = rememberVicoScrollState(scrollEnabled = rangeDays > 7),
    zoomState: VicoZoomState =
        rememberVicoZoomState(
            zoomEnabled = rangeDays > 7,
            initialZoom = Zoom.Content,
            minZoom = Zoom.min(Zoom.Content, Zoom.fixed(1f)),
            maxZoom =
                remember(rangeDays) {
                    when (rangeDays) {
                        30 -> Zoom.fixed(6f)
                        180 -> Zoom.fixed(25f)
                        else -> Zoom.fixed(2f)
                    }
                },
        ),
    parentScrollInProgress: () -> Boolean = { false },
) {
    var tooltipState by remember { mutableStateOf<DataPointTooltipData?>(null) }
    var selectedDayOffset by remember { mutableStateOf<Int?>(null) }
    var selectedCanvasX by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(tooltipState) {
        if (tooltipState == null) {
            selectedDayOffset = null
            selectedCanvasX = null
        }
    }

    LaunchedEffect(rangeDays) {
        tooltipState = null
    }

    // Clear tooltip when the chart is scrolled/panned (Vico horizontal scroll)
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value }.collect {
            tooltipState = null
            selectedDayOffset = null
            selectedCanvasX = null
        }
    }

    // Clear tooltip on vertical scroll — fires on both start (true) and end (false)
    // to eliminate any stale state that slips through mid-scroll recompositions.
    val currentParentScrollInProgress by rememberUpdatedState(parentScrollInProgress)
    LaunchedEffect(Unit) {
        snapshotFlow { currentParentScrollInProgress() }.collect {
            tooltipState = null
            selectedDayOffset = null
            selectedCanvasX = null
        }
    }

    // Early‑exit placeholder when no data
    if (systolicPoints.none { it.value != null } && diastolicPoints.none { it.value != null }) {
        EmptyChartPlaceholder(modifier = modifier)
        return
    }

    val onDaySelected = { dayOffset: Int, canvasX: Float, canvasY: Float ->
        selectedDayOffset = dayOffset
        selectedCanvasX = canvasX

        val date = ChartUtils.dayOffsetToLocalDate(dayOffset, rangeStartMs)
        val dateString = ChartUtils.formatTooltipDate(date)

        val sysPoint = systolicPoints.firstOrNull { it.dayOffset == dayOffset }?.value
        val diaPoint = diastolicPoints.firstOrNull { it.dayOffset == dayOffset }?.value

        val valueText =
            if (sysPoint != null && diaPoint != null) {
                "${sysPoint.roundToInt()}/${diaPoint.roundToInt()} mmHg"
            } else if (sysPoint != null) {
                "Sys: ${sysPoint.roundToInt()} mmHg"
            } else if (diaPoint != null) {
                "Dia: ${diaPoint.roundToInt()} mmHg"
            } else {
                "—"
            }

        tooltipState =
            DataPointTooltipData(
                valueText = valueText,
                dateText = dateString,
                offset = IntOffset(canvasX.toInt(), canvasY.toInt()),
            )
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Upper chart – systolic
            SingleBloodPressureChart(
                points = systolicPoints,
                rangeStartMs = rangeStartMs,
                rangeDays = rangeDays,
                isDiastolic = false,
                scrollState = scrollState,
                zoomState = zoomState,
                modifier = Modifier.fillMaxWidth().height(180.dp),
                onDaySelected = onDaySelected,
                externalSelectedDayOffset = selectedDayOffset,
                externalSelectedCanvasX = selectedCanvasX,
                showTooltip = false,
                parentScrollInProgress = parentScrollInProgress,
            )
            // Lower chart – diastolic
            SingleBloodPressureChart(
                points = diastolicPoints,
                rangeStartMs = rangeStartMs,
                rangeDays = rangeDays,
                isDiastolic = true,
                scrollState = scrollState,
                zoomState = zoomState,
                modifier = Modifier.fillMaxWidth().height(180.dp),
                onDaySelected = onDaySelected,
                externalSelectedDayOffset = selectedDayOffset,
                externalSelectedCanvasX = selectedCanvasX,
                showTooltip = false,
                parentScrollInProgress = parentScrollInProgress,
            )
            // Combined legend – mirrors the one from the original BloodPressureTrendChart
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Systolic legend box
                Box(
                    modifier =
                        Modifier
                            .size(width = 12.dp, height = 2.dp)
                            .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Systolic",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(24.dp))
                // Diastolic legend box
                Box(
                    modifier =
                        Modifier
                            .size(width = 12.dp, height = 2.dp)
                            .background(MaterialTheme.colorScheme.tertiaryContainer),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Diastolic",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                )
            }
        }

        if (tooltipState != null) {
            DataPointTooltip(
                isVisible = true,
                data = tooltipState!!,
                onDismissRequest = { tooltipState = null },
            )
        }
    }
}
