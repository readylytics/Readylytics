package app.readylytics.health.ui.theme

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.data.preferences.AppTheme
import app.readylytics.health.data.preferences.FallbackThemeColor
import app.readylytics.health.data.preferences.SettingsDefaults
import app.readylytics.health.ui.scaffold.ThemeViewModel
import app.readylytics.health.core.designsystem.FitDashboardTheme as CoreFitDashboardTheme

@Composable
fun FitDashboardTheme(
    appTheme: AppTheme = AppTheme.SYSTEM,
    viewModel: ThemeViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val dynamicColor = viewModel.dynamicColorFlow.collectAsStateWithLifecycle(initialValue = true).value
    val fallbackThemeColor =
        viewModel.fallbackThemeColorFlow
            .collectAsStateWithLifecycle(
                initialValue = FallbackThemeColor.GREEN_PERFORMANCE,
            ).value
    val isCustomPaletteEnabled =
        viewModel.isCustomPaletteEnabledFlow
            .collectAsStateWithLifecycle(
                initialValue = false,
            ).value
    val customSecondaryColor =
        viewModel.customSecondaryColorFlow
            .collectAsStateWithLifecycle(
                initialValue = 0L,
            ).value
    val customTertiaryColor =
        viewModel.customTertiaryColorFlow
            .collectAsStateWithLifecycle(
                initialValue = 0L,
            ).value
    val customPrimaryColor =
        viewModel.customPrimaryColorFlow
            .collectAsStateWithLifecycle(
                initialValue = SettingsDefaults.CUSTOM_PRIMARY_COLOR,
            ).value

    CoreFitDashboardTheme(
        appTheme = appTheme,
        dynamicColor = dynamicColor,
        fallbackThemeColor = fallbackThemeColor,
        isCustomPaletteEnabled = isCustomPaletteEnabled,
        customSecondaryColor = customSecondaryColor,
        customTertiaryColor = customTertiaryColor,
        customPrimaryColor = customPrimaryColor,
        content = content,
    )
}
