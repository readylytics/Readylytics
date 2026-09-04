package app.readylytics.health.feature.settings.category

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import app.readylytics.health.core.model.domain.preferences.Vo2MaxEstimationMethod
import app.readylytics.health.core.model.domain.preferences.Vo2MaxSourceMode
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
                SettingsCategoryListItem(SettingsItemIds.PHYSIOLOGY_VO2_MAX_SOURCE) {
                    Vo2MaxSourcePicker(
                        selectedMode = states.physiologyState.vo2MaxSourceMode,
                        onModeSelected = { intents.onPhysiologyEvent(SettingsEvent.Vo2MaxSourceModeChanged(it)) },
                        enabled = controlsEnabled,
                    )
                },
                SettingsCategoryListItem(SettingsItemIds.PHYSIOLOGY_VO2_MAX_METHOD) {
                    Vo2MaxEstimationMethodPicker(
                        selectedMethod = states.physiologyState.vo2MaxEstimationMethod,
                        onMethodSelected = {
                            intents.onPhysiologyEvent(SettingsEvent.Vo2MaxEstimationMethodChanged(it))
                        },
                        enabled = controlsEnabled,
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

@Composable
private fun Vo2MaxSourcePicker(
    selectedMode: Vo2MaxSourceMode,
    onModeSelected: (Vo2MaxSourceMode) -> Unit,
    enabled: Boolean,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        Text(
            text = stringResource(R.string.vo2_max_source_title),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    SingleChoiceSegmentedButtonRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
    ) {
        Vo2MaxSourceMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selectedMode == mode,
                onClick = { onModeSelected(mode) },
                enabled = enabled,
                shape =
                    SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = Vo2MaxSourceMode.entries.size,
                    ),
                label = {
                    Text(
                        text =
                            when (mode) {
                                Vo2MaxSourceMode.AUTO ->
                                    stringResource(R.string.vo2_max_source_auto)
                                Vo2MaxSourceMode.WEARABLE_ONLY ->
                                    stringResource(R.string.vo2_max_source_wearable)
                                Vo2MaxSourceMode.ESTIMATED_ONLY ->
                                    stringResource(R.string.vo2_max_source_estimated)
                            },
                    )
                },
            )
        }
    }
    Text(
        text = stringResource(R.string.vo2_max_source_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier.padding(
                horizontal = MaterialTheme.spacing.pageHorizontal,
                vertical = MaterialTheme.spacing.small,
            ),
    )
}

@Composable
private fun Vo2MaxEstimationMethodPicker(
    selectedMethod: Vo2MaxEstimationMethod,
    onMethodSelected: (Vo2MaxEstimationMethod) -> Unit,
    enabled: Boolean,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        Text(
            text = stringResource(R.string.vo2_max_method_title),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    SingleChoiceSegmentedButtonRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
    ) {
        Vo2MaxEstimationMethod.entries.forEachIndexed { index, method ->
            SegmentedButton(
                selected = selectedMethod == method,
                onClick = { onMethodSelected(method) },
                enabled = enabled,
                shape =
                    SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = Vo2MaxEstimationMethod.entries.size,
                    ),
                label = {
                    Text(
                        text =
                            when (method) {
                                Vo2MaxEstimationMethod.HR_RATIO ->
                                    stringResource(R.string.vo2_max_method_hr_ratio)
                                Vo2MaxEstimationMethod.MATERKO_ADAPTED ->
                                    stringResource(R.string.vo2_max_method_materko_adapted)
                            },
                    )
                },
            )
        }
    }
    Text(
        text = stringResource(R.string.vo2_max_method_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier.padding(
                horizontal = MaterialTheme.spacing.pageHorizontal,
                vertical = MaterialTheme.spacing.small,
            ),
    )
}
