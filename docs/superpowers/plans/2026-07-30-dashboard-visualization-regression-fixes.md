# Dashboard Visualization Regression Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the `main` Value and Gauge card appearances, correct Bar mode to use continuous progress with external values, and enforce one Readylytics active color plus one theme-aware neutral track across shared gauges.

**Architecture:** Preserve persisted display modes, `DashboardCardCatalog`, typed metric visuals, and all existing normalization. Split the over-limit presentation factory only along the recovery-metric boundary being repaired, then make the shared card shell mode-aware and reduce `M3MetricGauge` to a two-color progress primitive used across all tabs.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Readylytics design system, JUnit 4, Robolectric, AndroidX Compose UI Test.

## Global Constraints

- Use `main` as the source of truth for Value and Gauge typography, alignment, padding, hierarchy, shape, and dimensions.
- Preserve `MaterialTheme.dimens.cardHeight` at 156 dp in every display mode.
- Resolve active visualization colors only through the existing `MetricStatus.gaugeColor()` implementation.
- Derive neutral tracks only from `MaterialTheme.colorScheme`; do not hardcode a color.
- Do not render threshold bands, future classifications, marker bubbles, baseline markers, target markers, range markers, or visible status labels.
- Keep status classifications in localized merged-card accessibility descriptions.
- Do not modify scoring, normalization, baselines, Health Connect, Room, ordering, visibility, persistence, or supported-mode definitions.
- Daily Steps remains its existing fixed bespoke Bar.
- Use `MaterialTheme.shapes.large`, M3 cards, Readylytics spacing tokens, and theme typography.
- Use M3 clickable `Card` overloads for card interaction states and retain 48 dp tooltip/menu targets.
- Keep production Kotlin files below 800 lines and target at most 400 lines.
- Put dashboard-owned strings in `feature/dashboard/src/main/res/values/strings.xml`, consistent with the approved foundation plan and non-transitive resources.
- Run `codegraph index` after creating files and `codegraph sync` after the presentation-factory extraction.
- Mandatory final verification is `./gradlew ktlintFormat`, `./gradlew testDebugUnitTest`, and `./gradlew lintRelease`.

---

## File Map

### Create

- `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardRecoveryMetricPresentationFactory.kt`
  - Builds Sleep duration, HRV, Sleep RHR, Resting HR, and RAS presentations.
- `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardRecoveryMetricPresentationFactoryTest.kt`
  - Guards original statuses, time ranges, units, and tooltips.
- `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardVisualizationRegressionTest.kt`
  - Robolectric Compose coverage for Value, Gauge, Bar, semantics, dimensions, and interaction.

### Modify

- `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricGauge.kt`
  - Enforces one neutral track and one continuous active arc.
- `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3ScoreGaugeCard.kt`
  - Adopts the simplified shared gauge while preserving `main` content.
- `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/M3MetricGaugeTest.kt`
  - Guards the shared API and non-dashboard Gauge rendering.
- `feature/dashboard/build.gradle.kts`
  - Adds Compose UI Test dependencies for Robolectric tests.
- `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardMetricPresentationFactory.kt`
  - Delegates the repaired recovery metrics to the focused factory and falls below 800 lines.
- `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCard.kt`
  - Restores mode-aware card roles, headers, spacing, M3 interactions, and stable dispatch.
- `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricRenderers.kt`
  - Restores Value/Gauge hierarchy and replaces segmented Bar rendering.
- `feature/dashboard/src/main/res/values/strings.xml`
  - Adds the localized sleep-session time-range format if no existing format resource is suitable.

---

### Task 1: Enforce the Shared Two-Color Gauge Contract

**Files:**

- Modify: `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/M3MetricGaugeTest.kt:17-58`
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricGauge.kt:28-143`
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3ScoreGaugeCard.kt:196-208`

**Interfaces:**

- Consumes: `markerFraction: Float?`, `activeColor: Color`, Material 3 `ColorScheme`.
- Produces:

```kotlin
@Composable
fun M3MetricGauge(
    markerFraction: Float?,
    activeColor: Color,
    modifier: Modifier = Modifier,
    animateMarker: Boolean = true,
)

@Composable
fun metricVisualizationTrackColor(): Color
```

- Removes: `M3GaugeSegment` and the `segments` parameter.

- [ ] **Step 1: Change the primitive test to the desired API**

Replace segment-based calls with:

```kotlin
@Test
fun metricGauge_acceptsNullMarker_andClampsOutsideRange_withSingleTrackContract() {
    composeTestRule.setContent {
        M3MetricGauge(
            markerFraction = null,
            activeColor = Color.Red,
            animateMarker = false,
        )
        M3MetricGauge(
            markerFraction = 1.5f,
            activeColor = Color.Red,
            animateMarker = false,
        )
    }

    val unmergedRoot = composeTestRule.onRoot(useUnmergedTree = true)
    assert(unmergedRoot.fetchSemanticsNode().children.isEmpty()) {
        "Expected gauge Canvas to add no semantic children"
    }
}
```

