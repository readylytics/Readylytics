# Architecture Remediation Plan

**Baseline:** branch `main` @ `63254e2f` — 2026-08-17
**Scope:** 23 self-contained steps across 6 phases (5 P0, 9 P1, 9 P2)
**Status:** Phases 0-6 complete — see [`remediation-baseline.txt`](remediation-baseline.txt) and the phase outcomes below.
Characterization golden tests pass and scoring output is unchanged. `ScoringRepositoryImpl`
went **767 -> 440 lines** and constructor dependencies **21 -> 10** across Steps 20-22, with all
ten DAOs moved out. The <400-line target was **missed by 40 lines**; `resolveEverydayTrimp`,
`computeRas` and `buildBaseSummary` remain in the repository. The <=11 constructor target was met.
(For the full arc: the file was 863 lines before Phase 4.)

> ✅ Phase 6 complete on 2026-08-18.


**Test-suite flakiness — RESOLVED 2026-08-18.** `:core:database:testDebugUnitTest` had been failing
~2 runs in 3 with a MockK inline-agent self-attach failure (`AttachNotSupportedException`, 110
cascading failures). Fixed in `readylytics.kotlin-android-conventions.gradle.kts` by preloading
`byte-buddy-agent` as `-javaagent`, so the agent never self-attaches. Note for anyone touching it:
the configuration must be **detached** (plain JVM jar, no Android variant attributes) and resolved
**eagerly into a String** (the configuration cache cannot serialise a live `Configuration`).
Caveat on the record: every "all tests green" gate in phases 3 and 4 was evaluated *before* this
fix, against an unreliable suite. The golden and determinism tests were never among the flaky ones,
so the Phase 4 scoring conclusions stand, but the broader green claims from those phases were
weaker than they appeared.

Every step names its files, its exact change, its verification command, and its
done-condition. No step requires context outside itself except the steps listed
in its **Blocked by** column.

> Line numbers refer to commit `63254e2f` and will drift as steps land. Re-grep
> before editing if the surrounding code no longer matches the quoted snippet.

---

## Corrections to the source review

Pinning down exact code for this plan surfaced three inaccuracies in the audit
that produced it. They change scope, so they are stated before the steps.

| Claim | Actual | Effect on plan |
| --- | --- | --- |
| `ComputeWorkoutTrimpUseCase:113` swallows cancellation | `execute` is **not** a suspend function — no suspension points, so `CancellationException` cannot originate inside it. Only the discarded `e` is a real defect. | Step 3 fixes it for diagnostics only. `ComputeSleepMetricsUseCase` (`suspend operator fun invoke`, L46) is the sole cancellation risk on the walk-forward path. |
| `ci.log`/`logcat.log` need a gitignore entry | `.gitignore` line 11 is `*.log`. Already covered. | Item dropped entirely. |
| Modules whose tests emigrated are "gated at nothing" | Root `jacocoTestReport` aggregates exec data from all 15 modules, so misplaced tests still count toward the 30% instruction floor. Per-package floors exist for `domain.scoring` (80%), `domain.sync` (70%), `workers` (60%). | Step 12 adds a floor for `data.repository` rather than restoring gates that were never absent. |

### Corrections made after review of the draft plan (2026-08-17)

| Claim in the draft | Actual | Effect |
| --- | --- | --- |
| Step 04 covers 4 files / 5 cancellation sites | **3 of the 5 are non-suspend** and cannot receive cancellation. `SecureFileLogSink:90` sits in `override fun log()`; `UserPreferencesSerializer:134` sits in the non-suspend top-level `UserPreferences.toProto()`, not in `readFrom`/`writeTo` (which catch only `InvalidProtocolBufferException`); `LocalBackupViewModel:248` is in a `launch{}` lambda but its `try` wraps only the non-suspend `EncryptionManager.decrypt()`. | Step 04 rescoped to **`UserUseCase.kt:38` and `:51` only**. The three non-risk catches move to step 19 as over-broad-catch narrowing. |
| Step 07's Konsist rule may be file-granular; false positives are a cheap trade | A redundant rethrow in a *non-suspend* function is exactly the unreachable branch step 03 forbids. The file-granular rule would have made the plan self-contradicting — and it was the reason the step 04 list was wrong. | Rule rewritten to scope on `functions().filter { it.hasSuspendModifier }`. No allowlist needed. |
| Step 05's two failure-path tests are writable as specified | `LocalBackupManager` calls `File.renameTo`, `ContentResolver` and `DocumentFile` directly with no injection point. Neither failure is deterministically reproducible under Robolectric. The scheme branch is duplicated at **six** sites and `pruneOldBackups:733` already carries a `// Support for file:// URIs (e.g. in tests)` branch in production code. | Step 05 split into **05a** (extract `BackupStore`, pure refactor) and **05b** (the atomicity fix plus the two tests, now trivially writable against a fake store). |

---

## How to run this plan

**One step per branch, one branch per PR.** Steps 1–8 are mutually independent
and can be parallelised across people. Steps 9–19 assume every earlier step has
landed on `main`.

**Every step ends the same way**, unless it says otherwise:

```bash
./gradlew ktlintFormat
./gradlew testDebugUnitTest
# and once, before opening the PR:
./gradlew lintRelease
```

**Repo rules that apply throughout:**

- Changes to the ingestion pipeline, the Room schema, the scoring use-cases, or
  the scoring formulas require a same-PR update to `internal-docs/DATA_FLOW.md`.
- Creating or deleting files requires `codegraph index` afterwards; moving files
  requires `codegraph sync`.
- Steps that trigger either say so explicitly.

**Scoring math is off-limits.** No step changes a formula, coefficient, or
threshold. Where a step moves scoring code, the determinism tests
(`ScoringDeterminismRegressionTest`, `ScoringPointInTimeRegressionTest`,
`ScoringSyncScopeOutputsDeterminismTest`) plus the golden snapshot added in
step 13 are the proof that nothing moved numerically.

---

## Sequence at a glance

| # | Step | Sev | Effort | Blocked by |
| --- | --- | --- | --- | --- |
| 01 | ~~Record baseline~~ ✅ **done 2026-08-17** | — | 20 min | — |
| 02 | ~~Lock the read path~~ ✅ **done 2026-08-17** | P0 | 1–2 h | 01 |
| 03 | ~~Scoring use-case exception handling~~ ✅ **done 2026-08-17** | P0 | 1 h | 01 |
| 04 | ~~Cancellation fix, `UserUseCase` (2 sites)~~ ✅ **done 2026-08-17** | P0 | 1 h | 01 |
| 05a | ~~Extract `BackupStore` seam (pure refactor)~~ ✅ **done 2026-08-17** | P0 | 1–1.5 d | 01 |
| 05b | ~~Atomic backup re-encryption + 2 tests~~ ✅ **done 2026-08-17** | P0 | 1 d | 05a |
| 06 | ~~No plaintext during key rotation~~ ✅ **done 2026-08-17** | P0 | 4–6 h | 05b |
| 07 | ~~Konsist cancellation rule (function-granular)~~ ✅ **done 2026-08-17** | P1 | 3 h | 03, 04 |
| 08 | ~~detekt + baseline~~ ✅ **done 2026-08-17** | P1 | 4 h | — |
| 09 | ~~Extract `core:database-schema`~~ ✅ **done 2026-08-17** | P1 | 3–4 d | 08 |
| 10 | ~~Distribute Hilt modules~~ ✅ **done 2026-08-17** | P1 | 2–3 d | 09 |
| 11 | ~~Relocate 48 tests~~ ✅ **done 2026-08-17** | P1 | 2 d | 09, 10 |
| 12 | ~~Repository coverage floor~~ ✅ **done 2026-08-17** | P1 | 2 h | 11 |
| 13 | ~~Golden-snapshot test~~ ✅ **done 2026-08-18** | P1 | 2 d | 02 |
| 14 | ~~Extract compute pipeline~~ ✅ **done 2026-08-18** | P1 | 4–5 d | 13 |
| 15 | ~~Collapse duplicate overloads~~ ✅ **done 2026-08-18** | P2 | 3 h | 14 |
| 16 | ~~Keyset pagination~~ ✅ **done 2026-08-18** | P2 | 2 d | 09 |
| 17 | ~~Collapse `writeJsonStreaming`~~ ✅ **done 2026-08-18** | P2 | 1 d | 16 |
| 18 | ~~`WorkoutsStateFactory`~~ ✅ **done 2026-08-18** (partial — see Outcome) | P2 | 2–3 d | 08 |
| 19 | ~~Housekeeping batch~~ ✅ **done 2026-08-18** (1 of 8 sub-items; rest deferred) | P2 | 1 d | — |
| 20 | ~~Extract `ScoringDayDataLoader`~~ ✅ **done 2026-08-18** | P2 | 3–4 d | 15 |
| 21 | ~~Move remaining pure helpers to `core:scoring`~~ ✅ **done 2026-08-18** | P2 | 2 d | 20 |
| 22 | ~~Verify decomposition targets and record~~ ✅ **done 2026-08-18** | P2 | 3 h | 21 |

**Critical path:** `01 → 05a → 05b → 06` for the P0s (≈3 days — the backup chain
is now the long pole; steps 02, 03 and 04 are a few hours combined and run
alongside it), then `08 → 09 → 10 → 11 → 12` for the module boundary (≈2 weeks),
with `13 → 14 → 15` runnable in parallel by a second person once step 02 lands.

**20 steps**, counting 05a and 05b separately. Numbering is deliberately *not*
renumbered past 05 — every "Blocked by" reference elsewhere in this document
stays valid.

---

# Phase 0 — Baseline

## Step 01 — Record a known-good baseline ✅ DONE 2026-08-17

**Effort:** 20 min · **Blocked by:** — · **Result:** `remediation-baseline.txt`

**Why first.** Every later step verifies against "same as before". Without a
recorded baseline, a pre-existing failure will be misread as a regression
introduced by the step.

### Outcome

All six commands green on `63254e2f`. Headline figures — the full record,
including per-package coverage and the lowest-covered packages, is in
[`remediation-baseline.txt`](remediation-baseline.txt).

| Command | rc | Duration | Result |
| --- | --- | --- | --- |
| `clean` | 0 | 23s | — |
| `ktlintCheck` | 0 | 17s | clean |
| `testDebugUnitTest` | 0 | 230s | **2,939 tests, 0 failures, 0 skipped** |
| `jacocoTestReport` + `Verification` | 0 | 14s | **63.58% instruction** (gate 30%) |
| per-module jacoco | 0 | 2s | skipped as UP-TO-DATE — see note below |
| `lint lintRelease` | 0 | 157s | **12 warnings, 0 errors, 0 fatal** |

**Invariants for later steps.** Total test count `2939` and aggregate
instruction coverage `63.58%` must not drop. Steps 11 and 12 move tests between
modules; neither figure should change, because the root report merges exec data
from every module in `coverageProjects`.

**Gate margins measured.** `domain.scoring` 88.60% vs 80% floor;
`domain.sync` 88.98% vs 70%; `workers` 91.87% vs 60%. All comfortable.

**Caveat found during capture — read before trusting any coverage gate.**
`:core:scoring:jacocoCoverageVerification` and its `:core:healthconnect`
counterpart reported `BUILD SUCCESSFUL in 2s` with every task `UP-TO-DATE`,
i.e. Gradle skipped them. `./gradlew clean` wipes `build/` but not the
task-history database in `.gradle/`, so a previous session's verdict was
reused. Forcing them with `--rerun-tasks` executed both in 32s and both passed.
**A green `jacocoCoverageVerification` is therefore not by itself evidence that
coverage was evaluated** — step 12 must verify with `--rerun-tasks`.

**Device available** for the manual verification steps 05 and 06 require:
Samsung SM-A576B, Android 16 (API 36), serial `R5GL23J6G5E`. Both
`app.readylytics.health` (production — never uninstall) and
`app.readylytics.health.local.grl3lb` are installed.

### Commands, for re-capture

**Do.** On a clean checkout of `main` at `63254e2f`, run each command and save
its output to `internal-docs/plans/remediation-baseline.txt`:

```bash
git switch main && git pull && git status   # must be clean
./gradlew clean

./gradlew ktlintCheck                                 2>&1 | tail -40
./gradlew testDebugUnitTest                           2>&1 | tail -60
./gradlew jacocoTestReport jacocoCoverageVerification 2>&1 | tail -30
./gradlew lint lintRelease                            2>&1 | tail -40

# per-module test counts, for step 11 to compare against
./gradlew testDebugUnitTest --console=plain 2>&1 | grep -E "tests completed|BUILD"
```

**Also record** the aggregate coverage percentage from
`build/reports/jacoco/jacocoTestReport/html/index.html`. Steps 11 and 12 move
tests between modules; this number must not drop.

**Done when.** All five commands have a recorded result and `testDebugUnitTest`
is green. If it is not green on untouched `main`, fix that before starting
Phase 1 — otherwise every subsequent verification is uninterpretable.

> ✅ Satisfied on 2026-08-17. Phase 1 is cleared to start.

---

# Phase 1 — Correctness defects

Steps 02–06. Steps 02, 03, 04 and 05a are mutually independent and can run in
parallel; the backup chain `05a → 05b → 06` is the long pole at ~3 days.

## Step 02 — Serialize the database write on the read-only compute path ✅ DONE 2026-08-17

**Severity:** P0 · **Effort:** 1–2 h · **Blocked by:** 01

**Defect.** `ScoringRepositoryImpl.computeDailySummary(targetDate)` is documented
and named as a pure read, but the private implementation it delegates to writes
`workout_records.modelTrimp` at line 278. That public overload does not take
`calculationMutex`, while both `computeAndPersistDailySummary` overloads (L106,
L127) do. A read concurrent with a resync walk-forward can therefore interleave
writes to the same rows.

**Files.**

```
core/database/src/main/kotlin/app/readylytics/health/data/repository/ScoringRepositoryImpl.kt
```

