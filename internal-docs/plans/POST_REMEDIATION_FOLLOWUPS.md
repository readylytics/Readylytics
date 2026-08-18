# Post-Remediation Follow-Ups

**Status:** not started · **Created:** 2026-08-18 · **Branch of origin:** `feat/code-review`

Self-contained. Four independent items left over after a repo-wide architecture remediation
that landed on the `feat/code-review` branch. None of them blocked that branch from merging.
Each item below is executable on its own, in any order, and states its own evidence, decision
points, steps, and verification. No other document is required to act on any of them.

A separate document, `internal-docs/plans/DETEKT_BASELINE_BURNDOWN.md`, covers the 658
suppressed detekt findings; that work is not repeated here.

---

## Ground rules that apply to every item

- **Pre-commit gate (mandatory):** `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`, and
  `./gradlew lintRelease` once all coding is done. Before handing work back, run the full gate:
  `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease`.
- **Known-good state as of 2026-08-18:** 3,009 unit tests, 0 failures, 0 lint warnings.
  If your change drops the test count or adds a warning, that is a regression.
- **Scoring math is off-limits.** Formulas, coefficients, operator order and constants in
  `domain/scoring/**` must not change. Never regenerate the golden fixtures in
  `core/database/src/test/resources/golden/` to make a test pass.
- **Never uninstall the production app** `app.readylytics.health` without explicit permission.
  The debug variant is `app.readylytics.health.local.grl3lb`.
- Doc-sync rules from `.claude/CLAUDE.md` still apply — changes to the ingestion pipeline, Room
  schema, scoring use-cases or formulas require a same-change update to
  `internal-docs/DATA_FLOW.md`.
- New files require `codegraph index` afterwards; structural moves require `codegraph sync`.

---

## Item 1 — `SelectedDateRepository.earliestDate`: decide what `SharingStarted` should be

**File:** `core/database/src/main/kotlin/app/readylytics/health/data/repository/SelectedDateRepository.kt`

### The situation

Line 60 shares a six-DAO `combine` eagerly, for the lifetime of the process:

```kotlin
override val earliestDate: StateFlow<LocalDate?> =
    combine(
        dao.observeEarliestDateMs(),
        sleepSessionDao?.observeEarliestSessionTime() ?: flowOf(null),
        heartRateDao?.observeEarliestHrTime() ?: flowOf(null),
        hrvDao?.observeEarliestHrvTime() ?: flowOf(null),
        oxygenSaturationRecordDao?.observeEarliestSpo2Time() ?: flowOf(null),
        bloodPressureRecordDao?.observeEarliestBpTime() ?: flowOf(null),
    ) { times ->
        val minTime = times.filterNotNull().minOrNull()
        minTime?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
    }.stateIn(scope = appScope, started = SharingStarted.Eagerly, initialValue = null)
```

It was flagged during an audit of the codebase's five `SharingStarted.Eagerly` sites as the one
genuinely questionable case — the class is `@Singleton`, the scope is `@ApplicationScope`, and
six Room observers stay hot forever. (The other four sites were examined and found correct:
three are `viewModelScope`-bounded notice flags covered in Item 2, and
`DatabaseMigrationController.kt:44` must know migration readiness before any subscriber
attaches.)

### Read this before changing anything — the obvious fix is a no-op

**Switching `Eagerly` to `WhileSubscribed` will not make the flow cold.** Lines 62-74 attach a
permanent collector in the same application scope:

```kotlin
init {
    appScope.launch {
        earliestDate.collect { earliest ->
            if (earliest != null) {
                dateMutex.withLock {
                    if (_selectedDate.value.isBefore(earliest)) {
                        _selectedDate.value = earliest
                    }
                }
            }
        }
    }
}
```

That collector never completes, so the flow has at least one subscriber for the process
lifetime regardless of the sharing strategy. `Eagerly` here is **redundant, not expensive**.
Changing it in isolation buys nothing and only risks the hazard below.

### The real hazard, if you change it anyway

`earliestDate.value` is read **synchronously** at three call sites — `updateSelectedDate`
(:79), `selectPreviousDay` (:116), and implicitly through the init collector. Those reads clamp
the user's selected date to the earliest date that actually has data. If the flow were ever
genuinely cold, `.value` would return `initialValue = null`, and:

- `updateSelectedDate` (:80-83) would skip the `coerceAtLeast(earliest)` clamp entirely;
- `selectPreviousDay` (:117) would take the `earliest == null` branch and allow paging back
  past the first day with data.

