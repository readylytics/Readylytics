package app.readylytics.health.feature.settings

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.readylytics.health.core.model.domain.githubissue.GitHubIssueType
import app.readylytics.health.core.model.domain.githubissue.IssueReportRequest
import app.readylytics.health.feature.settings.LocalBackupViewModel.SideEffect
import app.readylytics.health.feature.settings.R
import app.readylytics.health.feature.settings.category.BackupRestoreCategoryScreen
import app.readylytics.health.feature.settings.category.DataSourcesSyncCategoryScreen
import app.readylytics.health.feature.settings.category.DisplayCategoryScreen
import app.readylytics.health.feature.settings.category.PhysiologyProfileCategoryScreen
import app.readylytics.health.feature.settings.category.SleepCategoryScreen
import app.readylytics.health.feature.settings.category.SupportAboutCategoryScreen
import app.readylytics.health.feature.settings.category.TrainingCategoryScreen
import app.readylytics.health.feature.settings.category.VitalsCategoryScreen
import app.readylytics.health.feature.settings.common.resyncGateEnabled
import app.readylytics.health.feature.settings.nav.SettingsCategoryId
import app.readylytics.health.feature.settings.nav.SettingsDestination
import app.readylytics.health.feature.settings.nav.SettingsHomeScreen
import com.google.android.gms.oss.licenses.v2.OssLicensesMenuActivity
import kotlinx.coroutines.flow.collectLatest
import app.readylytics.health.core.ui.R as CoreUiR

private const val TRANSITION_DURATION_MS = 300
private const val PREDICTIVE_POP_SCALE = 0.9f

private val settingsEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(animationSpec = tween(TRANSITION_DURATION_MS)) +
        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(TRANSITION_DURATION_MS))
}

private val settingsExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(animationSpec = tween(TRANSITION_DURATION_MS)) +
        slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(TRANSITION_DURATION_MS))
}

private val settingsPopEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(animationSpec = tween(TRANSITION_DURATION_MS)) +
        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(TRANSITION_DURATION_MS))
}

private val settingsPopExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(animationSpec = tween(TRANSITION_DURATION_MS)) +
        slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(TRANSITION_DURATION_MS))
}

internal val settingsPredictivePopEnterTransition:
    AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> EnterTransition =
    { _: Int ->
        scaleIn(
            initialScale = PREDICTIVE_POP_SCALE,
            transformOrigin = TransformOrigin.Center,
            animationSpec = tween(TRANSITION_DURATION_MS),
        ) + fadeIn(animationSpec = tween(TRANSITION_DURATION_MS))
    }

internal val settingsPredictivePopExitTransition:
    AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> ExitTransition =
    { _: Int ->
        scaleOut(
            targetScale = PREDICTIVE_POP_SCALE,
            transformOrigin = TransformOrigin.Center,
            animationSpec = tween(TRANSITION_DURATION_MS),
        ) + fadeOut(animationSpec = tween(TRANSITION_DURATION_MS))
    }

private fun openOssLicenses(
    context: Context,
    licensesTitle: String,
) {
    OssLicensesMenuActivity.setActivityTitle(licensesTitle)
    context.startActivity(
        Intent(context, OssLicensesMenuActivity::class.java),
    )
}

private fun openPrivacyPolicy(context: Context) {
    val url = context.getString(R.string.privacy_policy_url)
    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
}

private fun openSourceCode(context: Context) {
    val url = context.getString(R.string.source_code_url)
    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
}