**Safety fact worth knowing before you start.** The public
`computeDailySummary(targetDate)` has **zero production callers** — only tests
and the interface declaration in
`core/model/.../domain/repository/ScoringRepository.kt:66`. There is therefore
no path where a mutex-holding function calls it, so taking the lock cannot
deadlock (`kotlinx.coroutines.sync.Mutex` is not reentrant).

**Before** — L167:

```kotlin
override suspend fun computeDailySummary(
    targetDate: LocalDate,
): DailySummary =
    computeDailySummary(
        targetDate,
        settingsRepo.userPreferences.first(),
    )
```

**After:**

```kotlin
override suspend fun computeDailySummary(
    targetDate: LocalDate,
): DailySummary {
    // The private overload persists modelTrimp back to workout_records
    // (see L278), so this path is not read-only and must serialize with
    // the walk-forward recompute like computeAndPersistDailySummary does.
    val prefs = settingsRepo.userPreferences.first()
    return calculationMutex.withLock {
        computeDailySummary(targetDate, prefs)
    }
}
```

**Note on the prefs read.** Read `userPreferences.first()` *outside* the lock,
exactly as shown. Reading it inside would hold the mutex across a DataStore
suspension for no reason and lengthen the critical section.

**Regression test to add.** In
`app/src/test/kotlin/app/readylytics/health/data/repository/ScoringRepositoryImplTest.kt`,
launch `computeDailySummary(day)` and
`computeAndPersistDailySummary(day, null, prefs)` concurrently on a `TestScope`
with a fake `WorkoutDao` that records the order and count of `upsertAll` calls,
and assert no two `upsertAll` invocations interleave.

**Docs.** Update the concurrency paragraph of `internal-docs/DATA_FLOW.md` to
state that *all four* entry points on `ScoringRepository` are serialized by
`calculationMutex`.

**Done when.**

```bash
./gradlew :app:testDebugUnitTest --tests "*ScoringRepositoryImplTest*" \
  --tests "*Determinism*" --tests "*PointInTime*"
```

is green, and the new concurrency test fails when the `withLock` is reverted.

### Outcome

- **Commit**: `58b02b58` (`fix(scoring): lock calculationMutex on computeDailySummary read path`)
- **Fix**: Wrapped `computeDailySummary(targetDate)` with `calculationMutex.withLock`, evaluating `userPreferences.first()` outside the mutex.
- **Tests & Verification**: Added concurrency test `computeDailySummary and computeAndPersistDailySummary serialize via calculationMutex` in `ScoringRepositoryImplTest.kt` verifying serialization of concurrent write/read executions. Targeted tests passed: `./gradlew :app:testDebugUnitTest --tests "*ScoringRepositoryImplTest*" --tests "*Determinism*" --tests "*PointInTime*"` (27 tests passed).
- **Docs**: Updated concurrency documentation in `internal-docs/DATA_FLOW.md` recording all `ScoringRepository` entry points as mutex-serialized.

---

## Step 03 — Stop discarding exceptions in the two scoring use-cases ✅ DONE 2026-08-17

**Severity:** P0 · **Effort:** 1 h · **Blocked by:** 01

**Defect.** Two catch blocks convert every throwable into an opaque
`Result.failure` and discard the caught exception entirely — no log, no cause. A
null-pointer or arithmetic fault anywhere in 600 lines of sleep math currently
reaches the user as the string `"SLEEP_METRICS_ERROR"` with nothing in logcat.
`ComputeSleepMetricsUseCase.invoke` is additionally a `suspend` function, so it
also absorbs `CancellationException` and lets a cancelled `HealthResyncWorker`
keep walking forward over remaining days.

**Files.**

```
core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/ComputeSleepMetricsUseCase.kt  — L605
core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/ComputeWorkoutTrimpUseCase.kt  — L113
```

**Available helper.** `logE(tag: String, throwable: Throwable? = null, msg: () -> String)`
lives in `core/model/.../domain/util/AppLog.kt:88`. `ComputeSleepMetricsUseCase`
already imports `logD` from that file; add `logE` to the same import block.
`ComputeWorkoutTrimpUseCase` needs a new
`import app.readylytics.health.domain.util.logE`.

**Before** — `ComputeSleepMetricsUseCase.kt:605` (suspend):

```kotlin
} catch (e: Exception) {
    Result.failure("Failed to compute sleep metrics", "SLEEP_METRICS_ERROR")
}
```

**After:**

```kotlin
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    logE("ComputeSleepMetrics", e) { "Sleep metrics failed for $targetDate" }
    Result.failure("Failed to compute sleep metrics", "SLEEP_METRICS_ERROR")
}
```

**Before** — `ComputeWorkoutTrimpUseCase.kt:113` (not suspend):

```kotlin
} catch (e: Exception) {
    Result.failure("Failed to compute workout TRIMP", "TRIMP_COMPUTATION_ERROR")
}
```

**After:**

```kotlin
} catch (e: Exception) {
    logE("ComputeWorkoutTrimp", e) {
        "TRIMP failed for workout $workoutStartTime..$workoutEndTime"
    }
    Result.failure("Failed to compute workout TRIMP", "TRIMP_COMPUTATION_ERROR")
}
```

> **Do not add a cancellation rethrow to `ComputeWorkoutTrimpUseCase`.**
> `execute` is not a suspend function. Importing `CancellationException` there
> adds a branch that cannot be reached and misleads the next reader. Add it only
> to `ComputeSleepMetricsUseCase`.

**Import to add in `ComputeSleepMetricsUseCase`:**
`import kotlinx.coroutines.CancellationException` — the coroutines alias, not
`java.util.concurrent.CancellationException`. The
`catch (e: CancellationException)` clause must come *before* the
`catch (e: Exception)` clause; Kotlin matches clauses top-down.

**Test to add.** In `core/scoring/src/test/.../ComputeSleepMetricsUseCaseTest.kt`:
run `invoke` inside a coroutine, cancel the job during a suspending
collaborator, and assert the job ends `CANCELLED` rather than returning a
`Result.Failure`.

**Done when.** `./gradlew :core:scoring:testDebugUnitTest` is green, the new
cancellation test fails without the rethrow, and
`./gradlew :core:scoring:jacocoCoverageVerification` still passes its 80% floor
on `app.readylytics.health.domain.scoring`.

### Outcome

- **Commit**: `771fade8` (`fix(scoring): preserve diagnostics and rethrow cancellation in scoring use cases`)
- **Fix**:
  - `ComputeSleepMetricsUseCase.kt`: Added `catch (e: CancellationException) { throw e }` before general catch block, and added `logE` diagnostic logging on generic exceptions.
  - `ComputeWorkoutTrimpUseCase.kt`: Added `logE` diagnostic error logging in non-suspend catch block without unreachable cancellation rethrow.
- **Tests & Verification**: Added `invoke_rethrowsCancellationException` unit test in `ComputeSleepMetricsUseCaseTest.kt`. Verified `./gradlew :core:scoring:testDebugUnitTest` (534 tests green) and `./gradlew :core:scoring:jacocoCoverageVerification --rerun-tasks` (passed with 100% threshold compliance).

---

## Step 04 — Stop `UserUseCase` absorbing cancellation ✅ DONE 2026-08-17

**Severity:** P0 · **Effort:** 1 h · **Blocked by:** 01

**Scope — verified against the AST, not by grep.** The repo has 19 files with a
bare `catch (… : Exception)` and no `CancellationException` handling. Only **two
sites, in one file**, are genuine cancellation risks. Step 03 covered the third
(`ComputeSleepMetricsUseCase`). Everything else is out of scope.

A site is a risk only when **both** hold: the *enclosing* function or coroutine
lambda suspends, **and** the `try` block actually contains a suspension point.
File-level grep for `suspend` establishes neither. An earlier draft of this step
listed four files on that basis; three were wrong, including one whose catch sits
in a non-suspend top-level extension function 34 lines below the last suspend
function in the file.

| Site | Enclosing function | suspend? | Suspension point in `try`? | Risk |
| --- | --- | --- | --- | --- |
| `SecureFileLogSink.kt:90` | `override fun log()` L70 | no | — | no |
| `UserPreferencesSerializer.kt:134` | `fun UserPreferences.toProto()` L100 | no | — | no |
| `UserUseCase.kt:38` | `override suspend fun updateBirthday()` L22 | **yes** | **yes** — 3 suspend calls | **YES** |
| `UserUseCase.kt:51` | `override suspend fun calculateAndSetMaxHr()` L42 | **yes** | **yes** — `.first()` | **YES** |
| `LocalBackupViewModel.kt:248` | `viewModelScope.launch {}` L240 | lambda | no — `decrypt()` is `override fun` | no |

**Files and lines — the actual scope.**

```
app/src/main/kotlin/app/readylytics/health/domain/user/UserUseCase.kt
    catch L38, inside  override suspend fun updateBirthday(date: LocalDate)  L22
    catch L51, inside  override suspend fun calculateAndSetMaxHr()           L42
```

**Why `updateBirthday` is the one that matters.** Its `try` wraps
`settingsRepo.updateBirthday(date)`,
`scoringRepository.computeAndPersistDailySummary()` — which takes
`calculationMutex` — and `settingsRepo.userPreferences.first()`. If the caller's
scope is cancelled part-way, the current code turns that into
`Result.failure("Failed to update birthday", "BIRTHDAY_UPDATE_ERROR")`. The user
is shown a save failure for merely navigating away, and the preference writes
that did land are never signalled as partial.

**Pattern to apply at each site:**

```kotlin
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    // existing handling, unchanged
}
```

**Reference implementation already in the repo.** `LocalBackupManager.kt:129-132`
and `:254-256` already do exactly this. Copy that shape rather than inventing a
variant.

**Do not touch the three non-risk sites.** Adding a `CancellationException`
rethrow to a non-suspend function creates a branch that cannot be reached and
misleads the next reader — the same reasoning step 03 applies to
`ComputeWorkoutTrimpUseCase`. The plan must not contradict itself. The
file-level grep will keep listing those files; that is a defect in the grep, and
step 07 fixes it by making the rule function-granular.

**The three non-risk catches are still over-broad**, but that is a separate,
lower-severity concern. Narrowing them is a step 19 housekeeping item:

| Site | Should catch | Currently catches |
| --- | --- | --- |
| `UserPreferencesSerializer.kt:134` — `LocalDate.parse` | `DateTimeParseException` | `Exception` |
| `LocalBackupViewModel.kt:248` — `decrypt` | `GeneralSecurityException` | `Exception` |
| `SecureFileLogSink.kt:90` — file append | `IOException` | `Exception` |

**Done when.** `./gradlew :app:testDebugUnitTest` is green, and this returns
exactly the two `UserUseCase` lines and nothing else:

```bash
awk '/override suspend fun/{s=1} /^    fun |^fun /{s=0} s && /catch \(.*: Exception\)/{print FILENAME": "NR": "$0}' \
  app/src/main/kotlin/app/readylytics/health/domain/user/UserUseCase.kt
```

The whole-repo check belongs to step 07's Konsist rule, which understands
function boundaries. Do not gate this step on a repo-wide grep.

### Outcome

- **Commit**: `b8310778` (`fix(user): rethrow CancellationException in UserUseCase suspend methods`)
- **Fix**: Updated `UserUseCase.kt` suspend functions `updateBirthday(date: LocalDate)` and `calculateAndSetMaxHr()` to rethrow `CancellationException` before generic exception handling. Non-suspend catch sites left for Step 19 housekeeping as planned.
- **Tests & Verification**: Added unit tests `updateBirthday rethrows CancellationException` and `calculateAndSetMaxHr rethrows CancellationException` in `UserUseCaseTest.kt`. AST / awk verified 2 matching catch sites preceded by cancellation rethrow. `./gradlew :app:testDebugUnitTest --tests "app.readylytics.health.domain.user.UserUseCaseTest"` passed (4/4 tests).

---

## Step 05a — Extract a `BackupStore` seam ✅ DONE 2026-08-17

**Severity:** P0 (enabler) · **Effort:** 1–1.5 d · **Blocked by:** 01

**Why this exists.** Step 05b needs two failure-path tests that the current class
cannot support: `LocalBackupManager` calls `File.renameTo`, `ContentResolver`, and
`DocumentFile` directly, with no injection point. Forcing a `renameTo` failure or a
mid-copy SAF write failure deterministically is not practical against the real
APIs under Robolectric.

**But this is not a test-only seam.** The scheme branch is already duplicated at
six sites:

```
createBackup        L101/108  vs  L116-119
deleteBackup        L148      vs  L163
reencryptBackups    L195      vs  L200      (read)
reencryptBackups    L229      vs  L234      (write)
listBackups         L683      vs  L699
pruneOldBackups     L729      vs  L733
```

and `pruneOldBackups:733` already carries a branch that exists purely for tests:

```kotlin
} else {
    // Support for file:// URIs (e.g. in tests)
    customUri.path?.let { DocumentFile.fromFile(File(it)) }
}
```

The missing seam has already been paid for, in the worst available currency. This
step is the decomposition the class has been missing; testability falls out of it.

**Files.**

```
app/src/main/kotlin/app/readylytics/health/data/backup/LocalBackupManager.kt
app/src/main/kotlin/app/readylytics/health/data/backup/BackupStore.kt        — new
app/src/main/kotlin/app/readylytics/health/data/backup/FileBackupStore.kt    — new
app/src/main/kotlin/app/readylytics/health/data/backup/SafBackupStore.kt     — new
app/src/main/kotlin/app/readylytics/health/di/…                              — binding
```

**Interface.**

```kotlin
interface BackupStore {
    suspend fun list(): List<BackupFileInfo>
    suspend fun read(location: BackupLocation): InputStream
    /** Publishes [source] as [name], replacing any existing entry atomically. */
    suspend fun publish(source: File, name: String)
    suspend fun delete(location: BackupLocation)
}
```

Two implementations — `FileBackupStore` (internal `filesDir/backups`) and
`SafBackupStore` (tree URI) — selected **once** from `backupDirectoryUri` instead
of re-branched at six call sites.

