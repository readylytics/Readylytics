package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.common.CardLoader
import app.readylytics.health.core.ui.common.ScoreDialSkeleton
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.common.formatRoundedScoreDelta
import app.readylytics.health.core.ui.common.resolveOrNull
import app.readylytics.health.core.ui.components.MetricTooltip
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.domain.display.MetricFormatter
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.strainRatioStatus
import app.readylytics.health.feature.workouts.R
import app.readylytics.health.feature.workouts.RasWeeklyBar
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import app.readylytics.health.core.ui.R as CoreUiR

internal enum class RasSummaryValueTextStyle {
    TITLE,
}

internal fun rasTotalValueTextStyle(): RasSummaryValueTextStyle = RasSummaryValueTextStyle.TITLE

@Composable
private fun RasSummaryValueTextStyle.asTextStyle(): TextStyle =
    when (this) {
        RasSummaryValueTextStyle.TITLE -> MaterialTheme.typography.titleMedium
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutStatsSection(
    uiState: WorkoutsUiState,
    selectedRange: TimeRange,
    onRangeSelected: (TimeRange) -> Unit,
    modifier: Modifier = Modifier,
    rangeDays: Int = uiState.selectedRange.days,
    scrollState: VicoScrollState = rememberVicoScrollState(scrollEnabled = rangeDays > 7),
    zoomState: VicoZoomState =
        rememberVicoZoomState(
            zoomEnabled = rangeDays > 7,
            initialZoom = Zoom.Content,
            // Floor zoom-out at the fit-to-range view (see ChartDefaults.rememberChartState):
            // prevents zooming out past the initial range / revealing future dates.
            minZoom = Zoom.min(Zoom.Content, Zoom.fixed(1f)),
            maxZoom =
                remember(rangeDays) {
                    when (rangeDays) {
                        30 -> Zoom.fixed(6f)
                        180 -> Zoom.fixed(25f)
                        else -> Zoom.Content
                    }
                },
        ),
    parentScrollInProgress: () -> Boolean = { false },
) {
    Column(modifier = modifier) {
        // Note: graphicsLayer{} intentionally omitted for performance (F19)
        CardLoader(
            isLoading = uiState.isLoading,
            skeleton = {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = MaterialTheme.spacing.pageHorizontal,
                                vertical = MaterialTheme.spacing.small,
                            ),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScoreDialSkeleton(height = 156.dp, modifier = Modifier.weight(1f))
                    ScoreDialSkeleton(height = 156.dp, modifier = Modifier.weight(1f))
                }
            },
            content = {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = MaterialTheme.spacing.pageHorizontal,
                                vertical = MaterialTheme.spacing.small,
                            ),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val strainRatio = uiState.latestMetrics?.strainRatioRaw
                    val strainStatus = strainRatio?.strainRatioStatus() ?: MetricStatus.CALIBRATING
                    val strainTooltip = stringResource(CoreUiR.string.tooltip_strain_ratio)

                    val strainDelta =
                        if (uiState.todayStrainIncrease != null) {
                            if (uiState.todayStrainIncrease > 0.005f) {
                                val diffFormatted = MetricFormatter.formatStrain(uiState.todayStrainIncrease)
                                stringResource(
                                    CoreUiR.string.delta_up_format,
                                    stringResource(CoreUiR.string.delta_up),
                                    diffFormatted,
                                )
                            } else {
                                stringResource(CoreUiR.string.delta_no_change)
                            }
                        } else {
                            null
                        }

                    UniversalWorkoutMetricCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(CoreUiR.string.card_title_strain_ratio),
                        rawValue = strainRatio,
                        valueText =
                            uiState.latestMetrics?.strainRatioDisplay ?: stringResource(CoreUiR.string.delta_no_change),
                        unitText = "",
                        maxValue = 2.0f,
                        status = strainStatus,
                        secondaryText = strainDelta,
                        tooltip = strainTooltip,
                        mode = app.readylytics.health.core.ui.components.metriccard.UniversalCardDisplayMode.GAUGE,
                    )

                    val readinessVal = uiState.latestMetrics?.readinessRounded?.toFloat()
                    val readinessDelta =
                        formatRoundedScoreDelta(
                            currentRounded = uiState.latestMetrics?.readinessRounded,
                            previousRounded = uiState.yesterdayReadiness?.toInt(),
                        ).resolveOrNull()

                    UniversalWorkoutMetricCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(CoreUiR.string.card_title_readiness),
                        status =
                            readinessVal?.let {
                                when {
                                    it >= 85f -> MetricStatus.OPTIMAL
                                    it >= 60f -> MetricStatus.NEUTRAL
                                    it >= 40f -> MetricStatus.WARNING
                                    else -> MetricStatus.POOR
                                }
                            } ?: MetricStatus.CALIBRATING,
                        rawValue = readinessVal,
                        valueText =
                            uiState.latestMetrics?.readinessRounded?.toString()
                                ?: stringResource(CoreUiR.string.delta_no_change),
                        unitText = "",
                        secondaryText = readinessDelta,
                        tooltip = stringResource(CoreUiR.string.tooltip_readiness),
                        mode = app.readylytics.health.core.ui.components.metriccard.UniversalCardDisplayMode.GAUGE,
                    )
                }
            },
        )

        CardLoader(
            isLoading = uiState.isLoading,
            skeleton = {
                SkeletonCard(
                    height = 160.dp,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                )
            },
            content = {
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(modifier = Modifier.padding(MaterialTheme.spacing.medium)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = stringResource(R.string.workout_stats_ras_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                uiState.latestMetrics?.rasRounded?.let { total ->
                                    Text(
                                        text = total.toString(),
                                        style = rasTotalValueTextStyle().asTextStyle(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                MetricTooltip(
                                    description = stringResource(CoreUiR.string.tooltip_ras),
                                )
                            }
                        }
                        Spacer(Modifier.height(MaterialTheme.spacing.smallMedium))
                        RasWeeklyBar(
                            dailyBreakdown = uiState.rasDailyBreakdown,
                            totalRas = uiState.latestMetrics?.rasRounded?.toFloat() ?: 0f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
        )

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
        SectionHeader(
            title = stringResource(R.string.workout_stats_acwr_title),
            enabled = !uiState.isLoading,
        )
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
        SingleChoiceSegmentedButtonRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
        ) {
            TimeRange.entries.forEachIndexed { index, range ->
                SegmentedButton(
                    selected = selectedRange == range,
                    onClick = { onRangeSelected(range) },
                    enabled = !uiState.isLoading && !uiState.isRangeChanging,
                    shape =
                        SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = TimeRange.entries.size,
                        ),
                    label = { Text(range.label) },
                )
            }
        }

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

        CardLoader(
            isLoading = uiState.isLoading,
            skeleton = {
                SkeletonCard(
                    height = 312.dp,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                )
            },
            content = {
                Box(
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                ) {
                    AcwrChartCard(
                        trimpPoints = uiState.dailyTrimp,
                        ratioPoints = uiState.dailyStrainRatio,
                        rangeStartMs = uiState.rangeStartMs,
                        rangeDays = rangeDays,
                        scrollState = scrollState,
                        zoomState = zoomState,
                        parentScrollInProgress = parentScrollInProgress,
                        granularity = selectedRange.granularity,
                    )
                    if (uiState.isRangeChanging) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp,
                        )
                    }
                }
            },
        )

        Spacer(Modifier.height(MaterialTheme.spacing.large))
    }
}
