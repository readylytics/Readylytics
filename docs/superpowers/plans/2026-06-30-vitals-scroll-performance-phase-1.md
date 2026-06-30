# Vitals Scroll Performance Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove idle chart animation and marker-driven UI-thread churn that competes with Vitals vertical scrolling.

**Architecture:** Keep Vitals screen and all three Vico charts eagerly composed. Add pure immutable chart render-data preparation, consume it from `TrendChart`, suppress marker callbacks during parent scrolling, and scope halo animation to active selections only.

**Tech Stack:** Kotlin, Jetpack Compose, Vico, JUnit, Robolectric, Gradle

---

## File Structure

- Create `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/TrendChartRenderData.kt`: pure point indexing and summary preparation.
- Create `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/TrendChartRenderDataTest.kt`: boundary and equivalence tests for render-data preparation.
- Create `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/VicoChartTooltipOverlayTest.kt`: Robolectric Compose lifecycle tests for conditional halo composition.
- Modify `core/ui/build.gradle.kts`: enable Compose UI tests under Robolectric unit tests.
- Modify `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/TrendCharts.kt`: consume prepared data and suppress marker churn during parent scroll.
- Modify `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/VicoChartTooltipOverlay.kt`: create pulse animation only for active point halos.

### Task 1: Pure Trend Render Data

**Files:**
- Create: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/TrendChartRenderData.kt`
- Create: `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/TrendChartRenderDataTest.kt`

- [ ] **Step 1: Write failing render-data tests**

Create tests covering empty points, sparse/null points, even and odd medians, stable input order, and O(1) day lookup:

```kotlin
package app.readylytics.health.core.ui.components

import app.readylytics.health.core.ui.common.DailyDataPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrendChartRenderDataTest {
    @Test
    fun `empty input produces empty render data`() {
        val result = buildTrendChartRenderData(emptyList())

        assertEquals(emptyList(), result.validPoints)
        assertEquals(emptyMap(), result.pointByDayOffset)
        assertNull(result.calculatedBaseline)
        assertNull(result.minimum)
        assertNull(result.maximum)
    }

    @Test
    fun `null values are excluded and valid input order is preserved`() {
        val result =
            buildTrendChartRenderData(
                listOf(
                    DailyDataPoint(2, 30f),
                    DailyDataPoint(0, null),
                    DailyDataPoint(1, 10f),
                ),
            )

        assertEquals(listOf(DailyDataPoint(2, 30f), DailyDataPoint(1, 10f)), result.validPoints)
        assertEquals(DailyDataPoint(1, 10f), result.pointByDayOffset[1])
        assertNull(result.pointByDayOffset[0])
        assertEquals(20f, result.calculatedBaseline)
        assertEquals(10f, result.minimum)
        assertEquals(30f, result.maximum)
    }

    @Test
    fun `odd value count uses middle sorted value`() {
        val result =
            buildTrendChartRenderData(
                listOf(DailyDataPoint(0, 40f), DailyDataPoint(1, 10f), DailyDataPoint(2, 20f)),
            )

        assertEquals(20f, result.calculatedBaseline)
    }
}
```

- [ ] **Step 2: Run tests and confirm missing-symbol failure**

Run:

```powershell
.\gradlew :core:ui:testDebugUnitTest --tests "*TrendChartRenderDataTest"
```

Expected: compilation fails because `buildTrendChartRenderData` does not exist.

- [ ] **Step 3: Implement immutable render-data preparation**

Create production code:

```kotlin
package app.readylytics.health.core.ui.components

import androidx.compose.runtime.Immutable
import app.readylytics.health.core.ui.common.DailyDataPoint

@Immutable
internal data class TrendChartRenderData(
    val validPoints: List<DailyDataPoint>,
    val pointByDayOffset: Map<Int, DailyDataPoint>,
    val calculatedBaseline: Float?,
    val minimum: Float?,
    val maximum: Float?,
)

internal fun buildTrendChartRenderData(points: List<DailyDataPoint>): TrendChartRenderData {
    val validPoints = points.filter { it.value != null }
    val values = validPoints.map { requireNotNull(it.value) }
    val sortedValues = values.sorted()
    val midpoint = sortedValues.size / 2
    val median =
        when {
            sortedValues.isEmpty() -> null
            sortedValues.size % 2 == 0 -> (sortedValues[midpoint - 1] + sortedValues[midpoint]) / 2f
            else -> sortedValues[midpoint]
        }
    return TrendChartRenderData(
        validPoints = validPoints,
        pointByDayOffset = validPoints.associateBy(DailyDataPoint::dayOffset),
        calculatedBaseline = median,
        minimum = values.minOrNull(),
        maximum = values.maxOrNull(),
    )
}
```

- [ ] **Step 4: Run focused tests**

Run same Gradle command. Expected: all `TrendChartRenderDataTest` tests pass.

- [ ] **Step 5: Commit render-data unit**

```powershell
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/TrendChartRenderData.kt core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/TrendChartRenderDataTest.kt
git commit -m "perf(charts): prepare trend render data once"
```

### Task 2: Suppress Marker Work During Parent Scroll

**Files:**
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/TrendCharts.kt`
- Test: `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/TrendChartRenderDataTest.kt`

