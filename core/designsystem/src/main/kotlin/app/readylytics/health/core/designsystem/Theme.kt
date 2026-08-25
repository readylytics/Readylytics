package app.readylytics.health.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.readylytics.health.core.model.data.preferences.AppTheme
import app.readylytics.health.core.model.data.preferences.FallbackThemeColor
import app.readylytics.health.core.model.data.preferences.SettingsDefaults

private data class ThemeHolder(
    val colorScheme: ColorScheme,
    val semanticColors: StatusColors,
    val extendedColors: ExtendedColors,
)

private data class ColorSchemeParams(
    val dynamicColor: Boolean,
    val darkTheme: Boolean,
    val customPrimaryColor: Long,
    val customSecondaryColor: Long,
    val customTertiaryColor: Long,
    val isCustomPaletteEnabled: Boolean,
    val fallbackThemeColor: FallbackThemeColor,
    val context: android.content.Context,
)

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

private fun resolveColorScheme(params: ColorSchemeParams): ColorScheme {
    val secondarySeed =
        if (params.isCustomPaletteEnabled) Color(params.customSecondaryColor) else null
    val tertiarySeed =
        if (params.isCustomPaletteEnabled) Color(params.customTertiaryColor) else null

    val matchingPreset =
        if (params.isCustomPaletteEnabled) {
            FallbackThemeColor.entries.find { it.primaryColor == params.customPrimaryColor }
        } else {
            params.fallbackThemeColor
        }

    return when {
        params.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (params.darkTheme) {
                dynamicDarkColorScheme(params.context)
            } else {
                dynamicLightColorScheme(params.context)
            }
        }

        matchingPreset != null -> {
            if (params.darkTheme) {
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
                seedColor = Color(params.customPrimaryColor),
                secondaryColor = secondarySeed,
                tertiaryColor = tertiarySeed,
                isDark = params.darkTheme,
            )
        }
    }
}

private fun resolveSemanticColors(
    dynamicColor: Boolean,
    darkTheme: Boolean,
    colorScheme: ColorScheme,
): StatusColors =
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

private fun resolveBaseExtendedColors(
    darkTheme: Boolean,
    colorScheme: ColorScheme,
): ExtendedColors =
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

private fun resolveExtendedColors(
    dynamicColor: Boolean,
    colorScheme: ColorScheme,
    baseExtended: ExtendedColors,
): ExtendedColors =
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

    val context = LocalContext.current

    val themeHolder =
        remember(
            darkTheme,
            dynamicColor,
            fallbackThemeColor,
            customPrimaryColor,
            customSecondaryColor,
            customTertiaryColor,
            isCustomPaletteEnabled,
            context,
        ) {
            val colorScheme =
                resolveColorScheme(
                    ColorSchemeParams(
                        dynamicColor = dynamicColor,
                        darkTheme = darkTheme,
                        customPrimaryColor = customPrimaryColor,
                        customSecondaryColor = customSecondaryColor,
                        customTertiaryColor = customTertiaryColor,
                        isCustomPaletteEnabled = isCustomPaletteEnabled,
                        fallbackThemeColor = fallbackThemeColor,
                        context = context,
                    ),
                )

            val semanticColors =
                resolveSemanticColors(
                    dynamicColor = dynamicColor,
                    darkTheme = darkTheme,
                    colorScheme = colorScheme,
                )

            val baseExtended =
                resolveBaseExtendedColors(
                    darkTheme = darkTheme,
                    colorScheme = colorScheme,
                )

            val extendedColors =
                resolveExtendedColors(
                    dynamicColor = dynamicColor,
                    colorScheme = colorScheme,
                    baseExtended = baseExtended,
                )

            ThemeHolder(
                colorScheme = colorScheme,
                semanticColors = semanticColors,
                extendedColors = extendedColors,
            )
        }

    val spacing = remember { Spacing() }
    val dimens = remember { Dimens() }

    CompositionLocalProvider(
        LocalExtendedColors provides themeHolder.extendedColors,
        LocalStatusColors provides themeHolder.semanticColors,
        LocalSpacing provides spacing,
        LocalDimens provides dimens,
    ) {
        MaterialTheme(
            colorScheme = themeHolder.colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
