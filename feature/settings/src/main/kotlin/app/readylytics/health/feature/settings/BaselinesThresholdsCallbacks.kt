package app.readylytics.health.feature.settings

/**
 * Wrapper for baselines/thresholds section event callbacks to reduce parameter list length.
 */
internal data class BaselinesThresholdsCallbacks(
    val onThresholdEvent: (SettingsEvent) -> Unit,
    val onSleepEvent: (SettingsEvent) -> Unit,
    val onPhysiologyEvent: (SettingsEvent) -> Unit,
    val onHeartRateEvent: (SettingsEvent) -> Unit,
)
