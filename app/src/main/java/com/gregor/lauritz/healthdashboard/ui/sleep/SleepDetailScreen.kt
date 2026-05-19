package com.gregor.lauritz.healthdashboard.ui.sleep

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.hiltViewModel
import com.gregor.lauritz.healthdashboard.R
import com.gregor.lauritz.healthdashboard.ui.components.SleepStageBreakdownRow
import com.gregor.lauritz.healthdashboard.ui.components.SleepStagesChart
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun SleepDetailRoute(
    onBack: () -> Unit,
    viewModel: SleepDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SleepDetailScreen(
        uiState = uiState,
        onBack = onBack,
    )
}

@Composable
fun SleepDetailScreen(
    uiState: SleepDetailUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val timeRanges = listOf(
        stringResource(R.string.time_range_day),
        stringResource(R.string.time_range_week),
        stringResource(R.string.time_range_month),
        stringResource(R.string.time_range_year),
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_sleep)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxWidth(),
        ) {
            item {
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    divider = {},
                ) {
                    timeRanges.forEachIndexed { index, range ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = {
                                Text(
                                    range,
                                    color = if (selectedTabIndex == index) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.sleep_timeline_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            stringResource(R.string.sleep_timeline_learn_more),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                        .withZone(ZoneId.systemDefault())

                    val startTimeStr = if (uiState.session != null) {
                        timeFormatter.format(Instant.ofEpochMilli(uiState.session.startTime))
                    } else {
                        "--:-- --"
                    }

                    val endTimeStr = if (uiState.session != null) {
                        timeFormatter.format(Instant.ofEpochMilli(uiState.session.endTime))
                    } else {
                        "--:-- --"
                    }

                    Text(
                        "$startTimeStr – $endTimeStr",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val durationHours = if (uiState.session != null) {
                        val durationMs = uiState.session.endTime - uiState.session.startTime
                        durationMs / (1000f * 60 * 60)
                    } else {
                        0f
                    }

                    Text(
                        stringResource(R.string.sleep_duration_format, durationHours),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                SleepStagesChart(
                    session = uiState.session,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        stringResource(R.string.sleep_breakdown_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            item {
                val colorScheme = MaterialTheme.colorScheme
                val stages = listOf(
                    Triple(
                        stringResource(R.string.sleep_stage_awake),
                        uiState.session?.awakeMinutes ?: 0,
                        colorScheme.error,
                    ),
                    Triple(
                        stringResource(R.string.sleep_stage_rem),
                        uiState.session?.remSleepMinutes ?: 0,
                        colorScheme.tertiary,
                    ),
                    Triple(
                        stringResource(R.string.sleep_stage_light),
                        uiState.session?.lightSleepMinutes ?: 0,
                        colorScheme.tertiary.copy(alpha = 0.6f),
                    ),
                    Triple(
                        stringResource(R.string.sleep_stage_deep),
                        uiState.session?.deepSleepMinutes ?: 0,
                        colorScheme.primary,
                    ),
                )

                Column {
                    stages.forEach { (stageName, minutes, color) ->
                        SleepStageBreakdownRow(
                            stageName = stageName,
                            durationMinutes = minutes,
                            color = color,
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