- [ ] **Step 1: Replace repeated point scans with remembered render data**

At start of `TrendChart`, remember prepared data and use it for empty detection, baseline, bounds, model transaction, and marker lookup:

```kotlin
val renderData = remember(points) { buildTrendChartRenderData(points) }

if (renderData.validPoints.isEmpty()) {
    EmptyChartPlaceholder(modifier = modifier)
    return
}

val calculatedBaseline = requireNotNull(renderData.calculatedBaseline)
val baselineValue = baseline ?: calculatedBaseline
val (minY, maxY) =
    remember(renderData, minYOverride, maxYOverride) {
        val lo = requireNotNull(renderData.minimum)
        val hi = requireNotNull(renderData.maximum)
        val computedMin = kotlin.math.floor(lo * 0.9f).toDouble()
        val computedMax = kotlin.math.ceil(hi * 1.1f).toDouble()
        (minYOverride ?: computedMin) to (maxYOverride ?: computedMax)
    }
```

Update transaction:

```kotlin
LaunchedEffect(renderData.validPoints) {
    modelProducer.runTransaction {
        lineModel {
            series(
                x = renderData.validPoints.map(DailyDataPoint::dayOffset),
                y = renderData.validPoints.map { requireNotNull(it.value).toDouble() },
            )
        }
    }
}
```

- [ ] **Step 2: Guard stable marker listener before allocation or state writes**

Keep `rememberUpdatedState(parentScrollInProgress)` and make marker callback return before date formatting or lookup while parent scroll is active:

```kotlin
val markerVisibilityListener =
    rememberChartMarkerVisibilityListener { x, _, canvasX, canvasY ->
        if (currentParentScrollInProgress()) return@rememberChartMarkerVisibilityListener

        val dayOffset = x.toInt()
        val nearest = renderData.pointByDayOffset[dayOffset]
        val date = ChartUtils.dayOffsetToLocalDate(dayOffset, rangeStartMs)
        val valueText = formatTrendTooltipValue(nearest?.value, tooltipDecimalPlaces, hideUnitInTooltip, baselineUnit)
        val nextOffset = Offset(canvasX, canvasY)
        val nextTooltip =
            DataPointTooltipData(
                valueText = valueText,
                dateText = ChartUtils.formatTooltipDate(date),
                offset = androidx.compose.ui.unit.IntOffset(canvasX.toInt(), canvasY.toInt()),
            )
        if (selectedPointOffset != nextOffset) selectedPointOffset = nextOffset
        if (tooltipState != nextTooltip) tooltipState = nextTooltip
    }
```

Extract exact formatting logic without changing output:

```kotlin
internal fun formatTrendTooltipValue(
    value: Float?,
    decimalPlaces: Int,
    hideUnit: Boolean,
    unit: String,
): String {
    if (value == null) return "—"
    val formatted =
        if (decimalPlaces == 0) value.roundToInt().toString()
        else String.format("%.${decimalPlaces}f", value)
    return if (hideUnit) formatted else "$formatted $unit"
}

internal fun shouldProcessTrendMarker(parentScrollInProgress: Boolean): Boolean =
    !parentScrollInProgress

internal fun <T> shouldAssignTrendMarkerState(current: T, next: T): Boolean =
    current != next
```

- [ ] **Step 3: Add formatter regression cases**

Append tests asserting null, integer, decimal, hidden-unit, and visible-unit output:

```kotlin
@Test
fun `tooltip formatting preserves chart output contract`() {
    assertEquals("—", formatTrendTooltipValue(null, 0, false, "ms"))
    assertEquals("42 ms", formatTrendTooltipValue(42.4f, 0, false, "ms"))
    assertEquals("42.4 %", formatTrendTooltipValue(42.44f, 1, false, "%"))
    assertEquals("42", formatTrendTooltipValue(42f, 0, true, "steps"))
}

@Test
fun `marker work is suppressed during parent scroll`() {
    assertFalse(shouldProcessTrendMarker(parentScrollInProgress = true))
    assertTrue(shouldProcessTrendMarker(parentScrollInProgress = false))
}

@Test
fun `equivalent marker state is not assigned`() {
    assertFalse(shouldAssignTrendMarkerState(current = Offset(4f, 8f), next = Offset(4f, 8f)))
    assertTrue(shouldAssignTrendMarkerState(current = Offset(4f, 8f), next = Offset(5f, 8f)))
}
```

Use helpers in callback before expensive work and before each state assignment:

```kotlin
if (!shouldProcessTrendMarker(currentParentScrollInProgress())) {
    return@rememberChartMarkerVisibilityListener
}
// Build nextOffset and nextTooltip after this guard.
if (shouldAssignTrendMarkerState(selectedPointOffset, nextOffset)) selectedPointOffset = nextOffset
if (shouldAssignTrendMarkerState(tooltipState, nextTooltip)) tooltipState = nextTooltip
```

- [ ] **Step 4: Run core UI tests**

```powershell
.\gradlew :core:ui:testDebugUnitTest
```

Expected: all tests pass; chart output tests remain unchanged.

- [ ] **Step 5: Commit marker hot-path fix**

```powershell
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/TrendCharts.kt core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/TrendChartRenderDataTest.kt
git commit -m "perf(charts): suppress marker churn while scrolling"
```

### Task 3: Stop Idle Tooltip Animation

**Files:**
- Modify: `core/ui/build.gradle.kts`
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/VicoChartTooltipOverlay.kt`
- Create: `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/VicoChartTooltipOverlayTest.kt`

- [ ] **Step 1: Enable Robolectric Compose UI tests**

Add unit-test dependencies in `core/ui/build.gradle.kts`:

```kotlin
testImplementation(platform(libs.androidx.compose.bom))
testImplementation(libs.androidx.compose.ui.test.junit4)
testImplementation(libs.androidx.compose.ui.test.manifest)
```

- [ ] **Step 2: Write failing halo lifecycle tests**

Create:

```kotlin
package app.readylytics.health.core.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VicoChartTooltipOverlayTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `idle overlay does not compose animated halo`() {
        composeRule.setContent {
            VicoChartTooltipOverlay(
                selectedPointOffset = null,
                modifier = Modifier.size(200.dp),
            )
        }

        composeRule.onNodeWithTag(VICO_POINT_HALO_TAG).assertDoesNotExist()
    }

    @Test
    fun `selected point composes animated halo`() {
        composeRule.setContent {
            VicoChartTooltipOverlay(
                selectedPointOffset = Offset(50f, 60f),
                modifier = Modifier.size(200.dp),
            )
        }

        composeRule.onNodeWithTag(VICO_POINT_HALO_TAG).assertExists()
    }
}
```

Run:

```powershell
.\gradlew :core:ui:testDebugUnitTest --tests "*VicoChartTooltipOverlayTest"
```

Expected: compilation fails because `VICO_POINT_HALO_TAG` does not exist.

- [ ] **Step 3: Move halo animation into conditionally composed child**

Remove `rememberInfiniteTransition` and animated state from top-level `VicoChartTooltipOverlay`. Keep guideline Canvas static, then compose this child only when `tapY` exists:

```kotlin
if (tapY != null) {
    PulsingPointHalo(
        center = Offset(clampedTapX, tapY),
        color = pulseColor,
        modifier = Modifier.fillMaxSize(),
    )
}
```

Add child:

```kotlin
internal const val VICO_POINT_HALO_TAG = "vico_point_halo"

@Composable
private fun PulsingPointHalo(
    center: Offset,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "vicoHaloTransition")
    val haloRadiusCoeff by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "vicoHaloRadiusCoeff",
    )
    val haloAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "vicoHaloAlpha",
    )
    Canvas(modifier = modifier.testTag(VICO_POINT_HALO_TAG)) {
        drawCircle(
            color = color.copy(alpha = haloAlpha),
            center = center,
            radius = 8.dp.toPx() * haloRadiusCoeff,
        )
        drawCircle(color = color, center = center, radius = 4.dp.toPx())
    }
}
```

Top-level Canvas continues drawing vertical guideline only. No selection means `PulsingPointHalo` leaves composition and its infinite transition is cancelled.

- [ ] **Step 4: Run halo tests and compile Vitals**

```powershell
.\gradlew :core:ui:testDebugUnitTest --tests "*VicoChartTooltipOverlayTest"
.\gradlew :feature:vitals:compileDebugKotlin
```

Expected: halo lifecycle tests pass and Vitals compiles.

- [ ] **Step 5: Run regression suite and formatting**

```powershell
.\gradlew ktlintFormat
.\gradlew :core:ui:testDebugUnitTest :feature:vitals:testDebugUnitTest
.\gradlew lint
```

Expected: all commands succeed.

- [ ] **Step 6: Refresh code index for new files**

```powershell
codegraph index
```

Expected: index completes with new render-data source and test paths.

- [ ] **Step 7: Commit overlay fix**

```powershell
git add core/ui/build.gradle.kts core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/VicoChartTooltipOverlay.kt core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/VicoChartTooltipOverlayTest.kt
git commit -m "perf(charts): animate tooltip halo only when visible"
```

## Phase 1 Exit Check

Phase 1 exits when focused behavior tests, full unit tests, formatting, compile checks, and lint pass. Do not claim measured frame-time improvement. Phase 2 is not automatic; activate it only after a separate user report says phase 1 remains insufficient.
