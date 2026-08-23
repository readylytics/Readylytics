package app.readylytics.health.feature.vitals.heartrate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.common.MetricCardSkeleton
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.feature.vitals.R
import app.readylytics.health.feature.vitals.common.formatDurationToMinutes
import app.readylytics.health.feature.vitals.common.metricStatusLabelRes
import app.readylytics.health.feature.vitals.common.zoneColor
import java.time.ZoneId
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
fun HeartRateDetailRoute(
    onBack: () -> Unit,
    viewModel: HeartRateDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HeartRateDetailScreen(
        uiState = uiState,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeartRateDetailScreen(
    uiState: HeartRateDetailUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = uiState.today
    val dayStartMs =
        remember(uiState.selectedDate) {
            uiState.selectedDate
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }
    val dayEndMs =
        remember(uiState.selectedDate) {
            uiState.selectedDate
                .plusDays(1)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(app.readylytics.health.core.ui.R.string.heart_rate_title)) },
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
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(vertical = MaterialTheme.spacing.pageBottom),
        ) {
            if (uiState.isLoading) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
                ) {
                    MetricCardSkeleton(modifier = Modifier.weight(1f), height = 72.dp)
                    MetricCardSkeleton(modifier = Modifier.weight(1f), height = 72.dp)
                    MetricCardSkeleton(modifier = Modifier.weight(1f), height = 72.dp)
                }
            } else if (uiState.minBpm != null && uiState.maxBpm != null) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
                    verticalAlignment = Alignment.Top,
                ) {
                    HrStatCard(stringResource(CoreUiR.string.label_min), "${uiState.minBpm} bpm", Modifier.weight(1f))
                    HrStatCard(stringResource(CoreUiR.string.label_max), "${uiState.maxBpm} bpm", Modifier.weight(1f))
                    HrStatCard(
                        label = stringResource(CoreUiR.string.label_avg),
                        value = "${uiState.avgBpm} bpm",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(MaterialTheme.spacing.small))

            SectionHeader(title = stringResource(CoreUiR.string.label_timeline))
            Spacer(Modifier.height(MaterialTheme.spacing.extraSmall))

            if (uiState.isLoading) {
                SkeletonCard(
                    height = 300.dp,
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                )
            } else {
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                    shape = MaterialTheme.shapes.large,
                ) {
                    HrTimelineChart(
                        samples = uiState.samples,
                        dayStartMs = dayStartMs,
                        dayEndMs = dayEndMs,
                        zone1MinBpm = uiState.zone1MinBpm,
                        zone1MaxBpm = uiState.zone1MaxBpm,
                        zone2MaxBpm = uiState.zone2MaxBpm,
                        zone3MaxBpm = uiState.zone3MaxBpm,
                        zone4MaxBpm = uiState.zone4MaxBpm,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(MaterialTheme.spacing.smallMedium),
                    )
                }
            }

            Spacer(Modifier.height(MaterialTheme.spacing.small))

            SectionHeader(title = stringResource(CoreUiR.string.label_zone_breakdown))
            Spacer(Modifier.height(MaterialTheme.spacing.extraSmall))

            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(modifier = Modifier.padding(MaterialTheme.spacing.medium)) {
                    val zoneDefs =
                        listOf(
                            Triple(
                                0,
                                stringResource(CoreUiR.string.hr_zone_n, 0),
                                stringResource(R.string.hr_zone_0_range, uiState.zone1MinBpm),
                            ),
                            Triple(
                                1,
                                stringResource(CoreUiR.string.hr_zone_n, 1),
                                stringResource(
                                    R.string.hr_zone_inner_range,
                                    uiState.zone1MinBpm,
                                    uiState.zone1MaxBpm,
                                ),
                            ),
                            Triple(
                                2,
                                stringResource(CoreUiR.string.hr_zone_n, 2),
                                stringResource(
                                    R.string.hr_zone_inner_range,
                                    uiState.zone1MaxBpm + 1,
                                    uiState.zone2MaxBpm,
                                ),
                            ),
                            Triple(
                                3,
                                stringResource(CoreUiR.string.hr_zone_n, 3),
                                stringResource(
                                    R.string.hr_zone_inner_range,
                                    uiState.zone2MaxBpm + 1,
                                    uiState.zone3MaxBpm,
                                ),
                            ),
                            Triple(
                                4,
                                stringResource(CoreUiR.string.hr_zone_n, 4),
                                stringResource(
                                    R.string.hr_zone_inner_range,
                                    uiState.zone3MaxBpm + 1,
                                    uiState.zone4MaxBpm,
                                ),
                            ),
                            Triple(
                                5,
                                stringResource(CoreUiR.string.hr_zone_n, 5),
                                stringResource(R.string.hr_zone_above_range, uiState.zone4MaxBpm),
                            ),
                        )

                    if (uiState.zoneTotals.isEmpty()) {
                        Text(
                            text = stringResource(app.readylytics.health.core.ui.R.string.dashboard_no_data),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        zoneDefs.forEachIndexed { index, (zone, label, range) ->
                            if (index > 0) {
                                Spacer(Modifier.height(MaterialTheme.spacing.small))
                            }
                            ZoneRow(
                                zoneNumber = zone,
                                label = label,
                                range = range,
                                total = uiState.zoneTotals[zone],
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))
        }
    }
}

@Composable
private fun HrStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.heightIn(min = 72.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .padding(MaterialTheme.spacing.smallMedium),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.hairline))
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ZoneRow(
    zoneNumber: Int,
    label: String,
    range: String,
    total: ZoneTotal?,
) {
    val zoneColorValue = zoneColor(zoneNumber)
    val minutes = total?.let { formatDurationToMinutes(it.durationMs) } ?: 0

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(12.dp)
                    .background(color = zoneColorValue, shape = CircleShape),
        )
        Spacer(Modifier.width(MaterialTheme.spacing.small))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(
                range,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (total != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${minutes}m",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    total.formattedPercent,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

