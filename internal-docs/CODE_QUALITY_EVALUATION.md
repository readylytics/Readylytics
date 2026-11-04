# Code-Quality Evaluation — Clean Code, DI, DRY, SOLID

**Date:** 2026-11-04 · **Baseline:** `origin/main` @ `9eeddcc` · **Scope:** whole repository (`app`, `feature:*` ×8, `core:*` ×7, ~1,780 Kotlin files / ~127k LOC) · **Type:** read-only assessment, no code changed.

## 1. Purpose

This document records a point-in-time review of the Readylytics codebase against common engineering practices: Clean Code, Dependency Injection (DI), Don't Repeat Yourself (DRY), and the SOLID principles. It is meant to be read without any other context. For each finding it lists the evidence (file and line), why it matters, and a remediation step that can be scheduled as its own small PR.

## 2. Method

- The whole repo was swept with targeted `grep`/`find` queries. Queries covered file sizes, `@Module` bindings, Android imports in pure modules, `@Suppress` usage, detekt baselines, hard-coded dispatchers and clocks, broad `catch` blocks, `!!`/`lateinit`, cross-module dependencies, and fully-qualified call noise.
- Representative files were then read directly (`ScoringRepositoryImpl`, `HealthConnectRepositoryImpl`, `DashboardViewModel`, DI modules, selected settings VMs and use cases).
- Counts are approximate. Production sources only; `src/test`, `src/androidTest` and `build/` are excluded unless stated.
- Scoring formulas were **not** evaluated. They are out of scope and governed by `ABOUT.md` / `internal-docs/DATA_FLOW.md`.

## 3. Summary

The architecture is above average: well-layered modules, consistent Hilt DI, a pure-Kotlin scoring core, and a large unit-test suite. Most weaknesses are at the micro level:

- oversized classes and methods (SRP);
- a few DI leaks and an inconsistent time abstraction;
- broad exception handling, including one real cancellation bug;
- duplicated chart and validation code;
- some structure that was split to satisfy detekt thresholds rather than by cohesion.

| Area | Grade | Rationale |
|---|---|---|
| Architecture / layering | A- | Features depend only on `core:designsystem`, `core:scoring`, `core:model`. None depend on `core:database` or `core:healthconnect`. Zero `android.*` imports in `core:scoring` / `core:model`. |
| Dependency Injection | B | Hilt is used throughout, with no service-locator singletons and qualified dispatchers. Weak spots: collaborators built inline, dead `Clock` defaults, wall-clock calls bypassing the injected `Clock`. |
| SOLID | B- | Dependency inversion is strong (interfaces in `core:model`, impls in `core:database`). SRP and ISP are violated by several 500–780-line classes. |
| DRY | B | `RetentionBounds` and `SessionLinker` are good single sources of truth. Chart scaffolding, chart-visibility handlers and birth-year validation are duplicated. |
| Clean Code | B- | Good naming, 598 unit-test files, no `GlobalScope` / `runBlocking`. Against that: ~200 detekt-baseline entries, ~101 broad catches, fully-qualified call noise, and stringly-typed errors. |

## 4. Strengths (keep doing)

1. **Dependency inversion and module boundaries.**
   - Repository contracts (`ScoringRepository`, `ScoringHistoryRepository`, `SettingsRepository`) live in `core/model/.../domain/repository/` and are bound in `core/database/.../di/DatabaseRepositoryModule.kt`.
   - Feature modules only see ports such as `ForegroundSyncGateway` and `HealthConnectPermissionChecker`, wired in `app/.../di/FeaturePortModule.kt`.
2. **Pure domain core.** `core:scoring` has no Android dependency, so `BaselineComputer` and the `Compute*UseCase` classes are plain-JVM testable.
3. **Coroutine hygiene.**
   - No `GlobalScope` or `runBlocking` in production code.
   - Dispatchers are injected via qualifiers such as `@DefaultDispatcher`.
   - Most catch sites rethrow `CancellationException` first, e.g. `HealthConnectRepositoryImpl.kt:164`.
4. **Injectable time.** A `Clock` binding exists (`app/.../di/DataStoreModule.kt:339`), and `DashboardViewModel` uses `LocalDate.now(clock)`.
5. **Suppression discipline.** Production code has only 18 `@Suppress` annotations.

## 5. Findings

Findings are ordered by impact. Paths are abbreviated: `.../` stands for the module's `src/main/kotlin/app/readylytics/health/...` prefix.

### 5.1 SRP / ISP — oversized classes and methods

**Evidence**

The per-module `detekt-baseline.xml` files hold about 200 accepted issues:

| Rule | Count |
|---|---|
| `LongMethod` | 89 |
| `TooManyFunctions` | 43 |
| `ReturnCount` | 32 |
| `LongParameterList` | 19 |
| `CyclomaticComplexMethod` | 19 |
| `LargeClass` | 3 |

`core:scoring` alone carries 45 of these.

These files are at or near the 800-line hard limit (target is ≤400):