- [ ] **Step 2: Run the focused test and confirm RED**

Run:

```bash
./gradlew :core:ui:testDebugUnitTest --tests '*M3MetricGaugeTest.metricGauge_acceptsNullMarker*'
```

Expected: compilation fails because `segments` is still required.

- [ ] **Step 3: Replace segmented-track drawing with a theme-aware full track**

Delete `M3GaugeSegment`. Add:

```kotlin
@Composable
fun metricVisualizationTrackColor(): Color =
    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
```

Inside `M3MetricGauge`, resolve `val trackColor = metricVisualizationTrackColor()`.
Draw it first over the complete 180-degree arc:

```kotlin
drawArc(
    color = trackColor,
    startAngle = 180f,
    sweepAngle = 180f,
    useCenter = false,
    topLeft = topLeft,
    size = arcSize,
    style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
)
```

Retain the existing clamped animation and draw one active arc:

```kotlin
if (markerFraction != null && progressToDraw > 0f) {
    drawArc(
        color = activeColor,
        startAngle = 180f,
        sweepAngle = 180f * progressToDraw,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
    )
}
```

Use the rounded stroke cap as the active arc endpoint; do not draw an endpoint
dot or any classification, reference, baseline, or target marker.

- [ ] **Step 4: Update `M3ScoreGaugeCard` without changing its layout**

Replace its segment list with:

```kotlin
M3MetricGauge(
    markerFraction = markerFraction,
    activeColor = progressColor,
    animateMarker = true,
)
```

Do not change its title, centered value/unit, delta chip, card role, padding,
typography, or `MetricStatus.gaugeColor()` call.

- [ ] **Step 5: Run shared Gauge tests and confirm GREEN**

Run:

```bash
./gradlew :core:ui:testDebugUnitTest --tests '*M3MetricGaugeTest'
```

Expected: both primitive and `M3ScoreGaugeCard` regression tests pass.

- [ ] **Step 6: Prove no segmented API remains**

Run:

```bash
rg -n 'M3GaugeSegment|segments\\s*=' --glob '*.kt' core feature app
```

Expected: no matches.

- [ ] **Step 7: Commit**

```bash
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricGauge.kt core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3ScoreGaugeCard.kt core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/M3MetricGaugeTest.kt
git commit -m "fix(ui): enforce continuous shared gauges"
```

---

### Task 2: Restore Recovery Metric Presentation Details

**Files:**

- Create: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardRecoveryMetricPresentationFactory.kt`
- Create: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardRecoveryMetricPresentationFactoryTest.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardMetricPresentationFactory.kt:29-870`
- Modify: `feature/dashboard/src/main/res/values/strings.xml:1-69`

**Interfaces:**

- Consumes: `DailySummary?`, `DailyMetrics?`, `UserPreferences`,
  `SleepSessionSummary?`, and `ResourceProvider`.
- Produces:

```kotlin
internal class DashboardRecoveryMetricPresentationFactory(
    private val resourceProvider: ResourceProvider,
) {
    fun build(
        summary: DailySummary?,
        metrics: DailyMetrics?,
        preferences: UserPreferences,
        lastSleepSession: SleepSessionSummary?,
    ): Map<CardId, DashboardMetricPresentation>
}
```

- Produces entries for exactly `SLEEP_DURATION`, `HRV`, `SLEEP_RHR`,
  `RESTING_HR`, and `RAS_DAILY`.

- [ ] **Step 1: Write failing presentation regression tests**

Create tests using the real existing status extensions through the public
factory output:

```kotlin
@Test
fun `recovery cards reuse original domain statuses`() {
    val summary =
        DailySummary(
            date = date,
            sleepDurationMinutes = 240,
            nocturnalHrv = 40,
            hrvBaseline = 80,
            restingHeartRate = 72,
            restingHrRatio = 1.2f,
            isCalibrating = false,
            totalRasWorkoutOnly = 60f,
        )
    val preferences =
        UserPreferences(
            goalSleepHours = 8f,
            hrvOptimalThreshold = 1.05f,
            hrvWarningThreshold = 0.9f,
            rhrOptimalThreshold = 0.95f,
            rhrWarningThreshold = 1.05f,
        )

    val cards = buildCards(summary, preferences, null)

    assertEquals(MetricStatus.POOR, cards.getValue(CardId.SLEEP_DURATION).status)
    assertEquals(MetricStatus.POOR, cards.getValue(CardId.HRV).status)
    assertEquals(MetricStatus.POOR, cards.getValue(CardId.RESTING_HR).status)
    assertEquals(MetricStatus.WARNING, cards.getValue(CardId.RAS_DAILY).status)
}
```

Add the time-range/tooltip test:

