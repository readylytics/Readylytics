# Fix RAS/Steps bar inconsistencies (marker dot + 75%-fill scaling)

This document is self-contained: it includes exact before/after code for every file touched. An
implementing agent should not need to re-explore the codebase — just apply the diffs below in
order, then run the verification steps at the end.

## Context

The Dashboard's RAS card renders through the "Universal Metric Card" system and shows a visible
thumb/dot marker on its bar, with the bar filling proportionally to `value / 100`. Two other
bar-style widgets don't match it:

1. **Missing dot marker** — the Workout tab's RAS weekly bar (`RasWeeklyBar`) and the Dashboard's
   Daily Steps bar (`StepsCard`) render via the shared `M3MetricBar` component but never pass
   `showMarker = true`, so they draw a bare track+fill with no dot. The Steps detail screen's bar
   (`StepsBar`, feature/vitals — reached by tapping the Daily Steps card) has the same gap; it's a
   bespoke `Canvas` implementation (not `M3MetricBar`), so it needs a hand-drawn marker circle.
2. **Wrong fill scaling on Dashboard RAS** — the Workout tab's RAS bar intentionally fills only
   **75%** of the track width when RAS hits 100 (`BAR_MAX = 100f / 0.75f`), leaving headroom to
   visually show overshoot past 100. The Dashboard's RAS card (in Bar or Gauge display mode)
   instead uses a plain linear `value / 100` fraction via
   `UniversalMetricScalePreparer.score(value, 0f, 100f)`, so it fills 100% of the track/arc at
   RAS=100. These need to match: Dashboard RAS bar *and* gauge should also cap fill at 75% for
   RAS=100.

Decisions already confirmed with the user:
- Fix the Steps detail bar (`StepsBar.kt`) too, not just the two originally named widgets.
- Centralize the duplicated `0.75f` literal into one shared public constant
  (`GOAL_FILL_CAP_FRACTION`) instead of adding a 4th copy.
- Leave the Workout tab's separate "Readiness"/"Strain Ratio" gauges untouched — out of scope,
  different metric.

## Change 1 — add the shared constant

**File:** `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricBar.kt`

Must be a **public** (not `internal`) top-level `const val`, since it will be consumed from three
other Gradle modules (`feature/workouts`, `feature/vitals`, `feature/dashboard`), all of which
already depend on `core/ui` and already import other symbols from this same
`app.readylytics.health.core.ui.components` package.

Insert it right above the existing `METRIC_BAR_TICK_FRACTIONS` declaration:

```kotlin
// BEFORE (line 21):
internal val METRIC_BAR_TICK_FRACTIONS: List<Float> = listOf(0.2f, 0.4f, 0.6f, 0.8f)
```

```kotlin
// AFTER:
/**
 * Goal-style bars/gauges (RAS, Steps) fill only this fraction of their track/arc at the goal or
 * max value, leaving headroom in the remaining width/sweep to visually show overshoot past goal.
 */
const val GOAL_FILL_CAP_FRACTION: Float = 0.75f

internal val METRIC_BAR_TICK_FRACTIONS: List<Float> = listOf(0.2f, 0.4f, 0.6f, 0.8f)
```

No other changes to this file — `showMarker`/marker-drawing logic already exists (lines 137-143)
and is purely opt-in; nothing else here needs to change.

## Change 2 — `StepsCard.kt` (Dashboard Daily Steps bar)

**File:** `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/StepsCard.kt`

Same package as `M3MetricBar.kt`, so **no new import needed**.

```kotlin
// BEFORE (lines 83-92):
        val count = stepCount ?: 0
        val max = stepGoal / 0.75f
        M3MetricBar(
            progressFraction = (count.toFloat() / max.coerceAtLeast(1f)).coerceIn(0f, 1f),
            activeColor = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.secondaryContainer,
            barHeight = MaterialTheme.dimens.miniBarHeight,
            animateProgress = false,
            modifier = Modifier.fillMaxWidth(),
        )
```

