# Align `database-benchmark` Packages With Its Module — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the three `data.migration` files in `database-benchmark` so that package no
longer spans `app`, `core/database`, and `database-benchmark`.

**Architecture:** Pure package rename via IDE refactor. `database-benchmark` is a
`com.android.test` module (see `.claude/CLAUDE.md` and `database-benchmark/build.gradle.kts`) with
a `benchmark` build type (`isDebuggable = false`); it is not a runtime-shipped module, so this
rename carries no user-facing risk.

**Tech Stack:** Kotlin, JUnit4 (`androidx.benchmark.junit4.AndroidBenchmarkRunner`), Room.

**Spec:** `internal-docs/plans/POST_REMEDIATION_FOLLOWUPS.md` (Item 4, lines 337-397) and
`docs/superpowers/plans/2026-08-19-package-module-alignment-index.md` (sequencing, shared safety
verification, naming convention).

## Global Constraints

- Full gate before closing this plan: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease`.
  Note: `database-benchmark`'s own tests are not unit tests (they are instrumented
  microbenchmarks — `V7DatabaseIngestMicrobenchmark.kt`, `V7DatabaseMigrationBenchmark.kt`) so
  `testDebugUnitTest` will not exercise this module directly; verification for this module uses
  `assembleDebug`/`compileDebugAndroidTestKotlin` instead (Task 1, Step 5).
- Baseline: 3,009 unit tests, 0 failures, 0 lint warnings (2026-08-18) — this plan must not change
  that number, since it does not touch anything under unit test source sets.
- Rename via IDE "Refactor → Rename → Rename package", not `sed`.
- Run `codegraph sync` after the rename.

## File Structure

One package, no subpackages, 3 files, all in `src/main` (this module's own androidTest source set
does not itself contain a `data.migration` file — verified 2026-08-19,
`find database-benchmark/src -path "*data/migration*"` returns only the three `src/main` files):

- `database-benchmark/src/main/kotlin/app/readylytics/health/data/migration/DatabaseBenchmarkFixture.kt`
- `database-benchmark/src/main/kotlin/app/readylytics/health/data/migration/V7DatabaseIngestMicrobenchmark.kt`
- `database-benchmark/src/main/kotlin/app/readylytics/health/data/migration/V7DatabaseMigrationBenchmark.kt`

Target: `database-benchmark/src/main/kotlin/app/readylytics/health/databasebenchmark/data/migration/`
(module namespace is `app.readylytics.health.databasebenchmark`, per
`database-benchmark/build.gradle.kts:namespace` — note this module sits at the top level, not
under `core/`, so its namespace has no `core.` segment, unlike the `core/*` modules).

The other two contributors to this package — `app`'s one file and `core/database`'s one file — are
untouched by this plan: `app`'s namespace equals the base package (no rename needed, ever), and
`core/database`'s file is handled by the `core/database` module plan.

## Task 1: Rename `data.migration` → `databasebenchmark.data.migration`

**Files:**
- Move (3): all three files listed above.
- Modify (imports only, rewritten automatically): any consumer of these three classes. Given they
  are benchmark fixtures/tests, consumers are expected to be limited to this module's own
  `src/androidTest` (if any) — confirm with Step 3's grep rather than assuming.

**Interfaces:**
- Produces: `app.readylytics.health.databasebenchmark.data.migration.DatabaseBenchmarkFixture`,
  `...V7DatabaseIngestMicrobenchmark`, `...V7DatabaseMigrationBenchmark` — same simple names, same
  public API.

- [ ] **Step 1: Confirm the pre-rename baseline compiles.**

Run: `./gradlew :database-benchmark:compileDebugKotlin`
Expected: PASS (this module has no unit tests to run as a baseline; compilation success is the
baseline signal).

- [ ] **Step 2: Perform the rename in the IDE.**

Right-click the
`database-benchmark/src/main/kotlin/app/readylytics/health/data/migration` package node →
Refactor → Rename → "Rename package" → `app.readylytics.health.databasebenchmark.data.migration`
→ "Search in comments and strings" + "Search for text occurrences" checked → Preview (confirm it
shows exactly 3 files moving) → apply.

- [ ] **Step 3: Verify zero stale references remain.**

Run: `grep -rn "app\.readylytics\.health\.data\.migration\." --include="*.kt" . | grep -v /build/`
Expected: matches only inside `app` and `core/database` (their own untouched `data.migration`
files, handled by other plans) — no `database-benchmark` file should appear.

- [ ] **Step 4: Sync the code graph.**

Run: `codegraph sync`

- [ ] **Step 5: Compile and lint this module.**

Run: `./gradlew :database-benchmark:compileDebugKotlin :database-benchmark:compileDebugAndroidTestKotlin ktlintCheck detekt`
Expected: PASS.

- [ ] **Step 6: Run the full repo gate to confirm no cross-module regression.**

Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease`
Expected: PASS, 3,009 unit tests, 0 lint warnings — unchanged, since this module has no unit test
source set of its own.

- [ ] **Step 7: Commit.**

```bash
git add -A -- 'database-benchmark/src/main/kotlin/app/readylytics/health/databasebenchmark' ':(glob)**/*.kt'
git commit -m "refactor: align database-benchmark data.migration package with module namespace"
```

## Verification

`./gradlew :database-benchmark:compileDebugKotlin :database-benchmark:compileDebugAndroidTestKotlin`
green, full repo gate green, Step 3's grep showing only the expected `app`/`core/database`
matches. No on-device verification needed — this module is never installed on a device
(`isDebuggable = false`, `com.android.test` target, debug variants disabled per
`internal-docs/plans/POST_REMEDIATION_FOLLOWUPS.md:264-267`).
