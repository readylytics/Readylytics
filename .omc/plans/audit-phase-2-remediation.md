# Audit Phase 2 remediation — status & continuation plan

Tracking branch: `claude/admiring-galileo-kujbv2` (PR #106). Source: `internal-docs/ARCHITECTURE_AUDIT.md`.

> Written so work can continue in a normal local environment (Gradle/AGP/emulator available).
> The cloud session that produced M1/m2/M5 could not run Gradle at all (Maven host network-blocked),
> so `ktlintFormat` / `testDebugUnitTest` / `lint` were never executed there — **run them first** on
> this branch before trusting anything below as green.

## Done

- **M1 — Decompose `HealthSyncUseCase` god-class.** Facade now delegates to
  `HealthIngestionCoordinator`, `StepCountFetcher`, `RetryWithBackoff`, `DailyRecomputeSupport`,
  `DailySyncUseCase`, `ResyncRangeUseCase` (all in `domain/sync/`). Public API (`sync`,
  `catchUpSync`, `resyncRange`) unchanged; `syncMutex` still owned solely by the facade.
  `internal-docs/DATA_FLOW.md` updated to match. Characterization tests relocated onto the new
  seams (`DailySyncUseCaseTest`, `ResyncRangeUseCaseTest`, retargeted
  `ResyncCheckpointResumeTest`, thin-facade `HealthSyncUseCaseTest`).
- **m2 — Theme fallback defaults.** Fixed incoherent `staticCompositionLocalOf` fallback defaults
  for `LocalStatusColors`/`LocalExtendedColors` (commit `c3490e1`). Runtime theme adaptivity was
  already correct; this was only the unused-fallback-default cleanup.
- **M5 (finish, partial) — Recomposition-stability guards.** Added pure-JVM unit tests that run in
  CI (`testDebugUnitTest`), commit `9882238`:
  - `app/src/test/kotlin/app/readylytics/health/ui/common/DailyDataPointTest.kt` — structural
    `equals`/`@Immutable` contract for the trend-chart data point, plus `padToRange` gap-filling
    logic and boundaries.
  - `app/src/test/kotlin/app/readylytics/health/ui/components/CardConfigurationsListTest.kt` —
    structural-equality contract of the `@Immutable` `List` wrapper that lets the reorderable card
    grid skip recomposition.

**First step locally:** run `./gradlew ktlintFormat && ./gradlew testDebugUnitTest && ./gradlew lint`
on `claude/admiring-galileo-kujbv2` to confirm M1/m2/M5 are actually green — none of this was
verified by a real build.

## Remaining work

### M5 — finish the rest (device-level, needs Gradle + emulator)

Not done; both need a real build environment to do safely:

1. **Device-level recomposition-count tests** (`createComposeRule`). `androidx.compose.ui.test
   .junit4` is currently wired only to the `androidTest` source set
   (`app/build.gradle.kts:465-470`), and CI's `validate-and-build` job runs `testDebugUnitTest`
   only — it does **not** run `connectedAndroidTest`. Two options:
   - Add a `connectedAndroidTest` step to CI (emulator runner) so existing/new
     `androidTest` recomposition tests actually execute in CI, **or**
   - Get Compose UI testing working under Robolectric in the unit `test/` source set (there's
     already a Robolectric precedent: `app/src/test/kotlin/.../ui/theme/ThemeTest.kt`, but it only
     exercises pure color math — no Compose `createComposeRule` usage yet to copy from). This is
     unproven in this codebase; expect friction with `ui-test-manifest`/Robolectric interplay.
   - Target surfaces once runnable: the dashboard's heavy charts (e.g. trend charts consuming
     `List<DailyDataPoint>`, `ReorderableCardGrid` consuming `CardConfigurationsList`) — assert
     recomposition count stays flat when an equal-but-newly-allocated list/data class is re-emitted.
2. **Startup metric (Macrobenchmark).** Needs a new `:macrobenchmark` Gradle module + Baseline
   Profile generation + an emulator-backed CI job. This is a build-topology change with real
   blast radius (a misconfigured module/CI step can break the whole build), so it should be done
   incrementally with the build runnable at each step, not blind.

### m1 — Domain history ports for `BaselineComputer` (DEFERRED — scope finding)

Originally scoped as "give `BaselineComputer` a narrow history-read port instead of direct
Room-DAO access." Turned out **not containable** to `BaselineComputer` alone: the determinism
tests share the same Room-DAO mocks across the whole scoring engine —
`CurrentNightHrvResolver`, `SleepPercentileRhrCalculator`, `SleepNadirAnalyzer`,
`ScoringRepository`, and `BaselineComputer` together. Porting one class's data access forces the
same port refactor across the entire scoring history-access surface, touching roughly 7–14
load-bearing determinism test files (the ones enforcing the off-limits scoring math stays
correct per CLAUDE.md).

Recommended approach when picked back up:
1. Do this **only** where `./gradlew testDebugUnitTest` can be run after every incremental step —
   the determinism suite is the only thing standing between a "looks fine" refactor and silently
   changed scoring output.
