package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.readylytics.health.feature.workouts.R
import app.readylytics.health.core.ui.common.CardLoader
import app.readylytics.health.core.ui.common.ScoreDialSkeleton
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.common.formatRoundedScoreDelta
import app.readylytics.health.core.ui.common.resolveOrNull
import app.readylytics.health.core.ui.components.M3ScoreGaugeCard
import app.readylytics.health.core.ui.components.MetricTooltip
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.strainRatioStatus
import app.readylytics.health.feature.workouts.RasWeeklyBar
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState

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
        CardLoader(
            isLoading = uiState.isLoading,
            skeleton = {
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
            },
            content = {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val strainRatio = uiState.latestMetrics?.strainRatioRaw
                    val strainStatus = strainRatio?.strainRatioStatus() ?: MetricStatus.CALIBRATING
                    val strainTooltip = stringResource(R.string.tooltip_strain_ratio)

                    val strainDelta =
                        if (uiState.todayStrainIncrease != null) {
                            if (uiState.todayStrainIncrease > 0.005f) {
                                val diffFormatted =
                                    String.format(
                                        java.util.Locale.US,
                                        "%.2f",
                                        uiState.todayStrainIncrease,
                                    )
                                stringResource(R.string.delta_up) + " $diffFormatted"
                            } else {
                                stringResource(R.string.delta_no_change)
                            }
                        } else {
                            null
                        }

                    M3ScoreGaugeCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.card_title_strain_ratio),
                        score = strainRatio,
                        displayText =
                            uiState.latestMetrics?.strainRatioDisplay ?: stringResource(R.string.delta_no_change),
                        unitText = "",
                        maxScore = 2.0f,
                        status = strainStatus,
                        deltaText = strainDelta,
                        tooltipDescription = strainTooltip,
                    )

                    val readinessVal = uiState.latestMetrics?.readinessRounded?.toFloat()
                    val readinessDelta =
                        formatRoundedScoreDelta(
                            currentRounded = uiState.latestMetrics?.readinessRounded,
                            previousRounded = uiState.yesterdayReadiness?.toInt(),
                        ).resolveOrNull()

                    M3ScoreGaugeCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.card_title_readiness),
                        score = readinessVal,
                        displayText =
                            uiState.latestMetrics?.readinessRounded?.toString()
                                ?: stringResource(R.string.delta_no_change),
                        unitText = "",
                        deltaText = readinessDelta,
                        tooltipDescription = stringResource(R.string.tooltip_readiness),
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
                            .padding(horizontal = 16.dp),
                )
            },
            content = {
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .graphicsLayer { },
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
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
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                                    description = stringResource(R.string.tooltip_ras),
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        RasWeeklyBar(
                            dailyBreakdown = uiState.rasDailyBreakdown,
                            totalRas = uiState.latestMetrics?.rasRounded?.toFloat() ?: 0f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
        )

        Spacer(Modifier.height(8.dp))
        SectionHeader(
            title = stringResource(R.string.workout_stats_acwr_title),
            enabled = !uiState.isLoading,
        )
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
                    enabled = !uiState.isLoading,
                    shape =
                        SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = TimeRange.entries.size,
                        ),
                    label = { Text(range.label) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        CardLoader(
            isLoading = uiState.isLoading,
            skeleton = {
                SkeletonCard(
                    height = 312.dp,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                )
            },
            content = {
                AcwrChartCard(
                    trimpPoints = uiState.dailyTrimp,
                    ratioPoints = uiState.dailyStrainRatio,
                    rangeStartMs = uiState.rangeStartMs,
                    rangeDays = rangeDays,
                    scrollState = scrollState,
                    zoomState = zoomState,
                    parentScrollInProgress = parentScrollInProgress,
                    modifier =
                        Modifier
                            .padding(horizontal = 16.dp)
                            .graphicsLayer { },
                )
            },
        )

        Spacer(Modifier.height(24.dp))
    }
}
