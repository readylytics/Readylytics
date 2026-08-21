# Align `core/model` Packages With Its Module — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename every `core/model` package that today spans another module (`di`, `domain.sync`
with its `.link`/`.mappers` subpackages, `domain.dashboard`, `workers`, `domain.util`,
`domain.user`, `domain.security`, `domain.scoring`, `domain.migration`, `domain.common`,
`data.preferences`) so each is prefixed with `app.readylytics.health.core.model`.

**Architecture:** Eleven independent package renames via IDE refactor, ordered smallest and
lowest-fan-in first. This is the **largest and last** module plan in the Item 4 sequence (56
`src/main` files) — run it only after `core/database-schema`, `feature/dashboard`,
`database-benchmark`, `core/database`, `core/healthconnect`, and `core/scoring` are all done, so
this plan is the one that finally leaves zero packages in the measured 16-row table still
spanning modules.

**Tech Stack:** Kotlin, Hilt, Proto DataStore (`data.preferences`), Konsist. `core/model` is
pure-Kotlin (per `.claude/CLAUDE.md`'s "Logic Isolation" rule) except where explicitly noted.

**Spec:** `internal-docs/plans/POST_REMEDIATION_FOLLOWUPS.md` (Item 4, lines 337-397) and
`docs/superpowers/plans/2026-08-19-package-module-alignment-index.md` (sequencing, shared safety
verification, naming convention).

## Global Constraints

- Full gate before closing this plan: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease`.
- Baseline: 3,009 unit tests, 0 failures, 0 lint warnings (2026-08-18).
- **`data.preferences` is backed by Proto DataStore, not kotlinx.serialization.** Verified
  2026-08-19: `grep -n "@Serializable" core/model/src/main/kotlin/app/readylytics/health/data/preferences/*.kt`
  returns no matches, and `app/src/main/proto/user_preferences.proto` defines the on-disk wire
  format independently of any Kotlin package. Proto DataStore identity is the `.proto` file's
  message/field numbers, which this plan never touches — only the plain Kotlin domain classes
  (`UserPreferences`, `Gender`, `AppTheme`, etc.) that a hand-written `Serializer` maps to/from
  that proto. Renaming their package is safe for existing users' persisted preferences.
- `CleanArchTest.kt`'s allowed-data-imports lists (lines 56-68 and 92-104) enumerate
  `app.readylytics.health.data.preferences.*` FQNs by name — Task 11 of this plan must update
  those two `setOf(...)` literals in the same commit as the rename, or the test will start
  failing every domain-layer file that legitimately imports these types. This is called out again
  at Task 11.
- Rename via IDE "Refactor → Rename → Rename package", not `sed`.
- Run `codegraph sync` after each task.

## File Structure

Eleven packages/subtrees, 56 `src/main` files total, rooted at
`core/model/src/main/kotlin/app/readylytics/health/` (or `src/test/`):

| # | Package | `src/main` | `src/test` | Target |
|--:|---|--:|--:|---|
| 1 | `di` | 2 (`ApplicationScope.kt`, `CoroutineDispatchers.kt`) | 0 | `core.model.di` |
| 2 | `workers` | 1 (`WorkerScheduler.kt`) | 0 | `core.model.workers` |
| 3 | `domain.user` | 1 (`UserProfileActions.kt`) | 0 | `core.model.domain.user` |
| 4 | `domain.migration` | 1 (`DatabaseMigrationModels.kt`) | 0 | `core.model.domain.migration` |
| 5 | `domain.common` | 1 (`CircadianThresholdValidator.kt`) | 1 (`DomainCommonValidatorTest.kt`) | `core.model.domain.common` |
| 6 | `domain.security` | 2 (`AppLockSecurityConfig.kt`, `EncryptionManager.kt`) | 0 | `core.model.domain.security` |
| 7 | `domain.scoring` | 5 | 1 (`SleepScoreWeightProfileTest.kt`) | `core.model.domain.scoring` |
| 8 | `domain.util` | 13 | 9 | `core.model.domain.util` |
| 9 | `domain.dashboard` | 7 | 4 | `core.model.domain.dashboard` |
| 10 | `domain.sync` (+ `.link`, `.mappers`) | 14 | 4 | `core.model.domain.sync` (subtree) |
| 11 | `data.preferences` | 9 | 1 (`GenderTest.kt`) | `core.model.data.preferences` |

**Row 7, `domain.scoring` file list (5):** `LoadCoverageConfidence.kt`, `LoadSourceMode.kt`,
`ScoringConstants.kt`, `SleepScoreWeightProfile.kt`, `TrimpModel.kt`. Distinct from `core/scoring`'s
55-file `domain.scoring` package, already renamed to `core.scoring.domain.scoring` by that plan —
no collision.

**Row 8, `domain.util` file list (13 `src/main`):** `AppLog.kt`, `ElevationGainCalculator.kt`,
`PaceSpeedCalculator.kt`, `PerformanceMonitor.kt`, `ResourceProvider.kt`, `RetentionBounds.kt`,
`RouteDistanceCalculator.kt`, `RouteProjector.kt`, `RouteSimplifier.kt`,
`SessionTotalsResolver.kt`, `TimeUtils.kt`, `TimezoneProvider.kt`, `UnitConverter.kt`.
`RetentionBounds.kt` is the single source of truth for resync/cleanup retention math referenced by
`.claude/CLAUDE.md`'s "Retention-bounded window" rule — this plan renames its package only, never
its logic. `src/test` (9): `AppLogTest.kt`, `ElevationGainCalculatorTest.kt`,
`PaceSpeedCalculatorTest.kt`, `RetentionBoundsTest.kt`, `RouteDistanceCalculatorTest.kt`,
`RouteProjectorTest.kt`, `RouteSimplifierTest.kt`, `SessionTotalsResolverTest.kt`,
`UnitConverterTest.kt`. Distinct from `core/scoring`'s 2-file `domain.util` package (already
renamed) — no collision.

**Row 9, `domain.dashboard` file list (7 `src/main`):** `CardConfiguration.kt`,
`CardConfigurationRepository.kt`, `CardIdExtensions.kt`, `CardManagementDelegate.kt`,
`DashboardCardCatalog.kt`, `DashboardCardDisplayMode.kt`, `ModeSpec.kt`. `src/test` (4):
`DashboardCardDisplayModeSerializerTest.kt`, `DashboardCardCatalogTest.kt`,
`CardManagementDelegateTest.kt`, `ModeSpecTest.kt`. Distinct from `core/scoring`'s and
`feature/dashboard`'s `domain.dashboard` packages (both already renamed) — no collision.

**Row 10, `domain.sync` subtree detail:**
- Flat (6 `src/main`): `FeatureSyncPorts.kt`, `HealthChangeTokenStore.kt`,
  `HealthIngestionStore.kt`, `ResyncCheckpointStore.kt`, `SelectedSourcePruner.kt`,
  `SyncWorkoutRouteUseCase.kt`. Flat `src/test` (2): `RecalcProgressTest.kt`,
  `HealthIngestionStoreTest.kt`.
- `.link/` (3 `src/main`): `SessionLinkReconciler.kt`, `SessionLinkSweep.kt`, `SessionLinker.kt`
  — this is the `SessionLinkReconciler` from `.claude/CLAUDE.md`'s "Session-link reconcile"
  contract (chunk-independent determinism pass). `.link/` `src/test` (2):
  `SessionLinkSweepPropertyTest.kt`, `SessionLinkerTest.kt`. **Note:**
  `core/database/src/test/kotlin/app/readylytics/health/domain/sync/link/SessionLinkReconcilerTest.kt`
  (flagged in the `core/database` module plan's File Structure section) tests *this* module's
  `SessionLinkReconciler` via same-package cross-module implicit access — Task 10, Step 3 verifies
  it still compiles after this rename, mirroring the `core/scoring` plan's Task 4 pattern.
- `.mappers/` (5 `src/main`): `HeartRateMapper.kt`, `HrvMapper.kt`, `SleepDataMapper.kt`,
  `StepsMapper.kt`, `WorkoutMapper.kt` — the per-chunk mappers referenced in `.claude/CLAUDE.md`'s
  "Session-link reconcile" note (`HeartRateMapper`/`HrvMapper` only see sessions in their own
  fetch window). `.mappers/` `src/test`: none found.
- (6 + 3 + 5 = 14 `src/main`; 2 + 2 + 0 = 4 `src/test`.)

**Row 11, `data.preferences` file list (9 `src/main`):** `AppTheme.kt`,
`BackgroundSyncInterval.kt`, `FallbackThemeColor.kt`, `Gender.kt`, `PhysiologyProfile.kt`,
`SettingsDefaults.kt`, `SyncPreference.kt`, `UnitSystem.kt`, `UserPreferences.kt`. `src/test` (1):
`GenderTest.kt`. **This is the widest-consumer package in this plan** — 14 modules import from it
(`app`, `core/database`, `core/designsystem`, `core/healthconnect`, `core/model`, `core/scoring`,
`core/ui`, `database-benchmark`, `feature/dashboard`, `feature/onboarding`, `feature/settings`,
`feature/sleep`, `feature/vitals`, `feature/workouts`) — do this task last, after the pattern is
well-proven on the other ten.

## Task 1: Rename `di` → `core.model.di`

**Files:** Move `di/ApplicationScope.kt`, `di/CoroutineDispatchers.kt`.

**Interfaces:** Produces `app.readylytics.health.core.model.di.{ApplicationScope,CoroutineDispatchers}`.
`ApplicationScope` and `@DefaultDispatcher`/`@IoDispatcher` qualifiers are Hilt qualifier
annotations resolved by type, not by package string — safe under rename.

- [ ] **Step 1: Baseline.** Run: `./gradlew :core:model:testDebugUnitTest` — Expected: PASS.
- [ ] **Step 2: Rename.** Right-click `core/model/src/main/kotlin/app/readylytics/health/di` →
  Refactor → Rename → "Rename package" → `app.readylytics.health.core.model.di` → both search
  options checked → Preview (confirm exactly 2 files) → apply.
- [ ] **Step 3: Sweep.** Run: `grep -rn "app\.readylytics\.health\.di\.\(ApplicationScope\|CoroutineDispatchers\)" --include="*.kt" . | grep -v /build/` — Expected: no output.
- [ ] **Step 4: `codegraph sync`.**
- [ ] **Step 5: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [ ] **Step 6: Commit.**
```bash
git add -A -- 'core/model/src/main/kotlin/app/readylytics/health/core' ':(glob)**/*.kt'
git commit -m "refactor: align core/model di package with module namespace"
```

## Task 2: Rename `workers` → `core.model.workers`

**Files:** Move `workers/WorkerScheduler.kt`.

**Interfaces:** Produces `app.readylytics.health.core.model.workers.WorkerScheduler` — an
interface implemented by `app`'s `WorkerSchedulerImpl` (`app/src/main/kotlin/app/readylytics/health/workers/WorkerSchedulerImpl.kt`,
untouched, `app` module). Consumed widely in `app` (`HealthDashboardApplication.kt`,
`DatabaseReadyStartupInitializer.kt`, `di/WorkerModule.kt`, `PeriodicHealthSyncWorker.kt`,
`data/backup/LocalRestoreManager.kt`, `domain/migration/DatabaseMigrationController.kt`,
`domain/user/UserUseCase.kt`, `domain/sync/HealthDataRefreshAdapter.kt`,
`domain/sync/HistoricalResyncControllerImpl.kt`) and in `core/healthconnect`'s
`ForegroundSyncController.kt`. This is a Kotlin interface, not a `@HiltWorker`/`CoroutineWorker`
implementation — per the index doc's verified safety notes, WorkManager's persisted-class-name
lookup only concerns actual `Worker` subclasses (all in `app`, all untouched), so this rename
carries no WorkManager reflection risk despite the wide fan-out.

- [ ] **Step 1: Baseline.** Run: `./gradlew :core:model:testDebugUnitTest :core:healthconnect:testDebugUnitTest :app:testDebugUnitTest` — Expected: PASS.
- [ ] **Step 2: Rename.** Right-click `core/model/src/main/kotlin/app/readylytics/health/workers`
  → Refactor → Rename → "Rename package" → `app.readylytics.health.core.model.workers` → both
  search options checked → Preview (confirm exactly 1 file moves; confirm the usage list includes
  the `app` and `core/healthconnect` files named above) → apply.
- [ ] **Step 3: Sweep.** Run: `grep -rn "app\.readylytics\.health\.workers\.WorkerScheduler\b" --include="*.kt" . | grep -v /build/` — Expected: no output (`app`'s own `workers.WorkerSchedulerImpl`, `workers.DataRollupWorker`, etc. are untouched, different classes, and must still appear under the old `app.readylytics.health.workers.` prefix — that is correct and expected).
- [ ] **Step 4: `codegraph sync`.**
- [ ] **Step 5: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [ ] **Step 6: Commit.**
```bash
git add -A -- 'core/model/src/main/kotlin/app/readylytics/health/core' ':(glob)**/*.kt'
git commit -m "refactor: align core/model workers package with module namespace"
```

## Task 3: Rename `domain.user` → `core.model.domain.user`

**Files:** Move `domain/user/UserProfileActions.kt`.

**Interfaces:** Produces `app.readylytics.health.core.model.domain.user.UserProfileActions`.
Consumers: `app`, `feature/onboarding`, `feature/settings`.

- [ ] **Step 1: Baseline.** Run: `./gradlew :core:model:testDebugUnitTest` — Expected: PASS.
- [ ] **Step 2: Rename.** Right-click `core/model/src/main/kotlin/app/readylytics/health/domain/user`
  → Refactor → Rename → "Rename package" → `app.readylytics.health.core.model.domain.user` → both
  search options checked → Preview (confirm exactly 1 file) → apply.
- [ ] **Step 3: Sweep.** Run: `grep -rn "app\.readylytics\.health\.domain\.user\.UserProfileActions" --include="*.kt" . | grep -v /build/` — Expected: no output.
- [ ] **Step 4: `codegraph sync`.**
- [ ] **Step 5: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [ ] **Step 6: Commit.**
```bash
git add -A -- 'core/model/src/main/kotlin/app/readylytics/health/core' ':(glob)**/*.kt'
git commit -m "refactor: align core/model domain.user package with module namespace"
```

## Task 4: Rename `domain.migration` → `core.model.domain.migration`

**Files:** Move `domain/migration/DatabaseMigrationModels.kt`.

**Interfaces:** Produces `app.readylytics.health.core.model.domain.migration.DatabaseMigrationModels`.
Consumers: `app` (`DatabaseMigrationController.kt`, per `.claude/CLAUDE.md`'s note that this
controller "must know migration readiness before any subscriber attaches" — this plan renames the
*models* package only, never `DatabaseMigrationController`'s own logic or its `SharingStarted`
choice), `core/database`, `database-benchmark`.

- [ ] **Step 1: Baseline.** Run: `./gradlew :core:model:testDebugUnitTest` — Expected: PASS.
- [ ] **Step 2: Rename.** Right-click `core/model/src/main/kotlin/app/readylytics/health/domain/migration`
  → Refactor → Rename → "Rename package" → `app.readylytics.health.core.model.domain.migration` →
  both search options checked → Preview (confirm exactly 1 file; confirm `app`, `core/database`,
  `database-benchmark` usages listed) → apply.
- [ ] **Step 3: Sweep.** Run: `grep -rn "app\.readylytics\.health\.domain\.migration\.DatabaseMigrationModels" --include="*.kt" . | grep -v /build/` — Expected: no output.
- [ ] **Step 4: `codegraph sync`.**
- [ ] **Step 5: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [ ] **Step 6: Commit.**
```bash
git add -A -- 'core/model/src/main/kotlin/app/readylytics/health/core' ':(glob)**/*.kt'
git commit -m "refactor: align core/model domain.migration package with module namespace"
```

## Task 5: Rename `domain.common` → `core.model.domain.common`

**Files:** Move `domain/common/CircadianThresholdValidator.kt` and its test
`DomainCommonValidatorTest.kt`.

**Interfaces:** Produces `app.readylytics.health.core.model.domain.common.CircadianThresholdValidator`.
No consumer found outside `core/model` itself (verified 2026-08-19 — only its own test imports
it). Distinct from `core/scoring`'s `domain.common.ScoringConfigValidator` (already renamed).

- [ ] **Step 1: Baseline.** Run: `./gradlew :core:model:testDebugUnitTest` — Expected: PASS.
- [ ] **Step 2: Rename.** Right-click `core/model/src/main/kotlin/app/readylytics/health/domain/common`
  → Refactor → Rename → "Rename package" → `app.readylytics.health.core.model.domain.common` →
  both search options checked → Preview (confirm 1 `src/main` + 1 `src/test` file) → apply.
- [ ] **Step 3: Sweep.** Run: `grep -rn "app\.readylytics\.health\.domain\.common\.CircadianThresholdValidator" --include="*.kt" . | grep -v /build/` — Expected: no output.
- [ ] **Step 4: `codegraph sync`.**
- [ ] **Step 5: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [ ] **Step 6: Commit.**
```bash
git add -A -- 'core/model/src/main/kotlin/app/readylytics/health/core' 'core/model/src/test/kotlin/app/readylytics/health/core' ':(glob)**/*.kt'
git commit -m "refactor: align core/model domain.common package with module namespace"
```

## Task 6: Rename `domain.security` → `core.model.domain.security`

**Files:** Move `domain/security/AppLockSecurityConfig.kt`, `domain/security/EncryptionManager.kt`.

**Interfaces:** Produces `app.readylytics.health.core.model.domain.security.{AppLockSecurityConfig,EncryptionManager}`.
`EncryptionManager` here is a `core/model`-level abstraction/interface, distinct from
`core/database`'s concrete `data.security.SqlCipherKeyManager`/`AndroidKeystoreKeyProvider`
(already renamed by the `core/database` plan) — confirm this by reading the file before renaming
if unsure which is the interface and which is the implementation, since getting this wrong would
mean editing the wrong module's plan.

- [ ] **Step 1: Confirm this is the interface, not the implementation.** Run:
  `grep -n "^interface\|^class\|^abstract class" core/model/src/main/kotlin/app/readylytics/health/domain/security/EncryptionManager.kt`
  — Expected: `interface EncryptionManager` (or similar abstract declaration). If it is a concrete
  class instead, stop and re-check whether this file belongs in this plan or the `core/database`
  plan before proceeding.
- [ ] **Step 2: Baseline.** Run: `./gradlew :core:model:testDebugUnitTest :core:database:testDebugUnitTest :core:scoring:testDebugUnitTest :database-benchmark:compileDebugKotlin :feature:settings:testDebugUnitTest` — Expected: PASS.
- [ ] **Step 3: Rename.** Right-click `core/model/src/main/kotlin/app/readylytics/health/domain/security`
  → Refactor → Rename → "Rename package" → `app.readylytics.health.core.model.domain.security` →
  both search options checked → Preview (confirm exactly 2 files; confirm `core/database`,
  `core/scoring`, `database-benchmark`, `feature/settings` usages listed) → apply.
- [ ] **Step 4: Sweep.** Run: `grep -rn "app\.readylytics\.health\.domain\.security\.\(AppLockSecurityConfig\|EncryptionManager\)" --include="*.kt" . | grep -v /build/` — Expected: no output.
- [ ] **Step 5: `codegraph sync`.**
- [ ] **Step 6: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [ ] **Step 7: Commit.**
```bash
git add -A -- 'core/model/src/main/kotlin/app/readylytics/health/core' ':(glob)**/*.kt'
git commit -m "refactor: align core/model domain.security package with module namespace"
```

## Task 7: Rename `domain.scoring` (core/model's 5-file package) → `core.model.domain.scoring`

**Files:** Move `domain/scoring/LoadCoverageConfidence.kt`, `domain/scoring/LoadSourceMode.kt`,
`domain/scoring/ScoringConstants.kt`, `domain/scoring/SleepScoreWeightProfile.kt`,
`domain/scoring/TrimpModel.kt`, and test `SleepScoreWeightProfileTest.kt`. **Do not confuse with
`core/scoring`'s 55-file `domain.scoring` package — that one was already renamed in the
`core/scoring` module plan.**

**Interfaces:** Produces
`app.readylytics.health.core.model.domain.scoring.{LoadCoverageConfidence,LoadSourceMode,ScoringConstants,SleepScoreWeightProfile,TrimpModel}`.
`SleepScoreWeightProfile` backs the "User-selectable weight profiles" feature described in
`.claude/CLAUDE.md`'s Sleep Score section — this plan renames only its package, never the
Balanced/Duration/Recovery/Architecture/Continuity profile weights or the 0.92–1.00 regularity
multiplier logic.

- [ ] **Step 1: Baseline.** Run: `./gradlew :core:model:testDebugUnitTest :core:database:testDebugUnitTest :core:healthconnect:testDebugUnitTest :core:scoring:testDebugUnitTest :database-benchmark:compileDebugKotlin :feature:dashboard:testDebugUnitTest :feature:onboarding:testDebugUnitTest :feature:settings:testDebugUnitTest :feature:sleep:testDebugUnitTest :feature:workouts:testDebugUnitTest :app:testDebugUnitTest` — Expected: PASS.
- [ ] **Step 2: Rename.** Right-click `core/model/src/main/kotlin/app/readylytics/health/domain/scoring`
  → Refactor → Rename → "Rename package" → `app.readylytics.health.core.model.domain.scoring` →
  both search options checked → Preview (confirm exactly 5 `src/main` + 1 `src/test` file — if the
  preview shows more than 5 `src/main` files, it has incorrectly pulled in `core/scoring`'s
  already-renamed package; stop and re-select only this module's node) → apply.
- [ ] **Step 3: Sweep.** Run:
  `grep -rn "app\.readylytics\.health\.domain\.scoring\.\(LoadCoverageConfidence\|LoadSourceMode\|ScoringConstants\|SleepScoreWeightProfile\|TrimpModel\)" --include="*.kt" . | grep -v /build/`
  — Expected: no output.
- [ ] **Step 4: `codegraph sync`.**
- [ ] **Step 5: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [ ] **Step 6: Commit.**
```bash
git add -A -- 'core/model/src/main/kotlin/app/readylytics/health/core' 'core/model/src/test/kotlin/app/readylytics/health/core' ':(glob)**/*.kt'
git commit -m "refactor: align core/model domain.scoring package with module namespace"
```

## Task 8: Rename `domain.util` → `core.model.domain.util`

**Files:** Move the 13 `src/main` files and 9 `src/test` files listed in the File Structure
section's row 8.

**Interfaces:** Produces `app.readylytics.health.core.model.domain.util.{AppLog,ElevationGainCalculator,PaceSpeedCalculator,PerformanceMonitor,ResourceProvider,RetentionBounds,RouteDistanceCalculator,RouteProjector,RouteSimplifier,SessionTotalsResolver,TimeUtils,TimezoneProvider,UnitConverter}`.
`RetentionBounds` is shared by `HealthResyncWorker`'s resync range AND `DataCleanupWorker`'s
cutoff (both in `app`, untouched) — verify both still resolve it correctly in Step 1's baseline
and Step 3's sweep.

- [ ] **Step 1: Baseline.** Run: `./gradlew :core:model:testDebugUnitTest :core:database:testDebugUnitTest :core:healthconnect:testDebugUnitTest :core:scoring:testDebugUnitTest :feature:dashboard:testDebugUnitTest :feature:settings:testDebugUnitTest :feature:sleep:testDebugUnitTest :feature:vitals:testDebugUnitTest :feature:workouts:testDebugUnitTest :app:testDebugUnitTest` — Expected: PASS.
- [ ] **Step 2: Rename.** Right-click `core/model/src/main/kotlin/app/readylytics/health/domain/util`
  → Refactor → Rename → "Rename package" → `app.readylytics.health.core.model.domain.util` → both
  search options checked → Preview (confirm 13 `src/main` + 9 `src/test` files) → apply.
- [ ] **Step 3: Sweep.** Run:
  `grep -rn "app\.readylytics\.health\.domain\.util\.\(AppLog\|ElevationGainCalculator\|PaceSpeedCalculator\|PerformanceMonitor\|ResourceProvider\|RetentionBounds\|RouteDistanceCalculator\|RouteProjector\|RouteSimplifier\|SessionTotalsResolver\|TimeUtils\|TimezoneProvider\|UnitConverter\)" --include="*.kt" . | grep -v /build/`
  — Expected: no output.
- [ ] **Step 4: `codegraph sync`.**
- [ ] **Step 5: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [ ] **Step 6: Commit.**
```bash
git add -A -- 'core/model/src/main/kotlin/app/readylytics/health/core' 'core/model/src/test/kotlin/app/readylytics/health/core' ':(glob)**/*.kt'
git commit -m "refactor: align core/model domain.util package with module namespace"
```

## Task 9: Rename `domain.dashboard` → `core.model.domain.dashboard`

**Files:** Move the 7 `src/main` files and 4 `src/test` files listed in the File Structure
section's row 9.

**Interfaces:** Produces `app.readylytics.health.core.model.domain.dashboard.{CardConfiguration,CardConfigurationRepository,CardIdExtensions,CardManagementDelegate,DashboardCardCatalog,DashboardCardDisplayMode,ModeSpec}`.
`CardConfigurationRepository`/`DashboardCardDisplayMode` back the
`DashboardCardsSettingsViewModel` flows discussed in
`internal-docs/plans/POST_REMEDIATION_FOLLOWUPS.md` Item 2 — this plan does not touch that
ViewModel's `SharingStarted` choices, only its imports' package.

- [ ] **Step 1: Baseline.** Run: `./gradlew :core:model:testDebugUnitTest :core:scoring:testDebugUnitTest :feature:dashboard:testDebugUnitTest :feature:settings:testDebugUnitTest` — Expected: PASS.
- [ ] **Step 2: Rename.** Right-click `core/model/src/main/kotlin/app/readylytics/health/domain/dashboard`
  → Refactor → Rename → "Rename package" → `app.readylytics.health.core.model.domain.dashboard` →
  both search options checked → Preview (confirm 7 `src/main` + 4 `src/test` files) → apply.
- [ ] **Step 3: Sweep.** Run:
  `grep -rn "app\.readylytics\.health\.domain\.dashboard\.\(CardConfiguration\|CardConfigurationRepository\|CardIdExtensions\|CardManagementDelegate\|DashboardCardCatalog\|DashboardCardDisplayMode\|ModeSpec\)" --include="*.kt" . | grep -v /build/`
  — Expected: no output.
- [ ] **Step 4: `codegraph sync`.**
- [ ] **Step 5: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [ ] **Step 6: Commit.**
```bash
git add -A -- 'core/model/src/main/kotlin/app/readylytics/health/core' 'core/model/src/test/kotlin/app/readylytics/health/core' ':(glob)**/*.kt'
git commit -m "refactor: align core/model domain.dashboard package with module namespace"
```

## Task 10: Rename `domain.sync` (whole subtree) → `core.model.domain.sync`

**Files:** Move all 14 `src/main` files (6 flat + 3 `.link` + 5 `.mappers`) and 4 `src/test` files
(2 flat + 2 `.link`) listed in the File Structure section's row 10.

**Interfaces:** Produces `app.readylytics.health.core.model.domain.sync.{FeatureSyncPorts,HealthChangeTokenStore,HealthIngestionStore,ResyncCheckpointStore,SelectedSourcePruner,SyncWorkoutRouteUseCase}`,
`...domain.sync.link.{SessionLinkReconciler,SessionLinkSweep,SessionLinker}`,
`...domain.sync.mappers.{HeartRateMapper,HrvMapper,SleepDataMapper,StepsMapper,WorkoutMapper}`.
Distinct from `core/healthconnect`'s already-renamed `domain.sync` package (different simple
names — `DailySyncUseCase`, `HealthSyncUseCase`, etc. — no collision).

- [ ] **Step 1: Baseline — cover every consumer module.** Run:
  `./gradlew :core:model:testDebugUnitTest :core:database:testDebugUnitTest :core:healthconnect:testDebugUnitTest :feature:dashboard:testDebugUnitTest :feature:onboarding:testDebugUnitTest :feature:settings:testDebugUnitTest :feature:sleep:testDebugUnitTest :feature:vitals:testDebugUnitTest :feature:workouts:testDebugUnitTest :app:testDebugUnitTest`
  — Expected: PASS. (`core/ui` has no unit test source set for this package — a Compose module —
  so it is covered by the full gate's `lintRelease`/compile step instead, not a `testDebugUnitTest`
  target of its own.)
- [ ] **Step 2: Rename, whole subtree.** Right-click
  `core/model/src/main/kotlin/app/readylytics/health/domain/sync` (this selects `.link` and
  `.mappers` along with it) → Refactor → Rename → "Rename package" →
  `app.readylytics.health.core.model.domain.sync` → both search options checked → Preview (confirm
  14 `src/main` + 4 `src/test` files moving; confirm
  `core/database/src/test/kotlin/app/readylytics/health/domain/sync/link/SessionLinkReconcilerTest.kt`
  appears under "usages to update" — if it is missing, open it and add an explicit
  `import app.readylytics.health.domain.sync.link.SessionLinkReconciler` before re-running the
  rename, mirroring the `core/scoring` plan's Task 4, Step 2 handling) → apply.
- [ ] **Step 3: Explicitly verify the cross-module `core/database` test file still compiles.** Run:
  `./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.domain.sync.link.SessionLinkReconcilerTest"`
  Expected: PASS.
- [ ] **Step 4: Sweep for stale references to this module's 14 simple names.** Run:
  `grep -rn "app\.readylytics\.health\.domain\.sync\.\(FeatureSyncPorts\|HealthChangeTokenStore\|HealthIngestionStore\|ResyncCheckpointStore\|SelectedSourcePruner\|SyncWorkoutRouteUseCase\)\|app\.readylytics\.health\.domain\.sync\.link\.\(SessionLinkReconciler\|SessionLinkSweep\|SessionLinker\)\|app\.readylytics\.health\.domain\.sync\.mappers\.\(HeartRateMapper\|HrvMapper\|SleepDataMapper\|StepsMapper\|WorkoutMapper\)" --include="*.kt" . | grep -v /build/`
  — Expected: no output.
- [ ] **Step 5: `codegraph sync`.**
- [ ] **Step 6: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [ ] **Step 7: Commit.**
```bash
git add -A -- 'core/model/src/main/kotlin/app/readylytics/health/core' 'core/model/src/test/kotlin/app/readylytics/health/core' 'core/database/src/test/kotlin/app/readylytics/health/domain/sync' ':(glob)**/*.kt'
git commit -m "refactor: align core/model domain.sync package (and subpackages) with module namespace"
```

## Task 11: Rename `data.preferences` → `core.model.data.preferences`

**Files:** Move the 9 `src/main` files and 1 `src/test` file (`GenderTest.kt`) listed in the File
Structure section's row 11.

**Interfaces:** Produces `app.readylytics.health.core.model.data.preferences.{AppTheme,BackgroundSyncInterval,FallbackThemeColor,Gender,PhysiologyProfile,SettingsDefaults,SyncPreference,UnitSystem,UserPreferences}`.
Widest consumer footprint in this plan (14 modules — see File Structure row 11).

- [ ] **Step 1: Baseline — cover every consumer module.** Run:
  `./gradlew :core:model:testDebugUnitTest :core:database:testDebugUnitTest :core:healthconnect:testDebugUnitTest :core:scoring:testDebugUnitTest :database-benchmark:compileDebugKotlin :feature:dashboard:testDebugUnitTest :feature:onboarding:testDebugUnitTest :feature:settings:testDebugUnitTest :feature:sleep:testDebugUnitTest :feature:vitals:testDebugUnitTest :feature:workouts:testDebugUnitTest :app:testDebugUnitTest`
  — Expected: PASS. (`core/designsystem` and `core/ui` have no unit tests for this package — a
  Compose module — covered by the full gate's compile/lint step.)
- [ ] **Step 2: Rename.** Right-click
  `core/model/src/main/kotlin/app/readylytics/health/data/preferences` → Refactor → Rename →
  "Rename package" → `app.readylytics.health.core.model.data.preferences` → both search options
  checked → Preview (confirm 9 `src/main` + 1 `src/test` file; review the full usage list given
  the wide fan-out — this is the one task in this plan worth reading the entire preview list
  rather than spot-checking) → apply.
- [ ] **Step 3: Update `CleanArchTest.kt`'s two allowed-data-imports lists.** This is the one
  required non-mechanical edit in this entire plan (flagged in Global Constraints above). In
  `app/src/test/kotlin/app/readylytics/health/CleanArchTest.kt`, the IDE rename from Step 2
  already rewrote every *code* `import` of these 9 types — but two `setOf(...)` literals in this
  test file enumerate their **old** FQNs as plain strings (not Kotlin `import`s), at lines 56-68
  (`` `domain package does not import data package` `` test) and lines 92-104
  (`` `domain package does not reference data package via fully-qualified names` `` test):
  ```kotlin
  val allowedDataImports =
      setOf(
          "app.readylytics.health.data.preferences.UserPreferences",
          "app.readylytics.health.data.preferences.Gender",
          "app.readylytics.health.data.preferences.AppTheme",
          "app.readylytics.health.data.preferences.SettingsDefaults",
          "app.readylytics.health.data.preferences.PhysiologyProfile",
          "app.readylytics.health.data.preferences.UnitSystem",
          "app.readylytics.health.data.preferences.SyncPreference",
          "app.readylytics.health.data.preferences.BackgroundSyncInterval",
          "app.readylytics.health.data.preferences.FallbackThemeColor",
          "app.readylytics.health.data.preferences.BackupSchedule",
      )
  ```
  Replace the `app.readylytics.health.data.preferences.` prefix with
  `app.readylytics.health.core.model.data.preferences.` in **all ten** entries, in **both**
  occurrences of this set (lines 56-68 and 92-104 are two separately-declared, identical-content
  sets — edit both). This includes `BackupSchedule`: verified 2026-08-19, `BackupSchedule` is a
  nested enum declared inside `UserPreferences.kt` (not its own file), so it moves along with the
  rest of `UserPreferences.kt` in Step 2 and its FQN changes exactly like the other nine.
- [ ] **Step 4: Sweep.** Run:
  `grep -rn "app\.readylytics\.health\.data\.preferences\.\(AppTheme\|BackgroundSyncInterval\|FallbackThemeColor\|Gender\|PhysiologyProfile\|SettingsDefaults\|SyncPreference\|UnitSystem\|UserPreferences\)" --include="*.kt" . | grep -v /build/`
  — Expected: no output, except any remaining `CleanArchTest.kt` string literal you deliberately
  left unprefixed in Step 3 for a type that turned out not to belong to this module.
- [ ] **Step 5: `codegraph sync`.**
- [ ] **Step 6: Run `CleanArchTest` specifically.** Run:
  `./gradlew :app:testDebugUnitTest --tests "app.readylytics.health.CleanArchTest"` — Expected:
  PASS. If either of the two data-import tests newly fails, Step 3's string replacement missed a
  type or missed one of the two `setOf(...)` occurrences.
- [ ] **Step 7: Full gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [ ] **Step 8: Commit.**
```bash
git add -A -- 'core/model/src/main/kotlin/app/readylytics/health/core' 'core/model/src/test/kotlin/app/readylytics/health/core' 'app/src/test/kotlin/app/readylytics/health/CleanArchTest.kt' ':(glob)**/*.kt'
git commit -m "refactor: align core/model data.preferences package with module namespace"
```

## Verification

Every module listed across all eleven tasks' baselines, green; full gate green after every task,
not just the last one. On-device: `./gradlew installDebug`, exercise Settings (physiology profile,
sync preference, theme, dashboard card management), confirm preferences persist across app
restart (proves the Proto DataStore `Serializer` still round-trips `UserPreferences` correctly
after its package rename), and confirm `EncryptionManager`/`AppLockSecurityConfig`-gated app-lock
still unlocks correctly if that feature is enabled on the test device.

## Plan-level self-review

- All 11 rows of `core/model`'s slice of the source spec's 16-row table are covered
  (`internal-docs/plans/POST_REMEDIATION_FOLLOWUPS.md:346-363`): `di` (Task 1), `workers` (Task 2),
  `domain.user` (Task 3), `domain.migration` (Task 4), `domain.common` (Task 5),
  `domain.security` (Task 6), `domain.scoring` (Task 7), `domain.util` (Task 8),
  `domain.dashboard` (Task 9), `domain.sync` (Task 10), `data.preferences` (Task 11).
- Every task's Step 2 preview check names the exact expected file count from the File Structure
  table, so a mis-scoped IDE selection (e.g. accidentally including a sibling module's
  already-renamed package) is caught before the rename is applied, not after.
- Task 11 is the only task requiring a hand-edit beyond the mechanical rename+sweep+gate+commit
  pattern (`CleanArchTest.kt`'s two string-literal `setOf`s) — flagged both in Global Constraints
  and inline at Task 11, Step 3, so it is not missed.
