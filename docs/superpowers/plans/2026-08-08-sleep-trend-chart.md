# Sleep Trend Chart Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the sleep trend use the scoring aggregator’s canonical main sleep, show total counted sleep including naps, and list naps in the tooltip.

**Architecture:** Add a pure Kotlin trend projection in `core:scoring` that converts `SleepDayAggregator` results into one model per scoring day. `SleepViewModel` will map repository sessions into scoring segments and expose both the existing chart points and the richer day projection; Compose will use the projection only for tooltip nap details.

**Tech Stack:** Kotlin, coroutines/Flow, Room-backed repositories, Compose Material 3, Vico Cartesian charts, JUnit/Kotlin unit tests.

## Global Constraints

- Room DB remains the single source of truth; the UI never accesses Health Connect.
- Reuse `SleepDayAggregator` and `SleepDayPolicy`; do not duplicate or change scoring formulas.
- Keep business/calculation logic pure Kotlin with zero Android dependencies.
- All user-facing strings must be added to `feature/sleep/src/main/res/values/strings.xml`.
- Preserve the current Vico cubic curve, gradient fill, selection overlay, scrolling, and zoom behavior.
- Keep files at or below the repository’s 400-line target and 800-line hard limit.
- Run `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`, then `./gradlew lintRelease` after implementation is complete.
- Run `codegraph index` after creating new files.

---

### Task 1: Add the pure Kotlin sleep-trend projection

**Files:**
- Create: `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/SleepTrendDay.kt`
- Create: `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/SleepTrendDayAssembler.kt`
- Test: `core/scoring/src/test/kotlin/app/readylytics/health/domain/scoring/sleep/SleepTrendDayAssemblerTest.kt`

**Interfaces:**
- Consumes: `List<SleepDaySegment>`, `LocalDate` range start, range length, and `SleepDayPolicy`.
- Produces: `List<SleepTrendDay>` with one entry per requested day, including empty days.

Define these immutable models:

```kotlin
data class SleepTrendNap(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMinutes: Int,
)

data class SleepTrendDay(
    val scoreDay: LocalDate,
    val coreStartTimeMs: Long?,
    val coreEndTimeMs: Long?,
    val totalDurationMinutes: Int?,
    val naps: List<SleepTrendNap>,
)
```

Define:

```kotlin
object SleepTrendDayAssembler {
    fun assemble(
        segments: List<SleepDaySegment>,
        rangeStart: LocalDate,
        rangeDays: Int,
        policy: SleepDayPolicy,
    ): List<SleepTrendDay>
}
```

- Call `SleepDayAggregator.aggregate(segments, policy)` once.
- Index aggregates by `scoreDay` and emit exactly `rangeDays` entries from `rangeStart`.
- For a non-empty aggregate, use `coreCluster.startTimeMs` and `coreCluster.endTimeMs` as the main interval, `totalDurationMinutes` as the total, and map `supplementalBlocks` to naps sorted by start time and stable ID.
- For an empty day, emit null core/totals and an empty nap list.
- Preserve the aggregator’s selected `coreCluster` even when the day contains only one daytime segment; that segment supplies the displayed main interval.

- [ ] **Step 1: Write failing assembler tests.** Cover an overnight core plus two naps, a session starting before the visible range but assigned to its first scoring day, cutoff-boundary day assignment, minimum-duration filtering, overlap canonicalization delegated to the aggregator including its stable-ID winner, single-segment days using the core cluster, empty days, and deterministic ordering of non-overlapping naps.

```kotlin
@Test
fun `total uses core cluster and supplemental blocks while core interval stays main sleep`() {
    val days = SleepTrendDayAssembler.assemble(segments, LocalDate.of(2026, 8, 1), 1, policy)

    assertEquals(545, days.single().totalDurationMinutes)
    assertEquals(coreStart, days.single().coreStartTimeMs)
    assertEquals(coreEnd, days.single().coreEndTimeMs)
    assertEquals(listOf(napStart), days.single().naps.map { it.startTimeMs })
}
```

- [ ] **Step 2: Run the focused test and verify it fails for the missing projection.**

Run: `./gradlew :core:scoring:testDebugUnitTest --tests '*SleepTrendDayAssemblerTest'`

Expected: FAIL because `SleepTrendDayAssembler` and its output models do not exist yet.

