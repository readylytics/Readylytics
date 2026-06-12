package app.readylytics.health.ui.theme

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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.data.preferences.AppTheme
import app.readylytics.health.data.preferences.FallbackThemeColor
import com.google.android.material.color.MaterialColors

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
        background = Color(0xFF121212),
        surface = Color(0xFF121212),
        onSurface = Color(0xFFE6E6E6),
        surfaceContainerLowest = Color(0xFF0F0F0F),
        surfaceContainerLow = Color(0xFF1A1A1A),
        surfaceContainer = Color(0xFF1F1F1F),
        surfaceContainerHigh = Color(0xFF262626),
        surfaceContainerHighest = Color(0xFF303030),
        surfaceVariant = Color(0xFF353535),
        onSurfaceVariant = Color(0xFFCCCCCC),
        outline = Color(0xFF8E8E8E),
        outlineVariant = Color(0xFF444444),
    )

private val LightColorScheme =
    lightColorScheme(
        primary = Purple40,
        secondary = PurpleGrey40,
        tertiary = Pink40,
        primaryContainer = PrimaryContainerLight,
        onPrimaryContainer = OnPrimaryContainerLight,
        background = Color(0xFFFFFFFF),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1A1A1A),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF5F5F5),
        surfaceContainer = Color(0xFFEEEEEE),
        surfaceContainerHigh = Color(0xFFE5E5E5),
        surfaceContainerHighest = Color(0xFFDCDCDC),
        surfaceVariant = Color(0xFFE5E5E5),
        onSurfaceVariant = Color(0xFF474747),
        outline = Color(0xFF757575),
        outlineVariant = Color(0xFFC6C6C6),
    )

private fun onColorFor(seed: Color): Color = if (seed.luminance() > 0.179f) Color.Black else Color.White

private fun fallbackLightScheme(
    seed: Color,
    secondarySeed: Color? = null,
    tertiarySeed: Color? = null,
): ColorScheme =
    colorSchemeFromSeed(
        primarySeed = seed,
        secondarySeed = secondarySeed,
        tertiarySeed = tertiarySeed,
        isDark = false,
    )

private fun fallbackDarkScheme(
    seed: Color,
    secondarySeed: Color? = null,
    tertiarySeed: Color? = null,
): ColorScheme =
    colorSchemeFromSeed(
        primarySeed = seed,
        secondarySeed = secondarySeed,
        tertiarySeed = tertiarySeed,
        isDark = true,
    )

fun calculateSecondarySeedColor(primary: Color): Color {
    val hsl = FloatArray(3)
    primary.toHsl(hsl)
    val sSat = maxOf(0.16f, hsl[1] * 0.35f)
    return hslToColor(hsl[0], sSat, hsl[2])
}

fun calculateTertiarySeedColor(primary: Color): Color {
    val hsl = FloatArray(3)
    primary.toHsl(hsl)
    val tHue = (hsl[0] + 60f) % 360f
    val tSat = maxOf(0.24f, hsl[1] * 0.5f)
    return hslToColor(tHue, tSat, hsl[2])
}

internal fun Color.toHsl(outHsl: FloatArray) {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, maxOf(g, b))
    val min = minOf(r, minOf(g, b))

    var h: Float
    val s: Float
    val l = (max + min) / 2f

    if (max == min) {
        h = 0f
        s = 0f
    } else {
        val d = max - min
        s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        h =
            when (max) {
                r -> (g - b) / d + (if (g < b) 6f else 0f)
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            }
        h /= 6f
    }

    outHsl[0] = h * 360f
    outHsl[1] = s
    outHsl[2] = l
}

internal fun hslToColor(
    h: Float,
    s: Float,
    l: Float,
    alpha: Float = 1f,
): Color {
    val r: Float
    val g: Float
    val b: Float

    if (s == 0f) {
        r = l
        g = l
        b = l
    } else {
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        r = hueToRgb(p, q, h / 360f + 1f / 3f)
        g = hueToRgb(p, q, h / 360f)
        b = hueToRgb(p, q, h / 360f - 1f / 3f)
    }

    return Color(
        red = r.coerceIn(0f, 1f),
        green = g.coerceIn(0f, 1f),
        blue = b.coerceIn(0f, 1f),
        alpha = alpha,
    )
}

private fun hueToRgb(
    p: Float,
    q: Float,
    t: Float,
): Float {
    var varT = t
    if (varT < 0f) varT += 1f
    if (varT > 1f) varT -= 1f
    if (varT < 1f / 6f) return p + (q - p) * 6f * varT
    if (varT < 1f / 2f) return q
    if (varT < 2f / 3f) return p + (q - p) * (2f / 3f - varT) * 6f
    return p
}

