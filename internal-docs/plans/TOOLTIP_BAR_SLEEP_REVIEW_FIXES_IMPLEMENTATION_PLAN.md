# Tooltip / Bar / Sleep-Pipeline Review Fixes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the 8 code-review fixes specified in `internal-docs/plans/TOOLTIP_BAR_SLEEP_REVIEW_FIXES_PLAN.md` (source doc), on branch `claude/progress-bar-dot-indicator-t9pc28`.

**Architecture:** Eight independent in-place fixes to existing Compose UI and sleep-pipeline code, executed in the source doc's order: three isolated single-file fixes first (shrink the diff fast), then the `SleepTrendDay` data-model + `SleepViewModel` plumbing work, then dashboard-renderer + perf cleanups. No ingestion, Room schema, or scoring-formula changes — `DATA_FLOW.md` stays untouched (see Global Constraints).

**Tech Stack:** Kotlin, Jetpack Compose (M3), Vico charts, Robolectric + Compose UI tests, MockK, JUnit. Gradle modules: `:core:ui`, `:core:scoring`, `:feature:sleep`, `:feature:dashboard`.

## Global Constraints

- **Per-task commit gate:** before each commit run `./gradlew ktlintFormat`, then the task's targeted test command, then `git add` only the files listed in the task and commit.
- **Final gate (after Task 8):** `./gradlew testDebugUnitTest` then `./gradlew lintRelease`.
- **No new user-facing strings:** all changes must reuse existing `app/src/main/res/values/strings.xml` resources. Do not add `stringResource` entries or hardcode text.
- **No `internal-docs/DATA_FLOW.md` update needed.** The `SleepViewModel` change (Task 5) is combine/`flatMapLatest` plumbing around existing reads; scoring math continues to flow exclusively through `ScoringRepository.computeDailySummary`. None of the 8 fixes touch `HealthConnectRepository*`, data/healthconnect mappers, sync workers, Room schema, or `domain/scoring/**` formulas.
- **No new/deleted source files:** all fixes are in-place edits → no `codegraph index`/`sync` required.
- **File size caps:** edits must not push any touched file past the 400-line soft cap / 800-line hard cap (largest touched file is `SleepViewModelTest.kt` at 774 → stays under 800).
- **Skip finding #10** (duplicated tick-overhang math in `M3MetricBar` vs `M3MetricGauge`): linear vs angular geometry — no real dedup possible. Not implemented.
- **`SleepTrendMarkerListener` note:** `handleTargets` is invoked from a listener object captured outside recomposition — keep `rememberUpdatedState` wrapping around any reactive value it reads.
- **Setup before Task 1:** commit the two plan docs (`git add internal-docs/plans/TOOLTIP_BAR_SLEEP_REVIEW_FIXES_PLAN.md internal-docs/plans/TOOLTIP_BAR_SLEEP_REVIEW_FIXES_IMPLEMENTATION_PLAN.md && git commit -m "docs: plan for tooltip bar sleep review fixes"`).

---

### Task 1: `DataPointTooltip` — restore centered alignment + shrink-to-content width (source findings #1 + #2)

**Files:**
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/DataPointTooltip.kt`
- Test: `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/DataPointTooltipTest.kt`

**Interfaces:**
- Consumes: `DataPointTooltipData(valueText, dateText, offset, preDateLines, extraLine)` — unchanged.
- Produces: a `testTag` on the tooltip `Surface` so tests can assert bubble width: add `const val DATA_POINT_TOOLTIP_TAG = "data_point_tooltip"` in `DataPointTooltip.kt`. Existing callers (SleepStagesChart, SleepHrChart, TrimpBreakdownChart, AcwrChart, TrendCharts, StepsBar, HrTimelineChart, BloodPressureTrendChart, BloodPressureSplitChart, SingleBloodPressureChart, SleepTrendChart) pass `DataPointTooltipData` with the same shape → **no caller changes**.

- [ ] **Step 1: Write the failing tests**

Append to `DataPointTooltipTest.kt`:

```kotlin
@Test
fun `tooltip without extra content centers lines and shrinks to content`() {
    composeRule.setContent {
        MaterialTheme {
            DataPointTooltip(
                isVisible = true,
                data = DataPointTooltipData(valueText = "Duration: 8h", dateText = "01.08"),
                onDismissRequest = {},
            )
        }
    }

    val valueBounds = composeRule.onNodeWithText("Duration: 8h").fetchSemanticsNode().boundsInRoot
    val dateBounds = composeRule.onNodeWithText("01.08").fetchSemanticsNode().boundsInRoot
    assertEquals("value and date must share the same center axis", valueBounds.center.x, dateBounds.center.x, 1f)

    val bubble = boundsOfTag(DATA_POINT_TOOLTIP_TAG)
    val maxWidthPx = with(composeRule.density) { 150.dp.toPx() }
    assertTrue(
        "short tooltip must shrink toward content width instead of forcing 150dp, width=${bubble.width}",
        bubble.width < maxWidthPx,
    )
}

