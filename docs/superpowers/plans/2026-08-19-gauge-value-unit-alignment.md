# Gauge Value/Unit Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep `M3MetricGaugeWithValue`'s value text vertically aligned whether a unit is present or absent, while preserving the existing gauge API and visual constants.

**Architecture:** Replace the overlay's wrap-content `Column` with a private `GaugeValueUnitOverlay` based on `SubcomposeLayout`. It measures the value and optional unit independently, reports their natural combined footprint, and places the value at `unitBlockHeight / 2` so parent centering keeps the value center fixed.

**Tech Stack:** Kotlin, Jetpack Compose Foundation `SubcomposeLayout`, Compose `BasicText`/`TextAutoSize`, Compose UI tests, Robolectric, Gradle.

**Spec:** `docs/superpowers/specs/2026-08-19-gauge-value-unit-alignment-design.md`

## Global Constraints

- Preserve the public `M3MetricGaugeWithValue` signature.
- Preserve gauge geometry, text-bound calculations, dimensions, typography, colors, auto-size settings, overlay width bound, vertical offset, and `metric_gauge_value_overlay` test tag.
- Keep unit-less value placement at the current anchor; a unit may only occupy space below the value.
- Measure actual text placeables so alignment remains correct when auto-size changes font size.
- Do not add dimensions, strings, scoring changes, data changes, or documentation-sync changes.
- Follow TDD: add and run the regression test before implementing the layout.
- Run `./gradlew ktlintFormat`, `./gradlew testDebugUnitTest`, and `./gradlew lintRelease` in the stated verification order.

---

## File Map

- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricGauge.kt` — replace only the value/unit `Column` and add the private measured overlay; retain the public composable and all gauge calculations.
- Modify: `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/M3MetricGaugeTest.kt` — add the regression test comparing value centers with and without a unit; retain existing overlay tests.

## Task 1: Add the failing alignment regression test

**Files:**
- Modify: `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/M3MetricGaugeTest.kt`

**Interfaces:**
- Consumes: existing `M3MetricGaugeWithValue` and `composeTestRule`.
- Produces: a test that fails against the current `Column` implementation and passes only when the value-center invariant is restored.

- [ ] **Step 1: Add the regression test**

Append this test inside `M3MetricGaugeTest`:

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

Add only imports required by compilation; the test already imports the layout, color, node-query, density, and assertion symbols used above.

- [ ] **Step 2: Run the new test against the current implementation**

Run:

```bash
./gradlew :core:ui:testDebugUnitTest --tests "app.readylytics.health.core.ui.components.M3MetricGaugeTest.metricGaugeWithValue_valueTextVerticalCenter_isUnaffectedByUnitPresence"
```

Expected: **FAIL** because the existing wrap-content `Column` centers the value/unit block, moving the value upward when the unit is present. If it fails to compile because the Compose test API requires an explicit bounds import, add that import and rerun; the assertion must remain a value-center comparison.

- [ ] **Step 3: Commit the failing test**

```bash
git add core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/M3MetricGaugeTest.kt
git commit -m "test(ui): capture gauge value unit alignment invariant"
```

## Task 2: Implement the measured value/unit overlay

**Files:**
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricGauge.kt:3-309`

**Interfaces:**
- Consumes: `M3MetricGaugeWithValue`'s existing value/unit parameters, typography constants, `unitSpacing`, `textBoundsWidth`, and vertical offset.
- Produces: private `@Composable fun GaugeValueUnitOverlay(...)` with parameters `valueText: String`, `unitText: String`, `valueColor: Color`, `unitColor: Color`, `unitSpacing: Dp`, and `modifier: Modifier = Modifier`.

- [ ] **Step 1: Update imports**

Remove the layout imports used only by the old overlay: `Arrangement`, `Column`, `Spacer`, and `height`. Add `SubcomposeLayout`, `Constraints`, and `Dp`. Retain all imports still used elsewhere in the file.

