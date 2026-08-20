# Package Alignment 3/4 — `core/database` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the 5 remaining flat-namespace `data.*` packages in `core/database` (68 files) to `core.database.data.*` equivalents, plus 21 test-only files in flat `domain.*` packages (missed by the original measurement), including the Room schemas directory rename that rides along with `HealthDatabase`. One commit per package, zero behaviour change.

**Architecture:** Pure package moves, smallest first, with `data.local` LAST because it carries the highest-risk item: `HealthDatabase`'s FQN is baked into the Room schema export directory name and one test's path string. Existing tests (including the golden fixtures in `core/database/src/test/resources/golden/` — never regenerate) are the regression net; no new tests, TDD does not apply.

**Tech Stack:** Kotlin, Room (KSP, schema export), Hilt, Gradle, WorkManager.

**Source item:** `POST_REMEDIATION_FOLLOWUPS.md` Item 4, execution order step 3.

**Branch:** `feat/remediation-followup-p2`

---

## Preconditions

- [ ] Plans 1 and 2 complete and green.
- [ ] Working tree clean; `./gradlew testDebugUnitTest` → 3,009 tests, 0 failures.

## Guardrails

- **Move-only diffs.** Any change to a DAO query, `@Query` SQL, migration SQL, entity definition, or the golden fixtures is a bug — revert it. `Migration9To10/10To11/11To12.kt` and `DatabaseUpgradeSql.kt` contain SQL: rename-only diffs, doubly checked.
- **Never rewrite `^package ` lines outside `core/database`.** External importers include ~26 files in `app` (backup, migration, androidTest) and 4 in `database-benchmark` — for those, only `import` lines / inline qualifiers change.
- **Room schemas are load-bearing.** `core/database/schemas/app.readylytics.health.data.local.HealthDatabase/{1..12}.json` must end up at the new FQN path with **byte-identical contents**. If any schema JSON content changes (e.g. a different `identityHash` appears), STOP — something altered the database definition; revert and investigate. Never edit schema JSON by hand.
- **Golden fixtures:** `core/database/src/test/resources/golden/` must show zero diff in every commit of this plan.
- **detekt baseline:** `core/database/detekt-baseline.xml` (96 entries). Fix failing `<ID>`s per `DETEKT_BASELINE_BURNDOWN.md` §5 by editing the entry; never regenerate.
- **Architecture tests:** `app.readylytics.health.core.database.data.` and `app.readylytics.health.core.database.domain..` are already covered (`CleanArchTest.kt:22` and the `domainPackageGlobs` list). No edits needed.
- **Doc sync:** `internal-docs/DATA_FLOW.md` references many `core/database/src/.../health/data/...` and `health/domain/scoring/golden/...` paths — update in the same commits (per-task Step 8 of the standard procedure, plus Task 7).

## Package inventory

| Task | Current package | Target | Files |
|---|---|---|---:|
| 1 | `data.audit` | `core.database.data.audit` | 2 |
| 2 | `data.local.migration` | `core.database.data.local.migration` | 3 |
| 3 | `data.local.dao` (test-only) | `core.database.data.local.dao` | 18 |
| 4 | `data.repository` | `core.database.data.repository` | 22 |
| 5 | `data.local` (incl. schemas dir rename) | `core.database.data.local` | 23 |
| 6 | `domain.model`/`domain.scoring`/`domain.scoring.golden`/`domain.sync.link` (test-only) | `core.database.domain.*` | 21 |

Note on Task 3: the aligned `core.database.data.local.dao` package already holds the main DAOs; the 18 files are test sources that must join them there.

## Standard rename procedure — run once per task below

1. **Enumerate:** `grep -rl "^package app\.readylytics\.health\.$OLD\$" core/database/src --include='*.kt' | sort` — confirm count matches the table (include `src/androidTest` — `data.local` has 2 androidTest files).
2. **Move:** for each source set (`main`, `test`, `androidTest`) containing the dir:
   `git mv core/database/src/<set>/kotlin/app/readylytics/health/$OLD_PATH core/database/src/<set>/kotlin/app/readylytics/health/core/database/$NEW_SUBPATH`
   (`app/readylytics/health/core/database/` already exists; some subpaths already exist — move file-by-file with `git mv` into the target dir if the dir is already there.)
3. **Package lines (moved files only):**
   `... | xargs sed -i '' "s/^package app\.readylytics\.health\.$OLD\$/package app.readylytics.health.core.database.$NEW_SUBPATH/"`
4. **Import lines repo-wide:**
   `grep -rl "^import app\.readylytics\.health\.$OLD\." app core feature database-benchmark --include='*.kt' | grep -v '/build/' | xargs sed -i '' "s/^import app\.readylytics\.health\.$OLD\./import app.readylytics.health.core.database.$NEW_SUBPATH./"`
