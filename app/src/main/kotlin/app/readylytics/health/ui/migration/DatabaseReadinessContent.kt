package app.readylytics.health.ui.migration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.domain.migration.DatabaseMigrationUiState
import app.readylytics.health.ui.theme.DatabaseReadinessTheme

@Composable
fun DatabaseReadinessContent(
    state: DatabaseMigrationUiState,
    onStartOrResume: () -> Unit,
    onSendDiagnostics: () -> Unit,
    readyContent: @Composable () -> Unit,
    keyRecoveryContent: @Composable () -> Unit,
) {
    when (val readiness = state.readiness) {
        DatabaseReadiness.Ready -> readyContent()
        DatabaseReadiness.KeyCorrupted -> DatabaseReadinessTheme { keyRecoveryContent() }
        else -> {
            if (readiness == DatabaseReadiness.EncryptionRequired || readiness is DatabaseReadiness.MigrationRequired) {
                LaunchedEffect(readiness) { onStartOrResume() }
            }
            DatabaseReadinessTheme {
                DatabaseMigrationScreen(
                    readiness = readiness,
                    progress = state.progress,
                    onRetry = onStartOrResume,
                    onSendDiagnostics = onSendDiagnostics,
                )
            }
        }
    }
}