@Composable
fun SettingsRoute(
    thresholdViewModel: ThresholdSettingsViewModel = hiltViewModel(),
    sleepViewModel: SleepSettingsViewModel = hiltViewModel(),
    physiologyViewModel: PhysiologySettingsViewModel = hiltViewModel(),
    heartRateViewModel: HeartRateZonesViewModel = hiltViewModel(),
    localBackupViewModel: LocalBackupViewModel = hiltViewModel(),
    syncViewModel: SyncSettingsViewModel = hiltViewModel(),
    uiViewModel: UISettingsViewModel = hiltViewModel(),
    dashboardCardsViewModel: DashboardCardsSettingsViewModel = hiltViewModel(),
    crashReportViewModel: CrashReportSettingsViewModel = hiltViewModel(),
    onNavigateToAbout: () -> Unit = {},
    onSendIssueReport: (IssueReportRequest) -> Unit = {},
) {
    val thresholdState by thresholdViewModel.consolidatedState.collectAsStateWithLifecycle()
    val sleepState by sleepViewModel.uiState.collectAsStateWithLifecycle()
    val physiologyState by physiologyViewModel.uiState.collectAsStateWithLifecycle()
    val heartRateState by heartRateViewModel.uiState.collectAsStateWithLifecycle()
    val localBackupState by localBackupViewModel.uiState.collectAsStateWithLifecycle()
    val syncState by syncViewModel.uiState.collectAsStateWithLifecycle()
    val uiState by uiViewModel.uiState.collectAsStateWithLifecycle()
    val dashboardCardsState by dashboardCardsViewModel.uiState.collectAsStateWithLifecycle()
    val hasCrashReport by crashReportViewModel.hasCrashReport.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val licensesTitle = stringResource(R.string.settings_item_licenses_title)

    LaunchedEffect(localBackupViewModel.sideEffect) {
        localBackupViewModel.sideEffect.collectLatest { effect ->
            when (effect) {
                SideEffect.RestartApp -> {
                    val restartIntent =
                        context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                    if (restartIntent != null) {
                        context.startActivity(restartIntent)
                    }
                }
                is SideEffect.TakePersistableUriPermission -> {
                    val uri = effect.uri.toUri()
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
        }
    }

    SettingsScreen(
        thresholdState = thresholdState,
        sleepState = sleepState,
        physiologyState = physiologyState,
        heartRateState = heartRateState,
        localBackupState = localBackupState,
        syncState = syncState,
        uiState = uiState,
        dashboardCardsState = dashboardCardsState,
        onThresholdEvent = thresholdViewModel::onEvent,
        onSleepEvent = sleepViewModel::onEvent,
        onPhysiologyEvent = physiologyViewModel::onEvent,
        onHeartRateEvent = heartRateViewModel::onEvent,
        onLocalBackupEvent = localBackupViewModel::onEvent,
        onSyncEvent = syncViewModel::onEvent,
        onUIEvent = uiViewModel::onEvent,
        onDashboardCardsEvent = dashboardCardsViewModel::onEvent,
        onNavigateToAbout = onNavigateToAbout,
        onNavigateToLicenses = {
            openOssLicenses(context, licensesTitle)
        },
        onOpenPrivacyPolicy = {
            openPrivacyPolicy(context)
        },
        onOpenSourceCode = {
            openSourceCode(context)
        },
        hasCrashReport = hasCrashReport,
        onSendIssueReport = { request ->
            onSendIssueReport(request)
            if (request.hasCrashReport) crashReportViewModel.markSent()
        },
    )
}

@Composable
fun SettingsScreen(
    thresholdState: ThresholdSettingsState,
    sleepState: SleepSettingsState,
    physiologyState: PhysiologySettingsState,
    heartRateState: HeartRateZonesState,
    localBackupState: LocalBackupState,
    syncState: SyncSettingsState,
    uiState: UIState,
    dashboardCardsState: DashboardCardsSettingsState,
    onThresholdEvent: (SettingsEvent) -> Unit,
    onSleepEvent: (SettingsEvent) -> Unit,
    onPhysiologyEvent: (SettingsEvent) -> Unit,
    onHeartRateEvent: (SettingsEvent) -> Unit,
    onLocalBackupEvent: (SettingsEvent) -> Unit,
    onSyncEvent: (SettingsEvent) -> Unit,
    onUIEvent: (SettingsEvent) -> Unit,
    onDashboardCardsEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToAbout: () -> Unit = {},
    onNavigateToLicenses: () -> Unit = {},
    onOpenPrivacyPolicy: () -> Unit = {},
    onOpenSourceCode: () -> Unit = {},
    hasCrashReport: Boolean = false,
    onSendIssueReport: (IssueReportRequest) -> Unit = {},
) {
    val states =
        SettingsStates(
            thresholdState = thresholdState,
            sleepState = sleepState,
            physiologyState = physiologyState,
            heartRateState = heartRateState,
            localBackupState = localBackupState,
            syncState = syncState,
            uiState = uiState,
            dashboardCardsState = dashboardCardsState,
            hasCrashReport = hasCrashReport,
        )
    val intents =
        SettingsIntents(
            onThresholdEvent = onThresholdEvent,
            onSleepEvent = onSleepEvent,
            onPhysiologyEvent = onPhysiologyEvent,
            onHeartRateEvent = onHeartRateEvent,
            onLocalBackupEvent = onLocalBackupEvent,
            onSyncEvent = onSyncEvent,
            onUIEvent = onUIEvent,
            onDashboardCardsEvent = onDashboardCardsEvent,
            onNavigateToAbout = onNavigateToAbout,
            onNavigateToLicenses = onNavigateToLicenses,
            onOpenPrivacyPolicy = onOpenPrivacyPolicy,
            onOpenSourceCode = onOpenSourceCode,
            onSendIssueReport = onSendIssueReport,
        )

    SettingsScreenContent(
        states = states,
        intents = intents,
        modifier = modifier,
    )
}

@Composable
private fun SettingsScreenContent(
    states: SettingsStates,
    intents: SettingsIntents,
    modifier: Modifier = Modifier,
) {
    var pendingReportType by remember { mutableStateOf<GitHubIssueType?>(null) }
    val controlsEnabled = resyncGateEnabled(states.syncState.isResyncing)
    val navController = rememberNavController()
    var searchQuery by rememberSaveable { mutableStateOf("") }

    RestoreConfirmDialog(states = states, intents = intents)
    IssueReportDialogHandler(
        pendingReportType = pendingReportType,
        hasCrashReport = states.hasCrashReport,
        onDismiss = { pendingReportType = null },
        onSendIssueReport = intents.onSendIssueReport,
    )

    NavHost(
        navController = navController,
        startDestination = SettingsDestination.Home,
        modifier = modifier.fillMaxSize(),
        enterTransition = settingsEnterTransition,
        exitTransition = settingsExitTransition,
        popEnterTransition = settingsPopEnterTransition,
        popExitTransition = settingsPopExitTransition,
        predictivePopEnterTransition = settingsPredictivePopEnterTransition,
        predictivePopExitTransition = settingsPredictivePopExitTransition,
    ) {
        composable<SettingsDestination.Home> {
            SettingsHomeScreen(
                searchQuery = searchQuery,
                onSearchQueryChanged = { searchQuery = it },
                onCategorySelected = { navController.navigate(SettingsDestination.Category(it)) },
                onSearchResultSelected = {
                    navController.navigate(SettingsDestination.Category(it.categoryId, highlightItemId = it.id))
                },
            )
        }
        composable<SettingsDestination.Category> { backStackEntry ->
            val destination: SettingsDestination.Category = backStackEntry.toRoute()
            CategoryScreenHost(
                categoryId = destination.id,
                highlightItemId = destination.highlightItemId,
                states = states,
                intents = intents,
                controlsEnabled = controlsEnabled,
                onReportTypeSelected = { pendingReportType = it },
            )
        }
    }
}

@Composable
private fun CategoryScreenHost(
    categoryId: SettingsCategoryId,
    highlightItemId: String?,
    states: SettingsStates,
    intents: SettingsIntents,
    controlsEnabled: Boolean,
    onReportTypeSelected: (GitHubIssueType) -> Unit,
) {
    when (categoryId) {
        SettingsCategoryId.PHYSIOLOGY_PROFILE ->
            PhysiologyProfileCategoryScreen(states, intents, controlsEnabled, highlightItemId)
        SettingsCategoryId.SLEEP -> SleepCategoryScreen(states, intents, controlsEnabled, highlightItemId)
        SettingsCategoryId.TRAINING -> TrainingCategoryScreen(states, intents, controlsEnabled, highlightItemId)
        SettingsCategoryId.VITALS -> VitalsCategoryScreen(states, intents, controlsEnabled, highlightItemId)
        SettingsCategoryId.DATA_SOURCES_SYNC ->
            DataSourcesSyncCategoryScreen(states, intents, highlightItemId)
        SettingsCategoryId.BACKUP_RESTORE -> BackupRestoreCategoryScreen(states, intents, highlightItemId)
        SettingsCategoryId.DISPLAY -> DisplayCategoryScreen(states, intents, highlightItemId)
        SettingsCategoryId.SUPPORT_ABOUT ->
            SupportAboutCategoryScreen(intents, onReportTypeSelected, highlightItemId)
    }
}

@Composable
private fun RestoreConfirmDialog(
    states: SettingsStates,
    intents: SettingsIntents,
) {
    if (states.localBackupState.showRestoreConfirmDialog) {
        AlertDialog(
            onDismissRequest = { intents.onLocalBackupEvent(SettingsEvent.RestoreDismissed) },
            title = { Text(stringResource(R.string.dialog_restore_backup_title)) },
            text = {
                val filename =
                    states.localBackupState.pendingRestoreFile?.name
                        ?: stringResource(R.string.backup_this_backup)
                Text(stringResource(R.string.dialog_restore_backup_body, filename))
            },
            confirmButton = {
                Button(onClick = { intents.onLocalBackupEvent(SettingsEvent.RestoreConfirmed) }) {
                    Text(stringResource(R.string.action_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { intents.onLocalBackupEvent(SettingsEvent.RestoreDismissed) }) {
                    Text(stringResource(CoreUiR.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun IssueReportDialogHandler(
    pendingReportType: GitHubIssueType?,
    hasCrashReport: Boolean,
    onDismiss: () -> Unit,
    onSendIssueReport: (IssueReportRequest) -> Unit,
) {
    pendingReportType?.let { reportType ->
        IssueReportDialog(
            reportType = reportType,
            hasCrashReport = hasCrashReport,
            onDismiss = onDismiss,
            onSubmit = onSendIssueReport,
        )
    }
}