```kotlin
// AFTER:
        val count = stepCount ?: 0
        val max = stepGoal / GOAL_FILL_CAP_FRACTION
        M3MetricBar(
            progressFraction = (count.toFloat() / max.coerceAtLeast(1f)).coerceIn(0f, 1f),
            activeColor = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.secondaryContainer,
            barHeight = MaterialTheme.dimens.miniBarHeight,
            showMarker = true,
            animateProgress = false,
            modifier = Modifier.fillMaxWidth(),
        )
```

## Change 3 — `RasWeeklyBar.kt` (Workout tab RAS weekly bar)

**File:** `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/RasWeeklyBar.kt`

```kotlin
// BEFORE (import block, lines 21-27):
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.M3MetricBar
import app.readylytics.health.core.ui.components.gaugeColor
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.util.roundToPercentInt
import app.readylytics.health.feature.workouts.R
```

```kotlin
// AFTER:
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.GOAL_FILL_CAP_FRACTION
import app.readylytics.health.core.ui.components.M3MetricBar
import app.readylytics.health.core.ui.components.gaugeColor
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.util.roundToPercentInt
import app.readylytics.health.feature.workouts.R
```
(Exact import position doesn't matter — running `./gradlew ktlintFormat` will re-sort it.)

```kotlin
// BEFORE (lines 29-30):
// 100 RAS fills 75% of the bar width
private const val BAR_MAX = 100f / 0.75f
```

```kotlin
// AFTER:
// 100 RAS fills 75% of the bar width
private const val BAR_MAX = 100f / GOAL_FILL_CAP_FRACTION
```

```kotlin
// BEFORE (lines 54-64):
        M3MetricBar(
            progressFraction = (totalRas / BAR_MAX).coerceIn(0f, 1f),
            activeColor = fillColor,
            trackColor = trackColor,
            barHeight = MaterialTheme.dimens.miniBarHeight,
            animateProgress = false,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = chartSummary },
        )
```

```kotlin
// AFTER:
        M3MetricBar(
            progressFraction = (totalRas / BAR_MAX).coerceIn(0f, 1f),
            activeColor = fillColor,
            trackColor = trackColor,
            barHeight = MaterialTheme.dimens.miniBarHeight,
            showMarker = true,
            animateProgress = false,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = chartSummary },
        )
```

## Change 4 — `StepsBar.kt` (Steps detail screen, feature/vitals)

**File:** `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/steps/StepsBar.kt`

This one does **not** use `M3MetricBar` — it's a bespoke `Canvas` with its own track/fill/tap
tooltip/halo-animation drawing (`drawRect` calls inside a `clipPath { }` block, plus separate
tap-selection halo circles later in the same `Canvas`). We must hand-draw the marker, unclipped,
using the same visual convention as `M3MetricBar`'s marker (a filled circle in the active/fill
color, centered at the fill's end, on the vertical center of the bar).

**4a. Add the import** (alongside the existing `core.ui.components` imports, lines 42-46):

```kotlin
// BEFORE:
import app.readylytics.health.core.ui.common.ChartUtils
import app.readylytics.health.core.ui.components.DataPointTooltip
import app.readylytics.health.core.ui.components.DataPointTooltipData
import app.readylytics.health.core.ui.components.SegmentHitBox
import app.readylytics.health.core.ui.components.detectCanvasTap
import app.readylytics.health.core.ui.components.gaugeColor
```

```kotlin
// AFTER:
import app.readylytics.health.core.ui.common.ChartUtils
import app.readylytics.health.core.ui.components.DataPointTooltip
import app.readylytics.health.core.ui.components.DataPointTooltipData
import app.readylytics.health.core.ui.components.GOAL_FILL_CAP_FRACTION
import app.readylytics.health.core.ui.components.SegmentHitBox
import app.readylytics.health.core.ui.components.detectCanvasTap
import app.readylytics.health.core.ui.components.gaugeColor
```
(Again, `ktlintFormat` will fix ordering.)

**4b. Use the constant in `barMax`** (lines 53-54):

```kotlin
// BEFORE:
// stepGoal fills bar to 75% width — mirrors RAS bar design
private fun barMax(stepGoal: Int): Float = stepGoal / 0.75f
```

```kotlin
// AFTER:
// stepGoal fills bar to 75% width — mirrors RAS bar design
private fun barMax(stepGoal: Int): Float = stepGoal / GOAL_FILL_CAP_FRACTION
```

**4c. Resolve the marker diameter in composable scope.** Add this alongside the other
`MaterialTheme`-derived vals (right after line 96, `val primaryColor = MaterialTheme.colorScheme.primary`):

```kotlin
// BEFORE (lines 93-96):
    val fillColor = status.gaugeColor()
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val primaryColor = MaterialTheme.colorScheme.primary
```

```kotlin
// AFTER:
    val fillColor = status.gaugeColor()
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val markerDiameter = MaterialTheme.dimens.metricGaugeMarkerDiameter
```

(`MaterialTheme.dimens` is already imported via `app.readylytics.health.core.designsystem.dimens`,
line 39 — no new import needed for this part. `metricGaugeMarkerDiameter` = 6dp, comfortably inside
the 28dp `miniBarHeight` used for this bar's `Canvas` height, so it won't clip vertically.)

**4d. Hoist `fillWidth` out of the `clipPath` block and draw the marker after it, unclipped.**
The marker must NOT be inside `clipPath { }` (same as `M3MetricBar`, which draws its marker
unclipped so the full circle renders even right at the bar's rounded edge).

```kotlin
// BEFORE (lines 216-245):
            ) {
                val totalWidth = size.width
                val barHeight = size.height
                val radius = barHeight / 2f

                val clipPath =
                    Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = 0f,
                                top = 0f,
                                right = totalWidth,
                                bottom = barHeight,
                                cornerRadius = CornerRadius(radius),
                            ),
                        )
                    }

                clipPath(clipPath) {
                    drawRect(color = trackColor, topLeft = Offset(0f, 0f), size = Size(totalWidth, barHeight))

                    if (stepCount != null && stepCount > 0) {
                        val fillWidth = (totalWidth * (count.toFloat() / barMax(stepGoal))).coerceAtMost(totalWidth)
                        drawRect(
                            color = fillColor,
                            topLeft = Offset(0f, 0f),
                            size = Size(fillWidth, barHeight),
                        )
                    }
                }

                drawRoundRect(
                    color = outlineColor,
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(width = 1.dp.toPx()),
                )
```

```kotlin
// AFTER:
            ) {
                val totalWidth = size.width
                val barHeight = size.height
                val radius = barHeight / 2f
                val markerRadiusPx = markerDiameter.toPx() / 2f
                val fillWidth =
                    if (stepCount != null && stepCount > 0) {
                        (totalWidth * (count.toFloat() / barMax(stepGoal))).coerceAtMost(totalWidth)
                    } else {
                        0f
                    }

                val clipPath =
                    Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = 0f,
                                top = 0f,
                                right = totalWidth,
                                bottom = barHeight,
                                cornerRadius = CornerRadius(radius),
                            ),
                        )
                    }

                clipPath(clipPath) {
                    drawRect(color = trackColor, topLeft = Offset(0f, 0f), size = Size(totalWidth, barHeight))

                    if (stepCount != null && stepCount > 0) {
                        drawRect(
                            color = fillColor,
                            topLeft = Offset(0f, 0f),
                            size = Size(fillWidth, barHeight),
                        )
                    }
                }

                if (stepCount != null && stepCount > 0) {
                    drawCircle(
                        color = fillColor,
                        radius = markerRadiusPx,
                        center = Offset(fillWidth, barHeight / 2f),
                    )
                }

                drawRoundRect(
                    color = outlineColor,
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(width = 1.dp.toPx()),
                )
```

Everything else in this file (tap-selection halo/indicator circles, tooltip logic, accessibility
actions) is untouched — those are a separate "selected point" affordance, not the goal-value
marker, and must keep working exactly as before.

## Change 5 — Dashboard RAS bar/gauge fill scaling

**File:** `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardRecoveryMetricPresentationFactory.kt`

```kotlin
// BEFORE (import block, lines 1-6):
package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.ui.common.DateFormatUtils
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricScalePreparer
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricUnavailableReason
```

```kotlin
// AFTER:
package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.ui.common.DateFormatUtils
import app.readylytics.health.core.ui.components.GOAL_FILL_CAP_FRACTION
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricScalePreparer
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricUnavailableReason
```

```kotlin
// BEFORE (rasPresentation(), lines 196-205):
        val value =
            summary?.let {
                LoadSourceSelector.selectTotalRas(it, preferences.rasSourceMode)
            }
        val visual =
            UniversalMetricScalePreparer.score(
                value,
                0f,
                100f,
            )
```

```kotlin
// AFTER:
        val value =
            summary?.let {
                LoadSourceSelector.selectTotalRas(it, preferences.rasSourceMode)
            }
        val visual =
            UniversalMetricScalePreparer.score(
                value,
                0f,
                100f / GOAL_FILL_CAP_FRACTION,
            )
```

Do **not** touch anything else in `rasPresentation()` — `valueText` (line 207) and
`accessibilityDescription` (lines 217-225) still display/announce the raw `100` max, which is
correct (that's the real RAS ceiling; only the fill-fraction geometry changes, not the displayed
numbers or a11y text).

This single change affects both Bar mode (`UniversalBarRenderer` → `M3MetricBar`) and Gauge mode
(`UniversalGaugeRenderer` → `M3MetricGauge`) for the `RAS_DAILY` card, since both renderers consume
the same `UniversalMetricVisual.Score.markerFraction` produced by `score()`. The Dashboard RAS
card's marker dot already renders in both modes today (unaffected by this change) — only the fill
fraction math changes.

**Out of scope, do not touch:** the Workout tab's "Readiness" gauge
(`feature/workouts/.../WorkoutStatsSection.kt` lines ~153-180 and
`UniversalWorkoutMetricCard.kt`) and "Strain Ratio" gauge — these call the same
`UniversalMetricScalePreparer.score()` but are a different metric and were explicitly excluded by
the user. They must keep filling 100% at their max value, unchanged.

## Files touched (summary)

- `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricBar.kt` — add `GOAL_FILL_CAP_FRACTION` constant (Change 1)
- `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/StepsCard.kt` — `showMarker = true`, use constant (Change 2)
- `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/RasWeeklyBar.kt` — `showMarker = true`, use constant (Change 3)
- `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/steps/StepsBar.kt` — draw marker circle, use constant (Change 4)
- `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardRecoveryMetricPresentationFactory.kt` — scale `maximum` in `rasPresentation()` (Change 5)

## Verification

1. `./gradlew ktlintFormat` — fixes import ordering across the touched files.
2. `./gradlew testDebugUnitTest` — search for existing unit tests asserting `RasWeeklyBar`,
   `StepsCard`, `StepsBar` fill fractions, or `DashboardRecoveryMetricPresentationFactory.rasPresentation()`
   output (e.g. `markerFraction`/`maxValue` assertions — grep for `rasPresentation`, `BAR_MAX`,
   `barMax`, `RAS_DAILY` in `*Test.kt` files) and update any expected values to the new
   `100f / 0.75f` max where the test hard-codes the old `100f` denominator.
3. `./gradlew installDebug`, then use the `run` skill (or manual device/emulator check) to visually
   confirm:
   - Dashboard RAS card (Bar and Gauge display modes, switchable via the card's `⋮` menu) shows
     the dot, and at RAS=100 the bar/arc fills to ~75%.
   - Workout tab's RAS weekly bar shows the dot.
   - Dashboard Daily Steps bar shows the dot.
   - Steps detail screen bar (tap into Daily Steps) shows the dot at the correct step-count
     position, and existing tap-to-select tooltip/halo behavior still works.
   - Workout tab's Readiness/Strain Ratio gauges are visually unchanged (still fill 100% at max).
4. `./gradlew lintRelease` at the end, per project convention.
