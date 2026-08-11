package app.readylytics.health.feature.vitals.bloodpressure

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.common.ScoreDialSkeleton
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.LabeledPeriodAverage
import app.readylytics.health.core.ui.components.PeriodAverageSummaryGroup
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.core.ui.components.TrendCard
import app.readylytics.health.domain.model.BloodPressureStatus
import app.readylytics.health.feature.vitals.R
import app.readylytics.health.feature.vitals.UniversalVitalsMetricCard
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
fun BloodPressureDetailRoute(
    onBack: () -> Unit,
    viewModel: BloodPressureDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BloodPressureDetailScreen(
        uiState = uiState,
        onBack = onBack,
        onRangeSelected = viewModel::onRangeSelected,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BloodPressureDetailScreen(
    uiState: BloodPressureDetailUiState,
    onBack: () -> Unit,
    onRangeSelected: (TimeRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (chartScrollState, chartZoomState) =
        ChartDefaults.rememberChartState(
            rangeDays = uiState.selectedRange.days,
            key = uiState.selectedRange,
        )

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.label_blood_pressure)) },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(CoreUiR.string.back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val scrollState = rememberScrollState()
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(
                        top = MaterialTheme.spacing.pageTop,
                        bottom = MaterialTheme.spacing.pageBottom,
                    ),
        ) {
            if (uiState.isLoading) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = MaterialTheme.spacing.pageHorizontal,
                                vertical = MaterialTheme.spacing.pageSectionGapSmall,
                            ),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScoreDialSkeleton(height = 156.dp, modifier = Modifier.weight(1f))
                    ScoreDialSkeleton(height = 156.dp, modifier = Modifier.weight(1f))
                }
            } else {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = MaterialTheme.spacing.pageHorizontal,
                                vertical = MaterialTheme.spacing.pageSectionGapSmall,
                            ),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val systolicDelta =
                        if (uiState.latestSystolic != null) {
                            val diff = (uiState.latestSystolic - 120)
                            when {
                                diff > 0 ->
                                    stringResource(CoreUiR.string.delta_up) + " $diff " +
                                        stringResource(app.readylytics.health.core.ui.R.string.unit_mmHg)
                                diff < 0 ->
                                    stringResource(CoreUiR.string.delta_down) + " ${kotlin.math.abs(diff)} " +
                                        stringResource(app.readylytics.health.core.ui.R.string.unit_mmHg)
                                else -> stringResource(CoreUiR.string.delta_no_change)
                            }
                        } else {
                            null
                        }

                    UniversalVitalsMetricCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.label_systolic),
                        rawValue = uiState.latestSystolic?.toFloat(),
                        valueText =
                            uiState.latestSystolic?.toString()
                                ?: stringResource(CoreUiR.string.metric_value_unavailable),
                        unitText = stringResource(app.readylytics.health.core.ui.R.string.unit_mmHg),
                        maxValue = 200f,
                        status = uiState.systolicStatus,
                        secondaryText = systolicDelta,
                        tooltip = stringResource(R.string.tooltip_blood_pressure_systolic),
                    )

                    val diastolicDelta =
                        if (uiState.latestDiastolic != null) {
                            val diff = (uiState.latestDiastolic - 80)
                            when {
                                diff > 0 ->
                                    stringResource(CoreUiR.string.delta_up) + " $diff " +
                                        stringResource(app.readylytics.health.core.ui.R.string.unit_mmHg)
                                diff < 0 ->
                                    stringResource(CoreUiR.string.delta_down) + " ${kotlin.math.abs(diff)} " +
                                        stringResource(app.readylytics.health.core.ui.R.string.unit_mmHg)
                                else -> stringResource(CoreUiR.string.delta_no_change)
                            }
                        } else {
                            null
                        }

                    UniversalVitalsMetricCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.label_diastolic),
                        rawValue = uiState.latestDiastolic?.toFloat(),
                        valueText =
                            uiState.latestDiastolic?.toString()
                                ?: stringResource(CoreUiR.string.metric_value_unavailable),
                        unitText = stringResource(app.readylytics.health.core.ui.R.string.unit_mmHg),
                        maxValue = 120f,
                        status = uiState.diastolicStatus,
                        secondaryText = diastolicDelta,
                        tooltip = stringResource(R.string.tooltip_blood_pressure_diastolic),
                    )
                }

                uiState.bloodPressureStatus?.let { status ->
                    Text(
                        text = stringResource(bloodPressureStatusLabelRes(status)),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = MaterialTheme.spacing.pageHorizontal,
                                    vertical = MaterialTheme.spacing.extraSmall,
                                ),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            SectionHeader(title = stringResource(CoreUiR.string.label_trends))
            Spacer(Modifier.height(MaterialTheme.spacing.small))
            SingleChoiceSegmentedButtonRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
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
                        enabled = !uiState.isLoading,
                        label = { Text(range.label) },
                    )
                }
            }

            Spacer(Modifier.height(MaterialTheme.spacing.small))

            if (uiState.isLoading) {
                SkeletonCard(
                    height = 250.dp,
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                )
            } else {
                TrendCard(
                    title = stringResource(R.string.label_blood_pressure_trend),
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                ) {
                    BloodPressureSplitChart(
                        systolicPoints = uiState.dailySystolic,
                        diastolicPoints = uiState.dailyDiastolic,
                        rangeStartMs = uiState.rangeStartMs,
                        rangeDays = uiState.selectedRange.days,
                        granularity = uiState.selectedRange.granularity,
                        scrollState = chartScrollState,
                        zoomState = chartZoomState,
                        parentScrollInProgress = { scrollState.isScrollInProgress },
                    )
                    val systolicSummary = uiState.systolicPeriodSummary
                    val diastolicSummary = uiState.diastolicPeriodSummary
                    if (systolicSummary != null && diastolicSummary != null) {
                        PeriodAverageSummaryGroup(
                            primary =
                                LabeledPeriodAverage(
                                    label = stringResource(R.string.label_systolic),
                                    color = MaterialTheme.colorScheme.primary,
                                    summary = systolicSummary,
                                ),
                            secondary =
                                LabeledPeriodAverage(
                                    label = stringResource(R.string.label_diastolic),
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    summary = diastolicSummary,
                                ),
                            unit = stringResource(app.readylytics.health.core.ui.R.string.unit_mmHg),
                            decimalPlaces = 0,
                        )
                    }
                }
            }

            if (uiState.historyItems.isNotEmpty()) {
                BloodPressureHistorySection(items = uiState.historyItems)
            }

            Spacer(Modifier.height(MaterialTheme.spacing.pageBottom))
        }
    }
}

@StringRes
internal fun bloodPressureStatusLabelRes(status: BloodPressureStatus): Int =
    when (status) {
        BloodPressureStatus.Optimal -> R.string.bp_status_normal
        BloodPressureStatus.Neutral -> R.string.bp_status_elevated
        BloodPressureStatus.HypertensionStage1 -> R.string.bp_status_stage1
        BloodPressureStatus.HypertensionStage2 -> R.string.bp_status_stage2
    }
