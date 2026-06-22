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
