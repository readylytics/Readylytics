package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile

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
            PhysiologyProfile.GENERAL -> "General population"
            PhysiologyProfile.SEDENTARY -> "Sedentary / low activity"
            PhysiologyProfile.SHIFT_WORKER -> "Shift worker / irregular schedule"
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
