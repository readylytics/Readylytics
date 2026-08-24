package app.readylytics.health.feature.settings

/**
 * Wrapper for baselines/thresholds section state and callbacks to reduce parameter list length.
 */
internal data class BaselinesThresholdsContext(
    val thresholdState: ThresholdSettingsState,
    val sleepState: SleepSettingsState,
    val physiologyState: PhysiologySettingsState,
    val heartRateState: HeartRateZonesState,
    val uiState: UIState,
    val isResyncing: Boolean,
    val controlsEnabled: Boolean,
    val onThresholdEvent: (SettingsEvent) -> Unit,
    val onSleepEvent: (SettingsEvent) -> Unit,
    val onUIEvent: (SettingsEvent) -> Unit,
    val onPhysiologyEvent: (SettingsEvent) -> Unit,
    val onHeartRateEvent: (SettingsEvent) -> Unit,
)
