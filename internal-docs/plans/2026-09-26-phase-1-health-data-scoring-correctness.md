# Phase 1 Health Data and Scoring Correctness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the Phase 1 correctness and data-integrity findings in the architecture remediation plan without changing scoring formulas or the Room schema.

**Architecture:** Keep Room as the source of truth and retain the existing capture-before-read, generation-fenced daily publication protocol. Put settings recomputes behind a narrow `:core:model` port implemented in `:core:database`, so feature modules do not acquire a database dependency. Change checkpoint identity once, in the same release as the hrMax producer cleanup.

**Tech Stack:** Kotlin, coroutines, Hilt, Room/SQLCipher, Health Connect, WorkManager, JUnit, Robolectric and Android instrumented tests.

**Spec:** `internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md`, Phase 1 and WP-03 through WP-10; review baseline `782dd99f`.

## Global Constraints

- `minSdk=26`, `targetSdk=37`; Room schema stays at version 23.
- Health Connect is ingestion-only; UI reads Room through domain ports.
- Scoring formulas are off limits; all recomputation remains through `ScoringRepository.computeDailySummary(day)` or its existing persistence path.
- Daily refresh remains current-day-only; full historical resync remains durable WorkManager work.
- Keep cancellation cooperative and never swallow `CancellationException`.
- Keep the retained-suffix dependency closure for score-affecting changes; recommendation examples retain their separate 30-day fan-out.
- Update `internal-docs/DATA_FLOW.md` in the same commit as every ingestion, Room, or scoring-path change. If user-facing scoring copy changes, synchronize `ABOUT.md`, `docs/about.md`, and `app/src/main/res/values/strings.xml`.
- No new detekt issue, baseline entry, or `@Suppress`; resolve existing detekt issues in touched files. Keep files below 800 lines, preferably below 400.
- Every package runs `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest`; run `./gradlew lintRelease` after all Phase 1 coding tasks. New/deleted files require `codegraph index`; structural moves require `codegraph sync`.
- Phase 0's `DataFlowPathReferenceTest` is already present in this checkout. Run `./gradlew :app:testDebugUnitTest --tests '*DataFlowPathReferenceTest'` before Task 1; if it fails, complete WP-02's two path corrections before Phase 1.

## Review Focus

1. A failed preference migration with a pending dirty ticket still enqueues the recompute, but a clean startup enqueues nothing (Task 2 tests).
2. An excluded `EXERCISE` upsertion deletes the existing workout and its affected date, while a selected update preserves route and model metrics (Task 4 tests).
3. A nested mutation in the same coroutine completes, while a child coroutine and maintenance operation remain serialized (Task 5 tests).
4. A malformed journal row at the retention boundary is readable and removed without discarding valid retained work (Task 3 tests).
5. A v3 checkpoint at any of the four resync phases restarts from the range start under protocol 4, while a v3 backup inventory still validates (Task 6 tests).

---

## File Structure and Ownership

| Unit | Files to change | Responsibility |
|---|---|---|
| Dirty publication | `core/database/.../DirtySummaryPublisher.kt`, `RoomDirtyRangeStore.kt`, `core/database-schema/.../DirtyRangeDao.kt`, `DirtyRangeEntity.kt` | One publication protocol; write-time range validation and read-time repair. |
| Startup | `app/.../DatabaseReadyStartupInitializer.kt` | Independent migration and recompute gates. |
| Changes ingestion | `core/healthconnect/.../HealthChangeSynchronizerImpl.kt` | Source-selection decision before update-in-place. |
| Settings recompute | New `core/model/.../domain/scoring/RecomputeToday.kt` port; new `core/database/.../domain/scoring/RecomputeTodayUseCase.kt`; `core/database/.../di/ScoringSyncBindingsModule.kt`; three callers | Serialize and safely defer today’s recompute. |
| Checkpoint identity | `core/model/.../sync/{ScoringRunSnapshot,HistoricalRunIdentity}.kt`; `core/healthconnect/.../ResyncRangeUseCase.kt`; snapshot-id producers and tests | One snapshot shape and protocol 4. |
| Baselines | `core/scoring/.../BaselineComputer.kt` and its tests | One live HRV and RHR baseline path. |
| Invalidation/math | `core/model/.../ScoreInvalidation.kt`; `core/scoring/.../{MathUtils,LoadScoringStrategy,SleepBaselineMetrics,ComputeHistoricalBaselinesUseCase}.kt` | One horizon; explicit standard-deviation fallbacks. |
| Documentation | `internal-docs/DATA_FLOW.md`; conditional `ABOUT.md`, `docs/about.md`, `app/.../strings.xml` | Same-commit pipeline and copy synchronization. |

