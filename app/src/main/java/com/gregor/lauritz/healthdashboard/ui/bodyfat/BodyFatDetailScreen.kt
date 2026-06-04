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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.R
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.data.preferences.Gender
import com.gregor.lauritz.healthdashboard.domain.model.bodyFatZoneBands
import com.gregor.lauritz.healthdashboard.ui.common.ScoreDialSkeleton
import com.gregor.lauritz.healthdashboard.ui.common.SkeletonCard
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.components.ChartDefaults
import com.gregor.lauritz.healthdashboard.ui.components.M3ScoreDial
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

    val bodyFatBands =
        remember(uiState.age, uiState.gender) {
            val genderEnum = Gender.entries.firstOrNull { it.name == uiState.gender }
            if (genderEnum != null && genderEnum != Gender.OTHER && genderEnum != Gender.PREFER_NOT_TO_SAY) {
                bodyFatZoneBands(uiState.age, genderEnum)
            } else {
                null
            }
        }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.label_body_fat_percentage)) },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
                } else if (uiState.optimalRangeMax > 0f) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        M3ScoreDial(
                            score = uiState.latestBodyFat,
                            label = stringResource(R.string.label_body_fat),
                            maxScore = uiState.optimalRangeMax * 2f,
                            status = uiState.bodyFatStatus,
                            displayText = uiState.bodyFatDisplay,
                            tooltipDescription = stringResource(
                                R.string.tooltip_body_fat_current,
                                uiState.bodyFatDisplay ?: "—",
                                uiState.optimalRangeDisplay ?: "—",
                                uiState.gender,
                                uiState.age,
                            ),
                        )
                    }
                }
            }

            item(key = "trends_header") {
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
            }

            item(key = "spacer_trends") { Spacer(Modifier.height(8.dp)) }

            item(key = "body_fat_chart") {
                if (uiState.isLoading) {
                    SkeletonCard(
                        height = 250.dp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                } else {
                    TrendCard(
                        title = stringResource(R.string.label_body_fat_trend),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        TrendChart(
                            points = uiState.dailyBodyFat,
                            rangeStartMs = uiState.rangeStartMs,
                            rangeDays = uiState.selectedRange.days,
                            metricName = "Body Fat",
                            baselineUnit = "%",
                            baseline = uiState.averageBodyFat,
                            baselineLabel = stringResource(R.string.label_average),
                            baselineDecimalPlaces = 1,
                            axisDecimalPlaces = 1,
                            scrollState = chartScrollState,
                            zoomState = chartZoomState,
                            zoneBands = bodyFatBands,
                        )
                    }
                }
            }

            item(key = "spacer_bottom") { Spacer(Modifier.height(16.dp)) }
        }
    }
}
