package app.readylytics.health.feature.onboarding

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import app.readylytics.health.core.model.data.preferences.UnitSystem
import app.readylytics.health.core.model.domain.validation.BirthdayBounds
import app.readylytics.health.core.ui.components.SettingsToggleItem
import app.readylytics.health.core.ui.components.settings.BirthdayDatePickerField
import app.readylytics.health.core.ui.settings.common.UnitSystemSelector
import app.readylytics.health.feature.onboarding.R
import java.time.LocalDate
import app.readylytics.health.core.ui.R as CoreR

private const val DEFAULT_AGE_YEARS = 30L

@Composable
fun OnboardingScreen(
    onProfileSetupComplete: (result: ProfileSetupResult, onComplete: () -> Unit) -> Unit,
    onRetentionSetupComplete: (retentionDays: Int) -> Unit,
    onOpenSettingsClick: () -> Unit,
    permissions: OnboardingPermissionsState,
    restoreCallbacks: OnboardingRestoreCallbacks,
    birthdayBounds: BirthdayBounds,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }

    Surface(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        when (step) {
            0 ->
                WelcomeScreen(
                    onNext = { step = 1 },
                    onRestoreFromBackupClick = { step = 2 },
                )
            2 ->
                RestoreBackupScreen(
                    state = restoreCallbacks.restoreState,
                    onRestoreClick = restoreCallbacks.onRestoreBackupClick,
                    onDismissError = restoreCallbacks.onDismissRestoreError,
                    onBack = { step = 0 },
                )
            3 ->
                RetentionSetupScreen(
                    onContinueClick = { retentionDays ->
                        onRetentionSetupComplete(retentionDays)
                    },
                    onOpenSettingsClick = onOpenSettingsClick,
                    requiredPermissions = permissions.requiredPermissions,
                    optionalPermissions = permissions.optionalPermissions,
                )
            else ->
                ProfileSetupScreen(
                    birthdayBounds = birthdayBounds,
                    onNextClick = { result ->
                        onProfileSetupComplete(result) {
                            step = 3
                        }
                    },
                )
        }
    }
}

@Composable
private fun ProfileSetupScreen(
    birthdayBounds: BirthdayBounds,
    onNextClick: (result: ProfileSetupResult) -> Unit,
) {
    var birthDate by remember { mutableStateOf(birthdayBounds.maxDate.minusYears(DEFAULT_AGE_YEARS)) }
    var showBirthdatePicker by remember { mutableStateOf(false) }
    var gender by remember { mutableStateOf("Other") }
    var physiologyProfile by remember { mutableStateOf(PhysiologyProfile.ACTIVE) }
    var dynamicColorEnabled by remember { mutableStateOf(true) }
    var unitSystem by remember { mutableStateOf(UnitSystem.METRIC) }
    var heightCm: Float? by remember { mutableStateOf(null) }
    var heightHasError by remember { mutableStateOf(false) }

    val onSubmit = {
        onNextClick(
            ProfileSetupResult(
                birthDate = birthDate,
                gender = gender,
                physiologyProfile = physiologyProfile,
                dynamicColorEnabled = dynamicColorEnabled,
                unitSystem = unitSystem,
                heightCm = heightCm,
            ),
        )
    }

    val state =
        ProfileSetupState(
            birthDate = birthDate,
            birthdayBounds = birthdayBounds,
            onBirthDateChange = { birthDate = it },
            showBirthdatePicker = showBirthdatePicker,
            onShowBirthdatePickerChange = { showBirthdatePicker = it },
            gender = gender,
            onGenderChange = { gender = it },
            physiologyProfile = physiologyProfile,
            onPhysiologyProfileChange = { physiologyProfile = it },
            heightCm = heightCm,
            onHeightChange = { heightCm = it },
            heightHasError = heightHasError,
            onHeightErrorChange = { heightHasError = it },
            dynamicColorEnabled = dynamicColorEnabled,
            onDynamicColorEnabledChange = { dynamicColorEnabled = it },
            unitSystem = unitSystem,
            onUnitSystemChange = { unitSystem = it },
            onSubmit = onSubmit,
        )
    ProfileSetupContent(state)
}

@Composable
internal fun ProfileSetupHeader() {
    Text(
        text = stringResource(R.string.onboarding_profile_title),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
    Text(
        text = stringResource(R.string.onboarding_profile_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(MaterialTheme.spacing.large))
    Text(
        text = stringResource(R.string.onboarding_activity_profile_label),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun ProfileSetupSubmitButton(
    birthDate: LocalDate,
    birthdayBounds: BirthdayBounds,
    heightHasError: Boolean,
    onSubmit: () -> Unit,
) {
    val isInputValid = birthDate in birthdayBounds && !heightHasError

    Button(
        onClick = onSubmit,
        modifier = Modifier.fillMaxWidth(),
        enabled = isInputValid,
    ) {
        Text(stringResource(R.string.onboarding_next))
    }
}

@Composable
internal fun BirthdayAndGenderFields(
    birthDate: LocalDate,
    birthdayBounds: BirthdayBounds,
    onBirthDateChange: (LocalDate) -> Unit,
    showBirthdatePicker: Boolean,
    onShowBirthdatePickerChange: (Boolean) -> Unit,
    gender: String,
    onGenderChange: (String) -> Unit,
) {
    BirthdayDatePickerField(
        birthDate = birthDate,
        bounds = birthdayBounds,
        onDateSelected = onBirthDateChange,
        showDialog = showBirthdatePicker,
        onDialogDismiss = { onShowBirthdatePickerChange(false) },
        onFieldClick = { onShowBirthdatePickerChange(true) },
    )

    Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))

    Text(
        text = stringResource(CoreR.string.label_gender),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = gender == "Male", onClick = { onGenderChange("Male") })
        Text(stringResource(CoreR.string.gender_male), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(MaterialTheme.spacing.pageSectionGapSmall))
        RadioButton(selected = gender == "Female", onClick = { onGenderChange("Female") })
        Text(stringResource(CoreR.string.gender_female), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(MaterialTheme.spacing.pageSectionGapSmall))
        RadioButton(selected = gender == "Other", onClick = { onGenderChange("Other") })
        Text(stringResource(CoreR.string.gender_other), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun AppearanceAndUnitsFields(
    dynamicColorEnabled: Boolean,
    onDynamicColorEnabledChange: (Boolean) -> Unit,
    unitSystem: UnitSystem,
    onUnitSystemChange: (UnitSystem) -> Unit,
) {
    Text(
        text = stringResource(R.string.onboarding_appearance_label),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

    SettingsToggleItem(
        label = stringResource(CoreR.string.onboarding_dynamic_color_label),
        description = stringResource(CoreR.string.onboarding_dynamic_color_desc),
        checked = dynamicColorEnabled,
        onCheckedChange = onDynamicColorEnabledChange,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))

    Text(
        text = stringResource(R.string.onboarding_units_label),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

    UnitSystemSelector(
        selectedUnit = unitSystem,
        onUnitSelected = onUnitSystemChange,
        modifier = Modifier.fillMaxWidth(),
    )
}
