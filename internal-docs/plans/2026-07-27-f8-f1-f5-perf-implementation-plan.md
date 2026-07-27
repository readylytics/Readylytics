# F8, F1, F5 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land F8 (`@Immutable` UI-state annotations), F1 (stop routine sync from
skeleton-flashing/rebuilding Vitals/Sleep/Workouts charts), and F5 (isolate Vitals recomposition
scope) from `internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md`, in that order, as three
independent commits.

**Architecture:** No new architecture. F8 adds a stability-contract annotation to four existing
`data class` UI states. F1 refines the already-landed (F4/F9) "merge sync state after the heavy
pipeline" pattern in three ViewModels, splitting one boolean into two (`isLoading` = true first
load only, `isRefreshing` = any sync). F5 extracts two focused composables + one `@Immutable`
slice type out of `VitalsScreen.kt`, mirroring `DashboardUiState.cardInputs()`.

**Tech Stack:** Kotlin, Jetpack Compose (Compose Compiler stability annotations), Hilt ViewModels,
Kotlin Flow (`combine`, `distinctUntilChanged`), JUnit4 + MockK + `kotlinx-coroutines-test`.

## Global Constraints

- Charts stay composed — no lazification, placeholder, or destroy-off-viewport of any chart.
  Manually verify scroll-back never visibly recreates a chart after each UI-touching commit.
- No changes to scoring math, thresholds, or coefficients.
- No functional regressions except F1's already-approved UX change (skeletons on true first-load
  only, not on every routine sync).
- No new visible "refresh indicator" widget — confirmed with maintainer, matches Dashboard's
  current (indicator-less) behavior.
- Pre-commit every commit: `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`. Run
  `./gradlew lintRelease` once, after the final (F5) commit.
- New files get `codegraph index` run after creation.
- Design source of truth: `internal-docs/plans/2026-07-27-f8-f1-f5-perf-design.md`. Read it before
  starting if anything below is ambiguous — it has the full rationale.

---

## File Structure

| File | Change |
|---|---|
| `feature/vitals/.../overview/VitalsViewModel.kt` | Modify: `@Immutable` (F8), `isLoading`/`isRefreshing` split (F1) |
| `feature/vitals/.../overview/VitalsViewModelTest.kt` | Modify: new tests (F1) |
| `feature/vitals/.../overview/VitalsScreen.kt` | Modify: `ScreenHeaderSection` wiring (F1); rewritten as composition root (F5) |
| `feature/vitals/.../overview/VitalsStateFactory.kt` | Modify: add `VitalsChartInputs` + `chartInputs()` (F5) |
| `feature/vitals/.../overview/VitalsGaugeRow.kt` | **Create** (F5) |
| `feature/vitals/.../overview/VitalsTrendSection.kt` | **Create** (F5) |
| `feature/sleep/.../SleepViewModel.kt` | Modify: `@Immutable` (F8), `isLoading`/`isRefreshing` split (F1) |
| `feature/sleep/.../SleepViewModelTest.kt` | Modify: new tests (F1) |
| `feature/workouts/.../WorkoutsViewModel.kt` | Modify: `@Immutable` (F8), `isLoading`/`isRefreshing` split (F1) |
| `feature/workouts/.../WorkoutsViewModelTest.kt` | Modify: fix one existing test + new tests (F1) |
| `feature/workouts/.../WorkoutsScreen.kt` | Modify: `ScreenHeaderSection` wiring (F1) |
| `feature/workouts/.../WorkoutDetailViewModel.kt` | Modify: `@Immutable` (F8 only) |

No new strings, no DB/DAO changes, no `DATA_FLOW.md` update required (nothing here touches
ingestion, schema, or scoring coordinators).

---

## Task 1: F8 — `@Immutable` on the four feature UI states

**Files:**
- Modify: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsViewModel.kt`
- Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepViewModel.kt`
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModel.kt`
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutDetailViewModel.kt`

**Interfaces:**
- Produces: `VitalsUiState`, `SleepUiState`, `WorkoutsUiState`, `WorkoutDetailUiState` all annotated
  `@Immutable` — Task 6 (F5) relies on this for `VitalsChartInputs` to actually skip.

- [ ] **Step 1: Add the annotation to `VitalsUiState`**

In `VitalsViewModel.kt`, add the import and annotate the class:

```kotlin
import androidx.compose.runtime.Immutable
```

```kotlin
@Immutable
data class VitalsUiState(
    val latestSummary: DailySummary? = null,
    val chartSeries: VitalsChartSeries = VitalsChartSeries(emptyList(), emptyList(), emptyList()),
    val presentation: VitalsPresentationState = VitalsPresentationState.empty(),
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val selectedDate: LocalDate = LocalDate.now(),
    val rangeStartMs: Long = System.currentTimeMillis(),
    val isLoading: Boolean = false,
)
```

(Place the new import alphabetically with the existing `androidx.lifecycle.*` imports at the top
of the file — Kotlin doesn't require alphabetical import order, but this file already keeps them
sorted; `ktlintFormat` will fix ordering regardless.)

- [ ] **Step 2: Add the annotation to `SleepUiState`**

In `SleepViewModel.kt`, add the import and annotate:

```kotlin
import androidx.compose.runtime.Immutable
```

```kotlin
@Immutable
data class SleepUiState(
    val latestSummary: DailySummary? = null,
    // ... rest of the class body is unchanged
)
```

- [ ] **Step 3: Add the annotation to `WorkoutsUiState`**

In `WorkoutsViewModel.kt`, add the import and annotate:

```kotlin
import androidx.compose.runtime.Immutable
```

```kotlin
@Immutable
data class WorkoutsUiState(
    val latestSummary: DailySummary? = null,
    // ... rest of the class body is unchanged
)
```

Leave `WorkoutDisplayItem` (also declared in this file) un-annotated — the plan only targets the
four top-level screen `UiState` classes; `WorkoutDisplayItem` is a list-item type, not a
composition root.

- [ ] **Step 4: Add the annotation to `WorkoutDetailUiState`**

In `WorkoutDetailViewModel.kt`, add the import and annotate:

