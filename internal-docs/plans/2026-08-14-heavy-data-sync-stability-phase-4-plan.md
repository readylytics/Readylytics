# Phase 4 Implementation Plan: UX Resilience & Dead Code Cleanup

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clean up dead `DashboardLoadingState` code and add comprehensive unit test coverage for the dashboard's empty-date and syncing UI state contracts.

**Architecture:** Remove unreferenced `DashboardLoadingState.kt` and stale doc references. Add unit tests for `DashboardViewModel` and `DashboardFlowIntermediate` validating the three sync/loading matrix states (`summary == null && isSyncing`, `summary != null && isSyncing`, `summary == null && !isSyncing`) and progress propagation.

**Tech Stack:** Kotlin, Android/Compose M3, StateFlow/Flow coroutines, MockK, JUnit4, Room.

## Global Constraints

- Zero Android dependencies in domain/unit tests (except Robolectric/in-memory Room where required).
- All changes must strictly pass pre-commit checks: `./gradlew ktlintFormat && ./gradlew testDebugUnitTest && ./gradlew lintRelease`.
- Keep `internal-docs/DATA_FLOW.md` synchronized with codebase changes.

---

### Task 1: Dead Code Cleanup (`DashboardLoadingState.kt`)

**Files:**
- Delete: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardLoadingState.kt`
- Modify: `internal-docs/DATA_FLOW.md:788-793`

**Interfaces:**
- Consumes: None (unreferenced).
- Produces: Cleaned up codebase without unused loading state interface.

- [ ] **Step 1: Verify no active callers in production or test code**

Run: `git grep "DashboardLoadingState"`
Expected: Only in `DashboardLoadingState.kt`, `DATA_FLOW.md`, and plan docs.

- [ ] **Step 2: Delete `DashboardLoadingState.kt`**

Run: `rm feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardLoadingState.kt`

- [ ] **Step 3: Update `internal-docs/DATA_FLOW.md`**

In `internal-docs/DATA_FLOW.md`, update lines 788–793 to remove `DashboardLoadingState.kt` from the UI state wrappers table.

- [ ] **Step 4: Run unit tests to verify clean compilation**

Run: `./gradlew testDebugUnitTest`
Expected: PASS (all tests green, no compilation errors).

- [ ] **Step 5: Commit**

```bash
git add feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardLoadingState.kt internal-docs/DATA_FLOW.md
git commit -m "refactor(dashboard): remove dead DashboardLoadingState code"
```

---

### Task 2: Add Unit Tests for Dashboard State Machine & Sync Transitions

**Files:**
- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardFlowIntermediateTest.kt`
- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModelTest.kt`

**Interfaces:**
- Consumes: `createDashboardRealtimeStateFlow`, `DashboardViewModel.uiState`, `ForegroundSyncGateway.isSyncing`, `ForegroundSyncGateway.recalcProgress`.
- Produces: Tested guarantees for the dashboard loading/syncing state matrix.

- [ ] **Step 1: Add unit tests for `createDashboardRealtimeStateFlow` in `DashboardFlowIntermediateTest.kt`**

Add tests verifying:
- Flow combines `isSyncing` and `recalcProgress` into `DashboardRealtimeState`.
- State updates reactively when either input changes.

```kotlin
@Test
fun `createDashboardRealtimeStateFlow combines isSyncing and recalcProgress`() =
    runTest {
        val isSyncing = MutableStateFlow(false)
        val recalcProgress = MutableStateFlow<RecalcProgress?>(null)
        val gateway =
            mockk<ForegroundSyncGateway> {
                every { this@mockk.isSyncing } returns isSyncing
                every { this@mockk.recalcProgress } returns recalcProgress
            }

        val emissions = mutableListOf<DashboardRealtimeState>()
        val job =
            backgroundScope.launch {
                createDashboardRealtimeStateFlow(gateway).collect(emissions::add)
            }
        runCurrent()

        assertEquals(DashboardRealtimeState(isSyncing = false, recalcProgress = null), emissions.last())

        isSyncing.value = true
        recalcProgress.value = RecalcProgress(ResyncPhase.INGEST, current = 2, total = 0)
        runCurrent()

        assertEquals(
            DashboardRealtimeState(
                isSyncing = true,
                recalcProgress = RecalcProgress(ResyncPhase.INGEST, current = 2, total = 0),
            ),
            emissions.last(),
        )
        job.cancel()
    }
