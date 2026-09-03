package app.readylytics.health.feature.settings.category

import androidx.compose.runtime.Composable
import app.readylytics.health.feature.settings.BaselineOverridesSubsection
import app.readylytics.health.feature.settings.BodyTempElevatedThresholdItem
import app.readylytics.health.feature.settings.ConsistencyBaselineWindowItem
import app.readylytics.health.feature.settings.ConsistencyEvaluationPeriodItem
import app.readylytics.health.feature.settings.HrvOptimalThresholdItem
import app.readylytics.health.feature.settings.HrvWarningThresholdItem
import app.readylytics.health.feature.settings.RecoveryToleranceSubsection
import app.readylytics.health.feature.settings.RestingHrPercentileSubsection
import app.readylytics.health.feature.settings.RhrOptimalThresholdItem
import app.readylytics.health.feature.settings.RhrWarningThresholdItem
import app.readylytics.health.feature.settings.SettingsIntents
import app.readylytics.health.feature.settings.SettingsStates
import app.readylytics.health.feature.settings.nav.SettingsCategoryListItem
import app.readylytics.health.feature.settings.nav.SettingsCategoryScaffold
import app.readylytics.health.feature.settings.search.SettingsItemIds

@Composable
internal fun VitalsCategoryScreen(
    states: SettingsStates,
    intents: SettingsIntents,
    controlsEnabled: Boolean,
    highlightItemId: String?,
) {
    SettingsCategoryScaffold(
        items =
            vitalsSubsectionItems(states, intents, controlsEnabled) +
                vitalsThresholdItems(states, intents, controlsEnabled),
        highlightItemId = highlightItemId,
    )
}

private fun vitalsSubsectionItems(
    states: SettingsStates,
    intents: SettingsIntents,
    controlsEnabled: Boolean,
): List<SettingsCategoryListItem> {
    val sleepState = states.sleepState
    val uiState = states.uiState
    return listOf(
        SettingsCategoryListItem(SettingsItemIds.VITALS_BASELINE_OVERRIDES) {
            BaselineOverridesSubsection(
                sleepState = sleepState,
                controlsEnabled = controlsEnabled,
                onEvent = intents.onSleepEvent,
            )
        },
        SettingsCategoryListItem(SettingsItemIds.VITALS_RESTING_HR_PERCENTILE) {
            RestingHrPercentileSubsection(
                sleepState = sleepState,
                controlsEnabled = controlsEnabled,
                onEvent = intents.onSleepEvent,
            )
        },
        SettingsCategoryListItem(SettingsItemIds.VITALS_HRR_RECOVERY_TOLERANCE) {
            RecoveryToleranceSubsection(
                hrrToleranceSeconds = uiState.hrrToleranceSeconds,
                controlsEnabled = controlsEnabled,
                onUIEvent = intents.onUIEvent,
            )
        },
    )
}

private fun vitalsThresholdItems(
    states: SettingsStates,
    intents: SettingsIntents,
    controlsEnabled: Boolean,
): List<SettingsCategoryListItem> {
    val thresholdState = states.thresholdState
    return listOf(
        SettingsCategoryListItem(SettingsItemIds.VITALS_HRV_OPTIMAL_THRESHOLD) {
            HrvOptimalThresholdItem(thresholdState.hrvOptimalThreshold, controlsEnabled, intents.onThresholdEvent)
        },
        SettingsCategoryListItem(SettingsItemIds.VITALS_HRV_WARNING_THRESHOLD) {
            HrvWarningThresholdItem(thresholdState.hrvWarningThreshold, controlsEnabled, intents.onThresholdEvent)
        },
        SettingsCategoryListItem(SettingsItemIds.VITALS_RHR_OPTIMAL_THRESHOLD) {
            RhrOptimalThresholdItem(thresholdState.rhrOptimalThreshold, controlsEnabled, intents.onThresholdEvent)
        },
        SettingsCategoryListItem(SettingsItemIds.VITALS_RHR_WARNING_THRESHOLD) {
            RhrWarningThresholdItem(thresholdState.rhrWarningThreshold, controlsEnabled, intents.onThresholdEvent)
        },
        SettingsCategoryListItem(SettingsItemIds.VITALS_BODY_TEMP_THRESHOLD) {
            BodyTempElevatedThresholdItem(
                thresholdState.bodyTempElevatedThreshold,
                controlsEnabled,
                intents.onThresholdEvent,
            )
        },
        SettingsCategoryListItem(SettingsItemIds.VITALS_CONSISTENCY_EVALUATION_PERIOD) {
            ConsistencyEvaluationPeriodItem(
                thresholdState.consistencyEvaluationDays,
                controlsEnabled,
                intents.onThresholdEvent,
            )
        },
        SettingsCategoryListItem(SettingsItemIds.VITALS_CONSISTENCY_BASELINE_WINDOW) {
            ConsistencyBaselineWindowItem(
                thresholdState.consistencyBaselineDays,
                controlsEnabled,
                intents.onThresholdEvent,
            )
        },
    )
}
