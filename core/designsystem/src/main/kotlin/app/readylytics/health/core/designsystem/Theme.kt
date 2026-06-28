package app.readylytics.health.core.designsystem

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
import app.readylytics.health.data.preferences.AppTheme
import app.readylytics.health.data.preferences.FallbackThemeColor
import app.readylytics.health.data.preferences.SettingsDefaults

data class StatusColors(
    val optimal: Color,
    val neutral: Color,
    val warning: Color,
    val poor: Color,
)

// Fallback only. Live, theme-aware values (dark/light + dynamic color) are always supplied by
// [FitDashboardTheme]. A `staticCompositionLocalOf` default cannot read the runtime ColorScheme,
// so this mirrors the provider's light branch as a coherent fallback for the rare read outside
// the theme (e.g. an unwrapped @Preview) rather than the previous mismatched hardcoded values.
val LocalStatusColors =
    staticCompositionLocalOf {
        StatusColors(
            optimal = SuccessGreenLight,
            neutral = Color(0xFF0061A4), // M3 Blue 40 — matches FitDashboardTheme light branch
            warning = WarningOrangeLight,
            poor = Color(0xFFBA1A1A), // M3 Error 40
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

// Fallback only — see [LocalStatusColors]. Mirrors [FitDashboardTheme]'s light branch; the
// previous default used Dark variants, which is incoherent as a theme-agnostic fallback.
val LocalExtendedColors =
    staticCompositionLocalOf {
        ExtendedColors(
            success = SuccessGreenLight,
            onSuccess = OnSuccessGreenLight,
            successContainer = SuccessGreenContainerLight,
            onSuccessContainer = OnSuccessGreenContainerLight,
            warning = WarningOrangeLight,
            onWarning = OnWarningOrangeLight,
            warningContainer = WarningOrangeContainerLight,
            onWarningContainer = OnWarningOrangeContainerLight,
            neutralContainer = PrimaryContainerLight,
            onNeutralContainer = OnPrimaryContainerLight,
        )
    }

@Composable
fun FitDashboardTheme(
    appTheme: AppTheme = AppTheme.SYSTEM,
    dynamicColor: Boolean = true,
    fallbackThemeColor: FallbackThemeColor = FallbackThemeColor.GREEN_PERFORMANCE,
    isCustomPaletteEnabled: Boolean = false,
    customSecondaryColor: Long = 0L,
    customTertiaryColor: Long = 0L,
    customPrimaryColor: Long = SettingsDefaults.CUSTOM_PRIMARY_COLOR,
    content: @Composable () -> Unit,
) {
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
