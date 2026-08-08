# Add end-of-fill marker dot to `M3MetricBar`, fix stray tick-dot visibility

## Context

The dashboard's circular gauges (Sleep Score, Readiness) draw a small dot at the end of
the active arc marking the current value (`M3MetricGauge`, in
`core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricGauge.kt`).
The horizontal bar variant used for HRV and Sleep Time (`M3MetricBar`, in
`core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricBar.kt`) has no
equivalent — it only draws track, fill, and four quintile "tick" reference dots (0.2/0.4/0.6/0.8).
The user wants the bar to get the same value-marker dot the gauge has.

Separately, the HRV screenshot shows a tick dot (around the 20% mark) still visible even though
it sits inside the region the fill's rounded end-cap should visually cover. Root cause: both
`M3MetricBar` and `M3MetricGauge` hide their tick dots purely by filtering
`tickFraction > progress`, but the fill/arc is drawn with a **round stroke cap** whose visual
extent goes `strokeWidth/2` px (bar) / an equivalent angular amount (gauge) *past* the raw
progress fraction. A tick that's nominally `> progress` can still fall inside that cap overhang and
render on top of the fill (ticks are drawn last), producing the visible stray dot. This is the same
duplicated `[0.2,0.4,0.6,0.8].filter { it > progress }` idiom in both files, so both get the same fix.

## Changes

### 1. `core/ui/.../components/M3MetricBar.kt` — coverage fix for tick dots

- Change `visibleTickFractions` to accept the fill's cap overhang as a fraction of width:
  ```kotlin
  internal fun visibleTickFractions(progress: Float, capCoverageFraction: Float = 0f): List<Float> =
      METRIC_BAR_TICK_FRACTIONS.filter { it > progress + capCoverageFraction }
  ```
  Default `0f` keeps existing call sites/tests (which test pure filtering) valid.
- In the `Canvas` block, compute the cap coverage only when a fill is actually drawn
  (`progressToDraw > 0f`, matching the existing `if (progressToDraw > 0f)` fill guard) and pass it:
  ```kotlin
  val capCoverageFraction = if (progressToDraw > 0f) (strokeWidth / 2f) / size.width else 0f
  visibleTickFractions(progressToDraw, capCoverageFraction).forEach { ... }
  ```

### 2. `core/ui/.../components/M3MetricBar.kt` — new end-of-fill marker dot

Mirror `M3MetricGauge`'s marker: draw it last (on top of track/fill/ticks), same size/color
convention as the gauge.

- Add params to `M3MetricBar`: `markerColor: Color = activeColor`, `markerDiameter: Dp =
  MaterialTheme.dimens.metricGaugeMarkerDiameter` (reuses the existing 6dp token the gauge already
  uses — no new dimen).
- After the tick-dot loop, draw the marker circle only when there's a real value, matching the
  gauge's `markerFraction != null && progressToDraw > 0f` guard:
  ```kotlin
  if (progressFraction != null && progressToDraw > 0f) {
      drawCircle(
          color = markerColor,
          radius = markerDiameter.toPx() / 2f,
          center = Offset(fillEndCenterX(progressToDraw, size.width, strokeWidth), centerY),
      )
  }
  ```
  Reuses the existing `fillEndCenterX` helper so the dot always sits exactly at the visual end of
  the fill's rounded cap, consistent with how the gauge's marker sits exactly at `activeEndAngle`.

### 3. `core/ui/.../metriccard/UniversalMetricRenderers.kt` — wire marker color

In `UniversalBarRenderer`, pass `markerColor = presentation.status.containerColor()` to
`M3MetricBar`, the same status-driven color the gauge renderer already passes to
`M3MetricGaugeWithValue` (line ~91), so the bar's dot visually matches the gauge's dot for the same
status.

### 4. `core/ui/.../components/M3MetricGauge.kt` — same coverage fix for arc tick dots

Apply the analogous fix to `M3MetricGauge`'s inline tick filtering (lines ~117–134): the active arc
is stroked with `activeStrokeWidthPx` and a round cap, which overhangs the arc's end angle by
approximately `(activeStrokeWidthPx / 2) / geometry.radius` radians. Convert that to the same
0..1 fraction-of-sweep units as `tickFractions`/`progressToDraw`:
```kotlin
val capCoverageFraction =
    if (progressToDraw > 0f) {
        Math.toDegrees((activeStrokeWidthPx / 2f / geometry.radius).toDouble()).toFloat() / geometry.sweepAngle
    } else {
        0f
    }
tickFractions
    .filter { it > progressToDraw + capCoverageFraction }
    .forEach { ... }
```
Guarded the same way as the bar (only when the active arc is actually drawn, `progressToDraw > 0f`).
No change to the gauge's own end marker dot logic (it already draws last, on top, unaffected).

### 5. Tests

- `core/ui/src/test/.../M3MetricBarTest.kt`:
  - Existing `visibleTickFractions_hidesTicksAtOrBeforeProgress` stays valid (uses default
    `capCoverageFraction = 0f`).
  - Add a new test exercising `visibleTickFractions(progress, capCoverageFraction)` with a nonzero
    cap, asserting a tick within the overhang is excluded (e.g.
    `visibleTickFractions(0.16f, 0.05f)` excludes `0.2f` but keeps `0.4f`/`0.6f`/`0.8f`).
  - Add a marker-dot presence test analogous to how the gauge is tested, if an existing pattern for
    asserting `drawCircle` calls / semantics exists (check `M3MetricGauge`'s test file for the
    pattern before writing this).
- Check for a corresponding `M3MetricGaugeTest.kt` (if it exists) and add the same style of
  cap-coverage test there.
- No changes expected to `DashboardMetricCardModesTest`/snapshot-style tests unless they assert on
  bar pixel output directly — verify after implementing.

## Verification

1. `./gradlew ktlintFormat`
2. `./gradlew testDebugUnitTest` (in particular `M3MetricBarTest`, and the gauge's test file if
   one exists)
3. `./gradlew installDebug` and visually confirm on the dashboard:
   - HRV / Sleep Time bar cards now show a small dot at the end of the filled portion, matching the
     gauge's dot style.
   - No stray tick dot renders inside the filled region at any progress value (spot-check low
     progress values near 0.15–0.25 where the bug was most visible).
4. `./gradlew lintRelease` after the above are green (per repo convention).