Use the exact package paths in the repository; ellipses in this map are only for readability. Each task below names its own paths and tests. Do not create a second settings-side implementation of the recompute logic.

### Task 1: Remove the dead dirty-ticket publication path (WP-04, DB-102)

**Files:** Modify `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/DirtySummaryPublisher.kt`, `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RoomDirtyRangeStore.kt`, `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/DirtyRangeDao.kt`, `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/DirtyRangeEntity.kt`, `core/database/src/androidTest/kotlin/app/readylytics/health/core/database/data/local/DirtyMutationRecoveryInstrumentedTest.kt`, and `internal-docs/DATA_FLOW.md`.

**Interfaces:** Consume `DirtySummaryPublisher.captureDay(day: LocalDate): DayPublication` and `publishDay(publication: DayPublication, write: suspend () -> Unit): Boolean`. Produce those same two public methods; remove both `publish(ticket, ...)` overloads, `RoomDirtyRangeStore.rebindSnapshot`, and `DirtyRangeDao.updateSnapshotId`. Keep the `scoringSnapshotId` column for diagnostics.

- [ ] **Step 1: Migrate the instrumented dirty-drain assertions.** In `DirtyMutationRecoveryInstrumentedTest`, make the advance, source-generation mismatch, completion-delete, and retention-trim scenarios call `captureDay` then `publishDay`; assert a mismatched generation returns `false` and leaves both summary and ticket unchanged.
- [ ] **Step 2: Run the targeted instrumented test.** Run `./gradlew :core:database:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.core.database.data.local.DirtyMutationRecoveryInstrumentedTest`. Expected: green before deletion; the migrated tests prove the live path.
- [ ] **Step 3: Delete the dead symbols.** Remove their imports and test references. Add a KDoc sentence to `DirtyRangeEntity.scoringSnapshotId` stating it is diagnostic metadata, not a publication fence. Update the dirty-publication paragraph in `internal-docs/DATA_FLOW.md` in this edit.
- [ ] **Step 4: Verify.** Run the targeted instrumented command again and `rg -n 'rebindSnapshot|updateSnapshotId|activeSnapshotId' app/src/main core/*/src/main feature/*/src/main`; expected: test PASS and no matches. Run the global package gate, then `git add` the task files and `git commit -m "refactor: remove dead dirty publication protocol"`.

### Task 2: Make the startup recompute gate independent (WP-03, CACHE-101)

**Files:** Modify `app/src/main/kotlin/app/readylytics/health/DatabaseReadyStartupInitializer.kt`, `app/src/test/kotlin/app/readylytics/health/DatabaseReadyStartupInitializerTest.kt`, `app/src/test/kotlin/app/readylytics/health/DatabaseReadyStartupInitializerScoringVersionTest.kt`, and `internal-docs/DATA_FLOW.md`.

**Interfaces:** `scheduleRecomputeResyncIfNeeded(prefs: UserPreferences)` stays private and keeps trigger priority `STARTUP_SCORING_VERSION > STARTUP_TRIMP_BACKFILL > STARTUP_PENDING_DIRTY`.

