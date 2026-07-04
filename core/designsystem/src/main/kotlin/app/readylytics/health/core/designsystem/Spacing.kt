package app.readylytics.health.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class Spacing(
    val default: Dp = 0.dp,
    val hairline: Dp = 2.dp,
    val extraSmall: Dp = 4.dp,
    val extraSmallMedium: Dp = 6.dp,
    val small: Dp = 8.dp,
    val smallMedium: Dp = 12.dp,
    val medium: Dp = 16.dp,
    val large: Dp = 24.dp,
    val extraLarge: Dp = 32.dp,
    val doubleExtraLarge: Dp = 48.dp,
) {
    val pageHorizontal: Dp
        get() = medium

    val pageTop: Dp
        get() = medium

    val pageBottom: Dp
        get() = medium

    val pageSectionGap: Dp
        get() = medium

    val pageSectionGapSmall: Dp
        get() = small

    val pageSectionGapLarge: Dp
        get() = large
}

val LocalSpacing = staticCompositionLocalOf { Spacing() }

val MaterialTheme.spacing: Spacing
    @Composable
    @ReadOnlyComposable
    get() = LocalSpacing.current