```kotlin
import androidx.compose.runtime.Immutable
```

```kotlin
@Immutable
data class WorkoutDetailUiState(
    val workout: WorkoutData? = null,
    // ... rest of the class body is unchanged
)
```

- [ ] **Step 5: Run the existing unit test suites to confirm no regression**

Run: `./gradlew :feature:vitals:testDebugUnitTest :feature:sleep:testDebugUnitTest :feature:workouts:testDebugUnitTest`
Expected: PASS, unchanged (the annotation is a compile-time contract only — it changes no runtime
value or equality behavior).

- [ ] **Step 6: Generate and inspect the compose compiler reports**

Run:
```bash
./gradlew :feature:vitals:assembleRelease -PenableComposeReports
./gradlew :feature:sleep:assembleRelease -PenableComposeReports
./gradlew :feature:workouts:assembleRelease -PenableComposeReports
```

Each produces `<module>/build/compose-metrics/*-classes.txt`. Grep for the annotated class names:

```bash
grep -A2 "VitalsUiState" feature/vitals/build/compose-metrics/*-classes.txt
grep -A2 "SleepUiState" feature/sleep/build/compose-metrics/*-classes.txt
grep -A2 "WorkoutsUiState\|WorkoutDetailUiState" feature/workouts/build/compose-metrics/*-classes.txt
```

Expected: each class reported `stable class VitalsUiState { ... }` (etc.) — no `unstable` fields.

- [ ] **Step 7: Format and commit**

```bash
./gradlew ktlintFormat
git add feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsViewModel.kt \
        feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepViewModel.kt \
        feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModel.kt \
        feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutDetailViewModel.kt
git commit -m "Annotate feature UI states @Immutable (F8)"
```

---

## Task 2: F1 — Vitals `isLoading`/`isRefreshing` split

**Files:**
- Modify: `feature/vitals/.../overview/VitalsViewModel.kt`
- Modify: `feature/vitals/.../overview/VitalsScreen.kt:100`
- Modify: `feature/vitals/.../overview/VitalsViewModelTest.kt`

**Interfaces:**
- Produces: `VitalsUiState.isRefreshing: Boolean` (new field). `isLoading`'s meaning changes to
  "true first-load, no summary yet" — every existing reader of `isLoading` keeps compiling
  unchanged, with a new (approved) meaning.

- [ ] **Step 1: Split the flag in the final `combine` block**

Replace this block in `VitalsViewModel.kt`:

```kotlin
        val uiState: StateFlow<VitalsUiState> =
            combine(
                contentFlow,
                presentationFlow,
                foregroundSyncController.isSyncing,
            ) { content, presentation, isSyncing ->
                VitalsUiState(
                    latestSummary = content.latestSummary,
                    chartSeries = content.chartSeries,
                    presentation = presentation,
                    selectedRange = content.selection.range,
                    selectedDate = content.selection.date,
                    rangeStartMs = content.rangeStartMs,
                    isLoading = isSyncing,
                )
            }.stateIn(
```

with:

```kotlin
        val uiState: StateFlow<VitalsUiState> =
            // isLoading now means "true first-load, no data yet" (skeleton). isRefreshing tracks
            // every sync regardless of data presence, and only gates the date-switcher (see
            // VitalsScreen). Mirrors DashboardViewModel's isComputingMetrics/isRefreshing split.
            combine(
                contentFlow,
                presentationFlow,
                foregroundSyncController.isSyncing,
            ) { content, presentation, isSyncing ->
                VitalsUiState(
                    latestSummary = content.latestSummary,
                    chartSeries = content.chartSeries,
                    presentation = presentation,
                    selectedRange = content.selection.range,
                    selectedDate = content.selection.date,
                    rangeStartMs = content.rangeStartMs,
                    isLoading = isSyncing && content.latestSummary == null,
                    isRefreshing = isSyncing,
                )
            }.stateIn(
```

And add the field to `VitalsUiState` (right after `isLoading`):

```kotlin
@Immutable
data class VitalsUiState(
    val latestSummary: DailySummary? = null,
    val chartSeries: VitalsChartSeries = VitalsChartSeries(emptyList(), emptyList(), emptyList()),
    val presentation: VitalsPresentationState = VitalsPresentationState.empty(),
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val selectedDate: LocalDate = LocalDate.now(),
    val rangeStartMs: Long = System.currentTimeMillis(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
)
```

- [ ] **Step 2: Switch the date-switcher gate to `isRefreshing`**

In `VitalsScreen.kt`, change line 100:

```kotlin
        ScreenHeaderSection(isLoading = uiState.isLoading) { isDisabled ->
```

to:

```kotlin
        ScreenHeaderSection(isLoading = uiState.isRefreshing) { isDisabled ->
```

Every other reader of `uiState.isLoading` in this file (the two `CardLoader` calls at the gauge
row and the three trend charts, the `SectionHeader`/`SegmentedButton` `enabled` params) is left
unchanged — same expression, new (initial-load-only) meaning.

- [ ] **Step 3: Write the new "data present" test**

Add to `VitalsViewModelTest.kt` (needs `import org.junit.Assert.assertTrue` added alongside the
existing `assertFalse`/`assertSame` imports):

```kotlin
    @Test
    fun `isRefreshing toggles independently of isLoading when data is present`() =
        runTest {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                val before = viewModel.uiState.value
                assertFalse(before.isLoading)
                assertFalse(before.isRefreshing)

                syncing.value = true
                advanceUntilIdle()
                val during = viewModel.uiState.value
                assertFalse(during.isLoading)
                assertTrue(during.isRefreshing)

                syncing.value = false
                advanceUntilIdle()
                val after = viewModel.uiState.value
                assertFalse(after.isLoading)
                assertFalse(after.isRefreshing)
            } finally {
                collector.cancel()
            }
        }
```

This relies on `setUp()`'s existing fixture, which already populates `summaries` with two real
entries (today + yesterday) before every test.

- [ ] **Step 4: Write the new "no data yet" test**