```kotlin
@Test
fun `sleep time range and original tooltip content are retained`() {
    val zone = ZoneId.systemDefault()
    val start =
        ZonedDateTime.of(2026, 7, 29, 22, 51, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
    val end =
        ZonedDateTime.of(2026, 7, 30, 6, 2, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
    val session = SleepSessionSummary(0.9f, start, end)

    val cards = buildCards(baseSummary.copy(sleepDurationMinutes = 431), preferences, session)

    assertTrue(cards.getValue(CardId.SLEEP_DURATION).secondaryText.orEmpty().contains("→"))
    assertTrue(cards.getValue(CardId.SLEEP_DURATION).tooltip.isNotBlank())
    assertTrue(cards.getValue(CardId.HRV).tooltip.isNotBlank())
    assertTrue(cards.getValue(CardId.RESTING_HR).tooltip.isNotBlank())
    assertTrue(cards.getValue(CardId.RAS_DAILY).tooltip.isNotBlank())
}
```

Stub each resource ID distinctly so non-blank assertions cannot pass from a
relaxed empty default.

- [ ] **Step 2: Run the presentation tests and confirm RED**

Run:

```bash
./gradlew :feature:dashboard:testDebugUnitTest --tests '*DashboardRecoveryMetricPresentationFactoryTest'
```

Expected: failures show the current simplified statuses, missing sleep-session
secondary text, and blank tooltips.

- [ ] **Step 3: Extract the five recovery presentation builders**

Move the current Sleep duration through RAS construction from
`DashboardMetricPresentationFactory.build` into the new focused class. In the
top-level factory, retain:

```kotlin
val metrics = summary?.let { DailyMetricsMapper.toMetrics(it, preferences) }

map.putAll(
    DashboardRecoveryMetricPresentationFactory(resourceProvider).build(
        summary = summary,
        metrics = metrics,
        preferences = preferences,
        lastSleepSession = lastSleepSession,
    ),
)
```

The top-level factory must fall below 800 lines; the new factory should remain
below 400 lines.

Give the extracted factory its own resource-only accessibility helpers so it
does not depend on private members of the top-level factory:

```kotlin
private fun classificationText(status: MetricStatus): String =
    resourceProvider.getString(
        when (status) {
            MetricStatus.OPTIMAL -> CoreUiR.string.metric_status_optimal
            MetricStatus.NEUTRAL -> CoreUiR.string.metric_status_neutral
            MetricStatus.WARNING -> CoreUiR.string.metric_status_warning
            MetricStatus.POOR -> CoreUiR.string.metric_status_poor
            MetricStatus.NO_DATA,
            MetricStatus.CALIBRATING,
            -> CoreUiR.string.metric_status_calibrating
        },
    )

private fun unavailableDescription(
    title: String,
    reason: DashboardMetricUnavailableReason,
): String =
    resourceProvider.getString(
        DashboardR.string.semantics_unavailable_format,
        title,
        resourceProvider.getString(
            when (reason) {
                DashboardMetricUnavailableReason.MISSING_VALUE ->
                    CoreUiR.string.metric_unavailable_missing_value
                DashboardMetricUnavailableReason.MISSING_TARGET ->
                    CoreUiR.string.metric_unavailable_missing_target
                DashboardMetricUnavailableReason.BASELINE_NOT_READY ->
                    CoreUiR.string.metric_unavailable_baseline_not_ready
                DashboardMetricUnavailableReason.MISSING_BMI ->
                    CoreUiR.string.metric_unavailable_missing_bmi
            },
        ),
    )
```

- [ ] **Step 4: Restore existing status resolvers without changing thresholds**

Use only existing domain methods:

```kotlin
val durationStatus =
    summary?.sleepDurationStatus(goalMinutes) ?: MetricStatus.CALIBRATING
val hrvStatus =
    summary?.hrvStatus(
        preferences.hrvOptimalThreshold,
        preferences.hrvWarningThreshold,
    ) ?: MetricStatus.CALIBRATING
val sleepRhrStatus =
    summary?.rhrStatus(
        preferences.rhrOptimalThreshold,
        preferences.rhrWarningThreshold,
    ) ?: MetricStatus.CALIBRATING
val restingHrStatus =
    summary?.restingHrStatus(
        preferences.rhrOptimalThreshold,
        preferences.rhrWarningThreshold,
    ) ?: MetricStatus.CALIBRATING
val rasStatus = metrics?.rasRounded?.toFloat().rasStatus()
```

Do not add renderer or presentation thresholds.

- [ ] **Step 5: Restore original secondary time range**

Add:

```xml
<string name="sleep_session_time_range_format">%1$s → %2$s</string>
```

Format the existing session timestamps using the existing `HH:mm`/local-zone
behavior and set:

```kotlin
secondaryText =
    lastSleepSession?.let { session ->
        resourceProvider.getString(
            DashboardR.string.sleep_session_time_range_format,
            formatTime(session.startTime),
            formatTime(session.endTime),
        )
    }
```

Do not change sleep duration normalization or the displayed duration.

- [ ] **Step 6: Restore original tooltip resource paths**

Use the existing strings from `core/ui` and `feature/dashboard`:

