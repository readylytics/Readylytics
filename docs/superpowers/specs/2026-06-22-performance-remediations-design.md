# Performance Remediations Design

## Overview
This document specifies the design for three performance remediations initially identified in `performance_notes.md`. These changes optimize the data flow and batching around the scoring engine without altering any of the underlying scoring formulas or mathematical logic.

## 1. N+1 Heart-Rate Fetch Optimization

**Context:** During daily summary computation, the system currently fetches heart-rate samples individually for each workout. On multi-workout days, this results in N separate database queries.

**Design:**
- **Location:** `ScoringRepositoryImpl.kt` (`computeDailySummary` method).
- **Change:** 
  1. Perform a single database query to fetch all exercise heart-rate samples for the entire day (`dayMidnightMs` to `nextDayMidnightMs`).
  2. Filter and sort these samples in memory once.
  3. Inside the workout iteration loop, derive `workoutHrSamples` by filtering the pre-fetched daily list to the specific workout's time bounds (`startTime` to `endTime`).
- **Benefit:** Reduces database round-trips from O(N) to O(1) for workout heart-rate ingestion.

## 2. Redundant Preferences Read Optimization

**Context:** The `userPreferences.first()` flow is read multiple times within a single scoring computation path, causing redundant disk/DataStore snapshot reads.

**Design:**
- **Location:** `ScoringRepositoryImpl.kt`
- **Change:**
  1. Read `settingsRepo.userPreferences.first()` exactly once at the entry point of `computeAndPersistDailySummary`.
  2. Pass the resolved `prefs` to `computeDailySummary(targetDate, prefs)`.
  3. Resolve `zoneId` from `prefs` and pass it to `persist(summary, zoneId)`.
  4. Ensure internal zero-arg methods do not re-read `settingsRepo.userPreferences.first()`.
- **Benefit:** Eliminates redundant I/O operations per scoring pass.

## 3. EMA Batching in Scoring Engine & ViewModel

**Context:** Inside `WorkoutsViewModel`, the CTL (Chronic Training Load) and ATL (Acute Training Load) EMAs are recomputed from scratch for every displayed day. Because the EMA calculation walks 42 days of history, this results in O(N × 42) operations. Additionally, the same TRIMP history is redundantly mapped into two separate data structures (`trimpByDay` and `trimpByDate`).

**Design:**
- **Engine Changes (`ScoringCalculator.kt`, `CompositeScoringCalculator.kt`, `RasScoringStrategy.kt`):**
  - Add `computeCtlEmaSeries` and `computeAtlEmaSeries` methods to the `ScoringCalculator` interface.
  - These methods will take the `dailyTrimpByDate` map, a `rangeStart`, and a `rangeEnd`.
  - The implementation will compute the seed EMA up to `rangeStart`, then iteratively apply the EMA decay formula day-by-day up to `rangeEnd`, returning a `Map<LocalDate, Float>` containing the EMA for each day in the range.
- **ViewModel Changes (`WorkoutsViewModel.kt`):**
  - Consolidate the creation of `trimpByDay` and `trimpByDate` to avoid iterating the summary list twice.
  - Call the new `computeCtlEmaSeries` and `computeAtlEmaSeries` methods once for the entire displayed range before the daily loop.
  - Inside the daily loop, perform an O(1) map lookup for the day's CTL and ATL instead of invoking the heavy recalculation.
- **Benefit:** Reduces the CPU complexity from O(Days × 42) down to O(Days + 42), keeping the UI smooth during range adjustments. The scoring math remains encapsulated within the engine.

## Testing and Safety
- All changes are strictly structural (data flow and batching).
- No scoring formulas, thresholds, or coefficients are altered.
- Pre-existing unit tests covering `ScoringRepositoryImpl` and `WorkoutsViewModel` must continue to pass, verifying that the logical output remains completely identical.
