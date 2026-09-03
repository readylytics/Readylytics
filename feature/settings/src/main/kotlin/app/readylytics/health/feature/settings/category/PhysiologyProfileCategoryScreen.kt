package app.readylytics.health.feature.settings.category

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import app.readylytics.health.core.ui.components.settings.PhysiologyProfilePicker
import app.readylytics.health.feature.settings.R
import app.readylytics.health.feature.settings.SettingsEvent
import app.readylytics.health.feature.settings.SettingsIntents
import app.readylytics.health.feature.settings.SettingsStates
import app.readylytics.health.feature.settings.nav.SettingsCategoryId
import app.readylytics.health.feature.settings.nav.SettingsCategoryListItem
import app.readylytics.health.feature.settings.nav.SettingsCategoryScaffold
import app.readylytics.health.feature.settings.physiologyprofile.HeartRateZoneSection
import app.readylytics.health.feature.settings.search.SettingsItemIds

@Composable
internal fun PhysiologyProfileCategoryScreen(
    states: SettingsStates,
    intents: SettingsIntents,
    controlsEnabled: Boolean,
    highlightItemId: String?,
    onNavigateBack: () -> Unit = {},
) {
    SettingsCategoryScaffold(
        titleRes = SettingsCategoryId.PHYSIOLOGY_PROFILE.titleRes,
        items =
            listOf(
                SettingsCategoryListItem(SettingsItemIds.PHYSIOLOGY_PROFILE_PICKER) {
                    PhysiologyProfilePickerItem(
                        physiologyProfile = states.physiologyState.physiologyProfile,
                        controlsEnabled = controlsEnabled,
                        onPhysiologyEvent = intents.onPhysiologyEvent,
                    )
                },
                SettingsCategoryListItem(SettingsItemIds.PHYSIOLOGY_HR_ZONES) {
                    HeartRateZoneSection(
                        uiState = states.heartRateState,
                        physiologyState = states.physiologyState,
                        onEvent = intents.onHeartRateEvent,
                        onPhysiologyEvent = intents.onPhysiologyEvent,
                        isResyncing = states.syncState.isResyncing,
                    )
                },
            ),
        highlightItemId = highlightItemId,
        onNavigateBack = onNavigateBack,
    )
}

@Composable
private fun PhysiologyProfilePickerItem(
    physiologyProfile: PhysiologyProfile,
    controlsEnabled: Boolean,
    onPhysiologyEvent: (SettingsEvent) -> Unit,
) {
    PhysiologyProfilePicker(
        selectedProfile = physiologyProfile,
        onProfileSelected = { onPhysiologyEvent(SettingsEvent.PhysiologyProfileChanged(it)) },
        label = stringResource(R.string.physiology_profile_picker_label),
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
        enabled = controlsEnabled,
    )
}
