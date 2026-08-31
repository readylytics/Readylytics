package app.readylytics.health.ui.navigation

import android.content.Context
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.readylytics.health.MainActivity
import app.readylytics.health.R
import app.readylytics.health.core.model.domain.githubissue.GitHubIssueType
import app.readylytics.health.core.model.domain.githubissue.IssueReportRequest
import app.readylytics.health.core.model.domain.sync.RecalcProgress
import app.readylytics.health.core.ui.common.resolveOrNull
import app.readylytics.health.crashreport.GithubIssueIntentResult
import app.readylytics.health.crashreport.buildIssueReportIntent
import app.readylytics.health.crashreport.buildLogFileShareIntent
import app.readylytics.health.crashreport.buildOversizedFallbackIntent
import app.readylytics.health.feature.onboarding.OnboardingRoute
import app.readylytics.health.feature.settings.IssueReportDialog
import app.readylytics.health.ui.crashreport.CrashReportViewModel
import app.readylytics.health.ui.logcat.LogcatCaptureViewModel
import app.readylytics.health.ui.scaffold.MainScaffold
import app.readylytics.health.ui.sync.SyncUiState
import app.readylytics.health.ui.sync.SyncViewModel
import kotlinx.coroutines.CoroutineScope
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
    val recalcProgress by viewModel.recalcProgress.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    var syncBackgrounded by rememberSaveable { mutableStateOf(false) }
    var pendingReportType by remember { mutableStateOf<GitHubIssueType?>(null) }
    var pendingOversized by remember { mutableStateOf<PendingGithubSave?>(null) }
    var showOversizedDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState, userPrefs) {
        if (userPrefs == null) return@LaunchedEffect
        syncNavigationState(navController, uiState, syncBackgrounded)
    }

    IssueReportSection(
        args =
            IssueReportSectionArgs(
                pendingReportType = pendingReportType,
                hasCrashReport = hasCrashReport,
                context = context,
                coroutineScope = coroutineScope,
                crashReportViewModel = crashReportViewModel,
                logcatCaptureViewModel = logcatCaptureViewModel,
                onDismissReport = { pendingReportType = null },
                onOversized = { oversized, hasCrash ->
                    pendingOversized = PendingGithubSave(oversized, hasCrash)
                    showOversizedDialog = true
                },
            ),
    )

    if (showOversizedDialog) {
        OversizedReportDialog(
            pending = pendingOversized,
            context = context,
            onDismiss = {
                showOversizedDialog = false
                pendingOversized = null
            },
            onConsumeCrashReport = { crashReportViewModel.consumeReport() },
        )
    }

    AppNavGraph(
        navController = navController,
        onboardingArgs =
            OnboardingDestinationArgs(
                viewModel = viewModel,
                uiState = uiState,
                recalcProgress = recalcProgress,
                coroutineScope = coroutineScope,
                logcatCaptureViewModel = logcatCaptureViewModel,
                onSetSyncBackgrounded = { syncBackgrounded = true },
                onReportIssue = { pendingReportType = GitHubIssueType.BUG_REPORT },
            ),
    )
}

