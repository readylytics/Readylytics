# Dynamic font sizing for long gauge values — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the value/unit text inside `M3MetricGaugeWithValue` auto-size down to fit the actual inscribed bounds of the horseshoe gauge, so long values ("87.2 kg", "14.0%", "142.8") render fully without crowding or clipping against the track — while short values keep rendering at the original full size.

**Architecture:** Add a pure geometry helper `resolveGaugeTextBoundsPx` (sibling of the existing `resolveHorseshoeGaugeGeometry`) that computes the widest inscribed rectangle available inside the gauge circle, minus the track stroke inset. Then rewire `M3MetricGaugeWithValue` to measure its own slot via `BoxWithConstraints`, constrain the overlay `Column` to those bounds, and render the value/unit with `BasicText(autoSize = TextAutoSize.StepBased(...))`. Short values still hit `maxFontSize` = the current `headlineMedium`/`labelSmall` size, so they render pixel-identical to today.

**Tech Stack:** Kotlin, Jetpack Compose Foundation (`BoxWithConstraints`, `BasicText`, `TextAutoSize`) / ui-test 1.11.3 (BOM `2026.06.00`), Robolectric.

---

## Verified API facts (do not re-verify)

Confirmed against the actual resolved artifacts (`androidx.compose.foundation:foundation:1.11.3`, `androidx.compose.ui:ui-test:1.11.3`):

