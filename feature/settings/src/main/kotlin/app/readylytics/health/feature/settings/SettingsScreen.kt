package app.readylytics.health.feature.settings

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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.feature.settings.R
import app.readylytics.health.core.designsystem.calculateSecondarySeedColor
import app.readylytics.health.core.designsystem.calculateTertiarySeedColor
import app.readylytics.health.core.ui.common.resolveOrNull
import app.readylytics.health.core.ui.components.DropdownPreferenceItem
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.core.ui.components.SettingsToggleItem
import app.readylytics.health.core.ui.settings.common.UnitSystemSelector
import app.readylytics.health.data.preferences.AppTheme
import app.readylytics.health.feature.settings.PhysiologyProfilePicker
import app.readylytics.health.feature.settings.LocalBackupViewModel.SideEffect
import app.readylytics.health.feature.settings.backup.LocalBackupSection
import app.readylytics.health.feature.settings.common.CustomColorPicker
import app.readylytics.health.feature.settings.data.DataManagementSection
import app.readylytics.health.feature.settings.data.DataSourceSettingsSection
import app.readylytics.health.feature.settings.data.SyncSettingsSection
import app.readylytics.health.feature.settings.physiologyprofile.HeartRateZoneSection
import kotlinx.coroutines.flow.collectLatest

@Suppress("DEPRECATION")
private fun openOssLicenses(
    context: android.content.Context,
    licensesTitle: String,
) {
    com.google.android.gms.oss.licenses.OssLicensesMenuActivity
        .setActivityTitle(licensesTitle)
    context.startActivity(
        Intent(context, com.google.android.gms.oss.licenses.OssLicensesMenuActivity::class.java),
    )
}