| File | Lines |
|---|---|
| `core/healthconnect/.../data/healthconnect/HealthConnectRepositoryImpl.kt` | 779 |
| `feature/sleep/.../SleepTrendChart.kt` | 689 |
| `feature/sleep/.../SleepStagesChart.kt` | 682 |
| `feature/dashboard/.../usecase/DashboardMetricPresentationFactory.kt` | 665 |
| `core/scoring/.../domain/scoring/ComputeSleepMetricsUseCase.kt` | 636 |
| `feature/workouts/.../AcwrChart.kt` | 626 |
| `core/database/.../data/repository/ScoringRepositoryImpl.kt` | 590 |
| `core/healthconnect/.../domain/sync/HealthIngestionCoordinator.kt` | 584 |
| `feature/dashboard/.../DashboardViewModel.kt` | 539 |

**Why it matters.** `HealthConnectRepositoryImpl` is a "god repository". It mixes four concerns for every record type:

- SDK availability checks;
- permission checks;
- paged reads;
- error translation.

Every new record type grows the same class, and every consumer depends on the whole surface. That violates both SRP and ISP.

**Remediation.**

- Split `HealthConnectRepositoryImpl` behind its existing interface into a permission gateway and per-record-type readers, keeping the public API stable.
- Burn down the baselines per module as files are touched (boyscout rule).

### 5.2 DI — hidden dependencies, dead defaults, bypassed abstractions

1. **Inline construction.** `ScoringRepositoryImpl.kt:56-90` builds its collaborators itself: `ScoringDayContextResolver(...)`, `DailyTrimpComputer(...)`, `BaseSummaryAssembler(...)` and others. These dependencies are hidden and can't be substituted, so tests cannot replace them.
2. **Parameter-object holders.** `ScoringDataLoaders`, `ScoringDayUseCases`, `DashboardRepositories` and `DashboardUseCases` exist mainly to pass detekt's `LongParameterList`. Grouping cohesive dependencies is fine. Here the holders mostly hide that one class has more than 10 collaborators, which is a symptom of the SRP problem in 5.1, not a fix for it.
3. **Dead `Clock` defaults.**
   - About 15 `@Inject` constructors declare `clock: Clock = Clock.systemDefaultZone()`, for example `ResyncRangeUseCase.kt:141`, `HistoricalIngestPhase.kt:136`, `RoomHealthIngestionStore.kt:51`, `SyncPreferences.kt:15`.
   - Dagger always supplies the bound `Clock`, so the default never applies in production. It does let hand-constructed test instances silently use wall time.
   - `RoomScanStagingStore.kt:23` defaults to `Clock.systemUTC()` while the bound clock is `systemDefaultZone()`. The two time-zone semantics are inconsistent.
4. **Wall-clock calls bypass the injected `Clock`.** About 20 production sites call `Instant.now()` or `LocalDate.now()` directly:
   - `data/backup/LocalBackupManager.kt:56,94`, `LocalRestoreManager.kt:169`, `BackupRotationService.kt:312`, `RestoreRecommendationCoverageChecker.kt:59`;
   - `domain/security/DatabaseKeyRotator.kt:23,32`;
   - `feature/onboarding/.../OnboardingViewModel.kt:54,90`, `OnboardingScreen.kt:83,156-157`;
   - UI-state defaults in `VitalsViewModel.kt:57`, `HeartRateUiModels.kt:21-22`, `WorkoutsStateFactory.kt:53`.

   These sites are non-deterministic in tests and inconsistent with `DashboardViewModel`.
5. **Module cohesion.**
   - `provideClock` lives in `DataStoreModule` (372 lines), which should only hold DataStore bindings.
   - `DaoProvidersSupplementModule`, `DatabaseRepositorySupplementModule` and `ScoringSyncBindingsSupplementModule` split their parent modules by function count (to satisfy detekt) rather than by concern.
6. **Hard-coded dispatcher.** `app/.../MainActivity.kt:91,211,213` uses `Dispatchers.IO` directly instead of an injected IO dispatcher.

**Remediation.**

- Give the inline collaborators `@Inject` constructors and take them as constructor parameters. Then re-evaluate whether the holder objects are still needed.
- Remove the `= Clock.system*()` defaults and pass a fixed `Clock` in tests.
- Inject `Clock` into the backup, restore, key-rotation and onboarding classes.
- Move `provideClock` into `UtilModule`.
- Regroup the `*SupplementModule` files by concern, e.g. `VitalsDaoModule`, `SourceDaoModule`.
- Inject the IO dispatcher in `MainActivity`.

### 5.3 Error handling

1. **Volume.** About 101 `catch (e: Exception)` / `catch (e: Throwable)` sites exist across 57 production files. Eleven of those files never reference `CancellationException`:
   - `SqlCipherKeyManager`, `ComputeWorkoutTrimpUseCase`, `CustomColorPicker`, `PhysiologySettingsViewModel`, `DataSourceSettingsViewModel`, `GetDashboardDataUseCase`;
   - `SecureFileLogSink`, `SafBackupStore`, `BackupSnapshotExporter`, `EncryptionManager`, `SecureFileStore`.
