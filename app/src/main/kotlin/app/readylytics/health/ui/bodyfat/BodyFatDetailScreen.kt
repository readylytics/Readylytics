package app.readylytics.health.ui.bodyfat

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.R
import app.readylytics.health.data.preferences.Gender
import app.readylytics.health.domain.model.bodyFatZoneBands
import app.readylytics.health.ui.common.ScoreDialSkeleton
import app.readylytics.health.ui.common.SkeletonCard
import app.readylytics.health.ui.common.TimeRange
import app.readylytics.health.ui.components.ChartDefaults
import app.readylytics.health.ui.components.M3ScoreGaugeCard
import app.readylytics.health.ui.components.SectionHeader
import app.readylytics.health.ui.components.TrendCard
import app.readylytics.health.ui.components.TrendChart

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
        val scrollState = rememberScrollState()
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
            } else if (uiState.optimalRangeMax > 0f) {
                val genderStringRes =
                    when (Gender.entries.find { it.name == uiState.gender }) {
                        Gender.MALE -> R.string.gender_male
                        Gender.FEMALE -> R.string.gender_female
                        Gender.OTHER -> R.string.gender_other
                        Gender.PREFER_NOT_TO_SAY -> R.string.gender_prefer_not_to_say
                        else -> R.string.gender_other
                    }
                M3ScoreGaugeCard(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    title = stringResource(R.string.label_body_fat),
                    score = uiState.latestBodyFat,
                    displayText = uiState.bodyFatDisplay ?: "—",
                    unitText = stringResource(R.string.unit_percent),
                    maxScore = uiState.optimalRangeMax * 2f,
                    status = uiState.bodyFatStatus,
                    deltaText = uiState.deltaBodyFatDisplay,
                    tooltipDescription =
                        stringResource(
                            R.string.tooltip_body_fat_current,
                            uiState.bodyFatDisplay ?: "—",
                            uiState.optimalRangeDisplay ?: "—",
                            stringResource(genderStringRes),
                            uiState.age,
                        ),
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
                        parentScrollInProgress = scrollState.isScrollInProgress,
                    )
                }
            }

            if (uiState.historyItems.isNotEmpty()) {
                BodyFatHistorySection(items = uiState.historyItems)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
