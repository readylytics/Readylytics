# Implementation Plan: Native Biphasic Sleep Support

This plan translates approved design decisions from `docs/superpowers/specs/2026-07-09-biphasic-sleep-design.md` into concrete implementation work for Readylytics.

## 1. Goal
Implement segmented/biphasic sleep support with:
- raw Health Connect sleep sessions and stages preserved unchanged
- wake-day aggregation with metric isolation
- core-only readiness / overnight HRV / overnight RHR / nadir
- same-day supplemental duration and eligible architecture support
- deterministic outputs across daily sync, historical resync, retention windows, and restarts

## 2. Locked Rules To Implement
- core cluster = longest merged cluster
- cluster tie-break:
  1. greater total sleep minutes
  2. later final wake time
  3. earlier start time
  4. lexicographically smallest stable session id
- overlap fallback when no sleep device selected:
  1. longest total sleep duration
  2. richest stage coverage percentage
  3. lexicographically smallest package name
- supplemental ownership boundary:
  - `startTime < cutoff` => current day supplemental candidate
  - `startTime >= cutoff` => next-day-only candidate
- supplemental architecture formula:
  - `((light + deep + rem + awake) * 100) / totalSegmentDurationMinutes >= threshold`
  - integer math only
- time settings stored as `minutes-from-midnight`

## 3. Target Files

### Settings / Preferences
- `core/model/src/main/kotlin/app/readylytics/health/data/preferences/UserPreferences.kt`
- `core/model/src/main/kotlin/app/readylytics/health/data/preferences/SettingsDefaults.kt`
- `core/model/src/main/kotlin/app/readylytics/health/domain/preferences/FeatureSettingsPorts.kt`
- `core/model/src/main/kotlin/app/readylytics/health/domain/validation/SettingsValidators.kt`
- `app/src/main/kotlin/app/readylytics/health/data/preferences/UserPreferencesMapper.kt`
- `app/src/main/kotlin/app/readylytics/health/data/preferences/UserPreferencesSerializer.kt`
- `app/src/main/kotlin/app/readylytics/health/di/DataStoreModule.kt`
- backup/restore files under `app/src/main/kotlin/app/readylytics/health/data/backup/`
- settings UI and VMs under `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/`

### Aggregation / Scoring
- new pure Kotlin files under `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/`
- `core/database/src/main/kotlin/app/readylytics/health/data/repository/ScoringRepositoryImpl.kt`
- `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/ComputeSleepMetricsUseCase.kt`
- `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/BaselineComputer.kt`
- `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/CircadianConsistencyRepository.kt`
- `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/CurrentNightHrvResolver.kt`
- `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/SleepPercentileRhrCalculator.kt`
- `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/SleepNadirAnalyzer.kt`

### Storage / Repository / Sync
- `core/model/src/main/kotlin/app/readylytics/health/data/local/dao/SleepSessionDao.kt`
- `core/model/src/main/kotlin/app/readylytics/health/data/local/dao/SleepStageDao.kt`
- `core/database/src/main/kotlin/app/readylytics/health/data/repository/SleepSessionRepositoryImpl.kt`
- `core/database/src/main/kotlin/app/readylytics/health/data/repository/ScoringHistoryRepositoryImpl.kt`
- `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthChangeSynchronizerImpl.kt`
- `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailySyncUseCase.kt`
- `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCase.kt`

### UI / Read Models
- `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepViewModel.kt`
- `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendChart.kt`
- `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepStagesChart.kt`
- `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModel.kt`
- `core/database/src/main/kotlin/app/readylytics/health/data/repository/DailyMetricsRepositoryImpl.kt`
- `core/model/src/main/kotlin/app/readylytics/health/domain/model/DailyMetricsMapper.kt`
- optionally `core/model/src/main/kotlin/app/readylytics/health/domain/model/DailySummaryMapper.kt`

### Documentation
- `internal-docs/DATA_FLOW.md`
- `ABOUT.md`
- `docs/about.md`
- relevant strings files if user-visible copy changes

### Tests
- new and existing tests under:
  - `core/scoring/src/test/...`
  - `app/src/test/...`
  - `feature/sleep/src/test/...`
  - `feature/settings/src/test/...`
  - `core/database/src/androidTest/...` or `app/src/test/...` for migration coverage if needed

## 4. Step-by-Step Execution

### Step 1: Add Sleep Policy Settings
Implement new preference fields:
- `coreMergeGapMinutes`
- `supplementalCutoffMinutesOfDay`
- `minimumCountedSleepSegmentMinutes`
- `supplementalArchitectureCoveragePercent`

Tasks:
- add defaults, validators, mappers, serializer, restore/backup support
- store cutoff as integer minutes-from-midnight
- wire new update methods into settings ports
- update settings view models and UI sections

Verification:
- unit tests for defaults, ranges, persistence, backup/restore round-trip

### Step 2: Build Pure Aggregation Layer First
Create pure Kotlin aggregation model and service before changing scoring code.

Suggested new classes:
- `SleepAggregationPolicy`
- `SleepDayAggregate`
- `SleepSegment`
- `SleepCluster`
- `SleepDayAggregator`
- helper value objects for circadian anchor and recovery window

Responsibilities:
- filter short segments
- merge by configurable wake gap
- choose core cluster using locked tie-break order
- assign supplemental segments using strict cutoff boundary
- canonicalize provider overlaps
- compute eligible architecture totals with integer coverage math

