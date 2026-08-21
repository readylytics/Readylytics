# Align `core/scoring` Packages With Its Module — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename `core/scoring`'s five spanning packages (`di`, `domain.dashboard`, `domain.util`,
`domain.scoring` with its `.components`/`.sleep`/`.strategies` subpackages, `domain.common`) so
each is prefixed with `app.readylytics.health.core.scoring`.

**Architecture:** Five independent package renames via IDE refactor, ordered smallest first.
`domain.scoring` is last and by far the largest (55 files including subpackages) and the riskiest,
because `core/database`'s test source set has its own `domain.scoring` (and `domain.scoring.golden`)
package containing 19 test files that reach `core/scoring`'s classes via same-package implicit
resolution rather than explicit imports.

**Tech Stack:** Kotlin, Konsist. This module is pure-Kotlin domain/scoring logic — no Android
dependencies (per `.claude/CLAUDE.md`'s "Logic Isolation" rule) and no DI framework runtime
resolution risk beyond Hilt's own `@Module`/`@Binds` KSP processing.

**Spec:** `internal-docs/plans/POST_REMEDIATION_FOLLOWUPS.md` (Item 4, lines 337-397) and
`docs/superpowers/plans/2026-08-19-package-module-alignment-index.md` (sequencing, shared safety
verification, naming convention).

## Global Constraints

- Full gate before closing this plan: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease`.
- Baseline: 3,009 unit tests, 0 failures, 0 lint warnings (2026-08-18).
- **Scoring math is off-limits.** Every file this plan touches is inside `domain/scoring/**` —
  the exact directory the source spec calls out as forbidden to change formulas/coefficients in.
  This plan changes `package`/`import` lines only. If the IDE refactor's diff on any file shows
  anything beyond a `package` line, an `import` line, or a fully-qualified reference being
  shortened/lengthened to match the new package, stop and investigate before proceeding — that is
  not a rename anymore.
- **Never regenerate golden fixtures.** `core/database/src/test/resources/golden/` (fixture *data*
  files) is untouched by this plan — it is a different directory from the `domain.scoring.golden`
  *Kotlin test code* package this plan's Task 4 must account for (see Task 4's Files section). Do
  not run any "regenerate golden" script/task as part of this plan, even if a test failure tempts
  it — a failure here means the rename broke a reference, not that the fixture is stale.
- Rename via IDE "Refactor → Rename → Rename package", not `sed`.
- Run `codegraph sync` after each task.

## File Structure

Five packages/subtrees, 61 `src/main` files total:

| Package | `src/main` files | `src/test` files | Target |
|---|--:|--:|---|
| `di` | 2 (`ScoringBindsModule.kt`, `ScoringModule.kt`) | 0 | `core.scoring.di` |
| `domain.dashboard` | 1 (`InsightDeriver.kt`) | 1 (`InsightDeriverTest.kt`) | `core.scoring.domain.dashboard` |
| `domain.util` | 2 (`HeartRateFormulas.kt`, `MathUtils.kt`) | 1 (`MathUtilsTest.kt`) | `core.scoring.domain.util` |
| `domain.scoring` (+ `.components`, `.sleep`, `.strategies`) | 55 | 32 (flat) + 4 (`.sleep`) + 9 (`.components`) + 1 (`.strategies`) = 46 | `core.scoring.domain.scoring` (subtree) |
| `domain.common` | 1 (`ScoringConfigValidator.kt`) | 0 | `core.scoring.domain.common` |

All paths rooted at `core/scoring/src/main/kotlin/app/readylytics/health/` (or `src/test/`).

**`domain.scoring` subtree detail:**
- Flat (30 `src/main`): `AssembleDailySummaryUseCase.kt`, `AssembleEverydayLoadInputUseCase.kt`,
  `BackfillHistoricalBaselinesUseCase.kt`, `BaselineComputer.kt`, `BuildLoadSeriesUseCase.kt`,
  `CircadianConsistencyRepository.kt`, `CompositeScoringCalculator.kt`,
  `ComputeDailyTrimpUseCase.kt`, `ComputeHistoricalBaselinesUseCase.kt`,
  `ComputeSleepMetricsUseCase.kt`, `ComputeWorkoutLoadMetricsUseCase.kt`,
  `ComputeWorkoutTrimpUseCase.kt`, `DailyRasIncrease.kt`, `DailyStrainIncrease.kt`,
  `EverydayHeartRateLoadCalculator.kt`, `GetWorkoutDisplayMetricsUseCase.kt`,
  `HistoricalSleepDayAssembler.kt` (has an `internal`-visibility declaration — irrelevant to this
  rename since Kotlin `internal` is module-scoped, not package-scoped, and this file is not
  moving to a different Gradle module, only a different package within the same module),
  `HrMaxProvider.kt`, `HrvBaselineProvider.kt`, `RasCalculator.kt`, `RasProvider.kt`,
  `RasSourceModeBootstrapUseCase.kt`, `ReadinessDiagnostics.kt`, `ResolveDailyBaselinesUseCase.kt`,
  `RhrBaselineProvider.kt`, `ScoringCalculator.kt`, `ScoringConfig.kt`, `ScoringConfigFactory.kt`,
  `TrimpDateBucketer.kt`, `WorkoutLoadClassifier.kt`.
- `.components/` (12): `AuditTrail.kt`, `AuditTrailFactory.kt`, `CircadianConsistencyConfig.kt`,
  `ConfidenceLevel.kt`, `EmergencyFlagThresholds.kt`, `MaxHeartRateCalculator.kt`, `Phase.kt`,
  `PhaseCalculator.kt`, `RestorationWeights.kt`, `SleepArchitectureTargetFactory.kt`,
  `SleepArchitectureTargets.kt`, `SleepContinuityCurves.kt`.
- `.sleep/` (10): `CurrentNightHrvResolver.kt`, `HrCoverageValidator.kt`, `SleepDayAggregator.kt`,
  `SleepDayModels.kt`, `SleepFragmentationCalculator.kt`, `SleepModifierResolver.kt`,
  `SleepNadirAnalyzer.kt`, `SleepPercentileRhrCalculator.kt`, `SleepTrendDay.kt`,
  `SleepTrendDayAssembler.kt`.
- `.strategies/` (3): `LoadScoringStrategy.kt`, `RasScoringStrategy.kt`, `SleepScoringStrategy.kt`.
- (30 + 12 + 10 + 3 = 55, matching the spec's measured count for this package.)

**Cross-module test coupling — read before Task 4:** `core/database/src/test/kotlin/app/readylytics/health/domain/scoring/`
contains 19 files (`BackfillBaselinesUseCaseTest.kt`, `BaselineComputerBackfillEquivalenceTest.kt`,
`BaselineComputerN1FixTest.kt`, `BaselineComputerWalkForwardEquivalenceTest.kt`,
`ScoringDeterminismRegressionTest.kt`, `ScoringPointInTimeRegressionTest.kt`,
`ScoringRepositoryN1Test.kt`, `ScoringSyncScopeOutputsDeterminismTest.kt`,
`SyncScopeDeterminismTest.kt`, `WalkForwardDeterminismTest.kt`, plus a `golden/` subpackage —
`GoldenFixtureDataBuilder.kt`, `GoldenFixtureDataBuilderTest.kt`, `GoldenFixtureTestFakes.kt`,
`GoldenFixtureWalkForwardTest.kt`, `ScoringEquivalenceGoldenTest.kt`, `ScoringGoldenSnapshotTest.kt`,
`SyntheticDatasetGenerator.kt`, `SyntheticDatasetGeneratorTest.kt`,
`WalkForwardTransactionEquivalenceTest.kt` — and a `sleep/` file, `SleepMetricsHelpersTest.kt`).
These 19 files declare `package app.readylytics.health.domain.scoring` (and `.golden`, `.sleep`)
in the **`core/database`** module, not this one, and reference this module's classes (e.g.
`BaselineComputer`, `ScoringConfigFactory`) by *same-package implicit resolution* — no explicit
`import` today. **These files are not moved by this plan** (they belong to `core/database`'s
plan-file territory only in the sense that they live in that module; they are not one of that
module's 7 flagged packages either, since this exact coupling pattern was not part of the
measured 16-row table — it is a consequence of it). After Task 4 renames `core/scoring`'s
`domain.scoring` package, IntelliJ's rename refactor inserts the necessary new `import` lines into
these 19 `core/database` test files automatically, because "Search in comments and strings" +
project-wide reference search covers same-package call sites too. Task 4, Step 3 explicitly
verifies these 19 files still compile.

## Task 1: Rename `di` → `core.scoring.di`

**Files:**
- Move: `di/ScoringBindsModule.kt`, `di/ScoringModule.kt`.

**Interfaces:**
- Produces: `app.readylytics.health.core.scoring.di.{ScoringBindsModule,ScoringModule}`.

- [ ] **Step 1: Baseline.** Run: `./gradlew :core:scoring:testDebugUnitTest` — Expected: PASS.
- [ ] **Step 2: Rename.** Right-click `core/scoring/src/main/kotlin/app/readylytics/health/di` →
  Refactor → Rename → "Rename package" → `app.readylytics.health.core.scoring.di` → both search
  options checked → Preview (confirm exactly 2 files) → apply.
- [ ] **Step 3: Sweep.** Run: `grep -rn "app\.readylytics\.health\.di\.\(ScoringBindsModule\|ScoringModule\)" --include="*.kt" . | grep -v /build/` — Expected: no output.
- [ ] **Step 4: `codegraph sync`.**
- [ ] **Step 5: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [ ] **Step 6: Commit.**
```bash
git add -A -- 'core/scoring/src/main/kotlin/app/readylytics/health/core' ':(glob)**/*.kt'
git commit -m "refactor: align core/scoring di package with module namespace"
```

## Task 2: Rename `domain.dashboard` → `core.scoring.domain.dashboard`

**Files:**
- Move: `domain/dashboard/InsightDeriver.kt` and its test `InsightDeriverTest.kt`.

**Interfaces:**
- Produces: `app.readylytics.health.core.scoring.domain.dashboard.InsightDeriver`.
- Note: distinct from `core/model`'s and `feature/dashboard`'s own `domain.dashboard` packages
  (handled by their respective module plans) — no collision after all three land.

- [ ] **Step 1: Baseline.** Run: `./gradlew :core:scoring:testDebugUnitTest` — Expected: PASS.
- [ ] **Step 2: Rename.** Right-click `core/scoring/src/main/kotlin/app/readylytics/health/domain/dashboard`
  → Refactor → Rename → "Rename package" → `app.readylytics.health.core.scoring.domain.dashboard`
  → both search options checked → Preview (confirm 1 `src/main` + 1 `src/test` file) → apply.
- [ ] **Step 3: Sweep.** Run: `grep -rn "app\.readylytics\.health\.domain\.dashboard\.InsightDeriver" --include="*.kt" . | grep -v /build/` — Expected: no output.
- [ ] **Step 4: `codegraph sync`.**
- [ ] **Step 5: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [ ] **Step 6: Commit.**
```bash
git add -A -- 'core/scoring/src/main/kotlin/app/readylytics/health/core' 'core/scoring/src/test/kotlin/app/readylytics/health/core' ':(glob)**/*.kt'
git commit -m "refactor: align core/scoring domain.dashboard package with module namespace"
```

## Task 3: Rename `domain.util` and `domain.common` → `core.scoring.domain.util` / `.common`

**Files:**
- Move: `domain/util/HeartRateFormulas.kt`, `domain/util/MathUtils.kt`, test `MathUtilsTest.kt`;
  `domain/common/ScoringConfigValidator.kt`.

**Interfaces:**
- Produces: `app.readylytics.health.core.scoring.domain.util.{HeartRateFormulas,MathUtils}`,
  `app.readylytics.health.core.scoring.domain.common.ScoringConfigValidator`.
- Note: `MathUtils.kt` likely declares top-level extension functions (e.g. `stdev`, referenced by
  FQN-free call sites in same-package files) rather than only a class — the IDE package rename
  handles top-level function references identically to class references, but double-check the
  Step 3 sweep covers the function name too, not just a class name.

- [ ] **Step 1: Baseline.** Run: `./gradlew :core:scoring:testDebugUnitTest` — Expected: PASS.
- [ ] **Step 2: Rename `domain.util`.** Right-click
  `core/scoring/src/main/kotlin/app/readylytics/health/domain/util` → Refactor → Rename →
  "Rename package" → `app.readylytics.health.core.scoring.domain.util` → both search options
  checked → Preview (confirm 2 `src/main` + 1 `src/test` file) → apply.
- [ ] **Step 3: Rename `domain.common`.** Right-click
  `core/scoring/src/main/kotlin/app/readylytics/health/domain/common` → Refactor → Rename →
  "Rename package" → `app.readylytics.health.core.scoring.domain.common` → both search options
  checked → Preview (confirm exactly 1 file) → apply.
- [ ] **Step 4: Sweep.** Run:
  `grep -rn "app\.readylytics\.health\.domain\.util\.\(HeartRateFormulas\|MathUtils\)\|app\.readylytics\.health\.domain\.common\.ScoringConfigValidator" --include="*.kt" . | grep -v /build/`
  — Expected: no output.
- [ ] **Step 5: `codegraph sync`.**
- [ ] **Step 6: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [ ] **Step 7: Commit.**
```bash
git add -A -- 'core/scoring/src/main/kotlin/app/readylytics/health/core' 'core/scoring/src/test/kotlin/app/readylytics/health/core' ':(glob)**/*.kt'
git commit -m "refactor: align core/scoring domain.util and domain.common packages with module namespace"
```

## Task 4: Rename `domain.scoring` (whole subtree) → `core.scoring.domain.scoring`

**Files:**
- Move: all 55 `src/main` files (30 flat + 12 `.components` + 10 `.sleep` + 3 `.strategies`) and
  all 46 `src/test` files (32 flat + 9 `.components` + 4 `.sleep` + 1 `.strategies`) listed in the
  File Structure section.
- Modify (imports only, rewritten automatically, including cross-module implicit-access fixups):
  the 19 `core/database`-module test files listed in the "Cross-module test coupling" note above.

**Interfaces:**
- Produces: `app.readylytics.health.core.scoring.domain.scoring.*` for all 30 flat classes plus
  `.components.*`, `.sleep.*`, `.strategies.*` for their respective 12/10/3 classes — every simple
  name unchanged (e.g. `BaselineComputer`, `ScoringConfigFactory`, `LoadScoringStrategy`).
- Consumes: nothing new — this is the last task in this plan.

- [ ] **Step 1: Baseline — cover every consumer module found in the audit.** Run:
  `./gradlew :core:scoring:testDebugUnitTest :core:database:testDebugUnitTest :core:healthconnect:testDebugUnitTest :core:model:testDebugUnitTest :database-benchmark:compileDebugKotlin :feature:dashboard:testDebugUnitTest :feature:onboarding:testDebugUnitTest :feature:settings:testDebugUnitTest :feature:sleep:testDebugUnitTest :feature:workouts:testDebugUnitTest :app:testDebugUnitTest`
  — Expected: PASS. This module list comes directly from the 2026-08-19
  `grep -rl "import app.readylytics.health.domain.scoring\."` consumer sweep.
- [ ] **Step 2: Rename, one directory node, whole subtree.** In the IDE, right-click the
  `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring` package node (this
  selects `.components`, `.sleep`, `.strategies` along with it — package rename in
  IntelliJ/Android Studio always operates on the full subtree under the selected node) →
  Refactor → Rename → "Rename package" → `app.readylytics.health.core.scoring.domain.scoring` →
  both search options checked → Preview. **Read the preview list carefully before applying**: it
  must show 55 `src/main` files moving, and it must list the 19 `core/database` test files (and
  any `core/database` `.golden`/`.sleep` test-package files) under "usages to update" — if any of
  those 19 are missing from the preview, do not apply; instead open each missing file and add the
  explicit `import` for whichever class it uses from this package before re-running the rename, so
  the refactor's reference search can find it. Apply once the preview looks complete.
- [ ] **Step 3: Explicitly verify the 19 cross-module `core/database` test files still compile.**
  Run: `./gradlew :core:database:testDebugUnitTest`
  Expected: PASS, same test count in this module as the Step 1 baseline. This is the single
  highest-risk verification point in this entire plan — these are the files most likely to have a
  same-package implicit reference the rename's static search missed, because they are Kotlin
  language name resolution relying on package co-location across two different Gradle modules'
  source sets, not a plain FQN string match.
- [ ] **Step 4: Sweep for stale references to the 30 flat class names.** Run:
  `grep -rn "app\.readylytics\.health\.domain\.scoring\.\(AssembleDailySummaryUseCase\|AssembleEverydayLoadInputUseCase\|BackfillHistoricalBaselinesUseCase\|BaselineComputer\|BuildLoadSeriesUseCase\|CircadianConsistencyRepository\|CompositeScoringCalculator\|ComputeDailyTrimpUseCase\|ComputeHistoricalBaselinesUseCase\|ComputeSleepMetricsUseCase\|ComputeWorkoutLoadMetricsUseCase\|ComputeWorkoutTrimpUseCase\|DailyRasIncrease\|DailyStrainIncrease\|EverydayHeartRateLoadCalculator\|GetWorkoutDisplayMetricsUseCase\|HistoricalSleepDayAssembler\|HrMaxProvider\|HrvBaselineProvider\|RasCalculator\|RasProvider\|RasSourceModeBootstrapUseCase\|ReadinessDiagnostics\|ResolveDailyBaselinesUseCase\|RhrBaselineProvider\|ScoringCalculator\|ScoringConfig\|ScoringConfigFactory\|TrimpDateBucketer\|WorkoutLoadClassifier\)" --include="*.kt" . | grep -v /build/`
  — Expected: no output. (`core/model`'s *different*, 5-file `domain.scoring` package — e.g.
  `LoadCoverageConfidence`, `SleepScoreWeightProfile` — is not in this name list and is
  intentionally not checked here; it is out of scope until the `core/model` plan runs.)
- [ ] **Step 5: `codegraph sync`.**
- [ ] **Step 6: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings, and re-confirm the golden-fixture tests specifically:
  `./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.domain.scoring.golden.*"`
  must still pass with the exact same assertions as before this task (no fixture regenerated).
- [ ] **Step 7: Commit.**
```bash
git add -A -- 'core/scoring/src/main/kotlin/app/readylytics/health/core' 'core/scoring/src/test/kotlin/app/readylytics/health/core' 'core/database/src/test/kotlin/app/readylytics/health/domain/scoring' ':(glob)**/*.kt'
git commit -m "refactor: align core/scoring domain.scoring package (and subpackages) with module namespace"
```

## Verification

Every module listed in Task 4 Step 1's baseline, green, plus the full gate green. On-device:
`./gradlew installDebug`, open Dashboard/Sleep/Vitals/Workouts screens and confirm scores render
identically to before this plan (Readiness, Sleep Score, Load Score) — this is a pure rename, so
any visible score change is a bug in this plan's execution, not an expected side effect.
