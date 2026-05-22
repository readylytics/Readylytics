package com.gregor.lauritz.healthdashboard.ui.bodyfat

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
import com.gregor.lauritz.healthdashboard.ui.components.SectionHeader
import com.gregor.lauritz.healthdashboard.ui.components.TrendCard
import com.gregor.lauritz.healthdashboard.ui.components.TrendChart

@Composable
fun BodyFatDetailRoute(
    onBack: () -> Unit,
    viewModel: BodyFatDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BodyFatDetailScreen(
        uiState = uiState,
        onBack = onBack,
        onRangeSelected = viewModel::onRangeSelected,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BodyFatDetailScreen(
    uiState: BodyFatDetailUiState,
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
                title = { Text("Body Fat Percentage") },
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
            item(key = "body_fat_info") {
                TrendCard(
                    title = "Current Body Fat",
                    value = uiState.latestBodyFat?.let { String.format("%.1f %%", it) } ?: "—",
                    subtitle = "Age: ${uiState.age} years",
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            item(key = "ranges_info") {
                TrendCard(
                    title = "Optimal Ranges",
                    value = "Age & Gender Based",
                    subtitle = "Male 20-40: 8-19%\nFemale 20-40: 21-32%",
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

            item(key = "trend_chart") {
                SectionHeader(title = "Body Fat Trend")
                TrendChart(
                    points = uiState.dailyBodyFat,
                    rangeStartMs = uiState.rangeStartMs,
                    rangeDays = uiState.selectedRange.days,
                    metricName = "Body Fat",
                    baselineUnit = "%",
                    scrollState = chartScrollState,
                    zoomState = chartZoomState,
                    modifier = Modifier.fillMaxWidth().height(250.dp),
                )
            }
        }
    }
}
