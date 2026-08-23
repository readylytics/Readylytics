package app.readylytics.health.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.calculateSecondarySeedColor
import app.readylytics.health.core.designsystem.calculateTertiarySeedColor
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.data.preferences.AppTheme
import app.readylytics.health.core.model.domain.githubissue.GitHubIssueType
import app.readylytics.health.core.ui.common.resolveOrNull
import app.readylytics.health.core.ui.components.DropdownPreferenceItem
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.core.ui.components.SettingsToggleItem
import app.readylytics.health.core.ui.components.settings.PhysiologyProfilePicker
import app.readylytics.health.core.ui.settings.common.UnitSystemSelector
import app.readylytics.health.feature.settings.backup.LocalBackupSection
import app.readylytics.health.feature.settings.common.CustomColorPicker
import app.readylytics.health.feature.settings.data.DataManagementSection
import app.readylytics.health.feature.settings.data.DataSourceSettingsSection
import app.readylytics.health.feature.settings.data.SyncSettingsSection
import app.readylytics.health.feature.settings.physiologyprofile.HeartRateZoneSection
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
internal fun DataBackupSyncSection(
    localBackupState: LocalBackupState,
    uiState: UIState,
    syncState: SyncSettingsState,
    isResyncing: Boolean,
    onLocalBackupEvent: (SettingsEvent) -> Unit,
    onUIEvent: (SettingsEvent) -> Unit,
    onSyncEvent: (SettingsEvent) -> Unit,
) {
    Column {
        SectionHeader(stringResource(R.string.settings_sub_local_backup))
        LocalBackupSection(
            uiState = localBackupState,
            onEvent = onLocalBackupEvent,
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
        SectionHeader(stringResource(R.string.settings_sub_data_management))
        DataManagementSection(
            uiState = uiState,
            isResyncing = isResyncing,
            onEvent = onUIEvent,
            onSyncEvent = onSyncEvent,
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
        SectionHeader(stringResource(R.string.settings_sub_health_connect))
        SyncSettingsSection(uiState = syncState, onEvent = onSyncEvent)
    }
}

@Composable
internal fun BaselinesThresholdsSection(
    thresholdState: ThresholdSettingsState,
    sleepState: SleepSettingsState,
    physiologyState: PhysiologySettingsState,
    heartRateState: HeartRateZonesState,
    uiState: UIState,
    isResyncing: Boolean,
    controlsEnabled: Boolean,
    callbacks: BaselinesThresholdsCallbacks,
) {
    Column {
        ActivityThresholdsSubsection(stepGoal = uiState.stepGoal, onUIEvent = { callbacks.onSleepEvent(it) })
        SleepThresholdsSubsection(sleepState = sleepState, onSleepEvent = callbacks.onSleepEvent, isResyncing = isResyncing)
        HeartRateProfileSubsection(
            heartRateState = heartRateState,
            physiologyState = physiologyState,
            onHeartRateEvent = callbacks.onHeartRateEvent,
            onPhysiologyEvent = callbacks.onPhysiologyEvent,
            isResyncing = isResyncing,
            controlsEnabled = controlsEnabled,
        )
        LoadSourcesTolerance(sleepState = sleepState, onSleepEvent = callbacks.onSleepEvent, isResyncing = isResyncing)
        CircadianThresholdsSubsection(
            thresholdState = thresholdState,
            physiologyState = physiologyState,
            onThresholdEvent = callbacks.onThresholdEvent,
            controlsEnabled = controlsEnabled,
        )
        ThresholdSettingsSection(
            uiState = thresholdState,
            onEvent = callbacks.onThresholdEvent,
            isResyncing = isResyncing,
        )
    }
}


@Composable
internal fun DisplaySettingsSection(
    uiState: UIState,
    dashboardCardsState: DashboardCardsSettingsState,
    onUIEvent: (SettingsEvent) -> Unit,
    onDashboardCardsEvent: (SettingsEvent) -> Unit,
) {
    Column {
        AppThemeItem(uiState = uiState, onEvent = onUIEvent)
        DynamicColorSettings(uiState = uiState, onUIEvent = onUIEvent)
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.pageSectionGap))
        UnitSystemSelector(
            selectedUnit = uiState.unitSystem,
            onUnitSelected = { onUIEvent(SettingsEvent.UnitSystemChanged(it)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.pageHorizontal),
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.pageSectionGap))
        DashboardCardsSettingsSection(uiState = dashboardCardsState, onEvent = onDashboardCardsEvent)
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.pageSectionGap))
        WorkoutDetailLayoutSettingsSection(onEvent = onUIEvent)
    }
}

@Composable
private fun DynamicColorSettings(
    uiState: UIState,
    onUIEvent: (SettingsEvent) -> Unit,
) {
    SettingsToggleItem(
        label = stringResource(CoreUiR.string.onboarding_dynamic_color_label),
        description = stringResource(CoreUiR.string.onboarding_dynamic_color_desc),
        checked = uiState.dynamicColorEnabled,
        onCheckedChange = { onUIEvent(SettingsEvent.DynamicColorEnabledChanged(it)) },
    )
    AnimatedVisibility(visible = !uiState.dynamicColorEnabled) {
        Column {
            CustomColorPicker(
                label = stringResource(R.string.fallback_theme_color_label),
                selectedColor = Color(uiState.customPrimaryColor),
                onColorSelected = { onUIEvent(SettingsEvent.CustomPrimaryColorChanged(it.toArgb().toLong())) },
                enabled = true,
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = MaterialTheme.spacing.pageHorizontal,
                    vertical = MaterialTheme.spacing.small,
                ),
            )
            PaletteCustomizationSettings(uiState = uiState, onUIEvent = onUIEvent)
        }
    }
}

