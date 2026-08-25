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
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.sync.RecalcProgress
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import app.readylytics.health.core.model.domain.sync.fraction
import app.readylytics.health.core.ui.R
import app.readylytics.health.core.ui.components.M3MetricBar

@Composable
fun SyncProgressScreen(
    progress: RecalcProgress?,
    onDownloadLogs: () -> Unit,
    onContinueInBackground: () -> Unit,
    logText: String?,
    onLogsVisibilityChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showLogs by remember { mutableStateOf(false) }

    LaunchedEffect(showLogs) {
        onLogsVisibilityChanged(showLogs)
    }

    DisposableEffect(Unit) {
        onDispose {
            onLogsVisibilityChanged(false)
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.pageSectionGapLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SyncProgressContentArea(
            progress = progress,
            showLogs = showLogs,
            onToggleLogs = { showLogs = !showLogs },
            logText = logText,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        SyncProgressActionButtons(
            showLogs = showLogs,
            onDownloadLogs = onDownloadLogs,
            onContinueInBackground = onContinueInBackground,
        )
    }
}

@Composable
private fun SyncProgressContentArea(
    progress: RecalcProgress?,
    showLogs: Boolean,
    onToggleLogs: () -> Unit,
    logText: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = formatProgressPhaseText(progress),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))
        M3MetricBar(
            progressFraction = progress?.fraction(),
            activeColor = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.secondaryContainer,
            barHeight = MaterialTheme.dimens.syncProgressBarThickness,
            markerDiameter = MaterialTheme.dimens.syncProgressBarThickness,
            showMarker = true,
            animateProgress = false,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))

        TextButton(onClick = onToggleLogs) {
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
            SyncLogsCard(logText = logText)
        }
    }
}

@Composable
private fun formatProgressPhaseText(progress: RecalcProgress?): String =
    if (progress == null) {
        stringResource(R.string.sync_progress_finishing_setup)
    } else {
        when (progress.phase) {
            ResyncPhase.INGEST ->
                if (progress.total > 0) {
                    stringResource(
                        R.string.sync_progress_phase_ingest,
                        progress.current,
                        progress.total,
                    )
                } else {
                    stringResource(
                        R.string.sync_progress_phase_ingest_indeterminate,
                        progress.current,
                    )
                }
            ResyncPhase.PRUNE -> stringResource(R.string.sync_progress_phase_prune)
            ResyncPhase.RECONCILE -> stringResource(R.string.sync_progress_phase_reconcile)
            ResyncPhase.RECOMPUTE ->
                stringResource(R.string.sync_progress_fetching_data, progress.current, progress.total)
        }
    }

@Composable
private fun SyncLogsCard(logText: String?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
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
                modifier = Modifier.fillMaxSize().padding(MaterialTheme.spacing.smallMedium),
            ) {
                items(logLines) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncProgressActionButtons(
    showLogs: Boolean,
    onDownloadLogs: () -> Unit,
    onContinueInBackground: () -> Unit,
) {
    if (showLogs) {
        OutlinedButton(
            onClick = onDownloadLogs,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.sync_progress_download_logs))
        }
        Spacer(Modifier.height(MaterialTheme.spacing.small))
    }

    Button(
        onClick = onContinueInBackground,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.sync_progress_continue_in_background))
    }
}
