# Align `feature/dashboard` Packages With Its Module — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the single file in `feature/dashboard` that shares a package name with
`core/model` and `core/scoring` (`app.readylytics.health.domain.dashboard`), and use that to
convert one genuinely-completable `CleanArchTest` path-string check into a package predicate.

**Architecture:** One-file package rename via IDE refactor, plus a `CleanArchTest.kt` predicate
rewrite that is safe to do *now* (unlike the two predicates deferred to the index doc's capstone
task) because it only concerns whether a `domain`/`data`-layer file sits inside a `feature/*`
module or not — and after this rename, `feature/dashboard`'s only offending file is fixed.

**Tech Stack:** Kotlin, Konsist (`CleanArchTest`).

**Spec:** `internal-docs/plans/POST_REMEDIATION_FOLLOWUPS.md` (Item 4, lines 337-397) and
`docs/superpowers/plans/2026-08-19-package-module-alignment-index.md` (sequencing, shared safety
verification, naming convention).

## Global Constraints

- Full gate before closing this plan: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease`.
- Baseline: 3,009 unit tests, 0 failures, 0 lint warnings (2026-08-18).
- Rename via IDE "Refactor → Rename → Rename package", not `sed`.
- Run `codegraph sync` after the rename.

## File Structure

One file, one package, no subpackages:

- `feature/dashboard/src/main/kotlin/app/readylytics/health/domain/dashboard/GetWorkoutMetricsUseCase.kt`
  → `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/domain/dashboard/GetWorkoutMetricsUseCase.kt`

  Note the target keeps `domain.dashboard` as a suffix under the module's own namespace prefix
  (`app.readylytics.health.feature.dashboard`), consistent with how `core/model`'s and
  `core/scoring`'s `domain.dashboard` files are renamed in their own module plans
  (`app.readylytics.health.core.model.domain.dashboard.*` and
  `app.readylytics.health.core.scoring.domain.dashboard.*` respectively) — all three modules end
  up with their own distinct, non-overlapping `domain.dashboard` sub-namespace.

No other file in `feature/dashboard` is in one of the 16 flagged packages (verified 2026-08-19 —
`feature/dashboard` appears in the source spec's table exactly once, for this one file).

## Task 1: Rename `GetWorkoutMetricsUseCase.kt`'s package

**Files:**
- Move: `feature/dashboard/src/main/kotlin/app/readylytics/health/domain/dashboard/GetWorkoutMetricsUseCase.kt`.
- Modify (imports only, rewritten automatically): any file importing
  `app.readylytics.health.domain.dashboard.GetWorkoutMetricsUseCase` — check with the grep in
  Step 3 rather than assuming a fixed list, since this is a `feature/dashboard`-local use case and
  may only be consumed inside that module (e.g. by its own ViewModel).

**Interfaces:**
- Produces: `app.readylytics.health.feature.dashboard.domain.dashboard.GetWorkoutMetricsUseCase`,
  same public API, simple name unchanged.

- [ ] **Step 1: Confirm the pre-rename baseline is green.**

Run: `./gradlew :feature:dashboard:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 2: Perform the rename in the IDE.**

Right-click the `feature/dashboard/src/main/kotlin/app/readylytics/health/domain/dashboard`
package node → Refactor → Rename → "Rename package" →
`app.readylytics.health.feature.dashboard.domain.dashboard` → "Search in comments and strings" +
"Search for text occurrences" checked → Preview → apply. Since there is exactly one file, review
the preview directly rather than trusting it blindly — confirm it shows exactly one file moving
and lists every consumer it intends to update.

- [ ] **Step 3: Verify zero stale references remain.**

Run: `grep -rn "app\.readylytics\.health\.domain\.dashboard\.GetWorkoutMetricsUseCase" --include="*.kt" . | grep -v /build/`
Expected: no output, or matches only using the fully-qualified new name
`app.readylytics.health.feature.dashboard.domain.dashboard.GetWorkoutMetricsUseCase`.

- [ ] **Step 4: Sync the code graph.**

Run: `codegraph sync`