```kotlin
    @Test
    fun `isLoading stays true while syncing when no summary exists yet`() =
        runTest {
            summaries.value = emptyList()
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                syncing.value = true
                advanceUntilIdle()
                val state = viewModel.uiState.value
                assertTrue(state.isLoading)
                assertTrue(state.isRefreshing)
            } finally {
                collector.cancel()
            }
        }
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :feature:vitals:testDebugUnitTest`
Expected: all tests PASS, including the two new ones and the pre-existing
`sync change preserves structurally equal chart series` (unaffected — it never asserted on
`isLoading` during sync).

---

## Task 3: F1 — Sleep `isLoading`/`isRefreshing` split

**Files:**
- Modify: `feature/sleep/.../SleepViewModel.kt`
- Modify: `feature/sleep/.../SleepViewModelTest.kt`

**Interfaces:**
- Produces: `SleepUiState.isRefreshing: Boolean` (new field).

Sleep has no `ScreenHeaderSection`/date-switcher gate today (`SleepScreen.kt`'s `DateSwitcher` call
has no `enabled` param) — this task is VM-only, no screen change.

- [ ] **Step 1: Split the flag in the sync-merge `combine` step**

Replace this block in `SleepViewModel.kt`:

```kotlin
                    }.distinctUntilChanged()
                        // isSyncing is merged in after the heavy pipeline instead of inside it
                        // (mirrors DashboardViewModel.kt:104-113) so a sync toggle only triggers a
                        // cheap copy, not a full re-run of the trend-day-loop unpacking above.
                        .combine(
                            foregroundSyncController.isSyncing,
                        ) { state, syncing -> state.copy(isLoading = syncing) }
                }.flowOn(defaultDispatcher)
```

with:

```kotlin
                    }.distinctUntilChanged()
                        // isSyncing is merged in after the heavy pipeline instead of inside it
                        // (mirrors DashboardViewModel.kt:104-113) so a sync toggle only triggers a
                        // cheap copy, not a full re-run of the trend-day-loop unpacking above.
                        // isLoading means "true first-load, no data yet" (skeleton); isRefreshing
                        // tracks every sync regardless of data presence. The trend point lists are
                        // always padded to range.days entries (null-valued, never actually empty),
                        // so latestSummary/latestSession null-checks are the correct "no data yet"
                        // signal here, not a trend-list emptiness check.
                        .combine(
                            foregroundSyncController.isSyncing,
                        ) { state, syncing ->
                            state.copy(
                                isLoading = syncing && (state.latestSummary == null && state.latestSession == null),
                                isRefreshing = syncing,
                            )
                        }
                }.flowOn(defaultDispatcher)
```

And add the field to `SleepUiState` (right after `isLoading`):

```kotlin
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
```

- [ ] **Step 2: Extend the existing sync-toggle test with `isRefreshing` assertions**

In `SleepViewModelTest.kt`, replace the body of
`isSyncing toggle does not recompute content, only isLoading changes`:

```kotlin
    @Test
    fun `isSyncing toggle does not recompute content, only isLoading changes`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testDispatcher.scheduler.advanceUntilIdle()

            val stateBeforeToggle = viewModel.uiState.value
            assertEquals(false, stateBeforeToggle.isLoading)

            isSyncingFlow.value = true
            testDispatcher.scheduler.advanceUntilIdle()
            val stateSyncing = viewModel.uiState.value
            assertEquals(true, stateSyncing.isLoading)
            // Only isLoading should differ -- the content (trend lists etc.) must be the exact
            // same object, proving the sync toggle did not re-run the heavy day-loop unpacking.
            assertSame(stateBeforeToggle.trendStartOffsetPoints, stateSyncing.trendStartOffsetPoints)

            isSyncingFlow.value = false
            testDispatcher.scheduler.advanceUntilIdle()
            val stateAfterToggle = viewModel.uiState.value
            assertEquals(false, stateAfterToggle.isLoading)
            assertSame(stateBeforeToggle.trendStartOffsetPoints, stateAfterToggle.trendStartOffsetPoints)

            collectJob.cancelAndJoin()
        }
```

with (this fixture has no summary/session data by default, so `isLoading` staying `true` during
sync is now the *correct* true-first-load behavior, not a coincidence — the new assertions on
`isRefreshing` make that explicit):

```kotlin
    @Test
    fun `isSyncing toggle does not recompute content, only isLoading and isRefreshing change`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testDispatcher.scheduler.advanceUntilIdle()

            val stateBeforeToggle = viewModel.uiState.value
            assertEquals(false, stateBeforeToggle.isLoading)
            assertEquals(false, stateBeforeToggle.isRefreshing)

            isSyncingFlow.value = true
            testDispatcher.scheduler.advanceUntilIdle()
            val stateSyncing = viewModel.uiState.value
            // No summary/session exists in this fixture, so this is genuinely the first-load
            // case: isLoading correctly stays true.
            assertEquals(true, stateSyncing.isLoading)
            assertEquals(true, stateSyncing.isRefreshing)
            // Only the flags should differ -- the content (trend lists etc.) must be the exact
            // same object, proving the sync toggle did not re-run the heavy day-loop unpacking.
            assertSame(stateBeforeToggle.trendStartOffsetPoints, stateSyncing.trendStartOffsetPoints)

            isSyncingFlow.value = false
            testDispatcher.scheduler.advanceUntilIdle()
            val stateAfterToggle = viewModel.uiState.value
            assertEquals(false, stateAfterToggle.isLoading)
            assertEquals(false, stateAfterToggle.isRefreshing)
            assertSame(stateBeforeToggle.trendStartOffsetPoints, stateAfterToggle.trendStartOffsetPoints)

            collectJob.cancelAndJoin()
        }
```

- [ ] **Step 3: Write a new "data present" test**