Verification:
- exhaustive aggregation unit tests only, no scoring integration yet

### Step 3: Adapt Circadian Consistency
Refactor `CircadianConsistencyRepository` to consume aggregate/core anchors rather than raw sessions.

Tasks:
- remove raw-session `duration >= 180` heuristic as primary logic
- use historical core anchors for baseline and evaluation windows
- preserve scoring-zone determinism

Verification:
- regression tests for monophasic behavior
- new tests for segmented nights and supplemental naps not shifting circadian anchor

### Step 4: Adapt Recovery Helpers To Core Window
Refactor:
- `CurrentNightHrvResolver`
- `SleepPercentileRhrCalculator`
- `SleepNadirAnalyzer`
- `BaselineComputer`

Tasks:
- accept aggregate/core recovery-window inputs instead of whole raw session assumption
- ensure historical baselines use historical core clusters only
- ensure calibration counts valid aggregated sleep days, not raw fragment count

Verification:
- monophasic regressions unchanged
- late-arriving nap does not alter readiness physiology metrics

### Step 5: Refactor Daily Scoring To Consume Aggregate
Update `ScoringRepositoryImpl` and `ComputeSleepMetricsUseCase`.

Tasks:
- replace `getSessionEndingInRange(...)` as scoring truth source
- fetch enough raw sessions/stages to build target day aggregate
- use:
  - core recovery window for readiness metrics
  - aggregate duration for sleep duration scoring
  - eligible aggregate architecture totals for architecture scoring
- preserve existing formulas

Verification:
- scoring regression suite
- deterministic outputs for same raw inputs under multiple recompute paths

### Step 6: Update Sync / Recompute Ownership Behavior
Adjust sync-facing logic only where score-day ownership assumptions leak.

Tasks:
- ensure coarse affected-date triggers still widen enough for same-day nap updates
- keep one-extra-day ingest behavior in daily sync
- preserve resync chunk/reconcile/walk-forward contract
- verify no look-ahead dependency created by evening cutoff boundary

Verification:
- sync determinism tests
- retention-window equivalence tests

### Step 7: Update Sleep Screen And Trend Readers
Refactor sleep UI away from `firstOrNull()` raw-session assumptions.

Tasks:
- selected-day screen should show aggregate duration and distinguish core vs supplemental
- trend chart should derive from aggregate projection
- avoid fake merged raw session persistence

Verification:
- `SleepViewModel` tests
- trend projection tests

### Step 8: Audit Dashboard / Widget Consumers
Tasks:
- confirm dashboard score/duration cards already safe via `DailySummary`
- update any remaining raw-session summary consumers if needed
- ensure displayed sleep context does not contradict aggregate scoring

Verification:
- dashboard view model tests
- no raw single-session assumption remains in user-facing sleep metrics

### Step 9: Decide Whether DailySummaryEntity Needs New Fields
Default plan:
- avoid schema change unless necessary

If needed:
- add nullable additive columns only
- bump DB version `4 -> 5`
- update `DatabaseMigrations.kt`
- regenerate schema snapshots
- add migration tests

Decision gate:
- only proceed if UI/debug requirements cannot be satisfied from raw tables + existing summary columns

### Step 10: Documentation Synchronization
Update:
- `internal-docs/DATA_FLOW.md`
- `ABOUT.md`
- `docs/about.md`
- strings/about copy if user-facing explanation changes

Must document:
- wake-day with metric isolation
- core-only readiness physiology
- configurable segmented-sleep policy settings
- overlap resolution and determinism guarantees

Status:
- completed for `internal-docs/DATA_FLOW.md` in this pass
- verification not run in this pass

### Step 11: Full Verification Pass
Run at end:
- targeted new unit tests
- broader regression suites
- required repo checks:
  - `./gradlew ktlintFormat`
  - `./gradlew testDebugUnitTest`
  - `./gradlew lintRelease`

## 5. Test Matrix

### Aggregation
- single overnight session unchanged
- two nocturnal segments merge within threshold
- same segments split above threshold
- same-day nap before cutoff included
- evening segment at exact cutoff excluded from current day
- segment below minimum duration ignored
- equal-length cluster tie-break deterministic
- overlap canonicalization deterministic with and without selected device
- staged-coverage threshold uses integer clock-time denominator

### Scoring
- readiness unchanged by supplemental nap
- duration increases after supplemental nap
- architecture increases only when threshold passes
- core HRV/RHR/nadir unchanged by supplemental nap
- monophasic snapshots unchanged

### Sync / Resync
- daily sync catches prior-evening segmented sleep
- full resync equals daily sync outputs
- chunk size variation does not change outputs
- retention window variation does not change outputs

### Settings
- defaults correct
- ranges and steps enforced
- cutoff stored as minutes-from-midnight
- backup/restore round-trip preserved

### UI
- sleep view model selected-day projection
- trend chart aggregate projection
- dashboard no contradiction between duration and readiness

## 6. Risk Controls
- centralize aggregation in one pure domain component
- never duplicate tie-break logic across scoring and UI
- keep scoring formulas untouched
- preserve raw source tables unchanged
- use integer math for coverage thresholds
- keep scoring timezone as single source of truth for day-boundary math

## 7. Deliverables
- updated design spec with locked blocker resolutions
- implementation plan
- new settings and pure aggregation domain layer
- updated scoring/circadian/UI integrations
- updated docs and regression tests
