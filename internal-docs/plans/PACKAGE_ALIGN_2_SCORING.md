# Package Alignment 2/4 — `core/scoring` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the 5 remaining flat-namespace packages in `core/scoring` (55 files) to the module-namespaced `app.readylytics.health.core.scoring.*` equivalents, one commit per package, zero behaviour change.

**Architecture:** Pure package moves, smallest package first. Existing tests are the regression net — no new tests (nothing behavioural changes; TDD does not apply). `domain.scoring/**` formulas are ALREADY renamed and are OFF-LIMITS here — this plan never touches `domain.scoring`, `domain.scoring.sleep`, `domain.scoring.components`, `domain.scoring.strategies`, `domain.util`, `domain.dashboard`, `domain.common`, or `di`.

**Tech Stack:** Kotlin, Gradle, Hilt, kotlinx.serialization.

**Source item:** `POST_REMEDIATION_FOLLOWUPS.md` Item 4, execution order step 2.

**Branch:** `feat/remediation-followup-p2`

---

## Preconditions

- [ ] Plan 1 (`PACKAGE_ALIGN_1_HEALTHCONNECT.md`) is complete and its final verification green.
- [ ] Working tree clean; baseline `./gradlew testDebugUnitTest` → 3,009 tests, 0 failures.

## Guardrails

- **Move-only diffs** (renames + `package`/`import` lines only). Any change to a rule/coefficient/formula body is a bug — revert it. Insight-rule classes (`*Rule.kt`) contain thresholds: the diff for them must be rename-only, doubly checked.
- **Never rewrite `^package ` lines outside `core/scoring`** — only `import` lines and inline FQN qualifiers elsewhere.
- **detekt baseline:** `core/scoring/detekt-baseline.xml` (86 entries). On failure after a move, edit the matching `<ID>` signature per `DETEKT_BASELINE_BURNDOWN.md` §5 — never regenerate.
- **Architecture tests need no edits:** `app.readylytics.health.core.scoring.domain..` is already in `CleanArchTest.domainPackageGlobs` (`CleanArchTest.kt:13`).
- **Doc sync:** `internal-docs/DATA_FLOW.md` must be updated in the same commits (Task 7 handles it once, then rides along).

## Package inventory (measured 2026-08-20)

| Task | Current package | Target | Files |
|---|---|---|---:|
| 1 | `domain.health` (test-only) | `core.scoring.domain.health` | 1 |
| 2 | `domain.calculation` | `core.scoring.domain.calculation` | 2 |
| 3 | `domain.insights.detail` | `core.scoring.domain.insights.detail` | 3 |
| 4 | `domain.airecommendation` | `core.scoring.domain.airecommendation` | 13 |
| 5 | `domain.insights` | `core.scoring.domain.insights` | 36 |

Known external importers of these packages (imports only; verify by grep, do not trust this list):
`app/src/main/kotlin/app/readylytics/health/ui/scaffold/MainNavHost.kt`,
`feature/dashboard/src/main/.../DashboardCardFactory.kt`, `DashboardScreen.kt`, `DashboardViewModel.kt`,
`feature/dashboard/src/test/.../DashboardViewModelTest.kt`,
`feature/insights/src/main/.../InsightDetailRepository.kt`, `InsightDetailResourceSpec.kt`, `InsightDetailSheet.kt`,
`feature/insights/src/test/.../InsightDetailResourceSpecTest.kt`.

## Standard rename procedure — run once per task below

Substitute `OLD`/`NEW` per the task's table row. Steps 1–14:

1. **Enumerate:** `grep -rl "^package app\.readylytics\.health\.$OLD\$" core/scoring/src --include='*.kt' | sort` — confirm the file count matches the table.
2. **Move each source set:** for each of `main`, `test` that contains the dir:
   `git mv core/scoring/src/<set>/kotlin/app/readylytics/health/$OLD_PATH core/scoring/src/<set>/kotlin/app/readylytics/health/core/scoring/$NEW_SUBPATH`
   (e.g. `OLD_PATH=domain/insights`, `NEW_SUBPATH=domain/insights`; `app/readylytics/health/core/scoring/` already exists in both trees.)
3. **Package lines (moved files only):**
   `... | xargs sed -i '' "s/^package app\.readylytics\.health\.$OLD\$/package app.readylytics.health.core.scoring.$NEW_SUBPATH/"`
4. **Import lines repo-wide:**
   `grep -rl "^import app\.readylytics\.health\.$OLD\." app core feature database-benchmark --include='*.kt' | grep -v '/build/' | xargs sed -i '' "s/^import app\.readylytics\.health\.$OLD\./import app.readylytics.health.core.scoring.$NEW_SUBPATH./"`
