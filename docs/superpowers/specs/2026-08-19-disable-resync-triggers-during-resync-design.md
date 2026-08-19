# Disable resync/recalc triggers while a resync is running

## Problem

While a historical resync/recompute is running (the durable `HealthResyncWorker`), the Settings screen lets the user trigger another resync or recompute:

- "Recalculate sleep scores" button is **not** disabled during a resync (`ThresholdSettings.kt:326-329` gates only on `hasChangedSleepScoringPreferences`).
- "Resync Health Connect" and "Data Sources → Apply" *attempt* to disable during a resync, but the signal they rely on is broken (see Root cause), so they stay enabled in practice.
- Edit-driven resync triggers (HR zones, physiology profile, TRIMP/advanced settings) are never disabled.
- Separately, the "Recalculate sleep scores" button is enabled whenever the three sleep-scoring inputs differ from their **factory defaults** — not from the values history was last computed with. A user who sets a non-default value (e.g. goal = 9h) and recalcs sees the button stay enabled forever, even with no new changes.

User concern: pressing these while a resync runs could interfere. Even though the resync is idempotent (upsert keyed by HC `id`) and `scheduleResyncWorker` uses `KEEP`/`APPEND_OR_REPLACE`, the UI should lock these controls out while a resync is running.

## Root cause

`HistoricalResyncControllerImpl.state` derives `running` from:

```kotlin
workManager.getWorkInfosForUniqueWorkFlow(RESYNC_WORK_NAME)
    .map { it.firstOrNull()?.let { info ->
        info.state == RUNNING || info.state == ENQUEUED } ?: false }
```

`getWorkInfosForUniqueWorkFlow` returns the whole unique-work chain. With `ExistingWorkPolicy.APPEND_OR_REPLACE` (used by recompute passes) the chain holds more than one `WorkInfo`, and `firstOrNull()` can point at a terminal (e.g. `SUCCEEDED`) entry while a later entry is actively running → `running` reads `false`. The result is `isResyncing` stays `false` during a live resync, so the buttons stay enabled.

The reliable signal already exists: `HealthResyncWorker.doWork` bridges `onBackgroundRecalcStarted/Finished/Progress` into `ForegroundSyncController` (driving `_isSyncing`/`_recalcProgress` — the banner works correctly). We gate on that bridged state instead of the WorkManager flow.

## Approach

Single authoritative signal, bridged from the worker:

1. Add `val isResyncing: StateFlow<Boolean>` to `ForegroundSyncGateway` (`core/model/.../FeatureSyncPorts.kt`).
2. `ForegroundSyncController`: add `_isResyncing = MutableStateFlow(false)`; set `true` in `onBackgroundRecalcStarted()`, `false` in `onBackgroundRecalcFinished()`. Expose via `asStateFlow()`. **No worker changes** — the bridge calls already exist.
3. Thread `isResyncing` into the Settings UI and gate every resync/recompute trigger on it.

The signal is "background historical resync/recompute running" only. It does **not** cover the short foreground pull-to-refresh sync (`isSyncing`), which is out of scope per the decision to gate on background resync/recalc only.

Caveat: the bridged signal flips `true` when the worker actually starts running (a few ms after `enqueueUniqueWork`), not at enqueue time. This gap is negligible for an expedited foreground worker, and a redundant tap in the gap is already harmless (idempotent, `KEEP`/`APPEND_OR_REPLACE`).

## Changes

### 1. Signal (`core/model` + `core/healthconnect`)

- `FeatureSyncPorts.kt`: add `val isResyncing: StateFlow<Boolean>` to `ForegroundSyncGateway`.
- `ForegroundSyncController.kt`: implement as above.

### 2. Re-source existing gates (`feature/settings`)

- `SyncSettingsViewModel`: inject `ForegroundSyncGateway`; combine `foregroundSyncGateway.isResyncing` into `uiState.isResyncing` (replacing `historicalResyncController.state.running` as the boolean source). Keep `historicalResyncController` for `requestHistoricalResync` + periodic-sync scheduling. Keep `resyncCurrent`/`resyncTotal` sourced from `historicalResyncController.state` (per decision: keep the progress fields even though no UI reads them today).
- `DataSourceSettingsViewModel`: inject `ForegroundSyncGateway`; use `foregroundSyncGateway.isResyncing` for `isResyncing` (keep `HistoricalResyncController` for `requestHistoricalResync`).

### 3. Buttons

- Resync Health Connect (`DataManagementSection`): already `enabled = !isResyncing` — becomes correct via re-sourced signal.
- Data Sources → Apply: already `enabled = hasPendingChanges && !isResyncing` — becomes correct via re-sourced signal.
- Recalculate sleep scores (`SleepSettingsSection`): change to `enabled = uiState.hasPendingSleepScoreRecalc && !isResyncing`, and show the same spinner treatment as the Resync button.

### 4. Edit-driven controls

`SettingsScreen` already collects `syncState`; pass `syncState.isResyncing` down to the sections below and disable their resync/recompute-triggering controls when `true`:

- **HR zones** (`HeartRateSettings.kt`): auto-calculate switch, birthday picker, gender selector, max-HR field, manual-zone-editing switch, zone-editing text fields, and "Save zones" button.
- **Physiology profile picker** (`PhysiologyProfilePicker.kt` in `core/ui`): add `enabled: Boolean = true` param (default keeps existing onboarding/other callers unchanged), thread to `DropdownPreferenceItem.enabled`.
- **Advanced** (`AdvancedSettings.kt`): RAS-scaling slider, TRIMP model dropdown, banister/cheng/itrim sliders.
- **Sleep** (`ThresholdSettings.kt`): weight-profile dropdown and hypersomnia slider (explicitly requested), plus — for consistency, since they equally mutate scoring inputs and recompute today — goal-sleep-hours, core-merge-gap, supplemental-cutoff, minimum-segment, and architecture-coverage sliders.