5. **Inline FQN usages:** `grep -rn "app\.readylytics\.health\.$OLD\." app core feature database-benchmark --include='*.kt' | grep -v '/build/' | grep -v 'core/database/src'` — rewrite qualifiers (there are real ones here, e.g. `app.readylytics.health.data.local.HealthDatabase` in `app/src/main/.../data/backup/` and `database-benchmark/` files).
6. **Compile:** `./gradlew :core:database:compileDebugKotlin :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :database-benchmark:compileDebugKotlin` → BUILD SUCCESSFUL.
7. **Sweep:** `grep -rn "app\.readylytics\.health\.$OLD" app core feature database-benchmark --include='*.kt' --include='*.kts' | grep -v '/build/'` → empty.
8. **DATA_FLOW.md:** replace path segment `core/database/src/<set>/kotlin/app/readylytics/health/$OLD_PATH/` → `.../health/core/database/$NEW_SUBPATH/` for all source sets.
9. `./gradlew ktlintFormat`
10. `./gradlew :core:database:detekt` (§5-style baseline fix only)
11. `./gradlew testDebugUnitTest` → 0 failures, count still 3,009.
12. `codegraph sync`
13. Review `git diff --cached -M --stat`: renames + package/import lines only; `git diff --cached -- core/database/src/test/resources/golden/` must be EMPTY.
14. Commit: `refactor: align core/database <OLD> package with module namespace`

---

## Task 1: `data.audit` → `core.database.data.audit` (2 files)

- [ ] Procedure with `OLD=data.audit`, `NEW_SUBPATH=data/audit`.
Files: `RoomAuditTrailRepository.kt` (main), `RoomAuditTrailRepositoryTest.kt` (test).

## Task 2: `data.local.migration` → `core.database.data.local.migration` (3 files)

- [ ] Procedure with `OLD=data.local.migration`, `NEW_SUBPATH=data/local/migration`.
Files: `Migration9To10.kt`, `Migration10To11.kt`, `Migration11To12.kt` (all main).
- [ ] **Extra check:** the three migration files' diffs must be rename + package/import only. Migration SQL is behaviour.

## Task 3: `data.local.dao` → `core.database.data.local.dao` (18 test files)

- [ ] Procedure with `OLD=data.local.dao`, `NEW_SUBPATH=data/local/dao`. All 18 files are test sources (BloodPressureRecordDaoTest … WeightRecordDaoTest, ConflictTargetedUpsertTest, KeysetPaginationTest, OffsetPaginationTest, etc.). The target package already exists with the main DAOs — `git mv` the test dir's files into it.
- [ ] After the move these tests read the DAOs unqualified via same-package access; compile in Step 6 catches any now-needed imports.

## Task 4: `data.repository` → `core.database.data.repository` (22 files)

- [ ] Procedure with `OLD=data.repository`, `NEW_SUBPATH=data/repository`.
Files: 14 main (`ScoringRepositoryImpl.kt`, `SelectedDateRepository.kt`, `ScoringDayDataLoader.kt`, `ReadinessSummaryCoordinator.kt`, 9 further `*RepositoryImpl.kt`) + 8 test. DATA_FLOW.md references `data/repository/{ReadinessSummaryCoordinator,ScoringDayDataLoader,ScoringRepositoryImpl}.kt` — Step 8 covers them.

## Task 5: `data.local` → `core.database.data.local` (23 files) — highest risk, includes schemas dir

Files: 14 main (`HealthDatabase.kt`, `DatabaseMigrations.kt`, `DatabaseUpgradeSql.kt`, `Converters.kt`, `DataRollupManager.kt`, `RetentionCleanup.kt`, `RoomHealthIngestionStore.kt`, `RoomTransactionRunner.kt`, `RoomWalDiagnostics.kt`, `SelectedSourcePrunerImpl.kt`, `SessionLinkReconcilerImpl.kt`, `WarmTierReconstructor.kt`), 7 test, 2 androidTest (`DatabaseMigrationInstrumentedTest.kt`, `HealthDatabaseIndexTest.kt`).