**Constraints for this step:**

- **Pure refactor. No behaviour change.** `publish` initially keeps today's
  semantics, bugs included — the atomicity fix is step 05b. Doing both at once
  means the existing tests cannot serve as the safety net.
- The existing `LocalBackupManagerTest` must stay green **without modification**.
  If it needs changing, behaviour changed and something went wrong.
- Delete the `// Support for file:// URIs (e.g. in tests)` branch at L733. If it
  survives this step, the seam was not actually taken.

**Done when.** `./gradlew :app:testDebugUnitTest --tests "*LocalBackup*" --tests
"*LocalRestore*"` is green with the test file unchanged; `grep -c 'scheme ==' 
LocalBackupManager.kt` returns `0`; and `codegraph index` has been run for the
three new files.

### Outcome

- **Commit**: `95e4e2ce` (`refactor(backup): extract BackupStore and BackupStoreFactory seams`)
- **Refactor**:
  - Created `BackupStore` interface (`list`, `read`, `publish`, `delete`, `prune`) and `BackupStoreFactory` interface.
  - Created `FileBackupStore` (internal storage) and `SafBackupStore` (Storage Access Framework).
  - Injected `BackupStoreFactory` into `LocalBackupManager` and bound `DefaultBackupStoreFactory` in `FeaturePortModule`.
  - Removed all `scheme ==` branches and test-only branches (`// Support for file:// URIs`) from `LocalBackupManager`.
- **Verification**: `grep -c 'scheme ==' LocalBackupManager.kt` returned `0`. `./gradlew :app:testDebugUnitTest --tests "*LocalBackup*" --tests "*LocalRestore*"` passed without test modifications. Ran `codegraph index`.

---

## Step 05b — Make backup re-encryption atomic and make its failures visible ✅ DONE 2026-08-17

**Severity:** P0 · **Effort:** 1 d · **Blocked by:** 05a

**Defects — four in one method:**

1. **Silent failure.** L233 `newZipPath.renameTo(File(backupUri.path!!))` ignores
   the returned boolean. A failed rename reports success while the file on disk
   is still under the old password; the user then discards that password.
2. **Destructive non-atomic write.** L230 opens the original `content://`
   document with mode `"wt"` — truncate — then copies the new zip in. Process
   death mid-copy destroys the user's only backup. The comment at L226 claims
   "atomic rename-swap"; it is not.
3. **NPE.** `backupUri.path!!` at L199 and L233 throws on a path-less URI.
4. **Leak.** The `ZipFile` opened at L205 is never closed. `createZip` at L280
   has the same leak.

**Files.**

```
app/src/main/kotlin/app/readylytics/health/data/backup/FileBackupStore.kt  — publish()
app/src/main/kotlin/app/readylytics/health/data/backup/SafBackupStore.kt   — publish()
app/src/main/kotlin/app/readylytics/health/data/backup/LocalBackupManager.kt — ZipFile leaks
```

After 05a the fix lives inside each store's `publish`, so the two backends no
longer share one tangled branch. Line references below are to the pre-05a code,
for tracing the defect only.

**Before** — L226-234:

```kotlin
// 4. Overwrite original (atomic rename-swap)
if (backupUri.scheme == "content") {
    context.contentResolver.openOutputStream(backupUri, "wt")?.use { output ->
        newZipPath.inputStream().use { it.copyTo(output) }
    } ?: throw IllegalStateException("Could not write re-encrypted backup")
} else {
    newZipPath.renameTo(File(backupUri.path!!))
}
```

**After:**

```kotlin
// 4. Swap in the re-encrypted file.
if (backupUri.scheme == "content") {
    // SAF has no rename-over primitive, so write a sibling document first
    // and only delete the original once the new one is fully on disk.
    val dir = DocumentFile.fromTreeUri(context, treeUri)
        ?: error("Could not access backup directory")
    val staged = dir.createFile("application/zip", "${info.name}.rotating")
        ?: error("Could not stage re-encrypted backup")
    context.contentResolver.openOutputStream(staged.uri)?.use { output ->
        newZipPath.inputStream().use { it.copyTo(output) }
    } ?: error("Could not write re-encrypted backup")
    check(staged.length() == newZipPath.length()) {
        "Staged backup is truncated; original kept"
    }
    check(DocumentFile.fromSingleUri(context, backupUri)?.delete() == true) {
        "Could not replace original backup"
    }
    check(staged.renameTo(info.name)) {
        "Could not finalize re-encrypted backup"
    }
} else {
    val target = backupUri.path?.let(::File)
        ?: error("Backup URI has no file path")
    check(newZipPath.renameTo(target)) {
        "Could not replace $target with rotated backup"
    }
}
```

**Also in this step:**

- Wrap both `ZipFile` instances in `use { }` — the one at L205
  (`zipFile.extractAll`) and the one inside `createZip` at L280.
- Replace the `File(backupUri.path!!)` at L199 with the same `?: error(…)` guard.
- The `treeUri` referenced above is
  `settingsRepository.userPreferences.first().backupDirectoryUri?.toUri()`.
  Hoist it once above the `backups.forEach` loop rather than reading preferences
  per backup.

**Tests to add — now writable, because of 05a.** Both use a `FakeBackupStore`
implementing the four-method interface; neither touches `File.renameTo`,
`ContentResolver`, or `DocumentFile`:

- `publish` returns/throws a failure — assert `reencryptBackups` returns
  `Result.failure`, **and** that a subsequent `read` still yields an archive that
  opens with the *old* password. Asserting the failure alone is not enough; the
  defect is that the original is destroyed, not merely that the call fails.
- `publish` throws part-way through consuming `source` — same two assertions.
  This is the SAF truncate-then-write scenario in backend-independent form.

A third test worth adding while the fake is in front of you: assert `publish` is
never called with a `source` whose length is 0, which is the shape a truncated
staged write would take.

> **Do not test this by forcing a real `File.renameTo` failure.** It is
> technically possible — make the target an existing non-empty directory named
> `backup_*.zip` and `rename(2)` fails with `EISDIR`, and `listBackups`'s
> file-scheme filter checks only the name, not `isFile`, so such a directory is
> listed. That test would exercise POSIX semantics rather than this code, and it
> would silently stop testing anything the day someone adds `isFile` to that
> filter — which they should, since `pruneOldBackups` already does and the
> inconsistency is a latent bug of its own. Note it; fix it in 05a.

> **Manual verification still required, even with the fake.** The fake proves
> the error handling; it cannot prove the assumptions about SAF. Real
> `DocumentsProvider` semantics — in particular whether `DocumentFile.renameTo`
> on a staged document behaves as assumed on Samsung's One UI — is exactly what
> a fake abstracts away. Before merging, run on a device: create a backup, set a
> password, rotate it, then restore from the rotated backup on a fresh install.
>
> Device confirmed available at baseline: Samsung **SM-A576B**, Android 16
> (API 36), serial `R5GL23J6G5E`. Use the debug build
> `app.readylytics.health.local.grl3lb`. **Never uninstall
> `app.readylytics.health`** — that is the production install and holds real
> user data.

**Done when.** Both new tests pass,
`./gradlew :app:testDebugUnitTest --tests "*LocalBackup*" --tests "*LocalRestore*"`
is green, and the on-device rotate-then-restore round trip succeeds.

### Outcome

- **Commit**: `09400bca` (`fix(backup): atomic backup re-encryption and close ZipFile resource leaks`), followed up by `c4df761e` (`fix(backup): address Phase 1 backup store follow-ups (P1-1 through P2-6)`)
- **Fix**:
  - `SafBackupStore`: Implemented staging write to `$name.rotating`, verified staging file length matches source, guarded existing backup with `$name.bak` rename before replacing, restored `$name.bak` on staged rename failure, and safely handled cleanup via `try/catch` (P1-2, P2-6).
  - `FileBackupStore`: Atomic rename with verified intermediate temporary file staging and guaranteed cleanup, never deleting target prior to rename (P1-1).
  - `BackupStoreFactory`: Eliminated `path!!` in favor of safe `path ?: error(...)` (P2-5).
  - `LocalBackupManager`: Wrapped `ZipFile` instances in `use { }` blocks during archive extraction, archive re-encryption, and backup creation (`createZip`) to prevent file descriptor leaks. Hoisted `treeUri` resolution outside backup loops.
- **Tests & Verification**: Added `FakeBackupStore` and unit tests in `LocalBackupManagerTest.kt` (`reencryptBackups failure preserves original backup and returns failure` with actual decryption verification, `reencryptBackups partial publish preserves original backup and returns failure`, and `reencryptBackups never calls publish with zero length source`) as well as `FileBackupStoreTest.kt` (`publish_failedRename_preservesExistingTarget`). Verified `./gradlew :app:testDebugUnitTest --tests "*LocalBackup*" --tests "*LocalRestore*" --tests "*BackupStore*"`.

---

## Step 06 — Stop writing the health export to disk in plaintext during key rotation ✅ DONE 2026-08-17

**Severity:** P0 · **Effort:** 4–6 h · **Blocked by:** 05b

**Defect.** `LocalBackupManager.kt:206` — `zipFile.extractAll(tempDir.absolutePath)`
decrypts the entire backup (every sleep session, heart-rate sample, and workout)
to an unencrypted JSON file before re-zipping. The `finally` at L242 deletes it,
so the happy path is clean, but a crash or process kill between extract and
cleanup leaves a complete plaintext health record on disk indefinitely. The
directory is `context.cacheDir`, which is app-private — that bounds the exposure
to a rooted or backup-extracted device rather than to other apps, but does not
eliminate it.

**Files.**

```
app/src/main/kotlin/app/readylytics/health/data/backup/LocalBackupManager.kt
    reencryptBackups, extract at L205-206
```

**Preferred fix.** Stream entries from the old archive straight into the new one
without materializing plaintext. zip4j exposes `ZipFile.getInputStream(FileHeader)`
and `ZipOutputStream`, so the JSON never becomes a file:

```kotlin
ZipFile(tempZip, oldPassword?.toCharArray()).use { source ->
    ZipOutputStream(
        tempZipForNew.outputStream(),
        newPassword?.toCharArray(),
    ).use { sink ->
        source.fileHeaders.forEach { header ->
            sink.putNextEntry(
                ZipParameters().apply {
                    fileNameInZip = header.fileName
                    if (newPassword != null) {
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }
                },
            )
            source.getInputStream(header).use { it.copyTo(sink) }
            sink.closeEntry()
        }
    }
}
```

**Fallback if the streaming API proves unworkable.** Keep the extract, but before
`delete()`, overwrite the plaintext file's bytes: open it
`RandomAccessFile(f, "rws")`, write zeros over `f.length()`, then delete. Also
register a best-effort sweep of `cacheDir/reencrypt_temp` at application start so
a crash-orphaned file does not survive to the next session. Note honestly in the
code comment that this is mitigation, not prevention — flash wear-levelling means
an overwrite is not a guaranteed erase.

**Docs.** `docs/privacy.md` describes local backup handling. If it currently
implies backup contents are never written unencrypted, this step is what makes
that true — check the wording and update it in the same PR.

**Done when.** A rotation run produces no `.json` file in
`cacheDir/reencrypt_temp` at any point (verify with a debug-build file watcher or
a test asserting the directory stays empty of `*.json`), and the
rotate-then-restore round trip from step 05b still succeeds.

### Outcome

- **Commit**: `71143b8f` (`fix(backup): stream entries in reencryptBackups without decrypting to disk`)
- **Fix**: Replaced plaintext disk extraction with streamed copying using `net.lingala.zip4j.io.outputstream.ZipOutputStream`. Iterates through `source.fileHeaders` and copies streams directly to `sink` without ever materializing `.json` files to disk or cache.
- **Tests & Verification**: Added `reencryptBackups does not write plaintext JSON files to tempDir during rotation` unit test verifying no `.json` files exist on disk during or after rotation and that the re-encrypted archive decrypts successfully with the new password. Verified against `docs/privacy.md`.

> ✅ Phase 1 complete on 2026-08-17. Phase 2 is cleared to start.

---

# Phase 2 — Guardrails

Lock in Phase 1 before refactoring.

## Step 07 — Add a Konsist rule that fails the build on swallowed cancellation ✅ DONE 2026-08-17

**Severity:** P1 · **Effort:** 3 h · **Blocked by:** 03, 04

**Why.** Steps 03 and 04 fix the instances. This step makes the class of bug
unable to return, using the enforcement mechanism the project already trusts for
dispatchers.

**Files.**

```
app/src/test/kotlin/app/readylytics/health/CleanArchTest.kt
    add a ninth @Test alongside the existing eight
```

**Existing rule to mirror.** `` `no hardcoded dispatchers outside of di packages` ``
at L194. It scans `Konsist.scopeFromProject()`, filters to `/src/main/`,
regex-matches `file.text`, and asserts the violation list is empty with a message
naming each offender. Copy that structure exactly.

**Rule to add — scope on functions, not files.**

```kotlin
@Test
fun `suspend functions do not swallow CancellationException`() {
    val violations =
        Konsist
            .scopeFromProject()
            .functions(includeNested = true, includeLocal = true)
            .filter { it.containingFile.path.contains("/src/main/") }
            .filter { it.hasSuspendModifier }
            .filter { it.text.contains(Regex("""catch \(\w+: Exception\)""")) }
            .filter { !it.text.contains("CancellationException") }
            .map { "${it.containingFile.name}:${it.name}() swallows CancellationException" }

    org.junit.Assert.assertTrue(
        "Suspend functions must rethrow CancellationException before " +
            "catching Exception. Violations:\n${violations.joinToString("\n")}",
        violations.isEmpty(),
    )
}
```

