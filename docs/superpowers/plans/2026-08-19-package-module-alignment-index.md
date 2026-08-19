# Package/Module Alignment — Index and Sequencing

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement each module plan below task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate every Gradle package that spans more than one module, so `CleanArchTest.kt`
can express module-boundary rules as Konsist `resideInPackage(...)` predicates instead of
path-string matching, without changing any runtime behaviour.

**Architecture:** This is Item 4 of `internal-docs/plans/POST_REMEDIATION_FOLLOWUPS.md` —
"Optional: align packages with Gradle modules." That document measured, on 2026-08-18, **16
packages spanning more than one Gradle module** across 705 `src/main` Kotlin files. The fix is a
pure package rename (directory move + `package`/`import` rewrite) — never a change to which
Gradle module a file physically lives in, and never a change to behaviour. This index document
does not repeat the per-module task detail; each module below has its own self-contained plan
file that can be executed independently, in any order, except where noted.

**Tech Stack:** Kotlin, Gradle multi-module Android project, Hilt, Room (KSP), WorkManager,
kotlinx.serialization, Konsist (architecture tests), ktlint, detekt, `codegraph`.

**Spec:** `internal-docs/plans/POST_REMEDIATION_FOLLOWUPS.md`, Item 4 (lines 337-397).

## Global Constraints

- **Pre-commit gate (mandatory, every task):** `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`.
  Before handing back any module's work: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease`.
- **Known-good baseline (2026-08-18):** 3,009 unit tests, 0 failures, 0 lint warnings. A drop in
  test count or a new warning is a regression — stop and investigate, do not suppress.
- **Scoring math is off-limits.** These plans touch only package declarations and imports inside
  `domain/scoring/**` and friends — never formulas, coefficients, operator order, or constants.
  Never regenerate golden fixtures in `core/database/src/test/resources/golden/` to make a test pass.
- **Rename with an IDE-grade refactor, not `sed`.** Use Android Studio / IntelliJ's
  "Refactor → Rename → Rename package" on the package's directory node, with "Search in comments
  and strings" enabled. This is the only tool in scope that reliably rewrites every `package` and
  `import` statement project-wide — including in other Gradle modules that consume the renamed
  classes, and including `src/test`/`src/androidTest` source sets — in a single, atomic operation.
  Plain text substitution is not used in this plan because it cannot be trusted to catch every
  reference; the IDE refactor also updates same-package implicit references (files that use a
  class from the renamed package without an explicit `import` because they used to share its
  package) by inserting the needed `import`, which `sed` cannot do.
- **New files require `codegraph index`; structural moves require `codegraph sync`.** Every task
  below is a structural move — run `codegraph sync` after each one.
- **Never uninstall the production app** `app.readylytics.health` without explicit permission.
- Doc-sync rules from `.claude/CLAUDE.md` still apply, but none of these renames touch the
  ingestion pipeline's *behaviour*, the Room schema's *shape*, or scoring formulas — only package
  names — so `internal-docs/DATA_FLOW.md` needs no content change. If a module plan's rename
  happens to touch a file path that `DATA_FLOW.md` quotes verbatim, update that one path mention
  in the same commit; check with `grep -rn "app.readylytics.health.<old-package>" internal-docs/DATA_FLOW.md`
  before closing that module's plan.

## Why this is safe here (verified 2026-08-19, applies to every module plan below)

Three reflection/serialization hazards were checked project-wide before any plan below was
written, because a package rename is invisible to the Kotlin compiler in these specific cases and
would otherwise be a silent runtime break:

1. **kotlinx.serialization polymorphism.** `grep -rln "classDiscriminator\|PolymorphicSerializer\|polymorphic("`
   across the whole repo returns zero matches. The `@Serializable` entities in
   `core/database-schema` and the backup writer (`app/src/main/kotlin/app/readylytics/health/data/backup/LocalBackupManager.kt:76`,
   `Json { encodeDefaults = true }`) use plain field-based serialization only — no class
   discriminator is ever embedded in a JSON backup, so renaming an entity's package cannot break
   restoring an existing user's backup file.
