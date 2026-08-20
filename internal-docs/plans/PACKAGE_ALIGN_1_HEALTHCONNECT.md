# Package Alignment 1/4 — `core/healthconnect` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the single remaining flat-namespace package `app.readylytics.health.data.healthconnect` (8 files in `core/healthconnect`) to `app.readylytics.health.core.healthconnect.data.healthconnect`, with zero behaviour change.

**Architecture:** Pure package move. No code content changes beyond `package` lines and imports. Existing tests are the regression net — no new tests are written (nothing behavioural changes, so TDD does not apply; the 3,009-test suite plus compile is the verification).

**Tech Stack:** Kotlin, Gradle, Hilt, Health Connect API.

**Source item:** `internal-docs/plans/POST_REMEDIATION_FOLLOWUPS.md` Item 4, "Remaining work — measured 2026-08-20", execution order step 1 (smallest, isolated).

**Branch:** `feat/remediation-followup-p2`

---

## Preconditions

- [ ] Working tree clean (`git status --short` empty). There are currently uncommitted edits to `app/build.gradle.kts`, `build-logic/...kotlin-android-conventions.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties` — commit or stash them before starting; they are unrelated to this plan.
- [ ] Baseline green: `./gradlew testDebugUnitTest` → 3,009 tests, 0 failures. If the baseline is not green, stop and report.

## Guardrails (do not skip)

- **Move-only diffs.** `git show --stat` of every commit must show only renames (R100 where possible) plus one-line `package`/`import` edits. Any diff touching a function body, constant, or formula is a bug — revert it. Scoring math in `domain/scoring/**` is not touched by this plan at all.
- **Never rewrite `^package ` lines outside `core/healthconnect`.** The `app` module has its OWN package `app.readylytics.health.data.healthconnect` (in `app/src/androidTest/kotlin/app/readylytics/health/data/healthconnect/`) that must keep its name — only its `import` lines pointing at core classes change. A blanket repo-wide `sed` on the FQN would silently rename the app module's own package declaration. This is why the procedure below separates "moved files", "import lines", and "inline usages".
- **detekt baseline:** entries key on `FileName.kt$Class$signature`, not path (see `DETEKT_BASELINE_BURNDOWN.md` §5). If `:core:healthconnect:detekt` fails after the move, edit the offending `<ID>` in `core/healthconnect/detekt-baseline.xml` to the new signature — do NOT regenerate the baseline.
- **No uninstall of `app.readylytics.health`** (production app). Debug variant only: `app.readylytics.health.local.grl3lb`.
- **Documentation sync:** `internal-docs/DATA_FLOW.md` references three file paths under `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/` — they must be updated in the same commit.

## Files

Move (all 8 files in the package; main 4 + test 4):

```
core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/DeviceLabel.kt
core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthChangeSynchronizerImpl.kt
core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRecordConverters.kt
core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRepositoryImpl.kt
core/healthconnect/src/test/kotlin/app/readylytics/health/data/healthconnect/HealthChangeSynchronizerImplTest.kt
core/healthconnect/src/test/kotlin/app/readylytics/health/data/healthconnect/HealthConnectPermissionSetsTest.kt
core/healthconnect/src/test/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRecordConvertersTest.kt
core/healthconnect/src/test/kotlin/app/readylytics/health/data/healthconnect/SleepDataMapperTest.kt
```

Modify (import/FQN updates only; enumerate — do not trust this list to be exhaustive):
- `app/src/main/kotlin/app/readylytics/health/ui/health/ExerciseRoutePermissionRequest.kt`
- `app/src/androidTest/kotlin/app/readylytics/health/data/healthconnect/FakeHealthConnectRepository.kt` (imports only — its own `package` line stays)
- `app/src/androidTest/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRepositoryImplTest.kt` (imports only — its own `package` line stays)
- Any file matching `grep -rl 'app\.readylytics\.health\.data\.healthconnect\.' app core feature database-benchmark --include='*.kt' | grep -v '/build/'` after the move
- `internal-docs/DATA_FLOW.md` (3 path references)

---

## Task 1: Rename `data.healthconnect` → `core.healthconnect.data.healthconnect`

- [ ] **Step 1: Enumerate and confirm the file set**

```bash
grep -rl '^package app\.readylytics\.health\.data\.healthconnect$' core/healthconnect/src --include='*.kt' | sort
```

Expected: exactly the 8 files listed above. If different, re-measure before proceeding.

- [ ] **Step 2: Move the directories with git mv**

```bash
git mv core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect \
       core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect
git mv core/healthconnect/src/test/kotlin/app/readylytics/health/data/healthconnect \
       core/healthconnect/src/test/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect
```