### 5. Recalculate button — dirty-vs-last-recalc

Replace `hasChangedSleepScoringPreferences` (compares against factory defaults) with a persisted "last-recalculated" baseline, so the button enables only while the three sleep-scoring inputs differ from what history was last computed with.

**Semantics:** `hasPendingSleepScoreRecalc = current != baseline`, where baseline defaults to the factory defaults (`BALANCED`, `GOAL_SLEEP_HOURS`, `HYPERSOMNIA_ONSET_PERCENT`) until the first recalc, and is set to the current values when the button is pressed.

**Storage:** new small DataStore `SleepScoreRecalcBaselineProto` (three `optional` fields with presence flags: weight profile, goal sleep hours, hypersomnia onset percent), serializer with an empty default, following the existing per-feature DataStore pattern (`ResyncCheckpointStore`, layout-config DataStores). A repository/port exposes:
- `val baseline: Flow<SleepScoreRecalcBaseline?>` (`null` = never recalced),
- `suspend fun markRecalced(profile, goalHours, hypersomniaPercent)`.

Using a separate DataStore (rather than extending the shared `UserPreferencesProto`) keeps the change isolated and needs no migration — existing users simply start with `null` baseline, so the button is enabled once until they press it.

**ViewModel (`SleepSettingsViewModel`):** combine `userPreferences` (current) with the baseline flow into `SleepSettingsState`, exposing `hasPendingSleepScoreRecalc: Boolean`. On `SettingsEvent.RecalculateScores`: `requestScoreRecompute()` then `markRecalced(current values)` — the baseline is captured on enqueue (submit semantics), so the button disables immediately while the resync banner shows progress. (Trade-off: if the worker is killed before finishing, the baseline is briefly ahead of history; WorkManager's automatic retry and idempotent recompute make this self-correcting.)

**UI (`SleepSettingsSection`):** drop the local `hasChangedSleepScoringPreferences` computation; use `uiState.hasPendingSleepScoreRecalc`.

### Explicitly NOT gated (no scoring input — display/preference only)

- Retention slider (`DataManagementSection`) — data-retention scope; no recompute/resync triggered.
- Step goal (`ActivitySettingsSection`) — display goal; only a recent-window refresh.
- Display/theme/unit-system, custom palette, backup, issue-reporting, about — unrelated to scoring.

> Post-review scope note: the "Edit-driven controls" set was later widened to gate *all* scoring-input
> controls for consistency — this includes the HRV/RHR baseline overrides, resting-HR percentile,
> HRR tolerance slider, the thresholds sliders (`ThresholdSettingsSection`), the circadian-threshold
> override, and the strain/RAS load-source pickers. They are disabled while `isResyncing` even though
> they only trigger a current-day recompute (or persist) rather than a historical resync.

## Testing

- `ForegroundSyncControllerTest`: `isResyncing` goes `true` on `onBackgroundRecalcStarted`, `false` on `onBackgroundRecalcFinished`.
- `SyncSettingsViewModelTest` / `DataSourceSettingsViewModelTest`: `isResyncing` now follows the injected `ForegroundSyncGateway.isResyncing` flow.
- `SleepAndThresholdSettingsViewModelTest`: `hasPendingSleepScoreRecalc` is `false` at baseline, `true` after changing emphasis/goal/oversleep, `false` again after `RecalculateScores` (baseline captured), and `false` when a changed value is reverted to the last-recalced value. Also verify `markRecalced` is called on recalc.
- New baseline repository/store tests: `null` baseline → defaults comparison; round-trip of `markRecalced`.
- `ktlintFormat` + `testDebugUnitTest`.

## Documentation

- `internal-docs/DATA_FLOW.md`: update the progress/state section — Settings now gates on the `ForegroundSyncController`-bridged `isResyncing` StateFlow rather than the WorkManager `getWorkInfosForUniqueWorkFlow` `firstOrNull()` derivation (which had a multi-entry chain bug). No scoring-formula change, so `ABOUT.md` / `docs/about.md` / About strings are unaffected.
- `codegraph index` after finishing.

---

## Remediation Note (Post-Implementation Review)

The implementation described above was subsequently remediated during the code review fixes pass (`.superpowers/sdd/2026-08-19-code-review-fixes/`):

1. **Durable Gating Signal (`HistoricalResyncController`):** The ephemeral `ForegroundSyncGateway.isResyncing` bridge was removed. The Settings gating signal `isResyncing` is sourced from `HistoricalResyncController.state.running` (durable WorkInfo `RUNNING || ENQUEUED` across the unique work chain).
2. **Recalc Baseline Folded into `UserPreferences`:** Rather than a separate Proto DataStore (`SleepScoreRecalcBaselineStore`), the baseline fields were folded directly into `user_preferences.proto` (fields 86–88: `last_recalc_sleep_score_weight_profile`, `last_recalc_goal_sleep_hours`, `last_recalc_hypersomnia_onset_percent`), eliminating the duplicate store and ensuring standard backup/restore carries the baseline.
3. **Worker-Owned Scoring Version Bump & Baseline Update:** The scoring version bump was moved from the startup initializer to `HealthResyncWorker.persistPostRecomputeState()` (success-only, for both full-resync and recompute-only paths). The baseline is recorded upon worker success rather than prematurely at enqueue/submit time.
4. **Walk-Forward Lookback Optimization:** `BaselineComputer.prefetchWalkForwardSessions` widened its lookback window to `maxOf(56, 60)` = 60 days, enabling circadian regularity resolution to consume the prefetched superset in memory without per-day session queries.
