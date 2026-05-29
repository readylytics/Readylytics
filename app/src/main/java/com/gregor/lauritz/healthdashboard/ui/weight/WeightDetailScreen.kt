package com.gregor.lauritz.healthdashboard.ui.weight

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
import com.gregor.lauritz.healthdashboard.data.preferences.UnitSystem
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.components.ChartDefaults
import com.gregor.lauritz.healthdashboard.ui.components.M3ScoreDial
import com.gregor.lauritz.healthdashboard.ui.components.SectionHeader
import com.gregor.lauritz.healthdashboard.ui.components.TrendCard
import com.gregor.lauritz.healthdashboard.ui.components.TrendChart
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

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

    val unitLabel = if (uiState.unitSystem == UnitSystem.METRIC) "kg" else "lbs"

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
            val bmiStatus =
                uiState.bmi?.let { bmi ->
                    when {
                        bmi < 25f -> MetricStatus.OPTIMAL
                        bmi < 30f -> MetricStatus.NEUTRAL
                        bmi < 35f -> MetricStatus.WARNING
                        else -> MetricStatus.POOR
                    }
                } ?: MetricStatus.CALIBRATING

            item(key = "score_dials") {
                val weightMaxScore = if (uiState.unitSystem == UnitSystem.METRIC) 150f else 330f
                val heightTooltip =
                    uiState.heightCm?.let { cm ->
                        if (uiState.unitSystem == UnitSystem.METRIC) {
                            String.format(Locale.US, "%.0f cm", cm)
                        } else {
                            val totalInches = cm / 2.54f
                            val feet = floor(totalInches / 12f).toInt()
                            val inches = (totalInches % 12f).roundToInt()
                            val (finalFeet, finalInches) = if (inches == 12) Pair(feet + 1, 0) else Pair(feet, inches)
                            "$finalFeet'$finalInches\""
                        }
                    } ?: "—"

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    M3ScoreDial(
                        score = uiState.latestWeight,
                        label = "Weight ($unitLabel)",
                        maxScore = weightMaxScore,
                        status = bmiStatus,
                        displayText =
                            uiState.latestWeight?.let {
                                String.format(
                                    Locale.US,
                                    "%.1f %s",
                                    it,
                                    unitLabel,
                                )
                            },
                        tooltipDescription = "Latest weight measurement.\nHeight: $heightTooltip",
                    )
                    M3ScoreDial(
                        score = uiState.bmi,
                        label = "BMI",
                        maxScore = 40f,
                        status = bmiStatus,
                        displayText = uiState.bmi?.let { String.format(Locale.US, "%.1f", it) },
                        tooltipDescription =
                            "Body Mass Index (Normal: 18.5–24.9)\n\n" +
                                "Under 25: Normal\n" +
                                "25-30: Overweight\n" +
                                "30+: Obese",
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

            item(key = "weight_chart") {
                TrendCard(
                    title = "Weight Trend",
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    TrendChart(
                        points = uiState.dailyWeights,
                        rangeStartMs = uiState.rangeStartMs,
                        rangeDays = uiState.selectedRange.days,
                        metricName = "Weight",
                        baselineUnit = unitLabel,
                        baseline = uiState.averageWeight,
                        baselineLabel = "Average",
                        baselineDecimalPlaces = 1,
                        axisDecimalPlaces = 1,
                        scrollState = chartScrollState,
                        zoomState = chartZoomState,
                    )
                }
            }

            item(key = "spacer_bottom") { Spacer(Modifier.height(16.dp)) }
        }
    }
}
