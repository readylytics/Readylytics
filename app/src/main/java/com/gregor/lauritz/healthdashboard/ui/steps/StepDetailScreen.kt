package com.gregor.lauritz.healthdashboard.ui.steps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.domain.model.stepsStatus
import com.gregor.lauritz.healthdashboard.ui.common.ScoreDialSkeleton
import com.gregor.lauritz.healthdashboard.ui.common.SkeletonCard
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.components.ChartDefaults
import com.gregor.lauritz.healthdashboard.ui.components.M3ScoreDial
import com.gregor.lauritz.healthdashboard.ui.components.SectionHeader
import com.gregor.lauritz.healthdashboard.ui.components.TrendCard
import com.gregor.lauritz.healthdashboard.ui.components.TrendChart

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
    val listState = rememberLazyListState()

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Daily Steps") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item(key = "score_dial") {
                if (uiState.isLoading) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ScoreDialSkeleton()
                    }
                } else {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        M3ScoreDial(
                            score = uiState.latestSummary?.stepCount?.toFloat(),
                            label = "Steps Today",
                            maxScore = (uiState.stepGoal * 1.5f),
                            status = uiState.latestSummary?.stepCount?.let { stepsStatus(it, uiState.stepGoal) },
                            tooltipDescription = "Total steps recorded today.\nGoal: ${uiState.stepGoal} steps.",
                        )
                    }
                }
            }

            if (!uiState.isLoading) {
                item(key = "trends_header") {
                    SectionHeader(title = "Trends")
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

                item(key = "spacer_trends") { Spacer(Modifier.height(8.dp)) }
            }

            item(key = "steps_chart") {
                if (uiState.isLoading) {
                    SkeletonCard(
                        height = 250.dp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                } else {
                    TrendCard(
                        title = "Daily Steps",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        TrendChart(
                            points = uiState.dailySteps,
                            rangeStartMs = uiState.rangeStartMs,
                            rangeDays = uiState.selectedRange.days,
                            metricName = "Steps",
                            baselineUnit = "steps",
                            baseline = uiState.stepGoal.toFloat(),
                            scrollState = chartScrollState,
                            zoomState = chartZoomState,
                            parentScrollInProgress = listState.isScrollInProgress,
                        )
                    }
                }
            }

            item(key = "spacer_bottom") { Spacer(Modifier.height(16.dp)) }
        }
    }
}
