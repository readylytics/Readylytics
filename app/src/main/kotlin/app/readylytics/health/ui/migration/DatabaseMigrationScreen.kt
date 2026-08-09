package app.readylytics.health.ui.migration

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.R
import app.readylytics.health.domain.migration.DatabaseMigrationProgress
import app.readylytics.health.domain.migration.DatabaseReadiness
import java.text.NumberFormat

@Composable
fun DatabaseMigrationScreen(
    readiness: DatabaseReadiness,
    progress: DatabaseMigrationProgress?,
    onRetry: () -> Unit,
    onSendDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { contentPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
                shape = MaterialTheme.shapes.large,
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.database_migration_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = guidanceText(readiness),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    MigrationProgress(progress)
                    if (readiness is DatabaseReadiness.InsufficientSpace ||
                        readiness is DatabaseReadiness.Failed
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onRetry) {
                                Text(stringResource(R.string.database_migration_retry))
                            }
                            OutlinedButton(onClick = onSendDiagnostics) {
                                Text(stringResource(R.string.database_migration_send_diagnostics))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun guidanceText(readiness: DatabaseReadiness): String =
    when (readiness) {
        DatabaseReadiness.Ready,
        is DatabaseReadiness.MigrationRequired,
        -> stringResource(R.string.database_migration_description)

        is DatabaseReadiness.InsufficientSpace -> {
            val bytesToFree = (readiness.requiredBytes - readiness.availableBytes).coerceAtLeast(0L)
            stringResource(
                R.string.database_migration_space_error,
                Formatter.formatFileSize(LocalContext.current, bytesToFree),
            )
        }

        is DatabaseReadiness.Failed -> stringResource(R.string.database_migration_failed)

        DatabaseReadiness.KeyCorrupted -> stringResource(R.string.database_migration_failed)
    }

@Composable
private fun MigrationProgress(progress: DatabaseMigrationProgress?) {
    if (progress == null || progress.totalRows <= 0L) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        return
    }

    val copiedRows = progress.copiedRows.coerceIn(0L, progress.totalRows)
    val fraction = copiedRows.toFloat() / progress.totalRows.toFloat()
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth(),
    )
    val formatter = NumberFormat.getIntegerInstance()
    Text(
        text =
            pluralStringResource(
                R.plurals.database_migration_progress,
                progress.totalRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                formatter.format(copiedRows),
                formatter.format(progress.totalRows),
            ),
        style = MaterialTheme.typography.bodyMedium,
    )
}
