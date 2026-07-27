# F8, F1, F5 — Compose Stability & Sync-UX Design

**Source:** `internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md`
**Scope:** Implement work items F8, F1, F5 in that order, as three independent commits.
**Status:** Design approved by maintainer 2026-07-27. Ready for implementation planning.

## Context

`internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md` is a maintainer-approved performance audit
with prescriptive remediation steps per item. F4 and F9 (decoupling `isSyncing` from the heavy
Vitals/Sleep/Workouts pipelines) and F10 (batching the Workouts N+1 HR query) have already landed
(commits `ad1dd58`, `7df6d7c`). This design covers the next three items in the plan's dependency
order, as requested: F8 (compose stability annotations), F1 (stop routine sync from
skeleton-flashing/rebuilding charts), F5 (isolate Vitals recomposition scope).

All three items are additive/behavior-preserving except F1's approved UX change (skeletons only on
true first-load, not on every routine sync — this was already decided by the maintainer in plan
§3 decision 1, reconfirmed in this session).

Current code was read and verified against the plan's line anchors before this design was
written; anchors below reflect the actual current state, not the plan's (possibly drifted) line
numbers.

## Non-goals

- No changes to scoring math, thresholds, or coefficients (repo-wide constraint).
- No lazification of any chart — all Vitals/Sleep/Workouts charts stay fully composed.
- No new visible "refresh indicator" UI (spinner/progress bar). Confirmed with maintainer: match
  Dashboard's actual current behavior, where sync only disables specific controls — no separate
  indicator widget exists or is being added.
- F1 does not touch `WorkoutDetailViewModel`/`WorkoutDetailUiState` — that screen loads once via
  `loadWorkout()` and has no `isSyncing` combine to split.
- F5 does not touch Sleep or Workouts screens — plan scopes this item to Vitals only.

## F8 — Annotate feature UI states `@Immutable`

**Target classes** (all verified val-only, no post-construction mutation — safe to annotate):

| Class | File |
|---|---|
| `VitalsUiState` | `feature/vitals/.../overview/VitalsViewModel.kt` |
| `SleepUiState` | `feature/sleep/.../SleepViewModel.kt` |
| `WorkoutsUiState` | `feature/workouts/.../WorkoutsViewModel.kt` |
| `WorkoutDetailUiState` | `feature/workouts/.../WorkoutDetailViewModel.kt` |

**Change:** add `import androidx.compose.runtime.Immutable` and `@Immutable` directly above each
`data class` declaration. No other code changes. (`DailyDataPoint` is already `@Immutable`; no
action needed there.)

**Why safe:** every field is a `val`; nested types (`DailySummary`, `DailyMetrics`,
`SleepSessionData`, `SleepStageData`, `WorkoutData`, `WorkoutLoadClassification`, `SleepTimeGaugeData`,
`VitalsChartSeries`, `VitalsPresentationState`) were checked and are themselves val-only data
classes or already `@Immutable`. The annotation is a contract declaration; it changes no runtime
behavior, only the Compose compiler's stability inference.

**Verification:** `./gradlew :feature:vitals:assembleRelease -PenableComposeReports` (etc. per
module) shows these types as stable in the compose compiler report (M1 tooling, already wired up).

## F1 — Stop routine sync from destroying/rebuilding charts

**Per-ViewModel change** (mirrors `DashboardViewModel.kt:107-112`'s realtime-merge pattern, which
all three VMs already use post-F4/F9 — this is a refinement of the merge step, not a new pipeline
split):

### VitalsViewModel (`feature/vitals/.../overview/VitalsViewModel.kt`)

In the final `combine(contentFlow, presentationFlow, foregroundSyncController.isSyncing)`:
- `isLoading = isSyncing && content.latestSummary == null`
- add `isRefreshing = isSyncing` to `VitalsUiState`

### SleepViewModel (`feature/sleep/.../SleepViewModel.kt`)

In the `.combine(foregroundSyncController.isSyncing) { state, syncing -> ... }` step:
- `isLoading = syncing && (state.latestSummary == null && state.latestSession == null)`
- add `isRefreshing = syncing` to `SleepUiState`

Note: `trendStartOffsetPoints`/`trendDurationSpanPoints`/`trendActualDurationPoints` are always
padded to `range.days` entries (null-valued where no session exists that day — see the
`trendSessionsFlow` loop) and are therefore never actually empty. The plan's suggested "trend
lists empty" signal would never fire; `latestSummary == null` is the correct first-load proxy,
mirroring Dashboard's `isComputingMetrics = isSyncing && summary == null`.

### WorkoutsViewModel (`feature/workouts/.../WorkoutsViewModel.kt`)

In the `.combine(foregroundSyncController.isSyncing) { state, syncing -> ... }` step:
- `isLoading = syncing && (state.latestSummary == null && state.recentWorkouts.isEmpty())`
- add `isRefreshing = syncing` to `WorkoutsUiState`

Note: `dailyTrimp`/`dailyStrainRatio` are always padded to `displayDayMidnights.size` entries
(null-valued where no data — see the `displayDayMidnights.forEachIndexed` loop) and are therefore
never actually empty. The plan's suggested "`dailyTrimp.isEmpty()`" signal would never fire;
`latestSummary == null` is the correct first-load proxy, same reasoning as Sleep above.

### Screen-side wiring

- `CardLoader` call sites (Vitals ×3, Workouts ×2 in `WorkoutStatsSection.kt` + ×1 in
  `WorkoutsScreen.kt`), `SegmentedButton`/`SectionHeader` `enabled = !uiState.isLoading` sites
  (Vitals, Sleep, Workouts), and Sleep's raw `if (uiState.isLoading)` skeleton branches: **no code
  change** — same expression, now reads the redefined (initial-only) `isLoading`.
