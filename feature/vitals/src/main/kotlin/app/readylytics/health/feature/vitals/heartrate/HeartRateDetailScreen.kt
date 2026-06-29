package app.readylytics.health.feature.vitals.heartrate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.core.designsystem.LocalExtendedColors
import app.readylytics.health.core.ui.common.MetricCardSkeleton
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.feature.vitals.R
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
        onPreviousDay = viewModel::onPreviousDay,
        onNextDay = viewModel::onNextDay,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeartRateDetailScreen(
    uiState: HeartRateDetailUiState,
    onBack: () -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
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
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                            .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HrStatCard(stringResource(CoreUiR.string.label_min), "${uiState.minBpm} bpm", Modifier.weight(1f))
                    HrStatCard(stringResource(CoreUiR.string.label_max), "${uiState.maxBpm} bpm", Modifier.weight(1f))
                    HrStatCard(stringResource(CoreUiR.string.label_avg), "${uiState.avgBpm} bpm", Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(12.dp))

            SectionHeader(title = stringResource(CoreUiR.string.label_timeline))
            Spacer(Modifier.height(4.dp))

            if (uiState.isLoading) {
                SkeletonCard(
                    height = 300.dp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
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
                                .padding(12.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            SectionHeader(title = stringResource(CoreUiR.string.label_zone_breakdown))
            Spacer(Modifier.height(4.dp))

            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val zoneDefs =
                        listOf(
                            Triple(
                                0,
                                stringResource(R.string.hr_zone_n, 0),
                                stringResource(R.string.hr_zone_0_range, uiState.zone1MinBpm),
                            ),
                            Triple(
                                1,
                                stringResource(R.string.hr_zone_n, 1),
                                stringResource(
                                    R.string.hr_zone_inner_range,
                                    uiState.zone1MinBpm,
                                    uiState.zone1MaxBpm,
                                ),
                            ),
                            Triple(
                                2,
                                stringResource(R.string.hr_zone_n, 2),
                                stringResource(
                                    R.string.hr_zone_inner_range,
                                    uiState.zone1MaxBpm + 1,
                                    uiState.zone2MaxBpm,
                                ),
                            ),
                            Triple(
                                3,
                                stringResource(R.string.hr_zone_n, 3),
                                stringResource(
                                    R.string.hr_zone_inner_range,
                                    uiState.zone2MaxBpm + 1,
                                    uiState.zone3MaxBpm,
                                ),
                            ),
                            Triple(
                                4,
                                stringResource(R.string.hr_zone_n, 4),
                                stringResource(
                                    R.string.hr_zone_inner_range,
                                    uiState.zone3MaxBpm + 1,
                                    uiState.zone4MaxBpm,
                                ),
                            ),
                            Triple(
                                5,
                                stringResource(R.string.hr_zone_n, 5),
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
                                Spacer(Modifier.height(8.dp))
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

            Spacer(Modifier.height(24.dp))
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
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
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
    val zoneColor = zoneColor(zoneNumber)
    // Allow-listed: time calculation (duration to minutes), not metric rounding
    val minutes = total?.let { (it.durationMs / 60_000L).toInt() } ?: 0

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(12.dp)
                    .background(color = zoneColor, shape = CircleShape),
        )
        Spacer(Modifier.width(8.dp))
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

@Composable
private fun zoneColor(zone: Int): Color {
    val cs = MaterialTheme.colorScheme
    val ext = LocalExtendedColors.current
    return when (zone) {
        0 -> cs.surfaceVariant
        1 -> cs.secondaryContainer
        2 -> cs.primaryContainer
        3 -> cs.tertiaryContainer
        4 -> ext.warningContainer
        else -> cs.errorContainer
    }
}