- [ ] **Step 5: Run the full gate.**

Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease`
Expected: PASS, 3,009 unit tests, 0 lint warnings.

- [ ] **Step 6: Commit.**

```bash
git add -A -- 'feature/dashboard/src/main/kotlin/app/readylytics/health/feature' ':(glob)**/*.kt'
git commit -m "refactor: align feature/dashboard domain.dashboard package with module namespace"
```

## Task 2: Convert the feature-path CleanArchTest check for this one case

**Files:**
- Modify: `app/src/test/kotlin/app/readylytics/health/CleanArchTest.kt:132-161` (the
  `` `domain and data packages do not import feature package` `` test).

**Interfaces:**
- Consumes: the fact (established by Task 1) that `feature/dashboard` no longer has any file in an
  unprefixed `app.readylytics.health.domain..`/`app.readylytics.health.data..` package.

The current filter at lines 138-147 excludes files physically under `/feature/` or `\feature\`
from this rule, because a domain-shaped file that legitimately lives inside a feature module (like
`GetWorkoutMetricsUseCase.kt` did) should not be flagged as "domain layer importing feature
layer" when it imports its own module's other classes. That exclusion is still needed for other
feature modules that may have similar files today or in the future, but it can now also be
expressed as "the file's package is under `app.readylytics.health.feature..`" for the one file
this plan just moved — which is a strictly more precise, package-based version of the same
exclusion for `feature/dashboard`. Read this task's Step 1 before editing: it clarifies that the
predicate is deliberately left as the path-based OR-form for now, not narrowed to only this one
case, since narrowing it would re-break every *other* feature module's still-unaligned domain-ish
files (none currently exist, but the check should not become a promise this plan doesn't verify).

- [ ] **Step 1: Verify no other feature module currently has this problem.**

Run: `grep -rln "^package app\.readylytics\.health\.domain\.\|^package app\.readylytics\.health\.data\." --include="*.kt" feature/ | grep -v /build/`
Expected: no output. This confirms `feature/dashboard`'s `GetWorkoutMetricsUseCase.kt` was the
*only* file across all `feature/*` modules with an unprefixed `domain.*`/`data.*` package, so it is
safe to make the predicate change in Step 2 without leaving a silent gap for another feature
module.

- [ ] **Step 2: Add the package-based predicate alongside the path check.**

In `CleanArchTest.kt`, change:

```kotlin
                .filter {
                    (
                        it.hasPackage(
                            "app.readylytics.health.domain..",
                        ) ||
                            it.hasPackage("app.readylytics.health.data..")
                    ) &&
                        (it.path.contains("/src/main/") || it.path.contains("\\src\\main\\")) &&
                        !it.path.contains("/feature/") &&
                        !it.path.contains("\\feature\\")
                }.flatMap { file ->
```

to:

```kotlin
                .filter {
                    (
                        it.hasPackage(
                            "app.readylytics.health.domain..",
                        ) ||
                            it.hasPackage("app.readylytics.health.data..")
                    ) &&
                        (it.path.contains("/src/main/") || it.path.contains("\\src\\main\\")) &&
                        !it.path.contains("/feature/") &&
                        !it.path.contains("\\feature\\") &&
                        !it.hasPackage("app.readylytics.health.feature..")
                }.flatMap { file ->
```

This is deliberately additive (keeps the path check, adds the package check) rather than a
replacement — the path check still covers any feature module whose packages are not yet aligned;
the package check is the forward-looking, precise version that Step 1 confirmed is redundant
today but will be load-bearing once every feature module is done (at which point the index doc's
capstone task removes the path check entirely, once it re-verifies zero remaining unaligned
files across *all* modules, not just `feature/*`).

- [ ] **Step 3: Run `CleanArchTest` to confirm no behaviour change.**

Run: `./gradlew :app:testDebugUnitTest --tests "app.readylytics.health.CleanArchTest"`
Expected: PASS — identical result to before this task, since Step 1 already proved the new
condition is currently a no-op.

- [ ] **Step 4: Run the full gate.**

Run: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease`
Expected: PASS, 3,009 unit tests, 0 lint warnings.

- [ ] **Step 5: Commit.**

```bash
git add app/src/test/kotlin/app/readylytics/health/CleanArchTest.kt
git commit -m "test: add package-based feature predicate to CleanArchTest alongside path check"
```

## Verification

`./gradlew :feature:dashboard:testDebugUnitTest :app:testDebugUnitTest` green, full gate green,
`CleanArchTest` behaviour unchanged (same pass/fail outcome as before this plan). No on-device
verification needed — this is a use-case class with no UI change, but if convenient, open the
Dashboard screen and confirm workout metric cards still render (sanity check only, not required
by the spec's verification bar).