- [ ] **Step 1: Add failing tests.** A throwing `migrateTrimpDefaultsIfNeeded()` fake plus one pending `DirtyTicket` must still call `scheduleResyncWorker(recomputeOnly = true, trigger = STARTUP_PENDING_DIRTY)` once. Add a clean-start case that calls it zero times and a three-gates-true case selecting `STARTUP_SCORING_VERSION`.
- [ ] **Step 2: Confirm red.** Run `./gradlew :app:testDebugUnitTest --tests '*DatabaseReadyStartupInitializerTest' --tests '*DatabaseReadyStartupInitializerScoringVersionTest'`; expected: the throwing-migration case fails because nothing was enqueued.
- [ ] **Step 3: Unnest the gates.** Rename the boolean to `trimpDefaultsMigrationSucceeded`; keep its diagnostic meaning only. Run the recompute check in a separate `runNonFatal` after obtaining settings, regardless of that boolean. Keep the existing cancellation behavior.
- [ ] **Step 4: Verify.** Re-run the targeted tests and the global package gate; expected PASS. Update `DATA_FLOW.md`'s startup row, then `git add` and `git commit -m "fix: run startup recompute gate after migration failure"`.

### Task 3: Move dirty-range invariants to writes and repair invalid rows (WP-05, DB-101)

**Files:** Modify `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/DirtyRangeEntity.kt`, `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/DirtyRangeDao.kt`, `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RoomDirtyRangeStore.kt`, `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/DirtyRangeEntityTest.kt`, `core/database/src/androidTest/kotlin/app/readylytics/health/core/database/data/local/DirtyMutationRecoveryInstrumentedTest.kt`, and `internal-docs/DATA_FLOW.md`.

**Interfaces:** Keep `DirtyRangeDao.discardBefore(cutoffDay: Long)` and `RoomDirtyRangeStore.append(start: LocalDate, endInclusive: LocalDate, reason: String, snapshotId: String): Long`. Add `DirtyRangeDao.deleteInvalid(): Int`; its SQL predicate is `startEpochDay > endEpochDayInclusive OR nextEpochDay < startEpochDay OR nextEpochDay > endEpochDayInclusive + 1`.

- [ ] **Step 1: Add the failing read/repair test.** Insert three invalid rows by raw SQL, one per predicate, plus a valid row with `nextEpochDay == endEpochDayInclusive + 1`. Assert `pending()` reads all four without throwing, `discardBefore(cutoffDay)` removes the invalid rows, and the valid retained row remains. Move `DirtyRangeEntityTest`'s three invalid-construction assertions to `RoomDirtyRangeStore.append` tests.
- [ ] **Step 2: Confirm red.** Run the targeted `DirtyMutationRecoveryInstrumentedTest` connected test; expected: Room materialization throws on an invalid row.
- [ ] **Step 3: Implement.** Remove the entity `init` block; put the same three `require` checks immediately before `append` inserts. Add `deleteInvalid()` as a `@Query` and call it first inside `discardBefore`'s existing `@Transaction`, then `deleteExpired` and `trimExpiredPrefixes`. Protect `endEpochDayInclusive + 1` from `Long.MAX_VALUE` overflow in both validation and repair predicate.
- [ ] **Step 4: Verify.** Run the targeted connected test, the store unit tests, and the global gate; expected PASS and database schema version still 23. Update `DATA_FLOW.md`, then `git add` and `git commit -m "fix: repair invalid dirty rows on retention trim"`.

### Task 4: Delete excluded workouts on the changes path (WP-06, HC-102)

**Files:** Modify `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthChangeSynchronizerImpl.kt`, `core/healthconnect/src/test/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthChangeSynchronizerImplTest.kt`, and `internal-docs/DATA_FLOW.md`.

**Interfaces:** Keep `processChangesPage(...)`'s signature and the existing `ChangeIngestionStore` methods. Preserve selected-device `EXERCISE` update-in-place and all `DeletionChange` behavior.

