package app.readylytics.health.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.calculateSecondarySeedColor
import app.readylytics.health.core.designsystem.calculateTertiarySeedColor
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.data.preferences.AppTheme
import app.readylytics.health.core.model.domain.githubissue.GitHubIssueType
import app.readylytics.health.core.ui.components.DropdownPreferenceItem
import app.readylytics.health.core.ui.components.SettingsToggleItem
import app.readylytics.health.feature.settings.common.CustomColorPicker
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
internal fun DynamicColorSettings(
    uiState: UIState,
    onUIEvent: (SettingsEvent) -> Unit,
) {
    SettingsToggleItem(
        label = stringResource(CoreUiR.string.onboarding_dynamic_color_label),
        description = stringResource(CoreUiR.string.onboarding_dynamic_color_desc),
        checked = uiState.dynamicColorEnabled,
        onCheckedChange = { onUIEvent(SettingsEvent.DynamicColorEnabledChanged(it)) },
    )
    AnimatedVisibility(visible = !uiState.dynamicColorEnabled) {
        Column {
            CustomColorPicker(
                label = stringResource(R.string.fallback_theme_color_label),
                selectedColor = Color(uiState.customPrimaryColor),
                onColorSelected = { onUIEvent(SettingsEvent.CustomPrimaryColorChanged(it.toArgb().toLong())) },
                enabled = true,
                modifier =
                    Modifier.fillMaxWidth().padding(
                        horizontal = MaterialTheme.spacing.pageHorizontal,
                        vertical = MaterialTheme.spacing.small,
                    ),
            )
            PaletteCustomizationSettings(uiState = uiState, onUIEvent = onUIEvent)
        }
    }
}

@Composable
private fun PaletteCustomizationSettings(
    uiState: UIState,
    onUIEvent: (SettingsEvent) -> Unit,
) {
    SettingsToggleItem(
        label = stringResource(R.string.settings_customize_palette_label),
        description = stringResource(R.string.settings_customize_palette_desc),
        checked = uiState.isCustomPaletteEnabled,
        onCheckedChange = { onUIEvent(SettingsEvent.CustomPaletteEnabledChanged(it)) },
    )
    val primarySeed = Color(uiState.customPrimaryColor)
    val currentSecondary =
        if (uiState.isCustomPaletteEnabled) {
            Color(uiState.customSecondaryColor)
        } else {
            calculateSecondarySeedColor(primarySeed)
        }
    val currentTertiary =
        if (uiState.isCustomPaletteEnabled) {
            Color(uiState.customTertiaryColor)
        } else {
            calculateTertiarySeedColor(primarySeed)
        }
    CustomColorPicker(
        label = stringResource(R.string.settings_secondary_color_label),
        selectedColor = currentSecondary,
        onColorSelected = { onUIEvent(SettingsEvent.CustomSecondaryColorChanged(it.toArgb().toLong())) },
        enabled = uiState.isCustomPaletteEnabled,
        modifier =
            Modifier.fillMaxWidth().padding(
                horizontal = MaterialTheme.spacing.pageHorizontal,
                vertical = MaterialTheme.spacing.small,
            ),
        onReset = {
            onUIEvent(
                SettingsEvent.CustomSecondaryColorChanged(
                    calculateSecondarySeedColor(primarySeed).toArgb().toLong(),
                ),
            )
        },
        showPresets = false,
    )
    CustomColorPicker(
        label = stringResource(R.string.settings_tertiary_color_label),
        selectedColor = currentTertiary,
        onColorSelected = { onUIEvent(SettingsEvent.CustomTertiaryColorChanged(it.toArgb().toLong())) },
        enabled = uiState.isCustomPaletteEnabled,
        modifier =
            Modifier.fillMaxWidth().padding(
                horizontal = MaterialTheme.spacing.pageHorizontal,
                vertical = MaterialTheme.spacing.small,
            ),
        onReset = {
            onUIEvent(
                SettingsEvent.CustomTertiaryColorChanged(
                    calculateTertiarySeedColor(primarySeed).toArgb().toLong(),
                ),
            )
        },
        showPresets = false,
    )
}

@Composable
internal fun IssueReportingSection(onReportTypeSelected: (GitHubIssueType) -> Unit) {
    Column {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier =
                Modifier.clickable {
                    onReportTypeSelected(GitHubIssueType.BUG_REPORT)
                },
        ) {
            Text(
                text = stringResource(R.string.settings_item_report_bug),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = MaterialTheme.spacing.extraSmall),
        )
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier =
                Modifier.clickable {
                    onReportTypeSelected(GitHubIssueType.FEATURE_REQUEST)
                },
        ) {
            Text(
                text = stringResource(R.string.settings_item_request_feature),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
internal fun MiscellaneousSection(
    onNavigateToAbout: () -> Unit,
    onNavigateToLicenses: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onOpenSourceCode: () -> Unit,
) {
    Column {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { onNavigateToAbout() },
        ) {
            Text(
                text = stringResource(R.string.settings_about_button),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.spacing.extraSmall))
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { onNavigateToLicenses() },
        ) {
            Text(
                text = stringResource(R.string.settings_item_licenses),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.spacing.extraSmall))
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { onOpenPrivacyPolicy() },
        ) {
            Text(
                text = stringResource(R.string.settings_item_privacy_policy),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.spacing.extraSmall))
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { onOpenSourceCode() },
        ) {
            Text(
                text = stringResource(R.string.settings_item_source_code),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
internal fun AppThemeItem(
    uiState: UIState,
    onEvent: (SettingsEvent) -> Unit,
) {
    DropdownPreferenceItem(
        label = stringResource(R.string.settings_label_app_theme),
        selectedDisplayValue =
            uiState.appTheme.name
                .lowercase()
                .replaceFirstChar { it.uppercase() },
        options = AppTheme.entries,
        onOptionSelected = { onEvent(SettingsEvent.AppThemeChanged(it)) },
        optionLabel = { it.name.lowercase().replaceFirstChar { it.uppercase() } },
        modifier =
            Modifier.fillMaxWidth().padding(
                horizontal = MaterialTheme.spacing.pageHorizontal,
                vertical = MaterialTheme.spacing.small,
            ),
    )
}