> **Why function-granular and not file-granular.** An earlier draft of this rule
> scanned `files` and accepted the false positives, on the reasoning that "the
> false positive costs one redundant rethrow, the false negative costs a hung
> resync." That reasoning was wrong. A redundant rethrow in a **non-suspend**
> function is not a harmless cost — it is precisely the unreachable, misleading
> branch that step 03 forbids, so the file-granular rule would have forced the
> plan to contradict itself. Three of the five sites the file-level grep
> surfaced for step 04 turned out to be non-suspend. `hasSuspendModifier` is an
> AST property; use it.

**Known residual imprecision, and why it is acceptable.** This rule still flags a
suspend function whose `try` happens to contain no suspension point (so
cancellation cannot actually arrive there). That is a much smaller set, and a
rethrow in a genuinely-suspending function is defensible rather than misleading —
any future edit that adds a suspending call inside that `try` makes it load
bearing. Accept those; do not allowlist them.

**Run it before wiring it in.** After steps 03 and 04 the rule should be green.
If it names anything else, that is a real finding — investigate rather than
allowlist. Candidates worth checking by hand because they contain both suspend
functions and broad catches: `SecureFileStore.kt`, `SqlCipherKeyManager.kt`.

**Done when.** `./gradlew :app:testDebugUnitTest --tests "*CleanArchTest*"` is
green; reverting step 03's rethrow makes it fail naming
`ComputeSleepMetricsUseCase.kt:invoke()`; and adding a bare
`catch (e: Exception)` to the non-suspend `UserPreferences.toProto()` does **not**
make it fail.

### Outcome

- **Commit**: `95cbcf3f` (`test(arch): add Konsist rule prohibiting swallowed CancellationException in suspend functions`)
- **Fix**: Added `@Test fun \`suspend functions do not swallow CancellationException\`()` in `CleanArchTest.kt` using function-granular AST inspection on suspend modifier.
- **Tests & Verification**: Verified `./gradlew :app:testDebugUnitTest --tests "*CleanArchTest*"` passes. Verified synthetic suspend function catching `Exception` without rethrowing `CancellationException` causes rule failure, while non-suspend catch sites remain allowed.

---

## Step 08 — Add detekt with a baseline pinned at today's state ✅ DONE 2026-08-17

**Severity:** P1 · **Effort:** 4 h · **Blocked by:** —

**Why.** ktlint is configured for formatting only (`.editorconfig`:
`max_line_length = 120`). Nothing in CI flags a 490-line method, a
21-parameter constructor, or a discarded exception — which is exactly why the
defects in Phase 1 and the god object in Phase 4 passed review. A baseline means
today's violations are frozen rather than blocking, so the number can only go
down.

**Files.**

```
gradle/libs.versions.toml                                                  — add version + plugin alias
build-logic/src/main/kotlin/readylytics.kotlin-android-conventions.gradle.kts — apply to every module
config/detekt/detekt.yml                                                   — new, shared config
<module>/detekt-baseline.xml                                               — new, generated PER MODULE
.github/workflows/ci.yml                                                   — new step
```

> **Do not use a single shared baseline file.** `detektBaseline` is a
> per-*project* task. Point all 15 modules at one `config/detekt/baseline.xml`
> and each overwrites the last, so the file only ever holds one module's
> findings — regenerating yields ~40 entries and `./gradlew detekt` fails
> immediately. The first implementation of this step hit exactly that, and the
> committed baseline could not be reproduced by the command that made it. The
> shared *config* is correct; only the baseline must be per-module.

**Catalog entries:**

```toml
# [versions]
detekt = "1.23.8"

# [plugins]
detekt = { id = "io.gitlab.arturbosch.detekt", version.ref = "detekt" }
```

**Rules to enable** — start narrow; the point is the four that map to real
findings:

- `complexity > LongMethod` — threshold 60
- `complexity > LongParameterList` — `functionThreshold: 8`, `constructorThreshold: 10`
- `complexity > CyclomaticComplexMethod` — threshold 15
- `exceptions > SwallowedException` — `ignoredExceptionTypes: ['CancellationException']`
- `exceptions > TooGenericExceptionCaught` — `active: false` initially; the
  codebase deliberately uses `Result`-returning boundaries

**Generate the baseline, then wire CI:**

```bash
./gradlew detektBaseline      # writes <module>/detekt-baseline.xml for each module
./gradlew detekt              # must now be green
```

**Also disable the rules the project has already opted out of.** `detekt.yml`
only *overrides* the rules it names; every other default stays active. Leaving
them on froze 996 noise entries that buried the ~300 real ones:

| Rule | Entries | Why disable |
| --- | --- | --- |
| `MagicNumber` | 694 | scoring coefficients; they *are* the domain |
| `FunctionNaming` | 280 | `.editorconfig` disables ktlint's equivalent |
| `WildcardImport` | 22 | `.editorconfig` disables ktlint's equivalent |
| `RethrowCaughtException` | 1 | flags the exact pattern step 07's Konsist rule **requires** — the two gates would contradict each other |

```yaml
# .github/workflows/ci.yml, insert after the ktlint step:
      - name: Static analysis (detekt)
        if: ${{ !cancelled() }}
        run: ./gradlew detekt
```

**Record the numbers.** Append the baseline's violation count per rule to
`internal-docs/plans/remediation-baseline.txt`. Steps 14, 15, 17 and 18 each
remove entries from this baseline; that delta is how their success is measured.

**Housekeeping.** New files created — run `codegraph index` after this step.

**Done when.** `./gradlew detekt` passes on unmodified `main`, and adding a
throwaway 70-line function makes it fail.

### Outcome

- **Commit**: `044a6502` (`build(detekt): integrate detekt static analysis with frozen baseline and CI workflow`)
- **Fix**: Added detekt 1.23.8 to version catalog, build-logic convention plugin, and root/app build configs. Created `config/detekt/detekt.yml` configuring complexity and exception rules, and wired the detekt step into `.github/workflows/ci.yml`.
- **Review follow-up**: the initial integration used one shared
  `config/detekt/baseline.xml`, which could not be regenerated (per-project task,
  every module overwrote the last) and froze 996 noise entries from rules the
  project had deliberately opted out of. Corrected to per-module
  `<module>/detekt-baseline.xml` plus four rule disables; baseline **1638 → 641**,
  reconciling exactly. See `remediation-baseline.txt` §10a.
- **Tests & Verification**: `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease`
  BUILD SUCCESSFUL; 2,962 tests / 0 failures; lint 12 W / 0 E (Phase 0 baseline
  unchanged); `detektBaseline` now round-trips.

### Step 07 review follow-up

The rule as first written matched only `catch (… : Exception)`.
`catch (… : Throwable)` swallows cancellation identically and was invisible to
it — all four existing sites happened to handle cancellation correctly, so no
live defect, but the rule could not prevent the next one. Widened to cover both,
with a backreferenced escape hatch so `catch (t: Throwable) { …; throw t }`
(the `SafBackupStore.publish` shape) stays compliant without naming
`CancellationException`. Verified by mutation — suspend+Throwable-swallow now
fails, suspend+Throwable-rethrow passes, non-suspend+Exception still passes.

> ✅ Phase 2 complete on 2026-08-17. Phase 3 is cleared to start.

---

# Phase 3 — Module boundary

Strictly sequential.

## Step 09 — Extract `core:database-schema` out of `core:model` ✅ DONE 2026-08-17

**Severity:** P1 · **Effort:** 3–4 d · **Blocked by:** 08

**Problem.** `core:model` holds 218 files spanning Room DAOs (17), Room entities
(17), every repository interface, validation, preference types, sync mappers, and
`WorkerScheduler`. `readylytics.compose-feature-conventions.gradle.kts` puts it on
every feature module's compile classpath, so `feature:dashboard` can legally write
`import app.readylytics.health.data.local.dao.HeartRateDao`. The layering rule
exists only as a Konsist regex; the compiler does not enforce it.

**Scope decision.** Split **two ways, not three**. Moving the schema out is what
removes DAO visibility from the UI; a further `core:domain-api` split adds churn
without adding enforcement, because what remains in `core:model` after the schema
leaves *is* the domain API. Revisit a three-way split only if `core:model` grows
past ~150 files again.

**Move these directories:**

```
core/model/src/main/kotlin/app/readylytics/health/data/local/dao/     → core/database-schema/…  (17 files)
core/model/src/main/kotlin/app/readylytics/health/data/local/entity/  → core/database-schema/…  (17 files)
plus any Converters / row-projection types the DAOs return
```

**Keep package names unchanged.** Kotlin and Android permit the same package
across Gradle modules. Renaming packages in the same commit as a module move
produces an unreviewable diff. Package alignment is a sub-item of step 19, and it
is optional.

**Procedure:**

1. `settings.gradle.kts`: add `include(":core:database-schema")`.
2. New `core/database-schema/build.gradle.kts`:
   `id("readylytics.android-library-conventions")`, `alias(libs.plugins.ksp)`,
   `implementation(libs.room.runtime)`, `implementation(project(":core:model"))`
   — the entities reference domain enums such as `RecordType`.
3. `git mv` the two directories. Do not edit file contents in this commit.
4. Add `implementation(project(":core:database-schema"))` to `:core:database`,
   `:core:healthconnect`, `:app`, and `:database-benchmark`.
5. Build. Every module that fails to compile is one that was reaching into the
   schema. Fix each by adding the dependency *only* if the reach is legitimate —
   a feature module that fails is a real layering violation and needs a
   repository method instead.
6. Do **not** remove `implementation(project(":core:model"))` from
   `readylytics.compose-feature-conventions.gradle.kts`. Features legitimately
   need domain models. The schema is now simply not reachable through it.
7. Add `":core:database-schema"` to `coverageProjects` in the root
   `build.gradle.kts` (L21) so the aggregate report does not silently shrink.

**Room schema location.** `readylytics.room-conventions.gradle.kts` sets
`schemaDirectory("$projectDir/schemas")`. `@Database` stays in `core:database`
(`HealthDatabase.kt`), so the schema JSON directory does not move. Confirm
`core/database/schemas/` is unchanged after the build — a moved schema directory
breaks migration tests.

**Docs and housekeeping.** Schema files move ⇒ `internal-docs/DATA_FLOW.md` must
be updated in this PR. Run `codegraph sync` after the move.

**Done when.** `./gradlew testDebugUnitTest lintRelease` is green;
`grep -rn "data.local.dao\|data.local.entity" feature/*/src/main` returns
nothing; and `:core:database-schema` appears in no feature module's dependency
list.

### Outcome

- **Commit**: `721d0a42` (`refactor(core): extract core:database-schema module for DAOs and entities (phase 3 step 09)`); follow-up `4d22de4e`.
- **Module**: new `:core:database-schema` (`app.readylytics.health.core.databaseschema`; `readylytics.android-library-conventions` + kotlin.serialization; deps `core:model`, `androidx.core.ktx`, `room.runtime`, `kotlinx.serialization.json`; no Room compiler/ksp/hilt).
- **Moved**: 34 files via `git mv` (17 DAO interfaces incl `SleepHrSample.kt`, 17 entities incl `LocalDateSerializer.kt`) from `core:model/.../data/local/{dao,entity}`; package names unchanged.
- **Consumers**: `core:database`, `core:healthconnect`, `app`, `database-benchmark` get `implementation(project(":core:database-schema"))`; `core:scoring` gets `testImplementation`. `implementation(libs.room.runtime)` removed from `core:model`.
- **Room schema** `core/database/schemas/` byte-identical (verified); added to root `coverageProjects`; `:core:database-schema:detektBaseline` generated.
- **Docs**: `internal-docs/DATA_FLOW.md` updated; follow-up `4d22de4e` regenerated `:core:model:detektBaseline` (86→53 entries) and fixed a stale path in `internal-docs/ai-recommendations/DAILY_PROMPT_TEMPLATE.md`.
- **Verification**: `./gradlew testDebugUnitTest lintRelease` green; `grep -rn "data.local.dao\|data.local.entity" feature/*/src/main` returns nothing.

---

## Step 10 — Move Hilt modules into the modules whose implementations they bind ✅ DONE 2026-08-17

**Severity:** P1 · **Effort:** 2–3 d · **Blocked by:** 09

**Problem.** All 9 `@Module` files live in `app/di/`; every core and feature
module has zero. `RepositoryModule.kt` imports 12 concrete `*Impl` classes out of
`core:database` in order to bind them, so `:app` depends on the implementation
detail of every library module and no module can be assembled with a real graph
in isolation.

**Target distribution:**

| Current file | Lines | Moves to |
| --- | --- | --- |
| `RepositoryModule.kt` | 163 | split: repository binds → `:core:database`; scoring binds (`ScoringCalculator`, `RhrBaselineProvider`) → `:core:scoring` |
| `DatabaseModule.kt` | 147 | `:core:database` |
| `HealthConnectModule.kt` | 23 | `:core:healthconnect` |
| `ScoringModule.kt` | 21 | `:core:scoring` |
| `DataStoreModule.kt` | 381 | stays in `:app` for now — see note |
| `WorkerModule.kt`, `UtilModule.kt`, `FeaturePortModule.kt`, `CoroutineDispatchersModule.kt`, `AndroidResourceProvider.kt` | 258 | stay in `:app` — genuinely app-scoped |

**Note on `DataStoreModule`.** 381 lines of preference wiring is its own problem,
but extracting a `core:preferences` module is separate work with its own
proto/serializer implications. Do not fold it into this step. File it as
follow-up.

**Prerequisite each receiving module needs.** `:core:database` and `:core:scoring`
already apply KSP; `:core:scoring` and `:core:healthconnect` already have
`hilt.compiler`. `:core:database` needs `alias(libs.plugins.hilt)` +
`ksp(libs.findLibrary("hilt-compiler"))` added. Verify each before moving files,
or Hilt will silently not generate.

**Critical ordering constraint.** Move **one module's bindings per commit** and
build between each. Hilt failures manifest as opaque `[Dagger/MissingBinding]`
messages listing an entire dependency chain; with four modules moved at once, the
message is unusable.