2. **Confirmed defect.** `feature/settings/.../data/DataSourceSettingsViewModel.kt:118-127` catches `Exception` inside `viewModelScope.launch { ... }`.
   - It swallows `CancellationException` and then writes `availableDevicesFlow.value = emptyList()` after the scope was cancelled.
   - This breaks structured concurrency.
3. **Over-broad catch.** `PhysiologySettingsViewModel.kt:70` catches `Exception` around `LocalDate.parse`. It should catch `DateTimeParseException`.
4. **Errors masked as generic failures.** `GetDashboardDataUseCase.kt:59` maps every exception to `"CARD_GENERATION_ERROR"`, which hides programming errors (NPE, ISE) as data failures.

**Remediation.**

- Add `catch (e: CancellationException) { throw e }`, or narrow the caught types, in the files listed. Start with `DataSourceSettingsViewModel`.
- Add a VM test that asserts cancellation propagates.
- Narrow the catches in parsing and use-case code.

### 5.4 DRY

1. **Chart scaffolding.**
   - `SleepTrendChart`, `SleepStagesChart` and `AcwrChart` each exceed 600 lines.
   - They repeat axis, gradient and marker setup that `core:ui` already partly abstracts (`rememberTrendCartesianChart`, `rememberTrendChartDecorations`).
   - Remediation: extract the remaining shared Vico scaffolding into `core:ui`.
2. **Chart-management handlers.**
   - `onToggleChartVisibility` / `onToggleSleepChartVisibility` and `onReorderCharts` / `onReorderSleepCharts` are implemented separately in two ViewModels.
   - Remediation: generalize the existing dashboard `CardManagementDelegate` into a shared helper.
3. **Birth-year validation.**
   - The rule `1900..LocalDate.now().year` is repeated in `OnboardingScreen.kt:156-157`, `OnboardingViewModel.kt:54` and `core/ui/.../BirthdayDatePickerField.kt:58,64`.
   - This also breaks the project rule that validation lives in `domain/validation/SettingsValidators` and never in composables.
   - Remediation: centralize the rule there, taking an injected `Clock`.

### 5.5 Clean-code nits

- **Fully-qualified calls.** There are 27 in `HealthConnectRepositoryImpl.kt` (e.g. `app.readylytics.health.core.model.domain.util.logE(...)`, `...healthconnect.BuildConfig.DEBUG`) and 14 in `app/.../ui/sync/SyncViewModel.kt`. Use imports.
- **Hard-coded, stringly-typed errors.**
  - `DashboardViewModel.kt:71` returns the hard-coded user-facing message `"Cannot select future dates"`, which belongs in `strings.xml`.
  - Error codes such as `"INVALID_DATE"` and `"CARD_GENERATION_ERROR"` are plain strings. A sealed error type would be safer.
- **Nullability escapes.** There are 40 `!!` and 20 `lateinit var` in production code. That is modest for the codebase size, but worth an audit.

## 6. Remediation backlog

Ship these as small, independent PRs. None of them touch scoring formulas.

| # | Item | Primary files | Docs impact |
|---|---|---|---|
| 1 | Rethrow or narrow in broad catches; fix the cancellation bug | `DataSourceSettingsViewModel` + 10 others (§5.3) | — |
| 2 | Clock consistency: drop defaults, inject `Clock` everywhere, move `provideClock` | §5.2.3–5.2.5 files | — |
| 3 | Inject `ScoringRepositoryImpl` collaborators; revisit holders | `ScoringRepositoryImpl.kt`, `ScoringSyncBindingsModule.kt` | `internal-docs/DATA_FLOW.md` |
| 4 | Centralize birth-year validation | `SettingsValidators`, onboarding, `BirthdayDatePickerField` | — |
| 5 | Split `HealthConnectRepositoryImpl` | `core/healthconnect/.../data/healthconnect/` | `internal-docs/DATA_FLOW.md` |
| 6 | Extract shared Vico chart scaffolding | sleep/workouts charts, `core:ui` | — |
| 7 | Regroup `*SupplementModule` DI modules by concern | `core/database/.../di/` | — |
| 8 | Ongoing detekt-baseline burn-down (boyscout) | all modules | — |

## 7. Acceptance criteria for remediation PRs

- Pre-commit gate passes: `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest`, then `./gradlew lintRelease`.
- The total entry count in `*/detekt-baseline.xml` must not increase. No new `@Suppress` without explicit human approval.
- Required new unit tests:
  - cancellation propagation (item 1);
  - fixed-`Clock` determinism (item 2);
  - year-range boundaries 1899 / 1900 / current / next (item 4).
- Items 3 and 5 update `internal-docs/DATA_FLOW.md` in the same change.
- Run `codegraph index` / `codegraph sync` after any file is added or moved.