- [ ] **Step 1: Add the failing case.** With selected `EXERCISE` device `WatchA`, a stored record id `workout-1`, and an `UpsertionChange` reporting `WatchB`, assert `deleteRecord(EXERCISE, "workout-1")` is called, `upsertRecord`/workout persist is not called, and the stored workout day is in `affectedDates`. Add a matching-device case asserting no delete and preservation of `modelTrimp`, route state, distance, and elevation via the existing preparer/persist tests; keep the exercise deletion-change test.
- [ ] **Step 2: Confirm red.** Run `./gradlew :core:healthconnect:testDebugUnitTest --tests '*HealthChangeSynchronizerImplTest'`; expected: the excluded-exercise assertion fails.
- [ ] **Step 3: Change branch order.** Decide source match before deletion: non-matching records delete for every type, matching `EXERCISE` upserts in place, and matching other types retain delete-then-upsert. Keep affected-date capture before deleting. Update the synchronizer row in `DATA_FLOW.md`.
- [ ] **Step 4: Verify.** Run the targeted test and global gate; expected PASS. `git add` and `git commit -m "fix: remove excluded workouts during changes sync"`.

### Task 5: Serialize settings recomputes and persist birthday inputs first (WP-07, CACHE-102/SCORE-105)

**Files:** Create `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/scoring/RecomputeToday.kt` and `core/database/src/main/kotlin/app/readylytics/health/core/database/domain/scoring/RecomputeTodayUseCase.kt`; modify `core/database/src/main/kotlin/app/readylytics/health/core/database/di/ScoringSyncBindingsModule.kt`, `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/HealthMutationCoordinatorImpl.kt`, `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/HealthMutationCoordinatorImplTest.kt`, `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/{SleepSettingsViewModel,ThresholdSettingsViewModel}.kt`, their `SleepAndThresholdSettingsViewModelTest.kt`, `app/src/main/kotlin/app/readylytics/health/domain/user/UserUseCase.kt`, `app/src/test/kotlin/app/readylytics/health/domain/user/UserUseCaseTest.kt`, and `internal-docs/DATA_FLOW.md`. Create a focused `core/database/src/test/kotlin/app/readylytics/health/core/database/domain/scoring/RecomputeTodayUseCaseTest.kt`.

**Interfaces:** `interface RecomputeToday { suspend fun execute(day: LocalDate): Result<Unit> }` in `:core:model`; `class RecomputeTodayUseCase @Inject constructor(coordinator: HealthMutationCoordinator, scoringRepository: ScoringRepository, workerScheduler: WorkerScheduler) : RecomputeToday` in `:core:database`; bind in `ScoringSyncBindingsModule`. On maintenance failure, enqueue `scheduleResyncWorker(recomputeOnly = true, startDate = day, endDate = day, trigger = SETTINGS_CHANGE)` and return `Result.Failure(code = "MAINTENANCE_PENDING", ...)`; cancellation propagates. Other failures return a stable failure without silently reporting success.

- [ ] **Step 1: Pin coordinator behavior.** Add tests for nested same-coroutine `withMutation`, a child coroutine that cannot enter while its parent owns the lock, and maintenance exclusion. Confirm the nested test currently hangs or fails using a test timeout, never an unbounded wait.
- [ ] **Step 2: Make the coordinator reentrant for the owning coroutine.** Carry a private coroutine-context owner marker tied to this coordinator and the current `Job`; only an exactly matching owner bypasses mutex acquisition. All other callers, including child jobs, use the existing mutex and maintenance check. Re-run coordinator tests; expected PASS.
- [ ] **Step 3: Add failing use-case tests.** A fake multi-day `withMutation` run must finish before `execute(today)` calls `computeAndPersistDailySummary(today)`; maintenance pending returns `MAINTENANCE_PENDING`, schedules exactly the one-day worker, and never calls scoring; cancellation rethrows and enqueues nothing.
- [ ] **Step 4: Implement the port, use case and Hilt binding.** Use the existing `Result` hierarchy; catch only predictable maintenance/failure cases and rethrow cancellation. Run `./gradlew :core:database:testDebugUnitTest --tests '*RecomputeTodayUseCaseTest' --tests '*HealthMutationCoordinatorImplTest'`; expected PASS.
- [ ] **Step 5: Repoint the three callers and test ordering.** Remove their direct single-day `ScoringRepository` dependency. In `UserUseCase.updateBirthday`, assert `updateBirthday` → `updateMaxHeartRate` when auto mode is on → `RecomputeToday.execute(today)` → durable historical enqueue. For manual mode, assert no max-HR write. In the two ViewModel tests, assert a maintenance result does not escape the coroutine; Threshold preserves its existing error/rollback behavior.
- [ ] **Step 6: Verify.** Run the three targeted test classes, the global gate and `rg -n 'computeAndPersistDailySummary\(LocalDate.now' app/src/main feature/*/src/main`; expected no direct settings/user call. Update `DATA_FLOW.md`, run `codegraph index` for the new files, then `git add` and `git commit -m "fix: serialize settings recomputes and birthday writes"`.

