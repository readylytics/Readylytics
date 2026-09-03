package app.readylytics.health.ui.scaffold

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.readylytics.health.core.model.domain.githubissue.IssueReportRequest
import app.readylytics.health.core.ui.sync.SyncProgressScreen
import app.readylytics.health.crashreport.CrashReportFileExport
import app.readylytics.health.crashreport.GithubIssueIntentResult
import app.readylytics.health.crashreport.buildIssueReportIntent
import app.readylytics.health.crashreport.buildLogFileShareIntent
import app.readylytics.health.crashreport.buildOversizedFallbackIntent
import app.readylytics.health.feature.about.AboutScreen
import app.readylytics.health.feature.onboarding.SyncLogViewModel
import app.readylytics.health.feature.settings.SettingsRoute
import app.readylytics.health.feature.settings.SyncSettingsViewModel
import app.readylytics.health.feature.sleep.SleepRoute
import app.readylytics.health.feature.vitals.bloodpressure.BloodPressureDetailRoute
import app.readylytics.health.feature.vitals.bodyfat.BodyFatDetailRoute
import app.readylytics.health.feature.vitals.cardio.CardioFitnessDetailRoute
import app.readylytics.health.feature.vitals.heartrate.HeartRateDetailRoute
import app.readylytics.health.feature.vitals.overview.VitalsRoute
import app.readylytics.health.feature.vitals.steps.StepDetailRoute
import app.readylytics.health.feature.vitals.weight.WeightDetailRoute
import app.readylytics.health.feature.workouts.WorkoutDetailRoute
import app.readylytics.health.feature.workouts.WorkoutsRoute
import app.readylytics.health.ui.crashreport.CrashReportViewModel
import app.readylytics.health.ui.crashreport.OversizedReportDialog
import app.readylytics.health.ui.health.rememberExerciseRouteRequest
import app.readylytics.health.ui.logcat.LogcatCaptureViewModel
import app.readylytics.health.ui.navigation.AppDestination
import app.readylytics.health.ui.navigation.TabDestination
import app.readylytics.health.ui.sync.SyncViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun NavGraphBuilder.vitalsAndSleepDestinations(navController: NavHostController) {
    composable<TabDestination.Sleep> {
        SleepRoute()
    }
    composable<TabDestination.Vitals> {
        VitalsRoute(
            onNavigateToHrv = {},
            onNavigateToRhr = {},
            onNavigateToCardioFitness = { navController.navigate(AppDestination.CardioFitnessDetail) },
        )
    }
    composable<AppDestination.StepDetail> {
        StepDetailRoute(
            onBack = { navController.popBackStack() },
        )
    }
    composable<AppDestination.HeartRateDetail> {
        HeartRateDetailRoute(
            onBack = { navController.popBackStack() },
        )
    }
    composable<AppDestination.WeightDetail> {
        WeightDetailRoute(
            onBack = { navController.popBackStack() },
        )
    }
    composable<AppDestination.BodyFatDetail> {
        BodyFatDetailRoute(
            onBack = { navController.popBackStack() },
        )
    }
    composable<AppDestination.CardioFitnessDetail> {
        CardioFitnessDetailRoute(
            onBack = { navController.popBackStack() },
        )
    }
    composable<AppDestination.BloodPressureDetail> {
        BloodPressureDetailRoute(
            onBack = { navController.popBackStack() },
        )
    }
}

internal fun NavGraphBuilder.workoutsDestinations(navController: NavHostController) {
    composable<TabDestination.Workouts> {
        WorkoutsRoute { id ->
            navController.navigate(AppDestination.WorkoutDetail(id))
        }
    }
    composable<AppDestination.WorkoutDetail> { backStackEntry ->
        val detail: AppDestination.WorkoutDetail = backStackEntry.toRoute()
        val requestRoutePermission = rememberExerciseRouteRequest(detail.workoutId)
        WorkoutDetailRoute(
            workoutId = detail.workoutId,
            onBack = { navController.popBackStack() },
            onRequestRoutePermission = requestRoutePermission,
        )
    }
}