- `ScreenHeaderSection(isLoading = uiState.isLoading)` in `VitalsScreen.kt:100` and
  `WorkoutsScreen.kt:69` (the call that gates the `DateSwitcher`'s `enabled` state): change the
  argument to `uiState.isRefreshing`, so date navigation stays disabled for the full sync
  duration, not just on first-load. (Sleep's `DateSwitcher` call has no `enabled` gate today and
  is left as-is — out of scope to add one.)
- `isRefreshing` is otherwise unconsumed by any composable, matching `DashboardUiState.isRefreshing`'s
  current (also-unconsumed) state. No new indicator component.

**Risk:** Low-medium, UX change is approved. Must verify fresh-install (no data) still shows
skeletons, and a routine sync with existing data shows zero `TrendChart`/list recompositions
attributable to `isLoading`.

**Verification:** VM-level unit test asserting `isLoading`/`isRefreshing` values for each state
combination (no data + syncing, has data + syncing, has data + not syncing). Manual, on-device:
Layout Inspector recomposition count during a foreground sync (expect 0 for chart-hosting
composables), and a fresh-install skeleton check.

## F5 — Isolate Vitals screen recomposition

**Location:** `feature/vitals/.../overview/`

1. **`VitalsStateFactory.kt`** — add:
   ```kotlin
   @Immutable
   data class VitalsChartInputs(
       val chartSeries: VitalsChartSeries,
       val rangeStartMs: Long,
       val selectedRange: TimeRange,
       val presentation: VitalsPresentationState,
       val isCalibrating: Boolean,
       val isLoading: Boolean,
   )

   fun VitalsUiState.chartInputs(): VitalsChartInputs = VitalsChartInputs(
       chartSeries = chartSeries,
       rangeStartMs = rangeStartMs,
       selectedRange = selectedRange,
       presentation = presentation,
       isCalibrating = latestSummary?.isCalibrating ?: false,
       isLoading = isLoading,
   )
   ```
   (Mirrors `DashboardUiState.cardInputs()` exactly.)

2. **New file `VitalsTrendSection.kt`** (sibling to `VitalsScreen.kt`) — a private-scoped
   `@Composable fun VitalsTrendSection(chartInputs: VitalsChartInputs, chartScrollState, chartZoomState,
   parentScrollInProgress, modifier)` containing the three existing `CardLoader` + `TrendCard` +
   `TrendChart` blocks (HRV, RHR, SpO2), reading only `chartInputs` — never the raw `VitalsUiState`.

3. **New file `VitalsGaugeRow.kt`** (sibling) — a private-scoped
   `@Composable fun VitalsGaugeRow(latestSummary, presentation, onNavigateToHrv, onNavigateToRhr, modifier)`
   containing the gauge `CardLoader` block currently at `VitalsScreen.kt:127-245`, with the
   RHR/HRV delta-string logic changed to:
   ```kotlin
   val deltaUpText = stringResource(CoreUiR.string.delta_up)
   val deltaDownText = stringResource(CoreUiR.string.delta_down)
   val deltaNoChangeText = stringResource(CoreUiR.string.delta_no_change)
   val bpmUnit = stringResource(...unit_bpm)
   val rhrDelta = remember(currentRhr, baselineRhr) {
       if (currentRhr != null && baselineRhr != null) { /* build using hoisted locals */ } else null
   }
   ```
   (same pattern for HRV, with the `ms` unit string). `stringResource` calls are hoisted to plain
   locals *before* the `remember` block since composable calls aren't allowed inside its lambda.

4. **`VitalsScreen.kt`** shrinks to: header/date-switcher wiring, segmented time-range row, calls
   to `VitalsGaugeRow(...)` and `VitalsTrendSection(chartInputs = uiState.chartInputs(), ...)`, and
   `StatusLegend()`. `uiState.chartInputs()` is called once per recomposition of `VitalsScreen`
   itself (cheap — a handful of field reads into a new small data class) but the two child
   composables only recompose when the fields they were passed actually change, since
   `VitalsChartInputs` and the gauge-relevant params are stable/`@Immutable`.

**Depends on F8** (needs `@Immutable` on `VitalsUiState`/`VitalsPresentationState`/`VitalsChartSeries`
for the new slice types and the extracted composables to actually skip).

**Verification:** M1 compose compiler report shows `VitalsTrendSection`/`VitalsGaugeRow` as
skippable. Manual, on-device: Layout Inspector shows 0 `TrendChart` recompositions when only
gauge/refresh-related fields change, and vice versa.

## Implementation order & commits

Three separate commits, in order: **F8 → F1 → F5** (F5 depends on F8; F1 is independent of both
but requested in this position). Each commit runs `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`
before landing. `./gradlew lintRelease` runs once after the F5 commit (end of batch). New files
(`VitalsTrendSection.kt`, `VitalsGaugeRow.kt`) get `codegraph index` after creation.

## Open questions resolved this session

1. **F1 refresh indicator:** no new visible indicator. Skeletons remain for true first-load only;
   routine syncs show existing content with no skeleton and no replacement spinner/progress UI —
   matches Dashboard's current (also-indicator-less) behavior exactly.
2. **Manual verification:** device/emulator is available; Layout Inspector recomposition counts
   and the fresh-install skeleton check will be run manually when the plan reaches those steps.
3. **F5 file layout:** `VitalsTrendSection.kt` and `VitalsGaugeRow.kt` land as new sibling files
   next to `VitalsScreen.kt`, keeping each file under the repo's ≤400-line target.
