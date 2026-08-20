# Package Alignment 4/4 — `core/model` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the 21 remaining flat-namespace `domain.*` packages in `core/model` (179 files: 129 main + 50 test) to `core.model.domain.*` equivalents, plus 3 stray test files in `core/model/src/test/.../data/healthconnect/`, completing the flat-namespace elimination in `core/*`. One commit per package, zero behaviour change.

**Architecture:** Pure package moves, smallest package first, `domain.model` LAST (313 external importing files — the widest blast radius in the repo). Existing tests are the regression net; no new tests, TDD does not apply. NOTE: `domain.model` here is pure data/domain classes — it is NOT `domain/scoring/**` and contains no scoring math.

**Tech Stack:** Kotlin, kotlinx.serialization (`@Serializable` classes in `domain.sleep`, `domain.workouts`, `domain.workouts.detail`, `domain.vitals`, `domain.layout`), Hilt, Gradle.

**Source item:** `POST_REMEDIATION_FOLLOWUPS.md` Item 4, execution order step 4 (largest, scheduled alone).

**Branch:** `feat/remediation-followup-p2`

---

## Preconditions

- [x] Plans 1–3 complete and green.
- [x] Working tree clean; `./gradlew testDebugUnitTest` → 3,009 tests, 0 failures.

## Guardrails

- **Move-only diffs.** `domain.validation/**` contains validation logic imported across the entire project — its diffs must be rename + package/import only. Any logic change: revert.
- **Never rewrite `^package ` lines outside `core/model`.** External importers number in the hundreds (e.g. `domain.model` ≈ 313 files, `domain.preferences` ≈ 156, `domain.repository` ≈ 145) — only their `import` lines and inline FQN qualifiers change. The `app` module has its own flat-ns packages (`app/.../health/domain/migration/`, `app/.../health/data/**`) that are NOT part of this plan and must keep their names.
- **kotlinx.serialization:** `@Serializable` classes' generated serializers are referenced via imports and plugin-generated code — the compiler catches misses. Do not hand-edit generated sources; `git status` must show nothing under `core/model/build/`.
- **detekt baseline:** `core/model/detekt-baseline.xml` (54 entries). Fix failing `<ID>`s per `DETEKT_BASELINE_BURNDOWN.md` §5 by editing the entry; never regenerate.
- **Architecture tests need no edits:** `app.readylytics.health.core.model.domain..` and `app.readylytics.health.core.model.data.` are already in `CleanArchTest`'s lists (`CleanArchTest.kt:12`, `:24`).
- **Doc sync:** `internal-docs/DATA_FLOW.md` references many `core/model/src/.../health/domain/...` paths — update in the same commits (per-task Step 8 plus Task 24).
- **Known real inline-FQN usages (NOT imports) that Step 5 must rewrite:** worker tests qualify `app.readylytics.health.domain.model.Result` inline — `app/src/test/.../workers/PeriodicHealthSyncWorkerTest.kt` (3 sites) and `app/src/test/.../workers/HealthResyncWorkerTest.kt` (8+ sites). There will be others; the sweep step finds them.

## Package inventory (measured 2026-08-20; counts include main + test)

| Task | Current package                                 | Target                              | Files |
| ---- | ----------------------------------------------- | ----------------------------------- | ----: |
| 1    | `domain.logcat`                                 | `core.model.domain.logcat`          |     1 |
| 2    | `domain.error`                                  | `core.model.domain.error`           |     2 |
| 3    | `domain.display`                                | `core.model.domain.display`         |     2 |
| 4    | `domain.cache`                                  | `core.model.domain.cache`           |     2 |
| 5    | `domain.circadian`                              | `core.model.domain.circadian`       |     3 |
| 6    | `domain.audit`                                  | `core.model.domain.audit`           |     3 |
| 7    | `domain.githubissue`                            | `core.model.domain.githubissue`     |     3 |
| 8    | `domain.crashreport`                            | `core.model.domain.crashreport`     |     3 |
| 9    | `domain.vitals`                                 | `core.model.domain.vitals`          |     3 |
| 10   | `domain.date`                                   | `core.model.domain.date`            |     4 |
| 11   | `domain.heartrate`                              | `core.model.domain.heartrate`       |     4 |
| 12   | `domain.preferences`                            | `core.model.domain.preferences`     |     5 |
| 13   | `domain.backup`                                 | `core.model.domain.backup`          |     6 |
| 14   | `domain.layout`                                 | `core.model.domain.layout`          |     6 |
| 15   | `domain.workouts`                               | `core.model.domain.workouts`        |     6 |
| 16   | `domain.workouts.detail`                        | `core.model.domain.workouts.detail` |     7 |
| 17   | `domain.sleep`                                  | `core.model.domain.sleep`           |    12 |
| 18   | `domain.service`                                | `core.model.domain.service`         |    14 |
| 19   | `domain.repository`                             | `core.model.domain.repository`      |    16 |
| 20   | `domain.validation`                             | `core.model.domain.validation`      |    27 |
| 21   | `domain.model`                                  | `core.model.domain.model`           |    47 |
| 22   | `data.healthconnect` (test-only, in core/model) | `core.model.domain.sync.mappers`    |     3 |
| 23   | DATA_FLOW.md sweep + legacy-namespace narrowing | —                                   |     — |

