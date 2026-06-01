package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.domain.model.ZoneBand
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.ui.theme.LocalExtendedColors
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
// rememberChartMarkerVisibilityListener is defined in the same package, no import needed
import com.patrykandpatrick.vico.compose.cartesian.decoration.HorizontalLine
import com.gregor.lauritz.healthdashboard.ui.components.SingleBloodPressureChart
import com.gregor.lauritz.healthdashboard.ui.components.ZoneBandDecoration
import com.gregor.lauritz.healthdashboard.ui.components.zoneBandColors
import com.gregor.lauritz.healthdashboard.ui.components.ChartDefaults
import com.gregor.lauritz.healthdashboard.ui.components.EmptyChartPlaceholder
import com.gregor.lauritz.healthdashboard.ui.components.DataPointTooltip
import com.gregor.lauritz.healthdashboard.ui.components.DataPointTooltipData
import com.gregor.lauritz.healthdashboard.ui.components.InvisibleMarker
import com.gregor.lauritz.healthdashboard.ui.components.VicoChartTooltipOverlay
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine

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
    scrollState: VicoScrollState = rememberVicoScrollState(scrollEnabled = rangeDays > 7),
    zoomState: VicoZoomState = rememberVicoZoomState(
        zoomEnabled = rangeDays > 7,
        initialZoom = Zoom.Content,
        minZoom = Zoom.Content,
        maxZoom = remember(rangeDays) {
            when (rangeDays) {
                30 -> Zoom.fixed(6f)
                180 -> Zoom.fixed(25f)
                else -> Zoom.Content
            }
        },
    ),
    modifier: Modifier = Modifier,
) {
    // Early‑exit placeholder when no data
    if (systolicPoints.none { it.value != null } && diastolicPoints.none { it.value != null }) {
        EmptyChartPlaceholder(modifier = modifier)
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Upper chart – systolic
        SingleBloodPressureChart(
            points = systolicPoints,
            rangeStartMs = rangeStartMs,
            rangeDays = rangeDays,
            isDiastolic = false,
            scrollState = scrollState,
            zoomState = zoomState,
            modifier = Modifier.fillMaxWidth().height(180.dp),
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
        )
        // Combined legend – mirrors the one from the original BloodPressureTrendChart
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Systolic legend box
            Box(
                modifier = Modifier
                    .size(width = 12.dp, height = 2.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Systolic (Ref: <120)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(24.dp))
            // Diastolic legend box
            Box(
                modifier = Modifier
                    .size(width = 12.dp, height = 2.dp)
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Diastolic (Ref: <80)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiaryContainer,
            )
        }
    }
}
