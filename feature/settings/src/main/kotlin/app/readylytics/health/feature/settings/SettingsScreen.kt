package app.readylytics.health.feature.settings

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.core.designsystem.calculateSecondarySeedColor
import app.readylytics.health.core.designsystem.calculateTertiarySeedColor
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.data.preferences.AppTheme
import app.readylytics.health.core.model.domain.githubissue.GitHubIssueType
import app.readylytics.health.core.model.domain.githubissue.IssueReportRequest
import app.readylytics.health.core.ui.common.resolveOrNull
import app.readylytics.health.core.ui.components.DropdownPreferenceItem
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.core.ui.components.SettingsToggleItem
import app.readylytics.health.core.ui.components.settings.PhysiologyProfilePicker
import app.readylytics.health.core.ui.settings.common.UnitSystemSelector
import app.readylytics.health.feature.settings.LocalBackupViewModel.SideEffect
import app.readylytics.health.feature.settings.R
import app.readylytics.health.feature.settings.backup.LocalBackupSection
import app.readylytics.health.feature.settings.common.CustomColorPicker
import app.readylytics.health.feature.settings.common.resyncGateEnabled
import app.readylytics.health.feature.settings.data.DataManagementSection
import app.readylytics.health.feature.settings.data.DataSourceSettingsSection
import app.readylytics.health.feature.settings.data.SyncSettingsSection
import app.readylytics.health.feature.settings.physiologyprofile.HeartRateZoneSection
import com.google.android.gms.oss.licenses.v2.OssLicensesMenuActivity
import kotlinx.coroutines.flow.collectLatest
import app.readylytics.health.core.ui.R as CoreUiR

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

@OptIn(ExperimentalMaterial3Api::class)
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
    val states = SettingsStates(
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
    val intents = SettingsIntents(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenContent(
    states: SettingsStates,
    intents: SettingsIntents,
    modifier: Modifier = Modifier,
) {
    var expandState by rememberSaveable { mutableStateOf(SettingsExpandState()) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var pendingReportType by remember { mutableStateOf<GitHubIssueType?>(null) }
    val isResyncing = states.syncState.isResyncing
    val controlsEnabled = resyncGateEnabled(isResyncing)

    val matchingSections by remember(searchQuery) {
        derivedStateOf { settingsSections.filter { sectionMatches(it, searchQuery) } }
    }
    val shouldExpandSection = { sectionId: String ->
        searchQuery.isNotBlank() && matchingSections.any { it.id == sectionId }
    }

    RestoreConfirmDialog(
        states = states,
        intents = intents,
    )

    IssueReportDialogHandler(
        pendingReportType = pendingReportType,
        hasCrashReport = states.hasCrashReport,
        onDismiss = { pendingReportType = null },
        onSendIssueReport = intents.onSendIssueReport,
    )

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = MaterialTheme.spacing.pageSectionGapSmall),
            ) {
                SettingsSearchBar(
                    searchQuery = searchQuery,
                    onSearchQueryChanged = { searchQuery = it },
                )

                DataBackupSyncSectionWrapper(
                    states = states,
                    intents = intents,
                    matchingSections = matchingSections,
                    expandState = expandState,
                    shouldExpandSection = shouldExpandSection,
                    onExpandStateChange = { expandState = it },
                )

                DataSourcesSectionWrapper(
                    matchingSections = matchingSections,
                    expandState = expandState,
                    shouldExpandSection = shouldExpandSection,
                    onExpandStateChange = { expandState = it },
                )

                BaselinesThresholdsSectionWrapper(
                    states = states,
                    intents = intents,
                    matchingSections = matchingSections,
                    expandState = expandState,
                    shouldExpandSection = shouldExpandSection,
                    controlsEnabled = controlsEnabled,
                    onExpandStateChange = { expandState = it },
                )

                DisplaySectionWrapper(
                    states = states,
                    intents = intents,
                    matchingSections = matchingSections,
                    expandState = expandState,
                    shouldExpandSection = shouldExpandSection,
                    onExpandStateChange = { expandState = it },
                )

                AdvancedSectionWrapper(
                    states = states,
                    intents = intents,
                    matchingSections = matchingSections,
                    expandState = expandState,
                    shouldExpandSection = shouldExpandSection,
                    onExpandStateChange = { expandState = it },
                )

                IssueReportingSectionWrapper(
                    matchingSections = matchingSections,
                    expandState = expandState,
                    shouldExpandSection = shouldExpandSection,
                    onReportTypeSelected = { pendingReportType = it },
                    onExpandStateChange = { expandState = it },
                )

                MiscellaneousSectionWrapper(
                    matchingSections = matchingSections,
                    expandState = expandState,
                    shouldExpandSection = shouldExpandSection,
                    intents = intents,
                    onExpandStateChange = { expandState = it },
                )
            }
        }
    }
}

@Composable
private fun SettingsSearchBar(
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
) {
    OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchQueryChanged,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.pageHorizontal,
                    vertical = MaterialTheme.spacing.pageSectionGapSmall,
                ),
        placeholder = { Text(stringResource(R.string.settings_search_placeholder)) },
        leadingIcon = {
            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.accessibility_search))
        },
        trailingIcon = {
            if (searchQuery.isNotEmpty()) {
                IconButton(onClick = { onSearchQueryChanged("") }) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.accessibility_clear),
                    )
                }
            }
        },
        shape = MaterialTheme.shapes.large,
        singleLine = true,
    )
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

