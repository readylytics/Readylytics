# Fix RAS/Steps bar inconsistencies (marker dot + 75%-fill scaling)

## Context

The Dashboard's RAS card renders through the "Universal Metric Card" system and shows a visible thumb/dot marker on its bar, with the bar filling proportionally to `value / 100`. Two other bar-style widgets don't match it:

1. **Missing dot marker** — the Workout tab's RAS weekly bar (`RasWeeklyBar`) and the Dashboard's Daily Steps bar (`StepsCard`) render via the shared `M3MetricBar` component but never pass `showMarker = true`, so they draw a bare track+fill with no dot. (Per user follow-up, the Steps detail screen's bar (`StepsBar`, feature/vitals) has the same gap and should be fixed too, even though it's a bespoke Canvas implementation rather than `M3MetricBar`.)
2. **Wrong fill scaling on Dashboard RAS** — the Workout tab's RAS bar intentionally fills only **75%** of the track width when RAS hits 100 (`BAR_MAX = 100f / 0.75f`), leaving headroom to visually show overshoot past 100. The Dashboard's RAS card (when in Bar or Gauge display mode) instead uses a plain linear `value / 100` fraction via `UniversalMetricScalePreparer.score(value, 0f, 100f)`, so it fills 100% of the track/arc at RAS=100. These need to match: Dashboard RAS bar *and* gauge should also cap fill at 75% for RAS=100.

Per user answers: fix the Steps detail bar too, centralize the `0.75f` cap into one shared named constant (currently duplicated 3x as raw literals with no shared source of truth), and leave the Workout tab's separate "Readiness" gauge untouched (it's a different metric, not in scope).

## Approach

### 1. Centralize the 75%-fill-cap constant