internal fun NavGraphBuilder.aboutAndSyncProgressDestinations(
    navController: NavHostController,
    onSetResyncScreenDismissed: () -> Unit,
) {
    composable<AppDestination.About> {
        AboutScreen(
            onDismiss = { navController.popBackStack() },
        )
    }

    composable<AppDestination.SyncProgress> {
        val syncViewModel: SyncViewModel = hiltViewModel()
        val recalcProgress by syncViewModel.recalcProgress.collectAsStateWithLifecycle()
        val historicalResyncState by syncViewModel.historicalResyncState.collectAsStateWithLifecycle()
        val logcatCaptureViewModel: LogcatCaptureViewModel = hiltViewModel()
        val syncLogViewModel: SyncLogViewModel = hiltViewModel()
        val logText by syncLogViewModel.logText.collectAsStateWithLifecycle()
        val coroutineScope = rememberCoroutineScope()
        val context = LocalContext.current

        var hasSeenProgress by rememberSaveable { mutableStateOf(recalcProgress != null) }
        LaunchedEffect(recalcProgress, historicalResyncState, hasSeenProgress) {
            when (
                shouldAutoDismissSyncProgress(
                    recalcProgress = recalcProgress,
                    isResyncing = historicalResyncState?.running,
                    hasSeenProgress = hasSeenProgress,
                )
            ) {
                SyncProgressDismissalState.MarkProgressSeen -> hasSeenProgress = true
                SyncProgressDismissalState.Dismiss -> navController.popBackStack()
                SyncProgressDismissalState.StayOpen -> Unit
            }
        }

        Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            SyncProgressScreen(
                progress = recalcProgress,
                onDownloadLogs = {
                    coroutineScope.launch {
                        val file = logcatCaptureViewModel.captureFile()
                        context.startActivity(buildLogFileShareIntent(context, file))
                    }
                },
                onContinueInBackground = {
                    onSetResyncScreenDismissed()
                    navController.popBackStack()
                },
                logText = logText,
                onLogsVisibilityChanged = { visible ->
                    if (visible) syncLogViewModel.startPolling() else syncLogViewModel.stopPolling()
                },
            )
        }
    }
}

/**
 * [resyncScreenDismissed] is taken as a [State] rather than a `Boolean` on purpose: NavHost
 * remembers its `builder` lambda, so this function runs once per graph. A plain `Boolean` would be
 * captured at graph-creation time and never update, permanently reporting "not dismissed".
 */
internal fun NavGraphBuilder.settingsDestinations(
    navController: NavHostController,
    resyncScreenDismissed: State<Boolean>,
    onResetResyncScreenDismissed: () -> Unit,
) {
    composable<TabDestination.Settings> {
        SettingsDestination(
            navController = navController,
            resyncScreenDismissed = resyncScreenDismissed,
            onResetResyncScreenDismissed = onResetResyncScreenDismissed,
        )
    }
}

@Composable
private fun SettingsDestination(
    navController: NavHostController,
    resyncScreenDismissed: State<Boolean>,
    onResetResyncScreenDismissed: () -> Unit,
) {
    val context = LocalContext.current
    val crashReportViewModel: CrashReportViewModel = hiltViewModel()
    val logcatCaptureViewModel: LogcatCaptureViewModel = hiltViewModel()
    val coroutineScope = rememberCoroutineScope()

    val syncSettingsViewModel: SyncSettingsViewModel = hiltViewModel()
    val syncSettingsState by syncSettingsViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(syncSettingsState.isResyncing) {
        when (
            resolveSyncProgressEntryAction(
                isResyncing = syncSettingsState.isResyncing,
                resyncScreenDismissed = resyncScreenDismissed.value,
            )
        ) {
            SyncProgressEntryAction.Open ->
                navController.navigate(AppDestination.SyncProgress) { launchSingleTop = true }
            SyncProgressEntryAction.ClearDismissal -> onResetResyncScreenDismissed()
            SyncProgressEntryAction.None -> Unit
        }
    }

    var pendingOversized by remember { mutableStateOf<PendingGithubSave?>(null) }
    var showOversizedDialog by remember { mutableStateOf(false) }

    val saveLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            handleSaveFileResult(uri, pendingOversized, context, coroutineScope, crashReportViewModel)
            pendingOversized = null
        }

    SettingsRoute(
        onNavigateToAbout = { navController.navigate(AppDestination.About) },
        onSendIssueReport = { request ->
            handleSendIssueReport(
                request = request,
                context = context,
                coroutineScope = coroutineScope,
                crashReportViewModel = crashReportViewModel,
                logcatCaptureViewModel = logcatCaptureViewModel,
                onOversized = { oversized ->
                    pendingOversized = PendingGithubSave(oversized, request.hasCrashReport)
                    showOversizedDialog = true
                },
            )
        },
    )

    OversizedReportDialog(
        isShown = showOversizedDialog,
        onDismiss = {
            showOversizedDialog = false
            pendingOversized = null
        },
        onSaveFile = { filename ->
            showOversizedDialog = false
            pendingOversized?.let { saveLauncher.launch(filename) }
        },
        suggestedFilename = pendingOversized?.oversized?.suggestedFilename ?: "",
    )
}

private fun handleSaveFileResult(
    uri: android.net.Uri?,
    pending: PendingGithubSave?,
    context: Context,
    coroutineScope: CoroutineScope,
    crashReportViewModel: CrashReportViewModel,
) {
    if (uri == null || pending == null) return
    coroutineScope.launch {
        val filename =
            CrashReportFileExport
                .writeReport(context, uri, pending.oversized.fullReport)
                .getOrElse { pending.oversized.suggestedFilename }
        context.startActivity(buildOversizedFallbackIntent(context, pending.oversized, filename))
        if (pending.consumeCrashReport) crashReportViewModel.consumeReport()
    }
}

private fun handleSendIssueReport(
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
                buildIssueReportIntent(context, request, crashText, crashFile, logcatText, logcatFile)
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
