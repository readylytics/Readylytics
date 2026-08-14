# Phase 4 Design: UX Resilience & Dead Code Cleanup

**Date:** 2026-08-14
**Status:** Approved
**Topic:** Phase 4 (Steps 9–11) of Heavy Data Sync Stability Plan (`internal-docs/plans/HEAVY_DATA_SYNC_STABILITY_PLAN.md`)

---

## 1. Overview & Objectives

Phase 4 completes the remaining UX resilience and cleanup tasks identified in the sync stability plan:
1. **Dead Code Cleanup (Steps 9–10):** Delete `DashboardLoadingState.kt` and remove all residual documentation references.
2. **State Machine & UX Verification (Step 11 — Option E):** Formalize and test the dashboard UI state contract across all permutations of sync state (`isSyncing`) and summary availability (`summary == null` vs `summary != null`).

---

## 2. Dead Code Removal (Steps 9 & 10)

- **Audit Findings:** The sealed interface `DashboardLoadingState` (`Idle`, `SyncingMetrics`, `MetricsReady`, `Error`) and its helper functions `shouldShowSkeleton()` and `isBusy()` in `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardLoadingState.kt` are completely unused by production code and unit tests.
- **Action:**
  - Delete `DashboardLoadingState.kt`.
  - Remove references in `internal-docs/DATA_FLOW.md`.
  - Update `internal-docs/plans/HEAVY_DATA_SYNC_STABILITY_PLAN.md` to record completion of Steps 9 and 10.

---

## 3. Dashboard State Machine Contract (Option E)

The dashboard state is derived by combining persisted core state (`summary`, card configs, baselines) with realtime sync state (`isSyncing`, `recalcProgress`).

```
                              ┌────────────────────────────────────────┐
                              │     ForegroundSyncController / Flow    │
                              └───────────────────┬────────────────────┘
                                                  │ (isSyncing, recalcProgress)
                                                  ▼
┌─────────────────────────┐   ┌────────────────────────────────────────┐   ┌──────────────────────────┐
│   DailySummary / Core   ├──►│         DashboardViewModel             ├──►│     DashboardUiState     │
│   (summary, cards, etc) │   │ (isComputingMetrics = isSyncing &&     │   │ (summary, isLoading,     │
└─────────────────────────┘   │                       summary == null) │   │  recalcProgress, etc)    │
                              └────────────────────────────────────────┘   └─────────────┬────────────┘
                                                                                         │
                                                                                         ▼
                                                                           ┌──────────────────────────┐
                                                                           │     DashboardScreen      │
                                                                           └──────────────────────────┘
```

### State Matrix:

| Scenario | `summary` | `isSyncing` | `isComputingMetrics` (`isLoading`) | UI Behavior |
| :--- | :--- | :--- | :--- | :--- |
| **1. First Launch / Empty Date while Syncing** | `null` | `true` | **`true`** | **Skeleton Card Grid:** All metric cards render shimmering skeletons (`ScoreDialSkeleton`, `MetricCardSkeleton`).<br>**Top Progress Banner:** `MainScaffold` renders the live `RecalcProgress` banner (`INGEST` $\to$ `RECONCILE` $\to$ `RECOMPUTE`). |
| **2. Returning User / Cached Date while Syncing** | Non-null | `true` | **`false`** | **Progressive Availability:** Cached metric cards render immediately and remain interactive.<br>**Top Progress Banner:** `MainScaffold` shows background sync progress without flashing skeletons or blocking the user. |
| **3. Empty Historical Date (Idle)** | `null` | `false` | **`false`** | **Empty State:** Centered "No data available for this date" placeholder (`dashboard_no_data`). |
| **4. Empty Current Date (Idle)** | `null` | `false` | **`false`** | **Default Cards:** Metric grid renders cards with empty/dash (`--`) values. |

---

## 4. Testing & Verification

1. **Unit Tests (`feature/dashboard/.../DashboardFlowIntermediateTest.kt` or `DashboardViewModelTest.kt`):**
   - Verify `isComputingMetrics` is `true` when `isSyncing = true && summary == null`.
   - Verify `isComputingMetrics` is `false` when `isSyncing = true && summary != null` (progressive availability).
   - Verify `isComputingMetrics` is `false` when `isSyncing = false && summary == null`.
   - Verify `recalcProgress` updates propagate cleanly to `DashboardUiState`.
2. **Quality & Formatting Checks:**
   - `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`
   - `./gradlew lintRelease`

---

## 5. Documentation Updates

- Update `internal-docs/plans/HEAVY_DATA_SYNC_STABILITY_PLAN.md` to reflect Phase 4 completion (Steps 9, 10, 11).
- Update `internal-docs/DATA_FLOW.md` to clean up any obsolete references to `DashboardLoadingState`.
