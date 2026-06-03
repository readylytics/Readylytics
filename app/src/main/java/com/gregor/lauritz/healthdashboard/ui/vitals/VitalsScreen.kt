package com.gregor.lauritz.healthdashboard.ui.vitals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.R
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.hrvStatus
import com.gregor.lauritz.healthdashboard.domain.model.rhrStatus
import com.gregor.lauritz.healthdashboard.ui.common.ScoreDialSkeleton
import com.gregor.lauritz.healthdashboard.ui.common.SkeletonCard
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.components.ChartDefaults
import com.gregor.lauritz.healthdashboard.ui.components.M3ScoreDial
import com.gregor.lauritz.healthdashboard.ui.components.SectionHeader
import com.gregor.lauritz.healthdashboard.ui.components.StatusLegend
import com.gregor.lauritz.healthdashboard.ui.components.TrendCard
import com.gregor.lauritz.healthdashboard.ui.components.TrendChart
import com.gregor.lauritz.healthdashboard.ui.dashboard.DateSwitcher

private const val RHR_DIAL_FLOOR = 30
private const val RHR_BASELINE_FILL = 0.5f

@Composable
fun VitalsRoute(
    onNavigateToHrv: () -> Unit,
    onNavigateToRhr: () -> Unit,
    viewModel: VitalsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val baselines by viewModel.baselinesFlow.collectAsStateWithLifecycle()
    val earliestDate by viewModel.earliestDate.collectAsStateWithLifecycle()

    VitalsScreen(
        uiState = uiState,
        baselineHrv = baselines.hrv,
        baselineRhr = baselines.rhr,
        onRangeSelected = viewModel::onRangeSelected,
        onPreviousDay = viewModel::onPreviousDay,
        onNextDay = viewModel::onNextDay,
        onDateSelected = viewModel::onDateSelected,
        earliestDate = earliestDate,
        onNavigateToHrv = onNavigateToHrv,
        onNavigateToRhr = onNavigateToRhr,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VitalsScreen(
    uiState: VitalsUiState,
    baselineHrv: Float?,
    baselineRhr: Int?,
    onRangeSelected: (TimeRange) -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onNavigateToHrv: () -> Unit,
    onNavigateToRhr: () -> Unit,
    onDateSelected: (java.time.LocalDate) -> Unit = {},
    earliestDate: java.time.LocalDate? = null,
    modifier: Modifier = Modifier,
) {
    // Single shared scroll + zoom state so all three trend charts stay in sync.
    // Keyed on selectedRange so state resets when the user switches time ranges.
    val (chartScrollState, chartZoomState) =
        ChartDefaults.rememberChartState(
            rangeDays = uiState.selectedRange.days,
            key = "vitals-${uiState.selectedRange}",
        )
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        // Date switcher on top
        item(key = "date_switcher") {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                DateSwitcher(
                    selectedDate = uiState.selectedDate,
                    onPreviousDay = onPreviousDay,
                    onNextDay = onNextDay,
                    onDateSelected = onDateSelected,
                    earliestDate = earliestDate,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Twin gauges side-by-side below day picker
        item(key = "gauges_row") {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (uiState.isLoading) {
                    ScoreDialSkeleton()
                    ScoreDialSkeleton()
                } else {
                    val summary = uiState.latestSummary
                    val currentRhr = summary?.nocturnalRhr
                    val currentHrv = summary?.nocturnalHrv

                    // Gauge 1: Resting HR
                    // Map RHR onto the dial so the baseline sits at the 50% mark:
                    // progress = (current - floor) / (baseline - floor), where floor is the
                    // physiological RHR minimum. Below baseline fills < 50%, above fills > 50%.
                    val rhrFill =
                        if (baselineRhr != null && baselineRhr > RHR_DIAL_FLOOR && currentRhr != null) {
                            (
                                (currentRhr - RHR_DIAL_FLOOR).toFloat() /
                                    (baselineRhr - RHR_DIAL_FLOOR) * RHR_BASELINE_FILL
                            ).coerceIn(0f, 1f)
                        } else {
                            null
                        }
                    val rhrStatus =
                        summary?.rhrStatus(
                            optimalThreshold = uiState.rhrOptimalThreshold,
                            warningThreshold = uiState.rhrWarningThreshold,
                        ) ?: MetricStatus.CALIBRATING
                    val rhrTooltip = stringResource(R.string.tooltip_sleep_rhr)

                    M3ScoreDial(
                        score = rhrFill,
                        label = "RHR",
                        maxScore = 1f,
                        status = rhrStatus,
                        displayText = currentRhr?.toString() ?: "—",
                        tooltipDescription = rhrTooltip,
                        onClick = onNavigateToRhr,
                    )

                    // Gauge 2: HRV
                    val hrvMax = if (baselineHrv != null && baselineHrv > 0f) baselineHrv * 2.0f else 150f
                    val hrvStatus =
                        summary?.hrvStatus(
                            optimalThreshold = uiState.hrvOptimalThreshold,
                            warningThreshold = uiState.hrvWarningThreshold,
                        ) ?: MetricStatus.CALIBRATING
                    val hrvTooltip = stringResource(R.string.tooltip_sleep_hrv)

                    M3ScoreDial(
                        score = currentHrv?.toFloat(),
                        label = "HRV",
                        maxScore = hrvMax,
                        status = hrvStatus,
                        displayText = currentHrv?.toString() ?: "—",
                        tooltipDescription = hrvTooltip,
                        onClick = onNavigateToHrv,
                    )
                }
            }
        }

        item(key = "spacer_switcher") { Spacer(Modifier.height(8.dp)) }

        // Time Range selection
        item(key = "trends_header") {
            SectionHeader(title = "Physiological Trends")
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
            ) {
                TimeRange.entries.forEachIndexed { index, range ->
                    SegmentedButton(
                        selected = uiState.selectedRange == range,
                        onClick = { onRangeSelected(range) },
                        shape =
                            SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = TimeRange.entries.size,
                            ),
                        label = { Text(range.label) },
                    )
                }
            }
        }

        item(key = "spacer_trends") { Spacer(Modifier.height(16.dp)) }

        // Chart 1: HRV Trend
        item(key = "hrv_chart") {
            if (uiState.isLoading) {
                SkeletonCard(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    height = 250.dp,
                )
            } else {
                val isCalibrating = uiState.latestSummary?.isCalibrating ?: false
                TrendCard(
                    title = "HRV (RMSSD)",
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    TrendChart(
                        points = uiState.dailyHrv,
                        rangeStartMs = uiState.rangeStartMs,
                        rangeDays = uiState.selectedRange.days,
                        metricName = "HRV",
                        baselineUnit = "ms",
                        baseline = baselineHrv,
                        showBaseline = !isCalibrating,
                        scrollState = chartScrollState,
                        zoomState = chartZoomState,
                        zoneBands = uiState.hrvZoneBands,
                        parentScrollInProgress = listState.isScrollInProgress,
                    )
                }
            }
        }

        item(key = "spacer_hrv") { Spacer(Modifier.height(16.dp)) }

        // Chart 2: Resting HR Trend
        item(key = "rhr_chart") {
            if (uiState.isLoading) {
                SkeletonCard(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    height = 250.dp,
                )
            } else {
                val isCalibrating = uiState.latestSummary?.isCalibrating ?: false
                TrendCard(
                    title = "Resting Heart Rate",
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    TrendChart(
                        points = uiState.dailyRhr,
                        rangeStartMs = uiState.rangeStartMs,
                        rangeDays = uiState.selectedRange.days,
                        metricName = "RHR",
                        baselineUnit = "bpm",
                        baseline = baselineRhr?.toFloat(),
                        showBaseline = !isCalibrating,
                        scrollState = chartScrollState,
                        zoomState = chartZoomState,
                        zoneBands = uiState.rhrZoneBands,
                        parentScrollInProgress = listState.isScrollInProgress,
                    )
                }
            }
        }

        item(key = "spacer_rhr") { Spacer(Modifier.height(16.dp)) }

        // Chart 3: SpO2 Trend
        item(key = "spo2_chart") {
            if (uiState.isLoading) {
                SkeletonCard(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    height = 250.dp,
                )
            } else {
                TrendCard(
                    title = "Oxygen Saturation",
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    TrendChart(
                        points = uiState.dailySpo2,
                        rangeStartMs = uiState.rangeStartMs,
                        rangeDays = uiState.selectedRange.days,
                        metricName = "SpO2",
                        baselineUnit = "%",
                        baseline = 95f,
                        baselineLabel = "Normal Limit",
                        showBaseline = true,
                        scrollState = chartScrollState,
                        zoomState = chartZoomState,
                        zoneBands = uiState.spo2ZoneBands,
                        axisDecimalPlaces = 0,
                        baselineDecimalPlaces = 0,
                        minYOverride = 90.0,
                        maxYOverride = 100.0,
                        parentScrollInProgress = listState.isScrollInProgress,
                    )
                }
            }
        }

        item(key = "spacer_bottom") { Spacer(Modifier.height(24.dp)) }

        item(key = "status_legend") {
            StatusLegend()
        }
    }
}
