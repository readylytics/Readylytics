package app.readylytics.health.feature.settings.category

import androidx.compose.runtime.Composable
import app.readylytics.health.core.ui.common.resolveOrNull
import app.readylytics.health.feature.settings.CircadianThresholdSettingsSection
import app.readylytics.health.feature.settings.SettingsEvent
import app.readylytics.health.feature.settings.SettingsIntents
import app.readylytics.health.feature.settings.SettingsStates
import app.readylytics.health.feature.settings.SleepArchitectureCoverageItem
import app.readylytics.health.feature.settings.SleepCoreMergeGapItem
import app.readylytics.health.feature.settings.SleepGoalItem
import app.readylytics.health.feature.settings.SleepHypersomniaOnsetItem
import app.readylytics.health.feature.settings.SleepMinimumSegmentItem
import app.readylytics.health.feature.settings.SleepRecalculateScoresItem
import app.readylytics.health.feature.settings.SleepSupplementalCutoffItem
import app.readylytics.health.feature.settings.SleepWeightProfileItem
import app.readylytics.health.feature.settings.nav.SettingsCategoryListItem
import app.readylytics.health.feature.settings.nav.SettingsCategoryScaffold
import app.readylytics.health.feature.settings.search.SettingsItemIds

@Composable
internal fun SleepCategoryScreen(
    states: SettingsStates,
    intents: SettingsIntents,
    controlsEnabled: Boolean,
    highlightItemId: String?,
) {
    SettingsCategoryScaffold(
        items =
            sleepScoringItems(states, intents, controlsEnabled) + sleepDetectionItems(states, intents, controlsEnabled),
        highlightItemId = highlightItemId,
    )
}

private fun sleepScoringItems(
    states: SettingsStates,
    intents: SettingsIntents,
    controlsEnabled: Boolean,
): List<SettingsCategoryListItem> {
    val sleepState = states.sleepState
    return listOf(
        SettingsCategoryListItem(SettingsItemIds.SLEEP_GOAL) {
            SleepGoalItem(sleepState.goalSleepHours, controlsEnabled, intents.onSleepEvent)
        },
        SettingsCategoryListItem(SettingsItemIds.SLEEP_WEIGHT_PROFILE) {
            SleepWeightProfileItem(sleepState.sleepScoreWeightProfile, controlsEnabled, intents.onSleepEvent)
        },
        SettingsCategoryListItem(SettingsItemIds.SLEEP_HYPERSOMNIA_ONSET) {
            SleepHypersomniaOnsetItem(sleepState.hypersomniaOnsetPercent, controlsEnabled, intents.onSleepEvent)
        },
        SettingsCategoryListItem(SettingsItemIds.SLEEP_RECALCULATE_SCORES) {
            SleepRecalculateScoresItem(
                sleepState.hasPendingSleepScoreRecalc,
                controlsEnabled,
                states.syncState.isResyncing,
                intents.onSleepEvent,
            )
        },
    )
}

private fun sleepDetectionItems(
    states: SettingsStates,
    intents: SettingsIntents,
    controlsEnabled: Boolean,
): List<SettingsCategoryListItem> {
    val sleepState = states.sleepState
    val thresholdState = states.thresholdState
    val physiologyState = states.physiologyState
    return listOf(
        SettingsCategoryListItem(SettingsItemIds.SLEEP_CORE_MERGE_GAP) {
            SleepCoreMergeGapItem(sleepState.coreMergeGapMinutes, controlsEnabled, intents.onSleepEvent)
        },
        SettingsCategoryListItem(SettingsItemIds.SLEEP_SUPPLEMENTAL_CUTOFF) {
            SleepSupplementalCutoffItem(
                sleepState.supplementalCutoffMinutesOfDay,
                controlsEnabled,
                intents.onSleepEvent,
            )
        },
        SettingsCategoryListItem(SettingsItemIds.SLEEP_MINIMUM_SEGMENT) {
            SleepMinimumSegmentItem(
                sleepState.minimumCountedSleepSegmentMinutes,
                controlsEnabled,
                intents.onSleepEvent,
            )
        },
        SettingsCategoryListItem(SettingsItemIds.SLEEP_ARCHITECTURE_COVERAGE) {
            SleepArchitectureCoverageItem(
                sleepState.supplementalArchitectureCoveragePercent,
                controlsEnabled,
                intents.onSleepEvent,
            )
        },
        SettingsCategoryListItem(SettingsItemIds.SLEEP_CIRCADIAN_CONSISTENCY) {
            CircadianThresholdSettingsSection(
                profile = physiologyState.physiologyProfile,
                currentOverride = thresholdState.circadianThresholdOverride,
                onOverrideChanged = {
                    intents.onThresholdEvent(SettingsEvent.CircadianThresholdOverrideChanged(it))
                },
                isLoading = thresholdState.isUpdatingThreshold,
                error = thresholdState.thresholdError.resolveOrNull(),
                onErrorDismissed = { intents.onThresholdEvent(SettingsEvent.DismissThresholdError) },
                enabled = controlsEnabled,
            )
        },
    )
}