5. **Inline FQN usages:** `grep -rn "app\.readylytics\.health\.$OLD\." app core feature --include='*.kt' | grep -v '/build/' | grep -v 'core/scoring/src'` — rewrite each qualifier to the new package. Expect zero or very few.
6. **Compile:** `./gradlew :core:scoring:compileDebugKotlin :app:compileDebugKotlin` → BUILD SUCCESSFUL.
7. **Sweep:** `grep -rn "app\.readylytics\.health\.$OLD" app core feature database-benchmark --include='*.kt' | grep -v '/build/'` → empty.
8. **DATA_FLOW.md:** replace path segment `core/scoring/src/<set>/kotlin/app/readylytics/health/$OLD_PATH/` → `core/scoring/src/<set>/kotlin/app/readylytics/health/core/scoring/$NEW_SUBPATH/` for `<set>` ∈ {main, test}.
9. `./gradlew ktlintFormat`
10. `./gradlew :core:scoring:detekt` (baseline-signature fix only, per §5)
11. `./gradlew testDebugUnitTest` → 0 failures, count still 3,009.
12. `codegraph sync`
13. Review `git diff --cached -M --stat` after `git add -A` — rename-only + package/import lines. Any `*Rule.kt` body change: revert.
14. Commit: `refactor: align core/scoring <OLD> package with module namespace`

---

## Task 1: `domain.health` → `core.scoring.domain.health` (1 file, test-only)

- [ ] Run procedure with `OLD=domain.health`, `NEW_SUBPATH=domain/health`.

File: `core/scoring/src/test/kotlin/app/readylytics/health/domain/health/HealthMetricsCalculatorTest.kt` (tests `HealthMetricsCalculator`, which lives in `domain.calculation` — that class is renamed in Task 2, not here; the test's import of it updates in Task 2's Step 4).

## Task 2: `domain.calculation` → `core.scoring.domain.calculation` (2 files)

- [ ] Run procedure with `OLD=domain.calculation`, `NEW_SUBPATH=domain/calculation`.

Files: `HealthMetricsCalculator.kt` (main), `HeartRateCalculationUtilTest.kt` (test). DATA_FLOW.md references `core/scoring/src/main/kotlin/app/readylytics/health/domain/calculation/HealthMetricsCalculator.kt` — Step 8 covers it.

## Task 3: `domain.insights.detail` → `core.scoring.domain.insights.detail` (3 files)

- [ ] Run procedure with `OLD=domain.insights.detail`, `NEW_SUBPATH=domain/insights/detail`.

Files: `DailyInsightContext.kt`, `InsightCauseRanker.kt`, `InsightDetailModels.kt` (all main).

## Task 4: `domain.airecommendation` → `core.scoring.domain.airecommendation` (13 files)

- [ ] Run procedure with `OLD=domain.airecommendation`, `NEW_SUBPATH=domain/airecommendation`.

Files: 7 main (`AdvisorDataConfidence.kt`, `ComputeWorkoutPatternSummaryUseCase.kt`, `DailyPromptData.kt`, `DailyPromptFormatter.kt`, `GetDailyPromptDataUseCase.kt`, `RecommendedLoadCalculator.kt`, `RecoveryFlagGlossary.kt`) + 6 tests. External importers: `MainNavHost.kt` and the `feature/dashboard` files.

## Task 5: `domain.insights` → `core.scoring.domain.insights` (36 files)

- [ ] Run procedure with `OLD=domain.insights`, `NEW_SUBPATH=domain/insights`.

19 main files (`InsightEngine.kt`, `InsightContext.kt`, `InsightRule.kt`, `InsightConstants.kt`, `InsightFinding.kt`, 14 `*Rule.kt` rule files) + 17 test files (`InsightEngineTest.kt`, `InsightTestFixtures.kt`, `ReadinessCalculationTest.kt`, 14 `*RuleTest.kt`).
- [ ] **Extra check for this task:** every `*Rule.kt` and `ReadinessCalculationTest.kt` diff must be rename/import-only — these contain scoring-adjacent thresholds that are off-limits. `git diff --cached -M -- '*/insights/*Rule.kt'` and eyeball each hunk.
- [ ] After Task 5 the `domain.insights.detail` package (renamed in Task 3) must already live under `core/scoring/domain/insights/detail` — moving `domain/insights` in this task must NOT clobber it: git mv the remaining `domain/insights/*.kt` files into the existing new dir, and remove the empty old dir only after both moves (`rmdir` fails loudly if non-empty — good).

## Task 6: DATA_FLOW.md doc-sync for pre-existing stale scoring paths

`internal-docs/DATA_FLOW.md` still lists paths under `core/scoring/src/main/kotlin/app/readylytics/health/domain/{scoring,scoring/...,util,dashboard,common}/` and `core/scoring/src/main/kotlin/app/readylytics/health/di/` — packages already renamed in earlier commits on this branch. Fix them now, in the same plan that last touches the scoring section:

- [ ] Update every stale `core/scoring/src/` path in `internal-docs/DATA_FLOW.md` to the current on-disk path (verify each with `ls`/`glob`; the mapping is `health/domain/X` → `health/core/scoring/domain/X` and `health/di` → `health/core/scoring/di`).
- [ ] `grep -n 'core/scoring/src' internal-docs/DATA_FLOW.md` — every referenced path must exist on disk. Spot-check 5 with `test -f`.
- [ ] Commit: `docs: refresh core/scoring paths in DATA_FLOW.md after package alignment`

## Final verification

- [ ] `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — green, 3,009 tests, 0 lint warnings.
- [ ] `grep -rl '^package app\.readylytics\.health\.domain\.' core/scoring/src` → empty.
- [ ] `grep -rn 'app\.readylytics\.health\.domain\.\(insights\|airecommendation\|calculation\|health\)' app core feature database-benchmark --include='*.kt' | grep -v '/build/'` → empty.