@Test
fun `tooltip with pre-date lines left aligns value date and lines`() {
    composeRule.setContent {
        MaterialTheme {
            DataPointTooltip(
                isVisible = true,
                data =
                    DataPointTooltipData(
                        valueText = "01.08",
                        dateText = "Duration: 8h 05m",
                        preDateLines = listOf("Bedtime: 11:42 PM - 7:10 AM"),
                    ),
                onDismissRequest = {},
            )
        }
    }

    val valueLeft = composeRule.onNodeWithText("01.08").fetchSemanticsNode().boundsInRoot.left
    val dateLeft = composeRule.onNodeWithText("Duration: 8h 05m").fetchSemanticsNode().boundsInRoot.left
    val bedtimeLeft = composeRule.onNodeWithText("Bedtime: 11:42 PM - 7:10 AM").fetchSemanticsNode().boundsInRoot.left
    assertEquals("value must be left-aligned with date", valueLeft, dateLeft, 1f)
    assertEquals("date must be left-aligned with bedtime line", dateLeft, bedtimeLeft, 1f)
}
```

Add these imports to the test file and a `boundsOfTag` helper:

```kotlin
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp

private fun boundsOfTag(tag: String): androidx.compose.ui.geometry.Rect =
    composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:ui:testDebugUnitTest --tests "app.readylytics.health.core.ui.components.DataPointTooltipTest"`
Expected: FAIL — `DATA_POINT_TOOLTIP_TAG` unresolved (compile error). After adding the tag constant in step 3 the assertions still fail on the current buggy layout: value is `fillMaxWidth`-centered so its center ≠ date's center, and the bubble measures at the 150dp max.

- [ ] **Step 3: Write minimal implementation**

In `DataPointTooltip.kt`:

1. Add near the top of the file (after the imports, before `DataPointTooltipData`):

```kotlin
const val DATA_POINT_TOOLTIP_TAG = "data_point_tooltip"
```

2. Add the tag to the `Surface` modifier (currently lines ~142-145):

```kotlin
modifier =
    modifier
        .widthIn(min = 70.dp, max = 150.dp)
        .padding(horizontal = MaterialTheme.spacing.small)
        .testTag(DATA_POINT_TOOLTIP_TAG),
```

3. Replace the `Column` (currently lines 147-184) — alignment becomes conditional on whether the tooltip actually has multi-line content, and the `fillMaxWidth`/`textAlign` hack on `valueText` is removed entirely:

```kotlin
val hasExtraContent = data.preDateLines.isNotEmpty() || data.extraLine != null
Column(
    horizontalAlignment = if (hasExtraContent) Alignment.Start else Alignment.CenterHorizontally,
    modifier =
        Modifier
            .padding(
                horizontal = MaterialTheme.spacing.small,
                vertical = MaterialTheme.spacing.extraSmallMedium,
            )
            // extra padding to clear caret
            .padding(bottom = MaterialTheme.spacing.extraSmallMedium),
) {
    Text(
        text = data.valueText,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.inverseOnSurface,
    )
    Text(
        text = data.dateText,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.9f),
    )
    data.preDateLines.forEach { line ->
        Text(
            text = line,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.9f),
        )
    }
    data.extraLine?.let { extra ->
        Text(
            text = extra,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.85f),
        )
    }
}
```

4. Remove now-unused imports: `androidx.compose.foundation.layout.fillMaxWidth` and `androidx.compose.ui.text.style.TextAlign`. Add `import androidx.compose.ui.platform.testTag`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:ui:testDebugUnitTest --tests "app.readylytics.health.core.ui.components.DataPointTooltipTest"`
Expected: PASS — all 6 tests (2 new + 4 existing, including `tooltip renders naps between bedtime and date`).

- [ ] **Step 5: Commit**

```bash
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/DataPointTooltip.kt core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/DataPointTooltipTest.kt
git commit -m "fix: restore centered tooltip alignment and shrink-to-content width"
```

---

### Task 2: `M3MetricBar` — divide-by-zero guard on zero-width canvas (source finding #5)