### Task 6: Unify snapshot identity and adopt protocol 4 together (WP-08, SCORE-101/SCORE-102)

**Files:** Modify `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/ResyncRangeUseCase.kt`; `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/{ScoringRunSnapshot,HistoricalRunIdentity}.kt`; snapshot-id callers in `app/src/main/kotlin/app/readylytics/health/data/backup/BackupSnapshotExporter.kt`, `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/DailyTrimpComputer.kt`, and `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/GetWorkoutDisplayMetricsUseCase.kt`; every affected test call site found by `rg 'resolvedHrMax|computeSnapshotId\(|ScoringRunSnapshot.capture\(|HistoricalRunIdentity.create\('`; `core/healthconnect/src/test/kotlin/app/readylytics/health/core/healthconnect/domain/sync/{HistoricalRunResolverTest,ResyncRangeUseCaseTest}.kt`; `app/src/test/kotlin/app/readylytics/health/data/preferences/ResyncCheckpointStoreImplTest.kt`; `app/src/test/kotlin/app/readylytics/health/data/backup/LocalRestoreValidationTest.kt`; and `internal-docs/DATA_FLOW.md`. Do not change the checkpoint proto wire fields.

**Interfaces:** Final signatures: `ScoringRunSnapshot.capture(prefs: UserPreferences): ScoringRunSnapshot`; `HistoricalRunIdentity.computeSnapshotId(prefs: UserPreferences): String`; `HistoricalRunIdentity.create(runId: String = ..., mode: String, startDate: LocalDate, endDate: LocalDate, zoneId: ZoneId, prefs: UserPreferences, startedAtEpochMs: Long, algorithmRevision: Int = ...): HistoricalRunIdentity`; `CURRENT_PROTOCOL_VERSION = 4`. No `resolvedHrMax` snapshot property or identity parameter.

- [ ] **Step 1: Add the temporary SCORE-101 regression test.** Parameterize ages 18 through 90 and both auto/manual max-HR modes. Before removing the field, assert the resync-request identity matches `computeSnapshotId(prefs, HeartRateFormulas.resolveMaxHeartRate(prefs))`; age 35 must expose the current 183.5 vs 183.0 mismatch. Run the targeted resync test; expected FAIL.
- [ ] **Step 2: Remove the duplicate arithmetic.** In `prepareRequestedRun`, call `HeartRateFormulas.resolveMaxHeartRate(prefs)` and remove `TANAKA_BASE`/`TANAKA_FACTOR`; run the regression test; expected PASS. Keep this in the same unreleased commit series as Steps 3–5.
- [ ] **Step 3: Add the protocol tests.** Assert `CURRENT_PROTOCOL_VERSION == 4`, no `resolvedHrMax` key in serialized snapshot JSON, `HistoricalRunResolver.resolve` rejects a saved v3 identity, and `resolveEffectiveCheckpoint` returns `null` for saved v3 checkpoints in `INGEST`, `PRUNE`, `RECONCILE`, and `RECOMPUTE` so the caller starts at range start. Round-trip a stored v3 identity through `ResyncCheckpointStoreImpl` and validate a v3 backup manifest with `RestoreInventoryValidator`; its snapshot id remains an opaque string.
- [ ] **Step 4: Remove the redundant field and parameters.** Update all production and test callers, including sample-less workout and backup snapshot producers; no production code may rederive Tanaka arithmetic for identity. Replace the temporary age test with final identity equality across those producers and a static assertion that `ResyncRangeUseCase` contains no `208`/`0.7` arithmetic. Preserve the existing `HeartRateFormulas.estimateMaxHr` body unchanged.
- [ ] **Step 5: Verify and document.** Run `./gradlew :core:model:testDebugUnitTest :core:healthconnect:testDebugUnitTest :app:testDebugUnitTest --tests '*ResyncCheckpointStoreImplTest'` as separate module test invocations if Gradle test filtering requires it; run restore-validation tests and the global gate. Update `DATA_FLOW.md`'s checkpoint identity and score-production sections in this commit. Check `ABOUT.md`, `docs/about.md` and in-app `about_*`/`tooltip_*` strings for an existing rounding claim; correct only a conflicting claim, with matching copies in all three. Commit this package as one release unit with message `fix: unify scoring snapshot identity at protocol four`.

