package com.gregor.lauritz.healthdashboard.ui.weight

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.gregor.lauritz.healthdashboard.ui.components.M3ScoreDial
import com.gregor.lauritz.healthdashboard.ui.components.SectionHeader
import com.gregor.lauritz.healthdashboard.ui.components.TrendCard
import com.gregor.lauritz.healthdashboard.ui.components.TrendChart
import java.util.Locale

@Composable
fun WeightDetailRoute(
    onBack: () -> Unit,
    viewModel: WeightDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    WeightDetailScreen(
        uiState = uiState,
        onBack = onBack,
        onRangeSelected = viewModel::onRangeSelected,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeightDetailScreen(
    uiState: WeightDetailUiState,
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
                title = { Text("Weight") },
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
            item(key = "bmi_gauge") {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    M3ScoreDial(
                        score =
                            uiState.bmi
                                ?.times(10)
                                ?.toInt()
                                ?.div(10f)
                                ?.times(10f),
                        label = "BMI",
                        tooltipDescription =
                            "Body Mass Index (50% = 20)\n\n" +
                                "Under 25: Normal\n25-30: Overweight\n30+: Obese",
                    )
                }
            }

            item(key = "weight_info") {
                TrendCard(
                    title = "Current Weight",
                    value =
                        uiState.latestWeight?.let {
                            String.format(Locale.getDefault(), "%.1f kg", it)
                        } ?: "—",
                    subtitle =
                        uiState.heightCm?.let {
                            "Height: ${String.format(Locale.getDefault(), "%.0f cm", it)}"
                        } ?: "",
                )
                Spacer(modifier = Modifier.height(8.dp))
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

            item(key = "trend_chart") {
                SectionHeader(title = "Weight Trend")
                TrendChart(
                    points = uiState.dailyWeights,
                    rangeStartMs = uiState.rangeStartMs,
                    rangeDays = uiState.selectedRange.days,
                    metricName = "Weight",
                    baselineUnit = "kg",
                    scrollState = chartScrollState,
                    zoomState = chartZoomState,
                    modifier = Modifier.fillMaxWidth().height(250.dp),
                )
            }
        }
    }
}
