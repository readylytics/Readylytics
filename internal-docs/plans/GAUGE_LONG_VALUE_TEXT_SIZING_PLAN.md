# Dynamic font sizing for long gauge values (Weight, Body Fat, etc.)

## Context

Dashboard gauge cards (`M3MetricGaugeWithValue`) render the value/unit text at a
**fixed** `headlineMedium` size regardless of string length. For short values
("86 pts", "50 bpm") this fits fine, but for longer ones — 3-digit weight
("87.2 kg"), a 4-5 char body-fat percentage ("14.0%") — the fixed-size text
crowds or clips against the horseshoe track: "87.2" sits with almost no
margin against the arc, and "14.0%" gets its leading "1" cropped at the top
(rendering like "I4.0%") because the overlay text has no real width/height
bound tying it to the gauge's drawn circle — it's just centered in whatever
box the parent hands it, using `maxLines = 1` + `TextOverflow.Ellipsis` as
the only (inadequate) fit mechanism.

The fix: make the value/unit text shrink to fit the space actually available
inside the horseshoe, using Compose Foundation's official auto-size text API
(the mechanism Material 3 Expressive added for exactly this "large adaptive
numeral in a bounded container" pattern), constrained to bounds derived from
the *same* geometry the gauge arc is already drawn with — so the text can
never legitimately overlap the track or get clipped, at any string length or
card size.

This is a shared component (`core/ui`), so the fix applies to every card that
uses `M3MetricGaugeWithValue` (Weight, Body Fat, Readiness, Recovery, Sleep,
generic score gauges) — short values keep rendering at full size unchanged;
only long values shrink.

## Design

### 1. Derive real text bounds from the gauge geometry (`M3MetricGauge.kt`)

`resolveHorseshoeGaugeGeometry` (lines 47–63) already computes the arc's
`radius`/`center` from the canvas size — this is the single source of truth
for the circle the track is drawn on. Add a sibling internal helper next to
it:

```kotlin
internal fun resolveGaugeTextBoundsPx(
    geometry: HorseshoeGaugeGeometry,
    trackInsetPx: Float,
    textBlockCenterYOffsetPx: Float,
): Size
```

It computes the widest inscribed rectangle available for the text block: for
the top and bottom edge of the text block's vertical span (its center offset
from the circle center, ± half of an assumed block height), take the chord
half-width at that y via `sqrt(max(0f, (radius - trackInsetPx).pow(2) - dy.pow(2)))`
for both edges, and use the smaller of the two as the safe half-width. This
reuses `geometry.radius`/`geometry.center` directly, so it can never drift
out of sync with what's actually drawn — no separate magic-number fraction
to tune by eye.

`trackInsetPx` = the same `activeStrokeWidthPx` already computed in
`M3MetricGauge` (trackThickness + 2.dp), so text stays clear of the track
stroke, not just the bare circle.

### 2. Bound the overlay and switch to auto-sizing text (`M3MetricGaugeWithValue`)

- Wrap the existing `Box` in `BoxWithConstraints` to get the actual pixel
  size of the gauge slot (this varies per card/font-scale today — see
  `UniversalGaugeRenderer`).
- Inside, call `resolveHorseshoeGaugeGeometry` + `resolveGaugeTextBoundsPx`
  (same call the `Canvas` in `M3MetricGauge` makes) to get a `Size` in px;
  convert to `Dp` via `LocalDensity.current` and apply as
  `Modifier.widthIn(max = safeWidth).heightIn(max = safeHeight)` on the
  overlay `Column` — this replaces the current unconstrained wrap-content
  column that lets text run past the arc horizontally.
- Replace both `Text(...)` calls with `BasicText(...)`, keeping the same
  `MaterialTheme.typography.headlineMedium` / `labelSmall` styles, colors,
  and `LineHeightStyle`, but adding:
  ```kotlin
  autoSize = TextAutoSize.StepBased(
      minFontSize = GAUGE_VALUE_MIN_FONT_SIZE, // value text
      maxFontSize = MaterialTheme.typography.headlineMedium.fontSize,
      stepSize = 1.sp,
  )
  ```
  (analogous `GAUGE_UNIT_MIN_FONT_SIZE` / `labelSmall.fontSize` for the unit
  line). Keep `maxLines = 1`; switch `overflow` to `TextOverflow.Clip` since
  auto-size — given a correctly computed bound — should always find a
  fitting size down to the floor, so ellipsis is no longer the fit strategy.