private fun openPrivacyPolicy(context: android.content.Context) {
    val url = context.getString(R.string.privacy_policy_url)
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
    onNavigateToAbout: () -> Unit = {},
) {
    val thresholdState by thresholdViewModel.consolidatedState.collectAsStateWithLifecycle()
    val sleepState by sleepViewModel.uiState.collectAsStateWithLifecycle()
    val physiologyState by physiologyViewModel.uiState.collectAsStateWithLifecycle()
    val heartRateState by heartRateViewModel.uiState.collectAsStateWithLifecycle()
    val localBackupState by localBackupViewModel.uiState.collectAsStateWithLifecycle()
    val syncState by syncViewModel.uiState.collectAsStateWithLifecycle()
    val uiState by uiViewModel.uiState.collectAsStateWithLifecycle()

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
        onThresholdEvent = thresholdViewModel::onEvent,
        onSleepEvent = sleepViewModel::onEvent,
        onPhysiologyEvent = physiologyViewModel::onEvent,
        onHeartRateEvent = heartRateViewModel::onEvent,
        onLocalBackupEvent = localBackupViewModel::onEvent,
        onSyncEvent = syncViewModel::onEvent,
        onUIEvent = uiViewModel::onEvent,
        onNavigateToAbout = onNavigateToAbout,
        onNavigateToLicenses = {
            openOssLicenses(context, licensesTitle)
        },
        onOpenPrivacyPolicy = {
            openPrivacyPolicy(context)
        },
    )

    // Determinate, non-blocking progress dialog during resync. The work runs durably in WorkManager
    // (with a foreground notification), so the user may dismiss this and leave — the resync continues.
    var resyncDialogDismissed by rememberSaveable { mutableStateOf(false) }
    if (!syncState.isResyncing) resyncDialogDismissed = false
    if (syncState.isResyncing && !resyncDialogDismissed) {
        AlertDialog(
            onDismissRequest = { resyncDialogDismissed = true },
            confirmButton = {
                TextButton(onClick = { resyncDialogDismissed = true }) {
                    Text(stringResource(R.string.resync_dialog_continue_in_background))
                }
            },
            title = { Text(stringResource(R.string.resync_button_label)) },
            text = {
                Column {
                    val total = syncState.resyncTotal
                    val current = syncState.resyncCurrent
                    if (total > 0) {
                        Text(
                            text = stringResource(R.string.recalculating_progress, current, total),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { current.toFloat() / total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.resync_notification_preparing),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
        )
    }
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
    onThresholdEvent: (SettingsEvent) -> Unit,
    onSleepEvent: (SettingsEvent) -> Unit,
    onPhysiologyEvent: (SettingsEvent) -> Unit,
    onHeartRateEvent: (SettingsEvent) -> Unit,
    onLocalBackupEvent: (SettingsEvent) -> Unit,
    onSyncEvent: (SettingsEvent) -> Unit,
    onUIEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToAbout: () -> Unit = {},
    onNavigateToLicenses: () -> Unit = {},
    onOpenPrivacyPolicy: () -> Unit = {},
) {
    val context = LocalContext.current
    var expandState by rememberSaveable { mutableStateOf(SettingsExpandState()) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val resolvedThresholdError = thresholdState.thresholdError.resolveOrNull()

    val matchingSections by remember(searchQuery) {
        derivedStateOf { settingsSections.filter { sectionMatches(it, searchQuery) } }
    }
    val shouldExpandSection = { sectionId: String ->
        searchQuery.isNotBlank() && matchingSections.any { it.id == sectionId }
    }

    if (localBackupState.showRestoreConfirmDialog) {
        AlertDialog(
            onDismissRequest = { onLocalBackupEvent(SettingsEvent.RestoreDismissed) },
            title = { Text(stringResource(R.string.dialog_restore_backup_title)) },
            text = {
                val filename =
                    localBackupState.pendingRestoreFile?.name
                        ?: stringResource(R.string.backup_this_backup)
                Text(stringResource(R.string.dialog_restore_backup_body, filename))
            },
            confirmButton = {
                Button(onClick = { onLocalBackupEvent(SettingsEvent.RestoreConfirmed) }) {
                    Text(stringResource(R.string.action_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { onLocalBackupEvent(SettingsEvent.RestoreDismissed) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

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
                        .padding(vertical = 8.dp),
            ) {
                // Search
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.settings_search_placeholder)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.accessibility_search))
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
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

                // Data & Backup & Health Connect
                if (matchingSections.any { it.id == "data_backup_sync" }) {
                    M3CollapsibleSection(
                        header = stringResource(R.string.settings_section_data_backup),
                        expanded =
                            !expandState.collapseDataBackup ||
                                shouldExpandSection("data_backup_sync"),
                        onExpandedChange = {
                            expandState = expandState.copy(collapseDataBackup = !it)
                        },
                    ) {
                        Column {
                            SectionHeader(stringResource(R.string.settings_sub_local_backup))
                            LocalBackupSection(
                                uiState = localBackupState,
                                onEvent = onLocalBackupEvent,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            SectionHeader(stringResource(R.string.settings_sub_data_management))
                            DataManagementSection(
                                uiState = uiState,
                                isResyncing = syncState.isResyncing,
                                onEvent = onUIEvent,
                                onSyncEvent = onSyncEvent,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            SectionHeader(stringResource(R.string.settings_sub_health_connect))
                            SyncSettingsSection(uiState = syncState, onEvent = onSyncEvent)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
                }

                // Data Sources (per–data-type source device selection)
                if (matchingSections.any { it.id == "data_sources" }) {
                    M3CollapsibleSection(
                        header = stringResource(R.string.data_sources_title),
                        expanded =
                            !expandState.collapseDataSources ||
                                shouldExpandSection("data_sources"),
                        onExpandedChange = {
                            expandState = expandState.copy(collapseDataSources = !it)
                        },
                    ) {
                        DataSourceSettingsSection()
                    }
                    HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
                }

                // Baselines & Thresholds
                if (matchingSections.any { it.id == "baselines_thresholds" }) {
                    M3CollapsibleSection(
                        header = stringResource(R.string.settings_section_baselines_thresholds),
                        expanded =
                            !expandState.collapseBaselinesThresholds ||
                                shouldExpandSection("baselines_thresholds"),
                        onExpandedChange = {
                            expandState = expandState.copy(collapseBaselinesThresholds = !it)
                        },
                    ) {
                        Column {
                            SectionHeader(stringResource(R.string.label_daily_step_goal))
                            ActivitySettingsSection(stepGoal = uiState.stepGoal, onEvent = onUIEvent)
                            Spacer(modifier = Modifier.height(12.dp))
                            SectionHeader(stringResource(R.string.label_sleep))
                            SleepSettingsSection(uiState = sleepState, onEvent = onSleepEvent)
                            Spacer(modifier = Modifier.height(12.dp))
                            SectionHeader(stringResource(R.string.settings_sub_heart_rate_zones))
                            HeartRateZoneSection(
                                uiState = heartRateState,
                                physiologyState = physiologyState,
                                onEvent = onHeartRateEvent,
                                onPhysiologyEvent = onPhysiologyEvent,
                                expandState = expandState,
                                onExpandStateChange = { expandState = it },
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            PhysiologyProfilePicker(
                                selectedProfile = physiologyState.physiologyProfile,
                                onProfileSelected = { onPhysiologyEvent(SettingsEvent.PhysiologyProfileChanged(it)) },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            SectionHeader(stringResource(R.string.load_sources_section_title))
                            LoadSourcesSection(uiState = sleepState, onEvent = onSleepEvent)
                            Spacer(modifier = Modifier.height(16.dp))
                            SectionHeader(stringResource(R.string.label_circadian_consistency))
                            CircadianThresholdSettingsSection(
                                profile = physiologyState.physiologyProfile,
                                currentOverride = thresholdState.circadianThresholdOverride,
                                onOverrideChanged = {
                                    onThresholdEvent(
                                        SettingsEvent.CircadianThresholdOverrideChanged(it),
                                    )
                                },
                                isLoading = thresholdState.isUpdatingThreshold,
                                error = resolvedThresholdError,
                                onErrorDismissed = { onThresholdEvent(SettingsEvent.DismissThresholdError) },
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            SectionHeader(stringResource(R.string.settings_sub_thresholds))
                            ThresholdSettingsSection(uiState = thresholdState, onEvent = onThresholdEvent)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
                }

                // Display
                if (matchingSections.any { it.id == "display" }) {
                    M3CollapsibleSection(
                        header = stringResource(R.string.settings_section_display),
                        expanded =
                            !expandState.collapseDisplay ||
                                shouldExpandSection("display"),
                        onExpandedChange = {
                            expandState = expandState.copy(collapseDisplay = !it)
                        },
                    ) {
                        Column {
                            AppThemeItem(uiState = uiState, onEvent = onUIEvent)
                            SettingsToggleItem(
                                label = stringResource(R.string.onboarding_dynamic_color_label),
                                description = stringResource(R.string.onboarding_dynamic_color_desc),
                                checked = uiState.dynamicColorEnabled,
                                onCheckedChange = { onUIEvent(SettingsEvent.DynamicColorEnabledChanged(it)) },
                            )
                            AnimatedVisibility(visible = !uiState.dynamicColorEnabled) {
                                Column {
                                    CustomColorPicker(
                                        label = stringResource(R.string.fallback_theme_color_label),
                                        selectedColor = Color(uiState.customPrimaryColor),
                                        onColorSelected = {
                                            onUIEvent(
                                                SettingsEvent.CustomPrimaryColorChanged(it.toArgb().toLong()),
                                            )
                                        },
                                        enabled = true,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                    SettingsToggleItem(
                                        label = stringResource(R.string.settings_customize_palette_label),
                                        description = stringResource(R.string.settings_customize_palette_desc),
                                        checked = uiState.isCustomPaletteEnabled,
                                        onCheckedChange = { onUIEvent(SettingsEvent.CustomPaletteEnabledChanged(it)) },
                                    )
                                    val primarySeed = Color(uiState.customPrimaryColor)
                                    val currentSecondary =
                                        if (uiState.isCustomPaletteEnabled) {
                                            Color(uiState.customSecondaryColor)
                                        } else {
                                            calculateSecondarySeedColor(primarySeed)
                                        }
                                    val currentTertiary =
                                        if (uiState.isCustomPaletteEnabled) {
                                            Color(uiState.customTertiaryColor)
                                        } else {
                                            calculateTertiarySeedColor(primarySeed)
                                        }
                                    CustomColorPicker(
                                        label = stringResource(R.string.settings_secondary_color_label),
                                        selectedColor = currentSecondary,
                                        onColorSelected = {
                                            onUIEvent(
                                                SettingsEvent.CustomSecondaryColorChanged(it.toArgb().toLong()),
                                            )
                                        },
                                        enabled = uiState.isCustomPaletteEnabled,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                        onReset = {
                                            onUIEvent(
                                                SettingsEvent.CustomSecondaryColorChanged(
                                                    calculateSecondarySeedColor(primarySeed).toArgb().toLong(),
                                                ),
                                            )
                                        },
                                        showPresets = false,
                                    )
                                    CustomColorPicker(
                                        label = stringResource(R.string.settings_tertiary_color_label),
                                        selectedColor = currentTertiary,
                                        onColorSelected = {
                                            onUIEvent(
                                                SettingsEvent.CustomTertiaryColorChanged(it.toArgb().toLong()),
                                            )
                                        },
                                        enabled = uiState.isCustomPaletteEnabled,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                        onReset = {
                                            onUIEvent(
                                                SettingsEvent.CustomTertiaryColorChanged(
                                                    calculateTertiarySeedColor(primarySeed).toArgb().toLong(),
                                                ),
                                            )
                                        },
                                        showPresets = false,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            UnitSystemSelector(
                                selectedUnit = uiState.unitSystem,
                                onUnitSelected = { onUIEvent(SettingsEvent.UnitSystemChanged(it)) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
                }

                // Advanced
                if (matchingSections.any { it.id == "advanced" }) {
                    M3CollapsibleSection(
                        header = stringResource(R.string.settings_section_advanced),
                        expanded =
                            !expandState.collapseAdvanced ||
                                shouldExpandSection("advanced"),
                        onExpandedChange = {
                            expandState = expandState.copy(collapseAdvanced = !it)
                        },
                    ) {
                        AdvancedSettingsSection(
                            sleepState = sleepState,
                            rasScalingFactor = uiState.rasScalingFactor,
                            trimpModel = uiState.trimpModel,
                            banisterMultiplier = uiState.banisterMultiplier,
                            chengBeta = uiState.chengBeta,
                            itrimB = uiState.itrimB,
                            onEvent = onSleepEvent,
                            onPhysiologyEvent = onPhysiologyEvent,
                            onUIEvent = onUIEvent,
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
                }

                // Miscellaneous (About & Licenses)
                if (matchingSections.any { it.id == "miscellaneous" }) {
                    M3CollapsibleSection(
                        header = stringResource(R.string.settings_section_miscellaneous),
                        expanded =
                            !expandState.collapseMiscellaneous ||
                                shouldExpandSection("miscellaneous"),
                        onExpandedChange = {
                            expandState = expandState.copy(collapseMiscellaneous = !it)
                        },
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
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
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
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
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
                        }
                    }
                }
            }
        }
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}