- [ ] **Step 3: Implement the models and assembler.** Keep the implementation a pure transformation over `SleepDayAggregator` output; do not reimplement cluster selection, cutoffs, or overlap rules.

- [ ] **Step 4: Run the focused tests.**

Run: `./gradlew :core:scoring:testDebugUnitTest --tests '*SleepTrendDayAssemblerTest'`

Expected: PASS.

- [ ] **Step 5: Commit the projection.**

```bash
git add core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/SleepTrendDay.kt core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/SleepTrendDayAssembler.kt core/scoring/src/test/kotlin/app/readylytics/health/domain/scoring/sleep/SleepTrendDayAssemblerTest.kt
git commit -m "feat: project aggregated sleep trend days"
```

### Task 2: Feed scoring-day aggregates into `SleepViewModel`

**Files:**
- Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepViewModel.kt`
- Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepScreen.kt`
- Modify: `feature/sleep/src/test/kotlin/app/readylytics/health/feature/sleep/SleepViewModelTest.kt`

**Interfaces:**
- Consumes: repository `SleepSessionData`, `UserPreferences`, and `SleepTrendDayAssembler.assemble`.
- Produces: `SleepUiState.trendDays: List<SleepTrendDay>` alongside the existing three padded `DailyDataPoint` lists.

- Build `SleepDaySegment` values from each `SleepSessionData`, copying ID, times, duration, stage totals, efficiency, and zone offsets.
- Use `UserPreferences.scoringZone()` to construct the `SleepDayPolicy` with the existing four sleep settings.
- Move the trend range/date-boundary calculation into the preferences-aware flow so scoring-day boundaries use the same configured zone as aggregation.
- Query at least the existing `rangeStart.minusDays(2)` session window, retain sessions needed for the first visible scoring day, and pass all returned segments to the assembler.
- Replace `sessionsByDay[targetDate].firstOrNull()` with the assembled `SleepTrendDay` entries.
- Derive chart points from each day: core start offset and core span for the window series, and `totalDurationMinutes / 60f` for the duration line.
- Keep null padding exactly `range.days` entries so current loading/no-data behavior remains stable.
- Leave the selected-day detail flow (`observeFirstSessionEndingInRange`) unchanged; this task changes the trend flow only.

- [ ] **Step 1: Add failing ViewModel tests.** Add fixtures with an overnight core and a daytime nap assigned to the same scoring day; assert the window points use core start/span, the duration point equals the combined total, and `trendDays` contains the nap. Add a cutoff-boundary fixture and a single-segment fixture.

```kotlin
assertEquals(8.5f, state.trendActualDurationPoints[dayOffset].value!!, 0.01f)
assertEquals(listOf(napStart), state.trendDays[dayOffset].naps.map { it.startTimeMs })
```

- [ ] **Step 2: Run the focused ViewModel tests and verify the old first-session behavior fails.**

Run: `./gradlew :feature:sleep:testDebugUnitTest --tests '*SleepViewModelTest'`

Expected: FAIL on the new aggregate expectations while existing baseline tests continue to identify the old behavior.

- [ ] **Step 3: Implement the preferences-aware trend flow and state field.** Convert sessions to `SleepDaySegment`, assemble days, and derive the existing point lists from the projection.

- [ ] **Step 4: Update the screen call site.** Pass `uiState.trendDays` to `SleepTrendCard` without moving persistent UI state into the Composable.

- [ ] **Step 5: Run all sleep feature unit tests.**

Run: `./gradlew :feature:sleep:testDebugUnitTest`

Expected: PASS, including existing range padding, loading, sync, and selected-date tests.

- [ ] **Step 6: Commit the ViewModel integration.**

```bash
git add feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepViewModel.kt feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepScreen.kt feature/sleep/src/test/kotlin/app/readylytics/health/feature/sleep/SleepViewModelTest.kt
git commit -m "fix: aggregate sleep trend by scoring day"
```

### Task 3: Carry naps through chart selection and render tooltip bullets

**Files:**
- Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendSelectedState.kt`
- Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendMarkerListener.kt`
- Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendChart.kt`
- Modify: `feature/sleep/src/main/res/values/strings.xml`
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/DataPointTooltip.kt`
- Test: `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/DataPointTooltipTest.kt`

**Interfaces:**
- Consumes: `SleepTrendDay` keyed by chart `dayOffset` and the existing Vico marker targets.
- Produces: selected state with nap intervals and tooltip content ordered as duration, bedtime, naps, date.