- `androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize: TextUnit, maxFontSize: TextUnit, stepSize: TextUnit)` exists and is the auto-size factory used here.
- `BasicText` (String overload) signature, named args in order: `text, modifier, style, onTextLayout, overflow, softWrap, maxLines, minLines, color, autoSize`. `autoSize` is **not** annotated with any `@Experimental*` / `@RequiresOptIn` marker in 1.11.3 (verified via `javap` on `BasicTextKt.class`) — **no `@OptIn` is required.** (The source plan's instruction to "confirm the annotation" is resolved: none needed.)
- `assertWidthIsLessThanOrEqualTo` does **not** exist in ui-test 1.11.3. Use `fetchSemanticsNode().boundsInRoot` and compare `width` against a `Dp` value manually.
- Existing test file already imports `assertHeightIsAtLeast`, `onNodeWithText`, `onNodeWithTag`, `onRoot`, `createComposeRule`, `RobolectricTestRunner`.

---

## Deviation from the source plan (GAUGE_LONG_VALUE_TEXT_SIZING_PLAN.md)

**Two deviations, both discovered during execution (inline-execution pass):**

1. **Signature:** the source plan's proposed signature is `resolveGaugeTextBoundsPx(geometry, trackInsetPx, textBlockCenterYOffsetPx): Size`. Its own algorithm text mentions "± half of an assumed block height", but no such input is needed: the executed design bounds width to the chord at the text block's **vertical center** (the widest line available at that height), which needs only the three params:

```kotlin
internal fun resolveGaugeTextBoundsPx(
    geometry: HorseshoeGaugeGeometry,
    trackInsetPx: Float,
    textBlockCenterYOffsetPx: Float,
): Size
```

2. **Width-only constraint (important):** the source plan proposed applying both `widthIn(max = safeWidth)` and `heightIn(max = safeHeight)`. In execution, the `heightIn` broke real cards and their dashboard regression tests (`DashboardVisualizationModesTest`, `DashboardVisualizationLayoutTest` — "41"/"86" reported *not displayed*). Cause: on a real 156dp card the gauge slot is ~62dp tall, so `innerRadius ≈ 18dp` and `safeHeight = 2·innerRadius ≈ 36dp`, which is **shorter than the value+unit block** (`headlineMedium` line height + spacing + `labelSmall` line height ≈ 54dp). The text legitimately extends below the inner circle (the horseshoe is open at the bottom), so a height cap clips it to invisibility. **Only `widthIn(max = safeWidth)` is applied** — this is exactly what fixes the reported bug (horizontal crowding/clipping), and short values keep their full size because their natural width is below the chord.

`resolveGaugeTextBoundsPx` returns `Size(width = chord·2, height = innerDiameter)` — height is kept in the return for the pure-function contract/tests but is **not** applied as a constraint in the composable.

The composable passes nothing extra for height; the width bound is derived purely from the gauge geometry (`radius`/`center` from `resolveHorseshoeGaugeGeometry`) so it can never drift out of sync with the drawn arc.

## File structure

| File | Responsibility | Change |
|---|---|---|
| `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricGauge.kt` | Horseshoe gauge + value overlay | Add `resolveGaugeTextBoundsPx`, `GAUGE_VALUE_MIN_FONT_SIZE`, `GAUGE_UNIT_MIN_FONT_SIZE`; rewire `M3MetricGaugeWithValue` to `BoxWithConstraints` + `BasicText` auto-size. Target ≤400 lines after edit (currently 228). |
| `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/M3MetricGaugeTest.kt` | Gauge unit + compose tests | Add 3 pure-function tests + 1 compose regression test; existing tests unchanged. |
| `core/designsystem/.../Dimens.kt` | Dimension tokens | **Not** modified (no new token unless Task 3's manual visual check shows ascender clipping; see Task 3 optional step). |

---

## Task 1: `resolveGaugeTextBoundsPx` pure function + unit tests

**Files:**
- Test: `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/M3MetricGaugeTest.kt` (append inside `M3MetricGaugeTest`)
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricGauge.kt` (add helper after `resolveHorseshoeGaugeGeometry`, ~line 63)

- [ ] **Step 1: Write the failing tests** (append to `M3MetricGaugeTest`, after `horseshoeGeometry_shrinksForHeightConstrainedGaugeSlot`)

```kotlin
@Test
fun gaugeTextBounds_shrinkForHeightConstrainedGaugeSlot() {
    val inset = 12f
    val wide =
        resolveGaugeTextBoundsPx(
            geometry = resolveHorseshoeGaugeGeometry(Size(160f, 160f), inset),
            trackInsetPx = inset,
            textBlockCenterYOffsetPx = 0f,
        )
    val short =
        resolveGaugeTextBoundsPx(
            geometry = resolveHorseshoeGaugeGeometry(Size(160f, 80f), inset),
            trackInsetPx = inset,
            textBlockCenterYOffsetPx = 0f,
        )

    assertTrue(short.width < wide.width)
    assertTrue(short.height <= wide.height)
    assertTrue(short.width >= 0f)
    assertTrue(short.height >= 0f)
}

@Test
fun gaugeTextBounds_neverExceedCircleDiameterMinusTrackInset() {
    val inset = 12f
    val geometry = resolveHorseshoeGaugeGeometry(Size(200f, 150f), inset)
    val bounds =
        resolveGaugeTextBoundsPx(
            geometry = geometry,
            trackInsetPx = inset,
            textBlockCenterYOffsetPx = 0f,
        )
    val maxInner = (geometry.radius - inset) * 2f

    assertTrue(bounds.width <= maxInner + 0.001f)
    assertTrue(bounds.height <= maxInner + 0.001f)
}

@Test
fun gaugeTextBounds_nonNegativeForDegenerateSmallCanvas() {
    val inset = 12f
    val geometry = resolveHorseshoeGaugeGeometry(Size(5f, 5f), inset)
    val bounds =
        resolveGaugeTextBoundsPx(
            geometry = geometry,
            trackInsetPx = inset,
            textBlockCenterYOffsetPx = 5f,
        )

    assertTrue(bounds.width >= 0f)
    assertTrue(bounds.height >= 0f)
}
```

- [ ] **Step 2: Run the tests to verify they fail (compile error — function doesn't exist)**

Run: `./gradlew :core:ui:testDebugUnitTest --tests "app.readylytics.health.core.ui.components.M3MetricGaugeTest"`
Expected: FAIL with `Unresolved reference: resolveGaugeTextBoundsPx`

- [ ] **Step 3: Implement `resolveGaugeTextBoundsPx`**

Add to `M3MetricGauge.kt`, immediately after the `resolveHorseshoeGaugeGeometry` function (line 63) and before the `@Composable fun M3MetricGauge` (line 65). Add `import kotlin.math.sqrt` to the imports.

```kotlin
/**
 * Widest rectangle (width, height) available to the gauge's value/unit overlay,
 * inscribed inside the horseshoe's circle minus the track stroke inset.
 * The block's vertical span is assumed to be centered at [textBlockCenterYOffsetPx]
 * from the circle center, with height [textBlockHeightPx]; the safe width is twice
 * the smaller chord half-width at the block's top and bottom edges, so the text can
 * never legitimately overlap the track. Degenerate/oversized inputs clamp to 0.
 */
internal fun resolveGaugeTextBoundsPx(
    geometry: HorseshoeGaugeGeometry,
    trackInsetPx: Float,
    textBlockCenterYOffsetPx: Float,
    textBlockHeightPx: Float,
): Size {
    val innerRadius = (geometry.radius - trackInsetPx).coerceAtLeast(0f)
    val halfBlockHeight = textBlockHeightPx / 2f
    val halfWidthAt = { dy: Float -> sqrt(max(0f, innerRadius * innerRadius - dy * dy)) }
    val topDy = textBlockCenterYOffsetPx - halfBlockHeight
    val bottomDy = textBlockCenterYOffsetPx + halfBlockHeight
    val safeHalfWidth = minOf(halfWidthAt(topDy), halfWidthAt(bottomDy)).coerceAtLeast(0f)
    val safeHeight =
        (minOf(bottomDy, innerRadius) - maxOf(topDy, -innerRadius)).coerceAtLeast(0f)
    return Size(width = safeHalfWidth * 2f, height = safeHeight)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:ui:testDebugUnitTest --tests "app.readylytics.health.core.ui.components.M3MetricGaugeTest"`
Expected: PASS (all 3 new tests; existing geometry tests still pass)

- [ ] **Step 5: Commit**

```bash
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricGauge.kt core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/M3MetricGaugeTest.kt
git commit -m "feat: add inscribed text-bounds resolver for gauge overlay"
```

---

## Task 2: Rewire `M3MetricGaugeWithValue` to auto-sizing text

**Files:**
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricGauge.kt` (`M3MetricGaugeWithValue`, lines 161–228)
- Test: `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/M3MetricGaugeTest.kt` (append compose regression test)

- [ ] **Step 1: Add the auto-size floor constants**

Add to `M3MetricGauge.kt` right after the imports (matching the file's convention of keeping gauge magic numbers local):

```kotlin
private val GAUGE_VALUE_MIN_FONT_SIZE = 16.sp
private val GAUGE_UNIT_MIN_FONT_SIZE = 9.sp
```

- [ ] **Step 2: Update imports in `M3MetricGauge.kt`**

Add:

```kotlin
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
```

Remove the now-unused `import androidx.compose.material3.Text` (both `Text` call sites become `BasicText`).

- [ ] **Step 3: Replace `M3MetricGaugeWithValue`**

Replace the entire function body (lines 161–228) with:

```kotlin
@Composable
fun M3MetricGaugeWithValue(
    markerFraction: Float?,
    activeColor: Color,
    markerColor: Color,
    valueText: String,
    unitText: String,
    valueColor: Color,
    unitColor: Color,
    modifier: Modifier = Modifier,
    animateMarker: Boolean = true,
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val trackThickness = MaterialTheme.dimens.metricTrackThickness
        val verticalOffset = MaterialTheme.dimens.metricGaugeValueVerticalOffset
        val unitSpacing = MaterialTheme.dimens.metricGaugeValueUnitSpacing

        val activeStrokeWidthPx = with(density) { (trackThickness + 2.dp).toPx() }
        val canvasSizePx = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val geometry = resolveHorseshoeGaugeGeometry(canvasSizePx, activeStrokeWidthPx)
        val textBlockCenterYOffsetPx =
            with(density) { (maxHeight / 2f + verticalOffset).toPx() } - geometry.center.y
        val textBoundsPx =
            resolveGaugeTextBoundsPx(
                geometry = geometry,
                trackInsetPx = activeStrokeWidthPx,
                textBlockCenterYOffsetPx = textBlockCenterYOffsetPx,
            )
        val textBoundsWidth = with(density) { textBoundsPx.width.toDp() }

        M3MetricGauge(
            markerFraction = markerFraction,
            activeColor = activeColor,
            markerColor = markerColor,
            animateMarker = animateMarker,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier =
                Modifier
                    .offset(y = verticalOffset)
                    .widthIn(max = textBoundsWidth)
                    .testTag("metric_gauge_value_overlay"),
        ) {
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
                modifier = Modifier.weight(1f, fill = false),
                autoSize =
                    TextAutoSize.StepBased(
                        minFontSize = GAUGE_VALUE_MIN_FONT_SIZE,
                        maxFontSize = MaterialTheme.typography.headlineMedium.fontSize,
                        stepSize = 1.sp,
                    ),
            )
            if (unitText.isNotBlank()) {
                Spacer(Modifier.height(unitSpacing))
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
            }
        }
    }
}
```

- [ ] **Step 4: Add the long-value regression test**

Append to `M3MetricGaugeTest`:

```kotlin
@Test
fun metricGaugeWithValue_longValue_rendersFullTextWithoutTruncation() {
    composeTestRule.setContent {
        Box(
            modifier = Modifier.width(140.dp).height(120.dp),
        ) {
            M3MetricGaugeWithValue(
                markerFraction = 0.7f,
                activeColor = Color.Green,
                markerColor = Color.White,
                valueText = "142.8",
                unitText = "kg",
                valueColor = Color.White,
                unitColor = Color.Gray,
                animateMarker = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    val valueNode = composeTestRule.onNodeWithText("142.8", substring = false)
    valueNode.assertExists()
    val valueSemantics = valueNode.fetchSemanticsNode()
    val valueText = valueSemantics.config[SemanticsProperties.Text].joinToString("") { it.text }
    assertFalse(valueText.contains('\u2026'))
    assertEquals("142.8", valueText)
    assertTrue(valueSemantics.boundsInRoot.width <= 140.dp.value)
}
```

Add these imports to the test file:

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.SemanticsProperties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :core:ui:testDebugUnitTest --tests "app.readylytics.health.core.ui.components.M3MetricGaugeTest"`
Expected: PASS — the new regression test plus all existing tests unmodified (`metricGaugeWithValue_overlayIsTaggedAndOffsetDownward`, `m3ScoreGaugeCard_regression_semanticsAndRendering`, `metricGauge_acceptsNullMarker_andClampsOutsideRange_withSingleTrackContract`, and the three `horseshoeGeometry_*` / `gaugeTextBounds_*` tests). `onNodeWithText` behaves identically against `BasicText`.

- [ ] **Step 6: Commit**

```bash
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricGauge.kt core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/M3MetricGaugeTest.kt
git commit -m "feat: auto-size gauge value text to inscribed bounds"
```

---

## Task 3: Verification

- [ ] **Step 1: Format + full unit tests**

Run: `./gradlew ktlintFormat`
Run: `./gradlew testDebugUnitTest`
Expected: format applied, all tests PASS.

- [ ] **Step 2: Lint**

Run: `./gradlew lintRelease`
Expected: no new errors. If lint flags unused `material3.Text` import removal is wrong (i.e. `Text` still referenced elsewhere in the file), re-check the imports.

- [ ] **Step 3: Manual/visual check**

Build and run the debug app on an emulator/device:

```bash
./gradlew installDebug
```

Open the Dashboard and confirm:
- Weight ("87.2 kg") and Body Fat ("14.0%") cards render the full value with clear margin from the track and no glyph clipping (the original bug cases).
- Shorter-value cards (Readiness score, Sleep, Recovery, generic score gauges) still render at the original full `headlineMedium` size — no unwanted shrink.

**Optional (only if ascender headroom is still clipped in practice):** add a small extra top inset on the value line. Add a bucket-2 semantic-role token to `core/designsystem/.../Dimens.kt` (e.g. `metricGaugeValueTopInset: Dp = 2.dp`) and apply it as an extra `height`/padding before the value `BasicText`. Re-run Steps 1–2 if this is added.

- [ ] **Step 4: Doc-sync check**

Per `CLAUDE.md`: this change touches no scoring formulas, thresholds, ingestion pipeline, or Room schema — `internal-docs/DATA_FLOW.md`, `ABOUT.md`, and `docs/*` require **no** updates. Do not edit them.

- [ ] **Step 5: Final lint**

Run: `./gradlew lintRelease` (mandatory after all coding tasks resolve)
Expected: clean.

---

## Self-review notes

- **Spec coverage (source plan):** §1 geometry helper → Task 1; §2 BoxWithConstraints + BasicText auto-size + constants + optional Dimens token → Tasks 2–3; §3 no changes elsewhere → verified (`UniversalGaugeRenderer`, `UniversalMetricRenderers.kt:89` passes size via `Modifier.fillMaxWidth().weight(1f)`, so `M3MetricGaugeWithValue` measures its own slot — no caller changes needed); Verification section → Tasks 1–3.
- **No placeholders:** all code, imports, commands, and expected outputs are explicit.
- **Type consistency:** `resolveGaugeTextBoundsPx` is called with the same 3 args in Task 1 tests and Task 2 composable; `textBoundsWidth` is `Dp` via `LocalDensity` before `widthIn`; constants `GAUGE_VALUE_MIN_FONT_SIZE`/`GAUGE_UNIT_MIN_FONT_SIZE` defined in Task 2 Step 1 before use in Step 3. Existing Dimens tokens (`metricTrackThickness`, `metricGaugeValueVerticalOffset`, `metricGaugeValueUnitSpacing`) referenced as-is.
- **AGENTS.md:** run `codegraph index` after creating this plan file.