- `BasicText` requires the experimental auto-size opt-in
  (`@OptIn(...)` on the function) — confirm the exact annotation name/import
  for this project's Compose Foundation version (BOM `2026.06.00`) while
  implementing; the codebase already has precedent for opting into
  experimental Compose text APIs (`ExperimentalTextApi` in
  `core/designsystem/.../Type.kt`).
- Add `GAUGE_VALUE_MIN_FONT_SIZE` (e.g. `16.sp`) and `GAUGE_UNIT_MIN_FONT_SIZE`
  (e.g. `9.sp`) as private top-level constants in `M3MetricGauge.kt`,
  matching the file's existing convention of keeping gauge-specific magic
  numbers (`startAngle = 150f`, `sweepAngle = 240f`, tick fractions) local
  to this file rather than in `Dimens.kt`.
- If needed after visual verification, add one new `Dimens` token (bucket 2,
  semantic-role naming per the doc comment in `Dimens.kt`) for a small extra
  top inset on the value line to guarantee ascender headroom (e.g.
  `metricGaugeValueTopInset`) — only add this if the geometry-derived height
  bound alone doesn't fully stop ascender clipping in practice.

### 3. No changes needed elsewhere

`UniversalGaugeRenderer`, `DashboardMetricPresentationFactory`, and the
formatters stay untouched — this is purely a rendering fix inside
`M3MetricGaugeWithValue`; every caller (Weight, Body Fat, Readiness,
Recovery, Sleep, generic score cards) picks it up automatically.

Per `CLAUDE.md`'s doc-sync rules: this does not touch scoring formulas,
thresholds, the ingestion pipeline, or Room schema, so `DATA_FLOW.md`,
`ABOUT.md`, and `docs/*` require no updates.

## Files to modify

- `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricGauge.kt`
  — add `resolveGaugeTextBoundsPx`, convert `M3MetricGaugeWithValue`'s `Box`
  to `BoxWithConstraints`, bound the overlay `Column`, switch both `Text`
  calls to `BasicText` with `TextAutoSize.StepBased`.
- `core/designsystem/src/main/kotlin/app/readylytics/health/core/designsystem/Dimens.kt`
  — only if a new top-inset token turns out to be needed after visual check.

## Verification

- **Unit tests** (`core/ui/src/test/kotlin/.../M3MetricGaugeTest.kt`):
  - Add a test for `resolveGaugeTextBoundsPx` analogous to the existing
    `horseshoeGeometry_*` tests: assert bounds shrink for a height-constrained
    canvas, assert they never exceed the circle's diameter minus the track
    inset, assert non-negative for degenerate/very small canvas sizes.
  - Add a Compose test that renders `M3MetricGaugeWithValue` with a long
    value (`"142.8"`, unit `"kg"`) inside a small, constrained-size modifier
    (simulating a compact card) and asserts the full string is present via
    `onNodeWithText("142.8")` with no ellipsis character in the semantics
    text — proving auto-size, not truncation, is what made it fit.
  - Confirm existing tests still pass unmodified:
    `metricGaugeWithValue_overlayIsTaggedAndOffsetDownward` and
    `m3ScoreGaugeCard_regression_semanticsAndRendering` (both rely on
    `onNodeWithText`, which works identically against `BasicText`).
- **Pre-commit** (mandatory per `CLAUDE.md`): `./gradlew ktlintFormat`,
  `./gradlew testDebugUnitTest`, then `./gradlew lintRelease` at the end.
- **Manual/visual check**: build and run the debug app on an
  emulator/device, open the Dashboard, and confirm:
  - Weight ("87.2 kg") and Body Fat ("14.0%") cards render the full value
    with clear margin from the track and no glyph clipping.
  - Shorter-value cards (Readiness score, Sleep, Recovery, generic score
    gauges) still render at the original full `headlineMedium` size —
    i.e., no regression/unwanted shrink for values that already fit.