@Composable
private fun PaletteCustomizationSettings(
    uiState: UIState,
    onUIEvent: (SettingsEvent) -> Unit,
) {
    SettingsToggleItem(
        label = stringResource(R.string.settings_customize_palette_label),
        description = stringResource(R.string.settings_customize_palette_desc),
        checked = uiState.isCustomPaletteEnabled,
        onCheckedChange = { onUIEvent(SettingsEvent.CustomPaletteEnabledChanged(it)) },
    )
    val primarySeed = Color(uiState.customPrimaryColor)
    val currentSecondary = if (uiState.isCustomPaletteEnabled) {
        Color(uiState.customSecondaryColor)
    } else {
        calculateSecondarySeedColor(primarySeed)
    }
    val currentTertiary = if (uiState.isCustomPaletteEnabled) {
        Color(uiState.customTertiaryColor)
    } else {
        calculateTertiarySeedColor(primarySeed)
    }
    CustomColorPicker(
        label = stringResource(R.string.settings_secondary_color_label),
        selectedColor = currentSecondary,
        onColorSelected = { onUIEvent(SettingsEvent.CustomSecondaryColorChanged(it.toArgb().toLong())) },
        enabled = uiState.isCustomPaletteEnabled,
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = MaterialTheme.spacing.pageHorizontal,
            vertical = MaterialTheme.spacing.small,
        ),
        onReset = {
            onUIEvent(SettingsEvent.CustomSecondaryColorChanged(
                calculateSecondarySeedColor(primarySeed).toArgb().toLong(),
            ))
        },
        showPresets = false,
    )
    CustomColorPicker(
        label = stringResource(R.string.settings_tertiary_color_label),
        selectedColor = currentTertiary,
        onColorSelected = { onUIEvent(SettingsEvent.CustomTertiaryColorChanged(it.toArgb().toLong())) },
        enabled = uiState.isCustomPaletteEnabled,
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = MaterialTheme.spacing.pageHorizontal,
            vertical = MaterialTheme.spacing.small,
        ),
        onReset = {
            onUIEvent(SettingsEvent.CustomTertiaryColorChanged(
                calculateTertiarySeedColor(primarySeed).toArgb().toLong(),
            ))
        },
        showPresets = false,
    )
}

@Composable
internal fun IssueReportingSection(
    onReportTypeSelected: (GitHubIssueType) -> Unit,
) {
    Column {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = {
                Text(
                    text = stringResource(R.string.settings_item_report_bug),
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            modifier =
                Modifier.clickable {
                    onReportTypeSelected(GitHubIssueType.BUG_REPORT)
                },
        )
        HorizontalDivider(
            modifier = Modifier.padding(vertical = MaterialTheme.spacing.extraSmall),
        )
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = {
                Text(
                    text = stringResource(R.string.settings_item_request_feature),
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            modifier =
                Modifier.clickable {
                    onReportTypeSelected(GitHubIssueType.FEATURE_REQUEST)
                },
        )
    }
}

@Composable
internal fun MiscellaneousSection(
    onNavigateToAbout: () -> Unit,
    onNavigateToLicenses: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onOpenSourceCode: () -> Unit,
) {
    Column {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = {
                Text(
                    text = stringResource(R.string.settings_about_button),
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            modifier = Modifier.clickable { onNavigateToAbout() },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.spacing.extraSmall))
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = {
                Text(
                    text = stringResource(R.string.settings_item_licenses),
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            modifier = Modifier.clickable { onNavigateToLicenses() },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.spacing.extraSmall))
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = {
                Text(
                    text = stringResource(R.string.settings_item_privacy_policy),
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            modifier = Modifier.clickable { onOpenPrivacyPolicy() },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.spacing.extraSmall))
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = {
                Text(
                    text = stringResource(R.string.settings_item_source_code),
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            modifier = Modifier.clickable { onOpenSourceCode() },
        )
    }
}

@Composable
private fun AppThemeItem(
    uiState: UIState,
    onEvent: (SettingsEvent) -> Unit,
) {
    DropdownPreferenceItem(
        label = stringResource(R.string.settings_label_app_theme),
        selectedDisplayValue =
            uiState.appTheme.name
                .lowercase()
                .replaceFirstChar { it.uppercase() },
        options = AppTheme.entries,
        onOptionSelected = { onEvent(SettingsEvent.AppThemeChanged(it)) },
        optionLabel = { it.name.lowercase().replaceFirstChar { it.uppercase() } },
        modifier =
            Modifier.fillMaxWidth().padding(
                horizontal = MaterialTheme.spacing.pageHorizontal,
                vertical = MaterialTheme.spacing.small,
            ),
    )
}
