package com.gregor.lauritz.healthdashboard.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.android.material.color.MaterialColors
import com.gregor.lauritz.healthdashboard.data.preferences.AppTheme
import com.gregor.lauritz.healthdashboard.data.preferences.FallbackThemeColor

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

fun Color.harmonizeWith(primary: Color): Color = Color(MaterialColors.harmonize(this.toArgb(), primary.toArgb()))

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

private val DarkColorScheme =
    darkColorScheme(
        primary = Purple80,
        secondary = PurpleGrey80,
        tertiary = Pink80,
        primaryContainer = PrimaryContainerDark,
        onPrimaryContainer = OnPrimaryContainerDark,
        surface = Color(0xFF141218),
        onSurface = Color(0xFFE6E1E5),
        surfaceContainerLowest = Color(0xFF0F0D13),
        surfaceContainerLow = Color(0xFF1D1B20),
        surfaceContainer = Color(0xFF211F26),
        surfaceContainerHigh = Color(0xFF2B2930),
        surfaceContainerHighest = Color(0xFF36343B),
        surfaceVariant = Color(0xFF49454F),
        onSurfaceVariant = Color(0xFFCAC4D0),
        outline = Color(0xFF938F99),
        outlineVariant = Color(0xFF49454F),
    )

private val LightColorScheme =
    lightColorScheme(
        primary = Purple40,
        secondary = PurpleGrey40,
        tertiary = Pink40,
        primaryContainer = PrimaryContainerLight,
        onPrimaryContainer = OnPrimaryContainerLight,
        surface = Color(0xFFFEF7FF),
        onSurface = Color(0xFF1D1B20),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF7F2FA),
        surfaceContainer = Color(0xFFF3EDF7),
        surfaceContainerHigh = Color(0xFFECE6F0),
        surfaceContainerHighest = Color(0xFFE6E0E9),
        surfaceVariant = Color(0xFFE7E0EC),
        onSurfaceVariant = Color(0xFF49454F),
        outline = Color(0xFF79747E),
        outlineVariant = Color(0xFFC4C7C5),
    )

private fun onColorFor(seed: Color): Color = if (seed.luminance() > 0.179f) Color.Black else Color.White

private fun fallbackLightScheme(seed: Color): ColorScheme =
    LightColorScheme.copy(
        primary = seed,
        onPrimary = onColorFor(seed),
        primaryContainer = PrimaryContainerLight.harmonizeWith(seed),
        onPrimaryContainer = OnPrimaryContainerLight,
        secondary = PurpleGrey40.harmonizeWith(seed),
        tertiary = Pink40.harmonizeWith(seed),
    )

private fun fallbackDarkScheme(seed: Color): ColorScheme =
    DarkColorScheme.copy(
        primary = seed,
        onPrimary = onColorFor(seed),
        primaryContainer = PrimaryContainerDark.harmonizeWith(seed),
        onPrimaryContainer = OnPrimaryContainerDark,
        secondary = PurpleGrey80.harmonizeWith(seed),
        tertiary = Pink80.harmonizeWith(seed),
    )

@Composable
fun FitDashboardTheme(
    appTheme: AppTheme = AppTheme.SYSTEM,
    viewModel: ThemeViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val dynamicColor = viewModel.dynamicColorFlow.collectAsState(initial = true).value
    val fallbackThemeColor =
        viewModel.fallbackThemeColorFlow.collectAsState(initial = FallbackThemeColor.BRAND_PURPLE).value
    val darkTheme =
        when (appTheme) {
            AppTheme.LIGHT -> false
            AppTheme.DARK -> true
            AppTheme.SYSTEM -> isSystemInDarkTheme()
        }

    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> fallbackDarkScheme(Color(fallbackThemeColor.seedColor))
            else -> fallbackLightScheme(Color(fallbackThemeColor.seedColor))
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
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