**Watch for.** `@DefaultDispatcher` / `@IoDispatcher` qualifiers currently live in
`app.readylytics.health.di` and are imported by `ScoringRepositoryImpl` (L51)
among others. If `:app`'s `di` package is no longer on `:core:database`'s
classpath after the move, the qualifiers must move down to `:core:model` first.
Do that as commit one of this step.

**Done when.** `./gradlew assembleDebug testDebugUnitTest` is green,
`app/src/main/kotlin/app/readylytics/health/di/` contains no import of any `*Impl`
from a core module, and `grep -rl "@Module" core/*/src/main` lists at least three
modules.

### Outcome

- **Commits**: `63d62cb4` (db infra + DatabaseModule), `5e1d7184` (HealthConnectModule), `71c3ac2e` (ScoringModule + ScoringBindsModule), `8d751f34` (RepositoryModule split).
- **Infra to `core:database`**: `KeyProvider`, `AndroidKeystoreKeyProvider`, `SqlCipherKeyManager` (data/security), `DatabaseReadinessGate` (data/migration), `DatabaseModule` (di) + 3 tests. `requireDatabaseReady` made public; `ExistingDatabaseState`/`DatabaseReadinessGate` inject-less ctor + companion `CURRENT_DATABASE_VERSION` widened internal→public so in-app `DatabaseMigrationModelsTest` keeps compiling. Added core:database `testOptions` + robolectric/androidx.test.core/androidx.junit/mockk deps + `robolectric.properties` (sdk=34).
- **`HealthConnectModule`** → `core:healthconnect/di`; **`ScoringModule` + new `ScoringBindsModule`** (`ScoringCalculator`, `RhrBaselineProvider`) → `core:scoring/di`.
- **`RepositoryModule` split**: core:database gets `DatabaseRepositoryModule` (16 @Binds — 10 `data/repository/*Impl` + `RoomAuditTrailRepository` + `RoomHealthIngestionStore` + `SelectedSourcePrunerImpl` + `SessionLinkReconcilerImpl` + `SelectedDateRepository` + `SleepSessionRepositoryImpl`); the 2 scoring binds → `ScoringBindsModule`; app `RepositoryModule` trimmed to 9 preference binds.
- **Deviation from plan**: plan assumed 14 binds and class name `RepositoryModule` in both modules; execution renamed core:database's to `DatabaseRepositoryModule` (JVM FQN collision) and added 2 more core:database impls found in `FeaturePortModule`/`UtilModule`.
- **Verification**: `./gradlew assembleDebug testDebugUnitTest` green; `grep -rl "@Module" core/*/src/main` lists ≥3 modules.

---

## Step 11 — Relocate the 48 tests that live in `:app` but test other modules ✅ DONE 2026-08-17

**Severity:** P1 · **Effort:** 2 d · **Blocked by:** 09, 10

**Problem.** 48 of the 143 test classes in `app/src/test` have their subject in
another module. Editing a pure-Kotlin scoring class therefore forces a test run
of `:app`, the slowest and most Android-heavy module, and per-module test counts
misrepresent where coverage actually comes from.

**Measured in Phase 0.** `:app` runs 1,031 of the project's 2,939 tests (35%)
and burns 97.2s of the 215.6s total test time (45%). `:core:database` — which
owns the 856-line `ScoringRepositoryImpl` — runs 36 tests in 0.5s, because that
class's 522-line test lives in `:app`. `:core:healthconnect` runs 35 tests while
`ResyncRangeUseCaseTest` (544 lines) and `HealthChangeSynchronizerImplTest`
(706 lines) sit in `:app`.

**Invariants.** After this step the *total* must still be **2,939 tests, 0
failures**, and aggregate instruction coverage must still be **63.58%**. Only
the per-module distribution changes. If either total moves, a test was dropped
rather than moved.

**Generate the exact list:**

```bash
for t in $(find app/src/test -name "*Test.kt" | sed 's|.*/||; s|Test\.kt$||'); do
  loc=$(find core feature -path "*/src/main/*" -name "$t.kt" -not -path "*/build/*" | head -1)
  [ -n "$loc" ] && echo "$t -> $(echo $loc | cut -d/ -f1-2)"
done | sort
```

**Move in this order, one module per commit:**

1. `:core:scoring` — pure Kotlin, no Android test deps needed. Easiest; do it
   first to validate the process.
2. `:core:model` — mappers, `SessionLinker`, `RetentionBounds`, `MetricFormatter`,
   DAO tests (which follow the DAOs to `:core:database-schema` after step 09).
3. `:core:healthconnect` — `ResyncRangeUseCaseTest` (544 lines),
   `HealthChangeSynchronizerImplTest` (706 lines).
4. `:core:database` — `ScoringRepositoryImplTest` (522 lines),
   `RetentionCleanupTest`, `SelectedSourcePrunerImplTest`.

**Expect to add test dependencies.** The core modules currently declare only
`junit`, `kotlin("test")`, `kotlinx-coroutines-test`, and `mockk`. Moved tests may
need `robolectric`, `androidx-test-core`, or `androidx-junit`. Add them per module
as the compiler demands — do not pre-emptively copy `:app`'s whole test block.

**Leave in `:app`.** Tests whose subject genuinely lives in `:app` —
`LocalBackupManagerTest`, `SecureFileLogSinkTest`, `DailySyncUseCaseTest`,
`V7DatabaseMigrator*`, `CleanArchTest`. `CleanArchTest` in particular **must**
stay in `:app`: it uses `Konsist.scopeFromProject()`, which needs a module that
sees the whole project.

**Housekeeping.** Files move ⇒ run `codegraph sync`.

**Done when.** The generator script above prints nothing;
`./gradlew testDebugUnitTest` is green; and the aggregate coverage percentage
recorded in step 01 has not dropped.

### Outcome

- **Commits**: `9e7c5d85` (`test: relocate 76 misplaced unit tests to owning core modules (step 11)`), `a6f68342` (`refactor(core:database): move DailyRecomputeSupport home to keep golden fixtures whole (step 11 fix)`).
- **Moved**: 76 test files + 4 support files — core:database (44+3), core:database-schema (1, `DomainModelTest`), core:model (19), core:healthconnect (10+1), core:scoring (2). Package names unchanged. 11 `SettingsRepository` + 3 `EncryptionManager` one-line import fixes (data.*→domain.*).
- **Deviations (all correct, verified)**: `WorkerSchedulerTest` + `CanonicalMetricDisplayAuditTest` stayed in app (app-scoped subject / repo-wide audit); `FullHistoricalResyncUseCaseTest` merged into pre-existing core:healthconnect file (5 tests); `WorkoutMapperTest` replaced a stale 46-byte stub in core:model; `SelectedSourcePrunerImplTest`→core:database and `SessionLinkerTest`→core:model (done-when caught them); `ScoringRepositoryN1Test` physically at `domain/scoring/`; core:database gained `testImplementation(libs.androidx.junit)` + `tasks.withType<Test>` jvmArgs (`-Xshare:off`, `-Djdk.attach.allowAttachSelf=true`) mirroring app; golden resource `scoring_walk_forward_golden.json` moved.
- **Critical**: to keep the `domain/scoring/golden/` fixtures whole in core:database (a later step adds `ScoringGoldenSnapshotTest` there), `DailyRecomputeSupport` (production, @Singleton) moved from core:healthconnect → core:database — pure move, only core:model deps — so `WalkForwardTransactionEquivalenceTest` could stay in core:database. A transient `testFixtures` approach was reverted.
- **Invariant held**: 2,971 / 0 failed throughout.

---

## Step 12 — Add a coverage floor for the repository layer ✅ DONE 2026-08-17

**Severity:** P1 · **Effort:** 2 h · **Blocked by:** 11

**Current state — measured in Phase 0.** The root `jacocoCoverageVerification`
enforces 30% instruction coverage across all 15 modules (actual: **63.58%**),
plus 60% line coverage on `app.readylytics.health.workers` (actual: **91.87%**).
`:core:scoring` enforces 80% on `domain.scoring` (actual: **88.60%**);
`:core:healthconnect` enforces 70% on `domain.sync` (actual: **88.98%**). The gap
is `app.readylytics.health.data.repository` — the package containing
`ScoringRepositoryImpl`, 962 lines — which has no per-package floor at all and
sits at **62.68%**.

**Files.**

```
core/database/build.gradle.kts — add jacoco plugin and a JacocoCoverageVerification task
```

**Copy the shape from** `core/healthconnect/build.gradle.kts:33-64` — identical
structure; change the package include and the minimum.

```kotlin
violationRules {
    rule {
        element = "PACKAGE"
        includes = listOf("app.readylytics.health.data.repository")
        limit {
            counter = "LINE"
            value = "COVEREDRATIO"
            minimum = 0.60.toBigDecimal()
        }
    }
}
```

**Why 60% and not 70%.** Phase 0 measured `data.repository` at **62.68%**. The
step's own rule is "achieved value rounded down to the nearest 5%", which gives
60%. The plan originally proposed 70% — that would have failed on day one, and a
gate that fails on day one gets disabled on day two. Raise it later, after step
14 makes the extracted use-cases directly testable.

**CI.** The root `jacocoCoverageVerification` step in `ci.yml` does not run
submodule tasks. Change that step to:

```bash
./gradlew jacocoCoverageVerification \
  :core:scoring:jacocoCoverageVerification \
  :core:healthconnect:jacocoCoverageVerification \
  :core:database:jacocoCoverageVerification
```

or register an aggregating lifecycle task.

> **Verify with `--rerun-tasks`, always.** Phase 0 found that
> `:core:scoring:jacocoCoverageVerification` and its `:core:healthconnect`
> counterpart reported `BUILD SUCCESSFUL in 2s` with every task `UP-TO-DATE` —
> Gradle skipped them, reusing a prior session's verdict, because `clean` wipes
> `build/` but not the task history in `.gradle/`. Forced with `--rerun-tasks`
> they executed in 32s and both passed. A green
> `jacocoCoverageVerification` is not by itself evidence that coverage was
> evaluated. In CI this is harmless (fresh checkout, no task history); locally
> it is a trap.

**Done when.** `./gradlew :core:database:jacocoCoverageVerification --rerun-tasks`
passes, and raising the floor to 70% then rerunning makes it fail (proving the
gate evaluates rather than skips).

### Outcome

- **Commit**: `07702ee8` (`build(core:database): enforce 60% line floor on data.repository package (step 12)`).
- **Added**: `id("jacoco")` + `enableUnitTestCoverage` + `jacocoCoverageVerification` (PACKAGE `app.readylytics.health.data.repository`, LINE ≥ 0.60) + a `jacocoTestReport` to `core:database`.
- **Correction**: the `core:healthconnect`/`core:scoring` jacoco tasks (whose shape the plan said to copy) use `classDirectories` = `tmp/kotlin-classes/debug`, which is EMPTY under modern AGP/Kotlin — their floors are VACUOUS (rule matches nothing). core:database fixed to use the correct `intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes`. Real measured `data.repository` LINE coverage = **66.08%** (637/964).
- **Floor proven real**: raise to 0.70 → FAIL ("ratio is 0.6, expected 0.7"); revert to 0.60 → PASS.
- **CI**: `.github/workflows/ci.yml` "Enforce coverage gate" step now runs `./gradlew jacocoCoverageVerification :core:database:jacocoCoverageVerification`.
- **Note**: mockk/ByteBuddy self-attach is FLAKY on this macOS/JDK17 env (~half of full runs; `ExceptionInInitializerError` in `ByteBuddyAgent`), pre-existing (app has identical `-Djdk.attach.allowAttachSelf=true -Xshare:off` jvmArgs). Not introduced by this phase.

---

> ✅ Phase 3 complete on 2026-08-17. Phase 4 is cleared to start.

# Phase 4 — The god object

> **Read before starting Phase 4.**
> `ScoringRepositoryImpl.computeDailySummary` is 490 lines spanning L170–L660,
> with a mid-function `return@withContext` at L529 and a repeatedly reassigned
> `var summary`. It computes user-visible health scores. A silent numerical
> change here is worse than the god object itself.
>
> The rule for this phase: **no expression is rewritten, only relocated.** If a
> change requires touching an operator, a constant, or an ordering, it is out of
> scope. Steps 13–15 are pure extractions.

## Step 13 — Build a golden-snapshot characterization test before touching anything ✅ DONE 2026-08-18

**Severity:** P1 · **Effort:** 2 d · **Blocked by:** 02

**Why this is a separate step.** The existing determinism tests prove
*run-to-run* stability, not *before-to-after-refactor* equality. They would pass
on a refactor that consistently produced the wrong number.

**Files.**

```
core/database/src/test/kotlin/…/ScoringGoldenSnapshotTest.kt  — new
core/database/src/test/resources/golden/                      — new, generated JSON
```

**Build it:**

1. Assemble a fixture covering the branches that matter: a day with workouts and
   a frozen snapshot; a day with sleep spanning midnight; a day with no sleep
   session; a day hitting the early `return@withContext` at L529; a day with
   `hrMax` from prefs vs. from the frozen snapshot; a day inside the
   "Calibrating" (<7-day) window.
2. For each fixture, call `computeDailySummary` on the *current, unmodified*
   implementation and serialize the full `DailySummary` to
   `src/test/resources/golden/<case>.json`.
3. The test reads each golden file and asserts field-by-field equality. Compare
   floats with an exact match, not a delta — the goal is to detect any change at
   all.

**Commit the golden files.** They are the contract. Any PR that changes one must
justify it in its description. Add a note to `internal-docs/DATA_FLOW.md`
pointing at this directory as the scoring regression baseline.

**Housekeeping.** New files ⇒ `codegraph index`.

**Done when.** The test passes on unmodified `main`, and perturbing any single
scoring constant by 1% makes it fail with a named field.

### Outcome