Note: `app/readylytics/health/core/healthconnect/` already exists in both trees (`data.mapper`, `di`, `domain.sync` live there) — the `data/healthconnect` path is new under it.

- [ ] **Step 3: Rewrite package lines in the 8 moved files only**

```bash
grep -rl '^package app\.readylytics\.health\.data\.healthconnect$' core/healthconnect/src --include='*.kt' \
  | xargs sed -i '' 's/^package app\.readylytics\.health\.data\.healthconnect$/package app.readylytics.health.core.healthconnect.data.healthconnect/'
```

- [ ] **Step 4: Rewrite import lines repo-wide (never `package` lines)**

```bash
grep -rl '^import app\.readylytics\.health\.data\.healthconnect\.' app core feature database-benchmark --include='*.kt' 2>/dev/null | grep -v '/build/' \
  | xargs sed -i '' 's/^import app\.readylytics\.health\.data\.healthconnect\./import app.readylytics.health.core.healthconnect.data.healthconnect./'
```

- [ ] **Step 5: Fix any remaining inline (non-import) FQN usages**

```bash
grep -rn 'app\.readylytics\.health\.data\.healthconnect\.' app core feature database-benchmark --include='*.kt' 2>/dev/null | grep -v '/build/' | grep -v 'core/healthconnect/src'
```

For each hit: if it is an inline qualified expression (e.g. `app.readylytics.health.data.healthconnect.HealthConnectRepositoryImpl(...)`), rewrite the qualifier to `app.readylytics.health.core.healthconnect.data.healthconnect.`. If it is the app module's own `package app.readylytics.health.data.healthconnect` declaration or an unqualified same-package reference, LEAVE IT. The `app` module's own package keeps the flat name.

- [ ] **Step 6: Verify compile**

```bash
./gradlew :core:healthconnect:compileDebugKotlin :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
```

Expected: BUILD SUCCESSFUL. Fix any stragglers the compiler finds (they will be import misses from Step 4/5).

- [ ] **Step 7: Sweep — no stale references remain**

```bash
grep -rn 'app\.readylytics\.health\.data\.healthconnect' app core feature database-benchmark --include='*.kt' --include='*.xml' --include='*.kts' 2>/dev/null | grep -v '/build/' | grep -v 'app/src/androidTest/kotlin/app/readylytics/health/data/healthconnect/'
```

Expected: empty output (the only legal remnants are the app module's own package dir, excluded here). `CleanArchTest` needs no edit: `app.readylytics.health.core.healthconnect.data.` is already in its `dataLayerPackagePrefixes` (`app/src/test/.../CleanArchTest.kt:23`).

- [ ] **Step 8: Update `internal-docs/DATA_FLOW.md`**

```bash
sed -i '' 's#core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/#core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/#g' internal-docs/DATA_FLOW.md
```

Verify with `grep -n 'healthconnect/src/main' internal-docs/DATA_FLOW.md` — all three references (HealthChangeSynchronizerImpl, HealthConnectRecordConverters, HealthConnectRepositoryImpl) now point at the new path.

- [ ] **Step 9: ktlintFormat**

```bash
./gradlew ktlintFormat
```

- [ ] **Step 10: detekt with baseline-signature check**

```bash
./gradlew :core:healthconnect:detekt
```

If it fails on a moved file: per `DETEKT_BASELINE_BURNDOWN.md` §5, diff the reported signature against the matching `<ID>` in `core/healthconnect/detekt-baseline.xml` and edit that one entry. Do not run `detektBaseline`.

- [ ] **Step 11: Full unit-test gate — count must stay 3,009**

```bash
./gradlew detekt testDebugUnitTest
```

Expected: 0 failures. Confirm the test count is unchanged (the moved test classes report under their new FQN but the count must be identical). If the count drops, a test file was lost in the move — find it before committing.

- [ ] **Step 12: Reindex and review the diff**

```bash
codegraph sync
git add -A && git status --short
```

Review `git diff --cached -M --stat`: every file must be either a pure rename (R100) or contain only `package`/`import`/FQN-qualifier line changes. Revert anything else.

- [ ] **Step 13: Commit**

```bash
git commit -m "refactor: align core/healthconnect data.healthconnect package with module namespace"
```

---

## Final verification

- [ ] `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` — all green, 3,009 tests, 0 lint warnings.
- [ ] `grep -rl '^package app\.readylytics\.health\.data\.' core/healthconnect/src` → empty.
- [ ] No schema/Room impact expected (this module has no Room code); if any build warning mentions Room or Hilt duplicate bindings, stop and investigate before continuing.
