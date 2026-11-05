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

## 7. Recommended rollout: phased, not all-in-one

**Recommendation: roll out in phases, one or more small PRs per phase.** A single big-bang refactor is discouraged for four reasons:

- **Determinism risk.** The ingestion and scoring paths carry strict determinism and idempotency invariants: chunk-independent session linking, frozen baselines, and golden-equivalence tests. A mixed diff makes any score drift impossible to attribute to a cause.
- **Docs-sync rules.** Changes to the ingestion pipeline or the scoring coordinators must update `internal-docs/DATA_FLOW.md` in the same change. Isolating them keeps those doc updates small and reviewable.
- **Detekt discipline.** The detekt baseline must never grow. Small diffs make it easy to show the count only goes down.
- **Ordering dependencies.** Each later phase relies on safety nets from an earlier one. A deterministic `Clock` (Phase 2) must exist before the structural splits (Phase 4) can be verified with fixed-time tests.

Phases run in order. Within a phase, PRs are independent and can land in any order or in parallel. Each phase ends with its exit criteria green on `main`.

### Phase 0: Correctness hotfix
*Risk: low · Behavior change: bug fix only · Est. 1 PR*

| PR | Refactor | Files |
|---|---|---|
| 0.1 | Rethrow `CancellationException` before the broad catch in `refreshAvailableDevices()`, so state is not written after the scope is cancelled | `feature/settings/.../data/DataSourceSettingsViewModel.kt` |

**Exit criteria**
- A new unit test cancels `viewModelScope` mid-refresh and asserts that `availableDevicesFlow` is not written and that cancellation propagates.

### Phase 1: Error-handling and clean-code hygiene
*Risk: low · Behavior change: none intended · Est. 2–3 PRs*

| PR | Refactor | Files |
|---|---|---|
| 1.1 | Audit the remaining broad catches (§5.3). Add a `CancellationException` rethrow in suspend contexts, and narrow the caught types where the failure is known (e.g. `DateTimeParseException` in `PhysiologySettingsViewModel.kt:70`) | `SqlCipherKeyManager`, `SafBackupStore`, `BackupSnapshotExporter`, `EncryptionManager`, `SecureFileStore`, `SecureFileLogSink`, `PhysiologySettingsViewModel`, `GetDashboardDataUseCase`, `ComputeWorkoutTrimpUseCase`, `CustomColorPicker` |
| 1.2 | Replace fully-qualified calls with imports | `HealthConnectRepositoryImpl.kt` (27), `app/.../ui/sync/SyncViewModel.kt` (14), `ForegroundSyncController.kt` (7) |
| 1.3 | Move the `"Cannot select future dates"` message to `strings.xml`. Introduce a sealed domain error type in place of the stringly-typed codes (`"INVALID_DATE"`, `"CARD_GENERATION_ERROR"`) | `DashboardViewModel.kt`, `GetDashboardDataUseCase.kt`, the shared `Result` type in `core:model` |

**Exit criteria**
- The existing test suite is green with no golden-test changes.
- The detekt baseline count is ≤ the previous count.

### Phase 2: Time and DI consistency
*Risk: low–medium · Behavior change: none in production (Dagger already injects `Clock`); tests become deterministic · Est. 2–3 PRs*

| PR | Refactor | Files |
|---|---|---|
| 2.1 | Move `provideClock` from `DataStoreModule` to `UtilModule`. Add an `@IoDispatcher`-qualified binding if one is missing. Inject it into `MainActivity` in place of `Dispatchers.IO` | `app/.../di/DataStoreModule.kt`, `UtilModule.kt`, `CoroutineDispatchersModule.kt`, `MainActivity.kt` |
| 2.2 | Remove the dead `= Clock.system*()` defaults from the ~15 `@Inject` constructors. Pass a fixed `Clock` in tests. Resolve the `systemUTC()` vs `systemDefaultZone()` inconsistency in `RoomScanStagingStore` | `ResyncRangeUseCase`, `HistoricalIngestPhase`, `HistoricalPrunePhase`, `HistoricalRecomputePhase`, `HealthChangeSynchronizerImpl`, `RoomHealthIngestionStore`, `RoomHealthChangeIngestionStore`, `RoomScanStagingStore`, `SourcePayloadWriter`, `DailyRecomputeSupport`, `ScoringRepositoryImpl`, `SyncPreferences`, `PhysiologyPreferences`, `DatabaseReadyStartupInitializer` |
| 2.3 | Inject `Clock` wherever `Instant.now()` or `LocalDate.now()` is called directly. Remove `LocalDate.now()` defaults from UI-state data classes and seed them from the VM's clock instead | `LocalBackupManager`, `LocalRestoreManager`, `BackupRotationService`, `RestoreRecommendationCoverageChecker`, `DatabaseKeyRotator`, `OnboardingViewModel`, `VitalsViewModel`, `HeartRateUiModels`, `WorkoutsStateFactory` |
| 2.4 | Regroup the `*SupplementModule` DI modules by concern (e.g. `VitalsDaoModule`, `SourceDaoModule`, `JournalDaoModule`) instead of by function count | `core/database/.../di/` |

