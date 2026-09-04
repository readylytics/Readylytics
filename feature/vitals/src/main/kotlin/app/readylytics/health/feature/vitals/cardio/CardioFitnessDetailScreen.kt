package app.readylytics.health.feature.vitals.cardio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.scoring.domain.cardio.CooperCategory
import app.readylytics.health.core.ui.common.DeltaDirection
import app.readylytics.health.core.ui.common.ScoreDialSkeleton
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.core.ui.components.TrendCard
import app.readylytics.health.core.ui.components.TrendChart
import app.readylytics.health.core.ui.components.containerColor
import app.readylytics.health.core.ui.components.onContainerColor
import app.readylytics.health.feature.vitals.R
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
fun CardioFitnessDetailRoute(
    onBack: () -> Unit,
    viewModel: CardioFitnessDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    CardioFitnessDetailScreen(
        uiState = uiState,
        onBack = onBack,
        onRangeSelected = viewModel::onRangeSelected,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardioFitnessDetailScreen(
    uiState: CardioFitnessDetailUiState,
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
                title = { Text(stringResource(R.string.label_cardio_fitness)) },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(CoreUiR.string.back),
                        )
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
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(
                        top = MaterialTheme.spacing.pageTop,
                        bottom = MaterialTheme.spacing.pageBottom,
                    ),
        ) {
            CardioFitnessOverviewSection(uiState)
            CardioFitnessRangeSelector(uiState, onRangeSelected)
            CardioFitnessTrendSection(uiState, chartScrollState, chartZoomState) {
                scrollState.isScrollInProgress
            }
            CardioFitnessSupplementarySections(uiState)
            Spacer(Modifier.height(MaterialTheme.spacing.pageBottom))
        }
    }
}

@Composable
private fun CardioFitnessOverviewSection(uiState: CardioFitnessDetailUiState) {
    val sectionModifier =
        Modifier
            .fillMaxWidth()
            .padding(
                horizontal = MaterialTheme.spacing.pageHorizontal,
                vertical = MaterialTheme.spacing.pageSectionGapSmall,
            )
    if (uiState.isLoading) {
        ScoreDialSkeleton(modifier = sectionModifier)
    } else {
        CardioFitnessOverviewCard(uiState = uiState, modifier = sectionModifier)
    }
}

@Composable
private fun CardioFitnessRangeSelector(
    uiState: CardioFitnessDetailUiState,
    onRangeSelected: (TimeRange) -> Unit,
) {
    SectionHeader(title = stringResource(CoreUiR.string.label_trends))
    Spacer(Modifier.height(MaterialTheme.spacing.small))
    SingleChoiceSegmentedButtonRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
    ) {
        TimeRange.entries.forEachIndexed { index, range ->
            SegmentedButton(
                selected = uiState.selectedRange == range,
                onClick = { onRangeSelected(range) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = TimeRange.entries.size),
                enabled = !uiState.isLoading,
                label = { Text(range.label) },
            )
        }
    }
    Spacer(Modifier.height(MaterialTheme.spacing.small))
}

@Composable
private fun CardioFitnessTrendSection(
    uiState: CardioFitnessDetailUiState,
    chartScrollState: VicoScrollState,
    chartZoomState: VicoZoomState,
    parentScrollInProgress: () -> Boolean,
) {
    if (uiState.isLoading) {
        SkeletonCard(
            height = 250.dp,
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
        )
        return
    }
    TrendCard(
        title = stringResource(R.string.label_cardio_fitness_trend),
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
    ) {
        TrendChart(
            points = uiState.dailyVo2Max,
            rangeStartMs = uiState.rangeStartMs,
            rangeDays = uiState.selectedRange.days,
            baselineUnit = stringResource(R.string.unit_ml_kg_min),
            baseline = uiState.averageVo2Max,
            baselineLabel = stringResource(CoreUiR.string.label_average),
            baselineDecimalPlaces = 1,
            axisDecimalPlaces = 1,
            scrollState = chartScrollState,
            zoomState = chartZoomState,
            zoneBands = uiState.chartZoneBands,
            parentScrollInProgress = parentScrollInProgress,
            granularity = uiState.selectedRange.granularity,
            deltaDirection = DeltaDirection.HIGHER_IS_BETTER,
        )
    }
}

@Composable
private fun CardioFitnessSupplementarySections(uiState: CardioFitnessDetailUiState) {
    if (uiState.isLoading) return

    Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))
    CooperLadderCard(
        uiState = uiState,
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
    )

    Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))
    MethodologyCard(
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
    )
}

@Composable
private fun CardioFitnessOverviewCard(
    uiState: CardioFitnessDetailUiState,
    modifier: Modifier = Modifier,
) {
    val category = uiState.cooperCategory

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.medium)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = uiState.vo2MaxDisplay ?: stringResource(CoreUiR.string.metric_value_unavailable),
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = stringResource(R.string.unit_ml_kg_min),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.padding(
                            start = MaterialTheme.spacing.extraSmall,
                            bottom = MaterialTheme.spacing.extraSmall,
                        ),
                )
            }

            Spacer(Modifier.height(MaterialTheme.spacing.small))

            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                if (category != null) {
                    val status = category.toMetricStatus()
                    StatusChip(
                        text = stringResource(categoryLabelRes(category)),
                        containerColor = status.containerColor(),
                        contentColor = status.onContainerColor(),
                    )
                }
                sourceLabelRes(uiState.currentSource)?.let { labelRes ->
                    StatusChip(
                        text = stringResource(labelRes),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = containerColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            modifier =
                Modifier.padding(
                    horizontal = MaterialTheme.spacing.small,
                    vertical = MaterialTheme.spacing.extraSmall,
                ),
        )
    }
}

/** Shared with [CooperLadderCard] in CardioFitnessSupplementaryCards.kt. */
internal fun categoryLabelRes(category: CooperCategory): Int =
    when (category) {
        CooperCategory.SUPERIOR -> R.string.cooper_category_superior
        CooperCategory.EXCELLENT -> R.string.cooper_category_excellent
        CooperCategory.GOOD -> R.string.cooper_category_good
        CooperCategory.FAIR -> R.string.cooper_category_fair
        CooperCategory.POOR -> R.string.cooper_category_poor
    }

private fun sourceLabelRes(source: String?): Int? =
    when (source) {
        "WEARABLE" -> R.string.vitals_vo2_max_source_wearable
        "ESTIMATED_UTH" -> R.string.vitals_vo2_max_source_estimated
        else -> null
    }