- [ ] **Step 2: Replace the overlay call site**

Inside `M3MetricGaugeWithValue`, replace the existing `Column` and its children with:

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
```

Do not change the surrounding `BoxWithConstraints`, `M3MetricGauge`, geometry resolution, or text-bound calculations.

- [ ] **Step 3: Add the private measured overlay**

Add the private composable immediately after `M3MetricGaugeWithValue`. Keep the existing value and unit `BasicText` styles and auto-size settings verbatim. Its measure/layout logic must be:

```kotlin
val hasUnit = unitText.isNotBlank()
SubcomposeLayout(modifier = modifier) { constraints ->
    val looseConstraints =
        constraints.copy(
            minWidth = 0,
            minHeight = 0,
            maxHeight = Constraints.Infinity,
        )

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

    val unitBlockHeightPx =
        if (unitPlaceable != null) unitSpacing.roundToPx() + unitPlaceable.height else 0
    val totalHeight = valuePlaceable.height + unitBlockHeightPx
    val totalWidth = maxOf(valuePlaceable.width, unitPlaceable?.width ?: 0)

    layout(totalWidth, totalHeight) {
        valuePlaceable.placeRelative(
            x = (totalWidth - valuePlaceable.width) / 2,
            y = unitBlockHeightPx / 2,
        )
        unitPlaceable?.placeRelative(
            x = (totalWidth - unitPlaceable.width) / 2,
            y = totalHeight - unitPlaceable.height,
        )
    }
}
```

The measure lambda is a `SubcomposeMeasureScope`, so `unitSpacing.roundToPx()` is valid without an additional density wrapper. Preserve `unitText.isNotBlank()` semantics so blank units do not create a second subcomposition or spacing block.

- [ ] **Step 4: Run the focused test suite**

Run:

```bash
./gradlew :core:ui:testDebugUnitTest --tests "app.readylytics.health.core.ui.components.M3MetricGaugeTest"
```

Expected: **PASS**, including the new alignment test, the overlay tag/offset test, the long-value test, and the existing geometry tests. If semantic bounds are unstable under this Compose/Robolectric combination, revise only the coordinate-reading mechanism while preserving the value-center invariant and rerun.

- [ ] **Step 5: Commit the implementation**

```bash
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricGauge.kt
git commit -m "fix(ui): align gauge values with and without units"
```

## Task 3: Run repository-wide verification and review the visual result

**Files:**
- No source files expected; modify the regression test only if the focused run proves its bounds-reading mechanism is unstable, preserving the same assertion.

**Interfaces:**
- Consumes: the passing implementation and `M3MetricGaugeTest` from Task 2.
- Produces: verified formatting, unit-test, lint, and visual behavior with no unrelated changes.

- [ ] **Step 1: Format the repository**

Run:

```bash
./gradlew ktlintFormat
```

Expected: formatting completes successfully. Inspect the diff afterward and confirm only the planned gauge files changed.

- [ ] **Step 2: Run all debug unit tests**

Run:

```bash
./gradlew testDebugUnitTest
```

Expected: **PASS**. If a test fails, diagnose the failure before changing code; do not broaden the overlay behavior beyond the alignment contract.

- [ ] **Step 3: Run release lint**

Run:

```bash
./gradlew lintRelease
```

Expected: **PASS** with no new lint or detekt findings attributable to the overlay extraction.

- [ ] **Step 4: Perform the visual check**

Launch the app or a Compose preview containing representative dashboard cards such as Body Temp (`34.5` / `°C`) and SpO2 (`98%`). Confirm that value text centers align across the cards, the unit remains below the value, and no text overlaps the horseshoe track. Do not uninstall the production package during device testing.

- [ ] **Step 5: Review final diff and status**

Run:

```bash
git diff --check
git status --short
git log -3 --oneline
```

Expected: no whitespace errors, only the intended commits/files are present, and the working tree contains no unrelated changes.
