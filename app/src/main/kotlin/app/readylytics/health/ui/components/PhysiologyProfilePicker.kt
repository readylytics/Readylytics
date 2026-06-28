package app.readylytics.health.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.readylytics.health.core.ui.components.DropdownPreferenceItem
import app.readylytics.health.data.preferences.PhysiologyProfile

@Composable
fun PhysiologyProfilePicker(
    selectedProfile: PhysiologyProfile,
    onProfileSelected: (PhysiologyProfile) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Activity Profile",
) {
    val profileLabel: (PhysiologyProfile) -> String = { profile ->
        when (profile) {
            PhysiologyProfile.ATHLETE -> "Athlete (competitive / structured training)"
            PhysiologyProfile.ACTIVE -> "Active (regular exercise)"
            PhysiologyProfile.SEDENTARY -> "Sedentary / low activity"
        }
    }

    DropdownPreferenceItem(
        label = label,
        selectedDisplayValue = profileLabel(selectedProfile),
        options = PhysiologyProfile.entries,
        optionLabel = profileLabel,
        onOptionSelected = onProfileSelected,
        modifier = modifier,
    )
}
