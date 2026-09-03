package app.readylytics.health.feature.settings.category

import androidx.compose.runtime.Composable
import app.readylytics.health.feature.settings.ActivitySettingsSection
import app.readylytics.health.feature.settings.LoadSourcesSection
import app.readylytics.health.feature.settings.RasScalingSubsection
import app.readylytics.health.feature.settings.ResidualFatigueSubsection
import app.readylytics.health.feature.settings.SettingsIntents
import app.readylytics.health.feature.settings.SettingsStates
import app.readylytics.health.feature.settings.TrainingLoadSubsection
import app.readylytics.health.feature.settings.TrainingReadinessSubsection
import app.readylytics.health.feature.settings.nav.SettingsCategoryId
import app.readylytics.health.feature.settings.nav.SettingsCategoryListItem
import app.readylytics.health.feature.settings.nav.SettingsCategoryScaffold
import app.readylytics.health.feature.settings.search.SettingsItemIds

@Composable
internal fun TrainingCategoryScreen(
    states: SettingsStates,
    intents: SettingsIntents,
    controlsEnabled: Boolean,
    highlightItemId: String?,
) {
    val uiState = states.uiState
    val isResyncing = states.syncState.isResyncing
    SettingsCategoryScaffold(
        titleRes = SettingsCategoryId.TRAINING.titleRes,
        items =
            listOf(
                SettingsCategoryListItem(SettingsItemIds.TRAINING_STEP_GOAL) {
                    ActivitySettingsSection(stepGoal = uiState.stepGoal, onEvent = intents.onUIEvent)
                },
                SettingsCategoryListItem(SettingsItemIds.TRAINING_LOAD_SOURCES) {
                    LoadSourcesSection(
                        uiState = states.sleepState,
                        onEvent = intents.onSleepEvent,
                        isResyncing = isResyncing,
                    )
                },
                SettingsCategoryListItem(SettingsItemIds.TRAINING_RAS_SCALING) {
                    RasScalingSubsection(
                        rasScalingFactor = uiState.rasScalingFactor,
                        controlsEnabled = controlsEnabled,
                        onPhysiologyEvent = intents.onPhysiologyEvent,
                        onUIEvent = intents.onUIEvent,
                    )
                },
                SettingsCategoryListItem(SettingsItemIds.TRAINING_ADVANCED_LOAD) {
                    TrainingLoadSubsection(
                        uiState = uiState,
                        controlsEnabled = controlsEnabled,
                        isResyncing = isResyncing,
                        onUIEvent = intents.onUIEvent,
                    )
                },
                SettingsCategoryListItem(SettingsItemIds.TRAINING_RESIDUAL_FATIGUE) {
                    ResidualFatigueSubsection(
                        uiState = uiState,
                        controlsEnabled = controlsEnabled,
                        onUIEvent = intents.onUIEvent,
                    )
                },
                SettingsCategoryListItem(SettingsItemIds.TRAINING_READINESS_ADVANCED) {
                    TrainingReadinessSubsection(
                        uiState = uiState,
                        controlsEnabled = controlsEnabled,
                        isResyncing = isResyncing,
                        onUIEvent = intents.onUIEvent,
                    )
                },
            ),
        highlightItemId = highlightItemId,
    )
}
