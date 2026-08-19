# Plan: Align gauge value position regardless of unit presence

Status: **not yet implemented** — this document is the complete, self-contained spec.
No app code has been changed; only this file was added.

## Problem

`M3MetricGaugeWithValue` (the horseshoe gauge with a value/unit overlay — used for cards
like Body Temp, SpO2, BP) renders the value line at a different visual height depending on
whether a unit is shown below it:

- Gauges **with** a unit (e.g. "34.5" / "°C") show the value noticeably **higher**.
- Gauges **without** a unit (e.g. "98%") show the value lower, at the intended baseline
  position.

Dashboards mix both kinds of gauge cards side by side, so the numbers visibly don't sit on
the same line. The fix: make the value's vertical position identical in both cases, with
the unit (when present) hanging below it — never move where a unit-less gauge's value sits
today.

## Root cause

File: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricGauge.kt`,
`M3MetricGaugeWithValue`, current lines 255–309.

The value and unit `BasicText`s live in one `Column` with
`verticalArrangement = Arrangement.Center`. The `Column` is wrap-content (no explicit
height), so `Arrangement.Center` has no extra space to distribute and behaves exactly like
`Arrangement.Top` — it does nothing. The whole `Column` is centered inside the enclosing
`BoxWithConstraints` via `contentAlignment = Alignment.Center` and then shifted down by the
fixed `MaterialTheme.dimens.metricGaugeValueVerticalOffset`.

Because it's the **Column's** vertical center (not the value text's own center) that lands
at `box center + verticalOffset`, adding a unit grows the column downward, which pushes the
value text's own center **up** relative to the no-unit case, where the column is just the
value text.

## Fix approach (precise, measurement-based)

Replace the `Column` with a small custom two-pass layout (`SubcomposeLayout`) so the value
text's own vertical center is always pinned to the same anchor point
(`box center + verticalOffset`), whether or not a unit is present — the unit is measured
and placed below it. This stays exact even when `TextAutoSize.StepBased` shrinks the value
or unit font for long text, because it measures the actual rendered text rather than
estimating a compensation offset.

**Algorithm:**
1. Subcompose + measure the value `BasicText` with loose constraints (width bounded by the
   existing `widthIn(max = textBoundsWidth)` already applied to the overlay's modifier,
   height unbounded) — same style/autoSize/color as today.
2. If `unitText.isNotBlank()`, subcompose + measure the unit `BasicText` the same way (same
   style/autoSize/color as today).
3. `unitBlockHeightPx = if (hasUnit) unitSpacingPx + unit.height else 0`.
4. Report total layout height `totalHeight = value.height + unitBlockHeightPx` — identical
   to what the `Column`'s natural wrap-content height is today, so nothing about the
   overlay's overall footprint changes.
5. Place the value at `y = unitBlockHeightPx / 2` (i.e. `0` when there's no unit — same as
   today) and the unit, if present, flush to the bottom: `y = totalHeight - unit.height`.
   Center both horizontally.
6. That `y = unitBlockHeightPx / 2` value-placement offset is the actual fix: it exactly
   cancels the extra `-totalHeight/2` the parent `Box`'s `Alignment.Center` placement
   applies for the taller (value + unit) block, so the value text's absolute center stays
   at `box center + verticalOffset` in both cases.

Everything else is unchanged: the outer `Modifier.offset(y = verticalOffset)
.widthIn(max = textBoundsWidth).testTag("metric_gauge_value_overlay")` moves from the
`Column` to the new layout as-is. `M3MetricGaugeWithValue`'s public signature, the gauge
geometry/text-bounds math (`resolveHorseshoeGaugeGeometry`, `resolveGaugeTextBoundsPx`),
`Dimens.metricGaugeValueVerticalOffset` / `metricGaugeValueUnitSpacing`, and the value/unit
typography + auto-size configs are all reused verbatim. No new `Dimens` entries needed.

## Exact code changes

### 1. `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricGauge.kt`

**Imports** — remove (each is used exactly once today, only inside the block being
replaced, confirmed via grep):
```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
```

Add:
```kotlin
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
```

**Replace** the body of `M3MetricGaugeWithValue` from the `Column(` call (current line 255)
through its closing `}` (current line 309) with:

```kotlin
        GaugeValueUnitOverlay(
            valueText = valueText,
            unitText = unitText,
            valueColor = valueColor,
            unitColor = unitColor,
            unitSpacing = unitSpacing,
            modifier =
                Modifier
                    .offset(y = verticalOffset)
                    .widthIn(max = textBoundsWidth)
                    .testTag("metric_gauge_value_overlay"),
        )
    }
}

/**
 * Lays out the gauge's value text and, when present, its unit text below it, so the
 * *value's own* vertical center always lands where this composable is placed — independent
 * of whether a unit is present. A plain `Column` can't do this: its `Arrangement.Center`
 * only centers within extra space the column doesn't have (it's wrap-content), so it
 * behaves like `Arrangement.Top` and the value shifts up whenever a unit is added below it.
 * This measures both texts, then places the value at `y = unitBlockHeightPx / 2` — which
 * exactly cancels the extra height the parent's `Alignment.Center` placement would
 * otherwise apply for the taller (value + unit) block.
 */