Downstream, `earliestDate` reaches the UI through `SelectedDateStore`
(`core/model/.../domain/date/SelectedDateStore.kt:8`) and drives
`DateSwitcher` (`core/ui/.../components/DateSwitcher.kt:63,67,133,144`), where
`canGoBack = earliestDate == null || selectedDate > earliestDate`. A spurious `null` re-emit
therefore re-enables backward navigation into empty days and can flip dependent screens into
their "Calibrating" state.

### What to actually do

This is a **decision**, and the recommended decision is: *leave line 60 as it is, and delete the
finding.* If you want to make the code honest rather than merely leave it alone, the useful
change is not the sharing strategy but the redundancy — either:

- **Option A (recommended, smallest):** keep `Eagerly`, add a comment at line 60 recording that
  the init-block collector at :62 already pins the flow hot and that `.value` is read
  synchronously at :79 and :116, so the strategy must not be downgraded without addressing
  those reads. Zero behaviour change.
- **Option B:** remove the redundancy properly — drop the `init` collector and fold the
  "clamp `_selectedDate` up to `earliest`" rule into the two call sites that already read
  `.value`, then `WhileSubscribed(5000)` becomes meaningful. This is a real behavioural change
  and needs the tests below first.

Do **not** simply swap the enum value.

### Tests to write first (required for Option B, valuable either way)

Existing coverage lives in
`core/database/src/test/kotlin/app/readylytics/health/data/repository/SelectedDateRepositoryTest.kt`
(see the `// --- earliestDate boundary tests ---` block at :175, and the cases at :224, :270,
:274). It does not cover resubscribe behaviour. Add:

1. `earliestDate re-emits its real value, not null, after all subscribers detach and reattach`.
2. `updateSelectedDate still clamps to earliest when the flow has no external subscribers`.
3. `selectPreviousDay does not page before earliest when the flow has no external subscribers`.

Use `runTest` with an explicit `TestScope` as `appScope` so you control subscription lifetime.

### Verification

`./gradlew :core:database:testDebugUnitTest` plus the full gate. If you took Option B, also
exercise the date switcher on-device: page backwards to the first day with data and confirm the
back arrow disables at the right boundary.

---

## Item 2 — The `sharingStarted` test seam is bypassed at half its call sites

### The situation

Four settings ViewModels declare a mutable test seam:

```kotlin
var sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(5000)
```

- `feature/settings/.../UISettingsViewModel.kt:32`
- `feature/settings/.../SyncSettingsViewModel.kt:37`
- `feature/settings/.../DashboardCardsSettingsViewModel.kt:40`
- `feature/settings/.../data/DataSourceSettingsViewModel.kt:48`

Across those four files there are **eight `stateIn` call sites, and four ignore the seam**:

| File:line | `started =` | `initialValue` | Uses seam? |
|---|---|---|---|
| `UISettingsViewModel.kt:58` | `sharingStarted` | `UIState()` | yes |
| `SyncSettingsViewModel.kt:58` | `sharingStarted` | `SyncSettingsState()` | yes |
| `DashboardCardsSettingsViewModel.kt:47` | `SharingStarted.Eagerly` | `false` | **no** |
| `DashboardCardsSettingsViewModel.kt:54` | `SharingStarted.Eagerly` | `null` | **no** |
| `DashboardCardsSettingsViewModel.kt:65` | `sharingStarted` | `DashboardCardsSettingsState()` | yes |
| `DataSourceSettingsViewModel.kt:53` | `SharingStarted.WhileSubscribed(5000)` | `emptyMap()` | **no** |
| `DataSourceSettingsViewModel.kt:60` | `SharingStarted.Eagerly` | `false` | **no** |
| `DataSourceSettingsViewModel.kt:101` | `sharingStarted` | `DataSourceSettingsState()` | yes |

Tests set the seam to force flows hot or lazy —
`SettingsViewModelTest.kt` at :81, :105, :129, :167, :190, :215, :240, :263 (`Eagerly`) and :292
(`Lazily`); `DashboardCardsSettingsViewModelTest.kt` at :145, :301, :493 and
`DataSourceSettingsViewModelTest.kt:91` (`Lazily`). For the four bypassing sites those
assignments silently do nothing.

### Why the obvious fix is wrong

Three of the four bypassing sites hard-code `Eagerly` **deliberately**, and it is load-bearing:

- `DashboardCardsSettingsViewModel.kt:47` — `bulkDisplayModeNoticeDismissed`, `initialValue = false`
- `DashboardCardsSettingsViewModel.kt:54` — `lastGlobalDisplayMode`, `initialValue = null`
- `DataSourceSettingsViewModel.kt:60` — `deviceChangeNoticeDismissed`, `initialValue = false`

All three are `viewModelScope`-scoped, so they are bounded by the ViewModel and are *not* a
process-lifetime leak. Routing them through a `WhileSubscribed(5000)` seam would make each go
cold after its last subscriber and re-emit its `initialValue` on resubscribe **before** the
preference reloads — i.e. a dismissed notice visibly reappears, and the global display mode
briefly reads `null`. That is a real UI defect, not a hypothetical.

The fourth, `DataSourceSettingsViewModel.kt:53`, hard-codes `WhileSubscribed(5000)` — exactly
the seam's own default value. That one is a plain oversight.

### What to do

1. **`DataSourceSettingsViewModel.kt:53` → use `sharingStarted`.** Same value, so no behaviour
   change, and the seam starts working for that flow. Confirm
   `DataSourceSettingsViewModelTest` still passes.
2. **For the three `Eagerly` sites, pick one and apply it consistently:**
   - **Option A (recommended):** leave them hard-coded and add a one-line comment at each
     explaining that `Eagerly` is required because `initialValue` is a "not dismissed"/"unknown"
     sentinel that must not be re-emitted on resubscribe. This makes the bypass intentional and
     legible instead of looking like an omission.
   - **Option B:** give the ViewModels a second seam (e.g. `noticeSharingStarted`) defaulting to
     `Eagerly`, so tests can control these flows too without changing production behaviour.
     Only worth it if a test actually needs to drive them.
3. **Do not** delete the seam outright — eight test call sites depend on it.

### Verification

`./gradlew :feature:settings:testDebugUnitTest`, then the full gate. Behaviour must be
unchanged; if any settings test starts passing or failing differently, you have altered
behaviour and should stop.

---

## Item 3 — The instrumented suite is not reliably runnable on a local physical device

### Evidence (three runs on SM-A576B, Android 16 / API 36, 2026-08-18)

| Run | Conditions | Result |
|---|---|---|
| Branch, plain `connectedDebugAndroidTest` | animations on, no benchmark args | 196 tests, 6 failed |
| `origin/main` worktree, 5 UI tests only | animations on, no benchmark args | 5 tests, 5 failed |
| Branch, CI-equivalent args | animations **off**, `suppressErrors` passed | 196 tests, 17 failed |

The failures are **not a stable set** — 5, 6, and 17 failures with overlapping but different
membership across runs. `:core:database:connectedDebugAndroidTest` passed in every run; every
failure was in `:app`.

### Sub-item 3a — benchmark failures: solved, just not locally

Three `ScoringWalkForwardBenchmark` tests
(`app/src/androidTest/kotlin/app/readylytics/health/benchmark/ScoringWalkForwardBenchmark.kt`,
335 lines) failed with:

```
java.lang.AssertionError: ERRORS (not suppressed): ACTIVITY-MISSING DEBUGGABLE NOT-AOT-COMPILED
```

**This is already handled in CI and is purely a local-invocation gap.**
`scripts/run-instrumented-tests.sh:37-38` — which `.github/workflows/instrumented-tests.yml:79`
invokes — passes:

```
-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=ACTIVITY-MISSING,DEBUGGABLE,EMULATOR,NOT-AOT-COMPILED
```

with a comment at :32-36 recording the deliberate decision: the benchmark still executes and
asserts correctness, just without trustworthy timing numbers on that runner. **Passing those
args locally makes all three pass** — verified in run 3.

Two things worth doing, in order of value:

1. **Add a local runner script** so a developer running the suite by hand gets CI's conditions.
   Do *not* reuse `scripts/run-instrumented-tests.sh` on a physical device — line 11 runs
   `adb shell pm disable-user --user 0 com.google.android.apps.messaging`, which disables the
   owner's Messages app. A new `scripts/run-instrumented-tests-local.sh` should: save and
   restore the three animation scales, pass the `suppressErrors` args, and *not* touch
   unrelated packages.
