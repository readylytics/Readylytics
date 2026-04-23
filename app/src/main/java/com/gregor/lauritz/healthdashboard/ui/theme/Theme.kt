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
import androidx.compose.ui.platform.LocalContext
import com.gregor.lauritz.healthdashboard.data.preferences.AppTheme

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

    val extendedColors =
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
                neutralContainer = PrimaryContainerDark,
                onNeutralContainer = OnPrimaryContainerDark,
            )
        } else {
            ExtendedColors(
                success = SuccessGreenLight,
                onSuccess = OnSuccessGreenLight,
                successContainer = SuccessGreenContainerLight,
                onSuccessContainer = OnSuccessGreenContainerLight,
                warning = WarningOrangeDark,
                onWarning = OnWarningOrangeDark,
                warningContainer = WarningOrangeContainerDark,
                onWarningContainer = OnWarningOrangeContainerDark,
                neutralContainer = PrimaryContainerLight,
                onNeutralContainer = OnPrimaryContainerLight,
            )
        }

    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