### Task 7: Keep one sleep-day baseline implementation (WP-09, SCORE-103)

**Files:** Modify `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/BaselineComputer.kt`, `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/scoring/BaselineComputerTest.kt` and any tests still calling the two dead methods; `internal-docs/DATA_FLOW.md`; conditionally `ABOUT.md`, `docs/about.md`, `app/src/main/res/values/strings.xml`.

**Interfaces:** Keep `computeAdaptiveBaselineRhrBpmBetween(fromMs: Long, toMs: Long, rhrBaselineOverride: Float?, percentile: Int, zoneId: ZoneId, ...) : Float?` and `computeHrvBaselineBetween(fromMs: Long, toMs: Long, hrvBaselineOverride: Float?, zoneId: ZoneId, ...) : Int?`. Remove the `dayMidnight: Instant` variants.

- [ ] **Step 1: Migrate old-method tests.** Point them at the `...Between` methods using the scoring-zone day boundaries and preserve their valid-night and override assertions. Add a biphasic fixture with two core sleep sessions separated by less than `coreMergeGapMinutes`; assert one sleep-day contribution to the HRV median, not two session contributions. Add an RHR fallback assertion for no eligible nights.
- [ ] **Step 2: Confirm the live path before removing dead methods.** Run `./gradlew :core:scoring:testDebugUnitTest --tests '*BaselineComputerTest'`; expected PASS for the `...Between` fixtures. Delete `computeHrvBaseline(dayMidnight, ...)` and `computeAdaptiveBaselineRhrBpm(dayMidnight, ...)`, plus dead imports/KDoc. The test cycle here is a behavior-preserving refactor: the compiler and the migrated tests catch remaining old callers.
- [ ] **Step 3: Verify documentation.** Update `DATA_FLOW.md`'s baseline path. If any user-facing copy describes per-session averaging, revise `ABOUT.md`, `docs/about.md`, and the matching About/tooltip strings together; run the scoring documentation-drift/presence tests and the four-point documentation review checklist from `AGENTS.md`.
- [ ] **Step 4: Verify.** Run targeted baseline tests and global gate; expected PASS. `git add` and `git commit -m "refactor: keep one sleep-day baseline path"`.

### Task 8: Remove the obsolete invalidation horizon and explicitize sigma fallbacks (WP-10, CACHE-103/SCORE-106)

**Files:** Modify `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/ScoreInvalidation.kt`, `core/model/src/test/kotlin/app/readylytics/health/core/model/domain/sync/ScoreInvalidationTest.kt`, `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/util/MathUtils.kt`, `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/{ComputeHistoricalBaselinesUseCase,SleepBaselineMetrics}.kt`, `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/strategies/LoadScoringStrategy.kt`, their existing tests, and `internal-docs/DATA_FLOW.md`.