```

- [ ] **Step 2: Add unit tests for `DashboardViewModel.uiState` state matrix transitions in `DashboardViewModelTest.kt`**

Add tests covering:
1. `when syncing with null summary, isComputingMetrics is true (skeleton cards visible)`
2. `when syncing with non-null summary, isComputingMetrics is false (progressive availability / cached summary displayed)`
3. `when idle with null summary, isComputingMetrics is false (empty state)`
4. `recalcProgress updates through INGEST, RECONCILE, RECOMPUTE propagate cleanly into DashboardUiState`

```kotlin
@Test
fun `uiState sets isComputingMetrics to true when syncing and summary is null`() =
    runTest {
        val isSyncing = MutableStateFlow(true)
        val recalcProgress = MutableStateFlow<RecalcProgress?>(null)
        every { foregroundSyncController.isSyncing } returns isSyncing
        every { foregroundSyncController.recalcProgress } returns recalcProgress
        every { dailySummaryRepository.observeByDate(any()) } returns MutableStateFlow(null)

        val states = mutableListOf<DashboardUiState>()
        val job = backgroundScope.launch { viewModel.uiState.collect(states::add) }
        runCurrent()

        assertTrue(states.last().isComputingMetrics)
        assertTrue(states.last().isRefreshing)
        job.cancel()
    }

@Test
fun `uiState sets isComputingMetrics to false when syncing and cached summary exists`() =
    runTest {
        val isSyncing = MutableStateFlow(true)
        val recalcProgress = MutableStateFlow<RecalcProgress?>(null)
        val summary = mockk<DailySummary>(relaxed = true)
        every { foregroundSyncController.isSyncing } returns isSyncing
        every { foregroundSyncController.recalcProgress } returns recalcProgress
        every { dailySummaryRepository.observeByDate(any()) } returns MutableStateFlow(summary)

        val states = mutableListOf<DashboardUiState>()
        val job = backgroundScope.launch { viewModel.uiState.collect(states::add) }
        runCurrent()

        assertFalse(states.last().isComputingMetrics)
        assertTrue(states.last().isRefreshing)
        job.cancel()
    }

@Test
fun `uiState sets isComputingMetrics to false when not syncing and summary is null`() =
    runTest {
        val isSyncing = MutableStateFlow(false)
        val recalcProgress = MutableStateFlow<RecalcProgress?>(null)
        every { foregroundSyncController.isSyncing } returns isSyncing
        every { foregroundSyncController.recalcProgress } returns recalcProgress
        every { dailySummaryRepository.observeByDate(any()) } returns MutableStateFlow(null)

        val states = mutableListOf<DashboardUiState>()
        val job = backgroundScope.launch { viewModel.uiState.collect(states::add) }
        runCurrent()

        assertFalse(states.last().isComputingMetrics)
        assertFalse(states.last().isRefreshing)
        job.cancel()
    }

@Test
fun `uiState propagates recalcProgress updates cleanly from sync gateway`() =
    runTest {
        val isSyncing = MutableStateFlow(true)
        val recalcProgress = MutableStateFlow<RecalcProgress?>(null)
        every { foregroundSyncController.isSyncing } returns isSyncing
        every { foregroundSyncController.recalcProgress } returns recalcProgress

        val states = mutableListOf<DashboardUiState>()
        val job = backgroundScope.launch { viewModel.uiState.collect(states::add) }
        runCurrent()

        val progress = RecalcProgress(ResyncPhase.RECONCILE, current = 0, total = 0)
        recalcProgress.value = progress
        runCurrent()

        assertEquals(progress, states.last().recalcProgress)
        job.cancel()
    }
```

- [ ] **Step 3: Run the dashboard unit tests**

Run: `./gradlew testDebugUnitTest --tests "app.readylytics.health.feature.dashboard.*"`
Expected: All tests PASS.

- [ ] **Step 4: Commit**

```bash
git add feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardFlowIntermediateTest.kt feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModelTest.kt
git commit -m "test(dashboard): add unit tests for sync state matrix and progress propagation"
```

---

### Task 3: Update Sync Stability Plan Documentation & Verification

**Files:**
- Modify: `internal-docs/plans/HEAVY_DATA_SYNC_STABILITY_PLAN.md`

**Interfaces:**
- Record completion of Phase 4 (Steps 9, 10, 11).

- [ ] **Step 1: Update status and results in `HEAVY_DATA_SYNC_STABILITY_PLAN.md`**

Update:
- Line 3 status to: `**Status:** IN PROGRESS — Phase 4 (steps 9–11) complete; Phase 5 (steps 12–14) not started.`
- Section 5.1 (Phase 4): document results of Step 9, Step 10, and Step 11.

- [ ] **Step 2: Run full verification suite**

Run: `./gradlew ktlintFormat && ./gradlew testDebugUnitTest && ./gradlew lintRelease`
Expected: Everything PASS with 0 lint errors.

- [ ] **Step 3: Commit**

```bash
git add internal-docs/plans/HEAVY_DATA_SYNC_STABILITY_PLAN.md
git commit -m "docs: record Phase 4 completion in sync stability plan"
```
