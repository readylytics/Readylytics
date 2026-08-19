# Gauge Value/Unit Alignment Design

## Goal

Ensure the value text in `M3MetricGaugeWithValue` remains at the same vertical
position whether or not a unit is displayed. A present unit continues to render
below the value, while a unit-less value stays at its current baseline position.

## Scope

The change is limited to the reusable gauge overlay and its Compose tests:

- `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricGauge.kt`
- `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/M3MetricGaugeTest.kt`

The public composable signature, gauge geometry, dimensions, typography, colors,
auto-sizing configuration, overlay width bound, vertical offset, and test tag
remain unchanged. No Room, scoring, strings, or documentation-sync behavior is
affected.

## Design

Replace the wrap-content value/unit `Column` with a private
`GaugeValueUnitOverlay` implemented using `SubcomposeLayout`.

The overlay will:

1. Subcompose and measure the value text with the existing style, color, and
   auto-size settings.
2. Measure the unit text only when `unitText.isNotBlank()`, using its existing
   style, color, and auto-size settings.
3. Report the natural combined footprint: value height plus unit height and the
   existing unit spacing when present; width is the larger measured text width.
4. Place the value horizontally centered at `unitBlockHeight / 2` and place the
   unit horizontally centered at the bottom of the footprint.

Because the parent centers the overlay by its total height, the value placement
offset cancels the extra height introduced by the unit. The value's own vertical
center therefore remains at the same parent anchor for both variants. Measuring
actual placeables keeps this invariant valid when `TextAutoSize.StepBased` picks
different font sizes.

The overlay keeps the existing modifier chain:

```kotlin
Modifier
    .offset(y = verticalOffset)
    .widthIn(max = textBoundsWidth)
    .testTag("metric_gauge_value_overlay")
```

The measure pass will use zero minimum constraints, retain the inherited width
limit, and allow text height to measure naturally. Unit spacing is converted to
pixels in the measure scope.

## Testing

Retain the existing tests for the overlay tag/footprint and long-value rendering.
Add a Compose regression test that renders equivalent fixed-size gauge containers
first with a unit and then without one, reads each value node's semantic bounds,
and asserts that their vertical centers match within one pixel. This directly
guards the alignment invariant while allowing the two values to differ in text
content.

Verification will proceed from focused to broad:

1. Format with `./gradlew ktlintFormat`.
2. Run the focused `M3MetricGaugeTest`, then `./gradlew testDebugUnitTest`.
3. Run `./gradlew lintRelease` after coding tasks are complete.
4. Perform a visual comparison of representative unit and unit-less dashboard
   gauges when an app or Compose preview is available.

If semantic bounds are not stable under the repository's Compose/Robolectric
combination, the regression test may compare another stable layout coordinate,
but it must continue to assert the same value-center invariant.

## Non-goals and risks

This design does not change gauge geometry, text bounds calculations, typography,
spacing values, or scoring behavior. The main implementation risk is Compose
constraint behavior around subcomposed text; focused compilation and the existing
long-value test cover that risk before the full verification suite.
