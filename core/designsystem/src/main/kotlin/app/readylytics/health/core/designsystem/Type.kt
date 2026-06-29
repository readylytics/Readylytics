package app.readylytics.health.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.readylytics.health.core.designsystem.R

@OptIn(ExperimentalTextApi::class)
val GoogleSansFlex =
    FontFamily(
        Font(
            resId = R.font.google_sans_flex,
            weight = FontWeight.Light,
            variationSettings = FontVariation.Settings(FontVariation.weight(300)),
        ),
        Font(
            resId = R.font.google_sans_flex,
            weight = FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400)),
        ),
        Font(
            resId = R.font.google_sans_flex,
            weight = FontWeight.Medium,
            variationSettings = FontVariation.Settings(FontVariation.weight(500)),
        ),
        Font(
            resId = R.font.google_sans_flex,
            weight = FontWeight.SemiBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(600)),
        ),
        Font(
            resId = R.font.google_sans_flex,
            weight = FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(700)),
        ),
    )

private val defaultTypography = Typography()

// Set of Material typography styles to start with
val Typography =
    Typography(
        displayLarge = defaultTypography.displayLarge.copy(fontFamily = GoogleSansFlex),
        displayMedium = defaultTypography.displayMedium.copy(fontFamily = GoogleSansFlex),
        displaySmall = defaultTypography.displaySmall.copy(fontFamily = GoogleSansFlex),
        headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = GoogleSansFlex),
        headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = GoogleSansFlex),
        headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = GoogleSansFlex),
        titleLarge = defaultTypography.titleLarge.copy(fontFamily = GoogleSansFlex),
        titleMedium = defaultTypography.titleMedium.copy(fontFamily = GoogleSansFlex),
        titleSmall =
            TextStyle(
                fontFamily = GoogleSansFlex,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.1.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = GoogleSansFlex,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.5.sp,
            ),
        bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = GoogleSansFlex),
        bodySmall = defaultTypography.bodySmall.copy(fontFamily = GoogleSansFlex),
        labelLarge = defaultTypography.labelLarge.copy(fontFamily = GoogleSansFlex),
        labelMedium = defaultTypography.labelMedium.copy(fontFamily = GoogleSansFlex),
        labelSmall = defaultTypography.labelSmall.copy(fontFamily = GoogleSansFlex),
    )