```kotlin
val durationTooltip =
    resourceProvider.getString(
        CoreUiR.string.tooltip_sleep_duration,
        DailyMetricsMapper.formatSleepDuration(goalMinutes) ?: "—",
    )
val rasTooltip = resourceProvider.getString(CoreUiR.string.tooltip_ras)
```

Recreate the `main` HRV, Sleep RHR, and Resting HR tooltip builders using only
`DailyMetrics.hrvBaselineRounded`, `rhrBaselineRounded`, precomputed
`*BaselineArrow`, precomputed `*BaselineDiff`, `zLnHrvDisplay`, and
`hrvSigmaDisplay`. Do not recompute baselines or differences.

Use this exact HRV structure:

```kotlin
private fun hrvTooltip(metrics: DailyMetrics?): String =
    buildString {
        append(resourceProvider.getString(CoreUiR.string.tooltip_sleep_hrv))
        val baseline = metrics?.hrvBaselineRounded
        val arrow = metrics?.hrvBaselineArrow?.symbol
        val difference = metrics?.hrvBaselineDiff
        when {
            baseline == null ->
                append(resourceProvider.getString(CoreUiR.string.tooltip_sleep_hrv_no_baseline))
            arrow != null && difference != null ->
                append(
                    resourceProvider.getString(
                        CoreUiR.string.tooltip_sleep_hrv_baseline,
                        baseline,
                        arrow,
                        difference,
                    ),
                )
            else ->
                append(
                    resourceProvider.getString(
                        CoreUiR.string.tooltip_sleep_hrv_baseline_no_today,
                        baseline,
                    ),
                )
        }
        val zScore = metrics?.zLnHrvDisplay
        val sigma = metrics?.hrvSigmaDisplay
        if (zScore != null && sigma != null) {
            append(
                resourceProvider.getString(
                    CoreUiR.string.tooltip_sleep_hrv_diagnostics,
                    zScore,
                    sigma,
                ),
            )
        }
    }
```

Use the same null/available branching for Sleep RHR with
`tooltip_sleep_rhr`, `tooltip_sleep_rhr_baseline`, and
`tooltip_sleep_rhr_no_baseline`. Resting HR uses:

```kotlin
private fun restingHrTooltip(metrics: DailyMetrics?): String {
    val baseline = metrics?.rhrBaselineRounded
    val arrow = metrics?.rhrBaselineArrow?.symbol
    val difference = metrics?.rhrBaselineDiff
    return if (baseline != null && arrow != null && difference != null) {
        resourceProvider.getString(
            DashboardR.string.tooltip_resting_hr_baseline,
            baseline,
            arrow,
            difference,
        )
    } else {
        resourceProvider.getString(DashboardR.string.tooltip_resting_hr_no_baseline)
    }
}
```

- [ ] **Step 7: Run presentation tests and confirm GREEN**

Run:

```bash
./gradlew :feature:dashboard:testDebugUnitTest --tests '*DashboardRecoveryMetricPresentationFactoryTest' --tests '*DashboardMetricPresentationFactoryTest'
```

Expected: restored-detail tests and all existing typed-presentation tests pass.

- [ ] **Step 8: Verify file limits and synchronize the index**

Run:

```bash
wc -l feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardMetricPresentationFactory.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardRecoveryMetricPresentationFactory.kt
codegraph index
codegraph sync
```

Expected: both production files are below 800 lines, the new file is indexed,
and moved symbols resolve at their new paths.

- [ ] **Step 9: Commit**

```bash
git add feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardMetricPresentationFactory.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardRecoveryMetricPresentationFactory.kt feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardRecoveryMetricPresentationFactoryTest.kt feature/dashboard/src/main/res/values/strings.xml
git commit -m "fix(dashboard): restore metric presentation details"
```

---

### Task 3: Restore the Shared Shell and Value Mode

**Files:**

- Modify: `feature/dashboard/build.gradle.kts:7-12`
- Create: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardVisualizationRegressionTest.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCard.kt:1-110`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricRenderers.kt:218-230`

**Interfaces:**

- `DashboardMetricCard` signature remains unchanged.
- Produces:

```kotlin
@Composable
fun DashboardValueRenderer(
    presentation: DashboardMetricPresentation,
    contentColor: Color,
    modifier: Modifier = Modifier,
)
```

- [ ] **Step 1: Add local Compose UI Test dependencies**

Add the same JVM Compose test dependencies already used by `core:ui`:

```kotlin
testImplementation(platform(libs.androidx.compose.bom))
testImplementation(libs.androidx.compose.ui.test)
testImplementation(libs.androidx.compose.ui.test.junit4)
testImplementation(libs.androidx.compose.ui.test.manifest)
```

- [ ] **Step 2: Write failing Robolectric Value tests**

Create a `RobolectricTestRunner` test with `createComposeRule()`:

