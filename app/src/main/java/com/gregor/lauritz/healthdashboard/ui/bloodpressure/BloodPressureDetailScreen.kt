package com.gregor.lauritz.healthdashboard.ui.bloodpressure

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.components.BloodPressureSplitChart
import com.gregor.lauritz.healthdashboard.ui.components.ChartDefaults
import com.gregor.lauritz.healthdashboard.ui.components.M3ScoreDial
import com.gregor.lauritz.healthdashboard.ui.components.SectionHeader
import com.gregor.lauritz.healthdashboard.ui.components.TrendCard

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
                title = { Text("Blood Pressure") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            val systolicStatus =
                when (uiState.latestSystolic) {
                    null -> MetricStatus.CALIBRATING
                    in 0..119 -> MetricStatus.OPTIMAL
                    in 120..129 -> MetricStatus.NEUTRAL
                    in 130..139 -> MetricStatus.WARNING
                    else -> MetricStatus.POOR
                }

            val diastolicStatus =
                when (uiState.latestDiastolic) {
                    null -> MetricStatus.CALIBRATING
                    in 0..79 -> MetricStatus.OPTIMAL
                    in 80..89 -> MetricStatus.WARNING
                    else -> MetricStatus.POOR
                }

            item(key = "score_dials") {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    M3ScoreDial(
                        score = uiState.latestSystolic?.toFloat(),
                        label = "Systolic",
                        maxScore = 200f,
                        status = systolicStatus,
                        displayText = uiState.latestSystolic?.toString(),
                        tooltipDescription =
                            "Latest systolic blood pressure measurement.\n\n" +
                                "Optimal: <120\nElevated: 120–129\nStage 1: 130–139\nStage 2: ≥140",
                    )
                    M3ScoreDial(
                        score = uiState.latestDiastolic?.toFloat(),
                        label = "Diastolic",
                        maxScore = 120f,
                        status = diastolicStatus,
                        displayText = uiState.latestDiastolic?.toString(),
                        tooltipDescription =
                            "Latest diastolic blood pressure measurement.\n\n" +
                                "Optimal: <80\nStage 1: 80–89\nStage 2: ≥90",
                    )
                }
            }

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

            item(key = "bp_chart") {
                TrendCard(
                    title = "Blood Pressure Trend",
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    BloodPressureSplitChart(
                        systolicPoints = uiState.dailySystolic,
                        diastolicPoints = uiState.dailyDiastolic,
                        rangeStartMs = uiState.rangeStartMs,
                        rangeDays = uiState.selectedRange.days,
                        scrollState = chartScrollState,
                        zoomState = chartZoomState,
                        parentScrollInProgress = listState.isScrollInProgress,
                    )
                }
            }

            item(key = "spacer_bottom") { Spacer(Modifier.height(16.dp)) }
        }
    }
}
