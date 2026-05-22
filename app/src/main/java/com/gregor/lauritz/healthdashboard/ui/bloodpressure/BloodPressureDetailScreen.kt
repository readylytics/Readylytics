package com.gregor.lauritz.healthdashboard.ui.bloodpressure

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.components.ChartDefaults
import com.gregor.lauritz.healthdashboard.ui.components.SectionHeader
import com.gregor.lauritz.healthdashboard.ui.components.TrendCard
import com.gregor.lauritz.healthdashboard.ui.components.TrendChart

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
        LazyColumn(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item(key = "bp_info") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                ) {
                    TrendCard(
                        title = "Systolic",
                        value = uiState.latestSystolic?.toString() ?: "—",
                        subtitle = "mmHg",
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TrendCard(
                        title = "Diastolic",
                        value = uiState.latestDiastolic?.toString() ?: "—",
                        subtitle = "mmHg",
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item(key = "bp_stages") {
                TrendCard(
                    title = "Blood Pressure Stages",
                    value = "Reference",
                    subtitle = "Optimal: <120/80\nElevated: 120-129/<80\nStage 1: 130-139/80-89\nStage 2: ≥140/90",
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            item(key = "time_range") {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    SingleChoiceSegmentedButtonRow {
                        TimeRange.entries.forEachIndexed { index, range ->
                            SegmentedButton(
                                selected = uiState.selectedRange == range,
                                onClick = { onRangeSelected(range) },
                                shape = SegmentedButtonDefaults.itemShape(index, TimeRange.entries.size),
                                label = { Text(range.label) },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item(key = "systolic_chart") {
                SectionHeader(title = "Systolic Trend")
                TrendChart(
                    points = uiState.dailySystolic,
                    rangeStartMs = uiState.rangeStartMs,
                    rangeDays = uiState.selectedRange.days,
                    metricName = "Systolic",
                    baselineUnit = "mmHg",
                    baseline = 120f,
                    scrollState = chartScrollState,
                    zoomState = chartZoomState,
                    modifier = Modifier.fillMaxWidth().height(250.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            item(key = "diastolic_chart") {
                SectionHeader(title = "Diastolic Trend")
                TrendChart(
                    points = uiState.dailyDiastolic,
                    rangeStartMs = uiState.rangeStartMs,
                    rangeDays = uiState.selectedRange.days,
                    metricName = "Diastolic",
                    baselineUnit = "mmHg",
                    baseline = 80f,
                    scrollState = chartScrollState,
                    zoomState = chartZoomState,
                    modifier = Modifier.fillMaxWidth().height(250.dp),
                )
            }
        }
    }
}