2. **Room schema export.** `core/database/schemas/app.readylytics.health.data.local.HealthDatabase/*.json`
   is keyed by the `@Database` class's FQN (`app.readylytics.health.data.local.HealthDatabase`),
   which is **not** one of the 16 flagged packages and is not touched by any module plan below.
   The schema JSON content itself contains no entity/DAO FQNs (verified by grep) — only table/column
   definitions — so renaming `data.local.entity`/`data.local.dao` classes does not change any
   schema identity hash.
3. **WorkManager reflective worker lookup.** All seven `@HiltWorker`/`CoroutineWorker`
   implementations (`DataRollupWorker`, `LocalBackupWorker`, `DataCleanupWorker`,
   `BirthdayCheckWorker`, `HealthResyncWorker`, `PeriodicHealthSyncWorker`,
   `DatabaseMigrationWorker`) live under `app/src/main/kotlin/app/readylytics/health/workers/`,
   in the `app` module, whose Gradle namespace (`app.readylytics.health`) already equals the base
   package — so `app` needs **no rename** and none of these worker classes' FQNs ever change.
   WorkManager's persisted `WorkSpec` (which stores a worker's class name as a string and would
   break a pending job across an app update if that name changed) is therefore never touched by
   these plans. `core/model`'s `workers.WorkerScheduler.kt` (which does get renamed, in the
   `core/model` plan) only *enqueues* work by class literal — it is not itself a `Worker`.

Every per-module plan below inherits this verification; none of them re-derives it.

## Naming convention (mechanical, derived from each module's declared AGP `namespace`)

Every module's target root package is exactly the `namespace = "..."` value already declared in
that module's `build.gradle.kts` — no new naming decision is required. A renamed file's package
becomes `<module namespace>.<original suffix after "app.readylytics.health.">`, e.g.
`app.readylytics.health.data.local.dao.WorkoutDao` in `core/database-schema`
(`namespace = "app.readylytics.health.core.databaseschema"`) becomes
`app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao`. The `app` module's
namespace is `app.readylytics.health` — identical to the base package — so files physically in
`app` never move.

## Measured scope (2026-08-18 audit, re-verified 2026-08-19)

| Module | Packages touched | Files (src/main, recursive) | Plan |
|---|---|--:|---|
| `core/database-schema` | `data.local.entity`, `data.local.dao` | 33 | `2026-08-19-align-packages-core-database-schema.md` |
| `feature/dashboard` | `domain.dashboard` | 1 | `2026-08-19-align-packages-feature-dashboard.md` |
| `database-benchmark` | `data.migration` | 3 | `2026-08-19-align-packages-database-benchmark.md` |
| `core/database` | `di`, `domain.sync`, `data.migration`, `data.security`, `data.mapper`, `data.local.entity`, `data.local.dao` | 12 | `2026-08-19-align-packages-core-database.md` |
| `core/healthconnect` | `di`, `domain.sync`, `data.mapper` | 18 | `2026-08-19-align-packages-core-healthconnect.md` |
| `core/scoring` | `di`, `domain.dashboard`, `domain.util`, `domain.scoring` (+ `.components`, `.sleep`, `.strategies`), `domain.common` | 61 | `2026-08-19-align-packages-core-scoring.md` |
| `core/model` | `di`, `domain.sync` (+ `.link`, `.mappers`), `domain.dashboard`, `workers`, `domain.util`, `domain.user`, `domain.security`, `domain.scoring`, `domain.migration`, `domain.common`, `data.preferences` | 56 | `2026-08-19-align-packages-core-model.md` |

`app` is not listed: its namespace already equals the base package, so it requires no rename.

## Recommended sequencing

**Schedule one module at a time. Never combine two module plans in the same working session or
the same PR — this is explicitly called out as risky-to-combine in the source spec.** Suggested
order, smallest and lowest-fan-in first:

1. `core/database-schema` (pilot — proves the pattern on a leaf module with no `domain/**` code,
   only entities/DAOs)