2. Consider scoping it as its own PR/branch separate from the rest of Phase 2, given the surface
   area is the whole scoring engine, not a single class.
3. Re-derive the actual DAO call sites fresh (don't trust this doc's class list to be exhaustive —
   it was assembled by reading tests, not by a full call-graph sweep) before committing to a port
   interface shape.

## Phase 3 — Scalability Improvements (mid-term, NOT STARTED)

Per `internal-docs/ARCHITECTURE_AUDIT.md` (Phase 3, lines ~303–311). Goal: enforce
Clean-Architecture boundaries via real Gradle modules, speed up builds, deepen tests. None of
this is started. Recommended order within the phase: **M3 first cut → m6 → m7**. Pairs naturally
with **m1** above (the `:core:scoring` extraction is the right moment to introduce the domain
history ports). Strangler pattern throughout — **one module per PR, `:app` assembles green at
every step**. This is build-topology work; do it only with Gradle runnable.

### M3 — Incremental modularization (extract `:core:*` modules)

- **Root cause:** the whole app is one Gradle module (`:app`, ~47k LOC main / ~26k LOC test), so
  every change recompiles everything and the Clean-Architecture layers are enforced by convention
  only — nothing stops a UI class importing a DAO.
- **First cut (the recommended scope — Medium, not the full feature-modularization):**
  1. `:core:model` — pure data/model types.
  2. `:core:scoring` — `domain/scoring/**`, which **already has zero `android.*` imports**, so it
     should extract cleanly; this is the highest-value boundary to harden (off-limits formulas get
     a compiler-enforced wall).
  3. `:core:database` — Room (`HealthDatabase`, entities, DAOs, migrations).
  4. `:core:healthconnect` — `data/healthconnect/**` mappers + HC repo.
  - Feature modules (per-screen) are **Phase 4**, not here.
- **Prerequisite already met:** version catalog (`gradle/libs.versions.toml`) is in place.
- **Gotchas to expect:** Hilt across modules (module-level `@InstallIn` components must still
  resolve), KSP/Room codegen per module, `internal` visibility that silently widened to
  cross-module callers, and the `codegraph index`/`sync` step CLAUDE.md requires after structural
  moves. `internal-docs/DATA_FLOW.md` is load-bearing — update it for any ingestion/Room/scoring
  file moves in the same PR.
- **Verification per module PR:** `./gradlew :app:assembleDebug` green + full `testDebugUnitTest`
  + `lint` after each extraction; the scoring determinism suite is the gate when `:core:scoring`
  moves (it must still pass byte-for-byte — no formula drift).

### m6 — Re-trial KSP incremental compilation

- `ksp.incremental` is currently disabled in `gradle.properties`. Re-enable, build, and **measure**
  the build-time delta (best done *after* the module split, since incremental KSP wins compound
  with smaller compilation units). Small / Low risk. Revert if it reintroduces stale-codegen flakiness.

### m7 — Raise the coverage floor

- Per-package coverage gates are strong, but the **overall instruction floor is only 25%**. Thin
  spots: mappers/converters and some UI-state transitions. Raise the global floor gradually and add
  pure-math / mapper unit tests (these are Android-free and cheap to run in CI). Pairs well with the
  `:core:scoring` / `:core:model` extraction, where the mappers become easy isolated test targets.

## Phase 4 — Long-Term Hardening (NOT STARTED, out of current scope)

Per audit lines ~313–321. Listed here only so the roadmap is complete — do **not** pick these up
before Phase 3 lands:
- Per-feature modularization + Gradle convention plugins (the back half of M3).
- **m5** — key hardening: StrongBox-when-available, optional app-lock binding, key rotation, plus a
  backup/restore audit trail.
- **m4** — full accessibility program: contrast tests; forms / onboarding / nav coverage.
- Health Connect rate-limit-aware backoff loop; partial / staged restore UX.

## M-Restore — `LocalRestoreManager`: decouple DataStore writes from the SQLite restore transaction

> **Sequencing: DEFERRED.** Do not start until M5/m1 above are finished. This is a standalone
> follow-up scoped against PR #106 review feedback, parked here so it isn't lost — not next up.
>
> **Approach is locked to option (A)** (honest non-atomic restore). Option (B) is rejected — see
> "Approach" below.

### Context

