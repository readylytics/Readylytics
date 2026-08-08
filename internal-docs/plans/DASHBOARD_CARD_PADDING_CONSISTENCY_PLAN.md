# Dashboard Card Padding/Spacing Consistency Plan

**Status:** Proposed, not yet implemented.
**Reported issue:** HRV card and Sleep Time card (and others) appear to have inconsistent padding/spacing on the dashboard, in both Value display mode and Bar display mode.

## Context

Investigation found that HRV and Sleep Time render through the exact same shared composable — `UniversalMetricCard` → `UniversalValueUnitColumn` in `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/UniversalMetricCard.kt` / `UniversalMetricRenderers.kt` — with byte-identical padding/spacing tokens (16dp/12dp outer card padding, 4dp gaps around the track, a fixed 20dp-tall secondary-text slot). There is no per-card hardcoded dp override and no layout bug in the card shell itself.

The actual divergence is a boolean flag, `CardId.usesDeltaPill()` (`feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardToUniversalMapper.kt:30-41`), which decides how the secondary line under the value/bar is rendered:

- **Pill cards** (`SLEEP_SCORE`, `READINESS`, `HRV`, `SLEEP_RHR`, `RESTING_HR`, `STRAIN_RATIO`, `BODY_TEMPERATURE`): the secondary text (e.g. `"↑ 1 ms"`) is wrapped in `UniversalMetricDeltaPill` — a `Surface(shape = CircleShape, ...)` whose inner `Text` has `padding(horizontal = MaterialTheme.spacing.small /*8dp*/, vertical = MaterialTheme.spacing.hairline /*2dp*/)` (`UniversalMetricRenderers.kt:231-253`).
- **Plain-text cards** (everything else, including `SLEEP_DURATION`, `STEPS`, `RAS_DAILY`, `CIRCADIAN_CONSISTENCY`, `SLEEP_EFFICIENCY`, `HEART_RATE`, `WEIGHT`, `BODY_FAT`, `BLOOD_PRESSURE`, `OXYGEN_SATURATION`): the secondary text is a bare `Text(...)` with **no padding at all** (`UniversalMetricRenderers.kt:101-107` in `UniversalGaugeRenderer`, and `:215-221` in the shared `UniversalValueUnitColumn` used by Bar/Value mode).

Both variants sit inside the same fixed-size `Box`. In Bar/Value mode that box uses `Alignment.CenterStart` (`UniversalMetricRenderers.kt:209`), so the pill's 8dp internal start-padding makes its text begin ~8dp to the right of where the value/unit row above it starts, while the plain text (no padding) starts flush with the value row. That flush-vs-indented mismatch between the value row and the secondary row is what reads as "inconsistent padding" between e.g. HRV (pill, indented second line) and Sleep Time (plain, flush second line).

**Decision (confirmed with the user):** keep the pill/plain-text distinction as-is — pills stay reserved for short trend deltas; descriptive text like the sleep time range stays plain, no background — but adapt the plain-text cards' spacing to match the pill's inset so the dashboard reads consistently. This applies to the whole plain-text group, not just Sleep Time.

## Approach

Give the plain-text secondary line the same padding the pill's inner `Text` already uses, without adding any background/shape — so every card's second line starts at the same inset regardless of whether it's a pill or plain text.

**File:** `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/UniversalMetricRenderers.kt`

1. Extract the pill's existing inset (`horizontal = MaterialTheme.spacing.small`, `vertical = MaterialTheme.spacing.hairline`) into one shared private `@Composable` modifier helper (e.g. `Modifier.secondaryTextInset()`), so pill and plain text can't drift apart again.
2. Apply that helper to `UniversalMetricDeltaPill`'s inner `Text` (replacing its inline `.padding(...)`, no visual change).
3. Apply the same helper to the plain-text `Text` branches:
   - `UniversalGaugeRenderer`'s `else` branch (~line 101-107)
   - `UniversalValueUnitColumn`'s `else` branch (~line 215-221, shared by `UniversalBarRenderer` and `UniversalValueRenderer` — this is the one that fixes the HRV vs. Sleep Time / RAS / Weight / etc. misalignment seen in the reported screenshots, since it's the `Alignment.CenterStart` box).

No changes to `Spacing.kt`/`Dimens.kt` tokens, no changes to `DashboardToUniversalMapper.kt` (the pill/plain-text assignment itself is unchanged, per the decision above), no changes to card-specific files (`DashboardCardFactory.kt`, `DashboardRecoveryMetricPresentationFactory.kt`) — this is a single shared-component styling fix that automatically applies to every card using `UniversalMetricCard`.

## Verification

- `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` (per project pre-commit rule).
- `./gradlew installDebug`, open the dashboard, and visually compare HRV vs. Sleep Time (and ideally RAS/Weight) in both Value mode and Bar mode: confirm the second line now starts at the same horizontal offset as the value/unit row's second-line counterpart on pill cards, and that no secondary text clips or wraps inside the fixed 20dp slot at default font scale.
- Spot-check at a larger system font scale (Settings → Display → Font size) that the added padding doesn't push plain secondary text out of the fixed-height slot, since the pill already survives at that padding today.
- No `internal-docs/DATA_FLOW.md`, `ABOUT.md`, or scoring-doc updates needed — this is a pure presentation-layer styling fix, not a data-flow, schema, or scoring change.
