package app.readylytics.health.feature.onboarding

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import app.readylytics.health.core.ui.components.SettingsToggleItem
import app.readylytics.health.core.ui.components.settings.BirthdayDatePickerField
import app.readylytics.health.core.ui.components.settings.PhysiologyProfilePicker
import app.readylytics.health.core.ui.settings.HeightInputField
import app.readylytics.health.core.ui.settings.common.UnitSystemSelector
import app.readylytics.health.data.preferences.PhysiologyProfile
import app.readylytics.health.data.preferences.UnitSystem
import app.readylytics.health.feature.onboarding.R
import java.time.LocalDate
import app.readylytics.health.core.ui.R as CoreR

@Composable
fun OnboardingScreen(
    onGrantPermissionsClick: (
        birthDate: LocalDate,
        gender: String,
        physiologyProfile: PhysiologyProfile,
        dynamicColorEnabled: Boolean,
        unitSystem: UnitSystem,
        heightCm: Float?,
    ) -> Unit,
    onOpenSettingsClick: () -> Unit,
    restoreState: OnboardingRestoreState,
    onRestoreBackupClick: (uri: Uri, password: String) -> Unit,
    onDismissRestoreError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }

    Surface(modifier = modifier.fillMaxSize()) {
        when (step) {
            0 ->
                WelcomeScreen(
                    onNext = { step = 1 },
                    onRestoreFromBackupClick = { step = 2 },
                )
            2 ->
                RestoreBackupScreen(
                    state = restoreState,
                    onRestoreClick = onRestoreBackupClick,
                    onDismissError = onDismissRestoreError,
                    onBack = { step = 0 },
                )
            else ->
                ProfileSetupScreen(
                    onGrantPermissionsClick = onGrantPermissionsClick,
                    onOpenSettingsClick = onOpenSettingsClick,
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
fun FinishingSetupScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))
        Text(
            text = stringResource(R.string.onboarding_finishing_setup),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun PermissionsRequiredScreen(
    onGrantPermissionsClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
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

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))

        Button(
            onClick = onGrantPermissionsClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_grant_permissions_retry))
        }

        Spacer(Modifier.height(MaterialTheme.spacing.small))

        TextButton(onClick = onOpenSettingsClick) {
            Text(stringResource(R.string.onboarding_open_hc_settings))
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
private fun ProfileSetupScreen(
    onGrantPermissionsClick: (
        birthDate: LocalDate,
        gender: String,
        physiologyProfile: PhysiologyProfile,
        dynamicColorEnabled: Boolean,
        unitSystem: UnitSystem,
        heightCm: Float?,
    ) -> Unit,
    onOpenSettingsClick: () -> Unit,
) {
    var birthDate by remember { mutableStateOf(LocalDate.now().minusYears(30)) }
    var showBirthdatePicker by remember { mutableStateOf(false) }
    // Domain keys: these English strings are compared with stored/domain values — do NOT translate here
    var gender by remember { mutableStateOf("Other") }
    var physiologyProfile by remember { mutableStateOf(PhysiologyProfile.ACTIVE) }
    var dynamicColorEnabled by remember { mutableStateOf(true) }
    var unitSystem by remember { mutableStateOf(UnitSystem.METRIC) }
    var heightCm: Float? by remember { mutableStateOf(null) }
    var heightHasError by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.pageSectionGapLarge)
                .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
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

        // Original gap was 20.dp; large (24dp) is a deliberate +4dp rounding, no exact token exists for 20dp.
        Spacer(Modifier.height(MaterialTheme.spacing.large))

        Text(
            text = stringResource(R.string.onboarding_activity_profile_label),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

        PhysiologyProfilePicker(
            selectedProfile = physiologyProfile,
            onProfileSelected = { physiologyProfile = it },
        )

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))

        BirthdayDatePickerField(
            birthDate = birthDate,
            onDateSelected = { birthDate = it },
            showDialog = showBirthdatePicker,
            onDialogDismiss = { showBirthdatePicker = false },
            onFieldClick = { showBirthdatePicker = true },
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
            // onClick uses English domain key; label uses translated string resource
            RadioButton(selected = gender == "Male", onClick = { gender = "Male" })
            Text(stringResource(CoreR.string.gender_male), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(MaterialTheme.spacing.pageSectionGapSmall))
            RadioButton(selected = gender == "Female", onClick = { gender = "Female" })
            Text(stringResource(CoreR.string.gender_female), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(MaterialTheme.spacing.pageSectionGapSmall))
            RadioButton(selected = gender == "Other", onClick = { gender = "Other" })
            Text(stringResource(CoreR.string.gender_other), style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))

        HeightInputField(
            heightCm = heightCm,
            onHeightChange = { heightCm = it },
            unitSystem = unitSystem,
            onHasErrorChange = { heightHasError = it },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))

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
            onCheckedChange = { dynamicColorEnabled = it },
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
            onUnitSelected = { unitSystem = it },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))

        Text(
            text = stringResource(R.string.onboarding_hc_permissions_label),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(MaterialTheme.spacing.extraSmall))
        Text(
            text = stringResource(R.string.onboarding_hc_permissions_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))

        val isInputValid =
            !birthDate.isAfter(LocalDate.now()) &&
                birthDate.year in 1900..LocalDate.now().year &&
                !heightHasError

        Button(
            onClick = {
                onGrantPermissionsClick(
                    birthDate,
                    gender,
                    physiologyProfile,
                    dynamicColorEnabled,
                    unitSystem,
                    heightCm,
                )
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = isInputValid,
        ) {
            Text(stringResource(R.string.onboarding_grant_access))
        }

        Spacer(Modifier.height(MaterialTheme.spacing.small))

        TextButton(onClick = onOpenSettingsClick) {
            Text(stringResource(R.string.onboarding_open_hc_settings))
        }
    }
}
