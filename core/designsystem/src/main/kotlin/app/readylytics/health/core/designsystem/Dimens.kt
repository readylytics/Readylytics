package app.readylytics.health.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class Dimens(
    val iconSmall: Dp = 16.dp,
    val iconMedium: Dp = 20.dp,
    val iconStandard: Dp = 24.dp,
    val iconContainerLarge: Dp = 32.dp,
    val avatarSmall: Dp = 40.dp,
    val avatarMedium: Dp = 48.dp,
    val indicatorDot: Dp = 8.dp,
    val borderThin: Dp = 1.dp,
    val borderSelected: Dp = 3.dp,
    val progressStrokeWidth: Dp = 2.dp,
    val cardHeight: Dp = 156.dp,
    val miniBarHeight: Dp = 28.dp,
)

val LocalDimens = staticCompositionLocalOf { Dimens() }

val MaterialTheme.dimens: Dimens
    @Composable
    @ReadOnlyComposable
    get() = LocalDimens.current