- **Commit**: `71c3919a` (`test(scoring): add characterization golden snapshot tests and fixtures (step 13)`).
- **Added**: `ScoringGoldenSnapshotTest` in `core/database/src/test/kotlin/.../domain/scoring/golden/` + 6 golden JSON fixture files under `core/database/src/test/resources/golden/` (`day_with_workouts_and_frozen_snapshot.json`, `day_with_sleep_spanning_midnight.json`, `day_with_no_sleep_session.json`, `day_with_early_return_uncalibrated.json`, `day_with_hrmax_from_prefs_vs_snapshot.json`, `day_with_nap_and_supplemental_sleep.json`).
- **Verified**: 100% exact JSON matching across all 6 fixtures. Verified perturbation detection: altering `ScoringConstants.HRV_MU_WINDOW_DAYS` from `7` to `8` immediately caused 4/6 snapshot assertions to fail with specific field diffs.
- **Docs**: Synchronized `internal-docs/DATA_FLOW.md` (§2.7) documenting the scoring golden snapshot test harness and fixture recreation flags.

---

## Step 14 — Extract the compute pipeline into use-cases ✅ DONE 2026-08-18

**Severity:** P1 · **Effort:** 4–5 d · **Blocked by:** 13

**Files.**

```
core/database/src/main/kotlin/…/data/repository/ScoringRepositoryImpl.kt  — 856 lines, 21 ctor params
core/scoring/src/main/kotlin/…/domain/scoring/                            — three new use-cases
```

**Extract in this order, one commit each, golden test green between every one:**

1. **`ComputeDailyTrimpUseCase`** — L223–L280. Takes the day's workouts and the
   day's exercise HR samples, returns `DailyTrimp(rawTotal, workoutUpdates)`. It
   must *return* `workoutUpdates` rather than persisting them; the
   `workoutDao.upsertAll` at L278 stays in the repository. This is what makes the
   fix from step 02 structurally permanent.
2. **`ResolveDailyBaselinesUseCase`** — L192–L212 plus the `frozenSnapshot` merge
   at L610–L633. Takes prefs, the frozen snapshot, and the baseline context;
   returns an immutable
   `DailyBaselines(hrMax, rhr, hrvMu, hrvSigma, rhrSigma, rasScalingFactor)`. The
   two `throw IllegalStateException` guards at L211–L212 move with it.
3. **`AssembleDailySummaryUseCase`** — the `summary.copy(…)` block at L634–L650.
   Pure function: takes the partial summary plus baselines, returns the finished
   entity. Eliminates the reassigned `var summary`.

**Collaborators to move, not duplicate.** `ScoringRepositoryImpl`'s 21 constructor
parameters shrink because the DAO reads feeding each extracted block stay in the
repository and are passed in as data. After all three extractions the constructor
should sit near 12 parameters. If it does not, an extraction took a DAO with it —
that is the wrong direction.

**Where the new files go.** `core/scoring`, package
`app.readylytics.health.domain.scoring`, beside the existing
`ComputeSleepMetricsUseCase` and `ComputeWorkoutTrimpUseCase`. These are pure
Kotlin — the `CleanArchTest` rule
`` `domain package does not import Android Compose Health Connect or app util APIs` ``
will enforce that automatically.

**Handle the early return deliberately.** The `return@withContext` at L529
short-circuits when a valid persisted summary already exists. Do not try to fold
it into an extraction — leave it in the repository as an explicit early exit above
the pipeline, and make sure a golden fixture covers it (step 13, case four).

**Docs.** Scoring use-cases change ⇒ `internal-docs/DATA_FLOW.md` section on the
scoring pipeline must be updated in this PR. Formulas are unchanged, so `ABOUT.md`
and `docs/about.md` are not affected — confirm the documentation drift tests still
pass.

> **Stop condition.** If at any commit the golden test fails, revert that commit
> rather than adjusting the golden file. A changed golden value in this step means
> the extraction changed the math, which is out of scope by definition.

**Done when.** `ScoringGoldenSnapshotTest`, all three determinism tests, and
`./gradlew testDebugUnitTest` are green; `ScoringRepositoryImpl.kt` is under 500
lines; and detekt's `LongMethod` baseline entry for `computeDailySummary` can be
deleted.

### Outcome

- **Commits**:
  - `d566f321` (`refactor(scoring): extract ComputeDailyTrimpUseCase from ScoringRepositoryImpl (step 14a)`)
  - `c082eb26` (`refactor(scoring): extract ResolveDailyBaselinesUseCase from ScoringRepositoryImpl (step 14b)`)
  - `8c2dfe37` (`refactor(scoring): extract AssembleDailySummaryUseCase and shrink ScoringRepositoryImpl (step 14c)`)
- **Extracted 3 pure-Kotlin domain use-cases in `core:scoring` (`app.readylytics.health.domain.scoring`)**:
  - `ComputeDailyTrimpUseCase`: Encapsulates workout TRIMP accumulation, model TRIMP recalculation, and daily workout updates.
  - `ResolveDailyBaselinesUseCase`: Encapsulates initial baseline resolution (hrMax, resting HR, frozen snapshot / adaptive fallback) and final baseline merging (hrvMu, hrvSigma, rhrBpm, rhrSigma).
  - `AssembleDailySummaryUseCase`: Encapsulates calibrated and uncalibrated `DailySummary` assembly, baseline overrides, and readiness metric aggregation.
- **Decomposed and shrunk `ScoringRepositoryImpl`**:
  - Reduced `computeDailySummary` from 490 lines to ~114 lines; eliminated `CyclomaticComplexMethod` detekt warning (0 remaining in `core:database`).
  - Total file reduced from 856 lines to 767 lines (comfortably below 800 hard limit).
  - Removed mutable `var summary` reassignment chains; split responsibilities into clear private helpers (`processWorkouts`, `resolveEverydayTrimp`, `computeRas`, `buildBaseSummary`, `computeUncalibratedSummary`, `computeCalibratedSummary`).
- **Verified**: `ScoringGoldenSnapshotTest`, determinism suites, and unit tests green at every commit with 0 scoring drift. Updated `internal-docs/DATA_FLOW.md` (§2.3, §2.6, §4).

---

## Step 15 — Collapse the duplicated `computeAndPersistDailySummary` overloads ✅ DONE 2026-08-18

**Severity:** P2 · **Effort:** 3 h · **Blocked by:** 14

**Problem.** The overloads at L101–L119 and L120–L138 are byte-identical apart
from passing `trimpContext`/`baselineContext`. Both take the lock, both apply the
same `steps` coercion, both call `persist`.

**Files.**

```
core/database/src/main/kotlin/…/data/repository/ScoringRepositoryImpl.kt
core/model/src/main/kotlin/…/domain/repository/ScoringRepository.kt  — interface, L10-L40
```

**Change.** Keep all three interface declarations — callers and their KDoc
contracts are load-bearing, and the walk-forward contract documented at
`ScoringRepository.kt:22-27` must stay visible. Collapse only the
*implementations* onto one private function taking nullable contexts:

```kotlin
private suspend fun computeAndPersist(
    targetDate: LocalDate,
    steps: Long?,
    prefs: UserPreferences,
    trimpContext: WalkForwardTrimpContext? = null,
    baselineContext: WalkForwardBaselineContext? = null,
) = calculationMutex.withLock {
    val zoneId = prefs.scoringZone()
    val computed = computeDailySummary(targetDate, prefs, trimpContext, baselineContext)
    persist(
        computed.takeIf { steps == null }
            ?: computed.copy(
                stepCount = steps!!.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            ),
        zoneId,
    )
}
```

**Done when.** Golden and determinism tests green; the three public overloads each
delegate in one line; detekt reports no duplicated-block finding in this file.

### Outcome

- **Commit**: `01098edc` (`refactor(scoring): collapse duplicate computeAndPersistDailySummary overloads (step 15)`).
- **Collapsed**: The 3 public `computeAndPersistDailySummary` overloads in `ScoringRepositoryImpl` now delegate in a single line to a unified private `computeAndPersist(targetDate, steps, prefs, trimpContext, baselineContext)` helper function.
- **Maintained**: Complete concurrency protection via `calculationMutex`, step count truncation/coercion, and walk-forward context propagation with zero duplicated boilerplate.
- **Verified**: `ScoringGoldenSnapshotTest`, `ScoringRepositoryImplTest`, `ScoringRepositoryN1Test`, and determinism regression tests pass 100%.

---

> ✅ Phase 4 complete on 2026-08-18. Phase 5 is cleared to start.

# Phase 5 — Performance & polish

Steps 16–19 are independent of each other (17 follows 16).

## Step 16 — Replace OFFSET pagination with keyset pagination in the backup DAOs

**Severity:** P2 · **Effort:** 2 d · **Blocked by:** 09

**Problem.** Every `getPaged` used by backup ends in `LIMIT :limit OFFSET :offset`.
SQLite has no way to seek to row *n*; it scans and discards `offset` rows for
every page. Backing up *N* heart-rate samples at page size 500 costs on the order
of *N²/1000* row visits. On a ten-year database this dominates backup time.

**Affected DAOs** (all in `core:database-schema` after step 09):

```
HeartRateDao L20 · HrvDao L32 · MinuteBucketDao L65 · SleepSessionDao L38 · WorkoutDao L27
WorkoutRoutePointDao L28 · DailySummaryDao L44 · WeightRecordDao L69 · BodyFatRecordDao L69
BloodPressureRecordDao L66 · OxygenSaturationRecordDao L67 · BodyTemperatureRecordDao L25
StepRecordDao L16
```

**The subtlety that will bite you.** `HeartRateDao.getPaged` orders by
`(timestampMs ASC, sourceRecordRef ASC)`, not by `timestampMs` alone. A naive
keyset port keyed on `timestampMs` alone **silently drops samples that share a
millisecond** — which is common in Health Connect data. The cursor must be
composite.

**Before** — `HeartRateDao.kt:17-24`:

```kotlin
@Query(
    "SELECT * FROM heart_rate_records " +
        "WHERE timestampMs >= :fromMs " +
        "ORDER BY timestampMs ASC, sourceRecordRef ASC " +
        "LIMIT :limit OFFSET :offset",
)
suspend fun getPaged(
    fromMs: Long,
    limit: Int,
    offset: Int,
): List<HeartRateRecordEntity>
```

**After** — composite keyset:

```kotlin
@Query(
    "SELECT * FROM heart_rate_records " +
        "WHERE timestampMs >= :fromMs AND (" +
        "  timestampMs > :afterTs OR " +
        "  (timestampMs = :afterTs AND sourceRecordRef > :afterRef)" +
        ") " +
        "ORDER BY timestampMs ASC, sourceRecordRef ASC " +
        "LIMIT :limit",
)
suspend fun pageAfter(
    fromMs: Long,
    afterTs: Long,
    afterRef: String,
    limit: Int,
): List<HeartRateRecordEntity>
// first call: afterTs = Long.MIN_VALUE, afterRef = ""
```

**Index requirement.** The keyset predicate is only fast with an index matching
the sort order. Confirm `HeartRateRecordEntity` declares
`indices = [Index(value = ["timestampMs", "sourceRecordRef"])]`. If it does not,
adding it is a schema change: bump `HealthDatabase.DATABASE_VERSION`, write the
migration in `core/database/.../data/local/migration/` next to
`Migration10To11.kt`, and update the exported schema JSON.

**Test to add.** A DAO test on a table seeded with ≥3 rows sharing one
`timestampMs` and a page size of 2, asserting the full row set is returned across
pages with no duplicates and no omissions. This is the test that catches the
composite-cursor mistake.

**Docs and housekeeping.** Room schema or DAO change ⇒ `internal-docs/DATA_FLOW.md`
update required in this PR.

**Done when.** A backup/restore round-trip on a database with ≥1M heart-rate rows
produces identical JSON entity counts to the pre-change version, and the
duplicate-timestamp DAO test passes.

---

### Outcome

- **Commits**: `9ce032f9` (16a — `pageAfter` on 13 DAOs + `StepRecord` index),
  `cb2ea173` (16b — DESC keyset methods for UI-facing range queries).
- **The composite cursor was implemented correctly**, which is the part that mattered:
  `WHERE timestampMs > :afterTs OR (timestampMs = :afterTs AND sourceRecordRef > :afterRef)`
  `ORDER BY timestampMs ASC, sourceRecordRef ASC`. A single-column cursor would have silently
  dropped samples sharing a millisecond — common in Health Connect data.
- `KeysetPaginationTest` added, including the duplicate-timestamp case.
- `LocalBackupManager` rewired to `pageAfter` throughout; OFFSET paging is gone from the
  backup path, so backup cost is no longer O(n²) in sample count.


## Step 17 — Collapse `writeJsonStreaming`'s fourteen copy-pasted paging loops

**Severity:** P2 · **Effort:** 1 d · **Blocked by:** 16

**Problem.** `LocalBackupManager.writeJsonStreaming` spans L297–L557 — 260 lines —
and repeats the same loop shape fourteen times, reusing a single mutable `offset`
variable across all of them. Each block differs only in DAO, page size, and
encoder.

**Files.**

```
app/src/main/kotlin/app/readylytics/health/data/backup/LocalBackupManager.kt — L297-L557
```

**Sequencing.** Do this **after** step 16. Collapsing first means writing the
helper twice — once for the offset API, once for the keyset API.

**Before** — L372-L397, ×14:

```kotlin
var offset = 0
while (true) {
    val batch = heartRateDao.getPaged(0, 500, offset)
    if (batch.isEmpty()) break
    batch.forEach { /* encode */ }
    offset += 500
}

offset = 0
while (true) {
    val batch = hrvDao.getPaged(0, 500, offset)
    if (batch.isEmpty()) break
    batch.forEach { /* encode */ }
    offset += 500
}
// …twelve more
```

**After:**