**Interfaces:** Keep `ScoreInvalidation.dependencyClosure(changed: AffectedRange, reason: Reason, retentionStart: LocalDate, today: LocalDate): AffectedRange?` as the sole exported score dependency horizon. Keep `MAX_DEPENDENT_WINDOW_DAYS = 84L` only as the lookback depth guard and preserve `exampleFanOutRange`. Remove unused `affectedRange(changed, today)`. Mark both non-null `stdev()` overloads deprecated; production uses `stdevOrNull()` with explicit fallbacks.

- [ ] **Step 1: Add/adjust tests.** Assert `dependencyClosure` reaches today for a baseline or source deletion, bounds recommendation examples to 30 days, clips at retention start, and returns null for future-only input. Keep the depth guard that fails if a scoring lookback exceeds 84. Assert `hrvSigma(emptyList(), sigmaPrior)` and one-element input return `sigmaPrior.coerceAtLeast(Restoration.MIN_LN_SIGMA)`; two-element input retains the current blend. Assert RHR sigma fallback remains `max(0.05 × mu, 1)`.
- [ ] **Step 2: Run tests before the edit.** Run `./gradlew :core:model:testDebugUnitTest --tests '*ScoreInvalidationTest'` and `./gradlew :core:scoring:testDebugUnitTest --tests '*LoadScoringStrategyTest' --tests '*MathUtilsTest'`; expected existing behavior green, with any new source/structure assertion red.
- [ ] **Step 3: Edit the APIs and callers.** Delete `affectedRange` and references in KDoc; clarify that 84 days is only the longest single lookback, not the invalidation horizon. In `LoadScoringStrategy`, `SleepBaselineMetrics`, and `ComputeHistoricalBaselinesUseCase`, use the appropriate `stdevOrNull()` overload and explicit current fallbacks (`?: 0f` only for the HRV blend where its weight is zero). Keep `mean()` and `median()` unchanged. Deprecate the two non-null `stdev()` overloads for compatibility with test callers. Update `DATA_FLOW.md`'s invalidation and scoring rows.
- [ ] **Step 4: Verify.** Run targeted tests, `rg -n '\.stdev\(' core/scoring/src/main`, the global gate and `./gradlew lintRelease`; expected no production `.stdev()` calls and all checks PASS. `git add` and `git commit -m "refactor: keep one invalidation horizon and explicit sigma fallbacks"`.

## Phase 1 Completion Review

- [ ] Check each Phase 1 finding (CACHE-101, HC-102, CACHE-102, SCORE-101/102/103/105/106, DB-101/102, CACHE-103) against its acceptance criteria in the spec. Record any unmet item before calling Phase 1 complete.
- [ ] Run the mandatory global gate and `./gradlew lintRelease` on the full Phase 1 tree; run `./gradlew :core:database:connectedDebugAndroidTest` for the dirty-drain cases. Confirm schema version 23 and no new detekt baseline or suppression.
- [ ] Run `codegraph index` after all created/deleted files, and `codegraph sync` only if an actual structural move occurred. Check `git status --short` and `git diff --check`.
- [ ] Prepare Release A notes: in-flight v3 resync checkpoints restart once at range start; affected users may see lower training load after excluded-device workouts are removed. The one-time repair of workouts already stranded before upgrade is WP-17/Phase 4, outside this Phase 1 plan.

## Plan Self-Review

- **Coverage:** All eleven Phase 1 finding IDs map to Tasks 1–8. Phase 0 benchmark work and Phase 2–5 scalability, one-time repair, and SCORE-104 documentation work remain in their own roadmap phases.
- **Interfaces:** `RecomputeToday` lives in `:core:model`, binds in `:core:database`, and is used by feature/app callers. Snapshot identity signatures are stated in final form; checkpoint proto shape and Room schema do not change.
- **Review focus:** The five risks above each have an owning test step. Task 6's checkpoint test spans all four phases; Task 3 protects valid rows at the retention boundary.
- **Documentation:** `DATA_FLOW.md` changes travel with every task that touches its mapped pipeline. User-facing copy changes, if required by the audit, travel together across all three surfaces.