**Files:**
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricBar.kt`
- Test: `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/M3MetricBarTest.kt`

**Interfaces:**
- Consumes: `M3MetricBar(progressFraction, activeColor, trackColor, modifier, tickColor, barHeight, markerColor, markerDiameter, animateProgress)` — public signature unchanged.
- Produces: new `internal fun capCoverageFraction(progress: Float, width: Float, strokeWidth: Float): Float` (mirrors the existing top-level testable helpers `visibleTickFractions` / `fillEndCenterX` in the same file). `M3MetricGauge.kt`'s `arcTickCapCoverageFraction` is the sibling concept but stays untouched (angular geometry).

- [ ] **Step 1: Write the failing test**

Append to `M3MetricBarTest.kt`:

```kotlin
@Test
fun capCoverageFraction_zeroWidthOrZeroProgress_returnsZero() {
    // Zero-width canvas on an early/collapsing composition frame must not produce Infinity.
    assertEquals(0f, capCoverageFraction(0.5f, 0f, 10f))
    // Zero progress means no fill, so no overhang to hide ticks under.
    assertEquals(0f, capCoverageFraction(0f, 200f, 10f))
    assertEquals(0f, capCoverageFraction(0f, 0f, 10f))
    // Normal case: (strokeWidth / 2) / width.
    assertEquals(0.025f, capCoverageFraction(0.5f, 200f, 10f))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:ui:testDebugUnitTest --tests "app.readylytics.health.core.ui.components.M3MetricBarTest"`
Expected: FAIL — `capCoverageFraction` unresolved (compile error).

- [ ] **Step 3: Write minimal implementation**

In `M3MetricBar.kt`, after the existing `fillEndCenterX` function, add:

```kotlin
// Fraction of the bar width the fill's round cap overhangs past its center. Zero progress or a
// zero-width canvas (early/collapsing composition frame) must yield 0f, never Infinity.
internal fun capCoverageFraction(progress: Float, width: Float, strokeWidth: Float): Float =
    if (progress > 0f && width > 0f) (strokeWidth / 2f) / width else 0f
```

Then, inside the `Canvas` block, replace the current `capCoverageFraction` local (lines 95-100) so it delegates to the guarded helper:

```kotlin
val tickRadiusPx = tickDiameter.toPx() / 2f
// The fill's round cap overhangs `strokeWidth / 2` px past the raw progress fraction, so a
// tick nominally just past `progressToDraw` can still sit inside that cap and render on top
// of the fill (ticks are drawn after the fill). Hide ticks that fall within the overhang.
visibleTickFractions(
    progressToDraw,
    capCoverageFraction(progressToDraw, size.width, strokeWidth),
).forEach { fraction ->
    drawCircle(
        color = tickColor,
        radius = tickRadiusPx,
        center = Offset(size.width * fraction, centerY),
    )
}
```

(Delete the old `val capCoverageFraction = if (progressToDraw > 0f) (strokeWidth / 2f) / size.width else 0f` line.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:ui:testDebugUnitTest --tests "app.readylytics.health.core.ui.components.M3MetricBarTest"`
Expected: PASS — all 5 tests (1 new + 4 existing, including the `visibleTickFractions` and `fillEndCenterX` behavior tests).

- [ ] **Step 5: Commit**

```bash
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricBar.kt core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/M3MetricBarTest.kt
git commit -m "fix: guard M3MetricBar cap coverage against zero-width canvas"
```

---

### Task 3: `SyncProgressScreen` — marker overflows thinner sync bar (source finding #4)

**Files:**
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/sync/SyncProgressScreen.kt`

**Interfaces:**
- Consumes: `M3MetricBar` (from Task 2), `MaterialTheme.dimens.syncProgressBarThickness` (4dp) and `MaterialTheme.dimens.metricGaugeMarkerDiameter` (6dp default).
- Produces: no API change. Visual-only fix — the 6dp default marker is larger than the 4dp bar it sits on and overhangs it (Canvas drawing is not clipped to layout bounds). No automated test exists for this screen and canvas-drawn markers are not reachable via semantics; verification is the manual resync run.

- [ ] **Step 1: Add `markerDiameter` to the `M3MetricBar` call**

In `SyncProgressScreen.kt`, the call at lines 99-106 becomes:

```kotlin
M3MetricBar(
    progressFraction = progress?.fraction(),
    activeColor = MaterialTheme.colorScheme.primary,
    trackColor = MaterialTheme.colorScheme.secondaryContainer,
    barHeight = MaterialTheme.dimens.syncProgressBarThickness,
    markerDiameter = MaterialTheme.dimens.syncProgressBarThickness,
    animateProgress = false,
    modifier = Modifier.fillMaxWidth(),
)
```

- [ ] **Step 2: Verify the module still compiles and existing tests pass**

Run: `./gradlew :core:ui:testDebugUnitTest`
Expected: PASS (no behavior change to anything under test).

- [ ] **Step 3: Manual verification (do this once, after the task, on a device/emulator)**

Run the resync flow: Settings → Resync Health Connect data → observe the progress screen. Confirm the marker dot sits flush within the thin progress bar with no overhang into the surrounding `Spacer` whitespace.

- [ ] **Step 4: Commit**

```bash
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/sync/SyncProgressScreen.kt
git commit -m "fix: size sync progress marker to the thinner bar"
```

---

### Task 4: `SleepTrendDay` — add `dayOffset` field so the trend-day lookup can be keyed (source finding #7)

**Files:**
- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/SleepTrendDay.kt`
- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/SleepTrendDayAssembler.kt`
- Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendMarkerListener.kt`
- Test: `core/scoring/src/test/kotlin/app/readylytics/health/domain/scoring/sleep/SleepTrendDayAssemblerTest.kt`
- Test: `feature/sleep/src/test/kotlin/app/readylytics/health/feature/sleep/SleepTrendMarkerListenerTest.kt`

**Interfaces:**
- Produces: `SleepTrendDay` gains a required first property `dayOffset: Int`. `SleepTrendDayAssembler.assemble(segments, rangeStart, rangeDays, policy): List<SleepTrendDay>` signature is unchanged but every emitted entry now carries `dayOffset == list index`. `rememberSleepTrendMarkerVisibilityListener(...)` consumes the new field internally.
- Consumes: `DailyDataPoint.dayOffset` (existing) for the sibling keyed maps (`startOffsetMap`/`durationSpanMap`/`actualDurationMap`) that `SleepTrendMarkerListener` already uses — this fix makes `trendDays` lookup consistent with them.
- **Other `SleepTrendDay(` construction sites:** only `SleepTrendDayAssembler.kt` (2 sites) and `SleepTrendMarkerListenerTest.kt` (2 sites) — no Compose previews construct it. `SleepViewModel.kt` and `SleepTrendChart.kt` only *read* properties, so they compile unchanged.

- [ ] **Step 1: Write the failing tests**

Append to `SleepTrendDayAssemblerTest.kt`:

```kotlin
@Test
fun `emits dayOffset matching list index for populated and gap days`() {
    val core = segment("core", at(2026, 8, 1, 23, 0), at(2026, 8, 2, 7, 0))

    val result = SleepTrendDayAssembler.assemble(listOf(core), LocalDate.of(2026, 8, 1), 3, policy())

    assertEquals(listOf(0, 1, 2), result.map { it.dayOffset })
    assertEquals(0, result[0].dayOffset)
    assertEquals(LocalDate.of(2026, 8, 1), result[0].scoreDay)
    assertEquals(2, result[2].dayOffset)
    assertEquals(null, result[2].coreStartTimeMs)
}
```

Append to `SleepTrendMarkerListenerTest.kt` — this test is the discriminator: with a *sparse* `trendDays` list (only offset 5 present) the old index lookup `getOrNull(5)` returns null; the new keyed map resolves the day:

```kotlin
@Test
fun `selected marker resolves trend day by offset key when list is sparse`() {
    var selectedState: SleepTrendSelectedState? = null
    lateinit var listener: com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener

    composeRule.setContent {
        listener =
            rememberSleepTrendMarkerVisibilityListener(
                startOffsetPoints = listOf(DailyDataPoint(5, 11f)),
                durationSpanPoints = listOf(DailyDataPoint(5, 8f)),
                actualDurationPoints = listOf(DailyDataPoint(5, 9f)),
                trendDays =
                    listOf(
                        SleepTrendDay(
                            dayOffset = 5,
                            scoreDay = LocalDate.of(2026, 8, 6),
                            coreStartTimeMs = 42L,
                            coreEndTimeMs = 52L,
                            totalDurationMinutes = 600,
                            naps = emptyList(),
                        ),
                    ),
                onStateChanged = { selectedState = it },
            )
    }

    composeRule.runOnIdle {
        listener.onShown(
            marker = TestMarker,
            targets =
                listOf(
                    TestColumnTarget(
                        x = 5.0,
                        canvasX = 300f,
                        columns = listOf(column(canvasY = 170f), column(canvasY = 70f)),
                    ),
                ),
        )
    }

    val resolvedState = requireNotNull(selectedState)
    assertEquals(5, resolvedState.dayOffset)
    assertEquals(42L, resolvedState.coreStartTimeMs)
    assertEquals(52L, resolvedState.coreEndTimeMs)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run both:
- `./gradlew :core:scoring:testDebugUnitTest --tests "app.readylytics.health.domain.scoring.sleep.SleepTrendDayAssemblerTest"`
- `./gradlew :feature:sleep:testDebugUnitTest --tests "app.readylytics.health.feature.sleep.SleepTrendMarkerListenerTest"`

Expected: FAIL — both `dayOffset` property references unresolved (compile error).

- [ ] **Step 3: Write minimal implementation**

In `SleepTrendDay.kt`, add the property:

```kotlin
data class SleepTrendDay(
    val dayOffset: Int,
    val scoreDay: LocalDate,
    val coreStartTimeMs: Long?,
    val coreEndTimeMs: Long?,
    val totalDurationMinutes: Int?,
    val naps: List<SleepTrendNap>,
)
```

In `SleepTrendDayAssembler.kt`, add `dayOffset = offset,` as the first argument in **both** branches (gap day and populated day).

In `SleepTrendMarkerListener.kt`, replace the `rememberUpdatedState(trendDays)` + index-lookup pattern (lines 29, 81) with a keyed map consistent with the three point maps:

```kotlin
val trendDayMap = remember(trendDays) { trendDays.associateBy { it.dayOffset } }
val currentOnStateChanged = rememberUpdatedState(onStateChanged)
val currentTrendDayMap = rememberUpdatedState(trendDayMap)

return remember(startOffsetMap, durationSpanMap, actualDurationMap, trendDayMap) {
    // ...object unchanged except handleTargets:
```

and inside `handleTargets`:

```kotlin
val trendDay = currentTrendDayMap.value[resolvedOffset]
```

(Keep the `rememberUpdatedState` wrapping of `trendDayMap` — `handleTargets` is called from a listener object captured outside recomposition, the same reason the original code wrapped `trendDays`.)

In `SleepTrendMarkerListenerTest.kt`, update the two existing constructions in `selected marker state carries naps for its scoring day` to pass `dayOffset` (offsets 0 and 1 to match the tapped x=1):

```kotlin
SleepTrendDay(dayOffset = 0, scoreDay = LocalDate.of(2026, 8, 1), coreStartTimeMs = null, coreEndTimeMs = null, totalDurationMinutes = null, naps = emptyList()),
SleepTrendDay(dayOffset = 1, scoreDay = LocalDate.of(2026, 8, 2), coreStartTimeMs = 10L, coreEndTimeMs = 20L, totalDurationMinutes = 540, naps = naps),
```

- [ ] **Step 4: Run tests to verify they pass**

Run both commands from Step 2 plus the module-wide sleep tests:
- `./gradlew :core:scoring:testDebugUnitTest --tests "app.readylytics.health.domain.scoring.sleep.SleepTrendDayAssemblerTest"`
- `./gradlew :feature:sleep:testDebugUnitTest`

Expected: PASS — assembler test asserts offsets for populated + gap days; listener test asserts keyed resolution for a sparse list; existing listener/assembler tests unchanged in behavior.

- [ ] **Step 5: Commit**

```bash
git add core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/SleepTrendDay.kt core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/SleepTrendDayAssembler.kt core/scoring/src/test/kotlin/app/readylytics/health/domain/scoring/sleep/SleepTrendDayAssemblerTest.kt feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendMarkerListener.kt feature/sleep/src/test/kotlin/app/readylytics/health/feature/sleep/SleepTrendMarkerListenerTest.kt
git commit -m "fix: key sleep trend day lookup by dayOffset field"
```

---

### Task 5: `SleepViewModel` — gate the sleep pipeline restart on only the consumed pref fields (source finding #3)

**Files:**
- Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepViewModel.kt`
- Test: `feature/sleep/src/test/kotlin/app/readylytics/health/feature/sleep/SleepViewModelTest.kt`

**Interfaces:**
- Consumes: `settingsRepo.userPreferences: Flow<UserPreferences>`, fields `goalSleepHours`, `coreMergeGapMinutes`, `supplementalCutoffMinutesOfDay`, `minimumCountedSleepSegmentMinutes`, `supplementalArchitectureCoveragePercent`, and extension `scoringZone()`.
- Produces: `private data class SleepScoringPrefs(scoringZoneId: ZoneId, coreMergeGapMinutes: Int, supplementalCutoffMinutesOfDay: Int, minimumCountedSleepSegmentMinutes: Int, supplementalArchitectureCoveragePercent: Int, goalSleepHours: Float)`, `private fun UserPreferences.toSleepScoringPrefs()`, and `private val sleepScoringPrefsFlow` (`.map{}.distinctUntilChanged()`). The outer `combine`'s third source swaps from the full `UserPreferences` flow to `sleepScoringPrefsFlow`, so only those six fields re-trigger the `flatMapLatest` restart. `SleepUiState`/`SleepTrendData`/`SleepDayPolicy` types unchanged.

- [ ] **Step 1: Write the failing tests**

Add imports to `SleepViewModelTest.kt`:

```kotlin
import app.readylytics.health.data.preferences.AppTheme
import org.junit.Assert.assertTrue
```

Append these tests:

```kotlin
@Test
fun `unrelated pref change does not resubscribe inner flows`() = runTest(testDispatcher) {
    val prefsFlow = MutableStateFlow(UserPreferences())
    every { settingsRepo.userPreferences } returns prefsFlow
    var observeSinceCalls = 0
    every { sleepSessionRepository.observeSince(any()) } answers {
        observeSinceCalls++
        flowOf(emptyList())
    }
    var observeFirstSessionCalls = 0
    every { sleepSessionRepository.observeFirstSessionEndingInRange(any(), any()) } answers {
        observeFirstSessionCalls++
        flowOf(null)
    }

    viewModel = createViewModel()
    val collectJob = launch { viewModel.uiState.collect {} }
    testDispatcher.scheduler.advanceUntilIdle()

    val initialSinceCalls = observeSinceCalls
    val initialFirstSessionCalls = observeFirstSessionCalls
    assertTrue("initial load must subscribe once", initialSinceCalls >= 1)

    prefsFlow.value = UserPreferences(appTheme = AppTheme.DARK)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("unrelated pref change must not restart observeSince", initialSinceCalls, observeSinceCalls)
    assertEquals("unrelated pref change must not restart observeFirstSessionEndingInRange", initialFirstSessionCalls, observeFirstSessionCalls)

    collectJob.cancelAndJoin()
}

@Test
fun `sleep-relevant pref change resubscribes inner flows`() = runTest(testDispatcher) {
    val prefsFlow = MutableStateFlow(UserPreferences())
    every { settingsRepo.userPreferences } returns prefsFlow
    var observeSinceCalls = 0
    every { sleepSessionRepository.observeSince(any()) } answers {
        observeSinceCalls++
        flowOf(emptyList())
    }

    viewModel = createViewModel()
    val collectJob = launch { viewModel.uiState.collect {} }
    testDispatcher.scheduler.advanceUntilIdle()
    val initialSinceCalls = observeSinceCalls

    prefsFlow.value = UserPreferences(coreMergeGapMinutes = 120)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(initialSinceCalls + 1, observeSinceCalls)

    collectJob.cancelAndJoin()
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :feature:sleep:testDebugUnitTest --tests "app.readylytics.health.feature.sleep.SleepViewModelTest"`
Expected: FAIL — the unrelated-pref test: with the current code the full `UserPreferences` flow is the outer `combine` source, so an `appTheme` change restarts `flatMapLatest` and increments both counters.

- [ ] **Step 3: Write minimal implementation**

In `SleepViewModel.kt`:

1. Add `import app.readylytics.health.data.preferences.UserPreferences` to the imports (needed for the extension receiver type).

2. Add after the existing `SleepTrendData` data class (or after the imports):

```kotlin
private data class SleepScoringPrefs(
    val scoringZoneId: ZoneId,
    val coreMergeGapMinutes: Int,
    val supplementalCutoffMinutesOfDay: Int,
    val minimumCountedSleepSegmentMinutes: Int,
    val supplementalArchitectureCoveragePercent: Int,
    val goalSleepHours: Float,
)

private fun UserPreferences.toSleepScoringPrefs() =
    SleepScoringPrefs(
        scoringZoneId = scoringZone(),
        coreMergeGapMinutes = coreMergeGapMinutes,
        supplementalCutoffMinutesOfDay = supplementalCutoffMinutesOfDay,
        minimumCountedSleepSegmentMinutes = minimumCountedSleepSegmentMinutes,
        supplementalArchitectureCoveragePercent = supplementalArchitectureCoveragePercent,
        goalSleepHours = goalSleepHours,
    )
```

3. Add the gated flow as a class property next to `selectedTrendRangeFlow`:

```kotlin
private val sleepScoringPrefsFlow =
    settingsRepo.userPreferences
        .map { it.toSleepScoringPrefs() }
        .distinctUntilChanged()
```

4. Swap the outer `combine`'s third source (line 142):

```kotlin
combine(
    selectedDateRepository.selectedDate,
    selectedTrendRangeFlow,
    sleepScoringPrefsFlow,
) { date, range, prefs -> Triple(date, range, prefs) }
```

5. In the `flatMapLatest` body, the only semantic reference change is the derived zone (line 146):

```kotlin
val scoringZoneId = prefs.scoringZoneId
```

The other `prefs.*` references — `coreMergeGapMinutes`, `supplementalCutoffMinutesOfDay`, `minimumCountedSleepSegmentMinutes`, `supplementalArchitectureCoveragePercent` (SleepDayPolicy, lines 229-234) and `goalSleepHours` (lines 319, 324) — read identically off the new `SleepScoringPrefs` shape, so they stay as-is.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :feature:sleep:testDebugUnitTest --tests "app.readylytics.health.feature.sleep.SleepViewModelTest"`
Expected: PASS — both new tests plus all existing ones (goal-pref update, scoring-zone selection, trend calculations, sync-toggle identity checks).

- [ ] **Step 5: Commit**

```bash
git add feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepViewModel.kt feature/sleep/src/test/kotlin/app/readylytics/health/feature/sleep/SleepViewModelTest.kt
git commit -m "fix: gate sleep pipeline restart on consumed scoring prefs only"
```

---

### Task 6: `UniversalMetricRenderers` — revert `UniversalValueUnitColumn` to a flowing `Column` (source finding #6)

**Files:**
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/UniversalMetricRenderers.kt`
- Test: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardVisualizationLayoutTest.kt`

**Interfaces:**
- Consumes: `UniversalMetricPresentation`, `UNIVERSAL_SECONDARY_SLOT_HEIGHT` (20dp), `UNIVERSAL_TRACK_SECONDARY_GAP` (6dp), `UNIVERSAL_BAR_TRACK_EXTRA_THICKNESS` (4dp), `M3MetricBar` track slot.
- Produces: `UniversalValueUnitColumn` becomes a single flowing `Column` (value row `weight(1f, fill=false)` + `Spacer(weight(1f))` + track `Box` + secondary `Box`). `UniversalBarRenderer`/`UniversalValueRenderer` callers unchanged — geometry is preserved (Bar mode fills the track slot, Value mode leaves the identical slot empty), so `valueAndBarModes_shareTheirValueUnitAndSecondaryGeometry` must keep passing. Task 7 then dedups the inline `MaterialTheme.dimens.metricTrackThickness + UNIVERSAL_BAR_TRACK_EXTRA_THICKNESS` expressions that remain here.

- [ ] **Step 1: Write the failing regression test**

Append to `DashboardVisualizationLayoutTest.kt`:

```kotlin
@Test
fun barMode_shortCardHeight_keepsValueAboveTrackAndEverythingInsideCard() {
    val strainSpecification = requireNotNull(DashboardCardCatalog.spec(CardId.STRAIN_RATIO))
    composeRule.setContent {
        CompositionLocalProvider(
            LocalDensity provides Density(density = 1f, fontScale = 1.5f),
        ) {
            TestTheme {
                DashboardMetricCard(
                    presentation =
                        presentation.copy(
                            title = "Strain ratio",
                            valueText = "1.14",
                            secondaryText = "↑ 0.23",
                            accessibilityDescription = "Strain ratio 1.14, normal.",
                        ),
                    specification = strainSpecification,
                    requestedMode = DashboardCardDisplayMode.BAR,
                    isEditing = false,
                    onModeSelected = {},
                    modifier = Modifier.height(140.dp),
                )
            }
        }
    }

    composeRule.onNodeWithTag(UNIVERSAL_BAR_TAG, useUnmergedTree = true).assertIsDisplayed()
    composeRule.onNodeWithTag(UNIVERSAL_DELTA_PILL_TAG, useUnmergedTree = true).assertIsDisplayed()
    assertTextIsAboveBar("1.14")
    assertTagIsInsideCard(UNIVERSAL_BAR_TAG)
    assertTagIsInsideCard(UNIVERSAL_DELTA_PILL_TAG)
}
```

All helpers/imports it needs already exist in the test file and `DashboardVisualizationRegressionTestBase`.

- [ ] **Step 2: Run the test to verify it fails against the current Box implementation**

Run: `./gradlew :feature:dashboard:testDebugUnitTest --tests "app.readylytics.health.feature.dashboard.DashboardVisualizationLayoutTest"`
Expected: FAIL on `assertTextIsAboveBar("1.14")` — the Box's fixed bottom reservation (`UNIVERSAL_SECONDARY_SLOT_HEIGHT + UNIVERSAL_TRACK_SECONDARY_GAP + metricTrackThickness + UNIVERSAL_BAR_TRACK_EXTRA_THICKNESS`) is larger than the truly available space at 140dp × fontScale 1.5, so the value row is squeezed and the value text overlaps the track. **If it does not fail on the current code, tighten the fixture (drop the card height to 130dp) until it does.**

- [ ] **Step 3: Write minimal implementation**

Replace the entire current `UniversalValueUnitColumn` (lines 169-261) with:

```kotlin
// Shared vertical structure for Bar and Value mode: a baseline-aligned value/unit row on top, the
// track slot in the middle, and the secondary/delta slot at the bottom. Bar mode draws its Canvas
// into the track slot, Value mode leaves the same slot empty, so the two modes differ only in
// whether the track is painted and everything else stays put when switching between them.
@Composable
private fun UniversalValueUnitColumn(
    presentation: UniversalMetricPresentation,
    contentColor: Color,
    secondaryUsesPill: Boolean,
    modifier: Modifier = Modifier,
    track: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            // Elastic: the value/unit line keeps its natural height while the card has room and
            // gives way first under font-scale pressure, so the fixed-height track and the
            // secondary slot below it are never pushed out of the card.
            modifier = Modifier.weight(1f, fill = false),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
            Text(
                text = presentation.valueText,
                style = MaterialTheme.typography.displaySmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alignByBaseline(),
            )
            if (presentation.unitText.isNotBlank()) {
                Text(
                    text = presentation.unitText,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.extraSmall))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(MaterialTheme.dimens.metricTrackThickness + UNIVERSAL_BAR_TRACK_EXTRA_THICKNESS),
        ) {
            track()
        }

        Spacer(modifier = Modifier.height(UNIVERSAL_TRACK_SECONDARY_GAP))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(UNIVERSAL_SECONDARY_SLOT_HEIGHT),
            contentAlignment = Alignment.BottomStart,
        ) {
            presentation.secondaryText?.takeIf(String::isNotBlank)?.let { deltaText ->
                if (secondaryUsesPill) {
                    UniversalMetricDeltaPill(deltaText)
                } else {
                    Text(
                        text = deltaText,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.secondaryTextVerticalInset(),
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :feature:dashboard:testDebugUnitTest --tests "app.readylytics.health.feature.dashboard.DashboardVisualizationLayoutTest"`
Expected: PASS — the new short-height test plus all existing geometry tests (`barTrack_sharesVerticalPositionAcrossDifferentValueHeights`, `secondaryContent_sharesBottomEdgeAcrossDifferentValueHeights`, `valueAndBarModes_shareTheirValueUnitAndSecondaryGeometry`, `allModes_keepOriginalCardHeight`, etc.).

**If `assertTextIsAboveBar("1.14")` still fails after the revert** (value text legitimately taller than the reserved space at 140dp × fontScale 1.5, i.e. the assertion is over-strict for both layouts), relax the fixture to fontScale 1.25 — the goal is a regression test that FAILS on the old Box layout (Step 2) and PASSES on the new Column layout. Confirm the resolution on a device at the largest font scale before committing.

- [ ] **Step 5: Commit**

```bash
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/UniversalMetricRenderers.kt feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardVisualizationLayoutTest.kt
git commit -m "fix: revert universal metric value column to flowing layout"
```

---

### Task 7: `UniversalMetricRenderers` — dedupe the bar-thickness formula (source finding #9)

**Files:**
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/UniversalMetricRenderers.kt`

**Interfaces:**
- Consumes: `MaterialTheme.dimens.metricTrackThickness`, `UNIVERSAL_BAR_TRACK_EXTRA_THICKNESS` (4dp), and the layout produced by Task 6.
- Produces: `private val universalBarTrackThickness: Dp` (`@Composable get()`) as the single source of truth for the bar's rendered height. After Task 6 exactly three inline occurrences remain: `UniversalBarRenderer`'s `M3MetricBar(barHeight = ...)`, `UniversalValueUnitColumn`'s track `Box.height(...)`, and `UniversalValueRenderer`'s `Spacer.height(...)`.

- [ ] **Step 1: Add the composable-scoped getter**

In `UniversalMetricRenderers.kt`, next to the existing private dimension constants, add:

```kotlin
// Single source of truth for the bar's rendered track height, so a future thickness tweak cannot
// resize the painted bar in one place while its reserved slot stays the same elsewhere.
private val universalBarTrackThickness: Dp
    @Composable get() = MaterialTheme.dimens.metricTrackThickness + UNIVERSAL_BAR_TRACK_EXTRA_THICKNESS
```

Add `import androidx.compose.ui.unit.Dp` to the imports.

- [ ] **Step 2: Replace the three inline occurrences**

1. `UniversalBarRenderer` (line ~154):

```kotlin
barHeight = universalBarTrackThickness,
```

2. `UniversalValueUnitColumn` track `Box` (from Task 6):

```kotlin
Box(
    modifier =
        Modifier
            .fillMaxWidth()
            .height(universalBarTrackThickness),
) {
    track()
}
```

3. `UniversalValueRenderer` `Spacer` (line ~307):

```kotlin
Spacer(
    modifier =
        Modifier
            .fillMaxWidth()
            .height(universalBarTrackThickness),
)
```

- [ ] **Step 3: Run tests to verify the refactor is behavior-preserving**

Run: `./gradlew :feature:dashboard:testDebugUnitTest --tests "app.readylytics.health.feature.dashboard.DashboardVisualizationLayoutTest"`
Expected: PASS — identical assertions to Task 6 (pure extraction, no behavior change).

- [ ] **Step 4: Commit**

```bash
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/UniversalMetricRenderers.kt
git commit -m "refactor: dedupe universal metric bar track thickness"
```

---

### Task 8: `SleepTrendChart` — memoize the clock formatter so `remember` keys stay stable (source finding #8)

**Files:**
- Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendChart.kt`

**Interfaces:**
- Consumes: `DateFormat.getTimeFormat(context)` (reads the user's 12h/24h setting), `LocalContext.current`, `buildSleepTrendTooltipData(...)` which takes `clockFormatter`.
- Produces: no signature change. `clockFormatter` becomes `remember(context) { DateFormat.getTimeFormat(context) }` so the `remember(selectedState, rangeStartMs, scoringZoneId, ..., clockFormatter)` block (lines 179-205) hits its cache across ordinary recompositions instead of recomputing `tooltipState` on every one.

- [ ] **Step 1: Write the fix**

In `SleepTrendChart.kt`, replace line 157:

```kotlin
val clockFormatter = DateFormat.getTimeFormat(LocalContext.current)
```

with:

```kotlin
val context = LocalContext.current
val clockFormatter = remember(context) { DateFormat.getTimeFormat(context) }
```

(`remember` and `LocalContext` are already imported.) Keying on `context` still re-derives the formatter if the context changes (e.g. locale/12h↔24h config change) while memoizing across recompositions. Perf-only: no behavior change expected.

- [ ] **Step 2: Run the sleep tests to verify nothing regressed**

Run: `./gradlew :feature:sleep:testDebugUnitTest`
Expected: PASS — `SleepTrendTooltipFormatterTest`, `SleepTrendMarkerListenerTest`, `SleepViewModelTest` and the rest of the module.

- [ ] **Step 3: Manual smoke test (device/emulator)**

Open Sleep trend chart, tap a day, confirm the tooltip's bedtime/nap times still follow the device's 12h/24h setting.

- [ ] **Step 4: Commit**

```bash
git add feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendChart.kt
git commit -m "perf: memoize sleep trend clock formatter"
```

---

## Post-fix checklist (after all 8 tasks)

- [ ] `./gradlew ktlintFormat`
- [ ] `./gradlew testDebugUnitTest`
- [ ] `./gradlew lintRelease`
- [ ] Manual verification pass per each task's "Verify"/manual step:
  - Tooltips on Acwr, Trimp breakdown, Steps, HR timeline, Blood pressure, Sleep stages, Sleep HR (centered, narrow-as-content) and Sleep trend tooltip (left-aligned with bedtime + naps lines)
  - Resync progress screen marker flush within the thin bar (Settings → Resync Health Connect data)
  - Tap through multiple days on the sleep trend chart → bedtime/nap tooltip always matches the tapped day
  - Toggle an unrelated setting (e.g. theme) while the Sleep screen is open → no flicker/reload
  - Dashboard metric cards at Settings → Accessibility → largest font scale, Bar and Value modes → no clipped/overlapping value/unit text
  - Sleep trend tooltip content correct under 12h/24h
- [ ] Confirm `internal-docs/DATA_FLOW.md` untouched (verified by `git status` — no diff in `internal-docs/` other than the plan docs)
- [ ] Confirm no new source files were added (`git status --short` shows only modifications) → no `codegraph index` required
