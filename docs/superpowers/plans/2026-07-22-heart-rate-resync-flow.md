# Heart-rate resync flow implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep heart-rate detail state updating at a bounded cadence throughout sustained Room invalidations.

**Architecture:** Replace the quiet-period-based `debounce` at the ViewModel's Room-flow boundary with periodic `sample`. Preserve all existing mapping, dispatcher, and StateFlow behavior while changing only the emission policy.

**Tech Stack:** Kotlin, kotlinx.coroutines Flow and test scheduler, JUnit 4, MockK, AndroidX ViewModel.

## Global Constraints

- Room remains the single source of truth; the UI must not access Health Connect.
- Keep business/calculation logic pure Kotlin with zero Android dependencies.
- Do not change ingestion, Room schema, scoring formulas, or resync progress behavior.
- Run `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`, then `./gradlew lintRelease`.

---

### Task 1: Prevent heart-rate flow starvation

**Files:**
- Modify: `feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/heartrate/HeartRateDetailViewModelTest.kt`
- Modify: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/heartrate/HeartRateDetailViewModel.kt`

**Interfaces:**
- Consumes: `HeartRateRepository.observeByTimeRange(startMs: Long, endMs: Long): Flow<List<HeartRateRecordData>>`
- Produces: existing `HeartRateDetailViewModel.uiState: StateFlow<HeartRateDetailUiState>` with non-starving, 500 ms sampled updates.

- [ ] **Step 1: Add the sustained-invalidation regression test**

Add a test that starts collecting `uiState`, emits a new non-empty repository result every 100 ms for 1.5 seconds, and asserts before the source becomes quiet that `uiState` is no longer loading and at least two non-empty sampled states were observed.

Add imports for `runCurrent`, `assertFalse`, and `assertTrue`.

```kotlin
@Test
fun `sustained invalidations continue updating state before the source becomes quiet`() =
    runTest {
        val updates = MutableSharedFlow<List<HeartRateRecordData>>(replay = 1)
        updates.tryEmit(emptyList())
        every { heartRateRepository.observeByTimeRange(any(), any()) } returns updates
        viewModel = createViewModel()

        val collected = mutableListOf<HeartRateDetailUiState>()
        val job = launch { viewModel.uiState.collect { collected += it } }
        runCurrent()

        repeat(15) { index ->
            updates.emit(
                listOf(
                    HeartRateRecordData(
                        id = "update$index",
                        timestampMs = index * 1_000L,
                        beatsPerMinute = 100,
                        recordType = "instant",
                    ),
                ),
            )
            advanceTimeBy(100)
            runCurrent()
        }

        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(collected.count { it.samples.isNotEmpty() } >= 2)
        job.cancel()
    }
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew :feature:vitals:testDebugUnitTest --tests '*HeartRateDetailViewModelTest.sustained invalidations continue updating state before the source becomes quiet'
```

Expected: FAIL because `debounce(500)` emits no non-empty state while updates arrive every 100 ms.

- [ ] **Step 3: Implement the minimal flow change**

In `HeartRateDetailViewModel.kt`, replace the `debounce` import and operator with `sample`, and update the nearby comment to describe periodic latest-value rendering during sustained invalidations.

```kotlin
import kotlinx.coroutines.flow.sample

heartRateRepository.observeByTimeRange(startMs, endMs).sample(500).map { entities ->
```

- [ ] **Step 4: Align the existing burst test with sampling semantics**

Rename `rapid successive invalidations collapse into a single re-render via debounce` to `rapid successive invalidations render only the latest sampled value` and update its comments from debounce/quiet-window language to the 500 ms sampling cadence. Emit four updates 100 ms apart so they remain inside one sampling period, advance another 200 ms, and assert that exactly one downstream state is added containing the fourth batch at `3_000L`.

- [ ] **Step 5: Run the focused ViewModel test class and verify GREEN**

Run:

```bash
./gradlew :feature:vitals:testDebugUnitTest --tests '*HeartRateDetailViewModelTest'
```

Expected: BUILD SUCCESSFUL with all `HeartRateDetailViewModelTest` tests passing.

- [ ] **Step 6: Run repository verification**

Run:

```bash
./gradlew ktlintFormat
./gradlew testDebugUnitTest
./gradlew lintRelease
git diff --check
```

Expected: every command exits 0 with no failing test, lint error, or whitespace error.

- [ ] **Step 7: Commit the fix**

```bash
git add feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/heartrate/HeartRateDetailViewModel.kt feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/heartrate/HeartRateDetailViewModelTest.kt
git commit -m "fix: prevent heart-rate resync starvation"
```
