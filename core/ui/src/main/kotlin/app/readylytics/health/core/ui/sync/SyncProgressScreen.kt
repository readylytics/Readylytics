package app.readylytics.health.core.ui.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.R
import app.readylytics.health.domain.sync.RecalcProgress

@Composable
fun SyncProgressScreen(
    progress: RecalcProgress?,
    onDownloadLogs: () -> Unit,
    onContinueInBackground: () -> Unit,
    modifier: Modifier = Modifier,
    logViewModel: SyncLogViewModel = hiltViewModel(),
) {
    val logText by logViewModel.logText.collectAsStateWithLifecycle()
    var showLogs by remember { mutableStateOf(false) }

    LaunchedEffect(showLogs) {
        if (showLogs) {
            logViewModel.startPolling()
        } else {
            logViewModel.stopPolling()
        }
    }

    DisposableEffect(logViewModel) {
        onDispose {
            logViewModel.stopPolling()
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.pageSectionGapLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Scrollable content area — grows to fill remaining space above buttons
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (progress != null && progress.total > 0) {
                val percentage = progress.current.toFloat() / progress.total.toFloat()
                Text(
                    text = stringResource(R.string.sync_progress_fetching_data, progress.current, progress.total),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))
                LinearProgressIndicator(
                    progress = { percentage },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                CircularProgressIndicator()
                Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))
                Text(
                    text = stringResource(R.string.sync_progress_finishing_setup),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))

            TextButton(onClick = { showLogs = !showLogs }) {
                Text(
                    text =
                        if (showLogs) {
                            stringResource(R.string.sync_progress_hide_logs)
                        } else {
                            stringResource(R.string.sync_progress_view_logs)
                        },
                )
            }

            AnimatedVisibility(visible = showLogs) {
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    shape = MaterialTheme.shapes.large,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(vertical = MaterialTheme.spacing.small),
                ) {
                    val listState = rememberLazyListState()
                    val logLines =
                        remember(logText) {
                            logText?.split("\n")?.filter { it.isNotBlank() }?.takeLast(40) ?: emptyList()
                        }

                    LaunchedEffect(logLines) {
                        if (logLines.isNotEmpty()) {
                            listState.animateScrollToItem(logLines.size - 1)
                        }
                    }

                    if (logLines.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.sync_progress_logs_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(MaterialTheme.spacing.small),
                        ) {
                            items(logLines) { line ->
                                Text(
                                    text = line,
                                    style =
                                        MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Buttons pinned to the bottom
        Spacer(Modifier.height(MaterialTheme.spacing.small))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            OutlinedButton(
                onClick = onDownloadLogs,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.sync_progress_download_logs))
            }

            Button(
                onClick = onContinueInBackground,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.sync_progress_continue_in_background))
            }
        }
    }
}