Note: `domain.workouts.detail` (Task 16) must be renamed BEFORE `domain.workouts` (Task 15) would absorb its parent dir — but the table order renames the parent first, so instead: when running Task 15, move only the files directly in `domain/workouts/` (NOT the `detail/` subdir) into the new `core/model/domain/workouts/` dir; the `detail/` subdir moves in Task 16. Alternatively swap order (detail first) — either works, do not let `git mv` of the parent drag `detail/` along un-renamed.

## Standard rename procedure — run once per task below

1. **Enumerate:** `grep -rl "^package app\.readylytics\.health\.$OLD\$" core/model/src --include='*.kt' | sort` — confirm count matches the table (main + test).
2. **Move:** for each source set containing the dir (all are `main` and/or `test`; no androidTest):
   `git mv core/model/src/<set>/kotlin/app/readylytics/health/$OLD_PATH core/model/src/<set>/kotlin/app/readylytics/health/core/model/$NEW_SUBPATH`
   (`app/readylytics/health/core/model/` already exists in both trees; some subpaths already exist — move file-by-file into the existing target dir when it does.)
3. **Package lines (moved files only):**
   `... | xargs sed -i '' "s/^package app\.readylytics\.health\.$OLD\$/package app.readylytics.health.core.model.$NEW_SUBPATH/"`
4. **Import lines repo-wide:**
   `grep -rl "^import app\.readylytics\.health\.$OLD\." app core feature database-benchmark --include='*.kt' | grep -v '/build/' | xargs sed -i '' "s/^import app\.readylytics\.health\.$OLD\./import app.readylytics.health.core.model.$NEW_SUBPATH./"`
   ⚠️ For Task 21 (`domain.model`), the importer list is ~313 files — let the grep enumerate them; never hand-pick.
