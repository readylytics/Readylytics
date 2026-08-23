package app.readylytics.health.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.common.resolveOrNull
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.core.ui.components.settings.PhysiologyProfilePicker
import app.readylytics.health.feature.settings.physiologyprofile.HeartRateZoneSection
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
internal fun ActivityThresholdsSubsection(
    stepGoal: Int,
    onUIEvent: (SettingsEvent) -> Unit,
) {
    SectionHeader(stringResource(R.string.label_daily_step_goal))
    ActivitySettingsSection(stepGoal = stepGoal, onEvent = onUIEvent)
    Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
}

@Composable
internal fun SleepThresholdsSubsection(
    sleepState: SleepSettingsState,
    onSleepEvent: (SettingsEvent) -> Unit,
    isResyncing: Boolean,
) {
    SectionHeader(stringResource(R.string.label_sleep))
    SleepSettingsSection(
        uiState = sleepState,
        onEvent = onSleepEvent,
        isResyncing = isResyncing,
    )
    Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
}

@Composable
internal fun HeartRateProfileSubsection(
    heartRateState: HeartRateZonesState,
    physiologyState: PhysiologySettingsState,
    onHeartRateEvent: (SettingsEvent) -> Unit,
    onPhysiologyEvent: (SettingsEvent) -> Unit,
    isResyncing: Boolean,
    controlsEnabled: Boolean,
) {
    SectionHeader(stringResource(R.string.settings_sub_heart_rate_zones))
    HeartRateZoneSection(
        uiState = heartRateState,
        physiologyState = physiologyState,
        onEvent = onHeartRateEvent,
        onPhysiologyEvent = onPhysiologyEvent,
        isResyncing = isResyncing,
    )
    Spacer(modifier = Modifier.height(MaterialTheme.spacing.pageSectionGap))
    PhysiologyProfilePicker(
        selectedProfile = physiologyState.physiologyProfile,
        onProfileSelected = { onPhysiologyEvent(SettingsEvent.PhysiologyProfileChanged(it)) },
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
        enabled = controlsEnabled,
    )
    Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
}

@Composable
internal fun LoadSourcesTolerance(
    sleepState: SleepSettingsState,
    onSleepEvent: (SettingsEvent) -> Unit,
    isResyncing: Boolean,
) {
    SectionHeader(stringResource(R.string.load_sources_section_title))
    LoadSourcesSection(
        uiState = sleepState,
        onEvent = onSleepEvent,
        isResyncing = isResyncing,
    )
    Spacer(modifier = Modifier.height(MaterialTheme.spacing.pageSectionGap))
}

@Composable
internal fun CircadianThresholdsSubsection(
    thresholdState: ThresholdSettingsState,
    physiologyState: PhysiologySettingsState,
    onThresholdEvent: (SettingsEvent) -> Unit,
    controlsEnabled: Boolean,
) {
    SectionHeader(stringResource(CoreUiR.string.label_circadian_consistency))
    CircadianThresholdSettingsSection(
        profile = physiologyState.physiologyProfile,
        currentOverride = thresholdState.circadianThresholdOverride,
        onOverrideChanged = { onThresholdEvent(SettingsEvent.CircadianThresholdOverrideChanged(it)) },
        isLoading = thresholdState.isUpdatingThreshold,
        error = thresholdState.thresholdError.resolveOrNull(),
        onErrorDismissed = { onThresholdEvent(SettingsEvent.DismissThresholdError) },
        enabled = controlsEnabled,
    )
    Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
    SectionHeader(stringResource(R.string.settings_sub_thresholds))
}