```kotlin
@Test
fun valueMode_showsLargeValueUnitAndSecondary_withoutVisualizationOrVisibleStatus() {
    setMetricCard(
        mode = DashboardCardDisplayMode.VALUE,
        presentation =
            presentation.copy(
                title = "HRV",
                valueText = "41",
                unitText = "ms",
                secondaryText = "22:51 → 06:02",
                accessibilityDescription = "HRV 41 milliseconds, normal.",
            ),
    )

    composeRule.onNodeWithText("41").assertIsDisplayed()
    composeRule.onNodeWithText("ms").assertIsDisplayed()
    composeRule.onNodeWithText("22:51 → 06:02").assertIsDisplayed()
    composeRule.onNodeWithText("Normal").assertDoesNotExist()
    composeRule.onNodeWithTag(DASHBOARD_GAUGE_TAG).assertDoesNotExist()
    composeRule.onNodeWithTag(DASHBOARD_BAR_TAG).assertDoesNotExist()
}
```

Add:

```kotlin
@Test
fun allModes_keepOriginalCardHeight() {
    var mode by mutableStateOf(DashboardCardDisplayMode.VALUE)
    composeRule.setContent {
        TestTheme {
            DashboardMetricCard(
                presentation = presentation,
                specification = specification,
                requestedMode = mode,
                renderMode = mode,
                isEditing = false,
                onModeSelected = {},
            )
        }
    }

    DashboardCardDisplayMode.entries.forEach { newMode ->
        composeRule.runOnIdle { mode = newMode }
        composeRule.onNodeWithTag(DASHBOARD_METRIC_CARD_TAG).assertHeightIsEqualTo(156.dp)
    }
}
```

Use a mutable render-mode state as shown; do not call `setContent` more than
once in one test.

The literals are test fixtures, not production UI strings.

- [ ] **Step 3: Run the Value tests and confirm RED**

Run:

```bash
./gradlew :feature:dashboard:testDebugUnitTest --tests '*DashboardVisualizationRegressionTest.valueMode*' --tests '*DashboardVisualizationRegressionTest.allModes*'
```

Expected: test tags are unavailable and current Value typography/hierarchy
does not expose the required structure.

- [ ] **Step 4: Make the card shell mode-aware**

Resolve colors through existing systems:

```kotlin
val containerColor =
    if (renderMode == DashboardCardDisplayMode.VALUE) {
        presentation.status.containerColor()
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
val contentColor =
    if (renderMode == DashboardCardDisplayMode.VALUE) {
        presentation.status.onContainerColor()
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
```

Use `MaterialTheme.spacing.medium` horizontal and
`MaterialTheme.spacing.smallMedium` vertical padding. Mark the title row as a
heading. Value titles retain `main`’s two-line bounded header; Gauge/Bar titles
retain `main`’s one-line ellipsized header.

Replace `Modifier.clickable` with the Material 3 clickable `Card(onClick = …)`
overload when `onClick != null`; use the non-clickable overload otherwise.
Apply `DASHBOARD_METRIC_CARD_TAG` to the fixed-height Card modifier.

Define developer-only test tags beside the renderer functions:

```kotlin
internal const val DASHBOARD_METRIC_CARD_TAG = "dashboard_metric_card"
internal const val DASHBOARD_GAUGE_TAG = "dashboard_metric_gauge"
internal const val DASHBOARD_BAR_TAG = "dashboard_metric_bar"
```

Apply them with `Modifier.testTag`. These are not user-facing or accessibility
strings.

- [ ] **Step 5: Restore Value renderer hierarchy**

Use the original `MetricCard` styles:

```kotlin
Column(modifier = modifier.fillMaxSize()) {
    Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
    Text(
        text = presentation.valueText,
        style = MaterialTheme.typography.displaySmall,
        color = contentColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(modifier = Modifier.weight(1f))
    presentation.unitText.takeIf(String::isNotBlank)?.let { unit ->
        Text(
            text = unit,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor.copy(alpha = 0.7f),
        )
    }
    presentation.secondaryText?.let { secondary ->
        Text(
            text = secondary,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
```

When both unit and secondary text exist, show both outside any visualization.
Render no Canvas or progress component in this function.

- [ ] **Step 6: Run the Value tests and confirm GREEN**

Run:

```bash
./gradlew :feature:dashboard:testDebugUnitTest --tests '*DashboardVisualizationRegressionTest.valueMode*' --tests '*DashboardVisualizationRegressionTest.allModes*'
```

Expected: value hierarchy and fixed height tests pass.

- [ ] **Step 7: Commit**

```bash
git add feature/dashboard/build.gradle.kts feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardVisualizationRegressionTest.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCard.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricRenderers.kt
git commit -m "fix(dashboard): restore value card layout"
```

---

### Task 4: Restore Gauge Mode

**Files:**

- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardVisualizationRegressionTest.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricRenderers.kt:24-111`

**Interfaces:**

- Consumes: the unchanged normalized `markerFraction` from every
  `DashboardMetricVisual` subtype and `presentation.status`.
- Produces:

```kotlin
internal fun DashboardMetricVisual.progressFraction(): Float?