**Exit criteria**
- Running `grep -rn "Clock.system\|Instant.now()\|LocalDate.now()"` over production code finds only the single `provideClock` binding.
- New fixed-`Clock` tests are deterministic across time zones.
- The Hilt graph compiles: `assembleDebug` passes.

### Phase 3: DRY consolidation (validation and UI state)
*Risk: low–medium · Behavior change: none intended · Est. 2 PRs*

| PR | Refactor | Files |
|---|---|---|
| 3.1 | Centralize birth-date and year-range validation in `SettingsValidators`, taking an injected `Clock`. VMs call it, and composables only render the result | `domain/validation/SettingsValidators`, `OnboardingViewModel`, `OnboardingScreen`, `core/ui/.../BirthdayDatePickerField.kt`, `PhysiologySettingsViewModel` |
| 3.2 | Generalize the dashboard `CardManagementDelegate` into a shared chart and card management delegate. Replace the duplicated `onToggle*ChartVisibility` / `onReorder*Charts` handlers with it | `feature/dashboard/.../CardManagementDelegate`, the sleep and vitals VMs, target `core:ui` |

**Exit criteria**
- Boundary tests cover 1899, 1900, the current year and next year, plus future dates.
- No validation logic remains in composables.

**Phase 3 outcome (2026-11-05).**
- PR 3.1 shipped as `BirthdayBounds` in `core:model` (`SettingsValidators.birthdayBounds(clock)`). `BirthdayDateRule`, `OnboardingViewModel`, `PhysiologySettingsViewModel`, `BirthdayDatePickerField` and `OnboardingScreen` all take their limits from it. The `BackendClockArchitectureTest` birthday-picker allow-list entries are gone and both UI files are scanned.
- PR 3.2 differed from §5.4.2 as written: the chart toggle/reorder handlers already forwarded to the generic `LayoutManagementDelegate`. What was still duplicated was the edit-mode state machine in `SleepTopCardManagementDelegate`, `SleepMetricCardManagementDelegate` and `CardManagementDelegate`. `LayoutManagementDelegate` gained an optional display-mode hook, the two Sleep delegates were deleted, and `CardManagementDelegate` is now a thin facade (card-id validation and Health Connect permission gating on persist) over it. The shared delegate stayed in `core:model` (pure Kotlin), not `core:ui`.

### Phase 4: Structural SRP/DI refactors on the data path
*Risk: **high** (ingestion and scoring pipeline) · Behavior change: none permitted · Est. 3–4 PRs, one at a time*

| PR | Refactor | Files | Docs |
|---|---|---|---|
| 4.1 | Give the collaborators built inside `ScoringRepositoryImpl` (`ScoringDayContextResolver`, `DailyTrimpComputer`, `BaseSummaryAssembler`, …) `@Inject` constructors and take them as constructor parameters. Then re-evaluate whether `ScoringDataLoaders` / `ScoringDayUseCases` are still needed | `core/database/.../data/repository/ScoringRepositoryImpl.kt` and its collaborators, `ScoringSyncBindingsModule.kt` | `DATA_FLOW.md` |
| 4.2 | Split `HealthConnectRepositoryImpl` (779 lines) behind its existing interface into `HealthConnectAvailability`, `HealthConnectPermissionGateway`, a shared paged-read and error-translation helper, and per-record-type readers. The public API stays unchanged | `core/healthconnect/.../data/healthconnect/` | `DATA_FLOW.md` |
| 4.3 | Reduce `HealthIngestionCoordinator` (584 lines) by extracting cohesive phases, following the existing `Historical*Phase` pattern | `core/healthconnect/.../domain/sync/` | `DATA_FLOW.md` |
| 4.4 | Split `ComputeSleepMetricsUseCase` (636 lines) into structural helpers. **No formula changes.** | `core/scoring/.../domain/scoring/` | `DATA_FLOW.md` pointer only |

