# Align `core/database` Packages With Its Module — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename every `core/database` package that today spans another module
(`di`, `domain.sync`, `data.migration`, `data.security`, `data.mapper`, `data.local.entity`,
`data.local.dao`) so each is prefixed with `app.readylytics.health.core.database`.

**Architecture:** Seven independent package renames via IDE refactor, one per task, ordered
smallest-file-count first. `HealthDatabase.kt` itself (package `app.readylytics.health.data.local`,
not one of the seven) is **not** renamed by this plan — only its imports change, automatically, as
a side effect of Task 6 and Task 7 (its own `AuditEventEntity`/`AuditEventDao`) and of the
`core/database-schema` plan (the other 33 entities/DAOs it wires up).

**Tech Stack:** Kotlin, Hilt (`@Module`/`@InstallIn`), Room (KSP), Android Keystore (`data.security`),
Konsist.

**Spec:** `internal-docs/plans/POST_REMEDIATION_FOLLOWUPS.md` (Item 4, lines 337-397) and
`docs/superpowers/plans/2026-08-19-package-module-alignment-index.md` (sequencing, shared safety
verification, naming convention).

## Global Constraints

- Full gate before closing this plan: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease`.
- Baseline: 3,009 unit tests, 0 failures, 0 lint warnings (2026-08-18).
- Scoring math is off-limits. `data.migration` and `domain.scoring`-package test files in this
  module's `src/test` (see the cross-module coupling note in Task-level detail below) touch
  scoring *use cases*, never formulas — do not edit any assertion values, only package/import
  lines.
- Rename via IDE "Refactor → Rename → Rename package", not `sed`.
- Run `codegraph sync` after each task.
- Do this module's plan only after `core/database-schema` is done (its `data.local.entity`/
  `data.local.dao` renames land first, per the index doc's sequencing, so `HealthDatabase.kt`'s
  import list only needs to change once per entity, not be touched twice).

## File Structure

Seven packages, 12 `src/main` files total, all flat (no subpackages) in this module:

| Package | File(s) | Target |
|---|---|---|
| `di` | `DatabaseModule.kt`, `DatabaseRepositoryModule.kt` | `core.database.di` |
| `domain.sync` | `DailyRecomputeSupport.kt` | `core.database.domain.sync` |
| `data.migration` | `DatabaseReadinessGate.kt` | `core.database.data.migration` |
| `data.security` | `AndroidKeystoreKeyProvider.kt`, `KeyProvider.kt`, `SqlCipherKeyManager.kt` | `core.database.data.security` |
| `data.mapper` | `DailySummaryMapper.kt`, `SleepAndHeartRateRecordMappers.kt`, `VitalsRecordMappers.kt` | `core.database.data.mapper` |
| `data.local.entity` | `AuditEventEntity.kt` | `core.database.data.local.entity` |
| `data.local.dao` | `AuditEventDao.kt` | `core.database.data.local.dao` |

All paths below are rooted at `core/database/src/main/kotlin/app/readylytics/health/`.

**Associated `src/test` files (move automatically with their package's rename, listed for
awareness — do not move by hand):**

- `di`: none found.
- `domain.sync`: `core/database/src/test/kotlin/app/readylytics/health/domain/sync/link/SessionLinkReconcilerTest.kt`
  — **this file tests `core/model`'s `SessionLinkReconciler`, not anything in this module's own
  `domain.sync` package.** It sits in the matching `domain/sync/link` subpath purely for
  same-package test access. Renaming *this module's* `domain.sync` package (which only contains
  `DailyRecomputeSupport.kt`, no `link` subpackage) does not move this test file, because
  `domain.sync.link` is a distinct package from `domain.sync` and is not touched by this plan —
  confirm this explicitly in Task 2, Step 1, before assuming the IDE rename's scope.
- `data.migration`: `core/database/src/test/kotlin/app/readylytics/health/data/migration/DatabaseReadinessGateTest.kt`.
- `data.security`: `core/database/src/test/kotlin/app/readylytics/health/data/security/SqlCipherKeyManagerTest.kt`.
- `data.mapper`: `core/database/src/test/kotlin/app/readylytics/health/data/mapper/SleepAndHeartRateRecordMappersTest.kt`,
  `core/database/src/test/kotlin/app/readylytics/health/data/mapper/VitalsRecordMappersTest.kt`.
- `data.local.entity`: none found under this exact package in `src/test` for `AuditEventEntity`.
- `data.local.dao`: none found under this exact package in `src/test` for `AuditEventDao`
  specifically (the 17 `data.local.dao.*DaoTest.kt` files in this module's `src/test`, e.g.
  `BloodPressureRecordDaoTest.kt`, test `core/database-schema`'s DAOs and are handled — moved —
  by the `core/database-schema` plan's Task 2, not this plan; do not touch them here).

**Note on `domain.scoring` test files in this module:** `core/database/src/test/kotlin/app/readylytics/health/domain/scoring/`
contains 10 files (`BackfillBaselinesUseCaseTest.kt`, `ScoringRepositoryN1Test.kt`, etc.) that test
`core/scoring`'s use cases via same-package implicit access — this is a *different* package
(`domain.scoring`, not any of the seven in this plan) and is out of scope here; it is called out
explicitly in the `core/scoring` module plan's risk section instead, since that is the plan whose
rename affects those test files.

## Task 1: Rename `di` → `core.database.di`

**Files:**
- Move: `di/DatabaseModule.kt`, `di/DatabaseRepositoryModule.kt`.

**Interfaces:**
- Produces: `app.readylytics.health.core.database.di.DatabaseModule`,
  `app.readylytics.health.core.database.di.DatabaseRepositoryModule` — Hilt resolves `@Module`
  classes by type via KSP-generated code, not by package string, so no Hilt-specific follow-up is
  needed beyond the import rewrite.

- [x] **Step 1: Baseline.** Run: `./gradlew :core:database:testDebugUnitTest` — Expected: PASS.
- [x] **Step 2: Rename.** Right-click `core/database/src/main/kotlin/app/readylytics/health/di` →
  Refactor → Rename → "Rename package" → `app.readylytics.health.core.database.di` → both search
  options checked → Preview (confirm exactly 2 files) → apply.
- [x] **Step 3: Sweep.** Run: `grep -rn "app\.readylytics\.health\.di\.\(DatabaseModule\|DatabaseRepositoryModule\)" --include="*.kt" . | grep -v /build/` — Expected: no output.
- [x] **Step 4: `codegraph sync`.**
- [x] **Step 5: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [x] **Step 6: Commit.**
```bash
git add -A -- 'core/database/src/main/kotlin/app/readylytics/health/core' ':(glob)**/*.kt'
git commit -m "refactor: align core/database di package with module namespace"
```

## Task 2: Rename `domain.sync` → `core.database.domain.sync`

**Files:**
- Move: `domain/sync/DailyRecomputeSupport.kt`.

**Interfaces:**
- Produces: `app.readylytics.health.core.database.domain.sync.DailyRecomputeSupport`.

- [x] **Step 1: Confirm scope before renaming.** Run:
  `find core/database/src/main/kotlin/app/readylytics/health/domain/sync -maxdepth 1 -name "*.kt"`
  — Expected: exactly `DailyRecomputeSupport.kt`, confirming no subpackage (in particular, no
  `link/` subpackage — that lives only under `src/test` for a different module's class, per the
  File Structure note above, and must not be swept into this rename).
- [x] **Step 2: Baseline.** Run: `./gradlew :core:database:testDebugUnitTest` — Expected: PASS.
- [x] **Step 3: Rename.** Right-click `core/database/src/main/kotlin/app/readylytics/health/domain/sync`
  → Refactor → Rename → "Rename package" → `app.readylytics.health.core.database.domain.sync` →
  both search options checked → Preview (confirm exactly 1 file moves, and that
  `domain/sync/link/SessionLinkReconcilerTest.kt` under `src/test` is **not** in the preview list)
  → apply.
- [x] **Step 4: Sweep.** Run: `grep -rn "app\.readylytics\.health\.domain\.sync\.DailyRecomputeSupport" --include="*.kt" . | grep -v /build/` — Expected: no output (or only the new FQN).
- [x] **Step 5: `codegraph sync`.**
- [x] **Step 6: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [x] **Step 7: Commit.**
```bash
git add -A -- 'core/database/src/main/kotlin/app/readylytics/health/core' ':(glob)**/*.kt'
git commit -m "refactor: align core/database domain.sync package with module namespace"
```

## Task 3: Rename `data.migration` → `core.database.data.migration`

**Files:**
- Move: `data/migration/DatabaseReadinessGate.kt` and its test,
  `src/test/.../data/migration/DatabaseReadinessGateTest.kt`.

**Interfaces:**
- Produces: `app.readylytics.health.core.database.data.migration.DatabaseReadinessGate`.

- [x] **Step 1: Baseline.** Run: `./gradlew :core:database:testDebugUnitTest` — Expected: PASS.
- [x] **Step 2: Rename.** Right-click `core/database/src/main/kotlin/app/readylytics/health/data/migration`
  → Refactor → Rename → "Rename package" → `app.readylytics.health.core.database.data.migration` →
  both search options checked → Preview (confirm `DatabaseReadinessGate.kt` and
  `DatabaseReadinessGateTest.kt` both move) → apply.
- [x] **Step 3: Sweep.** Run: `grep -rn "app\.readylytics\.health\.data\.migration\.DatabaseReadinessGate" --include="*.kt" . | grep -v /build/` — Expected: no output.
- [x] **Step 4: `codegraph sync`.**
- [x] **Step 5: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [x] **Step 6: Commit.**
```bash
git add -A -- 'core/database/src/main/kotlin/app/readylytics/health/core' 'core/database/src/test/kotlin/app/readylytics/health/core' ':(glob)**/*.kt'
git commit -m "refactor: align core/database data.migration package with module namespace"
```

## Task 4: Rename `data.security` → `core.database.data.security`

**Files:**
- Move: `data/security/AndroidKeystoreKeyProvider.kt`, `data/security/KeyProvider.kt`,
  `data/security/SqlCipherKeyManager.kt`, and test `data/security/SqlCipherKeyManagerTest.kt`.

**Interfaces:**
- Produces: `app.readylytics.health.core.database.data.security.{AndroidKeystoreKeyProvider,KeyProvider,SqlCipherKeyManager}`.
  `SqlCipherKeyManager` is the SQLCipher passphrase manager backing the encrypted DB connection —
  it holds no persisted string referencing its own FQN (Android Keystore aliases are separate
  string constants inside the class, unaffected by package rename); confirm this in Step 1 before
  renaming, since a keystore alias collision would be a genuine data-loss risk if it existed.

- [x] **Step 1: Confirm no FQN-based keystore alias.** Run:
  `grep -n "KeyStore\|alias" core/database/src/main/kotlin/app/readylytics/health/data/security/AndroidKeystoreKeyProvider.kt`
  and read the matched lines — confirm the Keystore alias is a literal string constant (e.g.
  `"readylytics_db_key"`), not derived from `javaClass.name` or `::class.qualifiedName`. If it is
  derived from the class's qualified name, STOP — that would invalidate every existing user's
  encrypted database key on this rename, which is a data-loss regression. (Verified 2026-08-19
  during planning: this file uses a literal alias string, not a class-name-derived one — this step
  re-confirms it at execution time in case the file has changed since.)
- [x] **Step 2: Baseline.** Run: `./gradlew :core:database:testDebugUnitTest` — Expected: PASS.
- [x] **Step 3: Rename.** Right-click `core/database/src/main/kotlin/app/readylytics/health/data/security`
  → Refactor → Rename → "Rename package" → `app.readylytics.health.core.database.data.security` →
  both search options checked → Preview (confirm 3 `src/main` files + 1 `src/test` file) → apply.
- [x] **Step 4: Sweep.** Run: `grep -rn "app\.readylytics\.health\.data\.security\." --include="*.kt" . | grep -v /build/` — Expected: no output.
- [x] **Step 5: `codegraph sync`.**
- [x] **Step 6: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [x] **Step 7: Commit.**
```bash
git add -A -- 'core/database/src/main/kotlin/app/readylytics/health/core' 'core/database/src/test/kotlin/app/readylytics/health/core' ':(glob)**/*.kt'
git commit -m "refactor: align core/database data.security package with module namespace"
```

## Task 5: Rename `data.mapper` → `core.database.data.mapper`

**Files:**
- Move: `data/mapper/DailySummaryMapper.kt`, `data/mapper/SleepAndHeartRateRecordMappers.kt`,
  `data/mapper/VitalsRecordMappers.kt`, and tests
  `data/mapper/SleepAndHeartRateRecordMappersTest.kt`, `data/mapper/VitalsRecordMappersTest.kt`.

**Interfaces:**
- Produces: `app.readylytics.health.core.database.data.mapper.{DailySummaryMapper,SleepAndHeartRateRecordMappers,VitalsRecordMappers}`.
- Note: `core/healthconnect` has its own, *different* `data.mapper` package (`BloodPressureDataMapper.kt`
  etc., 5 files) — that is a separate task in the `core/healthconnect` module plan, not this one;
  after both plans land, `core/database`'s mappers are `core.database.data.mapper.*` and
  `core/healthconnect`'s are `core.healthconnect.data.mapper.*`, no collision.

- [x] **Step 1: Baseline.** Run: `./gradlew :core:database:testDebugUnitTest` — Expected: PASS.
- [x] **Step 2: Rename.** Right-click `core/database/src/main/kotlin/app/readylytics/health/data/mapper`
  → Refactor → Rename → "Rename package" → `app.readylytics.health.core.database.data.mapper` →
  both search options checked → Preview (confirm 3 `src/main` + 2 `src/test` files) → apply.
- [x] **Step 3: Sweep.** Run: `grep -rn "app\.readylytics\.health\.data\.mapper\.\(DailySummaryMapper\|SleepAndHeartRateRecordMappers\|VitalsRecordMappers\)" --include="*.kt" . | grep -v /build/` — Expected: no output.
- [x] **Step 4: `codegraph sync`.**
- [x] **Step 5: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [x] **Step 6: Commit.**
```bash
git add -A -- 'core/database/src/main/kotlin/app/readylytics/health/core' 'core/database/src/test/kotlin/app/readylytics/health/core' ':(glob)**/*.kt'
git commit -m "refactor: align core/database data.mapper package with module namespace"
```

## Task 6: Rename `data.local.entity` → `core.database.data.local.entity`

**Files:**
- Move: `data/local/entity/AuditEventEntity.kt`.

**Interfaces:**
- Produces: `app.readylytics.health.core.database.data.local.entity.AuditEventEntity`.
- Consumes: `HealthDatabase.kt`'s `entities = [...]` list already references
  `core.databaseschema.data.local.entity.*` for the other 16 entities (from the
  `core/database-schema` plan, done earlier per sequencing) — this task adds one more import to
  that same file for `AuditEventEntity`'s new FQN; the IDE refactor handles it automatically, this
  is documented so the diff on `HealthDatabase.kt` in this task's commit is expected and correct.

- [x] **Step 1: Baseline.** Run: `./gradlew :core:database:testDebugUnitTest` — Expected: PASS.
- [x] **Step 2: Rename.** Right-click `core/database/src/main/kotlin/app/readylytics/health/data/local/entity`
  → Refactor → Rename → "Rename package" → `app.readylytics.health.core.database.data.local.entity`
  → both search options checked → Preview (confirm exactly 1 file: `AuditEventEntity.kt`; confirm
  `HealthDatabase.kt` appears in the "usages to update" list) → apply.
- [x] **Step 3: Sweep.** Run: `grep -rn "app\.readylytics\.health\.data\.local\.entity\.AuditEventEntity" --include="*.kt" . | grep -v /build/` — Expected: no output.
- [x] **Step 4: `codegraph sync`.**
- [x] **Step 5: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [x] **Step 6: Commit.**
```bash
git add -A -- 'core/database/src/main/kotlin/app/readylytics/health/core' 'core/database/src/main/kotlin/app/readylytics/health/data/local/HealthDatabase.kt'
git commit -m "refactor: align core/database data.local.entity package with module namespace"
```

## Task 7: Rename `data.local.dao` → `core.database.data.local.dao`

**Files:**
- Move: `data/local/dao/AuditEventDao.kt`.

**Interfaces:**
- Produces: `app.readylytics.health.core.database.data.local.dao.AuditEventDao`.
- Consumes: same `HealthDatabase.kt` note as Task 6 — its `abstract fun auditEventDao(): AuditEventDao`
  accessor's import updates automatically.

- [x] **Step 1: Baseline.** Run: `./gradlew :core:database:testDebugUnitTest` — Expected: PASS.
- [x] **Step 2: Rename.** Right-click `core/database/src/main/kotlin/app/readylytics/health/data/local/dao`
  → Refactor → Rename → "Rename package" → `app.readylytics.health.core.database.data.local.dao` →
  both search options checked → Preview (confirm exactly 1 file: `AuditEventDao.kt`; confirm
  `HealthDatabase.kt` appears in the "usages to update" list) → apply.
- [x] **Step 3: Sweep.** Run: `grep -rn "app\.readylytics\.health\.data\.local\.dao\.AuditEventDao" --include="*.kt" . | grep -v /build/` — Expected: no output.
- [x] **Step 4: `codegraph sync`.**
- [x] **Step 5: Gate.** Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — Expected: PASS, 3,009 tests, 0 warnings.
- [x] **Step 6: Final repo-wide sweep for this module's old FQNs.** Run:
  `grep -rn "app\.readylytics\.health\.\(di\|domain\.sync\.DailyRecomputeSupport\|data\.migration\.DatabaseReadinessGate\|data\.security\.\(AndroidKeystoreKeyProvider\|KeyProvider\|SqlCipherKeyManager\)\|data\.mapper\.\(DailySummaryMapper\|SleepAndHeartRateRecordMappers\|VitalsRecordMappers\)\|data\.local\.entity\.AuditEventEntity\|data\.local\.dao\.AuditEventDao\)" --include="*.kt" . | grep -v /build/`
  — Expected: no output (this single command re-checks all seven of this plan's renames at once,
  as the plan's closing gate).
- [x] **Step 7: Commit.**
```bash
git add -A -- 'core/database/src/main/kotlin/app/readylytics/health/core' 'core/database/src/main/kotlin/app/readylytics/health/data/local/HealthDatabase.kt'
git commit -m "refactor: align core/database data.local.dao package with module namespace"
```

## Verification

`./gradlew :core:database:testDebugUnitTest :app:testDebugUnitTest` green, full gate green, Task
7 Step 6's combined sweep returning nothing. On-device: `./gradlew installDebug`, open the app,
confirm data still loads (DB opens, decrypts with `SqlCipherKeyManager` unchanged, dashboard
renders) — this module's `data.security` rename is the one genuine data-loss risk in this plan if
Task 4 Step 1's keystore-alias check is skipped, so do not skip that verification step.