- [ ] **Step 5a — move files** per the standard procedure (`OLD=data.local`, `NEW_SUBPATH=data/local`; the `data/local` target dir partially exists from Tasks 2–3 — merge, don't clobber; `rmdir` the old dir only when empty).
- [ ] **Step 5b — rename the Room schemas directory** (the DB class FQN changed from `app.readylytics.health.data.local.HealthDatabase` to `app.readylytics.health.core.database.data.local.HealthDatabase`):

```bash
git mv core/database/schemas/app.readylytics.health.data.local.HealthDatabase \
       core/database/schemas/app.readylytics.health.core.database.data.local.HealthDatabase
```

- [ ] **Step 5c — update the hardcoded schema path string** in `app/src/test/kotlin/app/readylytics/health/data/migration/DatabaseMigrationModelsTest.kt:150`:
  `"core/database/schemas/app.readylytics.health.data.local.HealthDatabase/7.json"` →
  `"core/database/schemas/app.readylytics.health.core.database.data.local.HealthDatabase/7.json"`.
- [ ] **Step 5d — sweep for other schema-path strings:**
  `grep -rn 'schemas/app\.readylytics\.health' app core feature database-benchmark --include='*.kt' --include='*.kts' | grep -v '/build/'` → all hits updated to the new FQN dir (the `room { schemaDirectory("$projectDir/schemas") }` convention in `build-logic` is relative — no change).
- [ ] Steps 6–14 of the standard procedure.
- [ ] **Step 5e — schema integrity check (before commit):** after the build, `git diff --cached -- 'core/database/schemas/**'` must show ONLY the directory rename (R100 for every `*.json`). If any `.json` content changed, STOP and investigate — the database definition must not have changed. Also verify `ls core/database/schemas/app.readylytics.health.core.database.data.local.HealthDatabase/` still lists all versioned schema files (1..12).
- [ ] Commit: `refactor: align core/database data.local package with module namespace`

## Task 6: flat `domain.*` test packages → `core.database.domain.*` (21 test files, unaccounted in the original measurement)

These test-only packages were omitted from the follow-ups doc's table but block the end state ("no flat `health.domain.*`/`health.data.*` files inside `core/*`"):

| Current (test-only) | Target | Files |
|---|---|---:|
| `domain.model` | `core.database.domain.model` | 1 (`DailySummaryMapperTest.kt`) |
| `domain.scoring` | `core.database.domain.scoring` | 10 (`BackfillBaselinesUseCaseTest.kt`, `BaselineComputer*Test.kt` ×3, `ScoringDeterminismRegressionTest.kt`, `ScoringPointInTimeRegressionTest.kt`, `ScoringRepositoryN1Test.kt`, `ScoringSyncScopeOutputsDeterminismTest.kt`, `SyncScopeDeterminismTest.kt`, `WalkForwardDeterminismTest.kt`) |
| `domain.scoring.golden` | `core.database.domain.scoring.golden` | 9 (`GoldenFixtureDataBuilder.kt`, `GoldenFixtureDataBuilderTest.kt`, `GoldenFixtureTestFakes.kt`, `GoldenFixtureWalkForwardTest.kt`, `ScoringEquivalenceGoldenTest.kt`, `ScoringGoldenSnapshotTest.kt`, `SyntheticDatasetGenerator.kt`, `SyntheticDatasetGeneratorTest.kt`, `WalkForwardTransactionEquivalenceTest.kt`) |
| `domain.sync.link` | `core.database.domain.sync.link` | 1 (`SessionLinkReconcilerTest.kt`) |

- [ ] Run the standard procedure **four times**, once per row (`OLD=domain.model` → `NEW_SUBPATH=domain/model`, `OLD=domain.scoring.golden` before `domain.scoring`, `OLD=domain.sync.link`, then `OLD=domain.scoring`). Order matters: rename `domain.scoring.golden` BEFORE `domain.scoring`, and move files individually into the existing/created target dirs.
- [ ] **Golden fixture guard, again:** `git diff --cached -- core/database/src/test/resources/golden/` must be EMPTY in all four commits. These tests read those fixtures; the fixtures themselves must not move or change.
- [ ] **Scoring-math guard:** `ScoringGoldenSnapshotTest` / `ScoringEquivalenceGoldenTest` / `SyntheticDatasetGenerator` diffs must be rename + package/import only. Any numeric change: revert.
- [ ] Four commits: `refactor: align core/database <OLD> package with module namespace`

## Task 7: DATA_FLOW.md sweep for core/database paths

- [ ] `grep -n 'core/database/src' internal-docs/DATA_FLOW.md` — every path must now point at an on-disk file. Fix any remaining stale segments (`health/data/local/...`, `health/data/repository/...`, `health/domain/scoring/golden/...` etc. — including the `data/local/dao/AuditEventDao.kt` and `data/local/entity/AuditEventEntity.kt` references).
- [ ] Spot-check 5 resolved paths with `test -f`.
- [ ] Commit: `docs: refresh core/database paths in DATA_FLOW.md after package alignment`

## Final verification

- [ ] `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — green, 3,009 tests, 0 lint warnings.
- [ ] `grep -rl '^package app\.readylytics\.health\.\(data\|domain\)\.' core/database/src` → empty.
- [ ] `ls core/database/schemas/` shows ONLY the new-FQN directory; all schema JSONs byte-identical to before (`git diff <pre-plan-commit> HEAD --stat -- core/database/schemas/` shows renames only).
- [ ] On-device smoke test (debug variant only): `./gradlew installDebug`, launch, confirm the DB opens (dashboard renders) — proves schema location + migrations still resolve. NEVER touch `app.readylytics.health` (production).