@Composable
fun DashboardGaugeRenderer(
    presentation: DashboardMetricPresentation,
    animateMarker: Boolean,
    modifier: Modifier = Modifier,
)
```

- [ ] **Step 1: Add failing Gauge content tests**

```kotlin
@Test
fun gaugeMode_showsValueUnitAndDelta_withoutVisibleStatus() {
    setMetricCard(
        mode = DashboardCardDisplayMode.GAUGE,
        presentation =
            presentation.copy(
                valueText = "41",
                unitText = "ms",
                secondaryText = "↓ 2",
                accessibilityDescription = "HRV 41 milliseconds, normal.",
            ),
    )

    composeRule.onNodeWithTag(DASHBOARD_GAUGE_TAG).assertIsDisplayed()
    composeRule.onNodeWithText("41").assertIsDisplayed()
    composeRule.onNodeWithText("ms").assertIsDisplayed()
    composeRule.onNodeWithText("↓ 2").assertIsDisplayed()
    composeRule.onNodeWithText("Normal").assertDoesNotExist()
}
```

Add a pure fraction test for `Score`, `Goal`, `PersonalBaseline`, and
`ReferenceRange`, asserting each returns its existing `markerFraction`.

- [ ] **Step 2: Run Gauge tests and confirm RED**

Run:

```bash
./gradlew :feature:dashboard:testDebugUnitTest --tests '*DashboardVisualizationRegressionTest.gaugeMode*' --tests '*DashboardVisualizationRegressionTest.progressFraction*'
```

Expected: current Gauge omits unit/delta and returns no progress for personal
baseline/reference range.

- [ ] **Step 3: Centralize progress-fraction access**

Add:

```kotlin
internal fun DashboardMetricVisual.progressFraction(): Float? =
    when (this) {
        is DashboardMetricVisual.Score -> markerFraction
        is DashboardMetricVisual.Goal -> markerFraction
        is DashboardMetricVisual.PersonalBaseline -> markerFraction
        is DashboardMetricVisual.ReferenceRange -> markerFraction
        is DashboardMetricVisual.ValueOnly -> null
    }
```

Do not inspect bands, target fractions, baseline fractions, or reference
fractions in renderers.

- [ ] **Step 4: Rebuild Gauge from the `main` body**

Resolve:

```kotlin
val markerFraction = presentation.visual.progressFraction()
val activeColor =
    if (markerFraction == null) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        presentation.status.gaugeColor()
    }
```

Render `M3MetricGauge(markerFraction, activeColor, animateMarker)` with
`DASHBOARD_GAUGE_TAG`. Overlay the original centered value/unit Column using
the same long-value typography branch from `M3ScoreGaugeCard`. Add the
20 dp centered footer and original M3 `Surface` delta chip when
`secondaryText` is non-blank.

Remove `metricStatusColor`, `LocalStatusColors`, all band mapping, all
`M3GaugeSegment` references, and all manual reference/baseline behavior from
the renderer.

- [ ] **Step 5: Run Gauge tests and confirm GREEN**

Run:

```bash
./gradlew :feature:dashboard:testDebugUnitTest --tests '*DashboardVisualizationRegressionTest.gaugeMode*' --tests '*DashboardVisualizationRegressionTest.progressFraction*'
```

Expected: Gauge content and all normalized visual types pass.

- [ ] **Step 6: Commit**

```bash
git add feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricRenderers.kt feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardVisualizationRegressionTest.kt
git commit -m "fix(dashboard): restore continuous gauge cards"
```

---

### Task 5: Replace Segmented Bar with Continuous External-Value Progress

**Files:**

- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardVisualizationRegressionTest.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricRenderers.kt:113-216`

**Interfaces:**

- Consumes: `DashboardMetricVisual.progressFraction()`,
  `MetricStatus.gaugeColor()`, and `metricVisualizationTrackColor()`.
- `DashboardBarRenderer` signature remains unchanged.

- [ ] **Step 1: Add failing Bar layout tests**

```kotlin
@Test
fun barMode_keepsValueUnitAndDeltaOutsideTrack_withoutStatusOrMarker() {
    setMetricCard(
        mode = DashboardCardDisplayMode.BAR,
        presentation =
            presentation.copy(
                valueText = "48",
                unitText = "bpm",
                secondaryText = "↓ 1",
                accessibilityDescription = "Resting heart rate 48 bpm, optimal.",
            ),
    )

    composeRule.onNodeWithText("48").assertIsDisplayed()
    composeRule.onNodeWithText("bpm").assertIsDisplayed()
    composeRule.onNodeWithText("↓ 1").assertIsDisplayed()
    composeRule.onNodeWithText("Optimal").assertDoesNotExist()
    composeRule.onNodeWithTag(DASHBOARD_BAR_TAG).assertIsDisplayed()

    val valueBounds = composeRule.onNodeWithText("48").fetchSemanticsNode().boundsInRoot
    val barBounds = composeRule.onNodeWithTag(DASHBOARD_BAR_TAG).fetchSemanticsNode().boundsInRoot
    assertTrue(valueBounds.bottom <= barBounds.top)
}
```

