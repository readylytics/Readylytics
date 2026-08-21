package app.readylytics.health.feature.onboarding

import java.time.LocalDate
import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import app.readylytics.health.core.model.data.preferences.UnitSystem

/**
 * Data class bundling all UI state and callbacks for the profile setup screen.
 * Used to reduce the parameter count of [ProfileSetupContent] to satisfy Detekt.
 */
internal data class ProfileSetupState(
    val birthDate: LocalDate,
    val onBirthDateChange: (LocalDate) -> Unit,
    val showBirthdatePicker: Boolean,
    val onShowBirthdatePickerChange: (Boolean) -> Unit,
    val gender: String,
    val onGenderChange: (String) -> Unit,
    val physiologyProfile: PhysiologyProfile,
    val onPhysiologyProfileChange: (PhysiologyProfile) -> Unit,
    val heightCm: Float?,
    val onHeightChange: (Float?) -> Unit,
    val heightHasError: Boolean,
    val onHeightErrorChange: (Boolean) -> Unit,
    val dynamicColorEnabled: Boolean,
    val onDynamicColorEnabledChange: (Boolean) -> Unit,
    val unitSystem: UnitSystem,
    val onUnitSystemChange: (UnitSystem) -> Unit,
    val onSubmit: () -> Unit,
)