**Exit criteria**
- The golden and determinism suites pass unchanged: `BaselineLiveFrozenEquivalenceTest`, `BaselineComputerBackfillEquivalenceTest`, `WorkoutModelTrimpIngestionDeterminismTest`, and the `DocumentationDriftTest` family.
- Resync over two different retention windows produces identical scores.
- No public interface changes in `core:model`.
- `DATA_FLOW.md` is updated in the same PR.

**Phase 4 outcome (2026-11-05).**
- PR 4.1: `ScoringRepositoryImpl` collaborators are now Hilt-injected. `WalkForwardContextLoader` and `DayAssembler` were extracted, and the holders `ScoringDataLoaders`, `ScoringDayUseCases` and `MorningRecommendationDependencies` were deleted (`Vo2MaxScoringDependencies` and `ScoringRuntime` stay). `ScoringRepositoryImpl` went from 591 to 166 lines; `DayAssembler` is 288. The test seam is `createScoringRepositoryForTest(loaders: ScoringTestLoaders, useCases: ScoringTestUseCases, …)`, grouped into test data classes to satisfy `LongParameterList`.
- PR 4.2: `HealthConnectRepositoryImpl` went from 784 to a 225-line facade over `HealthConnectAvailability`, `HealthConnectPermissionGateway`, `HealthConnectPagedReader`, `HealthConnectReadGuard`, `HealthConnectVitalsReader`, `HealthConnectBodyMetricsReader`, `HealthConnectExerciseReader` and `HealthConnectDeviceDiscovery`. The baseline `LargeClass` and `LongMethod(discoverDevices)` entries are removed. The `TooManyFunctions` entry for the facade is intentionally kept: the `core:model` `HealthConnectRepository` contract mandates 32 overrides and Phase 4 may not change it. This needs explicit human approval per the project rule. Splitting the interface by role (ISP) is a possible follow-up.
- PR 4.3: `HealthIngestionCoordinator` went from 584 to 168 lines via internal `BulkWindowIngestPhase`, `DenseWindowStreamPhase` and `WindowScanReconciler`. The public 3-arg constructor stays as a secondary constructor (39 call sites across tests and benchmarks).
- PR 4.4: `ComputeSleepMetricsUseCase` went from 636 to about 259 lines via `NocturnalSleepScorer`, `SleepBaselineWindowResolver` and `SleepNightScoringContext`; `invoke` is about 44 lines. The `CyclomaticComplexMethod` and `LongMethod` baseline entries and the `@Suppress("SENSELESS_COMPARISON")` are removed. No formula changed. `SleepMetricsTypes.kt` grew from 174 to 216 lines (`NightEvidence`, `NocturnalScoringResult.EMPTY`, `toSleepSessionData`).
- Deviations from the plan: Task 9 ran before Task 8; Tasks 12 and 13 were batched; a `runOnIo` bridge was added and then removed; the `createScoringRepositoryForTest` signature was regrouped; `SleepNightScoringContext` was added.
- Baseline entries: `core:database` 16 to 16, `core:healthconnect` 8 to 6, `core:scoring` 45 to 43.
- New pins: `ChunkAlignmentIndependenceTest`, `HealthConnectReadGuardTest`, `HealthIngestionCoordinatorPhaseOrderTest`, `ComputeSleepMetricsUseCaseCharacterizationTest`, `ScoringRepositoryCollaboratorSubstitutionTest`, `WalkForwardContextLoaderTest`.
- Open item: `ChunkAlignmentIndependenceTest` is a reduced form of the "two retention windows give identical scores" criterion. A true end-to-end test needs an in-memory Room plus Health Connect fake harness that does not exist yet.
- `:core:database:testDebugUnitTest` has 19 pre-existing environment failures (`SQLiteCantOpenDatabaseException`), unchanged from the phase base.

### Phase 5: UI and presentation decomposition
*Risk: medium (visual regressions) · Behavior change: none intended · Est. 3–4 PRs*

| PR | Refactor | Files |
|---|---|---|
| 5.1 | Extract the shared Vico scaffolding (axes, cubic-Bezier line spec, area gradient, marker, M3 palette mapping) into `core:ui`, building on `rememberTrendCartesianChart` / `rememberTrendChartDecorations` | `core/ui/...`, `feature/sleep/.../SleepTrendChart.kt`, `SleepStagesChart.kt`, `feature/workouts/.../AcwrChart.kt` |
| 5.2 | Split `DashboardMetricPresentationFactory` (665 lines) into a separate factory per card family | `feature/dashboard/.../usecase/` |
| 5.3 | Slim `DashboardViewModel` (539 lines) by moving the remaining orchestration into use cases and delegates | `feature/dashboard/...` |

