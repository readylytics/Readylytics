package app.readylytics.health.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.data.preferences.AppTheme
import app.readylytics.health.data.preferences.FallbackThemeColor
import app.readylytics.health.data.preferences.SettingsDefaults

data class StatusColors(
    val optimal: Color,
    val neutral: Color,
    val warning: Color,
    val poor: Color,
)

val LocalStatusColors =
    staticCompositionLocalOf {
        StatusColors(
            optimal = Color(0xFF2E7D32),
            neutral = Color(0xFF1976D2),
            warning = Color(0xFFED6C02),
            poor = Color.Red,
        )
    }

data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val neutralContainer: Color,
    val onNeutralContainer: Color,
)

val LocalExtendedColors =
    staticCompositionLocalOf {
        ExtendedColors(
            success = SuccessGreenDark,
            onSuccess = OnSuccessGreenDark,
            successContainer = SuccessGreenContainerDark,
            onSuccessContainer = OnSuccessGreenContainerDark,
            warning = WarningOrangeDark,
            onWarning = OnWarningOrangeDark,
            warningContainer = WarningOrangeContainerDark,
            onWarningContainer = OnWarningOrangeContainerDark,
            neutralContainer = PrimaryContainerDark,
            onNeutralContainer = OnPrimaryContainerDark,
        )
    }

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

    val darkTheme =
        when (appTheme) {
            AppTheme.LIGHT -> false
            AppTheme.DARK -> true
            AppTheme.SYSTEM -> isSystemInDarkTheme()
        }

    val secondarySeed = if (isCustomPaletteEnabled) Color(customSecondaryColor) else null
    val tertiarySeed = if (isCustomPaletteEnabled) Color(customTertiaryColor) else null

    val matchingPreset = FallbackThemeColor.entries.find { it.primaryColor == customPrimaryColor }

    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            matchingPreset != null -> {
                if (darkTheme) {
                    fallbackDarkScheme(
                        seed = Color(matchingPreset.primaryColor),
                        secondarySeed = Color(matchingPreset.secondaryColor),
                        tertiarySeed = Color(matchingPreset.tertiaryColor),
                    ).copy(
                        primary = Color(matchingPreset.primaryColor),
                        secondary = Color(matchingPreset.secondaryColor),
                        tertiary = Color(matchingPreset.tertiaryColor),
                    )
                } else {
                    fallbackLightScheme(
                        seed = Color(matchingPreset.primaryColor),
                        secondarySeed = Color(matchingPreset.secondaryColor),
                        tertiarySeed = Color(matchingPreset.tertiaryColor),
                    ).copy(
                        primary = Color(matchingPreset.primaryColor),
                        secondary = Color(matchingPreset.secondaryColor),
                        tertiary = Color(matchingPreset.tertiaryColor),
                    )
                }
            }

            else -> {
                mcuColorScheme(
                    seedColor = Color(customPrimaryColor),
                    secondaryColor = secondarySeed,
                    tertiaryColor = tertiarySeed,
                    isDark = darkTheme,
                )
            }
        }

    val semanticColors =
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            StatusColors(
                optimal = colorScheme.primary,
                neutral = colorScheme.secondary,
                warning = colorScheme.tertiary,
                poor = colorScheme.error,
            )
        } else {
            if (darkTheme) {
                StatusColors(
                    optimal = SuccessGreenDark,
                    neutral = Color(0xFFD1E4FF), // M3 Blue 80
                    warning = WarningOrangeDark,
                    poor = colorScheme.error,
                )
            } else {
                StatusColors(
                    optimal = SuccessGreenLight,
                    neutral = Color(0xFF0061A4), // M3 Blue 40
                    warning = WarningOrangeLight,
                    poor = colorScheme.error,
                )
            }
        }

    val baseExtended =
        if (darkTheme) {
            ExtendedColors(
                success = SuccessGreenDark,
                onSuccess = OnSuccessGreenDark,
                successContainer = SuccessGreenContainerDark,
                onSuccessContainer = OnSuccessGreenContainerDark,
                warning = WarningOrangeDark,
                onWarning = OnWarningOrangeDark,
                warningContainer = WarningOrangeContainerDark,
                onWarningContainer = OnWarningOrangeContainerDark,
                neutralContainer = colorScheme.primaryContainer,
                onNeutralContainer = colorScheme.onPrimaryContainer,
            )
        } else {
            ExtendedColors(
                success = SuccessGreenLight,
                onSuccess = OnSuccessGreenLight,
                successContainer = SuccessGreenContainerLight,
                onSuccessContainer = OnSuccessGreenContainerLight,
                warning = WarningOrangeLight,
                onWarning = OnWarningOrangeLight,
                warningContainer = WarningOrangeContainerLight,
                onWarningContainer = OnWarningOrangeContainerLight,
                neutralContainer = colorScheme.primaryContainer,
                onNeutralContainer = colorScheme.onPrimaryContainer,
            )
        }

    val extendedColors =
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val p = colorScheme.primary
            baseExtended.copy(
                success = baseExtended.success.harmonizeWith(p),
                successContainer = baseExtended.successContainer.harmonizeWith(p),
                warning = baseExtended.warning.harmonizeWith(p),
                warningContainer = baseExtended.warningContainer.harmonizeWith(p),
            )
        } else {
            baseExtended
        }

    CompositionLocalProvider(
        LocalExtendedColors provides extendedColors,
        LocalStatusColors provides semanticColors,
        LocalSpacing provides Spacing(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
