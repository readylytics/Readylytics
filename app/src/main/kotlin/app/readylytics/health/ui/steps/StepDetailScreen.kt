package app.readylytics.health.ui.steps

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.R
import app.readylytics.health.domain.model.stepsStatus
import app.readylytics.health.core.ui.common.ScoreDialSkeleton
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.M3ScoreGaugeCard
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.core.ui.components.TrendCard
import app.readylytics.health.core.ui.components.TrendChart

@Composable
fun StepDetailRoute(
    onBack: () -> Unit,
    viewModel: StepDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    StepDetailScreen(
        uiState = uiState,
        onBack = onBack,
        onRangeSelected = viewModel::onRangeSelected,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepDetailScreen(
    uiState: StepDetailUiState,
    onBack: () -> Unit,
    onRangeSelected: (TimeRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (chartScrollState, chartZoomState) =
        ChartDefaults.rememberChartState(
            rangeDays = uiState.selectedRange.days,
            key = uiState.selectedRange,
        )
    val scrollState = rememberScrollState()

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.label_daily_steps)) },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(vertical = 16.dp),
        ) {
            if (uiState.isLoading) {
                ScoreDialSkeleton(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                val stepCount = uiState.latestSummary?.stepCount
                val stepGoal = uiState.stepGoal
                val stepsDelta =
                    if (stepCount != null && stepGoal > 0) {
                        val diff = stepCount - stepGoal
                        val formattedDiff = String.format(java.util.Locale.US, "%,d", kotlin.math.abs(diff))
                        when {
                            diff > 0 -> stringResource(R.string.delta_up) + " $formattedDiff"
                            diff < 0 -> stringResource(R.string.delta_down) + " $formattedDiff"
                            else -> stringResource(R.string.delta_no_change)
                        }
                    } else {
                        null
                    }
                M3ScoreGaugeCard(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    title = stringResource(R.string.label_steps_today),
                    score = stepCount?.toFloat(),
                    displayText = stepCount?.let { String.format(java.util.Locale.US, "%,d", it) } ?: "—",
                    unitText = stringResource(R.string.unit_steps),
                    maxScore = (stepGoal * 1.5f),
                    status = stepCount?.let { stepsStatus(it, stepGoal) },
                    deltaText = stepsDelta,
                    tooltipDescription = pluralStringResource(R.plurals.tooltip_steps_today, stepGoal, stepGoal),
                )
            }

            SectionHeader(title = stringResource(R.string.label_trends))
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
                        enabled = !uiState.isLoading,
                        label = { Text(range.label) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (uiState.isLoading) {
                SkeletonCard(
                    height = 250.dp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                TrendCard(
                    title = stringResource(R.string.label_daily_steps),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    TrendChart(
                        points = uiState.dailySteps,
                        rangeStartMs = uiState.rangeStartMs,
                        rangeDays = uiState.selectedRange.days,
                        metricName = stringResource(R.string.label_steps),
                        baselineUnit = stringResource(R.string.unit_steps),
                        hideUnitInTooltip = true,
                        baseline = uiState.stepGoal.toFloat(),
                        baselineLabel = stringResource(R.string.label_goal),
                        scrollState = chartScrollState,
                        zoomState = chartZoomState,
                        parentScrollInProgress = { scrollState.isScrollInProgress },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