```kotlin
    @Test
    fun `isLoading stays false and isRefreshing toggles when sleep data is present`() =
        runTest(testDispatcher) {
            val zoneId = ZoneId.systemDefault()
            val selectedDate = selectedDateFlow.value
            val session =
                SleepSessionData(
                    id = "session_1",
                    deviceName = "SmartRing",
                    startTime =
                        selectedDate
                            .minusDays(1)
                            .atTime(22, 0)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    endTime = selectedDate.atTime(6, 0).atZone(zoneId).toInstant().toEpochMilli(),
                    durationMinutes = 480,
                    efficiency = 0.93f,
                    deepSleepMinutes = 90,
                    lightSleepMinutes = 300,
                    remSleepMinutes = 90,
                    awakeMinutes = 30,
                    sleepScore = 85f,
                )
            every { sleepSessionRepository.observeFirstSessionEndingInRange(any(), any()) } returns flowOf(session)
            every { sleepSessionRepository.observeSessionStages(session.id) } returns flowOf(emptyList())

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testDispatcher.scheduler.advanceUntilIdle()

            val stateBeforeToggle = viewModel.uiState.first { it.latestSession != null }
            assertEquals(false, stateBeforeToggle.isLoading)
            assertEquals(false, stateBeforeToggle.isRefreshing)

            isSyncingFlow.value = true
            testDispatcher.scheduler.advanceUntilIdle()
            val stateSyncing = viewModel.uiState.value
            assertEquals(false, stateSyncing.isLoading)
            assertEquals(true, stateSyncing.isRefreshing)

            isSyncingFlow.value = false
            testDispatcher.scheduler.advanceUntilIdle()
            val stateAfterToggle = viewModel.uiState.value
            assertEquals(false, stateAfterToggle.isLoading)
            assertEquals(false, stateAfterToggle.isRefreshing)

            collectJob.cancelAndJoin()
        }
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :feature:sleep:testDebugUnitTest`
Expected: all tests PASS.

---

## Task 4: F1 — Workouts `isLoading`/`isRefreshing` split

**Files:**
- Modify: `feature/workouts/.../WorkoutsViewModel.kt`
- Modify: `feature/workouts/.../WorkoutsScreen.kt:69`
- Modify: `feature/workouts/.../WorkoutsViewModelTest.kt`

**Interfaces:**
- Produces: `WorkoutsUiState.isRefreshing: Boolean` (new field).

- [ ] **Step 1: Split the flag in the sync-merge `combine` step**

Replace this block in `WorkoutsViewModel.kt`:

```kotlin
                }.distinctUntilChanged()
                // isSyncing is merged in after the heavy pipeline instead of inside it (mirrors
                // DashboardViewModel.kt:104-113) so a sync toggle only triggers a cheap copy, not a
                // full pipeline restart (Room re-subscriptions, EMA series, N+1 HR-sample loop).
                .combine(foregroundSyncController.isSyncing) { state, syncing -> state.copy(isLoading = syncing) }
                .flowOn(defaultDispatcher)
```

with:

```kotlin
                }.distinctUntilChanged()
                // isSyncing is merged in after the heavy pipeline instead of inside it (mirrors
                // DashboardViewModel.kt:104-113) so a sync toggle only triggers a cheap copy, not a
                // full pipeline restart (Room re-subscriptions, EMA series, N+1 HR-sample loop).
                // isLoading means "true first-load, no data yet" (skeleton); isRefreshing tracks
                // every sync regardless of data presence. dailyTrimp/dailyStrainRatio are always
                // padded to displayDayMidnights.size entries (null-valued, never actually empty),
                // so latestSummary/recentWorkouts are the correct "no data yet" signal here.
                .combine(foregroundSyncController.isSyncing) { state, syncing ->
                    state.copy(
                        isLoading = syncing && (state.latestSummary == null && state.recentWorkouts.isEmpty()),
                        isRefreshing = syncing,
                    )
                }
                .flowOn(defaultDispatcher)
```

And add the field to `WorkoutsUiState` (right after `isLoading`):

```kotlin
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
```

- [ ] **Step 2: Switch the date-switcher gate to `isRefreshing`**

In `WorkoutsScreen.kt`, change line 69:

```kotlin
        ScreenHeaderSection(isLoading = uiState.isLoading) { isDisabled ->
```

to:

```kotlin
        ScreenHeaderSection(isLoading = uiState.isRefreshing) { isDisabled ->
```

Every other reader of `uiState.isLoading` (the `CardLoader` in `WorkoutsScreen.kt` and the three
in `WorkoutStatsSection.kt`, plus its `SegmentedButton enabled` param) is unchanged.

- [ ] **Step 3: Fix the now-incompatible existing sync-toggle test**

This existing test asserts the *old* semantics (`isLoading == true` while syncing, even with data
present) and will fail once Step 1 lands. In `WorkoutsViewModelTest.kt`, replace the body of
`isSyncing toggle does not restart the heavy pipeline`:

```kotlin
    @Test
    fun `isSyncing toggle does not restart the heavy pipeline`() =
        runTest(testDispatcher) {
            val workout =
                WorkoutData(
                    id = "run-1",
                    startTime = System.currentTimeMillis() - 1000 * 60 * 30,
                    endTime = System.currentTimeMillis(),
                    exerciseType = "running",
                    durationMinutes = 30,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 0f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 50f,
                    avgHr = 130f,
                )
            workoutsFlow.value = listOf(workout)

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            val stateBeforeToggle = viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }
            assertEquals(false, stateBeforeToggle.isLoading)

            isSyncingFlow.value = true
            testScheduler.advanceUntilIdle()
            assertEquals(true, viewModel.uiState.value.isLoading)

            isSyncingFlow.value = false
            testScheduler.advanceUntilIdle()
            val stateAfterToggle = viewModel.uiState.value
            assertEquals(false, stateAfterToggle.isLoading)

            // The heavy pipeline (Room subscriptions, earliest-workout lookup, EMA series) must
            // not restart on a sync toggle -- only the cheap isLoading merge should run.
            coVerify(exactly = 1) { workoutRepository.getEarliestWorkoutTimestamp() }
            assertSame(stateBeforeToggle.recentWorkouts, stateAfterToggle.recentWorkouts)

            collectJob.cancel()
        }
```

with (workouts are present in this fixture, so `isLoading` now correctly stays `false` throughout
— only `isRefreshing` toggles):