2. **Consider moving the benchmark to `:database-benchmark`.** That module already exists and
   is the correct host — `com.android.test`, `testInstrumentationRunner =
   "androidx.benchmark.junit4.AndroidBenchmarkRunner"`, a `benchmark` build type with
   `isDebuggable = false`, debug variants disabled, and it already depends on `:core:database`,
   `:core:model` and `:core:database-schema` (see `database-benchmark/build.gradle.kts`, and
   the existing `V7DatabaseIngestMicrobenchmark.kt` / `V7DatabaseMigrationBenchmark.kt` there).
   You would need to add `implementation(project(":core:scoring"))` — `core:database` depends
   on `:core:scoring` with `implementation`, so it is not exposed transitively — and the
   benchmark's imports resolve to `core:database` (`data.local.*`, `data.repository.*`) and
   `core:scoring` (`AssembleDailySummaryUseCase`, `BaselineComputer`,
   `CompositeScoringCalculator`, `ScoringConfigFactory`, `SleepNadirAnalyzer`,
   `LoadScoringStrategy`, …). Note `:benchmark` is **not** the right home: it is a
   macrobenchmark module with `targetProjectPath = ":app"`.
   **Payoff beyond correctness:** once these actually run they are slow — the three benchmarks
   took roughly seven minutes of the 28-minute run 3. Moving them out makes the routine
   connected suite substantially faster.

### Sub-item 3b — the tail-cluster UI failures: root cause NOT yet identified

In run 3, tests 1-179 passed and then **every remaining test failed** — a contiguous tail of 17,
spanning `ChartAccessibilityTest` (5), `DatabaseMigrationScreenTest` (7),
`RootNavigationTest` (1) and `MainScaffoldTest` (4) — all with the identical error:

```
java.lang.IllegalStateException: No compose hierarchies found in the app. Possible reasons
include: (1) the Activity that calls setContent did not launch; (2) setContent was not called;
(3) setContent was called before the ComposeTestRule ran.
```

Everything failing from one point onward, with one error, is an **environment break partway
through a 28-minute run**, not seventeen independent test defects. In runs 1 and 2 (animations
on) the same area failed differently — `ComposeTimeoutException: Condition still not satisfied
after 10000 ms` and
`AppNotIdleException: Looped for 4270 iterations over 60 SECONDS ... MAIN_LOOPER_HAS_IDLED`,
the classic signature of animations being enabled.

What is established:
- These are **not** caused by the branch under test. `git diff origin/main..HEAD` touches zero
  files under `MainScaffold`, the navigation graph, or `app/src/main/kotlin/.../ui/`, and the
  same tests fail on a clean `origin/main` worktree on the same device.
- Animations were on (`window_animation_scale`/`transition_animation_scale`/
  `animator_duration_scale` all `1`) for runs 1 and 2; CI sets `disable-animations: true`
  (`.github/workflows/instrumented-tests.yml:78`). That explains runs 1-2 but **not** run 3.
- The device was plugged in with `stay_on_while_plugged_in = 2` and reported `mWakefulness=Awake`
  afterwards, so a plain screen-off is not an established cause.
- No crash/ANR/OOM text appeared in the Gradle log, but **logcat was not captured during the
  run**, so the app process's own fate is unknown.

**Next step is diagnostic, not a fix.** Re-run with logcat streaming to a file
(`adb logcat > /tmp/inst-logcat.txt &` before the run, as CI does at
`scripts/run-instrumented-tests.sh:57-58`), then look at what happens to the app process
immediately before the first `No compose hierarchies found`. Likely candidates to rule out, in
order: the app process being killed by the system after the long benchmark phase; a leaked
Activity or `ComposeTestRule` from the preceding test class; and device thermal throttling
after ~25 minutes of sustained load. Only once the cause is known is it worth deciding whether
this is a test-infrastructure fix or a genuine app defect.

Note the test classes themselves also have a latent weakness worth fixing whenever they are
next touched: `MainScaffoldTest` and `RootNavigationTest` drive the **real** `MainActivity` via
`createAndroidComposeRule<MainActivity>()` and assert on the hardcoded English literal
`"Dashboard"` (`MainScaffoldTest.kt:39, 52, 66`), so they depend on whatever real
DataStore/Room state the device holds and on the app not being in onboarding. Replacing the
literal with `stringResource`/test tags and seeding deterministic state would remove a whole
class of ambiguity from future investigations — and the hardcoded string violates the project's
own strings rule.

### Verification