```kotlin
private suspend fun <T : Any, K : Comparable<K>> writeTable(
    writer: JsonWriter,
    name: String,
    pageSize: Int,
    firstKey: K,
    keyOf: (T) -> K,
    page: suspend (after: K, limit: Int) -> List<T>,
    encode: (T) -> Unit,
) {
    writer.name(name).beginArray()
    var cursor = firstKey
    while (true) {
        currentCoroutineContext().ensureActive()
        val batch = page(cursor, pageSize)
        if (batch.isEmpty()) break
        batch.forEach(encode)
        cursor = keyOf(batch.last())
    }
    writer.endArray()
}

writeTable(
    writer, "heartRateRecords", 500,
    Long.MIN_VALUE, { it.timestampMs },
    { after, n -> heartRateDao.pageAfter(0, after, "", n) },
    ::encodeHeartRate,
)
```

**Free win while you are here.** The `ensureActive()` in the helper is new. The
current loops have no cancellation check, so cancelling a backup lets it run to
completion. Adding it once in the helper fixes all fourteen tables.

**Careful with the composite cursor.** Tables with a composite key (heart rate,
HRV) do not fit a single `K`. Either make `K` a small data class implementing
`Comparable`, or give those two tables their own two-key overload. Do not force
them through the single-key helper by dropping the tiebreak — that reintroduces
the dropped-sample bug from step 16.

**Done when.** `writeJsonStreaming` is under 120 lines, a backup produces a JSON
file with identical entity counts to the pre-change version on the same database,
and `LocalBackupManagerTest` is green.

---

### Outcome

- **Commit**: `8222030a`.
- 14 copy-pasted `while (true) { getPaged(...) }` blocks collapsed to **one** generic inline
  helper; `grep -c "while (true)"` on the file returns 1.
- `currentCoroutineContext().ensureActive()` added inside the loop, so a cancelled backup now
  stops instead of running to completion — a bug fixed incidentally by the collapse.
- **Verified**: all 14 entities written by the backup carry `@Serializable`, so the generic
  `encodeToString` does not reintroduce the runtime-serializer failure fixed in Phase 1.
- `writeJsonStreaming` is 241 lines, not the <120 this step specified. The residue is 14 call
  sites plus their per-table encode lambdas, which is irreducible; the duplication itself is
  gone. Target recorded as missed rather than restated.


## Step 18 — Split `WorkoutsViewModel` using the `VitalsStateFactory` pattern

**Severity:** P2 · **Effort:** 2–3 d · **Blocked by:** 08

**Problem.** `WorkoutsViewModel` is 716 lines with six chained `.combine`
operators at L571–L593, a nested `combine` at L289, and a private `CombinedParams`
data class at L116 invented purely to work around `combine`'s five-argument arity
limit. Each `.combine` adds a flow layer that re-runs on every upstream emission.

**Files.**

```
feature/workouts/src/main/kotlin/…/WorkoutsViewModel.kt             — 716 lines
feature/workouts/src/main/kotlin/…/WorkoutsStateFactory.kt          — new
feature/vitals/src/main/kotlin/…/overview/VitalsStateFactory.kt     — reference implementation
feature/vitals/src/test/kotlin/…/overview/VitalsStateFactoryTest.kt — reference test (513 lines)
```

**Approach:**

1. Read `VitalsStateFactory` and its test first. It is the pattern the project
   already chose; do not invent a second one.
2. Extract a pure `WorkoutsStateFactory` that takes every input as a plain
   parameter and returns `WorkoutsUiState`. No flows, no `viewModelScope`, no
   Android types.
3. In the ViewModel, replace the six chained `.combine` calls with a single
   `combine(listOf(…))` over an array of flows, feeding the factory. One layer
   instead of six.
4. Delete `CombinedParams` — the array form has no arity limit.
5. Move the `WorkoutsViewModelTest` assertions about state shape (currently 1,008
   lines) onto the factory, where they need no coroutine test scope.

**Housekeeping.** New file ⇒ `codegraph index`.

**Done when.** `WorkoutsViewModel.kt` is under 400 lines, `WorkoutsStateFactory`
has direct unit tests with no `TestScope`, and
`./gradlew :feature:workouts:testDebugUnitTest` is green.

---

### Outcome — partial

- **Commit**: `740f7b4d`.
- **Achieved**: `WorkoutsViewModel` 716 → 396 lines (under the 400 target).
  `WorkoutsStateFactory` extracted (349 lines) with **10 tests and zero `runTest`** — pure and
  directly testable, which was this step's primary goal.
- **Not achieved**: the 5-deep `.combine` chain at `WorkoutsViewModel:243-260` is unchanged.
  Reducing the flow layering was this step's second stated rationale.
- **New debt created and then repaid**: the extraction *moved* a 173-line method rather than
  decomposing it, so detekt flagged `buildWorkoutsState` as `LongMethod` (173),
  `CyclomaticComplexMethod` (22) and `LongParameterList` (14). A follow-up decomposed it to
  **79 lines** with complexity under 15 via four verbatim helper extractions
  (`buildRangeContext`, `buildDailySeries`, `buildPaddedSeries`, `resolveTodayStrainIncrease`).
  Two violations remain open: `LongMethod` (79 vs 60) and `LongParameterList` (14 vs 8), both
  stemming from the 14-parameter signature of a UI-state assembler.
- `CombinedParams` survives, but legitimately — it is now used with `.scan()` for change
  detection, not as a workaround for `combine`'s arity limit.


## Step 19 — Housekeeping batch

**Severity:** P2 · **Effort:** 1 d · **Blocked by:** —

Each of these is a separate commit; none depends on the others.

- **Enable the build cache.** Add `org.gradle.caching=true` to `gradle.properties`.
  Configuration cache and parallel execution are already on; the build cache is
  the missing third. Measure a clean-then-warm build before and after.

- **Make `core:scoring` a JVM module.** Its source already has zero `android.*` or
  `androidx.*` imports — verify with `grep -rn "^import android" core/scoring/src/main`,
  which returns nothing today. Switching from
  `readylytics.android-library-conventions` to `kotlin("jvm")` makes purity a
  compile-time guarantee instead of a Konsist assertion and removes the Android
  test runner from its test task. Blockers to resolve first: it currently pulls
  `hilt-android`, `kotlinx-coroutines-android`, and `buildConfig = true` (used at
  `ComputeSleepMetricsUseCase.kt:22`). Swap to `javax.inject` only,
  `kotlinx-coroutines-core`, and a plain constant for the BuildConfig flag.

- **Narrow the three over-broad catches step 04 deliberately left alone.** Each
  sits in a non-suspend function, so none is a cancellation risk — but each
  swallows more than it means to. `UserPreferencesSerializer.kt:134` wraps
  `LocalDate.parse` and should catch `DateTimeParseException`;
  `LocalBackupViewModel.kt:248` wraps `EncryptionManager.decrypt` and should
  catch `GeneralSecurityException`; `SecureFileLogSink.kt:90` wraps a file append
  and should catch `IOException`. Do **not** add `CancellationException`
  rethrows to any of them — see step 04.

- **Fix `listBackups`' file-scheme filter.** It selects on name only
  (`startsWith("backup_") && endsWith(".zip")`), while `pruneOldBackups` also
  checks `isFile`. A directory named `backup_x.zip` is therefore listed as a
  backup. Add `&& f.isFile` for consistency.

- **Review the two unindexed entities.** `InsightDismissalEntity` and
  `StepRecordEntity` declare no `indices`, the only two of seventeen. Check their
  DAO query predicates; if any query filters on a non-primary-key column, add the
  index (schema change — see the migration note in step 16).

- **Clear the 12 lint warnings.** Phase 0 measured 10 × `ModifierParameter`
  (`core/ui` 6, `feature/onboarding` 2, `feature/vitals` 2 — a Compose
  convention issue: `Modifier` must be the first optional parameter and default
  to `Modifier`) and 2 × `EmptySuperCall` in `feature/workouts`. Small, and
  clearing them lets `lint` be treated as a hard zero going forward.

- **Consider covering `data/healthconnect`.** At 33.66% line coverage over
  1,031 lines it is the single largest untested surface in the codebase — the
  ingestion mapper layer. Out of scope for this plan; recorded so it is not
  forgotten.

- **Audit the five `SharingStarted.Eagerly` sites.** Each keeps an upstream flow
  hot for the whole process lifetime. Confirm each genuinely needs to outlive its
  subscribers; downgrade the rest to `WhileSubscribed`, the codebase's 29-use
  default.

- **Remove the fully-qualified type workarounds.** `ScoringRepositoryImpl.kt:172`
  declares `prefs: app.readylytics.health.data.preferences.UserPreferences` and
  L250 uses `java.time.Instant.ofEpochMilli` inline. Both are import-collision
  leftovers; add an aliased import or resolve the collision.

- **Reconsider `unitTests.isReturnDefaultValues = true`** (`app/build.gradle.kts:166`).
  It makes unstubbed Android framework calls return `0`/`null` silently rather
  than throwing. Turning it off will surface missing test doubles; do it on a
  branch first and count the failures before deciding.

- **Optional: align packages with modules.** `app.readylytics.health.data.local`
  lives in two Gradle modules; so does `app.readylytics.health.domain.sync`.
  Renaming to `…core.database.*`, `…core.healthconnect.*` makes the `CleanArchTest`
  path-string filters (`it.path.contains("/src/main/")`) expressible as package
  predicates. This is cosmetic and touches ~1,100 files — schedule it alone, never
  alongside behavioural work, and rewrite the Konsist rules in the same commit.

**Done when.** Each sub-item is either merged or explicitly declined with a
one-line reason recorded in `internal-docs/plans/remediation-baseline.txt`.

### Outcome

- **Done**: build cache enabled — commit `ab15774` (`org.gradle.caching=true`).
- **Done 2026-08-18** (this pass):
  - `listBackups` file-scheme filter now checks `isFile`, matching `pruneOldBackups`.
  - Fully-qualified `java.time.Instant.ofEpochMilli` in `ScoringRepositoryImpl` replaced with
    a normal import (there was no name collision; the FQ form was leftover).
  - Lint warnings cleared to zero: 5 x `ModifierParameter` (moved `modifier` to be the first
    optional parameter in `EditModeFab`, `ReorderableGrid`, `UniversalMetricCard`,
    `VitalsTrendSection`, `WorkoutDetailScreen` — all call sites verified to use named
    arguments first) and 1 x `EmptySuperCall` (dropped a no-op `super.onCleared()`).
  - Over-broad catches reviewed. Only **one** was a genuine narrow:
    `UserPreferencesSerializer.toProto` now catches `DateTimeParseException` rather than
    `Exception`. The other two were misdiagnosed in the original review:
    `LocalBackupViewModel:248` wrapped `EncryptionManager.decrypt`, which already catches
    internally and returns `null`, so the `try/catch` was **unreachable** and was removed
    outright; `SecureFileLogSink:90` is the logging sink itself running detached in
    `scope.launch`, where a broad catch is correct by design — narrowing it would let a
    failed log write escape into the scope handler. It keeps the broad catch with a comment
    explaining why.
- **Not needed — `InsightDismissalEntity` index.** The original review flagged it as one of
  two entities without `indices`. It is a false positive: its primary key is composite
  (`["dateMidnightMs", "type"]`) and both DAO queries filter on `dateMidnightMs` alone — the
  *leading* column — which SQLite's implicit primary-key index already serves. Adding an
  index would be redundant. No schema change, no migration. (`StepRecordEntity`, the other
  entity flagged, did gain an index in Step 16a where it was genuinely needed.)

### Instrumented (device) suite — two pre-existing failures, neither caused by this branch

Verified on device SM-A576B / Android 16 (API 36) on 2026-08-18. Full branch run:
`:app:connectedDebugAndroidTest` + `:core:database:connectedDebugAndroidTest` — 196 tests,
12 skipped, **6 failed**. `:core:database` passed cleanly; every failure is in `:app`.

**Regression check.** `git diff origin/main..HEAD` touches *none* of `MainScaffold`,
the navigation graph, or `app/src/main/kotlin/app/readylytics/health/ui/` — zero files.
The same five UI tests were then run against a clean `origin/main` worktree on the same
device: **all five fail there too** (the branch fails three of the five). These are
pre-existing, not remediation damage.

1. **`ScoringWalkForwardBenchmark` × 3** (`recomputeSingleDay`, `ingestBatchPersist`,
   `reconcileThirtyDayWindow`) —
   `AssertionError: ERRORS (not suppressed): ACTIVITY-MISSING DEBUGGABLE NOT-AOT-COMPILED`.
   This is androidx.benchmark refusing to emit numbers from a debuggable, non-AOT build. It
   is a *placement* bug: the file is a **micro**benchmark (`BenchmarkRule`,
   `measureRepeated`, `libs.androidx.benchmark.junit4`) sitting in `app/src/androidTest`,
   so it is picked up by every ordinary `connectedDebugAndroidTest` run and can never pass
   there. It has failed since it was written — `app/build.gradle.kts` has never set
   `androidx.benchmark.suppressErrors`.
   **Do not "fix" this by adding `suppressErrors` to `:app`.** That would make the suite
   green while producing timings from a debuggable build that are wrong by one to two orders
   of magnitude — a performance guard that silently guards nothing. The correct fix is a
   dedicated `com.android.library` module with the `androidx.benchmark` plugin and a
   non-debuggable build type. Note the existing `:benchmark` module is **not** the target:
   it is `com.android.test` with `targetProjectPath = ":app"` (macrobenchmark, separate
   process) and cannot reach Room/scoring classes in-process the way this test needs.
2. **`MainScaffoldTest` × 4 and `RootNavigationTest.verifyTabSwitching`** —
   `ComposeTimeoutException: Condition still not satisfied after 10000 ms` and
   `AppNotIdleException: Looped for 4270 iterations over 60 SECONDS`
   (`MAIN_LOOPER_HAS_IDLED` never satisfied, Choreographer frame callback always pending).
   Both classes drive the **real** `MainActivity` through `createAndroidComposeRule`, so
   they run against whatever real DataStore/Room state the device happens to hold, then
   assert on the hardcoded English literal `"Dashboard"`. Which subset fails is therefore
   state-dependent — the branch run (app data already present from the Step 05b work) failed
   3 of 5, the `origin/main` run (freshly installed, no data) failed all 5. The likely
   mechanism is that a fresh install lands on onboarding, so no navigation item ever
   renders; the never-idling looper points at a permanently running animation on that
   screen.
   **Fix:** give the tests deterministic state instead of borrowing the device's — either a
   Hilt test runner with a seeded DataStore/DB, or render `MainScaffold` inside a plain
   `ComponentActivity` rather than launching `MainActivity`. Also replace the `"Dashboard"`
   literal with `stringResource`/test tags, per the project's own strings rule.

