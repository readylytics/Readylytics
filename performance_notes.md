# Performance Notes — Scoring-Engine-Adjacent Findings (Document Only)

These items were identified during the performance audit but sit **inside or directly
call the scoring engine** (HRV / RHR / TRIMP / CTL / ATL / recovery scoring). Per the
project guardrail they are **documented, not changed**. Each entry records the cost, a
deferred fix, and why it is off-limits.

> Guardrail: do not modify, refactor, or touch code/files/packages related to the
> scoring engine or health-metric calculations (HRV, RHR nocturnal nadir, TRIMP,
> recovery scoring models/formulas, or their direct data structures).

---

## 1. N+1 heart-rate fetch per workout during daily-summary compute

- **Location:** `app/src/main/kotlin/app/readylytics/health/data/repository/ScoringRepositoryImpl.kt` (~lines 136–173)
- **What:** `workouts.forEach { ... heartRateDao.getByTimeRange(workout.startTime, workout.endTime) ... }` issues one HR-sample DB query **per workout** in the day, then filters/sorts each result in memory.
- **Cost:** On multi-workout days this becomes N separate DB round-trips (5–10 queries) where one would do. It runs on every `computeDailySummary`, i.e. after every sync/recompute.
- **Deferred fix:** Fetch all HR samples for the day once (`getByTimeRange(dayStart, dayEnd)`), group in memory by workout window, eliminate the per-workout query. Pure data-flow/batching change — does **not** alter TRIMP math.
- **Why untouched:** This is the TRIMP ingestion path inside `ScoringRepositoryImpl`; the user selected the strict "document only" policy for anything inside/calling the scoring engine.

## 2. Redundant `userPreferences.first()` reads per scoring pass

- **Location:** `ScoringRepositoryImpl.kt` lines ~77, ~92, ~490
- **What:** `settingsRepo.userPreferences.first()` is read 2–3 times within a single scoring computation path (`computeAndPersistDailySummary`, `computeDailySummary`, `persist`).
- **Cost:** Each `.first()` reads the full DataStore/Proto snapshot from disk; duplicated per compute, and compute can run per-date in a loop.
- **Deferred fix:** Read preferences once at the entry point and thread them through the internal function signatures.
- **Why untouched:** Same file/path as the scoring engine; off-limits under the strict policy.

## 3. Per-day CTL/ATL recompute inside the WorkoutsViewModel display loop

- **Location:** `app/src/main/kotlin/app/readylytics/health/ui/workouts/WorkoutsViewModel.kt` (~lines 176–292)
- **What:** Inside the per-visible-day loop, calls `scoringCalculator.computeCtlEmaWithDecay(trimpByDate, day)` and `computeAtlEmaWithDecay(...)` for **every** displayed day, plus builds duplicate `trimpByDay`/`trimpByDate` maps over the same data.
- **Cost:** The EMA walks historical TRIMP each call; doing it per-day is O(days × history). Re-runs whenever range/date/page/sync state changes.
- **Deferred fix (data-flow only):** Deduplicate the two TRIMP maps into one pass; compute the EMA series once across the range instead of per-day. The EMA/strain **formulas** stay untouched — only how often they are invoked.
- **Why untouched:** Directly invokes the scoring calculator (CTL/ATL/strain). Under the strict policy, scoring-calling orchestration is also documented rather than refactored. (Candidate for a future increment if the policy is relaxed.)

---

## Audit decisions worth recording (non-scoring)

- **Chart aggregation micro-allocations** (e.g. `AcwrChart.kt` `filter/map/mapNotNull`
  triple passes, `BloodPressureTrendChart.kt` list concatenation): left as-is. They run
  inside `remember`/`LaunchedEffect` over ≤90-element lists and only on data change, not
  during composition — negligible gain, and rewriting Vico transaction code adds risk.
- **`ZoneBand` (`domain/model/HealthZone.kt`)** was *not* annotated `@Immutable`:
  it is pure-Kotlin domain (zero-Android-deps rule) and its fields are already
  stable primitives the Compose compiler auto-infers as stable.

## Increment 2 — flow dedup: what was (and was NOT) done

The original increment-2 idea was "add `distinctUntilChanged()` to combine inputs."
Investigation showed most of that would be **dead code** in this codebase:

- **Every Room DAO `observe*` flow already calls `.distinctUntilChanged()`**
  (DailySummaryDao, HeartRateDao, HrvDao, SleepSessionDao, Weight/BodyFat/BP/SpO2,
  Workout, InsightDismissal). So DB-backed flows only emit on genuine data changes.
