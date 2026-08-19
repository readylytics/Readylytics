# Align `core/healthconnect` Packages With Its Module — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename `core/healthconnect`'s three spanning packages (`di`, `domain.sync`,
`data.mapper`) so each is prefixed with `app.readylytics.health.core.healthconnect`.

**Architecture:** Three independent package renames via IDE refactor. `domain.sync` is the biggest
and highest-fan-in task in this module (12 `src/main` files, 13 `src/test` files, and the widest
consumer footprint in this plan — 11 different Gradle modules import something from
`app.readylytics.health.domain.sync.*` today, spread across this module, `core/model`, and
`core/database`). Do `di` and `data.mapper` first to prove the pattern on low-risk files before
the big one.

**Tech Stack:** Kotlin, Hilt, Health Connect Jetpack API, WorkManager (`ForegroundSyncController`
drives the resync/sync foreground-service progress banner — see
`.claude/CLAUDE.md`'s "Sync & Recalculation" section), Konsist.

**Spec:** `internal-docs/plans/POST_REMEDIATION_FOLLOWUPS.md` (Item 4, lines 337-397) and
`docs/superpowers/plans/2026-08-19-package-module-alignment-index.md` (sequencing, shared safety
verification, naming convention).

## Global Constraints

- Full gate before closing this plan: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease`.
- Baseline: 3,009 unit tests, 0 failures, 0 lint warnings (2026-08-18).
- **This module's `domain.sync` package is the sync/resync data-flow contract described in
  `.claude/CLAUDE.md`'s "Sync & Recalculation (Two-Flow Contract)" section
  (`ForegroundSyncController.triggerDailySync()`, `HealthSyncUseCase.sync/resyncRange`,
  `FullHistoricalResyncUseCase`).** This plan renames the *package*, never the sync/resync
  behaviour, chunking, retry policy, or the `SessionLinkReconciler` four-phase contract. If any
  step here would require touching the *logic* of these files rather than their `package`/`import`
  lines, stop — that is out of scope for Item 4.
- Rename via IDE "Refactor → Rename → Rename package", not `sed`.
- Run `codegraph sync` after each task.

## File Structure

Three packages, all flat (no subpackages) in this module, 18 `src/main` files total:

| Package | `src/main` files | `src/test` files | Target |
|---|--:|--:|---|
| `di` | 1 (`HealthConnectModule.kt`) | 0 | `core.healthconnect.di` |
| `domain.sync` | 12 | 13 | `core.healthconnect.domain.sync` |
| `data.mapper` | 5 | 2 | `core.healthconnect.data.mapper` |

All paths rooted at `core/healthconnect/src/main/kotlin/app/readylytics/health/` (or `src/test/`
for tests).

**`domain.sync` file list (`src/main`, 12):** `DailySyncUseCase.kt`, `DeviceSourceFilter.kt`,
`ForegroundSyncController.kt`, `FullHistoricalResyncUseCase.kt`, `HealthChangeSynchronizer.kt`,
`HealthConnectRetryPolicy.kt`, `HealthIngestionCoordinator.kt`, `HealthSyncUseCase.kt`,
`ResyncRangeUseCase.kt`, `RetryWithBackoff.kt`, `StepCountFetcher.kt`, `SyncConstants.kt`.

**`domain.sync` file list (`src/test`, 13):** `DailySyncUseCaseTest.kt`,
`DeviceSourceFilterTest.kt`, `FirstSetupDummyIngestionFlowTest.kt`,
`ForegroundSyncControllerTest.kt`, `FullHistoricalResyncUseCaseTest.kt`,
`HealthConnectRetryPolicyTest.kt`, `HealthIngestionCoordinatorTimeoutTest.kt`,
`HealthSyncUseCaseTest.kt`, `RecordingTransactionRunner.kt`, `ResyncCheckpointResumeTest.kt`,
`ResyncRangeUseCaseTest.kt`, `RetryWithBackoffTest.kt`, `StepCountFetcherRangeTest.kt`,
`SyncWorkoutRouteUseCaseTest.kt`.

**`data.mapper` file list (`src/main`, 5):** `BloodPressureDataMapper.kt`, `BodyFatDataMapper.kt`,
`BodyTemperatureDataMapper.kt`, `OxygenSaturationDataMapper.kt`, `WeightDataMapper.kt`.

**`data.mapper` file list (`src/test`, 2):** `BodyTemperatureDataMapperTest.kt`,
`OxygenSaturationDataMapperTest.kt`.

**Consumer modules of `app.readylytics.health.domain.sync.*` repo-wide (2026-08-19,
`grep -rl "import app.readylytics.health.domain.sync\."`):** `app`, `core/database`,
`core/healthconnect` (self), `core/model`, `core/ui`, `feature/dashboard`, `feature/onboarding`,
`feature/settings`, `feature/sleep`, `feature/vitals`, `feature/workouts`. This list mixes
consumers of *this* module's `domain.sync` classes (e.g. `HealthSyncUseCase`) and consumers of
`core/model`'s *same-named* `domain.sync` package (e.g. `FeatureSyncPorts`,
`HealthChangeTokenStore`) — both are handled correctly by the IDE refactor because it operates on
specific class references, not the package string as a whole; `core/model`'s classes are
untouched until that module's own plan runs.

## Task 1: Rename `di` → `core.healthconnect.di`

**Files:**
- Move: `di/HealthConnectModule.kt`.

**Interfaces:**
- Produces: `app.readylytics.health.core.healthconnect.di.HealthConnectModule`.

- [ ] **Step 1: Baseline.** Run: `./gradlew :core:healthconnect:testDebugUnitTest` — Expected: PASS.
- [ ] **Step 2: Rename.** Right-click `core/healthconnect/src/main/kotlin/app/readylytics/health/di`
  → Refactor → Rename → "Rename package" → `app.readylytics.health.core.healthconnect.di` → both
  search options checked → Preview (confirm exactly 1 file) → apply.
- [ ] **Step 3: Sweep.** Run: `grep -rn "app\.readylytics\.health\.di\.HealthConnectModule" --include="*.kt" . | grep -v /build/` — Expected: no output.
- [ ] **Step 4: `codegraph sync`.**
- [ ] **Step 5: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [ ] **Step 6: Commit.**
```bash
git add -A -- 'core/healthconnect/src/main/kotlin/app/readylytics/health/core' ':(glob)**/*.kt'
git commit -m "refactor: align core/healthconnect di package with module namespace"
```

## Task 2: Rename `data.mapper` → `core.healthconnect.data.mapper`

**Files:**
- Move: all 5 `src/main` files and 2 `src/test` files listed above.

**Interfaces:**
- Produces: `app.readylytics.health.core.healthconnect.data.mapper.{BloodPressureDataMapper,BodyFatDataMapper,BodyTemperatureDataMapper,OxygenSaturationDataMapper,WeightDataMapper}`.
- Note: distinct from `core/database`'s own `data.mapper` package (handled by the `core/database`
  module plan, Task 5) — no collision after both land.

- [ ] **Step 1: Baseline.** Run: `./gradlew :core:healthconnect:testDebugUnitTest` — Expected: PASS.
- [ ] **Step 2: Rename.** Right-click `core/healthconnect/src/main/kotlin/app/readylytics/health/data/mapper`
  → Refactor → Rename → "Rename package" → `app.readylytics.health.core.healthconnect.data.mapper`
  → both search options checked → Preview (confirm 5 `src/main` + 2 `src/test` files) → apply.
- [ ] **Step 3: Sweep.** Run: `grep -rn "app\.readylytics\.health\.data\.mapper\.\(BloodPressureDataMapper\|BodyFatDataMapper\|BodyTemperatureDataMapper\|OxygenSaturationDataMapper\|WeightDataMapper\)" --include="*.kt" . | grep -v /build/` — Expected: no output.
- [ ] **Step 4: `codegraph sync`.**
- [ ] **Step 5: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [ ] **Step 6: Commit.**
```bash
git add -A -- 'core/healthconnect/src/main/kotlin/app/readylytics/health/core' 'core/healthconnect/src/test/kotlin/app/readylytics/health/core' ':(glob)**/*.kt'
git commit -m "refactor: align core/healthconnect data.mapper package with module namespace"
```

## Task 3: Rename `domain.sync` → `core.healthconnect.domain.sync`

**Files:**
- Move: all 12 `src/main` files and 13 `src/test` files listed above.

**Interfaces:**
- Produces: `app.readylytics.health.core.healthconnect.domain.sync.{DailySyncUseCase,DeviceSourceFilter,ForegroundSyncController,FullHistoricalResyncUseCase,HealthChangeSynchronizer,HealthConnectRetryPolicy,HealthIngestionCoordinator,HealthSyncUseCase,ResyncRangeUseCase,RetryWithBackoff,StepCountFetcher,SyncConstants}`.
- Consumes: nothing new from Task 1/2 — but this task's own consumer set is the widest in this
  plan (see File Structure's consumer-module list). Because the rename touches 11 other modules'
  import statements, this task must run with a warm IDE project index (open the project and let
  indexing finish before starting) so the refactor preview is complete rather than partial.

- [ ] **Step 1: Baseline.** Run:
  `./gradlew :core:healthconnect:testDebugUnitTest :app:testDebugUnitTest :feature:settings:testDebugUnitTest :feature:sleep:testDebugUnitTest :feature:vitals:testDebugUnitTest :feature:workouts:testDebugUnitTest :feature:onboarding:testDebugUnitTest :feature:dashboard:testDebugUnitTest`
  — Expected: PASS. This baseline deliberately covers every consumer module found in the File
  Structure sweep so a break introduced by this task's rename is caught immediately rather than
  discovered later in the full gate.
- [ ] **Step 2: Rename.** Right-click `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync`
  → Refactor → Rename → "Rename package" → `app.readylytics.health.core.healthconnect.domain.sync`
  → both search options checked → Preview (confirm 12 `src/main` + 13 `src/test` files moving, and
  review the consumer-usage list — expect entries in `app`, `core/database`, `core/model` [only
  for files that import *this* module's classes, not its own `domain.sync` files — `core/model`'s
  own `domain.sync.*` files must not appear in the "files to move" list, only possibly in "usages
  to update" if `core/model` imports e.g. `HealthSyncUseCase`], `core/ui`, `feature/dashboard`,
  `feature/onboarding`, `feature/settings`, `feature/sleep`, `feature/vitals`, `feature/workouts`)
  → apply.
- [ ] **Step 3: Sweep — this module's specific classes only.** Run:
  `grep -rn "app\.readylytics\.health\.domain\.sync\.\(DailySyncUseCase\|DeviceSourceFilter\|ForegroundSyncController\|FullHistoricalResyncUseCase\|HealthChangeSynchronizer\|HealthConnectRetryPolicy\|HealthIngestionCoordinator\|HealthSyncUseCase\|ResyncRangeUseCase\|RetryWithBackoff\|StepCountFetcher\|SyncConstants\)" --include="*.kt" . | grep -v /build/`
  — Expected: no output. (Deliberately scoped to these 12 simple names, not the whole
  `domain.sync.` prefix, since `core/model`'s same-named package still legitimately matches that
  broader prefix until its own plan runs.)
- [ ] **Step 4: `codegraph sync`.**
- [ ] **Step 5: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [ ] **Step 6: Commit.**
```bash
git add -A -- 'core/healthconnect/src/main/kotlin/app/readylytics/health/core' 'core/healthconnect/src/test/kotlin/app/readylytics/health/core' ':(glob)**/*.kt'
git commit -m "refactor: align core/healthconnect domain.sync package with module namespace"
```

## Verification

`./gradlew :core:healthconnect:testDebugUnitTest :app:testDebugUnitTest` plus every consumer
module listed in Task 3's baseline, all green; full gate green. On-device: `./gradlew installDebug`,
pull-to-refresh the dashboard (exercises `HealthSyncUseCase.sync(windowDays = 1)` via
`ForegroundSyncController.triggerDailySync()`) and trigger Settings → "Resync Health Connect data"
(exercises `FullHistoricalResyncUseCase` → `HealthSyncUseCase.resyncRange()`), confirming both
flows still complete and the progress banner still updates — per `.claude/CLAUDE.md`'s explicit
requirement that these two flows never be conflated or broken.
