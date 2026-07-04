package app.readylytics.health.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Design-system dimension tokens.
 *
 * **Naming conventions (two buckets):**
 *
 * 1. **Size-tier names** — use when a concept has multiple discrete sizes
 *    (e.g. small / medium / large variants of the same visual element).
 *    Examples: `iconSmall`, `iconMedium`, `iconStandard`, `iconContainerLarge`,
 *    `avatarSmall`, `avatarMedium`.
 *    Pattern: `<concept><Tier>` where tier is one of
 *    `Tiny | Small | Medium | Standard | Large | ExtraLarge`.
 *
 * 2. **Semantic-role names** — use for single-purpose, one-off values that
 *    have exactly one meaning in the design system.
 *    Examples: `indicatorDot`, `borderThin`, `borderSelected`,
 *    `progressStrokeWidth`, `cardHeight`, `miniBarHeight`.
 *    Pattern: `<role>` or `<role><Qualifier>` — no size tier suffix.
 *
 * **Rule for new fields:** if you are adding a second (or third) size of an
 * existing concept, use bucket 1 and add a tier suffix. If the value is
 * unique to one role in the UI, use bucket 2 and name it by its role.
 */
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