- Add `naps: List<SleepTrendNap>` to `SleepTrendSelectedState`.
- Pass `trendDays` into `rememberSleepTrendMarkerVisibilityListener`; resolve the selected day’s nap list while preserving the existing point/bar canvas coordinates.
- Keep chart series semantics unchanged: stacked columns remain core sleep window and the line remains total duration.
- Add a small pre-date-lines capability to `DataPointTooltipData` so nap bullets render between bedtime and date without changing existing tooltip call sites. Render those lines before `dateText` and keep `extraLine` behavior intact.
- Add localized strings for the naps heading and nap item format, using `DateFormatUtils.formatSleepDuration` for each nap duration and the existing localized clock formatting path for times.
- Build tooltip data with one line per nap, prefixed as a bullet, and omit the naps section when there are no supplemental blocks.

- [ ] **Step 1: Add failing tooltip/model tests.** Verify a selected day carries naps and that tooltip lines are ordered as bedtime, naps heading/items, then date; verify a no-nap day has no empty naps heading.

```kotlin
assertEquals(listOf("Naps:", "• 2:00 PM – 2:35 PM (35m)"), data.preDateLines)
assertEquals("01.08", data.extraLine)
```

- [ ] **Step 2: Run focused UI tests and verify the new assertions fail.**

Run: `./gradlew :core:ui:testDebugUnitTest --tests '*DataPointTooltipTest'`

Expected: FAIL because tooltip line support and nap selection data are not implemented.

- [ ] **Step 3: Implement selected-state and marker propagation.** Keep marker identity stable with `rememberUpdatedState`; do not recreate listeners during gestures.

- [ ] **Step 4: Implement tooltip line rendering and resource-backed formatting.** Keep all labels and formats in `feature/sleep` resources and preserve the existing popup positioning and Material styling.

- [ ] **Step 5: Run focused UI and sleep tests.**

Run: `./gradlew :core:ui:testDebugUnitTest --tests '*DataPointTooltipTest' :feature:sleep:testDebugUnitTest`

Expected: PASS with existing tooltip and chart tests unchanged.

- [ ] **Step 6: Commit chart tooltip behavior.**

```bash
git add feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendSelectedState.kt feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendMarkerListener.kt feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendChart.kt feature/sleep/src/main/res/values/strings.xml core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/DataPointTooltip.kt core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/DataPointTooltipTest.kt
git commit -m "feat: show naps in sleep trend tooltip"
```

### Task 4: Verify formatting, regression coverage, and build readiness

**Files:**
- Modify only files identified by formatter/lint if required; do not broaden scope.

**Interfaces:**
- Consumes: completed projection, ViewModel, and tooltip changes.
- Produces: verified implementation with no stale chart behavior or resource errors.

- [ ] **Step 1: Run code formatting.**

Run: `./gradlew ktlintFormat`

- [ ] **Step 2: Run the complete debug unit-test suite.**

Run: `./gradlew testDebugUnitTest`

Expected: PASS, including `SleepDayAggregator` regressions, the new projection tests, ViewModel tests, and tooltip tests.

- [ ] **Step 3: Inspect the final diff and verify requirements.** Confirm there is no `sessionsForDay.firstOrNull()` in the trend path, all new copy is in resources, chart points use core interval plus total duration, and naps are not rendered as additional bars.

- [ ] **Step 4: Run release lint.**

Run: `./gradlew lintRelease`

Expected: PASS with no missing translations, Compose/resource errors, or lint regressions.

- [ ] **Step 5: Re-index the project after new files are complete.**

Run: `codegraph index`

- [ ] **Step 6: Commit formatter corrections separately when the formatter changed tracked implementation files.**

```bash
git add core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/SleepTrendDay.kt core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/SleepTrendDayAssembler.kt core/scoring/src/test/kotlin/app/readylytics/health/domain/scoring/sleep/SleepTrendDayAssemblerTest.kt feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepViewModel.kt feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepScreen.kt feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendSelectedState.kt feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendMarkerListener.kt feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendChart.kt feature/sleep/src/main/res/values/strings.xml core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/DataPointTooltip.kt core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/DataPointTooltipTest.kt
git commit -m "style: format sleep trend changes"
```