Whatever you change, the bar is: `./gradlew :app:connectedDebugAndroidTest
:core:database:connectedDebugAndroidTest` green twice in a row on a physical device under the
documented local conditions. One green run is not enough for a flake investigation.

---

## Item 4 — Optional: align packages with Gradle modules

**This is cosmetic, wide, and risky to combine with anything else. Schedule it alone.**

### The situation

Package names do not follow module boundaries. Measured 2026-08-18 across `src/main` only:
**16 packages span more than one Gradle module.**

| Modules | Package |
|--:|---|
| 5 | `app.readylytics.health.di` → `app`, `core/database`, `core/healthconnect`, `core/model`, `core/scoring` |
| 4 | `app.readylytics.health.domain.sync` → `app`, `core/database`, `core/healthconnect`, `core/model` |
| 3 | `app.readylytics.health.domain.dashboard` → `core/model`, `core/scoring`, `feature/dashboard` |
| 3 | `app.readylytics.health.data.migration` → `app`, `core/database`, `database-benchmark` |
| 2 | `app.readylytics.health.workers` → `app`, `core/model` |
| 2 | `app.readylytics.health.domain.util` → `core/model`, `core/scoring` |
| 2 | `app.readylytics.health.domain.user` → `app`, `core/model` |
| 2 | `app.readylytics.health.domain.security` → `app`, `core/model` |
| 2 | `app.readylytics.health.domain.scoring` → `core/model`, `core/scoring` |
| 2 | `app.readylytics.health.domain.migration` → `app`, `core/model` |
| 2 | `app.readylytics.health.domain.common` → `core/model`, `core/scoring` |
| 2 | `app.readylytics.health.data.security` → `app`, `core/database` |
| 2 | `app.readylytics.health.data.preferences` → `app`, `core/model` |
| 2 | `app.readylytics.health.data.mapper` → `core/database`, `core/healthconnect` |
| 2 | `app.readylytics.health.data.local.entity` → `core/database`, `core/database-schema` |
| 2 | `app.readylytics.health.data.local.dao` → `core/database`, `core/database-schema` |

Scope: 705 Kotlin files under `src/main`, 1,155 across all source sets.

### Why it is worth doing eventually

The architecture guard in `app/src/test/kotlin/app/readylytics/health/CleanArchTest.kt` cannot
express its rules as package predicates because packages do not identify modules. It resorts to
brittle path-string matching, with Windows/POSIX separator pairs duplicated throughout —
lines 75, 112, 145-147, 176, 201-202, 254-255. Aligning packages to modules
(`…core.database.*`, `…core.healthconnect.*`, and so on) lets those become
`resideInPackage(...)` predicates. It would also likely clear the 4 `InvalidPackageDeclaration`
entries tracked in `DETEKT_BASELINE_BURNDOWN.md`.

### How to do it, if it is scheduled

1. One module at a time, smallest first — `core/database-schema` (33 files) is the natural pilot.
2. Rename with an IDE-grade refactor, not `sed`: Hilt, Room, kotlinx.serialization and
   WorkManager all resolve names in ways plain text substitution will silently break. Watch in
   particular for: `@HiltWorker` / worker class names in WorkManager configuration, Room
   `@Database` entity lists and generated schema JSON in `core/database/schemas`, proto/DataStore
   serializers, and any fully-qualified name in a string.
3. **Rewrite the corresponding `CleanArchTest` rules in the same commit** — the whole point is
   to convert path filters into package predicates, and leaving them behind means the rename
   bought nothing.
4. Baseline signatures change when file paths change: expect `detekt-baseline.xml` churn and
   handle it per §5 of `DETEKT_BASELINE_BURNDOWN.md`.
5. Run `codegraph sync` after each module move.
6. Full gate after every module, not just at the end.

### Verification

Full gate green after each module, `CleanArchTest` rules converted rather than merely still
passing, and no change to any runtime behaviour.

---

## Explicitly out of scope here

- **`core:scoring` → `kotlin("jvm")`** — blocked on AGP 9.4.0 stable, which is outside this
  project's control. Fully specified in `internal-docs/plans/CORE_SCORING_JVM_MIGRATION.md`.
- **The 658 detekt baseline findings** — see `internal-docs/plans/DETEKT_BASELINE_BURNDOWN.md`.
- **`data/healthconnect` coverage** — measured at 33.66% line coverage, the weakest package in
  the repo. Raising it was considered during the remediation and never scheduled; it remains
  unowned work rather than a decision that was made.
