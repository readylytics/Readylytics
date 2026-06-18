package app.readylytics.health.ui.weight

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.R
import app.readylytics.health.data.preferences.UnitSystem
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.weightZoneBands
import app.readylytics.health.ui.common.ScoreDialSkeleton
import app.readylytics.health.ui.common.SkeletonCard
import app.readylytics.health.ui.common.TimeRange
import app.readylytics.health.ui.components.ChartDefaults
import app.readylytics.health.ui.components.M3ScoreGaugeCard
import app.readylytics.health.ui.components.SectionHeader
import app.readylytics.health.ui.components.TrendCard
import app.readylytics.health.ui.components.TrendChart
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

    val weightBands =
        remember(uiState.heightCm, uiState.unitSystem) {
            uiState.heightCm?.let { height ->
                val bands = weightZoneBands(height)
                if (uiState.unitSystem == UnitSystem.IMPERIAL) {
                    val kgToLbs = 2.20462
                    bands.map { band ->
                        band.copy(
                            lowerBound =
                                if (band.lowerBound ==
                                    Double.NEGATIVE_INFINITY
                                ) {
                                    Double.NEGATIVE_INFINITY
                                } else {
                                    band.lowerBound * kgToLbs
                                },
                            upperBound =
                                if (band.upperBound ==
                                    Double.POSITIVE_INFINITY
                                ) {
                                    Double.POSITIVE_INFINITY
                                } else {
                                    band.upperBound * kgToLbs
                                },
                        )
                    }
                } else {
                    bands
                }
            }
        }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.label_weight)) },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
                    .padding(vertical = 16.dp),
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

            if (uiState.isLoading) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScoreDialSkeleton(height = 156.dp, modifier = Modifier.weight(1f))
                    ScoreDialSkeleton(height = 156.dp, modifier = Modifier.weight(1f))
                }
            } else {
                val weightMaxScore = if (uiState.unitSystem == UnitSystem.METRIC) 150f else 330f
                val heightTooltip =
                    uiState.heightCm?.let { cm ->
                        if (uiState.unitSystem == UnitSystem.METRIC) {
                            String.format(Locale.US, "%.0f cm", cm)
                        } else {
                            val totalInches = cm / 2.54f
                            val feet = floor(totalInches / 12f).toInt()
                            val inches = (totalInches % 12f).roundToInt()
                            val (finalFeet, finalInches) =
                                if (inches ==
                                    12
                                ) {
                                    Pair(feet + 1, 0)
                                } else {
                                    Pair(feet, inches)
                                }
                            "$finalFeet'$finalInches\""
                        }
                    } ?: "—"

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    M3ScoreGaugeCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.label_weight_unit_format, unitLabel),
                        score = uiState.latestWeight,
                        displayText = uiState.weightDisplay ?: "—",
                        unitText = unitLabel,
                        maxScore = weightMaxScore,
                        status = bmiStatus,
                        deltaText = uiState.deltaWeightDisplay,
                        tooltipDescription =
                            stringResource(
                                R.string.tooltip_weight_current,
                                uiState.weightDisplay?.let { "$it $unitLabel" } ?: "—",
                                heightTooltip,
                            ),
                    )
                    M3ScoreGaugeCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.label_bmi),
                        score = uiState.bmi,
                        displayText = uiState.bmiDisplay ?: "—",
                        unitText = "",
                        maxScore = 40f,
                        status = bmiStatus,
                        deltaText = null,
                        tooltipDescription = stringResource(R.string.tooltip_bmi),
                    )
                }
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
                    title = stringResource(R.string.label_weight_trend),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    TrendChart(
                        points = uiState.dailyWeights,
                        rangeStartMs = uiState.rangeStartMs,
                        rangeDays = uiState.selectedRange.days,
                        metricName = "Weight",
                        baselineUnit = unitLabel,
                        baseline = uiState.averageWeight,
                        baselineLabel = stringResource(R.string.label_average),
                        baselineDecimalPlaces = 1,
                        axisDecimalPlaces = 1,
                        scrollState = chartScrollState,
                        zoomState = chartZoomState,
                        zoneBands = weightBands,
                        parentScrollInProgress = scrollState.isScrollInProgress,
                    )
                }
            }

            if (uiState.historyItems.isNotEmpty()) {
                WeightHistorySection(items = uiState.historyItems)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
