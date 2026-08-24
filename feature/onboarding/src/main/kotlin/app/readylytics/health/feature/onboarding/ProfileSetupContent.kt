package app.readylytics.health.feature.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.settings.PhysiologyProfilePicker
import app.readylytics.health.core.ui.settings.HeightInputField

/**
 * UI composable for the profile setup screen. Parameters are bundled in [ProfileSetupState]
 * to keep the function signature short and satisfy Detekt's max parameter rule.
 */
@Composable
internal fun ProfileSetupContent(state: ProfileSetupState) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.pageSectionGapLarge)
                .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ProfileSetupHeader()
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
        // Physiology profile picker
        PhysiologyProfilePicker(
            selectedProfile = state.physiologyProfile,
            onProfileSelected = state.onPhysiologyProfileChange,
        )
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))
        // Birthday and gender fields
        BirthdayAndGenderFields(
            birthDate = state.birthDate,
            onBirthDateChange = state.onBirthDateChange,
            showBirthdatePicker = state.showBirthdatePicker,
            onShowBirthdatePickerChange = state.onShowBirthdatePickerChange,
            gender = state.gender,
            onGenderChange = state.onGenderChange,
        )
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))
        // Height input
        HeightInputField(
            heightCm = state.heightCm,
            onHeightChange = state.onHeightChange,
            unitSystem = state.unitSystem,
            onHasErrorChange = state.onHeightErrorChange,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))
        // Appearance and unit system selectors
        AppearanceAndUnitsFields(
            dynamicColorEnabled = state.dynamicColorEnabled,
            onDynamicColorEnabledChange = state.onDynamicColorEnabledChange,
            unitSystem = state.unitSystem,
            onUnitSystemChange = state.onUnitSystemChange,
        )
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))
        // Submit button
        ProfileSetupSubmitButton(
            birthDate = state.birthDate,
            heightHasError = state.heightHasError,
            onSubmit = state.onSubmit,
        )
    }
}