Add variants for `6h 50m`, `41 ms`, and `86`, ensuring their text bounds do
not intersect the bar bounds.

- [ ] **Step 2: Run Bar tests and confirm RED**

Run:

```bash
./gradlew :feature:dashboard:testDebugUnitTest --tests '*DashboardVisualizationRegressionTest.barMode*'
```

Expected: current value bounds overlap the Canvas and no unit/delta is shown.

- [ ] **Step 3: Implement external value/unit hierarchy**

Render the primary value before the track:

```kotlin
Row(
    verticalAlignment = Alignment.Bottom,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
) {
    Text(
        text = presentation.valueText,
        style = MaterialTheme.typography.headlineMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (presentation.unitText.isNotBlank()) {
        Text(
            text = presentation.unitText,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
```

Give the progress Canvas `DASHBOARD_BAR_TAG`. Draw one rounded complete track
with `metricVisualizationTrackColor()`, followed by one rounded active line
from zero to `progressFraction.coerceIn(0f, 1f)`.

Use:

```kotlin
val activeColor =
    if (progressFraction == null) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        presentation.status.gaugeColor()
    }
```

Do not draw bands, circles, stop markers, target markers, or classification
sections.

- [ ] **Step 4: Render secondary information below the track**

```kotlin
presentation.secondaryText?.let { secondary ->
    Text(
        text = secondary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
```

Use weights/spacers within the fixed card body; never increase card height.

- [ ] **Step 5: Run Bar tests and confirm GREEN**

Run:

```bash
./gradlew :feature:dashboard:testDebugUnitTest --tests '*DashboardVisualizationRegressionTest.barMode*'
```

Expected: all displayed values precede and do not overlap the Bar, with unit
and secondary text visible.

- [ ] **Step 6: Prove forbidden Bar drawing is absent**

Run:

```bash
rg -n 'bands|bandColor|drawCircle|targetMarker|baselineMarker|referenceMarker' feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricRenderers.kt
```

Expected: no matches.

- [ ] **Step 7: Commit**

```bash
git add feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricRenderers.kt feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardVisualizationRegressionTest.kt
git commit -m "fix(dashboard): render continuous metric bars"
```

---

### Task 6: Validate Representative Metrics, Accessibility, and Large Fonts

**Files:**

- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardVisualizationRegressionTest.kt`
- Test existing: `feature/dashboard/src/test/kotlin/app/readylytics/health/domain/dashboard/DashboardCardCatalogTest.kt`
- Test existing: `feature/dashboard/src/androidTest/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCardTest.kt`
- Test existing: `feature/dashboard/src/androidTest/kotlin/app/readylytics/health/feature/dashboard/DashboardScreenTest.kt`

**Interfaces:**

- No production interface changes.

- [ ] **Step 1: Add a representative mode matrix test**

Build presentations for:

```kotlin
listOf(
    CardId.SLEEP_SCORE to "86",
    CardId.READINESS to "79",
    CardId.HRV to "41",
    CardId.SLEEP_DURATION to "6h 50m",
    CardId.RAS_DAILY to "74",
    CardId.RESTING_HR to "48",
)
```

For every mode returned by `DashboardCardCatalog.spec(cardId).supportedModes`,
compose one host whose `cardId`, presentation, and mode are mutable. Update
those values with `composeRule.runOnIdle`, then assert the primary value
exists. Assert unit text for HRV and Resting HR in every mode. Separately
assert `CardId.STEPS` supports only `BAR` and its existing `StepsCard` remains
outside `DashboardMetricCard`.

- [ ] **Step 2: Add accessibility-only status coverage**

```kotlin
@Test
fun status_isAccessibilityOnly_inEveryMode() {
    var mode by mutableStateOf(DashboardCardDisplayMode.VALUE)
    composeRule.setContent {
        TestTheme {
            DashboardMetricCard(
                presentation =
                    presentation.copy(
                        secondaryText = null,
                        accessibilityDescription = "Sleep score 86, excellent.",
                    ),
                specification = specification,
                requestedMode = mode,
                renderMode = mode,
                isEditing = false,
                onModeSelected = {},
            )
        }
    }

    DashboardCardDisplayMode.entries.forEach { newMode ->
        composeRule.runOnIdle { mode = newMode }

        composeRule
            .onNodeWithContentDescription("Sleep score 86, excellent.")
            .assertExists()
        composeRule.onNodeWithText("excellent", substring = true, ignoreCase = true)
            .assertDoesNotExist()
    }
}
```

Again, use one composition per test and mutate only state between assertions.

- [ ] **Step 3: Add light/dark and 1.5 font-scale bounds tests**

Compose each mode under Readylytics light and dark themes, with:

```kotlin
CompositionLocalProvider(
    LocalDensity provides Density(density = 1f, fontScale = 1.5f),
) {
    DashboardMetricCard(...)
}
```

Assert the card remains exactly 156 dp high and each tagged Gauge/Bar bound is
contained by the tagged Card bound. Assert value/unit text remains displayed.

- [ ] **Step 4: Run the complete dashboard JVM suite**

Run:

```bash
./gradlew :feature:dashboard:testDebugUnitTest
```

Expected: all presentation, catalog, ViewModel, renderer, and Robolectric
Compose tests pass.

- [ ] **Step 5: Run existing connected Dashboard tests if available**

Check:

```bash
adb devices
```

If an authorized device/emulator is listed, run:

```bash
./gradlew :feature:dashboard:connectedDebugAndroidTest
```

Expected: existing selector, semantics, edit-mode, integration, and drag tests
pass. If no device is listed, record that connected tests were not runnable;
do not claim they passed.

- [ ] **Step 6: Commit**

```bash
git add feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardVisualizationRegressionTest.kt
git commit -m "test(dashboard): cover visualization regressions"
```

---

### Task 7: Final Material 3, Index, and Repository Verification

**Files:**

- Verify all files changed in Tasks 1-6.
- No data-flow or scoring documentation change is required because this is a
  renderer/presentation-only repair with unchanged formulas and normalization.

**Interfaces:**

- No production interface changes.

- [ ] **Step 1: Inspect the final focused diff against `main`**

Run:

```bash
git diff --stat main...HEAD
git diff main...HEAD -- core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricGauge.kt core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3ScoreGaugeCard.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCard.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricRenderers.kt
```

Confirm:

- Value typography/padding/hierarchy matches `main`.
- Gauge title/value/unit/delta hierarchy matches `main`.
- Only the requested lighter shared neutral track differs from `main`.
- Bar values are outside the track.
- No visible status text, bands, marker bubbles, or reference markers exist.
- Card height and supported modes are unchanged.

- [ ] **Step 2: Complete the Material 3 checklist**

Inspect code and test evidence for:

- `MaterialTheme.typography` only.
- `MetricStatus.gaugeColor()` plus Material theme colors only.
- `MaterialTheme.shapes.large`.
- `surfaceContainerHighest` for Gauge/Bar and existing status containers for
  Value.
- Readylytics spacing/dimension tokens.
- M3 clickable Card interaction states.
- 48 dp tooltip/menu targets unchanged.
- bounded text and no Gauge/Bar overlap at 1.5 font scale.
- merged localized semantics with hidden visual status.
- shared subtle animation disabled during edit mode.

- [ ] **Step 3: Verify forbidden rendering and supported-mode stability**

Run:

```bash
rg -n 'Color\\.(LightGray|Red|Green|Yellow|Blue)|Color\\(0x' core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricGauge.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCard.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricRenderers.kt
./gradlew :feature:dashboard:testDebugUnitTest --tests '*DashboardCardCatalogTest'
```

Expected: no hardcoded production colors and catalog tests pass unchanged.

- [ ] **Step 4: Refresh Codegraph**

Run:

```bash
codegraph index
codegraph sync
```

Expected: all new files and moved symbols are current.

- [ ] **Step 5: Run mandatory formatting**

Run:

```bash
./gradlew ktlintFormat
```

Expected: exit 0.

- [ ] **Step 6: Run the complete unit-test suite**

Run:

```bash
./gradlew testDebugUnitTest
```

Expected: exit 0 with zero failed tests.

- [ ] **Step 7: Run release lint**

Run:

```bash
./gradlew lintRelease
```

Expected: exit 0 with no lint errors.

- [ ] **Step 8: Run build verification**

Run:

```bash
./gradlew assembleDebug
```

Expected: exit 0 and a debug APK is assembled.

- [ ] **Step 9: Verify formatting, file limits, and worktree scope**

Run:

```bash
git diff --check
wc -l core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricGauge.kt core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3ScoreGaugeCard.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCard.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricRenderers.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardMetricPresentationFactory.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardRecoveryMetricPresentationFactory.kt
git status --short
```

Expected: no whitespace errors, every production file is below 800 lines, and
only intended changes plus the user-owned `idea.png` remain.

- [ ] **Step 10: Commit any formatter-only changes**

If `ktlintFormat` changed intended files:

```bash
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricGauge.kt core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3ScoreGaugeCard.kt core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/M3MetricGaugeTest.kt feature/dashboard/build.gradle.kts feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCard.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricRenderers.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardMetricPresentationFactory.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardRecoveryMetricPresentationFactory.kt feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardVisualizationRegressionTest.kt feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardRecoveryMetricPresentationFactoryTest.kt feature/dashboard/src/main/res/values/strings.xml
git commit -m "style: format dashboard visualization fixes"
```

Do not add or commit `idea.png`.
