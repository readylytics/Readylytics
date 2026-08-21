# Align `core/database-schema` Packages With Its Module — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename every package in `core/database-schema` so it is prefixed with the module's own
namespace (`app.readylytics.health.core.databaseschema`), so that module no longer shares a
package with `core/database`, `core/healthconnect`, `core/scoring`, `database-benchmark`, or `app`.

**Architecture:** Pure package rename via IDE refactor. `core/database-schema` contains only Room
`@Entity` classes and `@Dao` interfaces — no business logic, no ViewModels, no DI modules. It is
the pilot module for Item 4 of the post-remediation follow-ups (smallest, most self-contained,
proves the pattern before the larger modules).

**Tech Stack:** Kotlin, Room (KSP annotation processing), kotlinx.serialization
(`@Serializable` entities), Konsist (`CleanArchTest`).

**Spec:** `internal-docs/plans/POST_REMEDIATION_FOLLOWUPS.md` (Item 4, lines 337-397) and
`docs/superpowers/plans/2026-08-19-package-module-alignment-index.md` (sequencing, shared safety
verification, naming convention — read that file's "Why this is safe here" and "Naming
convention" sections before starting; they are not repeated in full below).

## Global Constraints

- Full gate before closing this plan: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease`.
- Baseline: 3,009 unit tests, 0 failures, 0 lint warnings (2026-08-18). No drop in test count, no
  new warning.
- Scoring math is off-limits (not touched by this module — no scoring code lives here).
- Rename via IDE "Refactor → Rename → Rename package" only, not `sed` — see index doc for why.
- Run `codegraph sync` after each task (structural move).
- Room schema export (`core/database/schemas/app.readylytics.health.data.local.HealthDatabase/*.json`)
  is keyed by the `@Database` class FQN, which is not in `core/database-schema` and is not
  renamed by this plan — schema identity hashes are unaffected. `@Serializable` entities here use
  plain field-based serialization (no `classDiscriminator`/polymorphism found repo-wide) so the
  local encrypted backup format is unaffected by this rename. Both facts are verified in the index
  doc; this plan does not need to re-verify them.

## File Structure

Two packages, both flat (no subpackages), 33 files total:

- `core/database-schema/src/main/kotlin/app/readylytics/health/data/local/entity/` (16 files) →
  `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/`
- `core/database-schema/src/main/kotlin/app/readylytics/health/data/local/dao/` (17 files) →
  `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/`
- `core/database-schema/src/test/kotlin/app/readylytics/health/data/local/entity/DomainModelTest.kt`
  (the only test file in this module in either of the two packages) moves along with the entity
  package rename automatically — Android Studio's package rename operates across all source sets
  in the module, not just `src/main`.

**Known consumers outside this module (found by `grep -rl "import app.readylytics.health.data.local.\(dao\|entity\)\."`,
2026-08-19):** `app`, `core/database`, `core/healthconnect`, `core/scoring`, `database-benchmark`
all import one or more of these 33 classes — 56 files import from `data.local.dao`, 105 files
import from `data.local.entity`, repo-wide. The IDE rename rewrites every one of those imports in
the same operation; do not attempt to enumerate them by hand. `core/database`'s own
`data.local.dao.AuditEventDao` / `data.local.entity.AuditEventEntity` are **not** part of this
plan — they are a different module's files in the same-named package and are handled by the
`core/database` module plan.

## Task 1: Rename `data.local.entity` → `core.databaseschema.data.local.entity`

**Files:**
- Move (16, `src/main`): every `*.kt` directly under
  `core/database-schema/src/main/kotlin/app/readylytics/health/data/local/entity/`
  (`BloodPressureRecordEntity.kt`, `BodyFatRecordEntity.kt`, `BodyTemperatureRecordEntity.kt`,
  `DailySummaryEntity.kt`, `HealthSourceRecordEntity.kt`, `HeartRateRecordEntity.kt`,
  `HrMinuteBucketEntity.kt`, `HrvRecordEntity.kt`, `InsightDismissalEntity.kt`,
  `LocalDateSerializer.kt`, `OxygenSaturationRecordEntity.kt`, `SleepSessionEntity.kt`,
  `SleepStageEntity.kt`, `StepRecordEntity.kt`, `WeightRecordEntity.kt`, `WorkoutRecordEntity.kt`,
  `WorkoutRoutePointEntity.kt`).
- Move (1, `src/test`): `core/database-schema/src/test/kotlin/app/readylytics/health/data/local/entity/DomainModelTest.kt`.
- Modify (imports only, rewritten automatically by the IDE refactor — do not hand-edit): every
  consumer of `app.readylytics.health.data.local.entity.*` across `app`, `core/database`,
  `core/healthconnect`, `core/scoring`, `database-benchmark`, including
  `core/database/src/main/kotlin/app/readylytics/health/data/local/HealthDatabase.kt:23-38`
  (its `entities = [...]` list and per-entity imports).

**Interfaces:**
- Produces: every class in this package keeps its exact simple name and public API — only the
  package (and therefore fully-qualified name) changes, e.g.
  `app.readylytics.health.data.local.entity.WorkoutRecordEntity` becomes
  `app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity`. Task 2 and
  every consuming module rely on the simple names being unchanged.

- [ ] **Step 1: Confirm the pre-rename baseline is green.**

Run: `./gradlew :core:database-schema:testDebugUnitTest :core:database:testDebugUnitTest`
Expected: PASS (this establishes the state you are diffing against).

- [ ] **Step 2: Perform the rename in the IDE.**

In Android Studio / IntelliJ, right-click the
`core/database-schema/src/main/kotlin/app/readylytics/health/data/local/entity` package node →
Refactor → Rename → "Rename package" → enter `app.readylytics.health.core.databaseschema.data.local.entity`
→ ensure "Search in comments and strings" and "Search for text occurrences" are both checked →
Preview → apply. This physically moves all 16 `src/main` files and the 1 `src/test` file, and
rewrites every `package`/`import` statement it finds project-wide, including in `app`,
`core/database`, `core/healthconnect`, `core/scoring`, and `database-benchmark`.

- [ ] **Step 3: Verify zero stale references remain.**

Run: `grep -rn "app\.readylytics\.health\.data\.local\.entity\." --include="*.kt" . | grep -v /build/`
Expected: no output (every reference now reads `app.readylytics.health.core.databaseschema.data.local.entity`).
If any line appears, it is a file the IDE refactor's project index missed (e.g. a file outside the
IDE project scope) — open it and fix the `import`/qualified reference manually, matching the new
FQN exactly.

- [ ] **Step 4: Sync the code graph.**

Run: `codegraph sync`

- [ ] **Step 5: Run the full gate.**

Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease`
Expected: PASS, 3,009 unit tests (test count must not drop — a dropped count means a test file's
package rename broke test discovery), 0 lint warnings.

- [ ] **Step 6: Commit.**

```bash
git add -A -- 'core/database-schema/src/main/kotlin/app/readylytics/health/core' \
  'core/database-schema/src/test/kotlin/app/readylytics/health/core' \
  ':(glob)**/*.kt'
git commit -m "refactor: align core/database-schema entity package with module namespace"
```

## Task 2: Rename `data.local.dao` → `core.databaseschema.data.local.dao`

**Files:**
- Move (17, `src/main`): every `*.kt` directly under
  `core/database-schema/src/main/kotlin/app/readylytics/health/data/local/dao/`
  (`BloodPressureRecordDao.kt`, `BodyFatRecordDao.kt`, `BodyTemperatureRecordDao.kt`,
  `DailySummaryDao.kt`, `HeartRateDao.kt`, `HrvDao.kt`, `InsightDismissalDao.kt`,
  `MinuteBucketDao.kt`, `OxygenSaturationRecordDao.kt`, `SleepHrSample.kt`, `SleepSessionDao.kt`,
  `SleepStageDao.kt`, `SourceRecordDao.kt`, `StepRecordDao.kt`, `WeightRecordDao.kt`,
  `WorkoutDao.kt`, `WorkoutRoutePointDao.kt`).
- Modify (imports only, rewritten automatically): every consumer of
  `app.readylytics.health.data.local.dao.*`, including
  `core/database/src/main/kotlin/app/readylytics/health/data/local/HealthDatabase.kt:6-22` (its
  `abstract fun ...Dao()` accessor return types and per-DAO imports).

**Interfaces:**
- Consumes: nothing from Task 1 — DAOs reference entity types by import, and those imports were
  already rewritten to the new FQN by Task 1's IDE refactor, so no additional work is needed here
  to keep DAO method signatures (e.g. `WorkoutDao.insertAll(items: List<WorkoutRecordEntity>)`)
  compiling.
- Produces: same simple-name-preserving contract as Task 1, now for the DAO interfaces.

- [ ] **Step 1: Confirm Task 1's gate is still green before starting.**

Run: `./gradlew :core:database-schema:testDebugUnitTest :core:database:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 2: Perform the rename in the IDE.**

Right-click the `core/database-schema/src/main/kotlin/app/readylytics/health/data/local/dao`
package node → Refactor → Rename → "Rename package" →
`app.readylytics.health.core.databaseschema.data.local.dao` → "Search in comments and strings" +
"Search for text occurrences" checked → Preview → apply.

- [ ] **Step 3: Verify zero stale references remain.**

Run: `grep -rn "app\.readylytics\.health\.data\.local\.dao\." --include="*.kt" . | grep -v /build/`
Expected: no output. `core/database`'s own `AuditEventDao` (package `app.readylytics.health.data.local.dao`,
a *different* module's file) is out of scope for this plan — if this grep surfaces
`AuditEventDao.kt` itself (not an import of it), that is expected and correct; it is handled by
the `core/database` module plan, not this one. If this grep surfaces anything *importing*
`AuditEventDao` alongside a stale `data.local.dao.<one of the 17 above>` import on the same file,
only the stale import needs fixing here.

- [ ] **Step 4: Sync the code graph.**

Run: `codegraph sync`

- [ ] **Step 5: Run the full gate.**

Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease`
Expected: PASS, 3,009 unit tests, 0 lint warnings.

- [ ] **Step 6: Commit.**

```bash
git add -A -- 'core/database-schema/src/main/kotlin/app/readylytics/health/core' ':(glob)**/*.kt'
git commit -m "refactor: align core/database-schema dao package with module namespace"
```

## Task 3: Confirm `CleanArchTest` is unaffected and do a final consumer sweep

Neither package touched by this plan matches any `hasPackage("app.readylytics.health.domain..")`,
`"app.readylytics.health.data.."` (feature-import check only, not this rename), or `"/di/"` /
`"/feature/"` predicate in `CleanArchTest.kt` in a way that this module's rename changes — DAOs
and entities are pure data layer with no `domain` or `di` package involvement here. This task is a
verification-only checkpoint, not a code change.

**Files:**
- Test: `app/src/test/kotlin/app/readylytics/health/CleanArchTest.kt` (read-only check).

- [ ] **Step 1: Run `CleanArchTest` in isolation.**

Run: `./gradlew :app:testDebugUnitTest --tests "app.readylytics.health.CleanArchTest"`
Expected: PASS, same as before Task 1 and Task 2 — this rename must not change which files any
`CleanArchTest` rule flags.

- [ ] **Step 2: Repo-wide sweep for any remaining old FQN.**

Run: `grep -rln "app\.readylytics\.health\.data\.local\.\(entity\|dao\)\." --include="*.kt" --include="*.md" . | grep -v /build/`
Expected: only matches inside `core/database` (its own untouched `AuditEventDao`/`AuditEventEntity`
and their same-named-package siblings) and any documentation files that quote the *old, pre-rename*
FQN as historical context (e.g. this repo's own planning docs) — no `core/database-schema`,
`core/healthconnect`, `core/scoring`, `database-benchmark`, or `app` source file should appear.

- [ ] **Step 3: Full gate, one more time, clean.**

Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease`
Expected: PASS, 3,009 unit tests, 0 lint warnings — this is the state you hand back as "pilot done."

## Verification

`./gradlew :core:database-schema:testDebugUnitTest :core:database:testDebugUnitTest :app:testDebugUnitTest`
green, plus the full gate green, plus the Task 3 sweep returning only expected matches. No
runtime behaviour change — this module ships zero Compose UI and zero ViewModels, so no on-device
verification is needed beyond confirming the app still builds and installs:
`./gradlew installDebug`.