5. **Inline FQN usages:** `grep -rn "app\.readylytics\.health\.$OLD\." app core feature database-benchmark --include='*.kt' | grep -v '/build/' | grep -v ':import ' | grep -v '^core/model/src'` — rewrite each inline qualifier (e.g. `app.readylytics.health.domain.model.Result`) to the new package. Expect a handful per task, concentrated in worker tests.
6. **Compile:** `./gradlew :core:model:compileDebugKotlin :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` → BUILD SUCCESSFUL.
7. **Sweep:** `grep -rn "app\.readylytics\.health\.$OLD\." app core feature database-benchmark --include='*.kt' --include='*.kts' | grep -v '/build/'` → empty. (Hits in `app/src/main/kotlin/app/readylytics/health/domain/...` and `app/.../health/data/...` are the app module's OWN packages — those package declarations are untouched; only references to core classes matter. Disambiguate by directory: a hit is a remnant only if it references a class that lives in `core/model`.)
8. **DATA_FLOW.md:** replace path segment `core/model/src/<set>/kotlin/app/readylytics/health/$OLD_PATH/` → `.../health/core/model/$NEW_SUBPATH/` for all source sets.
9. `./gradlew ktlintFormat`
10. `./gradlew :core:model:detekt` (§5-style baseline fix only)
11. `./gradlew testDebugUnitTest` → 0 failures, count still 3,009.
12. `codegraph sync`
13. Review `git diff --cached -M --stat`: renames + package/import lines only.
14. Commit: `refactor: align core/model <OLD> package with module namespace`

---

## Tasks 1–11: small packages (≤ 4 files each)

- [x] Task 1: procedure with `OLD=domain.logcat`, `NEW_SUBPATH=domain/logcat` (1 file).
- [x] Task 2: `OLD=domain.error`, `NEW_SUBPATH=domain/error` (2).
- [x] Task 3: `OLD=domain.display`, `NEW_SUBPATH=domain/display` (2).
- [x] Task 4: `OLD=domain.cache`, `NEW_SUBPATH=domain/cache` (2).
- [x] Task 5: `OLD=domain.circadian`, `NEW_SUBPATH=domain/circadian` (3).
- [x] Task 6: `OLD=domain.audit`, `NEW_SUBPATH=domain/audit` (3).
- [x] Task 7: `OLD=domain.githubissue`, `NEW_SUBPATH=domain/githubissue` (3).
- [x] Task 8: `OLD=domain.crashreport`, `NEW_SUBPATH=domain/crashreport` (3).
- [x] Task 9: `OLD=domain.vitals`, `NEW_SUBPATH=domain/vitals` (3, includes `@Serializable` `VitalsChartConfiguration.kt`).
- [x] Task 10: `OLD=domain.date`, `NEW_SUBPATH=domain/date` (4).
- [x] Task 11: `OLD=domain.heartrate`, `NEW_SUBPATH=domain/heartrate` (4 — includes `ZoneThresholds.kt`, referenced by DATA_FLOW.md).

## Tasks 12–18: medium packages (5–14 files)

- [x] Task 12: `OLD=domain.preferences`, `NEW_SUBPATH=domain/preferences` (5; ~156 external importers — Step 4's grep enumerates them).
- [x] Task 13: `OLD=domain.backup`, `NEW_SUBPATH=domain/backup` (6).
- [x] Task 14: `OLD=domain.layout`, `NEW_SUBPATH=domain/layout` (6, `@Serializable` configs).
- [x] Task 15: `OLD=domain.workouts`, `NEW_SUBPATH=domain/workouts` (6 — see the workouts.detail ordering note in the inventory; move only the files directly in `domain/workouts/`).
- [x] Task 16: `OLD=domain.workouts.detail`, `NEW_SUBPATH=domain/workouts/detail` (7, `@Serializable` configs).
- [x] Task 17: `OLD=domain.sleep`, `NEW_SUBPATH=domain/sleep` (12, several `@Serializable` configs + the sleep-profile weight models — diffs rename-only).
- [x] Task 18: `OLD=domain.service`, `NEW_SUBPATH=domain/service` (14 — `HealthMetricsService.kt`, `BmiService.kt`, calculators; diffs rename-only).

## Task 19: `domain.repository` → `core.model.domain.repository` (16 files)

- [x] Procedure with `OLD=domain.repository`, `NEW_SUBPATH=domain/repository` (~145 external importers — includes DATA_FLOW.md-referenced `HealthConnectRepository.kt`, `ScoringRepository.kt`, `ScoringHistoryRepository.kt`).

## Task 20: `domain.validation` → `core.model.domain.validation` (27 files)

- [x] Procedure with `OLD=domain.validation`, `NEW_SUBPATH=domain/validation` (~15 external importers).
- [x] **Extra check:** validation logic is imported project-wide; every hunk in `git diff --cached -M` under this package must be rename/package/import only. Any change to a validator's condition or message: revert.

## Task 21: `domain.model` → `core.model.domain.model` (47 files, widest blast radius)

- [x] Procedure with `OLD=domain.model`, `NEW_SUBPATH=domain/model`.
- [x] **Extra pre-check before Step 4:** count importers (`grep -rl "^import app\.readylytics\.health\.domain\.model\." app core feature database-benchmark --include='*.kt' | grep -v '/build/' | wc -l`) and record the number in the commit message body (~313 expected). After the rename, re-running the same grep with the new prefix must return the same count.
- [x] Step 5 has the known inline `app.readylytics.health.domain.model.Result` usages in worker tests (PeriodicHealthSyncWorkerTest, HealthResyncWorkerTest) — rewrite each.
- [x] This package holds DATA_FLOW.md-referenced files (`BodyCompositionAssessment.kt`, `DailyMetricsMapper.kt`, `HealthConnectRecords.kt`, `InsightType.kt`, `LoadSourceSelector.kt`, `VitalStatusClassifiers.kt`) — Step 8 covers all.
- [x] Run the full gate before committing, not just `:core:model` tests — this rename touches every module.

## Task 22: stray `data.healthconnect` test package in core/model (3 files)

`core/model/src/test/kotlin/app/readylytics/health/data/healthconnect/` holds tests for mappers that already live at `app.readylytics.health.core.model.domain.sync.mappers` (renamed in an earlier commit). Align the test package with the code under test:

- [x] Move the 3 files (`HeartRateMapperTest.kt`, `StepsMapperTest.kt`, `WorkoutMapperTest.kt`) into `core/model/src/test/kotlin/app/readylytics/health/core/model/domain/sync/mappers/`.
- [x] Rewrite their package lines to `package app.readylytics.health.core.model.domain.sync.mappers` and drop now-redundant imports of that package (Step 6 compile confirms).
- [x] Steps 6–14 of the standard procedure (skipping Step 4's repo-wide import rewrite — nothing imports these tests; verify with the sweep grep).
- [x] Commit: `refactor: align core/model data.healthconnect test package with module namespace`

## Task 23: DATA_FLOW.md sweep + architecture-test namespace narrowing

- [x] `grep -n 'core/model/src' internal-docs/DATA_FLOW.md` — every path must now point at an on-disk file. Fix remaining stale segments (including `health/domain/heartrate/`, `health/domain/migration/`, `health/domain/model/`, `health/domain/repository/`, `health/domain/service/`, `health/domain/sleep/`, `health/domain/sync/...`, `health/domain/util/`, `health/domain/workouts/`, `health/data/preferences/` references — most covered by per-task Step 8, this is the backstop).
- [x] Spot-check 5 resolved paths with `test -f`.
- [x] **Namespace narrowing (per the follow-ups doc's "When all renames are done"):** once Plans 1–4 are all complete and `grep -rl '^package app\.readylytics\.health\.\(domain\|data\)\.' core feature --include='*.kt'` is empty, narrow the legacy entries in `app/src/test/.../CleanArchTest.kt` — `"app.readylytics.health.domain.."` (:11) and `"app.readylytics.health.data."` (:21) in `domainPackageGlobs`/`dataLayerPackagePrefixes` — to cover only the `app` module's own domain/data packages (e.g. `"app.readylytics.health.domain.migration.."`, and the specific `app/.../data/**` roots that remain in `app/src/main`). Enumerate what actually remains under `app/src/main/kotlin/app/readylytics/health/domain/` and `.../data/` first, and derive the narrowed globs from that measurement. Do NOT delete the legacy entries while any flat-ns files remain in `app`'s own tree.
- [x] `./gradlew :app:testDebugUnitTest` — `CleanArchTest` and `FeatureModuleArchitectureTest` must pass with the narrowed lists.
- [x] Commit: `docs: narrow legacy namespace globs in CleanArchTest after package alignment` (split into two commits if the DATA_FLOW backstop found changes of its own).

## Final verification (completes Item 4 end state)

- [x] `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — green, 3,009 tests, 0 lint warnings.
- [x] `grep -rl '^package app\.readylytics\.health\.\(domain\|data\)\.' core feature --include='*.kt'` → **empty** (the Item 4 completion criterion; `app` module's own packages may legitimately remain).
- [x] `grep -rn 'app\.readylytics\.health\.domain\.\(model\|validation\|repository\|service\|sleep\|workouts\|preferences\|backup\|vitals\|layout\|githubissue\|heartrate\|date\|crashreport\|circadian\|audit\|logcat\|error\|display\|cache\|sync\|util\|scoring\|insights\|airecommendation\|calculation\|health\|migration\|user\|common\|security\|dashboard\)\|app\.readylytics\.health\.data\.\(healthconnect\|local\|repository\|audit\|mapper\|migration\|security\|preferences\)' app core feature database-benchmark --include='*.kt' | grep -v '/build/' | grep -v 'app/src/main/kotlin/app/readylytics/health/\(domain\|data\)/' | grep -v 'app/src/test\|app/src/androidTest'` → review every remaining hit; each must be either an app-module-internal reference or an intentionally-kept declaration.
- [x] `codegraph sync` run after the final commit.
