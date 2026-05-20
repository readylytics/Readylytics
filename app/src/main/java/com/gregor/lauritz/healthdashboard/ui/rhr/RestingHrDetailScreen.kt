package com.gregor.lauritz.healthdashboard.ui.rhr

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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.components.M3ScoreDial
import com.gregor.lauritz.healthdashboard.ui.components.SectionHeader
import com.gregor.lauritz.healthdashboard.ui.components.TrendCard
import com.gregor.lauritz.healthdashboard.ui.components.TrendChart
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState

@Composable
fun RestingHrDetailRoute(
    onBack: () -> Unit,
    viewModel: RestingHrDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    RestingHrDetailScreen(
        uiState = uiState,
        onBack = onBack,
        onRangeSelected = viewModel::onRangeSelected,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestingHrDetailScreen(
    uiState: RestingHrDetailUiState,
    onBack: () -> Unit,
    onRangeSelected: (TimeRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rangeDays = uiState.selectedRange.days
    val (chartScrollState, chartZoomState) =
        key(uiState.selectedRange) {
            val scrollState = rememberVicoScrollState(scrollEnabled = rangeDays > 7)
            val zoomState =
                rememberVicoZoomState(
                    zoomEnabled = rangeDays > 7,
                    initialZoom = Zoom.Content,
                    minZoom = Zoom.Content,
                    maxZoom =
                        remember(rangeDays) {
                            when (rangeDays) {
                                30 -> Zoom.fixed(6f)
                                180 -> Zoom.fixed(25f)
                                else -> Zoom.Content
                            }
                        },
                )
            scrollState to zoomState
        }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Resting Heart Rate") },
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
            item(key = "score_dial") {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    M3ScoreDial(
                        score =
                            (uiState.latestSummary?.restingHeartRate ?: uiState.latestSummary?.nocturnalRhr)
                                ?.toFloat(),
                        label = "Resting HR",
                        maxScore = 120f,
                        status = uiState.rhrStatus,
                        tooltipDescription = "Minimum heart rate captured around wake up time.",
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

            item(key = "rhr_chart") {
                TrendCard(
                    title = "Resting Heart Rate",
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    TrendChart(
                        points = uiState.dailyRhr,
                        rangeStartMs = uiState.rangeStartMs,
                        rangeDays = uiState.selectedRange.days,
                        baselineUnit = "bpm",
                        baseline = uiState.rhrBaseline,
                        showBaseline = !(uiState.latestSummary?.isCalibrating ?: false),
                        scrollState = chartScrollState,
                        zoomState = chartZoomState,
                    )
                }
            }

            item(key = "spacer_bottom") { Spacer(Modifier.height(16.dp)) }
        }
    }
}