- **All detail-screen ViewModels** (`Step`, `HeartRate`, `Weight`, `BodyFat`,
  `Vitals`, `Sleep`) feed their `combine` from a `MutableStateFlow` range + the
  `selectedDate` `StateFlow`. `StateFlow` already dedupes by `equals`, so adding
  `distinctUntilChanged()` on those inputs is a no-op. `stateIn` likewise dedupes
  the downstream emission, so a trailing `distinctUntilChanged()` before `stateIn`
  is also redundant. **No changes made to these VMs** — adding the calls would be
  cargo-cult.

The one place a real redundant **recomputation** survived all of the above was the
**dashboard**: `createDashboardRealtimeStateFlow` (isSyncing / recalcProgress) was
part of the same `combine` as the expensive transform, so every sync-progress tick
during a historical resync re-ran `InsightEngine.evaluate` + `GetDashboardDataUseCase`
even though the underlying summary/cards/insights were unchanged. Fixed by splitting
`DashboardViewModel.uiState` into a heavy core flow (`basic`/`card`/`hr` →
`transformToUiState`, guarded by `distinctUntilChanged()`) and a cheap realtime
merge step that only `.copy()`s the three sync-dependent fields. Final `uiState`
behaviour is identical; the heavy work no longer runs on realtime ticks. Removed the
now-dead `DashboardCombinedInputs` / `combineDashboardInputs` helpers.

## Increment 3 — move detail-VM transforms off the main thread

The planned "extract heavy `groupBy/map/sort/pad` transforms onto `flowOn(io)`" turned
out to already be done in most detail VMs. A dispatcher audit:

| ViewModel | Already off-main? |
|-----------|-------------------|
| BloodPressure / Weight / BodyFat | yes — `withContext(ioDispatcher)` around the transform |
| Vitals / Sleep | yes — `.flowOn(Dispatchers.Default)` on the inner combine |
| **HeartRate** | **no** — fixed |
| **Step** | **no** — fixed |

Only two VMs actually ran their transform in the `stateIn` collector context
(`viewModelScope` → `Dispatchers.Main`):

- **`HeartRateDetailViewModel`** — the `.map { entities -> ... }` does per-sample
  `HrZoneClassifier.classify` plus `computeZoneTotals` (a second pass), over potentially
  hundreds of HR samples, on Main. Added `.flowOn(Dispatchers.Default)` before `stateIn`.
  `Dispatchers.Default` (CPU) is correct here — Room already runs the query on IO; this
  work is pure computation.
- **`StepDetailViewModel`** — lighter `filter/map/sort/padToRange` over ≤90 summaries,
  but still on Main. Added `.flowOn(Dispatchers.Default)` for correctness/consistency.

The other detail VMs were left unchanged: their transforms are already off-main and run
only on genuine input changes (range/date are `StateFlow`s; DB flows dedupe at the DAO),
so "extracting" them further would be churn with no benefit. The `VitalsViewModel` triple
`mapNotNull/sort/pad` repetition is a readability nit, not a perf bottleneck (O(n) on
Default), so it was not refactored.

## PR #101 review comments addressed (gemini-code-assist)

- **HIGH** (DashboardScreen `cardDataMap` remember keys): added `lastSleepSession`,
  `rasDailyBreakdown`, `isCalibrating`, `errorMessage`, `visibleInsightQueue` as keys.
  These are *not* actually read by `buildCardDataMap` today (verified), so the original
  keys were not stale — but they are added defensively against future card changes and
  cost nothing on the hot path (they never change on sync ticks).
- **MEDIUM** ×2 (`transformToUiState` redundant `selectedDate`): removed the parameter
  and derive `val selectedDate = basicInputs.selectedDate` inside the function.
- **HIGH** (follow-up: 23-key `remember` vararg allocates an `Any?[]` per recomposition):
  consolidated the keys into a single `@Immutable DashboardCardInputs` holder
  (`DashboardUiState.cardInputs()`), so the memo uses the single-key `remember` overload
  (one small object instead of a 23-element array) and is no longer a fragile key list.
  The bot's suggested 3-key set (`cardDataMap`, `isManagingCards`, `isComputingMetrics`)
  was **not** adopted because it is incorrect: `heartRateDaySummary`,
  `circadianConsistency` and the insight fields are read directly by the cards and are
  **not** part of the ViewModel-computed `cardDataMap` (not inputs to
  `getDashboardDataUseCase`), so keying on `cardDataMap` alone would leave HR/circadian/
  insight cards stale when only that data changes. Fields derivable from the holder's
  members (restingHrCard, stepCount, stepGoal, goalSleepHours) are omitted.
