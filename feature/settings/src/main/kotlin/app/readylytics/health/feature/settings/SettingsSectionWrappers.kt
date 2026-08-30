package app.readylytics.health.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.feature.settings.data.DataSourceSettingsSection

@Composable
internal fun DataBackupSyncSectionWrapper(
    states: SettingsStates,
    intents: SettingsIntents,
    matchingSections: List<SettingsSectionMetadata>,
    expandState: SettingsExpandState,
    shouldExpandSection: (String) -> Boolean,
    onExpandStateChange: (SettingsExpandState) -> Unit,
) {
    if (matchingSections.any { it.id == "data_backup_sync" }) {
        M3CollapsibleSection(
            header = stringResource(R.string.settings_section_data_backup),
            expanded = !expandState.collapseDataBackup || shouldExpandSection("data_backup_sync"),
            onExpandedChange = { onExpandStateChange(expandState.copy(collapseDataBackup = !it)) },
        ) {
            DataBackupSyncSection(
                localBackupState = states.localBackupState,
                uiState = states.uiState,
                syncState = states.syncState,
                isResyncing = states.syncState.isResyncing,
                onLocalBackupEvent = intents.onLocalBackupEvent,
                onUIEvent = intents.onUIEvent,
                onSyncEvent = intents.onSyncEvent,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = MaterialTheme.spacing.small))
    }
}

@Composable
internal fun DataSourcesSectionWrapper(
    matchingSections: List<SettingsSectionMetadata>,
    expandState: SettingsExpandState,
    shouldExpandSection: (String) -> Boolean,
    onExpandStateChange: (SettingsExpandState) -> Unit,
) {
    if (matchingSections.any { it.id == "data_sources" }) {
        M3CollapsibleSection(
            header = stringResource(R.string.data_sources_title),
            expanded = !expandState.collapseDataSources || shouldExpandSection("data_sources"),
            onExpandedChange = { onExpandStateChange(expandState.copy(collapseDataSources = !it)) },
        ) {
            DataSourceSettingsSection()
        }
        HorizontalDivider(modifier = Modifier.padding(top = MaterialTheme.spacing.small))
    }
}

@Composable
internal fun BaselinesThresholdsSectionWrapper(
    states: SettingsStates,
    intents: SettingsIntents,
    matchingSections: List<SettingsSectionMetadata>,
    expandState: SettingsExpandState,
    shouldExpandSection: (String) -> Boolean,
    controlsEnabled: Boolean,
    onExpandStateChange: (SettingsExpandState) -> Unit,
) {
    if (matchingSections.any { it.id == "baselines_thresholds" }) {
        M3CollapsibleSection(
            header = stringResource(R.string.settings_section_baselines_thresholds),
            expanded = !expandState.collapseBaselinesThresholds || shouldExpandSection("baselines_thresholds"),
            onExpandedChange = { onExpandStateChange(expandState.copy(collapseBaselinesThresholds = !it)) },
        ) {
            BaselinesThresholdsSection(
                context =
                    BaselinesThresholdsContext(
                        thresholdState = states.thresholdState,
                        sleepState = states.sleepState,
                        physiologyState = states.physiologyState,
                        heartRateState = states.heartRateState,
                        uiState = states.uiState,
                        isResyncing = states.syncState.isResyncing,
                        controlsEnabled = controlsEnabled,
                        onThresholdEvent = intents.onThresholdEvent,
                        onSleepEvent = intents.onSleepEvent,
                        onUIEvent = intents.onUIEvent,
                        onPhysiologyEvent = intents.onPhysiologyEvent,
                        onHeartRateEvent = intents.onHeartRateEvent,
                    ),
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = MaterialTheme.spacing.small))
    }
}

@Composable
internal fun DisplaySectionWrapper(
    states: SettingsStates,
    intents: SettingsIntents,
    matchingSections: List<SettingsSectionMetadata>,
    expandState: SettingsExpandState,
    shouldExpandSection: (String) -> Boolean,
    onExpandStateChange: (SettingsExpandState) -> Unit,
) {
    if (matchingSections.any { it.id == "display" }) {
        M3CollapsibleSection(
            header = stringResource(R.string.settings_section_display),
            expanded = !expandState.collapseDisplay || shouldExpandSection("display"),
            onExpandedChange = { onExpandStateChange(expandState.copy(collapseDisplay = !it)) },
        ) {
            DisplaySettingsSection(
                uiState = states.uiState,
                dashboardCardsState = states.dashboardCardsState,
                onUIEvent = intents.onUIEvent,
                onDashboardCardsEvent = intents.onDashboardCardsEvent,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = MaterialTheme.spacing.small))
    }
}

@Composable
internal fun AdvancedSectionWrapper(
    states: SettingsStates,
    intents: SettingsIntents,
    matchingSections: List<SettingsSectionMetadata>,
    expandState: SettingsExpandState,
    shouldExpandSection: (String) -> Boolean,
    onExpandStateChange: (SettingsExpandState) -> Unit,
) {
    if (matchingSections.any { it.id == "advanced" }) {
        M3CollapsibleSection(
            header = stringResource(R.string.settings_section_advanced),
            expanded = !expandState.collapseAdvanced || shouldExpandSection("advanced"),
            onExpandedChange = { onExpandStateChange(expandState.copy(collapseAdvanced = !it)) },
        ) {
            AdvancedSettingsSection(
                sleepState = states.sleepState,
                uiState = states.uiState,
                onEvent = intents.onSleepEvent,
                onPhysiologyEvent = intents.onPhysiologyEvent,
                onUIEvent = intents.onUIEvent,
                isResyncing = states.syncState.isResyncing,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = MaterialTheme.spacing.small))
    }
}

@Composable
internal fun IssueReportingSectionWrapper(
    matchingSections: List<SettingsSectionMetadata>,
    expandState: SettingsExpandState,
    shouldExpandSection: (String) -> Boolean,
    onReportTypeSelected: (app.readylytics.health.core.model.domain.githubissue.GitHubIssueType) -> Unit,
    onExpandStateChange: (SettingsExpandState) -> Unit,
) {
    if (matchingSections.any { it.id == "issue_reporting" }) {
        M3CollapsibleSection(
            header = stringResource(R.string.settings_section_issue_reporting),
            expanded = !expandState.collapseIssueReporting || shouldExpandSection("issue_reporting"),
            onExpandedChange = { onExpandStateChange(expandState.copy(collapseIssueReporting = !it)) },
        ) {
            IssueReportingSection(onReportTypeSelected = onReportTypeSelected)
        }
        HorizontalDivider(modifier = Modifier.padding(top = MaterialTheme.spacing.small))
    }
}

@Composable
internal fun MiscellaneousSectionWrapper(
    matchingSections: List<SettingsSectionMetadata>,
    expandState: SettingsExpandState,
    shouldExpandSection: (String) -> Boolean,
    intents: SettingsIntents,
    onExpandStateChange: (SettingsExpandState) -> Unit,
) {
    if (matchingSections.any { it.id == "miscellaneous" }) {
        M3CollapsibleSection(
            header = stringResource(R.string.settings_section_miscellaneous),
            expanded = !expandState.collapseMiscellaneous || shouldExpandSection("miscellaneous"),
            onExpandedChange = { onExpandStateChange(expandState.copy(collapseMiscellaneous = !it)) },
        ) {
            MiscellaneousSection(
                onNavigateToAbout = intents.onNavigateToAbout,
                onNavigateToLicenses = intents.onNavigateToLicenses,
                onOpenPrivacyPolicy = intents.onOpenPrivacyPolicy,
                onOpenSourceCode = intents.onOpenSourceCode,
            )
        }
    }
}
