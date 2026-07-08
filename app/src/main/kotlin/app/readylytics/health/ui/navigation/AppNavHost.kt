package app.readylytics.health.ui.navigation

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.readylytics.health.MainActivity
import app.readylytics.health.R
import app.readylytics.health.core.ui.common.resolveOrNull
import app.readylytics.health.crashreport.GithubIssueIntentResult
import app.readylytics.health.crashreport.buildIssueReportIntent
import app.readylytics.health.crashreport.buildOversizedFallbackIntent
import app.readylytics.health.domain.githubissue.GitHubIssueType
import app.readylytics.health.feature.onboarding.OnboardingRoute
import app.readylytics.health.feature.settings.IssueReportDialog
import app.readylytics.health.ui.crashreport.CrashReportViewModel
import app.readylytics.health.ui.logcat.LogcatCaptureViewModel
import app.readylytics.health.ui.scaffold.MainScaffold
import app.readylytics.health.ui.sync.SyncUiState
import app.readylytics.health.ui.sync.SyncViewModel
import kotlinx.coroutines.launch

@Composable
fun AppNavHost(
    viewModel: SyncViewModel = hiltViewModel(),
    navController: NavHostController = rememberNavController(),
    crashReportViewModel: CrashReportViewModel = hiltViewModel(),
    logcatCaptureViewModel: LogcatCaptureViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val userPrefs by viewModel.userPreferences.collectAsStateWithLifecycle(initialValue = null)
    val hasCrashReport by crashReportViewModel.hasReport.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    var pendingReportType by remember { mutableStateOf<GitHubIssueType?>(null) }
    var pendingOversized by remember { mutableStateOf<PendingGithubSave?>(null) }
    var showOversizedDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState, userPrefs) {
        if (userPrefs == null) return@LaunchedEffect
        val currentDest = navController.currentDestination
        when (uiState) {
            SyncUiState.NeedsPermissions -> {
                if (currentDest?.hasRoute<AppDestination.Onboarding>() != true) {
                    navController.navigate(AppDestination.Onboarding) {
                        popUpTo(AppDestination.MainShell) { inclusive = true }
                    }
                }
            }
            SyncUiState.Unavailable -> {
                if (currentDest?.hasRoute<AppDestination.Unavailable>() != true) {
                    navController.navigate(AppDestination.Unavailable) {
                        popUpTo(AppDestination.MainShell) { inclusive = true }
                    }
                }
            }
            SyncUiState.PermissionsGranted -> {
                if (currentDest?.hasRoute<AppDestination.MainShell>() != true) {
                    navController.navigate(AppDestination.MainShell) {
                        popUpTo(AppDestination.Onboarding) { inclusive = true }
                    }
                }
            }
            SyncUiState.SyncingCatchUp -> Unit // Gated
            else -> Unit
        }
    }

    // Handle issue reporting dialogs
    pendingReportType?.let { reportType ->
        IssueReportDialog(
            reportType = reportType,
            hasCrashReport = hasCrashReport,
            onDismiss = { pendingReportType = null },
            onSubmit = { request ->
                pendingReportType = null
                coroutineScope.launch {
                    val crashText = if (request.hasCrashReport) crashReportViewModel.reportText() else null
                    val crashFile = if (request.hasCrashReport) crashReportViewModel.reportFile() else null
                    val logcatText =
                        if (request.includeLogcat) {
                            logcatCaptureViewModel.capture(
                                request.logcatDurationMinutes,
                            )
                        } else {
                            null
                        }
                    val logcatFile = if (logcatText != null) logcatCaptureViewModel.captureFile() else null
                    when (
                        val result =
                            buildIssueReportIntent(
                                context,
                                request,
                                crashText,
                                crashFile,
                                logcatText,
                                logcatFile,
                            )
                    ) {
                        is GithubIssueIntentResult.Ready -> {
                            context.startActivity(result.intent)
                            if (request.hasCrashReport) crashReportViewModel.consumeReport()
                        }
                        is GithubIssueIntentResult.Oversized -> {
                            pendingOversized = PendingGithubSave(result, request.hasCrashReport)
                            showOversizedDialog = true
                        }
                    }
                }
            },
        )
    }

    if (showOversizedDialog) {
        val pending = pendingOversized
        if (pending != null) {
            AlertDialog(
                onDismissRequest = {
                    showOversizedDialog = false
                    pendingOversized = null
                },
                title = { Text(stringResource(R.string.crash_report_too_large_title)) },
                text = { Text(stringResource(R.string.crash_report_too_large_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        showOversizedDialog = false
                        pendingOversized = null
                        val filename = "readylytics_diagnostic_${System.currentTimeMillis()}.txt"
                        context.startActivity(buildOversizedFallbackIntent(context, pending.oversized, filename))
                        if (pending.consumeCrashReport) crashReportViewModel.consumeReport()
                    }) {
                        Text(stringResource(R.string.crash_report_too_large_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showOversizedDialog = false
                        pendingOversized = null
                    }) {
                        Text(stringResource(app.readylytics.health.core.ui.R.string.action_cancel))
                    }
                },
            )
        }
    }

    NavHost(
        navController = navController,
        startDestination = AppDestination.MainShell,
    ) {
        composable<AppDestination.MainShell> {
            MainScaffold()
        }

        composable<AppDestination.Onboarding> {
            val context = LocalContext.current
            val isSyncing = uiState is SyncUiState.SyncingCatchUp
            val syncError = (uiState as? SyncUiState.Error)?.message?.resolveOrNull()
            OnboardingRoute(
                userPreferencesFlow = viewModel.userPreferences,
                allPermissions = viewModel.allPermissions,
                requiredPermissions = viewModel.requiredPermissions,
                isSyncing = isSyncing,
                syncError = syncError,
                onRetrySync = { viewModel.onPermissionsGranted() },
                onSkipSync = { viewModel.skipSync() },
                onReportIssue = { pendingReportType = GitHubIssueType.BUG_REPORT },
                onPermissionsGranted = { viewModel.onPermissionsGranted() },
                onPermissionsDenied = { viewModel.onPermissionsDenied() },
                onRestartApp = {
                    val restartIntent =
                        Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                    context.startActivity(restartIntent)
                },
            )
        }

        composable<AppDestination.Unavailable> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.health_connect_unavailable_message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private data class PendingGithubSave(
    val oversized: GithubIssueIntentResult.Oversized,
    val consumeCrashReport: Boolean,
)