@Composable
private fun GaugeValueUnitOverlay(
    valueText: String,
    unitText: String,
    valueColor: Color,
    unitColor: Color,
    unitSpacing: Dp,
    modifier: Modifier = Modifier,
) {
    val hasUnit = unitText.isNotBlank()
    SubcomposeLayout(modifier = modifier) { constraints ->
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)

        val valuePlaceable =
            subcompose("value") {
                BasicText(
                    text = valueText,
                    style =
                        MaterialTheme.typography.headlineMedium.copy(
                            lineHeightStyle =
                                LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.Both,
                                ),
                        ),
                    color = { valueColor },
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    autoSize =
                        TextAutoSize.StepBased(
                            minFontSize = GAUGE_VALUE_MIN_FONT_SIZE,
                            maxFontSize = MaterialTheme.typography.headlineMedium.fontSize,
                            stepSize = 1.sp,
                        ),
                )
            }.first().measure(looseConstraints)

        val unitPlaceable =
            if (hasUnit) {
                subcompose("unit") {
                    BasicText(
                        text = unitText,
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                textAlign = TextAlign.Center,
                                lineHeightStyle =
                                    LineHeightStyle(
                                        alignment = LineHeightStyle.Alignment.Center,
                                        trim = LineHeightStyle.Trim.Both,
                                    ),
                            ),
                        color = { unitColor },
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        autoSize =
                            TextAutoSize.StepBased(
                                minFontSize = GAUGE_UNIT_MIN_FONT_SIZE,
                                maxFontSize = MaterialTheme.typography.labelSmall.fontSize,
                                stepSize = 1.sp,
                            ),
                    )
                }.first().measure(looseConstraints)
            } else {
                null
            }

        val unitBlockHeightPx = if (unitPlaceable != null) unitSpacing.roundToPx() + unitPlaceable.height else 0
        val totalHeight = valuePlaceable.height + unitBlockHeightPx
        val totalWidth = maxOf(valuePlaceable.width, unitPlaceable?.width ?: 0)

        layout(totalWidth, totalHeight) {
            val valueY = unitBlockHeightPx / 2
            valuePlaceable.placeRelative(
                x = (totalWidth - valuePlaceable.width) / 2,
                y = valueY,
            )
            unitPlaceable?.placeRelative(
                x = (totalWidth - unitPlaceable.width) / 2,
                y = totalHeight - unitPlaceable.height,
            )
        }
    }
}
```

(The final `}` above closes `M3MetricGaugeWithValue`'s `BoxWithConstraints { ... }` and
function body, matching the two closing braces that followed the old `Column` block.)

Note `unitSpacing.roundToPx()` is called directly with no `with(density) { }` wrapper: the
`SubcomposeLayout` measure lambda receiver (`SubcomposeMeasureScope`) already implements
`Density`, so `Dp.roundToPx()` is available directly inside it — same as it's available
directly inside a plain `Layout {}` or `Modifier.layout {}` block.

### 2. `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/M3MetricGaugeTest.kt`

Existing tests (`metricGaugeWithValue_overlayIsTaggedAndOffsetDownward`,
`metricGaugeWithValue_longValue_rendersFullTextWithoutTruncation`) require no changes —
the `metric_gauge_value_overlay` tag and outer behavior are unchanged.

Add this regression test, which directly encodes the alignment goal so it can't silently
regress:

```kotlin
    @Test
    fun metricGaugeWithValue_valueTextVerticalCenter_isUnaffectedByUnitPresence() {
        composeTestRule.setContent {
            Box(modifier = Modifier.width(140.dp).height(120.dp)) {
                M3MetricGaugeWithValue(
                    markerFraction = 0.6f,
                    activeColor = Color.Green,
                    markerColor = Color.White,
                    valueText = "34.5",
                    unitText = "°C",
                    valueColor = Color.White,
                    unitColor = Color.Gray,
                    animateMarker = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        val withUnitBounds = composeTestRule.onNodeWithText("34.5").fetchSemanticsNode().boundsInRoot
        val withUnitCenterY = withUnitBounds.top + withUnitBounds.height / 2f

        composeTestRule.setContent {
            Box(modifier = Modifier.width(140.dp).height(120.dp)) {
                M3MetricGaugeWithValue(
                    markerFraction = 0.6f,
                    activeColor = Color.Green,
                    markerColor = Color.White,
                    valueText = "98",
                    unitText = "",
                    valueColor = Color.White,
                    unitColor = Color.Gray,
                    animateMarker = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        val withoutUnitBounds = composeTestRule.onNodeWithText("98").fetchSemanticsNode().boundsInRoot
        val withoutUnitCenterY = withoutUnitBounds.top + withoutUnitBounds.height / 2f

        assertEquals(withoutUnitCenterY, withUnitCenterY, 1f)
    }
```

This uses `composeTestRule.setContent` twice in the same test to compare two independent
compositions (each call replaces the previous content), matching the pattern already used
elsewhere in this test class of rendering into a fixed-size `Box`.

## Verification

1. `./gradlew ktlintFormat`
2. `./gradlew testDebugUnitTest` — in particular `M3MetricGaugeTest`
   (`:core:ui:testDebugUnitTest --tests "*.M3MetricGaugeTest"`), plus
   `DashboardMetricCardModesTest` / `DashboardScreenTest` since they exercise gauge
   rendering on dashboard cards.
3. `./gradlew lintRelease` once all coding tasks in the session are done, per project
   convention.
4. Visual check: launch the app (or a Compose preview) and compare a gauge with a unit
   (Body Temp) against one without (SpO2) side by side, matching the screenshot that
   motivated this change, to confirm the value numbers now sit at the same height.