private fun colorSchemeFromSeed(
    primarySeed: Color,
    secondarySeed: Color?,
    tertiarySeed: Color?,
    isDark: Boolean,
): ColorScheme {
    val hsl = FloatArray(3)
    primarySeed.toHsl(hsl)
    val hue = hsl[0]

    // Neutral palette: pure gray (0% saturation) for untinted backgrounds/surfaces
    val nSat = 0f
    // Neutral Variant palette: pure gray (0% saturation) for untinted surface variants/borders
    val nvSat = 0f

    fun n(tone: Int): Color = hslToColor(hue, nSat, tone / 100f)

    fun nv(tone: Int): Color = hslToColor(hue, nvSat, tone / 100f)

    val pSat = maxOf(0.40f, hsl[1])

    fun p(tone: Int): Color = hslToColor(hue, pSat, tone / 100f)

    val sHue: Float
    val sSat: Float
    if (secondarySeed != null) {
        val sHsl = FloatArray(3)
        secondarySeed.toHsl(sHsl)
        sHue = sHsl[0]
        sSat = sHsl[1]
    } else {
        sHue = hue
        sSat = maxOf(0.16f, hsl[1] * 0.35f)
    }

    fun s(tone: Int): Color = hslToColor(sHue, sSat, tone / 100f)

    val tHueVal: Float
    val tSatVal: Float
    if (tertiarySeed != null) {
        val tHsl = FloatArray(3)
        tertiarySeed.toHsl(tHsl)
        tHueVal = tHsl[0]
        tSatVal = tHsl[1]
    } else {
        tHueVal = (hue + 60f) % 360f
        tSatVal = maxOf(0.24f, hsl[1] * 0.5f)
    }

    fun t(tone: Int): Color = hslToColor(tHueVal, tSatVal, tone / 100f)

    fun e(tone: Int): Color = hslToColor(0f, 0.85f, tone / 100f)

    return if (isDark) {
        val primaryColor = p(80)
        darkColorScheme(
            primary = primaryColor,
            onPrimary = onColorFor(primaryColor),
            primaryContainer = p(30),
            onPrimaryContainer = p(90),
            inversePrimary = p(40),
            secondary = s(80),
            onSecondary = s(20),
            secondaryContainer = s(30),
            onSecondaryContainer = s(90),
            tertiary = t(80),
            onTertiary = t(20),
            tertiaryContainer = t(30),
            onTertiaryContainer = t(90),
            background = Color(0xFF0A0A0A),
            onBackground = n(90),
            surface = Color(0xFF0A0A0A),
            onSurface = n(90),
            surfaceVariant = nv(30),
            onSurfaceVariant = nv(80),
            surfaceTint = primaryColor,
            inverseSurface = n(90),
            inverseOnSurface = n(10),
            outline = nv(50),
            outlineVariant = nv(30),
            error = e(80),
            onError = e(20),
            errorContainer = e(30),
            onErrorContainer = e(90),
            surfaceContainerLowest = n(4),
            surfaceContainerLow = n(10),
            surfaceContainer = n(12),
            surfaceContainerHigh = n(17),
            surfaceContainerHighest = n(22),
        )
    } else {
        val primaryColor = p(40)
        lightColorScheme(
            primary = primaryColor,
            onPrimary = onColorFor(primaryColor),
            primaryContainer = p(90),
            onPrimaryContainer = p(10),
            inversePrimary = p(80),
            secondary = s(40),
            onSecondary = s(100),
            secondaryContainer = s(90),
            onSecondaryContainer = s(10),
            tertiary = t(40),
            onTertiary = t(100),
            tertiaryContainer = t(90),
            onTertiaryContainer = t(10),
            background = Color(0xFFF5F5F5),
            onBackground = n(10),
            surface = Color(0xFFF5F5F5),
            onSurface = n(10),
            surfaceVariant = nv(90),
            onSurfaceVariant = nv(30),
            surfaceTint = primaryColor,
            inverseSurface = n(20),
            inverseOnSurface = n(95),
            outline = nv(50),
            outlineVariant = nv(80),
            error = e(40),
            onError = e(100),
            errorContainer = e(90),
            onErrorContainer = e(10),
            surfaceContainerLowest = n(100),
            surfaceContainerLow = n(96),
            surfaceContainer = n(94),
            surfaceContainerHigh = n(92),
            surfaceContainerHighest = n(90),
        )
    }
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
                initialValue = FallbackThemeColor.BRAND_PURPLE,
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

    val darkTheme =
        when (appTheme) {
            AppTheme.LIGHT -> false
            AppTheme.DARK -> true
            AppTheme.SYSTEM -> isSystemInDarkTheme()
        }

    val secondarySeed = if (isCustomPaletteEnabled) Color(customSecondaryColor) else null
    val tertiarySeed = if (isCustomPaletteEnabled) Color(customTertiaryColor) else null

    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme ->
                fallbackDarkScheme(
                    seed = Color(fallbackThemeColor.seedColor),
                    secondarySeed = secondarySeed,
                    tertiarySeed = tertiarySeed,
                )
            else ->
                fallbackLightScheme(
                    seed = Color(fallbackThemeColor.seedColor),
                    secondarySeed = secondarySeed,
                    tertiarySeed = tertiarySeed,
                )
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