@Composable
private fun AppNavGraph(
    navController: NavHostController,
    onboardingArgs: OnboardingDestinationArgs,
) {
    NavHost(
        navController = navController,
        startDestination = AppDestination.MainShell,
    ) {
        composable<AppDestination.MainShell> {
            MainScaffold()
        }

        addOnboardingDestination(navController, onboardingArgs)

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

private class IssueReportSectionArgs(
    val pendingReportType: GitHubIssueType?,
    val hasCrashReport: Boolean,
    val context: Context,
    val coroutineScope: CoroutineScope,
    val crashReportViewModel: CrashReportViewModel,
    val logcatCaptureViewModel: LogcatCaptureViewModel,
    val onDismissReport: () -> Unit,
    val onOversized: (GithubIssueIntentResult.Oversized, Boolean) -> Unit,
)

@Composable
private fun IssueReportSection(args: IssueReportSectionArgs) {
    args.pendingReportType?.let { reportType ->
        IssueReportDialog(
            reportType = reportType,
            hasCrashReport = args.hasCrashReport,
            onDismiss = args.onDismissReport,
            onSubmit = { request ->
                args.onDismissReport()
                handleSubmitIssueReport(
                    request = request,
                    context = args.context,
                    coroutineScope = args.coroutineScope,
                    crashReportViewModel = args.crashReportViewModel,
                    logcatCaptureViewModel = args.logcatCaptureViewModel,
                    onOversized = { oversized ->
                        args.onOversized(oversized, request.hasCrashReport)
                    },
                )
            },
        )
    }
}

private fun handleSubmitIssueReport(
    request: IssueReportRequest,
    context: Context,
    coroutineScope: CoroutineScope,
    crashReportViewModel: CrashReportViewModel,
    logcatCaptureViewModel: LogcatCaptureViewModel,
    onOversized: (GithubIssueIntentResult.Oversized) -> Unit,
) {
    coroutineScope.launch {
        val crashText = if (request.hasCrashReport) crashReportViewModel.reportText() else null
        val crashFile = if (request.hasCrashReport) crashReportViewModel.reportFile() else null
        val logcatText =
            if (request.includeLogcat) {
                logcatCaptureViewModel.capture(request.logcatDurationMinutes)
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
                onOversized(result)
            }
        }
    }
}

private fun syncNavigationState(
    navController: NavHostController,
    uiState: SyncUiState,
    syncBackgrounded: Boolean,
) {
    val currentDest = navController.currentDestination
    when (uiState) {
        SyncUiState.NeedsPermissions -> {
            if (currentDest?.hasRoute<AppDestination.Onboarding>() != true) {
                navController.navigate(AppDestination.Onboarding) {
                    popUpTo(AppDestination.MainShell) {
                        inclusive = true
                        saveState = true
                    }
                    restoreState = true
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
                    popUpTo(AppDestination.Onboarding) {
                        inclusive = true
                        saveState = true
                    }
                    restoreState = true
                }
            }
        }
        SyncUiState.SyncingCatchUp -> {
            if (syncBackgrounded && currentDest?.hasRoute<AppDestination.MainShell>() != true) {
                navController.navigate(AppDestination.MainShell) {
                    popUpTo(AppDestination.Onboarding) {
                        inclusive = true
                        saveState = true
                    }
                    restoreState = true
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun OversizedReportDialog(
    pending: PendingGithubSave?,
    context: Context,
    onDismiss: () -> Unit,
    onConsumeCrashReport: () -> Unit,
) {
    if (pending != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.crash_report_too_large_title)) },
            text = { Text(stringResource(R.string.crash_report_too_large_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    val filename = "readylytics_diagnostic_${System.currentTimeMillis()}.txt"
                    context.startActivity(buildOversizedFallbackIntent(context, pending.oversized, filename))
                    if (pending.consumeCrashReport) onConsumeCrashReport()
                }) {
                    Text(stringResource(R.string.crash_report_too_large_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(app.readylytics.health.core.ui.R.string.action_cancel))
                }
            },
        )
    }
}

private class OnboardingDestinationArgs(
    val viewModel: SyncViewModel,
    val uiState: SyncUiState,
    val recalcProgress: RecalcProgress?,
    val coroutineScope: CoroutineScope,
    val logcatCaptureViewModel: LogcatCaptureViewModel,
    val onSetSyncBackgrounded: () -> Unit,
    val onReportIssue: () -> Unit,
)

private fun NavGraphBuilder.addOnboardingDestination(
    navController: NavHostController,
    args: OnboardingDestinationArgs,
) {
    composable<AppDestination.Onboarding> {
        val context = LocalContext.current
        val isSyncing = args.uiState is SyncUiState.SyncingCatchUp
        val isSyncError = args.uiState is SyncUiState.Error
        val syncError = (args.uiState as? SyncUiState.Error)?.message?.resolveOrNull()
        val syncStatus =
            app.readylytics.health.feature.onboarding.OnboardingSyncStatus(
                isSyncing = isSyncing,
                isSyncError = isSyncError,
                syncError = syncError,
                recalcProgress = args.recalcProgress,
                onRetrySync = { args.viewModel.onPermissionsGranted() },
                onSkipSync = { args.viewModel.skipSync() },
                onReportIssue = args.onReportIssue,
                onDownloadLogs = {
                    args.coroutineScope.launch {
                        val file = args.logcatCaptureViewModel.captureFile()
                        context.startActivity(buildLogFileShareIntent(context, file))
                    }
                },
                onContinueInBackground = {
                    args.onSetSyncBackgrounded()
                    navController.navigate(AppDestination.MainShell) {
                        popUpTo(AppDestination.Onboarding) {
                            inclusive = true
                            saveState = true
                        }
                        restoreState = true
                    }
                },
            )
        OnboardingRoute(
            userPreferencesFlow = args.viewModel.userPreferences,
            allPermissions = args.viewModel.allPermissions,
            requiredPermissions = args.viewModel.requiredPermissions,
            optionalPermissions = args.viewModel.optionalPermissions,
            onPermissionsGranted = { args.viewModel.onPermissionsGranted() },
            onPermissionsDenied = { args.viewModel.onPermissionsDenied() },
            onRestartApp = {
                val restartIntent =
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                context.startActivity(restartIntent)
            },
            syncStatus = syncStatus,
        )
    }
}

private data class PendingGithubSave(
    val oversized: GithubIssueIntentResult.Oversized,
    val consumeCrashReport: Boolean,
)
