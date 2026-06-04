package com.gregor.lauritz.healthdashboard.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.R
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.MainActivity
import com.gregor.lauritz.healthdashboard.data.preferences.AppTheme
import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.ui.components.DropdownPreferenceItem
import com.gregor.lauritz.healthdashboard.ui.components.PhysiologyProfilePicker
import com.gregor.lauritz.healthdashboard.ui.components.SectionHeader
import com.gregor.lauritz.healthdashboard.ui.components.SettingsToggleItem
import com.gregor.lauritz.healthdashboard.ui.common.resolveOrNull
import com.gregor.lauritz.healthdashboard.ui.settings.LocalBackupViewModel.SideEffect
import com.gregor.lauritz.healthdashboard.ui.settings.backup.LocalBackupSection
import com.gregor.lauritz.healthdashboard.ui.settings.common.UnitSystemSelector
import com.gregor.lauritz.healthdashboard.ui.settings.data.DataManagementSection
import com.gregor.lauritz.healthdashboard.ui.settings.data.DeviceSelectionSection
import com.gregor.lauritz.healthdashboard.ui.settings.data.SyncSettingsSection
import com.gregor.lauritz.healthdashboard.ui.settings.physiologyprofile.HeartRateZoneSection
import kotlinx.coroutines.flow.collectLatest
import kotlinx.parcelize.Parcelize

@Parcelize
data class SettingsExpandState(
    val genderExpanded: Boolean = false,
    val collapseDataBackup: Boolean = false,
    val collapseBaselinesThresholds: Boolean = false,
    val collapseDisplay: Boolean = false,
    val collapseAdvanced: Boolean = false,
    val aboutDismissed: Boolean = false,
) : Parcelable

data class SettingsSectionMetadata(
    val id: String,
    val name: String,
    val keywords: List<String>,
)

val settingsSections =
    listOf(
        SettingsSectionMetadata(
            id = "data_backup_sync",
            name = "Data & Backup",
            keywords =
                listOf(
                    "backup",
                    "local",
                    "data",
                    "retention",
                    "resync",
                    "health connect",
                    "sync",
                    "foreground",
                ),
        ),
        SettingsSectionMetadata(
            id = "baselines_thresholds",
            name = "Baselines & Thresholds",
            keywords =
                listOf(
                    "step",
                    "goal",
                    "sleep",
                    "hrv",
                    "rhr",
                    "heart rate",
                    "zone",
                    "baseline",
                    "threshold",
                    "consistency",
                ),
        ),
        SettingsSectionMetadata(
            id = "display",
            name = "Display",
            keywords = listOf("appearance", "theme"),
        ),
        SettingsSectionMetadata(
            id = "advanced",
            name = "Advanced",
            keywords = listOf("advanced", "override", "pai", "resting", "hr timing"),
        ),
    )

fun sectionMatches(
    section: SettingsSectionMetadata,
    query: String,
): Boolean {
    if (query.isBlank()) return true
    val lowerQuery = query.lowercase()
    return section.name.lowercase().contains(lowerQuery) ||
        section.keywords.any { it.contains(lowerQuery) }
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

    LaunchedEffect(localBackupViewModel.sideEffect) {
        localBackupViewModel.sideEffect.collectLatest { effect ->
            when (effect) {
                SideEffect.RestartApp -> {
                    val restartIntent =
                        Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                    context.startActivity(restartIntent)
                }
                is SideEffect.TakePersistableUriPermission -> {
                    val uri = android.net.Uri.parse(effect.uri)
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
    )

    // Loading dialog during resync
    if (syncState.isResyncing) {
        Dialog(
            onDismissRequest = {},
            properties =
                DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false,
                ),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.message_syncing_health_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
) {
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
                val filename = localBackupState.pendingRestoreFile?.name
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
                                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.accessibility_clear))
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true,
                )

                // Data & Backup & Health Connect
                if (matchingSections.any { it.id == "data_backup_sync" }) {
                    M3CollapsibleSection(
                        header = "Data & Backup",
                        expanded =
                            !expandState.collapseDataBackup ||
                                shouldExpandSection("data_backup_sync"),
                        onExpandedChange = {
                            expandState = expandState.copy(collapseDataBackup = !it)
                        },
                    ) {
                        Column {
                            SectionHeader("Local Backup")
                            LocalBackupSection(
                                uiState = localBackupState,
                                onEvent = onLocalBackupEvent,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            SectionHeader("Device")
                            DeviceSelectionSection()
                            Spacer(modifier = Modifier.height(12.dp))
                            SectionHeader("Data Management")
                            DataManagementSection(
                                uiState = uiState,
                                isResyncing = syncState.isResyncing,
                                onEvent = onUIEvent,
                                onSyncEvent = onSyncEvent,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            SectionHeader("Health Connect")
                            SyncSettingsSection(uiState = syncState, onEvent = onSyncEvent)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
                }

                // Baselines & Thresholds
                if (matchingSections.any { it.id == "baselines_thresholds" }) {
                    M3CollapsibleSection(
                        header = "Baselines & Thresholds",
                        expanded =
                            !expandState.collapseBaselinesThresholds ||
                                shouldExpandSection("baselines_thresholds"),
                        onExpandedChange = {
                            expandState = expandState.copy(collapseBaselinesThresholds = !it)
                        },
                    ) {
                        Column {
                            SectionHeader("Daily Step Goal")
                            ActivitySettingsSection(stepGoal = uiState.stepGoal, onEvent = onUIEvent)
                            Spacer(modifier = Modifier.height(12.dp))
                            SectionHeader("Sleep")
                            SleepSettingsSection(uiState = sleepState, onEvent = onSleepEvent)
                            Spacer(modifier = Modifier.height(12.dp))
                            SectionHeader("Heart Rate Zones")
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
                            Spacer(modifier = Modifier.height(16.dp))
                            SectionHeader("Circadian Consistency")
                            CircadianThresholdSettingsSection(
                                profile = physiologyState.physiologyProfile,
                                currentOverride = thresholdState.circadianThresholdOverride,
                                isShiftWorkerMode = physiologyState.physiologyProfile == PhysiologyProfile.SHIFT_WORKER,
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
                            SectionHeader("Thresholds")
                            ThresholdSettingsSection(uiState = thresholdState, onEvent = onThresholdEvent)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
                }

                // Display
                if (matchingSections.any { it.id == "display" }) {
                    M3CollapsibleSection(
                        header = "Display",
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
                                label = "Dynamic Color",
                                description = "Use colors derived from your wallpaper (Android 12+)",
                                checked = uiState.dynamicColorEnabled,
                                onCheckedChange = { onUIEvent(SettingsEvent.DynamicColorEnabledChanged(it)) },
                            )
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
                        header = "Advanced",
                        expanded =
                            !expandState.collapseAdvanced ||
                                shouldExpandSection("advanced"),
                        onExpandedChange = {
                            expandState = expandState.copy(collapseAdvanced = !it)
                        },
                    ) {
                        AdvancedSettingsSection(
                            sleepState = sleepState,
                            paiScalingFactor = uiState.paiScalingFactor,
                            trimpModel = uiState.trimpModel,
                            banisterMultiplier = uiState.banisterMultiplier,
                            chengBeta = uiState.chengBeta,
                            itrimB = uiState.itrimB,
                            onEvent = onSleepEvent,
                            onPhysiologyEvent = onPhysiologyEvent,
                            onUIEvent = onUIEvent,
                        )
                    }
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            TextButton(onClick = onNavigateToAbout) {
                Text(
                    text = "About Readylytics",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        label = "App Theme",
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