2. `feature/dashboard` (1 file — trivial, but unlocks the one CleanArchTest predicate rewrite that
   is *fully* achievable this early, see that plan's Task 2)
3. `database-benchmark` (3 files, leaf module)
4. `core/database`
5. `core/healthconnect`
6. `core/scoring`
7. `core/model` (largest — 56 files, do last so its consumers upstream have already absorbed the
   pattern)

Within each module plan, tasks are further ordered smallest-package-first for the same reason.

## Final capstone task (only after all seven module plans are done)

`internal-docs/plans/POST_REMEDIATION_FOLLOWUPS.md` also calls out two path-string predicates in
`CleanArchTest.kt` that can *only* become package predicates once **every** core/feature module's
packages are fully prefixed — not just the ones in the measured 16-row table, since a leftover
unprefixed package anywhere in `core/*` or `feature/*` would make an app-vs-core/feature package
predicate unsound:

- `CleanArchTest.kt` — `` `feature packages are only imported from allowed app shell composition
  points` `` (the `/app/src/main/` path check that identifies "is this file physically in the
  `app` module").
- `CleanArchTest.kt` — `` `no hardcoded dispatchers outside of di packages` `` (the `/di/` path
  check — a smaller, independent cleanup, not blocked on this work, but convenient to do at the
  same time).

**Do not attempt this task as part of any individual module plan.** After module 7 (`core/model`)
is merged:

- [ ] **Step 1: Re-run the measurement.** Re-run the same per-package, per-module `find`
  audit used to produce the "Measured scope" table above, across the *entire* `src/main` tree (not
  just the 16 previously-flagged packages), to confirm zero packages currently span more than one
  Gradle module. If any remain, they are out of this plan's scope — stop and write a follow-up.

- [ ] **Step 2: Rewrite the app-shell predicate.** In
  `app/src/test/kotlin/app/readylytics/health/CleanArchTest.kt`, in the
  `` `feature packages are only imported from allowed app shell composition points` `` test,
  replace the physical-path filter:
  ```kotlin
  .filter { file ->
      (file.path.contains("/app/src/main/") || file.path.contains("\\app\\src\\main\\")) &&
          !file.name.startsWith("MainActivity") &&
          !file.name.startsWith("PrivacyRationaleActivity") &&
          allowedImportsInApp.none { pkg -> file.hasPackage("$pkg..") }
  }
  ```
  with a package predicate that identifies "not in any core/feature module":
  ```kotlin
  .filter { file ->
      !file.hasPackage("app.readylytics.health.core..") &&
          !file.hasPackage("app.readylytics.health.feature..") &&
          !file.name.startsWith("MainActivity") &&
          !file.name.startsWith("PrivacyRationaleActivity") &&
          allowedImportsInApp.none { pkg -> file.hasPackage("$pkg..") }
  }
  ```

- [ ] **Step 3: Rewrite the dispatcher-di predicate.** In the same file, in the
  `` `no hardcoded dispatchers outside of di packages` `` test, replace:
  ```kotlin
  val isDi = (path.contains("/di/") || path.contains("\\di\\"))
  ```
  with a package-suffix check, e.g.:
  ```kotlin
  val isDi = file.packagee?.name?.let { it == "di" || it.endsWith(".di") } ?: false
  ```

- [ ] **Step 4: Run the full gate.** `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease`.
  Both rewritten predicates must still pass with zero violations — if either now reports a
  violation it did not report before, a module plan above missed a file; find it with the
  re-run measurement from Step 1 before touching the test again.

- [ ] **Step 5: Commit.**
  ```bash
  git add app/src/test/kotlin/app/readylytics/health/CleanArchTest.kt
  git commit -m "refactor: convert remaining CleanArchTest path checks to package predicates"
  ```

## Self-review notes

- Every one of the 16 rows in the source spec's table is covered by exactly one module plan above
  (cross-checked against `internal-docs/plans/POST_REMEDIATION_FOLLOWUPS.md:346-363`).
- `app` deliberately has no plan — its namespace already equals the base package.
- The two CleanArchTest predicates that the source spec says become expressible only "once
  packages are aligned" are deferred to this index's capstone task rather than duplicated into
  each module plan, since neither is safely convertible until all seven module plans are done.