PR #106 review (gemini-code-assist, **medium**) flagged
`app/src/main/kotlin/app/readylytics/health/data/backup/LocalRestoreManager.kt:154`. Inside
`applyRestore(...)`, the whole restore runs in one `healthDatabase.withTransaction { … }` block
(lines 146–155): it clears + repopulates the Room tables **and** calls
`restorePreferences(prefsBackup, …)` → `settingsRepository.batchUpdate { … }`, which is a **Proto
DataStore write** (disk I/O on DataStore's own single-writer dispatcher).

Two problems:
1. **Performance / locking anti-pattern.** DataStore I/O executes on a separate dispatcher; running
   it *inside* `withTransaction` holds the SQLite write transaction (and its connection thread) open
   across slow, unrelated disk I/O. Under concurrent DB access this risks thread starvation, lock
   contention, or `SQLiteDatabaseLockedException`.
2. **False atomicity.** The nesting was introduced deliberately — commit `a48973a` *"fix: restore
   preferences inside DB transaction for restore atomicity (M4)"* — to roll the DB restore back if
   prefs restore throws. But it is only half a guarantee: SQLite and Proto DataStore are **two
   independent stores with no shared transaction**. If the DataStore write succeeds and the DB
   transaction then fails to commit, the DataStore changes cannot be rolled back. Cross-store
   atomicity is fundamentally unattainable here; the current code pays the perf cost for a guarantee
   it does not actually provide.

**Goal:** remove the anti-pattern (no DataStore I/O while holding the SQLite transaction) without
regressing the M4 intent (don't go back to a silent partial restore). This touches a hard-to-reverse,
user-data path (full restore, `SuccessRequiresRestart`), so it is its own phase, not folded into M1.

### Constraint that drives the design

There is no API to commit Room + DataStore atomically, and `SettingsRepository` exposes no
whole-proto overwrite — only field-wise `batchUpdate { … }` (internally atomic per write) and a
`userPreferences.first()` snapshot. So the realistic target is **"each store atomic on its own,
sequenced, with an explicit, honest failure contract,"** not true all-or-nothing.

### Approach — option (A), honest non-atomic restore (LOCKED)

**Sequentialize instead of nest.** Keep the DB clear+repopulate inside `withTransaction` (that part's
atomicity is real and worth keeping); move `restorePreferences(...)` to run **after** the DB
transaction commits, outside it:

```kotlin
var prefsBackup: UserPreferencesBackup? = null
healthDatabase.withTransaction {
    performStreamingRestore(reader) { prefsBackup = it }   // DB clear + repopulate only
}
prefsBackup?.let { restorePreferences(it, providedPassword) }  // DataStore write, post-commit
```

`performStreamingRestore` already captures `prefsBackup` via callback while streaming, so the parse
still happens once; only the *write* moves out, and `prefsBackup` is hoisted above the transaction so
it's visible afterward.

**Failure contract:** the DB commits first; if `restorePreferences` then throws, it propagates to the
existing `runCatching { … }.getOrElse { RestoreResult.Failure(it) }` and surfaces as
`RestoreResult.Failure` — **no new try/catch needed**. Since restore already replaces everything and
requires an app restart, the documented recovery is "re-run restore"; re-running is safe because the
DB restore is idempotent (`deleteAll()` + repopulate from the same backup). This removes the
misleading "atomic" guarantee (which the two-store design never actually provided) and, critically,
the SQLite transaction no longer wraps DataStore I/O — the real fix.

**Option (B) — best-effort prefs snapshot/rollback — is rejected.** It adds a full field-wise
re-apply path, still cannot cover a crash between the two writes, and re-introduces complexity for a
guarantee that remains illusory. Not in scope.

### Files

- **Modify:** `data/backup/LocalRestoreManager.kt` (`applyRestore`: unwrap the prefs write from
  `withTransaction`; add the chosen failure contract). Keep `performStreamingRestore`'s DB-only work
  inside the transaction.
- **Docs:** check whether `internal-docs/DATA_FLOW.md` / any backup section documents the restore
  transaction boundary; update if so. User-facing backup behavior is unchanged (still full replace +
  restart), so `docs/privacy.md` / `docs/about.md` likely need **no** change — verify, don't assume.

### Tests (`data/backup/` mirror)

- DB transaction still wraps the table clear+repopulate (atomic) — assert a forced failure *during
  DB repopulation* leaves prior data intact (rolled back).
- Prefs write happens **after** a successful DB commit, and **not** while the transaction is open
  (verify ordering: `settingsRepository.batchUpdate` is invoked after `withTransaction` returns).
- Failure path (option A): a `settingsRepository.batchUpdate` that throws → `RestoreResult.Failure`
  with that cause, DB left committed. `LocalRestoreManagerTest` already has this test
  (`batchUpdate throws → Failure`); confirm it still passes unchanged after the move.
- Existing restore success/round-trip, `applyRestore_updatesPreferences`, and wrong-password tests
  still pass (the real Room `db` in the test executes the `withTransaction` lambda, so moving the
  prefs write out keeps `batchUpdate` reachable on the success path).

### Verification

1. `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` (the backup tests are the gate), then
   `./gradlew lint`.
2. Manual reasoning that no `DataStore`/`batchUpdate`/`settingsRepository.*` call remains lexically
   inside any `withTransaction { … }` in `LocalRestoreManager`.
3. Commit on `claude/admiring-galileo-kujbv2`; push. Reply on PR #106 only if useful (reviewer is a
   bot — prefer summarizing in chat). No new PR unless asked.

## Sequencing note

Nothing above has been merged via a PR — everything is commits on `claude/admiring-galileo-kujbv2`
against open PR #106.
