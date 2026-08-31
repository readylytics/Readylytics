package app.readylytics.health.feature.settings

/**
 * Wrapper for all settings screen states to reduce parameter list length.
 */
internal data class SettingsStates(
    val thresholdState: ThresholdSettingsState,
    val sleepState: SleepSettingsState,
    val physiologyState: PhysiologySettingsState,
    val heartRateState: HeartRateZonesState,
    val localBackupState: LocalBackupState,
    val syncState: SyncSettingsState,
    val uiState: UIState,
    val dashboardCardsState: DashboardCardsSettingsState,
    val hasCrashReport: Boolean,
)

/**
 * Wrapper for all settings screen event callbacks to reduce parameter list length.
 */
internal data class SettingsIntents(
    val onThresholdEvent: (SettingsEvent) -> Unit,
    val onSleepEvent: (SettingsEvent) -> Unit,
    val onPhysiologyEvent: (SettingsEvent) -> Unit,
    val onHeartRateEvent: (SettingsEvent) -> Unit,
    val onLocalBackupEvent: (SettingsEvent) -> Unit,
    val onSyncEvent: (SettingsEvent) -> Unit,
    val onUIEvent: (SettingsEvent) -> Unit,
    val onDashboardCardsEvent: (SettingsEvent) -> Unit,
    val onNavigateToAbout: () -> Unit,
    val onNavigateToLicenses: () -> Unit,
    val onOpenPrivacyPolicy: () -> Unit,
    val onOpenSourceCode: () -> Unit,
    val onSendIssueReport: (app.readylytics.health.core.model.domain.githubissue.IssueReportRequest) -> Unit,
)