**Exit criteria**
- Compose UI tests (e.g. `DashboardScreenTest`) pass.
- Charts are checked manually on a device in light, dark and dynamic color.
- Every touched file is ≤ 400 lines.

**Phase 5 outcome (2026-11-06).**
- PR 5.1: Shared Vico chart scaffolding extracted to `core:ui` (`VicoLayers`, `BoundsTrackingMarker`, `ChartAutoDismiss`, `AxisItemPlacers`). `SleepTrendChart` dropped from 689 to 362 lines (with `SleepTrendRangeProviders` at 200 lines and `SleepTrendSummaryRow` at 68 lines). `SleepStagesChart` dropped from 682 to 218 lines (with `SleepStagesCanvasRenderer` at 326 lines, `SleepStagesLabelLayout` at 194 lines, and `SleepStagesAccessibility` at 147 lines). `AcwrChart` dropped from 626 to 268 lines (with `TsbChart` extracted at 375 lines, `AcwrChartRangeProviders` at 177 lines, `AcwrChartLegends` at 185 lines, `AcwrSelectedState` at 94 lines, and `TrainingLoadMetric` at 56 lines). All touched files are ≤ 400 lines.
- PR 5.2: `DashboardMetricPresentationFactory` (665 lines) decomposed into a clean facade (187 lines) orchestrating family factories: `DashboardRecoveryMetricPresentationFactory` (371 lines, leveraging helper `DashboardMetricPresentationHelpers` at 48 lines), `DashboardScoreMetricPresentationFactory` (211 lines), `DashboardVitalsMetricPresentationFactory` (246 lines), `DashboardSleepRhythmMetricPresentationFactory` (123 lines), and `DashboardBodyCompositionMetricPresentationFactory` (148 lines). All factories are ≤ 400 lines.
- PR 5.3: `DashboardViewModel` dropped from 539 to 240 lines. Extracted `DashboardUiState` (122 lines) into a standalone model file and `DashboardUiStateTransformer` (166 lines) for pure UI state mapping and presentation generation. `DashboardViewModel` retains only state flow orchestration and user intent handling.
- Baseline entries burned down:
  - `feature/dashboard`: 2 entries removed (`CyclomaticComplexMethod` and `LongMethod` for `DashboardMetricPresentationFactory.build`).
  - `feature/sleep`: 3 entries removed (`CyclomaticComplexMethod` for `SleepStagesChart` and `SleepTrendChart`, and `LongMethod` for `SleepTrendCard`).
  - `feature/workouts`: 1 entry removed (`CyclomaticComplexMethod` for `AcwrChart`).
  - Total: 6 baseline entries removed across the UI modules, with zero additions and zero new `@Suppress` annotations.
- Verification:
  - Pre-commit gate passed: `ktlintFormat`, `detekt`, `assembleDebug`, and `testDebugUnitTest` (all unit tests green, with the 19 pre-existing Windows SQLite environment failures in `:core:database:testDebugUnitTest` documented in Phase 4).
  - Release lint check passed: `lintRelease` passed with zero errors.
  - Every touched file in Phase 5 is ≤ 400 lines (hard limit ≤ 800 lines fully satisfied).

### Ongoing: detekt baseline burn-down
Apply the boyscout rule: every PR in Phases 0–5 removes the baseline entries for the methods it touches. Track the total entry count (about 205 today, 45 of them in `core:scoring`) at the end of each phase. The target is a monotonically decreasing count, with `LargeClass` and `LongParameterList` at zero after Phase 4.

### Phase overview

| Phase | Theme | Risk | Depends on | `DATA_FLOW.md` |
|---|---|---|---|---|
| 0 | Cancellation bug | Low | — | — |
| 1 | Error handling and hygiene | Low | 0 | — |
| 2 | Time and DI consistency | Low–Med | — | — |
| 3 | DRY: validation and chart-management delegate | Low–Med | 2 (`Clock` in validators) | — |
| 4 | Data-path SRP/DI splits | **High** | 1, 2 | Yes |
| 5 | UI decomposition | Medium | 3 | — |

## 8. Acceptance criteria for remediation PRs

- Pre-commit gate passes: `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest`, then `./gradlew lintRelease`.
- The total entry count in `*/detekt-baseline.xml` must not increase. No new `@Suppress` without explicit human approval.
- Required new unit tests:
  - cancellation propagation (item 1);
  - fixed-`Clock` determinism (item 2);
  - year-range boundaries 1899 / 1900 / current / next (item 4).
- Items 3 and 5 update `internal-docs/DATA_FLOW.md` in the same change.
- Run `codegraph index` / `codegraph sync` after any file is added or moved.
