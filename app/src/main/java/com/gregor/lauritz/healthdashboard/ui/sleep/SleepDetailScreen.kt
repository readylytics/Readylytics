package com.gregor.lauritz.healthdashboard.ui.sleep

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.R
import com.gregor.lauritz.healthdashboard.ui.components.SleepArchitectureBar
import com.gregor.lauritz.healthdashboard.ui.components.SleepStagesChart
import com.gregor.lauritz.healthdashboard.ui.components.TrendCard
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepDetailScreen(
    uiState: SleepDetailUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sleep_timeline_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier =
                Modifier
                    .padding(paddingValues)
                    .fillMaxWidth(),
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Spacer(modifier = Modifier.height(12.dp))

                    val timeFormatter =
                        remember {
                            DateTimeFormatter
                                .ofLocalizedTime(FormatStyle.SHORT)
                                .withZone(ZoneId.systemDefault())
                        }

                    val startTimeStr =
                        if (uiState.session != null) {
                            timeFormatter.format(Instant.ofEpochMilli(uiState.session.startTime))
                        } else {
                            "--:-- --"
                        }

                    val endTimeStr =
                        if (uiState.session != null) {
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

                    val durationText =
                        if (uiState.session != null) {
                            val durationMs = uiState.session.endTime - uiState.session.startTime
                            val totalMinutes = (durationMs / 1000 / 60).toInt()
                            val hours = totalMinutes / 60
                            val minutes = totalMinutes % 60
                            stringResource(R.string.sleep_duration_format, hours, minutes)
                        } else {
                            stringResource(R.string.sleep_duration_unknown)
                        }

                    Text(
                        durationText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                TrendCard(
                    title = stringResource(R.string.sleep_hypnogram_title),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    SleepStagesChart(
                        session = uiState.session,
                        stageTimeline = uiState.stageTimeline,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
            }

            item {
                SleepArchitectureBar(
                    session = uiState.session,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