Add a single shared constant in `core/ui` (alongside `M3MetricBar.kt`, since that's the common dependency for the three legacy callers and it's also visible to `feature/vitals`/`feature/workouts`/dashboard's `UniversalMetricScalePreparer`):

```kotlin
// In core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricBar.kt
/** Goal-style bars/gauges fill this fraction of their track at the goal/max value, leaving headroom to show overshoot. */
const val GOAL_FILL_CAP_FRACTION: Float = 0.75f
```

Replace the three existing inline/local usages with this constant:
- `feature/workouts/.../RasWeeklyBar.kt:29-30` — `BAR_MAX = 100f / 0.75f` → `100f / GOAL_FILL_CAP_FRACTION`
- `core/ui/.../StepsCard.kt:84` — `stepGoal / 0.75f` → `stepGoal / GOAL_FILL_CAP_FRACTION`
- `feature/vitals/.../steps/StepsBar.kt:54` — `barMax(stepGoal) = stepGoal / 0.75f` → `stepGoal / GOAL_FILL_CAP_FRACTION`

### 2. Add the dot marker to the two `M3MetricBar`-based callers

- **`RasWeeklyBar.kt`** (`feature/workouts`): in the `M3MetricBar(...)` call (line ~54), add `showMarker = true`. Marker color defaults to `activeColor` (=`fillColor`, already status-colored), matching the Dashboard RAS card's convention.
- **`StepsCard.kt`** (`core/ui`): in the `M3MetricBar(...)` call (line ~85), add `showMarker = true`.

No change needed to `M3MetricBar` itself — `showMarker`/marker-drawing logic already exists (lines 137-143) and is purely opt-in.

### 3. Add a value marker to `StepsBar.kt` (feature/vitals, Steps detail screen)

This one doesn't use `M3MetricBar` — it's a bespoke `Canvas` with its own track/fill/tap-tooltip/halo-animation drawing. Mirror `M3MetricBar`'s marker convention manually:
- Hoist the `fillWidth` calculation (currently computed inside the `if (stepCount != null && stepCount > 0)` block within `clipPath`, line 238) so it's available after the `clipPath { ... }` block closes (clipping must not apply to the marker, same as `M3MetricBar` draws its marker unclipped).
- After the `clipPath { ... }` block (i.e., unclipped, drawn on top, before or after the outline stroke — order doesn't visually matter here since the outline is a thin 1dp border), draw:
  ```kotlin
  if (stepCount != null && stepCount > 0) {
      drawCircle(
          color = fillColor,
          radius = MaterialTheme.dimens.metricGaugeMarkerDiameter.toPx() / 2f,
          center = Offset(fillWidth, barHeight / 2f),
      )
  }
  ```
  (Read `metricGaugeMarkerDiameter` via `MaterialTheme.dimens` outside the `Canvas` draw scope, same pattern `M3MetricBar` uses for `tickDiameter`, then reference the resolved px value inside the `Canvas{}` lambda.) `metricGaugeMarkerDiameter` is 6dp, well inside the 28dp `miniBarHeight`, so it won't clip vertically.
- Leave the existing tap-selection halo/indicator circles (lines 253-276) untouched — those are a separate "selected point" affordance, not the goal-value marker.

### 4. Match Dashboard RAS bar/gauge fill to the 75% cap

**File:** `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardRecoveryMetricPresentationFactory.kt`, `rasPresentation()` (lines 191-228).

Change the `UniversalMetricScalePreparer.score(value, 0f, 100f)` call (lines 200-205) to scale the maximum by the same cap, so RAS=100 lands at 75% fraction instead of 100%:

```kotlin
val visual =
    UniversalMetricScalePreparer.score(
        value,
        0f,
        100f / GOAL_FILL_CAP_FRACTION,
    )
```

This single change affects both Bar mode (`UniversalBarRenderer` → `M3MetricBar`) and Gauge mode (`UniversalGaugeRenderer` → `M3MetricGauge`) for the `RAS_DAILY` card, since both renderers consume the same `UniversalMetricVisual.Score.markerFraction` produced by `score()`. The Dashboard RAS card's marker dot already renders in both modes (`M3MetricBar`'s `showMarker=true` caller `UniversalBarRenderer`, and `M3MetricGauge`'s always-on marker) — that behavior is unaffected, only the fraction math changes.

Note: `valueText`/`accessibilityDescription` (lines 207, 217-225) still reference the raw `100` max for display text — leave those as-is; only the `score()` call's `maximum` argument changes, since that's what drives fill-fraction geometry, not displayed labels.

**Out of scope:** the Workout tab's "Readiness" gauge (`WorkoutStatsSection.kt` lines 153-180 / `UniversalWorkoutMetricCard.kt`) and "Strain Ratio" gauge — these use the same `UniversalMetricScalePreparer.score()` but are a different metric and were explicitly excluded by the user.

## Files to change

- `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricBar.kt` — add `GOAL_FILL_CAP_FRACTION` constant
- `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/StepsCard.kt` — `showMarker = true`, use constant
- `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/RasWeeklyBar.kt` — `showMarker = true`, use constant
- `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/steps/StepsBar.kt` — draw marker circle, use constant
- `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardRecoveryMetricPresentationFactory.kt` — scale `maximum` in `rasPresentation()`

## Verification

1. `./gradlew ktlintFormat`
2. `./gradlew testDebugUnitTest` — check for existing unit tests asserting `RasWeeklyBar`/`StepsCard`/`StepsBar` fill fractions or `DashboardRecoveryMetricPresentationFactory.rasPresentation()` output (e.g. `markerFraction`/`maxValue` assertions) and update expected values to match the new `100f / 0.75f` max.
3. `./gradlew installDebug`, then use the `run` skill (or manual device/emulator check) to visually confirm:
   - Dashboard RAS card (Bar and Gauge display modes, via card's `⋮` menu) shows the dot, and at RAS=100 the bar/arc fills to ~75%.
   - Workout tab's RAS weekly bar shows the dot.
   - Dashboard Daily Steps bar shows the dot.
   - Steps detail screen bar (tap into Daily Steps) shows the dot at the correct step-count position.
   - Workout tab's Readiness/Strain Ratio gauges are visually unchanged (still fill 100% at max).
4. `./gradlew lintRelease` at the end per project convention.
