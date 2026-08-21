package app.readylytics.health.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import app.readylytics.health.core.model.data.preferences.UnitSystem
import app.readylytics.health.core.ui.components.SettingsToggleItem
import app.readylytics.health.core.ui.components.settings.BirthdayDatePickerField
import app.readylytics.health.core.ui.settings.common.UnitSystemSelector
import app.readylytics.health.feature.onboarding.R
import java.time.LocalDate
import app.readylytics.health.core.ui.R as CoreR

@Composable
fun OnboardingScreen(
    onProfileSetupComplete: (result: ProfileSetupResult, onComplete: () -> Unit) -> Unit,
    onRetentionSetupComplete: (retentionDays: Int) -> Unit,
    onOpenSettingsClick: () -> Unit,
    permissions: OnboardingPermissionsState,
    restoreCallbacks: OnboardingRestoreCallbacks,
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
private fun WelcomeScreen(
    onNext: () -> Unit,
    onRestoreFromBackupClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.pageSectionGapLarge)
                .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))

        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

        Text(
            text = stringResource(R.string.onboarding_welcome_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(MaterialTheme.spacing.extraLarge))

        WelcomeFeatureHighlights()

        Spacer(Modifier.height(MaterialTheme.spacing.extraLarge))

        Text(
            text = stringResource(R.string.onboarding_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(MaterialTheme.spacing.extraLarge))

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_get_started))
        }

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

        TextButton(
            onClick = onRestoreFromBackupClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_restore_backup_button))
        }
    }
}

@Composable
private fun WelcomeFeatureHighlights() {
    FeatureItem(
        icon = { Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = stringResource(R.string.onboarding_feature_sleep_title),
        description = stringResource(R.string.onboarding_feature_sleep_desc),
    )
    HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.spacing.extraSmall))
    FeatureItem(
        icon = {
            Icon(
                Icons.Filled.FavoriteBorder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        title = stringResource(R.string.onboarding_feature_hrv_title),
        description = stringResource(R.string.onboarding_feature_hrv_desc),
    )
    HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.spacing.extraSmall))
    FeatureItem(
        icon = {
            Icon(
                Icons.Filled.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
        },
        title = stringResource(R.string.onboarding_feature_training_title),
        description = stringResource(R.string.onboarding_feature_training_desc),
    )
}

@Composable
fun PermissionsRequiredScreen(
    onRecheckPermissionsClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    missingPermissions: Set<String> = emptySet(),
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.pageSectionGapLarge)
                .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_permissions_required_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

        Text(
            text = stringResource(R.string.onboarding_permissions_required_message),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val missingLabelRes = missingPermissions.mapNotNull { healthPermissionLabelRes(it) }
        if (missingLabelRes.isNotEmpty()) {
            Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))
            Text(
                text = stringResource(R.string.onboarding_missing_permissions_label),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(MaterialTheme.spacing.extraSmall))
            missingLabelRes.forEach { labelRes ->
                PermissionBulletRow(stringResource(labelRes), modifier = Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))

        Button(
            onClick = onOpenSettingsClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_open_hc_settings))
        }

        Spacer(Modifier.height(MaterialTheme.spacing.small))

        TextButton(onClick = onRecheckPermissionsClick) {
            Text(stringResource(R.string.onboarding_recheck_permissions))
        }
    }
}

@Composable
private fun FeatureItem(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        leadingContent = icon,
    )
}

@Composable
private fun ProfileSetupScreen(onNextClick: (result: ProfileSetupResult) -> Unit) {
    var birthDate by remember { mutableStateOf(LocalDate.now().minusYears(30)) }
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
    heightHasError: Boolean,
    onSubmit: () -> Unit,
) {
    val isInputValid =
        !birthDate.isAfter(LocalDate.now()) &&
            birthDate.year in 1900..LocalDate.now().year &&
            !heightHasError

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
    onBirthDateChange: (LocalDate) -> Unit,
    showBirthdatePicker: Boolean,
    onShowBirthdatePickerChange: (Boolean) -> Unit,
    gender: String,
    onGenderChange: (String) -> Unit,
) {
    BirthdayDatePickerField(
        birthDate = birthDate,
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