```kotlin
    @Test
    fun `isSyncing toggle does not restart the heavy pipeline`() =
        runTest(testDispatcher) {
            val workout =
                WorkoutData(
                    id = "run-1",
                    startTime = System.currentTimeMillis() - 1000 * 60 * 30,
                    endTime = System.currentTimeMillis(),
                    exerciseType = "running",
                    durationMinutes = 30,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 0f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 50f,
                    avgHr = 130f,
                )
            workoutsFlow.value = listOf(workout)

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            val stateBeforeToggle = viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }
            assertEquals(false, stateBeforeToggle.isLoading)
            assertEquals(false, stateBeforeToggle.isRefreshing)

            isSyncingFlow.value = true
            testScheduler.advanceUntilIdle()
            // Workouts are already present, so this is a routine refresh, not a first load:
            // isLoading must stay false (no skeleton/chart rebuild) and only isRefreshing flips.
            assertEquals(false, viewModel.uiState.value.isLoading)
            assertEquals(true, viewModel.uiState.value.isRefreshing)

            isSyncingFlow.value = false
            testScheduler.advanceUntilIdle()
            val stateAfterToggle = viewModel.uiState.value
            assertEquals(false, stateAfterToggle.isLoading)
            assertEquals(false, stateAfterToggle.isRefreshing)

            // The heavy pipeline (Room subscriptions, earliest-workout lookup, EMA series) must
            // not restart on a sync toggle -- only the cheap isLoading/isRefreshing merge should run.
            coVerify(exactly = 1) { workoutRepository.getEarliestWorkoutTimestamp() }
            assertSame(stateBeforeToggle.recentWorkouts, stateAfterToggle.recentWorkouts)

            collectJob.cancel()
        }
```

- [ ] **Step 4: Write a new "no data yet" test**

```kotlin
    @Test
    fun `isLoading stays true while syncing when no workouts or summary exist yet`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            isSyncingFlow.value = true
            testScheduler.advanceUntilIdle()
            val state = viewModel.uiState.value
            assertEquals(true, state.isLoading)
            assertEquals(true, state.isRefreshing)

            collectJob.cancel()
        }
```

This relies on the class-level `workoutsFlow`/`summariesFlow` defaults (`emptyList()`) and
`dailySummaryRepository.observeLatest()` returning `flowOf(null)` — both already set up in
`setUp()`, unmodified by this test.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :feature:workouts:testDebugUnitTest`
Expected: all tests PASS.

---

## Task 5: F1 — final verification and commit

**Files:** none (verification + commit only).

- [ ] **Step 1: Run the full pre-commit gate**

```bash
./gradlew ktlintFormat
./gradlew testDebugUnitTest
```

Expected: PASS across all modules.

- [ ] **Step 2: Manual on-device verification (device confirmed available)**

1. Fresh install (or clear app data) → open each of Vitals/Sleep/Workouts before any sync
   completes → confirm skeletons are visible (true first-load path still works).
2. Let initial sync complete, data appears. Pull-to-refresh (or background/foreground the app to
   trigger a resume sync) on each tab → open Android Studio's Layout Inspector, watch
   recomposition counts on the `TrendChart`/chart-hosting composables during the sync → expect
   **zero** recompositions attributable to `isLoading` (charts must not flash/rebuild).
3. On Vitals and Workouts, confirm the date-switcher arrows are disabled for the sync's full
   duration (not just at first load).

- [ ] **Step 3: Commit**

```bash
git add feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsViewModel.kt \
        feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsScreen.kt \
        feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/overview/VitalsViewModelTest.kt \
        feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepViewModel.kt \
        feature/sleep/src/test/kotlin/app/readylytics/health/feature/sleep/SleepViewModelTest.kt \
        feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModel.kt \
        feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsScreen.kt \
        feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModelTest.kt
git commit -m "Stop routine sync from skeleton-flashing Vitals/Sleep/Workouts charts (F1)"
```

---

## Task 6: F5 — `VitalsChartInputs` slice type

**Files:**
- Modify: `feature/vitals/.../overview/VitalsStateFactory.kt`

**Interfaces:**
- Produces: `VitalsChartInputs` data class and `VitalsUiState.chartInputs(): VitalsChartInputs`
  extension — consumed by Task 8 (`VitalsTrendSection`) and Task 9 (`VitalsScreen`).

- [ ] **Step 1: Add the `TimeRange` import**

At the top of `VitalsStateFactory.kt`, add:

```kotlin
import app.readylytics.health.core.ui.common.TimeRange
```

- [ ] **Step 2: Add `VitalsChartInputs` and the extractor**

Insert this block after the existing `VitalsPresentationState` class (before
`internal fun buildVitalsChartSeries`):

```kotlin
/**
 * The subset of [VitalsUiState] the three trend charts read. Passing only this into
 * [VitalsTrendSection] means gauge-only or refresh-only state changes never recompose the chart
 * subtree — mirrors [app.readylytics.health.feature.dashboard.DashboardUiState.cardInputs].
 */
@Immutable
data class VitalsChartInputs(
    val chartSeries: VitalsChartSeries,
    val rangeStartMs: Long,
    val selectedRange: TimeRange,
    val presentation: VitalsPresentationState,
    val isCalibrating: Boolean,
    val isLoading: Boolean,
)

fun VitalsUiState.chartInputs(): VitalsChartInputs =
    VitalsChartInputs(
        chartSeries = chartSeries,
        rangeStartMs = rangeStartMs,
        selectedRange = selectedRange,
        presentation = presentation,
        isCalibrating = latestSummary?.isCalibrating ?: false,
        isLoading = isLoading,
    )
