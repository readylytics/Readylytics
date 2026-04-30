package com.gregor.lauritz.healthdashboard.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.google.android.material.color.MaterialColors
import com.gregor.lauritz.healthdashboard.data.preferences.AppTheme

data class StatusColors(
    val optimal: Color,
    val neutral: Color,
    val warning: Color,
    val poor: Color,
)

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(
        optimal = Color(0xFF2E7D32),
        neutral = Color(0xFF1976D2),
        warning = Color(0xFFED6C02),
        poor = Color.Red
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

fun Color.harmonizeWith(primary: Color): Color {
    return Color(MaterialColors.harmonize(this.toArgb(), primary.toArgb()))
}

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
    )

private val LightColorScheme =
    lightColorScheme(
        primary = Purple40,
        secondary = PurpleGrey40,
        tertiary = Pink40,
        primaryContainer = PrimaryContainerLight,
        onPrimaryContainer = OnPrimaryContainerLight,
    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
     */
    )

@Composable
fun FitDashboardTheme(
    appTheme: AppTheme = AppTheme.SYSTEM,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
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

            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    val semanticColors = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        StatusColors(
            optimal = colorScheme.primary,
            neutral = colorScheme.secondary,
            warning = colorScheme.tertiary,
            poor = colorScheme.error
        )
    } else {
        if (darkTheme) {
            StatusColors(
                optimal = SuccessGreenDark,
                neutral = Color(0xFFD1E4FF), // M3 Blue 80
                warning = WarningOrangeDark,
                poor = colorScheme.error
            )
        } else {
            StatusColors(
                optimal = SuccessGreenLight,
                neutral = Color(0xFF0061A4), // M3 Blue 40
                warning = WarningOrangeLight,
                poor = colorScheme.error
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
        LocalStatusColors provides semanticColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
