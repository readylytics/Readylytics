package app.readylytics.health.feature.vitals.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.common.CardLoader
import app.readylytics.health.core.ui.common.ScoreDialSkeleton
import app.readylytics.health.core.ui.common.ScreenHeaderSection
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.M3ScoreGaugeCard
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.core.ui.components.StatusLegend
import app.readylytics.health.core.ui.components.TrendCard
import app.readylytics.health.core.ui.components.TrendChart
import app.readylytics.health.core.ui.dashboard.DateSwitcher
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.hrvStatus
import app.readylytics.health.domain.model.rhrStatus
import app.readylytics.health.feature.vitals.R
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR

private const val RHR_DIAL_FLOOR = 30
private const val RHR_BASELINE_FILL = 0.5f

@Composable
fun VitalsRoute(
    onNavigateToHrv: () -> Unit,
    onNavigateToRhr: () -> Unit,
    viewModel: VitalsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val earliestDate by viewModel.earliestDate.collectAsStateWithLifecycle()

    VitalsScreen(
        uiState = uiState,
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
    onRangeSelected: (TimeRange) -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onNavigateToHrv: () -> Unit,
    onNavigateToRhr: () -> Unit,
    modifier: Modifier = Modifier,
    onDateSelected: (java.time.LocalDate) -> Unit = {},
    earliestDate: java.time.LocalDate? = null,
) {
    // Single shared scroll + zoom state so all three trend charts stay in sync.
    // Keyed on selectedRange so state resets when the user switches time ranges.
    val (chartScrollState, chartZoomState) =
        ChartDefaults.rememberChartState(
            rangeDays = uiState.selectedRange.days,
            key = "vitals-${uiState.selectedRange}",
        )
    val scrollState = rememberScrollState()
    val chartSeries = uiState.chartSeries
    val presentation = uiState.presentation
    val baselineHrv = presentation.baselineHrv
    val baselineRhr = presentation.baselineRhr

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeaderSection(isLoading = uiState.isLoading) { isDisabled ->
            DateSwitcher(
                selectedDate = uiState.selectedDate,
                onPreviousDay = onPreviousDay,
                onNextDay = onNextDay,
                onDateSelected = onDateSelected,
                earliestDate = earliestDate,
                enabled = !isDisabled,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.medium)
                        .padding(top = MaterialTheme.spacing.medium),
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(
                        top = MaterialTheme.spacing.small,
                        bottom = MaterialTheme.spacing.medium,
                    ),
        ) {
            // Twin gauges side-by-side
            CardLoader(
                isLoading = uiState.isLoading,
                skeleton = {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = MaterialTheme.spacing.medium,
                                    vertical = MaterialTheme.spacing.small,
                                ),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ScoreDialSkeleton(modifier = Modifier.weight(1f))
                        ScoreDialSkeleton(modifier = Modifier.weight(1f))
                    }
                },
                content = {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = MaterialTheme.spacing.medium,
                                    vertical = MaterialTheme.spacing.small,
                                ),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val summary = uiState.latestSummary
                        val currentRhr = summary?.restingHeartRate
                        val currentHrv = summary?.nocturnalHrv

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
                                optimalThreshold = presentation.rhrOptimalThreshold,
                                warningThreshold = presentation.rhrWarningThreshold,
                            ) ?: MetricStatus.CALIBRATING
                        val rhrTooltip = stringResource(app.readylytics.health.core.ui.R.string.tooltip_sleep_rhr)

                        val rhrDelta =
                            if (currentRhr != null && baselineRhr != null) {
                                val diff = currentRhr - baselineRhr
                                when {
                                    diff > 0 ->
                                        stringResource(CoreUiR.string.delta_up) + " $diff " +
                                            stringResource(app.readylytics.health.core.ui.R.string.unit_bpm)
                                    diff < 0 ->
                                        stringResource(CoreUiR.string.delta_down) + " ${kotlin.math.abs(diff)} " +
                                            stringResource(app.readylytics.health.core.ui.R.string.unit_bpm)
                                    else -> stringResource(CoreUiR.string.delta_no_change)
                                }
                            } else {
                                null
                            }

                        M3ScoreGaugeCard(
                            modifier = Modifier.weight(1f),
                            title = stringResource(CoreUiR.string.label_rhr),
                            score = rhrFill,
                            displayText = currentRhr?.toString() ?: "—",
                            unitText = stringResource(app.readylytics.health.core.ui.R.string.unit_bpm),
                            maxScore = 1f,
                            status = rhrStatus,
                            deltaText = rhrDelta,
                            tooltipDescription = rhrTooltip,
                            onClick = onNavigateToRhr,
                        )

                        val hrvMax = if (baselineHrv != null && baselineHrv > 0f) baselineHrv * 2.0f else 150f
                        val hrvStatus =
                            summary?.hrvStatus(
                                optimalThreshold = presentation.hrvOptimalThreshold,
                                warningThreshold = presentation.hrvWarningThreshold,
                            ) ?: MetricStatus.CALIBRATING
                        val hrvTooltip = stringResource(app.readylytics.health.core.ui.R.string.tooltip_sleep_hrv)

                        val hrvDelta =
                            if (currentHrv != null && baselineHrv != null) {
                                val diff = (currentHrv - baselineHrv).roundToInt()
                                when {
                                    diff > 0 ->
                                        stringResource(CoreUiR.string.delta_up) + " $diff " +
                                            stringResource(app.readylytics.health.core.ui.R.string.unit_ms)
                                    diff < 0 ->
                                        stringResource(CoreUiR.string.delta_down) + " ${kotlin.math.abs(diff)} " +
                                            stringResource(app.readylytics.health.core.ui.R.string.unit_ms)
                                    else -> stringResource(CoreUiR.string.delta_no_change)
                                }
                            } else {
                                null
                            }

                        M3ScoreGaugeCard(
                            modifier = Modifier.weight(1f),
                            title = stringResource(CoreUiR.string.label_hrv),
                            score = currentHrv?.toFloat(),
                            displayText = currentHrv?.toString() ?: "—",
                            unitText = stringResource(app.readylytics.health.core.ui.R.string.unit_ms),
                            maxScore = hrvMax,
                            status = hrvStatus,
                            deltaText = hrvDelta,
                            tooltipDescription = hrvTooltip,
                            onClick = onNavigateToHrv,
                        )
                    }
                },
            )

            // Time Range selection
            SectionHeader(
                title = stringResource(R.string.label_physiological_trends),
                enabled = !uiState.isLoading,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.small))
            SingleChoiceSegmentedButtonRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.medium),
            ) {
                TimeRange.entries.forEachIndexed { index, range ->
                    SegmentedButton(
                        selected = uiState.selectedRange == range,
                        onClick = { onRangeSelected(range) },
                        enabled = !uiState.isLoading,
                        shape =
                            SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = TimeRange.entries.size,
                            ),
                        label = { Text(range.label) },
                    )
                }
            }

            Spacer(Modifier.height(MaterialTheme.spacing.small))

            // Chart 1: HRV Trend
            CardLoader(
                isLoading = uiState.isLoading,
                skeleton = {
                    SkeletonCard(
                        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
                        height = 250.dp,
                    )
                },
                content = {
                    val isCalibrating = uiState.latestSummary?.isCalibrating ?: false
                    TrendCard(
                        title = stringResource(R.string.label_hrv_rmssd),
                        modifier =
                            Modifier
                                .padding(horizontal = MaterialTheme.spacing.medium)
                                .graphicsLayer { },
                    ) {
                        TrendChart(
                            points = chartSeries.hrv,
                            rangeStartMs = uiState.rangeStartMs,
                            rangeDays = uiState.selectedRange.days,
                            metricName = stringResource(CoreUiR.string.label_hrv),
                            baselineUnit = stringResource(app.readylytics.health.core.ui.R.string.unit_ms),
                            baseline = baselineHrv,
                            showBaseline = !isCalibrating,
                            scrollState = chartScrollState,
                            zoomState = chartZoomState,
                            zoneBands = presentation.hrvZoneBands,
                            parentScrollInProgress = { scrollState.isScrollInProgress },
                        )
                    }
                },
            )

            Spacer(Modifier.height(MaterialTheme.spacing.small))

            // Chart 2: Resting HR Trend
            CardLoader(
                isLoading = uiState.isLoading,
                skeleton = {
                    SkeletonCard(
                        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
                        height = 250.dp,
                    )
                },
                content = {
                    val isCalibrating = uiState.latestSummary?.isCalibrating ?: false
                    TrendCard(
                        title = stringResource(R.string.label_resting_heart_rate),
                        modifier =
                            Modifier
                                .padding(horizontal = MaterialTheme.spacing.medium)
                                .graphicsLayer { },
                    ) {
                        TrendChart(
                            points = chartSeries.rhr,
                            rangeStartMs = uiState.rangeStartMs,
                            rangeDays = uiState.selectedRange.days,
                            metricName = stringResource(CoreUiR.string.label_rhr),
                            baselineUnit = "bpm",
                            baseline = baselineRhr?.toFloat(),
                            showBaseline = !isCalibrating,
                            scrollState = chartScrollState,
                            zoomState = chartZoomState,
                            zoneBands = presentation.rhrZoneBands,
                            parentScrollInProgress = { scrollState.isScrollInProgress },
                        )
                    }
                },
            )

            Spacer(Modifier.height(MaterialTheme.spacing.small))

            // Chart 3: SpO2 Trend
            CardLoader(
                isLoading = uiState.isLoading,
                skeleton = {
                    SkeletonCard(
                        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
                        height = 250.dp,
                    )
                },
                content = {
                    TrendCard(
                        title = stringResource(R.string.label_oxygen_saturation),
                        modifier =
                            Modifier
                                .padding(horizontal = MaterialTheme.spacing.medium)
                                .graphicsLayer { },
                    ) {
                        TrendChart(
                            points = chartSeries.spo2,
                            rangeStartMs = uiState.rangeStartMs,
                            rangeDays = uiState.selectedRange.days,
                            metricName = stringResource(CoreUiR.string.label_spo2),
                            baselineUnit = "%",
                            baseline = 95f,
                            baselineLabel = stringResource(CoreUiR.string.label_normal_limit),
                            showBaseline = true,
                            scrollState = chartScrollState,
                            zoomState = chartZoomState,
                            zoneBands = presentation.spo2ZoneBands,
                            axisDecimalPlaces = 0,
                            baselineDecimalPlaces = 0,
                            minYOverride = 90.0,
                            maxYOverride = 100.0,
                            parentScrollInProgress = { scrollState.isScrollInProgress },
                        )
                    }
                },
            )

            Spacer(Modifier.height(MaterialTheme.spacing.large))

            StatusLegend()
        }
    }
}