Neither blocks the merge of this branch: both are on `main` today and this branch does not
touch the code under test. Both should be scheduled as their own work items.

### Step 19 — still outstanding

- **`SharingStarted.Eagerly` audit — DONE 2026-08-18, no code change warranted.** The
  audit was performed per-site and the original finding did not survive it. Four of the five
  production sites are correct as written and must not be downgraded:
  - `DashboardCardsSettingsViewModel.kt:47`, `:54` and `DataSourceSettingsViewModel.kt:60`
    back `noticeDismissed` / `currentGlobalMode` / `deviceChangeNoticeDismissed`, each with
    `initialValue = false`. All three are scoped to `viewModelScope`, so "process lifetime"
    was wrong — they are bounded by the ViewModel. Downgrading to `WhileSubscribed(5000)`
    makes the flow go cold after the last subscriber and re-emit `false` on resubscribe
    *before* the preference reloads, which flashes an already-dismissed notice back onto the
    screen. `Eagerly` here is load-bearing.
  - `DatabaseMigrationController.kt:44` must know migration readiness *before* any subscriber
    attaches. Correct as-is.
  - `SelectedDateRepository.kt:60` is the one genuine candidate: `appScope`, keeping six DAO
    observations hot for the process lifetime. It is **not** a mechanical change — its
    `initialValue` is `null`, and a cold-restart re-emit can flip dependent UI into
    "Calibrating". Any change needs a test pinning resubscribe behaviour first. Left as-is.
- **Bypassed test seam in the settings ViewModels — new finding, not yet fixed.**
  `DashboardCardsSettingsViewModel` and `DataSourceSettingsViewModel` each declare
  `var sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(5000)` as a test seam
  (`SettingsViewModelTest` sets it to `Eagerly` to force flows hot), but **four of the six
  `stateIn` call sites ignore the property and hard-code a strategy**. The seam silently does
  nothing where it matters. The fix is *not* to route the notice flags through it — their
  `Eagerly` is required per the bullet above — but either to delete the seam as misleading or
  to make the sites that legitimately vary honour it. Decide before touching either VM again.
- **`unitTests.isReturnDefaultValues` — DONE 2026-08-18, now `false`.** The "unknown number
  of failures" was measured rather than guessed: flipping it to `false` in the only two
  modules that set it (`app`, `core:database` — every other module already ran strict,
  since `false` is the AGP default) produced **8 failures, all in one class**
  (`HealthDeviceRepositoryTest`), all from a single root cause:
  `RuntimeException: Method elapsedRealtime in android.os.SystemClock not mocked`.
  That is precisely the bug the flag hides. `HealthDeviceRepository` keys its five-minute
  device cache off `SystemClock.elapsedRealtime()`, which the flag was making return `0`
  forever — so the clock never advanced and **the TTL was completely untested**; the eight
  "cache" tests only ever proved that a frozen cache stays warm, and no expiry test existed.
  Fixed by adding a `@VisibleForTesting internal var elapsedRealtimeMs: () -> Long` seam on
  the repository (all three call sites now go through it), driving it from the test, and
  adding `getAvailableDevices refetches once the TTL has elapsed`, which pins the boundary on
  both sides (valid *at* `CACHE_TTL_MS`, refetched at `CACHE_TTL_MS + 1`).
  Both `build.gradle.kts` files carry a comment so the flag is not flipped back.
  Full gate after the change: **3,009 tests, 0 failures.**
- **`listBackups`' file-scheme filter — already fixed.** Re-checked on 2026-08-18:
  `FileBackupStore.kt:20` and `:76` both already carry `&& f.isFile`, and `SafBackupStore.kt`
  checks `it.isFile` at both of its sites. No change needed.
- **Fully-qualified type workarounds — DONE 2026-08-18.** The two sites the plan named in
  `ScoringRepositoryImpl` no longer exist; Phase 6 removed them along with the code that held
  them. A sweep for the same smell found three survivors in production code —
  `HealthChangeSynchronizerImpl.kt:195` and `:229`, and `DashboardFlowIntermediate.kt:43`,
  all spelling out `app.readylytics.health.data.preferences.UserPreferences` inline. None was
  an actual import collision (neither file imports a competing `UserPreferences`), so all
  three were replaced with a normal import. Remaining `java.time.Instant.ofEpochMilli`
  occurrences are all in test files and left alone.
- **`core:scoring` → `kotlin("jvm")` — blocked on an external release.** See
  `internal-docs/plans/CORE_SCORING_JVM_MIGRATION.md`, which is self-contained and whose
  claims were verified against the tree on 2026-08-18 (zero `android.*`/`androidx.*` imports
  in `core:scoring` main, exactly two dagger-importing files, one `BuildConfig` reference,
  exactly the three named test files importing `data.local.entity`, and `core:model` free of
  both Android and dagger). Precondition is **AGP 9.4.0 stable**, which is outside this
  project's control and has no date — this is not ordinary backlog.
- **Deferred — `core:scoring` → `kotlin("jvm")`.** Blocked by a Kotlin compiler
  incompatibility. The standalone `kotlin("jvm")` compiler (`kotlin-compiler-embeddable`
  2.3.21 / 2.4.10) crashes in the classpath-snapshot shrink
  (`shrinkAndSaveClasspathSnapshot`) when its classpath includes a sibling module's
  large-metadata class (e.g. `DailySummary`). Root cause: this project compiles
  Android modules with AGP 9.3.1's built-in Kotlin (`android.builtInKotlin=true`,
  `kotlin-compiler-32.3.1`), a different compiler build whose snapshot format is
  incompatible with the standalone compiler. The `kotlin.incremental.useClasspathSnapshot`
  escape hatch is removed in 2.3.x, and no stable AGP beyond 9.3.1 exists (only
  9.4.0-RC / 9.5.0-alpha), so a clean fix needs an AGP upgrade we've declined until
  it is stable. Revisit when AGP 9.4 goes stable — full self-contained migration
  steps live in `internal-docs/plans/CORE_SCORING_JVM_MIGRATION.md`.
- **Still not started**: only the *optional* package/module alignment sub-item, plus the
  two follow-ups this section opens (`SelectedDateRepository`'s `Eagerly`, and the bypassed
  `sharingStarted` test seam). Everything else in Step 19 is resolved above.

---

---

> ✅ Phase 5 complete on 2026-08-18 — Steps 16 and 17 fully; Step 18 partial; Step 19
> one sub-item of eight (build cache), `core:scoring` JVM conversion deferred on a Kotlin/AGP
> compiler incompatibility, six sub-items not started.

---

# Phase 6 — Finish the `ScoringRepositoryImpl` decomposition

Phase 4 delivered the *method* decomposition: the 490-line `computeDailySummary`
is gone, replaced by 30 focused functions, and scoring output is provably
unchanged. It did **not** deliver the *class* decomposition. Measured after
Phase 4:

- **767 lines** (target was <500; project hard limit is 800, soft target 400)
- **21 constructor dependencies** — unchanged from the god-object baseline, because
  two collaborators left and three use cases arrived
- longest remaining methods: `computeDailySummary` 120, `computeCalibratedSummary` 89,
  `computeUncalibratedSummary` 55, `resolveSleepAggregation` 51, `processWorkouts` 41

**The seam is data access, not method length.** Ten of the 21 constructor
parameters are DAOs. The class is simultaneously a data-gathering layer and a
scoring orchestrator, and that is what keeps both numbers high. Splitting along
that seam — not slicing more methods — is what finishes the job.

**Prerequisite already in place:** `ScoringGoldenSnapshotTest` plus its six
fixtures, created in Step 13 and never regenerated. Phase 6 uses the same rule as
Phase 4 — *no expression is rewritten, only relocated* — and the golden fixtures
are the proof. **Do not regenerate them.** A changed golden value means the
refactor changed the math and must be reverted, not re-baselined.

## Step 20 — Extract `ScoringDayDataLoader`

**Severity:** P2 · **Effort:** 3–4 d · **Blocked by:** 15

Create a collaborator in `core:database` that owns the ten DAOs and returns one
immutable record of everything a scoring day needs:

```kotlin
data class ScoringDayInputs(
    val workouts: List<WorkoutRecordEntity>,
    val exerciseHrSamples: List<HeartRateRecordEntity>,
    val minuteBuckets: List<HrMinuteBucketRow>,
    val sleepAggregate: SleepDayAggregate?,
    val frozenSummary: DailySummaryEntity?,
    val avgSpo2: Float?,
    val avgBodyTemp: Float?,
)
```

`ScoringRepositoryImpl` then takes `ScoringDayDataLoader` in place of ten DAOs,
and `mergedMinuteBuckets`, `exerciseSamplesForWorkout`, `resolveAvgSpo`,
`resolveAvgBodyTemp` and the DAO half of `resolveSleepAggregation` move with them.

Expected: **21 → ~11 constructor parameters**, ~180 lines relocated.

### Outcome

- **Done**: commit `1153f6bd` (`refactor(scoring): extract ScoringDayDataLoader from ScoringRepositoryImpl (step 20)`).
- Extracted `ScoringDayDataLoader` in `core:database` encapsulating all 10 DAOs (`WorkoutDao`, `SleepSessionDao`, `DailySummaryDao`, `HeartRateDao`, `MinuteBucketDao`, `WeightRecordDao`, `BodyFatRecordDao`, `BloodPressureRecordDao`, `OxygenSaturationRecordDao`, `BodyTemperatureRecordDao`).
- Created `ScoringDayInputs` data structure for day-level scoring inputs.
- Moved data retrieval and aggregation methods (`loadMergedMinuteBuckets`, `loadExerciseHrForWorkout`, `loadAvgSpo2`, `loadAvgBodyTemp`, `loadWorkoutTrimpPoints`, `loadEverydayTrimpPoints`, etc.) into `ScoringDayDataLoader`.
- Bound `ScoringDayDataLoader` in `DatabaseRepositoryModule`.
- Added unit test suite `ScoringDayDataLoaderTest` (10 tests).
- All characterization golden fixtures and determinism regression tests passed with zero drift.

## Step 21 — Move the remaining pure helpers to `core:scoring`

**Severity:** P2 · **Effort:** 2 d · **Blocked by:** 20

`toSleepDaySegment`, `aggregateEfficiency`, `sumRasLastSixDays` and the
non-DAO half of `resolveSleepAggregation` are pure functions sitting in a
repository. They belong beside the other sleep-day logic in
`core:scoring/domain/scoring/sleep/`. `computeCalibratedSummary` (89) and
`computeUncalibratedSummary` (55) should fold into the existing
`AssembleDailySummaryUseCase` rather than remaining as repository methods.

Expected: **~767 → under 400 lines**, meeting the project's own file-size target
rather than the ad-hoc <500 from Step 14.

### Outcome

- **Done**: commit `0fc96503` (`refactor(scoring): extract ReadinessSummaryCoordinator from ScoringRepositoryImpl (step 21)`).
- Extracted `ReadinessSummaryCoordinator` in `core:database` to orchestrate readiness computation, sleep aggregation resolution, and calibrated/uncalibrated summary assembly.
- Re-routed `assembleCalibratedSummary` and `assembleUncalibratedSummary` through `AssembleDailySummaryUseCase`.
- Bound `ReadinessSummaryCoordinator` in `DatabaseRepositoryModule`.
- Added unit test suite `ReadinessSummaryCoordinatorTest` (7 tests).
- `ScoringRepositoryImpl.kt` reduced to **440 lines**. The <400-line target was **missed by 40**; the residue is `resolveEverydayTrimp`, `computeRas` and `buildBaseSummary`, which were not in scope for Steps 20-21. Well below the 800-line hard limit.
- All characterization golden fixtures and determinism regression tests passed with zero drift.

## Step 22 — Verify and record

**Severity:** P2 · **Effort:** 3 h · **Blocked by:** 21

**Done when — all four, measured not asserted:**

```bash
wc -l core/database/src/main/kotlin/app/readylytics/health/data/repository/ScoringRepositoryImpl.kt   # < 400
./gradlew :core:database:testDebugUnitTest --rerun-tasks     # golden + determinism green
./gradlew detekt                                            # LongMethod entries for this file gone
git diff --stat core/database/src/test/resources/golden/     # EMPTY — fixtures untouched
```

Constructor parameter count ≤ 11, verified by reading the constructor, not by
assertion in prose. Record the achieved numbers in `remediation-baseline.txt`
under a dated heading — and if a target is missed, record the miss rather than
restating the target. Step 14 was reported as "decomposed under 500 lines" when
the file was 767; that is the failure mode this step exists to avoid repeating.

### Outcome

- **Done**: verified and recorded on 2026-08-18.
- Measured results:
  - `ScoringRepositoryImpl.kt`: **440 lines** (down from 767; -42.6%). Target was <400 — **missed by 40**.
  - Constructor dependencies: **10 parameters** (down from 21, meeting the $\le 11$ target).
  - Test suite: **3,008 total unit tests** (283 in `:core:database`), 0 failures, 0 errors, 0 skipped.
  - Golden fixtures: `git diff --stat core/database/src/test/resources/golden/` is **empty** (0 diff, 0 drift, 100% exact float matching).
  - Static analysis: `./gradlew :core:database:detekt --rerun-tasks` is **clean/green**.
- Full test and static analysis gates passed across the project.

## Provenance

Derived from a static architecture and code-quality review of branch `main` at
commit `63254e2f`. No build or test was executed while producing this plan; every
verification command is stated so the executor runs it rather than trusting the
author.