```

- [ ] **Step 3: Compile-check**

Run: `./gradlew :feature:vitals:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (note: `VitalsScreen.kt` still references the old gauge/chart code
inline at this point — that's fine, it compiles unchanged until Task 9 rewires it).

---

## Task 7: F5 — Extract `VitalsGaugeRow.kt`

**Files:**
- Create: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsGaugeRow.kt`

**Interfaces:**
- Consumes: `VitalsPresentationState` (existing, from `VitalsStateFactory.kt`), `DailySummary`
  (existing, `core/model`).
- Produces: `@Composable internal fun VitalsGaugeRow(isLoading, latestSummary, presentation,
  onNavigateToHrv, onNavigateToRhr, modifier)` — consumed by Task 9 (`VitalsScreen`).

- [ ] **Step 1: Create the file**

```kotlin
package app.readylytics.health.feature.vitals.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.common.CardLoader
import app.readylytics.health.core.ui.common.ScoreDialSkeleton
import app.readylytics.health.core.ui.components.M3ScoreGaugeCard
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.hrvStatus
import app.readylytics.health.domain.model.rhrStatus
import kotlin.math.abs
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR

private const val RHR_DIAL_FLOOR = 30
private const val RHR_BASELINE_FILL = 0.5f

/**
 * The RHR/HRV gauge row on the Vitals screen. Takes only gauge-relevant fields (never the raw
 * [VitalsUiState] or [VitalsChartInputs]) so chart-only state changes never recompose it.
 */
@Composable
internal fun VitalsGaugeRow(
    isLoading: Boolean,
    latestSummary: DailySummary?,
    presentation: VitalsPresentationState,
    onNavigateToHrv: () -> Unit,
    onNavigateToRhr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CardLoader(
        isLoading = isLoading,
        skeleton = {
            Row(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = MaterialTheme.spacing.pageHorizontal,
                            vertical = MaterialTheme.spacing.small,
                        ),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScoreDialSkeleton(modifier = Modifier.weight(1f))
                ScoreDialSkeleton(modifier = Modifier.weight(1f))
            }
        },
        content = {
            Row(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = MaterialTheme.spacing.pageHorizontal,
                            vertical = MaterialTheme.spacing.small,
                        ),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val baselineHrv = presentation.baselineHrv
                val baselineRhr = presentation.baselineRhr
                val currentRhr = latestSummary?.restingHeartRate
                val currentHrv = latestSummary?.nocturnalHrv

                val rhrFill =
                    if (baselineRhr != null && baselineRhr > RHR_DIAL_FLOOR && currentRhr != null) {
                        (
                            (currentRhr - RHR_DIAL_FLOOR).toFloat() /
                                (baselineRhr - RHR_DIAL_FLOOR) * RHR_BASELINE_FILL
                        ).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                val rhrStatus =
                    latestSummary?.rhrStatus(
                        optimalThreshold = presentation.rhrOptimalThreshold,
                        warningThreshold = presentation.rhrWarningThreshold,
                    ) ?: MetricStatus.CALIBRATING
                val rhrTooltip = stringResource(CoreUiR.string.tooltip_sleep_rhr)

                // stringResource calls hoisted to locals before `remember` -- composable calls are
                // not allowed inside a remember lambda.
                val deltaUpText = stringResource(CoreUiR.string.delta_up)
                val deltaDownText = stringResource(CoreUiR.string.delta_down)
                val deltaNoChangeText = stringResource(CoreUiR.string.delta_no_change)
                val bpmUnit = stringResource(CoreUiR.string.unit_bpm)
                val msUnit = stringResource(CoreUiR.string.unit_ms)

                val rhrDelta =
                    remember(currentRhr, baselineRhr, deltaUpText, deltaDownText, deltaNoChangeText, bpmUnit) {
                        if (currentRhr != null && baselineRhr != null) {
                            val diff = currentRhr - baselineRhr
                            when {
                                diff > 0 -> "$deltaUpText $diff $bpmUnit"
                                diff < 0 -> "$deltaDownText ${abs(diff)} $bpmUnit"
                                else -> deltaNoChangeText
                            }
                        } else {
                            null
                        }
                    }

                M3ScoreGaugeCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(CoreUiR.string.label_rhr),
                    score = rhrFill,
                    displayText = currentRhr?.toString() ?: "—",
                    unitText = bpmUnit,
                    maxScore = 1f,
                    status = rhrStatus,
                    deltaText = rhrDelta,
                    tooltipDescription = rhrTooltip,
                    onClick = onNavigateToRhr,
                )

                val hrvMax = if (baselineHrv != null && baselineHrv > 0f) baselineHrv * 2.0f else 150f
                val hrvStatus =
                    latestSummary?.hrvStatus(
                        optimalThreshold = presentation.hrvOptimalThreshold,
                        warningThreshold = presentation.hrvWarningThreshold,
                    ) ?: MetricStatus.CALIBRATING
                val hrvTooltip = stringResource(CoreUiR.string.tooltip_sleep_hrv)

                val hrvDelta =
                    remember(currentHrv, baselineHrv, deltaUpText, deltaDownText, deltaNoChangeText, msUnit) {
                        if (currentHrv != null && baselineHrv != null) {
                            val diff = (currentHrv - baselineHrv).roundToInt()
                            when {
                                diff > 0 -> "$deltaUpText $diff $msUnit"
                                diff < 0 -> "$deltaDownText ${abs(diff)} $msUnit"
                                else -> deltaNoChangeText
                            }
                        } else {
                            null
                        }
                    }

                M3ScoreGaugeCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(CoreUiR.string.label_hrv),
                    score = currentHrv?.toFloat(),
                    displayText = currentHrv?.toString() ?: "—",
                    unitText = msUnit,
                    maxScore = hrvMax,
                    status = hrvStatus,
                    deltaText = hrvDelta,
                    tooltipDescription = hrvTooltip,
                    onClick = onNavigateToHrv,
                )
            }
        },
    )
}
```

- [ ] **Step 2: Index the new file**

Run: `codegraph index`

- [ ] **Step 3: Compile-check**

Run: `./gradlew :feature:vitals:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (`VitalsGaugeRow` is unused until Task 9, which is fine — Kotlin
doesn't error on unused top-level composables).

---

## Task 8: F5 — Extract `VitalsTrendSection.kt`

**Files:**
- Create: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsTrendSection.kt`

**Interfaces:**
- Consumes: `VitalsChartInputs` (Task 6), `VicoScrollState`/`VicoZoomState` (existing, Vico
  library).
- Produces: `@Composable internal fun VitalsTrendSection(chartInputs, chartScrollState,
  chartZoomState, parentScrollInProgress, modifier)` — consumed by Task 9 (`VitalsScreen`).

- [ ] **Step 1: Create the file**

```kotlin
package app.readylytics.health.feature.vitals.overview

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.common.CardLoader
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.components.TrendCard
import app.readylytics.health.core.ui.components.TrendChart
import app.readylytics.health.feature.vitals.R
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import app.readylytics.health.core.ui.R as CoreUiR

/**
 * The three Vico trend charts (HRV, RHR, SpO2) on the Vitals screen. Takes only [VitalsChartInputs]
 * (never the raw [VitalsUiState]) so gauge-only or refresh-only state changes never recompose the
 * chart subtree -- this is the guarantee F1/F5 exist to provide.
 */
@Composable
internal fun VitalsTrendSection(
    chartInputs: VitalsChartInputs,
    chartScrollState: VicoScrollState,
    chartZoomState: VicoZoomState,
    parentScrollInProgress: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val chartSeries = chartInputs.chartSeries
    val presentation = chartInputs.presentation

    // Chart 1: HRV Trend
    CardLoader(
        isLoading = chartInputs.isLoading,
        skeleton = {
            SkeletonCard(
                modifier = modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                height = 250.dp,
            )
        },
        content = {
            TrendCard(
                title = stringResource(R.string.label_hrv_rmssd),
                modifier =
                    modifier
                        .padding(horizontal = MaterialTheme.spacing.pageHorizontal)
                        .graphicsLayer { },
            ) {
                TrendChart(
                    points = chartSeries.hrv,
                    rangeStartMs = chartInputs.rangeStartMs,
                    rangeDays = chartInputs.selectedRange.days,
                    metricName = stringResource(CoreUiR.string.label_hrv),
                    baselineUnit = stringResource(CoreUiR.string.unit_ms),
                    modifier = Modifier.testTag("HrvTrendChart"),
                    baseline = presentation.baselineHrv,
                    showBaseline = !chartInputs.isCalibrating,
                    scrollState = chartScrollState,
                    zoomState = chartZoomState,
                    zoneBands = presentation.hrvZoneBands,
                    parentScrollInProgress = parentScrollInProgress,
                )
            }
        },
    )

    Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

    // Chart 2: Resting HR Trend
    CardLoader(
        isLoading = chartInputs.isLoading,
        skeleton = {
            SkeletonCard(
                modifier = modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                height = 250.dp,
            )
        },
        content = {
            TrendCard(
                title = stringResource(R.string.label_resting_heart_rate),
                modifier =
                    modifier
                        .padding(horizontal = MaterialTheme.spacing.pageHorizontal)
                        .graphicsLayer { },
            ) {
                TrendChart(
                    points = chartSeries.rhr,
                    rangeStartMs = chartInputs.rangeStartMs,
                    rangeDays = chartInputs.selectedRange.days,
                    metricName = stringResource(CoreUiR.string.label_rhr),
                    baselineUnit = "bpm",
                    modifier = Modifier.testTag("RestingHeartRateTrendChart"),
                    baseline = presentation.baselineRhr?.toFloat(),
                    showBaseline = !chartInputs.isCalibrating,
                    scrollState = chartScrollState,
                    zoomState = chartZoomState,
                    zoneBands = presentation.rhrZoneBands,
                    parentScrollInProgress = parentScrollInProgress,
                )
            }
        },
    )

    Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

    // Chart 3: SpO2 Trend
    CardLoader(
        isLoading = chartInputs.isLoading,
        skeleton = {
            SkeletonCard(
                modifier = modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                height = 250.dp,
            )
        },
        content = {
            TrendCard(
                title = stringResource(R.string.label_oxygen_saturation),
                modifier =
                    modifier
                        .padding(horizontal = MaterialTheme.spacing.pageHorizontal)
                        .graphicsLayer { },
            ) {
                TrendChart(
                    points = chartSeries.spo2,
                    rangeStartMs = chartInputs.rangeStartMs,
                    rangeDays = chartInputs.selectedRange.days,
                    metricName = stringResource(CoreUiR.string.label_spo2),
                    baselineUnit = "%",
                    modifier = Modifier.testTag("OxygenSaturationTrendChart"),
                    baseline = 95f,
                    baselineLabel = stringResource(CoreUiR.string.label_normal_limit),
                    showBaseline = true,
                    scrollState = chartScrollState,
                    zoomState = chartZoomState,
                    zoneBands = presentation.spo2ZoneBands,
                    axisDecimalPlaces = 0,
                    baselineDecimalPlaces = 0,
                    minYOverride = 90.0,
                    maxYOverride = 100.0,
                    parentScrollInProgress = parentScrollInProgress,
                )
            }
        },
    )
}
```

Note: `baselineUnit = "bpm"` and `"%"` are moved verbatim from the original inline code (a known,
separately-tracked cleanup item, N2 in the plan — not in scope here).

- [ ] **Step 2: Index the new file**

Run: `codegraph index`

- [ ] **Step 3: Compile-check**

Run: `./gradlew :feature:vitals:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

---

## Task 9: F5 — Rewrite `VitalsScreen.kt` as a composition root

**Files:**
- Modify: `feature/vitals/.../overview/VitalsScreen.kt`

**Interfaces:**
- Consumes: `VitalsGaugeRow` (Task 7), `VitalsTrendSection` (Task 8), `VitalsUiState.chartInputs()`
  (Task 6).

- [ ] **Step 1: Replace the full file content**

```kotlin
package app.readylytics.health.feature.vitals.overview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.common.ScreenHeaderSection
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.core.ui.components.StatusLegend
import app.readylytics.health.core.ui.dashboard.DateSwitcher
import app.readylytics.health.feature.vitals.R

@Composable
fun VitalsRoute(
    onNavigateToHrv: () -> Unit,
    onNavigateToRhr: () -> Unit,
    viewModel: VitalsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val earliestDate by viewModel.earliestDate.collectAsStateWithLifecycle()

    VitalsScreen(
        uiState = uiState,
        onRangeSelected = viewModel::onRangeSelected,
        onPreviousDay = viewModel::onPreviousDay,
        onNextDay = viewModel::onNextDay,
        onDateSelected = viewModel::onDateSelected,
        earliestDate = earliestDate,
        onNavigateToHrv = onNavigateToHrv,
        onNavigateToRhr = onNavigateToRhr,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VitalsScreen(
    uiState: VitalsUiState,
    onRangeSelected: (TimeRange) -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onNavigateToHrv: () -> Unit,
    onNavigateToRhr: () -> Unit,
    modifier: Modifier = Modifier,
    onDateSelected: (java.time.LocalDate) -> Unit = {},
    earliestDate: java.time.LocalDate? = null,
) {
    // Single shared scroll + zoom state so all three trend charts stay in sync.
    // Keyed on selectedRange so state resets when the user switches time ranges.
    val (chartScrollState, chartZoomState) =
        ChartDefaults.rememberChartState(
            rangeDays = uiState.selectedRange.days,
            key = "vitals-${uiState.selectedRange}",
        )
    val scrollState = rememberScrollState()

    Column(modifier = modifier.fillMaxSize()) {
        // isRefreshing (not isLoading) gates the date-switcher: date navigation stays disabled for
        // the full sync duration, not just on true first-load (F1).
        ScreenHeaderSection(isLoading = uiState.isRefreshing) { isDisabled ->
            DateSwitcher(
                selectedDate = uiState.selectedDate,
                onPreviousDay = onPreviousDay,
                onNextDay = onNextDay,
                onDateSelected = onDateSelected,
                earliestDate = earliestDate,
                enabled = !isDisabled,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.pageHorizontal)
                        .padding(top = MaterialTheme.spacing.pageTop),
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(
                        top = MaterialTheme.spacing.pageSectionGapSmall,
                        bottom = MaterialTheme.spacing.pageBottom,
                    ),
        ) {
            VitalsGaugeRow(
                isLoading = uiState.isLoading,
                latestSummary = uiState.latestSummary,
                presentation = uiState.presentation,
                onNavigateToHrv = onNavigateToHrv,
                onNavigateToRhr = onNavigateToRhr,
            )

            // Time Range selection
            SectionHeader(
                title = stringResource(R.string.label_physiological_trends),
                enabled = !uiState.isLoading,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
            SingleChoiceSegmentedButtonRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
            ) {
                TimeRange.entries.forEachIndexed { index, range ->
                    SegmentedButton(
                        selected = uiState.selectedRange == range,
                        onClick = { onRangeSelected(range) },
                        enabled = !uiState.isLoading,
                        shape =
                            SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = TimeRange.entries.size,
                            ),
                        label = { Text(range.label) },
                    )
                }
            }

            Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

            VitalsTrendSection(
                chartInputs = uiState.chartInputs(),
                chartScrollState = chartScrollState,
                chartZoomState = chartZoomState,
                parentScrollInProgress = { scrollState.isScrollInProgress },
            )

            Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))

            StatusLegend()
        }
    }
}
```

- [ ] **Step 2: Compile-check**

Run: `./gradlew :feature:vitals:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

---

## Task 10: F5 — Verification and commit

**Files:** none (verification + commit only).

- [ ] **Step 1: Run the unit test suite**

Run: `./gradlew :feature:vitals:testDebugUnitTest`
Expected: PASS, unchanged (F5 is a pure UI refactor — no ViewModel logic changed).

- [ ] **Step 2: Generate and inspect the compose compiler report**

```bash
./gradlew :feature:vitals:assembleRelease -PenableComposeReports
grep -A2 "VitalsChartInputs" feature/vitals/build/compose-metrics/*-classes.txt
grep "VitalsTrendSection\|VitalsGaugeRow" feature/vitals/build/compose-metrics/*-composables.txt
```

Expected: `VitalsChartInputs` reported `stable`; `VitalsTrendSection` and `VitalsGaugeRow` reported
`restartable skippable`.

- [ ] **Step 3: Manual on-device verification**

1. Open Vitals, confirm all three charts render identically to before (HRV/RHR/SpO2, same data,
   same zone bands, same baseline lines).
2. In Layout Inspector, trigger a gauge-only change (e.g. wait for a new summary to land without
   changing chart data) and confirm `VitalsTrendSection`/the three `TrendChart`s show **zero**
   recompositions. Trigger a chart-only change (switch time range) and confirm `VitalsGaugeRow`
   shows zero recompositions.
3. Scroll down and back up on Vitals — confirm no chart is visibly recreated (charts-stay-composed
   guarantee).

- [ ] **Step 4: Index and sync codegraph**

```bash
codegraph index
codegraph sync
```

- [ ] **Step 5: Run the full batch-final lint pass**

```bash
./gradlew ktlintFormat
./gradlew lintRelease
```

Expected: PASS. This is the one `lintRelease` run for the whole F8→F1→F5 batch (per repo rule),
run here since F5 is the last item.

- [ ] **Step 6: Commit**

```bash
git add feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsStateFactory.kt \
        feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsGaugeRow.kt \
        feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsTrendSection.kt \
        feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsScreen.kt
git commit -m "Isolate Vitals screen recomposition into gauge row + trend section (F5)"
```

---

## Self-review notes (for the plan author, not a task)

- **Type consistency:** `VitalsChartInputs` (Task 6) includes `isLoading` — this is a deliberate
  refinement over the design doc's field list (which omitted it), needed because
  `VitalsTrendSection`'s `CardLoader` calls need an `isLoading` source and the design's stated
  goal is "reading only `chartInputs`, never the raw `VitalsUiState`." Mirrors
  `DashboardCardInputs.isComputingMetrics`.
- **Predicate correction carried over from the design doc:** Sleep/Workouts "no data yet" checks
  use `latestSummary == null` (plus `latestSession == null` / `recentWorkouts.isEmpty()`), not the
  plan's originally-suggested "trend/dailyTrimp list empty" — those lists are always padded to a
  fixed length and never actually empty.
- **Existing test breakage found and fixed in-plan:** `WorkoutsViewModelTest`'s
  `isSyncing toggle does not restart the heavy pipeline` asserted the old `isLoading == true`
  during sync with data present; Task 4 Step 3 corrects this assertion as part of the same task
  that changes the production behavior.
