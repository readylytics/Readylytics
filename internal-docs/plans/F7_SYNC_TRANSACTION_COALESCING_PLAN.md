# F7 — Coalesce Room Invalidation Storms During Sync: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a routine foreground sync perform **one** `daily_summaries` write transaction
instead of one per synced day, so every observed DAO `Flow` in the UI re-runs once per sync
instead of once per synced day — with byte-identical scoring output.

**Architecture:** Room's `InvalidationTracker` fires **per table per transaction**. Wrapping the
whole walk-forward recompute loop in a single `HealthDatabase.withTransaction { }` therefore
collapses N per-day invalidations into one, *and* preserves the loop's read-after-write
dependencies for free (reads inside a transaction see that transaction's own uncommitted writes).
The daily sync gets one transaction for its whole window (≤8 days); the historical resync chunks
its transactions at 30 days, reusing the existing `RECOMPUTE_CHECKPOINT_INTERVAL_DAYS` so
checkpoint/resume granularity is unchanged.

**Tech Stack:** Kotlin, Room 2.8.4 (compatibility mode — SupportSQLite via SQLCipher
`openHelperFactory`, no `SQLiteDriver`), Hilt, kotlinx-coroutines, MockK, Robolectric.

---

## Global Constraints

Copied verbatim from `internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md` §1–§2 and the repo
`CLAUDE.md`. Every task's requirements implicitly include this section.

- **Scoring math is off-limits.** No task may change formulas, thresholds, or coefficients in
  `core/scoring` / `domain/scoring/**`. This plan changes *when/how* data flows and *when* it is
  committed — never what is computed.
- **No functional regressions.** All outputs must be structurally identical before/after.
- **Idempotency (non-negotiable):** Ingestion is upsert keyed by stable HC record `id`. NO blanket
  `deleteAll()`. A killed/failed worker must leave prior valid data intact and a retry must re-run
  the same range idempotently.
- **Concurrency:** Daily sync and resync share `HealthSyncUseCase.syncMutex` (serialized).
  Walk-forward recompute loops stay cooperative (`ensureActive()` + `yield()`); never swallow
  `CancellationException`.
- **Progress:** Recalc progress comes from `ForegroundSyncController` / `WorkInfo.progress`, never
  from Room emissions. Reuse this path — do not add parallel progress channels.
- Pre-commit (mandatory): `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`. Run
  `./gradlew lintRelease` once after the final commit of the batch.
- `internal-docs/DATA_FLOW.md` MUST be updated **in the same commit** for any change to the
  ingestion pipeline, Room schema/DAOs, or scoring coordinators. A stale `DATA_FLOW.md` is treated
  as a broken build. This binds Tasks 1, 2 and 3.
- File size: target ≤400 lines, hard limit ≤800. After creating new files run `codegraph index`.
- Load-bearing intent comments are house style: every change here must carry a short comment
  explaining why it is safe.
- No user-facing strings are added by this plan, so `strings.xml` is untouched.

---

## Background a fresh engineer needs

### The three collaborators

| Symbol | File | Role |
| --- | --- | --- |
| `DailySyncUseCase` | `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailySyncUseCase.kt` | Foreground sync. Ingests one HC window, reconciles, then walk-forward recomputes `oldestTargetDay..today` (≤8 days). |
| `ResyncRangeUseCase` | `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCase.kt` | Historical resync. Four phases INGEST → PRUNE → RECONCILE → RECOMPUTE; the RECOMPUTE phase is a walk-forward over up to 3650 days. |
| `DailyRecomputeSupport` | `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailyRecomputeSupport.kt` | Shared per-day helper both call. `recomputeDay(...)` → `ScoringRepository.computeAndPersistDailySummary(...)`. Documented as "a single point of daily score persistence" — which is why the transaction helper belongs here. |

### Why the naive "buffer all days, one `upsertAll` at the end" is wrong

`internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md` §F7 step 1 proposes buffering each day's
computed summary and upserting the list once after the loop. **That would silently change scores.**
Day N's computation reads rows that days N-1…N-6 wrote earlier in the same loop:

- `ScoringRepositoryImpl.kt:763-778` — `sumRasLastSixDays` calls
  `dailySummaryDao.getByDates(previousDaysMs)` for days **N-1 … N-6** and sums their
  `rasWorkoutOnly` / `rasEverydayHr` into today's `totalRasWorkoutOnly` / `totalRasEverydayHr`.
- `ComputeSleepMetricsUseCase.kt:152-158` — reads **yesterday's** `DailySummary`.
- `BaselineComputer.kt:131, 187, 270, 320` — frozen-baseline short-circuits read the day's own row.

A deferred write would make every one of those reads see stale (pre-loop) values. The single
transaction avoids the whole problem: reads inside the transaction observe the transaction's own
uncommitted writes, so results are identical to the per-day-commit behaviour by construction.

### Why the inner dispatcher switch is safe (verified against Room 2.8.4 sources)

`ScoringRepositoryImpl.computeDailySummary` runs its body in `withContext(defaultDispatcher)`
(`ScoringRepositoryImpl.kt:169`). That is *inside* the transaction block we are adding. Room
handles this:

- `RoomDatabase.android.kt:2053` `withTransactionContext` builds a context containing a
  `TransactionElement` holding the transaction's dispatcher, and confines the transaction to one
  thread from `transactionExecutor`.
- The Room KDoc at `RoomDatabase.android.kt:2113` states it explicitly: *"The `TransactionElement`
  serves as an indicator for inherited context, meaning, if there is a switch of context,
  suspending DAO methods will be able to use the indicator to dispatch the database operation to
  the transaction thread."*
- `DBUtil.android.kt:129-136` `getCoroutineContext` confirms the mechanism: a suspend DAO call
  resolves `coroutineContext[TransactionElement]?.transactionDispatcher` and, when present, appends
  it — so the DAO call is dispatched back onto the transaction thread regardless of which
  dispatcher the caller switched to. Coroutine context elements propagate across
  `withContext(otherDispatcher)`, so the element is still there.
- The app runs Room in **compatibility mode** (`RoomDatabase.inCompatibilityMode()` is true because
  `DatabaseModule.kt:56` supplies `openHelperFactory(...)` and no `SQLiteDriver`), which is the
  branch quoted above.

**Conclusion:** DAO calls issued from inside `computeAndPersistDailySummary` land in the enclosing
transaction. No production code needs to hoist the dispatcher switch.

### Consequences of one transaction that you must accept and document

1. **Cancellation rolls back the whole window/chunk.** Today, days completed before a cancellation
   stay committed. After this change they are discarded. This is approved: recompute is idempotent
   and the next sync re-runs the same window, so the cost is one re-run and the state is never
   inconsistent.
2. **A per-day `Result.Failure` still commits the surviving days on the daily path.** `recomputeDay`
   catches `Exception` and returns `Result.Failure` without rethrowing
   (`DailyRecomputeSupport.kt:89-94`), so no exception escapes to abort the transaction. The
   existing "log and continue, then report `SYNC_PARTIAL_FAILURE`" semantics are preserved exactly.
3. **No Health Connect I/O inside the transaction.** On both paths the HC window read, the
   session-link reconcile, and the step-count fetch all complete *before* the transaction opens.
   Never move them in.
4. **`workout_records` is coalesced for free.** `ScoringRepositoryImpl.kt:270-272` upserts
   `modelTrimp`-changed workouts once per recomputed day, which today invalidates `workout_records`
   per day (the Workouts tab observes it). Inside one transaction those N writes produce **one**
   invalidation, so F7 needs no separate buffering layer for them — the per-day `upsertAll` calls
   stay exactly as they are and Task 4 locks the coalescing with a test. Do not add a buffer here;
   it would reintroduce the compute/persist split this plan deliberately avoids.

---

## File Structure

**Modified — production:**

| File | Responsibility after this plan |
| --- | --- |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailyRecomputeSupport.kt` | Gains `transactionRunner: TransactionRunner` and one new method, `inRecomputeTransaction { }` — the single place either sync path opens a recompute transaction. |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailySyncUseCase.kt` | Builds the walk-forward TRIMP/baseline contexts once (Task 1); wraps `clearFrozenBaselines` + the whole walk-forward loop in one transaction (Task 2). |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCase.kt` | RECOMPUTE phase becomes an outer 30-day chunk loop, each chunk one transaction, checkpoint saved after each chunk commits (Task 3). |

**Modified — tests (constructor signature change ripples):**

- `app/src/test/kotlin/app/readylytics/health/domain/sync/DailySyncUseCaseTest.kt` (two
  `DailyRecomputeSupport(...)` sites: `:70`, `:365`; nine `computeAndPersistDailySummary(day, steps,
  any())` 3-arg verifications become 5-arg)
- `app/src/test/kotlin/app/readylytics/health/domain/sync/FirstSetupDummyIngestionFlowTest.kt`
  (`:225`)
- `app/src/test/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCaseTest.kt` (`:73`)
- `app/src/test/kotlin/app/readylytics/health/domain/sync/ResyncCheckpointResumeTest.kt` (`:68`)

**Modified — docs:**

- `internal-docs/DATA_FLOW.md` — §1.2 rows for `DailySyncUseCase`, `ResyncRangeUseCase`,
  `DailyRecomputeSupport`, `RoomTransactionRunner`.
- `internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md` — F7 section + §7 row 13 status.

**Created:**

- `app/src/test/kotlin/app/readylytics/health/domain/scoring/golden/WalkForwardTransactionEquivalenceTest.kt`
  — Robolectric A/B lock: per-day-commit vs one-transaction walk-forward over a real in-memory Room
  DB with the real scoring graph must produce byte-identical `daily_summaries` **and**
  `workout_records` rows.

---

## Reference: shared test helper used by several tasks

Several tasks need a `TransactionRunner` that records how many transactions were opened and
executes the block inline. The precedent already exists in the repo at
`core/database/src/test/kotlin/app/readylytics/health/data/local/PersistenceBatchingTest.kt:94-105`.
Each task below that needs it declares its own private copy inside its test class (these are
different Gradle modules; do **not** try to share one class across
`core/database/src/test` and `app/src/test`):

```kotlin
private class RecordingTransactionRunner : TransactionRunner {
    var transactionCount = 0
        private set
    var openDepth = 0
        private set
    var maxDepth = 0
        private set

    override suspend fun <R> runInTransaction(block: suspend () -> R): R {
        transactionCount++
        openDepth++
        maxDepth = maxOf(maxDepth, openDepth)
        try {
            return block()
        } finally {
            openDepth--
        }
    }
}
```

Import: `app.readylytics.health.domain.repository.TransactionRunner`.

---

## Task 1: Give the daily sync the walk-forward TRIMP/baseline contexts

`DailySyncUseCase.kt:142` calls the **3-arg** `recomputeDay(day, steps, prefs)`. Only
`ResyncRangeUseCase` (`:438-442`) uses the 5-arg overload with the PERF-002/WP-20/WP-22 contexts. So
every day of a foreground sync independently re-queries its own 84-day TRIMP lookback
(`WorkoutDao.getTrimpPoints` + `DailySummaryDao.getEverydayTrimpPoints`) and its own 56-day sleep
baseline window. Batching those to once per sync is the read-side twin of F7's write-side
coalescing, in the same loop. This task lands first so Task 2's new tests can be written against
the final 5-arg call shape.

**Files:**

- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailySyncUseCase.kt:126-142`
- Modify: `app/src/test/kotlin/app/readylytics/health/domain/sync/DailySyncUseCaseTest.kt`
- Modify: `app/src/test/kotlin/app/readylytics/health/domain/sync/FirstSetupDummyIngestionFlowTest.kt`
- Modify: `internal-docs/DATA_FLOW.md`

**Interfaces:**

- Consumes (already exist, unchanged):
  - `DailyRecomputeSupport.buildWalkForwardTrimpContext(startDate: LocalDate, endDate: LocalDate, zoneId: ZoneId): WalkForwardTrimpContext`
  - `DailyRecomputeSupport.buildWalkForwardBaselineContext(startDate: LocalDate, endDate: LocalDate, zoneId: ZoneId): WalkForwardBaselineContext`
  - `DailyRecomputeSupport.recomputeDay(day: LocalDate, steps: Long?, prefs: UserPreferences, trimpContext: WalkForwardTrimpContext, baselineContext: WalkForwardBaselineContext): Result<Unit>`
- Produces: `DailySyncUseCase` now invokes
  `ScoringRepository.computeAndPersistDailySummary(targetDate, steps, prefs, trimpContext, baselineContext)`
  — the 5-arg overload. Every test that verified the 3-arg overload must be updated.

---

- [ ] **Step 1: Write the failing test**

Add to `app/src/test/kotlin/app/readylytics/health/domain/sync/DailySyncUseCaseTest.kt`, after the
existing `sync shares one preferences snapshot across every recomputed day` test:

```kotlin
    @Test
    fun `sync builds one walk-forward context pair and shares it across every recomputed day`() =
        runTest {
            // PERF-002/WP-20/WP-22 shape, now on the daily path: each recomputed day must read the
            // TRIMP series and the RHR/HRV baseline window through ONE context built for the whole
            // window, not re-query its own 84-/56-day lookback per day.
            val capturedTrimp = mutableListOf<WalkForwardTrimpContext>()
            val capturedBaseline = mutableListOf<WalkForwardBaselineContext>()
            coEvery {
                scoringRepository.computeAndPersistDailySummary(
                    any(),
                    any(),
                    any(),
                    capture(capturedTrimp),
                    capture(capturedBaseline),
                )
            } returns Unit

            useCase.run(windowDays = 3, onProgress = null)

            assertEquals(3, capturedTrimp.size)
            assertEquals(1, capturedTrimp.distinctBy { System.identityHashCode(it) }.size)
            assertEquals(3, capturedBaseline.size)
            assertEquals(1, capturedBaseline.distinctBy { System.identityHashCode(it) }.size)
            coVerify(exactly = 1) {
                scoringRepository.fetchWalkForwardTrimpContext(any(), any(), any())
            }
            coVerify(exactly = 1) {
                scoringRepository.fetchWalkForwardBaselineContext(any(), any(), any())
            }
        }

    @Test
    fun `sync builds the walk-forward contexts over the widened recompute window`() =
        runTest {
            // The window widens to absorb a recent out-of-window HC change (see the
            // `absorbs recent out-of-window change inline` test); the contexts must cover the
            // widened range, not the nominal windowDays range, or the widened day reads an
            // incomplete series.
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(fixedClock.withZone(zoneId))
            val yesterday = today.minusDays(1)
            coEvery { changeSynchronizer.applyPendingChanges() } returns
                HealthChangeSyncOutcome(
                    affectedDates = setOf(yesterday),
                    requiresFullResync = false,
                )

            useCase.run(windowDays = 1, onProgress = null)

            coVerify(exactly = 1) {
                scoringRepository.fetchWalkForwardTrimpContext(yesterday, today, any())
            }
            coVerify(exactly = 1) {
                scoringRepository.fetchWalkForwardBaselineContext(yesterday, today, any())
            }
        }
```

Add these imports to the test file's import block:

```kotlin
import app.readylytics.health.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.domain.repository.WalkForwardTrimpContext
```

`scoringRepository` is a `mockk(relaxed = true)`, so `fetchWalkForward*Context` already return
relaxed stubs — no extra `coEvery` needed for them.

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*.DailySyncUseCaseTest"
```

Expected: FAIL. `capturedTrimp` is empty (size 0, not 3) because production still calls the 3-arg
overload, and `fetchWalkForwardTrimpContext` was never called.

- [ ] **Step 3: Write the implementation**

In `DailySyncUseCase.kt`, replace the block from the `stepsMap` line through the walk-forward loop
(currently `:125-158`) with:

```kotlin
                    val stepsDevice =
                        prefs.deviceByDataType[HealthDataType.STEPS.name]?.takeIf { it.isNotBlank() }
                    val totalDays = ChronoUnit.DAYS.between(oldestTargetDay, today).toInt() + 1
                    val stepsMap = stepCountFetcher.fetchWindow(today, totalDays, zoneId, stepsDevice)

                    // PERF-002/WP-20/WP-22 on the daily path: fetch the workout-only/everyday-HR
                    // TRIMP series and the RHR/HRV baseline sleep-session window ONCE for the whole
                    // walk-forward, instead of every recomputed day independently re-querying its
                    // own 84-/56-day lookback. Same batched-once shape as stepsMap above, and the
                    // same contexts ResyncRangeUseCase already builds. Built over the *widened*
                    // [oldestTargetDay, today] range so a day absorbed from outcome.affectedDates
                    // sees a complete series.
                    val trimpContext =
                        recomputeSupport.buildWalkForwardTrimpContext(oldestTargetDay, today, zoneId)
                    val baselineContext =
                        recomputeSupport.buildWalkForwardBaselineContext(oldestTargetDay, today, zoneId)

                    var processedDays = 0
                    onProgress?.invoke(ResyncPhase.RECOMPUTE, processedDays, totalDays)

                    var successCount = 0
                    var failureCount = 0

                    healthIngestionStore.clearFrozenBaselines(oldestTargetDay, today.plusDays(1), zoneId)

                    var dayToScore = oldestTargetDay
                    while (!dayToScore.isAfter(today)) {
                        ensureActive()
                        val steps = stepsMap[dayToScore]
                        val result =
                            recomputeSupport.recomputeDay(
                                dayToScore,
                                steps,
                                prefs,
                                trimpContext,
                                baselineContext,
                            )

                        when (result) {
                            is Result.Success -> {
                                successCount++
                                logD("DailySyncUseCase") { "Day $dayToScore: SUCCESS" }
                            }
                            is Result.Failure -> {
                                failureCount++
                                logI("DailySyncUseCase") { "Day $dayToScore: FAILED - ${result.reason}" }
                            }
                        }
                        processedDays++
                        onProgress?.invoke(ResyncPhase.RECOMPUTE, processedDays, totalDays)
                        dayToScore = dayToScore.plusDays(1)
                        yield()
                    }
```

Note the context build happens **before** `clearFrozenBaselines`, mirroring `ResyncRangeUseCase`'s
order (`:411-425`). Neither context reads a column that `clearFrozenBaselines` nulls
(`getEverydayTrimpPoints` reads `trimpEverydayHr`, which is not cleared), so the order is not
load-bearing — but keeping the two paths identical avoids a future reader having to re-derive that.

- [ ] **Step 4: Fix the existing 3-arg verifications in the same file**

In `DailySyncUseCaseTest.kt`, every `computeAndPersistDailySummary(<day>, <steps>, any())` becomes
`computeAndPersistDailySummary(<day>, <steps>, any(), any(), any())`, and every
`capture(...)`/`coJustRun` stub on the 3-arg overload moves to the 5-arg overload. The affected
tests are:

| Test | Current lines |
| --- | --- |
| `sync processes days in chronological order` | `:88-90` |
| `sync shares one preferences snapshot across every recomputed day` | `:108`, `:113-114` |
| `sync commits candidate change tokens after scoring succeeds` | `:131` |
| `sync clears frozen baselines for scoring window before recomputing days` | `:147-148` |
| `sync reconciles ingested overlap before scoring days` | `:182` |
| `daily sync keeps current-day range and requests historical resync for older changes` | `:253` |
| `daily sync absorbs recent out-of-window change inline without historical resync` | `:284` |
| `daily sync absorbs change exactly at the inline floor inline` | `:312` |
| `sync resolves today from the injected clock, not the real system clock` | `:374-380` |

Concretely, for example `:88-90` becomes:

```kotlin
            coVerifyOrder {
                scoringRepository.computeAndPersistDailySummary(day0, 0L, any(), any(), any())
                scoringRepository.computeAndPersistDailySummary(day1, 0L, any(), any(), any())
                scoringRepository.computeAndPersistDailySummary(day2, 0L, any(), any(), any())
            }
```

and `:107-109` becomes:

```kotlin
            coEvery {
                scoringRepository.computeAndPersistDailySummary(
                    any(),
                    any(),
                    capture(capturedPrefs),
                    any(),
                    any(),
                )
            } returns Unit
```

Then check `FirstSetupDummyIngestionFlowTest.kt` for the same pattern:

```bash
rtk grep -n "computeAndPersistDailySummary" app/src/test/kotlin/app/readylytics/health/domain/sync/FirstSetupDummyIngestionFlowTest.kt
```

Update every 3-arg reference there to the 5-arg overload the same way. Leave
`ResyncRangeUseCaseTest.kt` and `ResyncCheckpointResumeTest.kt` alone — they already assert the
5-arg overload.

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew ktlintFormat && ./gradlew testDebugUnitTest
```

Expected: PASS, including the two new tests and every updated verification.

- [ ] **Step 6: Update `internal-docs/DATA_FLOW.md`**

In §1.2's component table, edit the **`DailySyncUseCase`** row's responsibility cell: after the
existing description of the walk-forward, append one sentence:

> Before the walk-forward it builds one `WalkForwardTrimpContext` + `WalkForwardBaselineContext`
> over the widened `[oldestTargetDay, today]` range (`DailyRecomputeSupport.buildWalkForward*`) and
> passes both to every recomputed day, so the 84-day TRIMP series and 56-day baseline sleep window
> are fetched once per sync rather than once per synced day — the same PERF-002/WP-20/WP-22 shape
> `ResyncRangeUseCase` already uses.

In the **`DailyRecomputeSupport`** row, make sure the sentence describing the 5-arg
`recomputeDay(...)` overload no longer implies it is resync-only — both sync paths now use it.

- [ ] **Step 7: Commit**

```bash
rtk git add core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailySyncUseCase.kt \
  app/src/test/kotlin/app/readylytics/health/domain/sync/DailySyncUseCaseTest.kt \
  app/src/test/kotlin/app/readylytics/health/domain/sync/FirstSetupDummyIngestionFlowTest.kt \
  internal-docs/DATA_FLOW.md && \
rtk git commit -m "perf: batch the daily sync's walk-forward TRIMP/baseline lookups (F7)

DailySyncUseCase called the 3-arg recomputeDay, so each day of a foreground
sync re-queried its own 84-day TRIMP series and 56-day baseline sleep window.
Build both contexts once over the widened recompute range and pass them to
every day, matching ResyncRangeUseCase's existing PERF-002/WP-20/WP-22 shape.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: One Room transaction for the daily sync's whole walk-forward

**Files:**

- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailyRecomputeSupport.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailySyncUseCase.kt`
- Modify: `app/src/test/kotlin/app/readylytics/health/domain/sync/DailySyncUseCaseTest.kt`
- Modify: `app/src/test/kotlin/app/readylytics/health/domain/sync/FirstSetupDummyIngestionFlowTest.kt`
- Modify: `app/src/test/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCaseTest.kt`
- Modify: `app/src/test/kotlin/app/readylytics/health/domain/sync/ResyncCheckpointResumeTest.kt`
- Modify: `internal-docs/DATA_FLOW.md`

**Interfaces:**

- Consumes: `TransactionRunner.runInTransaction(block: suspend () -> R): R` from
  `app.readylytics.health.domain.repository.TransactionRunner` (bound to `RoomTransactionRunner` in
  `app/src/main/kotlin/app/readylytics/health/di/DatabaseModule.kt:38-39`; already injected into
  `core/healthconnect` by `HealthChangeSynchronizerImpl.kt:49`, so no Hilt wiring is needed).
- Produces:
  - `DailyRecomputeSupport` constructor becomes
    `DailyRecomputeSupport(scoringRepository: ScoringRepository, settingsRepo: SettingsRepository, transactionRunner: TransactionRunner)`.
    Every test constructing it must pass a third argument.
  - `suspend fun <R> DailyRecomputeSupport.inRecomputeTransaction(block: suspend () -> R): R` —
    used by Task 3 as well.

---

- [ ] **Step 1: Write the failing test**

Add to `DailySyncUseCaseTest.kt`. First add a field so the recording runner is reachable from every
test, and rewire `setup()`:

```kotlin
    private val transactionRunner = RecordingTransactionRunner()
```

and in `setup()` change the `recomputeSupport` argument to:

```kotlin
                recomputeSupport = DailyRecomputeSupport(scoringRepository, settingsRepo, transactionRunner),
```

Then add the tests:

```kotlin
    @Test
    fun `sync recomputes the whole window inside exactly one transaction`() =
        runTest {
            // F7: Room invalidates per table per transaction. One transaction for the whole
            // walk-forward means every observed daily_summaries/workout_records query in the UI
            // re-runs once per sync instead of once per synced day.
            useCase.run(windowDays = 8, onProgress = null)

            assertEquals(1, transactionRunner.transactionCount)
            assertEquals(1, transactionRunner.maxDepth)
        }

    @Test
    fun `sync clears frozen baselines and scores every day inside the transaction`() =
        runTest {
            // The frozen-baseline clear is a daily_summaries write too; leaving it outside would
            // cost a second invalidation round per sync.
            val insideTransaction = mutableListOf<String>()
            coEvery { healthIngestionStore.clearFrozenBaselines(any(), any(), any()) } answers {
                insideTransaction += "clear:${transactionRunner.openDepth}"
            }
            coEvery {
                scoringRepository.computeAndPersistDailySummary(any(), any(), any(), any(), any())
            } answers {
                insideTransaction += "score:${transactionRunner.openDepth}"
            }

            useCase.run(windowDays = 3, onProgress = null)

            assertEquals(
                listOf("clear:1", "score:1", "score:1", "score:1"),
                insideTransaction,
            )
        }

    @Test
    fun `sync opens no transaction around the Health Connect window read`() =
        runTest {
            // Holding a write transaction across HC IPC would pin the transaction thread for the
            // duration of a remote read. Ingestion, reconcile and the step fetch must all be done
            // before the transaction opens.
            var depthDuringHcRead = -1
            coEvery { hcRepo.readSteps(any(), any()) } answers {
                depthDuringHcRead = transactionRunner.openDepth
                0L
            }

            useCase.run(windowDays = 2, onProgress = null)

            assertEquals(0, depthDuringHcRead)
        }
```

Add the `RecordingTransactionRunner` class (copy from the "Reference" section above) as a private
nested class at the bottom of `DailySyncUseCaseTest`, plus the import
`import app.readylytics.health.domain.repository.TransactionRunner`.

Also update the second `DailyRecomputeSupport(...)` construction at `:365` (inside
`sync resolves today from the injected clock, not the real system clock`) to pass
`transactionRunner`.

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*.DailySyncUseCaseTest"
```

Expected: FAIL to **compile** — `DailyRecomputeSupport` takes two arguments, not three
(`No value passed for parameter 'transactionRunner'` / `Too many arguments`).

- [ ] **Step 3: Add the transaction helper to `DailyRecomputeSupport`**

In `DailyRecomputeSupport.kt`, add the import and constructor parameter:

```kotlin
import app.readylytics.health.domain.repository.TransactionRunner
```

```kotlin
@Singleton
class DailyRecomputeSupport
    @Inject
    constructor(
        private val scoringRepository: ScoringRepository,
        private val settingsRepo: SettingsRepository,
        private val transactionRunner: TransactionRunner,
    ) {
```

and add this method directly above `refreshAutoMaxHr`:

```kotlin
        /**
         * F7: runs a whole walk-forward's worth of [recomputeDay] calls inside ONE Room
         * transaction.
         *
         * Room's invalidation tracker fires per table per *transaction*, so N per-day
         * `daily_summaries` upserts (plus the per-day `workout_records` modelTrimp writes
         * `ScoringRepositoryImpl` issues) collapse into a single invalidation round instead of one
         * per day. Every observed DAO `Flow` in the UI — Dashboard's today-summary and 7-day RAS
         * window, Vitals/Sleep/Workouts `observeSince` — therefore re-runs its `SELECT` + mapping
         * once per sync rather than once per synced day, while the user is looking at the screen.
         *
         * Two properties make this safe and are load-bearing:
         * - **Reads see the transaction's own uncommitted writes.** The walk-forward depends on
         *   this: day N sums days N-1..N-6 (`ScoringRepositoryImpl.sumRasLastSixDays`) and reads
         *   day N-1 (`ComputeSleepMetricsUseCase`). Deferring the writes to after the loop instead
         *   would make those reads see stale rows and change the scores.
         * - **The dispatcher switch inside `ScoringRepositoryImpl.computeDailySummary` stays in the
         *   transaction.** Room's `TransactionElement` propagates across `withContext(...)` and
         *   suspend DAO calls resolve it to dispatch back onto the transaction thread
         *   (`RoomDatabase.withTransactionContext` / `DBUtil.getCoroutineContext`).
         *
         * Never perform Health Connect I/O inside [block] — that would pin the transaction thread
         * across a remote IPC read. Cancellation inside [block] rolls the whole unit back; that is
         * intended, because recompute is idempotent and the next run redoes the same range.
         */
        suspend fun <R> inRecomputeTransaction(block: suspend () -> R): R = transactionRunner.runInTransaction(block)
```

- [ ] **Step 4: Wrap the daily walk-forward**

In `DailySyncUseCase.kt`, wrap the `clearFrozenBaselines` call and the `while` loop (the block
Task 1 rewrote) in `recomputeSupport.inRecomputeTransaction { ... }`:

```kotlin
                    var processedDays = 0
                    onProgress?.invoke(ResyncPhase.RECOMPUTE, processedDays, totalDays)

                    var successCount = 0
                    var failureCount = 0

                    // F7: one transaction for the frozen-baseline clear plus the whole walk-forward,
                    // so a routine sync produces a single daily_summaries/workout_records
                    // invalidation round instead of one per synced day. Everything that touches
                    // Health Connect (ingestWindow, reconcile, fetchWindow) has already completed
                    // above -- keep it that way. A per-day Result.Failure does not abort the
                    // transaction: recomputeDay catches and returns rather than rethrowing, so the
                    // existing log-and-continue + SYNC_PARTIAL_FAILURE semantics are unchanged.
                    // Cancellation does roll the window back, which is fine: the next sync redoes
                    // the same idempotent range.
                    recomputeSupport.inRecomputeTransaction {
                        healthIngestionStore.clearFrozenBaselines(oldestTargetDay, today.plusDays(1), zoneId)

                        var dayToScore = oldestTargetDay
                        while (!dayToScore.isAfter(today)) {
                            ensureActive()
                            val steps = stepsMap[dayToScore]
                            val result =
                                recomputeSupport.recomputeDay(
                                    dayToScore,
                                    steps,
                                    prefs,
                                    trimpContext,
                                    baselineContext,
                                )

                            when (result) {
                                is Result.Success -> {
                                    successCount++
                                    logD("DailySyncUseCase") { "Day $dayToScore: SUCCESS" }
                                }
                                is Result.Failure -> {
                                    failureCount++
                                    logI("DailySyncUseCase") { "Day $dayToScore: FAILED - ${result.reason}" }
                                }
                            }
                            processedDays++
                            onProgress?.invoke(ResyncPhase.RECOMPUTE, processedDays, totalDays)
                            dayToScore = dayToScore.plusDays(1)
                            yield()
                        }
                    }
```

`successCount`, `failureCount` and `processedDays` are local `var`s captured by the lambda — that
is legal Kotlin and their values survive the block. Everything after the loop
(`commitTokens`, `updateLastSyncTimestamp`, the result branches) stays outside the transaction and
is unchanged.

- [ ] **Step 5: Fix the remaining `DailyRecomputeSupport(...)` call sites**

Three test files still construct it with two arguments and will not compile:

- `app/src/test/kotlin/app/readylytics/health/domain/sync/FirstSetupDummyIngestionFlowTest.kt:225`
- `app/src/test/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCaseTest.kt:73`
- `app/src/test/kotlin/app/readylytics/health/domain/sync/ResyncCheckpointResumeTest.kt:68`

In each, add a `RecordingTransactionRunner` (copy from the "Reference" section into that test class)
and pass it as the third argument, e.g.:

```kotlin
                recomputeSupport = DailyRecomputeSupport(scoringRepository, settingsRepo, transactionRunner),
```

Do not add assertions on it in `FirstSetupDummyIngestionFlowTest` — it just needs to compile.
`ResyncRangeUseCaseTest` / `ResyncCheckpointResumeTest` will assert on it in Task 3.

- [ ] **Step 6: Run tests to verify they pass**

```bash
./gradlew ktlintFormat && ./gradlew testDebugUnitTest
```

Expected: PASS. If `sync clears frozen baselines and scores every day inside the transaction` fails
with `clear:0`, the `clearFrozenBaselines` call was left outside the lambda — move it in.

- [ ] **Step 7: Update `internal-docs/DATA_FLOW.md`**

In §1.2's component table:

- **`DailySyncUseCase`** row — append:

  > The frozen-baseline clear and the entire walk-forward run inside one
  > `DailyRecomputeSupport.inRecomputeTransaction { }` (F7), so a routine sync produces a single
  > Room invalidation round on `daily_summaries`/`workout_records` instead of one per synced day.
  > Health Connect I/O (window ingest, reconcile, step fetch) always completes before that
  > transaction opens. Cancellation rolls the window back; the next sync redoes the same idempotent
  > range.

- **`DailyRecomputeSupport`** row — append:

  > Also owns `inRecomputeTransaction { }`, the single place either sync path opens a recompute
  > transaction (F7). Reads inside it observe the transaction's own uncommitted writes, which the
  > walk-forward requires (day N sums days N-1..N-6 and reads day N-1).

- **`RoomTransactionRunner`** row — append:

  > Also wraps the sync/resync walk-forward recompute via `DailyRecomputeSupport`.

- [ ] **Step 8: Commit**

```bash
rtk git add core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailyRecomputeSupport.kt \
  core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailySyncUseCase.kt \
  app/src/test/kotlin/app/readylytics/health/domain/sync/DailySyncUseCaseTest.kt \
  app/src/test/kotlin/app/readylytics/health/domain/sync/FirstSetupDummyIngestionFlowTest.kt \
  app/src/test/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCaseTest.kt \
  app/src/test/kotlin/app/readylytics/health/domain/sync/ResyncCheckpointResumeTest.kt \
  internal-docs/DATA_FLOW.md && \
rtk git commit -m "perf: run the daily sync walk-forward in one Room transaction (F7)

Room invalidates per table per transaction, so a 7-8 day foreground sync fired
7-8 daily_summaries invalidation rounds (plus one per day on workout_records),
each re-running every observed query in the UI while the user was looking at it.
Wrap the frozen-baseline clear and the whole walk-forward in a single
transaction via a new DailyRecomputeSupport.inRecomputeTransaction helper.

Reads inside the transaction see its own uncommitted writes, which the
walk-forward depends on (sumRasLastSixDays reads days N-1..N-6;
ComputeSleepMetricsUseCase reads day N-1), so scores are unchanged.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: Chunk the historical resync's recompute into 30-day transactions

A resync can span 3650 days. One transaction for the whole range would hold the write lock for the
entire multi-minute pass and lose everything on a kill. Chunk it at
`RECOMPUTE_CHECKPOINT_INTERVAL_DAYS` (already 30) so the transaction boundary and the checkpoint
boundary are the same thing, and checkpoint only after the chunk has committed.

**Files:**

- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCase.kt:426-468`
- Modify: `app/src/test/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCaseTest.kt`
- Modify: `app/src/test/kotlin/app/readylytics/health/domain/sync/ResyncCheckpointResumeTest.kt`
- Modify: `internal-docs/DATA_FLOW.md`

**Interfaces:**

- Consumes: `DailyRecomputeSupport.inRecomputeTransaction(block)` from Task 2.
- Produces: no new public API. `RECOMPUTE_CHECKPOINT_INTERVAL_DAYS` keeps its name and value (30)
  and now also defines the transaction chunk size.

---

- [ ] **Step 1: Write the failing test**

Add to `app/src/test/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCaseTest.kt`:

```kotlin
    @Test
    fun `recompute opens one transaction per thirty-day chunk`() =
        runTest {
            // 65 days => chunks of 30 + 30 + 5. One transaction each: bounded enough that a kill
            // loses at most one chunk, coalesced enough that the resync doesn't fire 65 separate
            // daily_summaries invalidation rounds at the UI.
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = startDate.plusDays(64)

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            assertEquals(3, transactionRunner.transactionCount)
            assertEquals(1, transactionRunner.maxDepth)
        }

    @Test
    fun `recompute checkpoints only after a chunk transaction commits`() =
        runTest {
            // A checkpoint saved inside the transaction would record days as done that a rollback
            // then discarded, so a resumed run would skip them.
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = startDate.plusDays(64)
            val depthAtCheckpoint = mutableListOf<Int>()
            checkpointStore.onSave = { depthAtCheckpoint += transactionRunner.openDepth }

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            assertTrue(depthAtCheckpoint.all { it == 0 })
        }

    @Test
    fun `a failing day rolls back only its own chunk and leaves the prior checkpoint`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = startDate.plusDays(64)
            // Day 35 is in chunk 2 (days 31..60); chunk 1 (days 1..30) must already be committed
            // and checkpointed at nextDate = startDate + 30.
            coEvery {
                scoringRepository.computeAndPersistDailySummary(
                    startDate.plusDays(34),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } throws IllegalStateException("scoring failed")

            val result =
                useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            assertEquals(false, result.isSuccess)
            assertEquals(2, transactionRunner.transactionCount)
            assertEquals(ResyncPhase.RECOMPUTE, checkpointStore.value?.phase)
            assertEquals(startDate.plusDays(30), checkpointStore.value?.nextDate)
            coVerify(exactly = 0) { changeSynchronizer.commitTokens(any()) }
        }
```

Wire the supporting fields into the test class:

- Add `private val transactionRunner = RecordingTransactionRunner()` and pass it as the third
  `DailyRecomputeSupport` argument at `:73` (Task 2 already did this — reuse the same field).
- The fake checkpoint store in this file needs an `onSave` hook. Open the file, find the fake
  implementing `ResyncCheckpointStore`, and add:

  ```kotlin
      var onSave: (() -> Unit)? = null
  ```

  then call `onSave?.invoke()` as the first statement of its `save(...)` override. Do not change
  any other behaviour of the fake.

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "*.ResyncRangeUseCaseTest"
```

Expected: FAIL — `transactionCount` is `0` (the resync loop opens no transaction at all yet).

- [ ] **Step 3: Write the implementation**

In `ResyncRangeUseCase.kt`, replace the recompute loop (`:426-468`, from
`onProgress?.invoke(ResyncPhase.RECOMPUTE, completedDays, totalDays)` through the closing `}` of
the `while (!day.isAfter(endDate))` loop) with:

```kotlin
                    onProgress?.invoke(ResyncPhase.RECOMPUTE, completedDays, totalDays)
                    // F7: recompute in RECOMPUTE_CHECKPOINT_INTERVAL_DAYS-day units, each unit one
                    // Room transaction. Room invalidates per table per transaction, so a 10-year
                    // resync fires one daily_summaries/workout_records invalidation round per chunk
                    // instead of one per day -- while its foreground-service notification is up and
                    // the user may be on a chart screen. The chunk size doubles as the checkpoint
                    // interval so transaction boundary == resume boundary: a kill or a rollback
                    // discards at most one chunk, and the checkpoint (saved only AFTER the chunk
                    // commits) still points at the first day of that chunk, so the resumed run
                    // idempotently redoes exactly what was lost.
                    var chunkStartDay = recomputeStartDate
                    var recomputedDays = completedDays
                    while (!chunkStartDay.isAfter(endDate)) {
                        val chunkEndDay =
                            minOf(
                                chunkStartDay.plusDays((RECOMPUTE_CHECKPOINT_INTERVAL_DAYS - 1).toLong()),
                                endDate,
                            )
                        val daysBeforeChunk = recomputedDays
                        val chunkFailure =
                            recomputeSupport.inRecomputeTransaction {
                                var day = chunkStartDay
                                var failure: Result.Failure? = null
                                var daysDone = daysBeforeChunk
                                while (!day.isAfter(chunkEndDay)) {
                                    ensureActive()
                                    val stepsForDay =
                                        when {
                                            skipIngestAndPrune -> null
                                            stepsDevice != null -> stepsMap[day] ?: 0L
                                            else -> stepsMap[day]
                                        }
                                    val dayResult =
                                        if (trimpContext != null && baselineContext != null) {
                                            recomputeSupport.recomputeDay(
                                                day,
                                                stepsForDay,
                                                prefs,
                                                trimpContext,
                                                baselineContext,
                                            )
                                        } else {
                                            recomputeSupport.recomputeDay(day, stepsForDay, prefs)
                                        }
                                    if (dayResult is Result.Failure) {
                                        logD(TELEMETRY_TAG) { "[RECOMPUTE] Failed at day $day: ${dayResult.reason}" }
                                        failure = dayResult
                                        break
                                    }
                                    daysDone++
                                    onProgress?.invoke(ResyncPhase.RECOMPUTE, daysDone, totalDays)
                                    day = day.plusDays(1)
                                    yield()
                                }
                                failure
                            }
                        if (chunkFailure != null) {
                            // The chunk rolled back, so no checkpoint advance: the stored checkpoint
                            // still starts at this chunk's first day and a retry redoes it whole.
                            return@withContext chunkFailure
                        }
                        recomputedDays =
                            ChronoUnit
                                .DAYS
                                .between(startDate, chunkEndDay.plusDays(1))
                                .toInt()
                                .coerceIn(0, totalDays)
                        checkpointStore.save(
                            ResyncCheckpoint(
                                startDate = startDate,
                                endDate = endDate,
                                phase = ResyncPhase.RECOMPUTE,
                                nextDate = chunkEndDay.plusDays(1),
                                selectionHash = selectionHash,
                                baselineChangeTokens = baselineChangeTokens,
                            ),
                        )
                        chunkStartDay = chunkEndDay.plusDays(1)
                    }
```

Two behaviours deliberately preserved:

- A `Result.Failure` still aborts the whole resync and returns that failure (previously
  `return@withContext dayResult` from inside the day loop; now the failure is carried out of the
  transaction lambda first, because a non-local return through a suspend lambda is not allowed).
- The final chunk always ends at `endDate`, so completion is still durably checkpointed before
  `checkpointStore.clear()` runs below — the old `isLastDay` special case is now implicit.

Also update the KDoc on `RECOMPUTE_CHECKPOINT_INTERVAL_DAYS` in the companion object:

```kotlin
            // PERF-002/WP-20 + F7: RECOMPUTE-phase transaction *and* checkpoint granularity. Each
            // unit of this many days is one Room transaction, checkpointed only after it commits,
            // so transaction rollback and resume boundaries coincide. Recompute is idempotent, so
            // redoing at most one unit after a kill only repeats already-correct work.
            private const val RECOMPUTE_CHECKPOINT_INTERVAL_DAYS = 30
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew ktlintFormat && ./gradlew testDebugUnitTest
```

Expected: PASS, including the pre-existing `ResyncCheckpointResumeTest` cases. Those all use ranges
of ≤3 days (one chunk), so their checkpoint expectations are unchanged:

- `resyncRange keeps checkpoint and tokens when recompute fails` — the single chunk rolls back, no
  checkpoint advance, `nextDate` stays `startDate`. ✅
- `recompute resumes with the same scoring preferences without Health Connect tokens` — the failing
  day is in the only chunk, so the pre-loop RECONCILE→RECOMPUTE save at `nextDate = startDate`
  stands and the resumed run redoes `startDate` too. Its existing comment already describes exactly
  this; extend it with "and F7's chunk rollback makes it structural rather than incidental."

If a `ResyncCheckpointResumeTest` case *does* fail on `nextDate`, do not weaken the assertion —
re-read the chunk-boundary arithmetic above; `nextDate` must always be `chunkEndDay.plusDays(1)`.

- [ ] **Step 5: Check the file-size budget**

```bash
rtk grep -c "" core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCase.kt
```

Expected: still under the 800-line hard limit (it was 556). It is already over the 400-line soft
target; this task adds ~20 lines and does not make that materially worse, so do **not** open a
refactor here — note it and move on.

- [ ] **Step 6: Update `internal-docs/DATA_FLOW.md`**

In §1.2's **`ResyncRangeUseCase`** row, append:

> Its RECOMPUTE phase runs in 30-day units (`RECOMPUTE_CHECKPOINT_INTERVAL_DAYS`), each unit one
> Room transaction via `DailyRecomputeSupport.inRecomputeTransaction { }` (F7), checkpointed only
> after that transaction commits. Transaction-rollback and checkpoint-resume boundaries therefore
> coincide: a kill, cancellation, or per-day failure discards at most one unit and the stored
> checkpoint still points at that unit's first day, so the retry idempotently redoes exactly what
> was lost.

- [ ] **Step 7: Commit**

```bash
rtk git add core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCase.kt \
  app/src/test/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCaseTest.kt \
  app/src/test/kotlin/app/readylytics/health/domain/sync/ResyncCheckpointResumeTest.kt \
  internal-docs/DATA_FLOW.md && \
rtk git commit -m "perf: chunk the historical resync recompute into 30-day transactions (F7)

The resync walk-forward committed (and invalidated) once per recomputed day,
so a multi-year resync fired thousands of invalidation rounds at the UI while
its foreground service ran. Recompute in RECOMPUTE_CHECKPOINT_INTERVAL_DAYS
units, one Room transaction each, checkpointed only after the unit commits so
rollback and resume boundaries coincide.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: Robolectric A/B lock — transaction-wrapped output is byte-identical

Tasks 2 and 3 assert *structure* (one transaction, right boundaries) with mocks. This task asserts
*equivalence* against a real Room database and the real scoring graph: the same seeded data,
recomputed per-day-commit vs inside one transaction, must produce identical `daily_summaries` and
`workout_records` rows. This is the acceptance criterion that would catch the read-after-write
mistake the naive buffering design would have introduced.

The existing `GoldenFixtureWalkForwardTest` cannot serve as this gate: its checked-in golden JSON is
documented as **known-stale** on two counts (WP-10 TRIMP unification, WP-11 stage-less-night
fallback). A self-contained A/B comparison needs no golden file and cannot go stale.

**Files:**

- Create: `app/src/test/kotlin/app/readylytics/health/domain/scoring/golden/WalkForwardTransactionEquivalenceTest.kt`

**Interfaces:**

- Consumes (all already exist in the same package/module):
  - `GoldenFixtureDataBuilder(zoneId: ZoneId, seed: Long = 20260101L)` with
    `suspend fun build(db: HealthDatabase, startDate: LocalDate, endDate: LocalDate): BuildResult`,
    where `BuildResult.stepsByDate: Map<LocalDate, Long>`. **`startDate` must be at least 95 days
    before `endDate`.**
  - `FakeSettingsRepository(prefs)` and `FakeEncryptionManager()` from `GoldenFixtureTestFakes.kt`.
  - `RoomTransactionRunner(db)` from `core/database`.
  - `DailyRecomputeSupport(scoringRepository, settingsRepo, transactionRunner)` from Task 2.
- Produces: nothing consumed by later tasks.

---

- [ ] **Step 1: Write the test**

Create `app/src/test/kotlin/app/readylytics/health/domain/scoring/golden/WalkForwardTransactionEquivalenceTest.kt`:

```kotlin
package app.readylytics.health.domain.scoring.golden

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.local.RoomTransactionRunner
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.data.repository.ScoringHistoryRepositoryImpl
import app.readylytics.health.data.repository.ScoringRepositoryImpl
import app.readylytics.health.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.domain.scoring.BaselineComputer
import app.readylytics.health.domain.scoring.BuildLoadSeriesUseCase
import app.readylytics.health.domain.scoring.CompositeScoringCalculator
import app.readylytics.health.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.domain.scoring.ScoringConfigFactory
import app.readylytics.health.domain.scoring.sleep.CurrentNightHrvResolver
import app.readylytics.health.domain.scoring.sleep.HrCoverageValidator
import app.readylytics.health.domain.scoring.sleep.SleepNadirAnalyzer
import app.readylytics.health.domain.scoring.sleep.SleepPercentileRhrCalculator
import app.readylytics.health.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.domain.scoring.strategies.RasScoringStrategy
import app.readylytics.health.domain.scoring.strategies.SleepScoringStrategy
import app.readylytics.health.domain.sync.DailyRecomputeSupport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

/**
 * F7 equivalence lock. Wrapping the walk-forward in one Room transaction must not change a single
 * persisted value.
 *
 * This is not a redundant restatement of the mock-level transaction-count tests: it is the only
 * check that exercises the walk-forward's read-after-write dependencies for real. Day N's
 * `totalRas*` sums days N-1..N-6 (`ScoringRepositoryImpl.sumRasLastSixDays`) and
 * `ComputeSleepMetricsUseCase` reads day N-1, so any implementation that defers the writes past
 * the days that read them -- e.g. buffering the summaries and calling `upsertAll` after the loop --
 * silently produces different scores. Reads inside a transaction see that transaction's own
 * uncommitted writes, so the transaction-wrapped run must match the per-day-commit run exactly.
 *
 * `workout_records` is compared too: `ScoringRepositoryImpl` writes `modelTrimp` per recomputed day,
 * and inside one transaction those N writes coalesce into a single invalidation for free -- this
 * asserts the coalescing is invisible in the data.
 *
 * Deliberately NOT built on `GoldenFixtureWalkForwardTest`'s checked-in JSON, which is documented
 * as known-stale (WP-10, WP-11). An A/B comparison of two runs cannot go stale.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class WalkForwardTransactionEquivalenceTest {
    private val zoneId: ZoneId = ZoneId.of("Europe/Berlin")

    // GoldenFixtureDataBuilder requires startDate to be at least 95 days before endDate (it places
    // its scenario days at fixed offsets up to +94). 120 days keeps the run short while still
    // covering the stage-less night, the biphasic night, and the multi-day gap.
    private val startDate: LocalDate = LocalDate.of(2024, 6, 1)
    private val endDate: LocalDate = startDate.plusDays(119)

    @Test
    fun `transaction-wrapped walk-forward produces identical rows to per-day commits`() =
        runBlocking {
            val perDay = runWalkForward(wrapInTransaction = false)
            val batched = runWalkForward(wrapInTransaction = true)

            assertEquals(perDay.summaries, batched.summaries)
            assertEquals(perDay.workouts, batched.workouts)
            // Guard against both runs silently producing nothing.
            assertEquals(120, perDay.summaries.size)
        }

    private data class RunOutput(
        val summaries: List<String>,
        val workouts: List<String>,
    )

    private suspend fun runWalkForward(wrapInTransaction: Boolean): RunOutput {
        val db =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).build()
        try {
            val prefs =
                UserPreferences(
                    scoringZoneId = zoneId.id,
                    installDate =
                        startDate
                            .minusDays(1)
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    age = 35,
                )
            // Default seed => both runs get byte-identical seeded data.
            val buildResult = GoldenFixtureDataBuilder(zoneId).build(db, startDate, endDate)

            val settingsRepo = FakeSettingsRepository(prefs)
            val scoringHistoryRepository =
                ScoringHistoryRepositoryImpl(
                    db.heartRateDao(),
                    db.hrvDao(),
                    db.sleepSessionDao(),
                    db.dailySummaryDao(),
                )
            val loadScoringStrategy = LoadScoringStrategy()
            val scoringCalculator =
                CompositeScoringCalculator(
                    sleepStrategy = SleepScoringStrategy(loadScoringStrategy),
                    rasStrategy = RasScoringStrategy(),
                    loadStrategy = loadScoringStrategy,
                )
            val baselineComputer = BaselineComputer(scoringHistoryRepository, scoringCalculator)
            val scoringConfigFactory = ScoringConfigFactory()
            val scoringRepository =
                ScoringRepositoryImpl(
                    workoutDao = db.workoutDao(),
                    sleepSessionDao = db.sleepSessionDao(),
                    dailySummaryDao = db.dailySummaryDao(),
                    settingsRepo = settingsRepo,
                    scoringCalculator = scoringCalculator,
                    baselineComputer = baselineComputer,
                    buildLoadSeriesUseCase = BuildLoadSeriesUseCase(scoringCalculator),
                    assembleEverydayLoadInputUseCase = AssembleEverydayLoadInputUseCase(),
                    computeSleepMetricsUseCase =
                        ComputeSleepMetricsUseCase(
                            baselineComputer = baselineComputer,
                            scoringHistoryRepository = scoringHistoryRepository,
                            scoringCalculator = scoringCalculator,
                            scoringConfigFactory = scoringConfigFactory,
                            encryptionManager = FakeEncryptionManager(),
                            hrvResolver = CurrentNightHrvResolver(scoringHistoryRepository),
                            sleepPercentileRhrCalculator =
                                SleepPercentileRhrCalculator(scoringHistoryRepository),
                            nadirAnalyzer = SleepNadirAnalyzer(scoringCalculator),
                            coverageValidator = HrCoverageValidator(),
                        ),
                    scoringConfigFactory = scoringConfigFactory,
                    computeWorkoutTrimpUseCase = ComputeWorkoutTrimpUseCase(),
                    heartRateDao = db.heartRateDao(),
                    weightRecordDao = db.weightRecordDao(),
                    bodyFatRecordDao = db.bodyFatRecordDao(),
                    bloodPressureRecordDao = db.bloodPressureRecordDao(),
                    oxygenSaturationRecordDao = db.oxygenSaturationRecordDao(),
                    sleepPercentileRhrCalculator = SleepPercentileRhrCalculator(scoringHistoryRepository),
                    scoringHistoryRepository = scoringHistoryRepository,
                    defaultDispatcher = UnconfinedTestDispatcher(),
                )
            val recomputeSupport =
                DailyRecomputeSupport(scoringRepository, settingsRepo, RoomTransactionRunner(db))

            val walkForward: suspend () -> Unit = {
                val trimpContext =
                    recomputeSupport.buildWalkForwardTrimpContext(startDate, endDate, zoneId)
                val baselineContext =
                    recomputeSupport.buildWalkForwardBaselineContext(startDate, endDate, zoneId)
                var day = startDate
                while (!day.isAfter(endDate)) {
                    recomputeSupport.recomputeDay(
                        day,
                        buildResult.stepsByDate[day],
                        prefs,
                        trimpContext,
                        baselineContext,
                    )
                    day = day.plusDays(1)
                }
            }

            if (wrapInTransaction) {
                recomputeSupport.inRecomputeTransaction { walkForward() }
            } else {
                walkForward()
            }

            return RunOutput(
                summaries =
                    db
                        .dailySummaryDao()
                        .getAllSummaries()
                        .sortedBy { it.dateMidnightMs }
                        .map { it.toString() },
                workouts =
                    db
                        .workoutDao()
                        .getWorkoutsInRange(
                            startDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                            endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
                        ).sortedBy { it.startTime }
                        .map { it.toString() },
            )
        } finally {
            db.close()
        }
    }
}
```

Entity `toString()` is used for comparison because `DailySummaryEntity` / `WorkoutRecordEntity` are
Kotlin data classes — their generated `toString()` includes every property, so a mismatch in any
column fails the assertion with a readable diff.

- [ ] **Step 2: Run the test**

```bash
./gradlew :app:testDebugUnitTest --tests "*.WalkForwardTransactionEquivalenceTest"
```

Expected: PASS.

**If it hangs or deadlocks:** `Room.withTransaction` takes a thread from the database's
`transactionExecutor` and runs a `runBlocking` event loop on it. That is why this test uses
`runBlocking` rather than `runTest` — `runTest`'s virtual-time scheduler and a real blocking
transaction thread can deadlock waiting on each other. If it still hangs, verify
`defaultDispatcher = UnconfinedTestDispatcher()` is what you passed (an unconfined dispatcher
resumes on the calling thread, avoiding a cross-thread handoff back into the transaction). Do not
"fix" a hang by removing the transaction from the test — that would delete the thing under test.

**If the assertion fails:** the diff is the answer. A mismatch confined to `totalRasWorkoutOnly` /
`totalRasEverydayHr` points at the `sumRasLastSixDays` read-after-write path; a mismatch in sleep
columns points at `ComputeSleepMetricsUseCase`'s yesterday read. Either way the implementation, not
the test, is wrong.

- [ ] **Step 3: Index the new file and commit**

```bash
codegraph index
```

```bash
rtk git add app/src/test/kotlin/app/readylytics/health/domain/scoring/golden/WalkForwardTransactionEquivalenceTest.kt && \
rtk git commit -m "test: lock F7 transaction batching to byte-identical walk-forward output

Real in-memory Room + real scoring graph, 120 seeded days, run twice: per-day
commits vs one transaction. Both daily_summaries and workout_records rows must
match exactly. This is the check that catches deferring a write past the day
that reads it (sumRasLastSixDays reads N-1..N-6; ComputeSleepMetricsUseCase
reads N-1), which the mock-level transaction-count tests cannot see.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 5: Manual verification, plan-doc status, and release lint

**Files:**

- Modify: `internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md`

---

- [ ] **Step 1: Manual sync-cost verification on a device**

`PERFORMANCE_OPTIMIZATION_PLAN.md` §8 specifies this as the F7 verification: *"temporary debug
counter on DAO-flow emissions before/after F7 — a routine sync must trigger each observed query
once, not once per day."*

1. Build and install the debug app: `./gradlew installDebug`.
2. Temporarily add a counter log to one observed query — e.g. in `DailySummaryDao.observeSince`,
   wrap with `.onEach { android.util.Log.d("F7Count", "observeSince emission") }`.
3. Background and foreground the app to fire a routine resume sync with a ≥7-day window.
4. Expected: **one** emission attributable to the sync's recompute, not one per synced day.
5. **Revert the temporary counter before committing.**

Also do the plan's standing charts-always-composed check: scroll Vitals/Sleep/Workouts during a
sync — no chart may visibly recreate.

Record what you observed (including "not run" if a device was unavailable) in the plan-doc update
below. Do not claim a measured result you did not measure.

- [ ] **Step 2: Run release lint**

```bash
./gradlew lintRelease
```

Expected: no new findings versus the pre-F7 baseline.

- [ ] **Step 3: Update `internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md`**

1. **Header (`:4-5`):** move `F7` from the "Not yet implemented" list to the "Landed" list.
2. **§6 F7 section (`:421`):** insert an `**Implemented:**` block immediately under the heading,
   following the format the other landed items use (`F2`, `F3`, `F11`), with the four commit SHAs
   from Tasks 1–4. State plainly where the landed shape differs from the original write-up:

   > **Implemented:** `<task1>` (daily-path walk-forward contexts), `<task2>` (daily sync in one
   > transaction), `<task3>` (resync chunked at 30 days), `<task4>` (Robolectric A/B equivalence
   > lock). **Landed shape differs from remediation step 1 below.** That step proposed buffering
   > each day's summary and upserting the list after the loop; that would have changed scores. Day N
   > reads rows days N-1..N-6 wrote in the same loop — `ScoringRepositoryImpl.sumRasLastSixDays`
   > (`dailySummaryDao.getByDates`) feeds `totalRasWorkoutOnly`/`totalRasEverydayHr`, and
   > `ComputeSleepMetricsUseCase` reads yesterday's summary. The landed fix wraps the loop in one
   > `withTransaction` instead: reads see the transaction's own uncommitted writes, so output is
   > identical by construction and no buffer or write-through read cache is needed. Two further
   > deltas: the per-day `workout_records` `modelTrimp` write inside `ScoringRepositoryImpl`
   > (a second per-day invalidation the write-up did not mention) is coalesced for free by the same
   > transaction, so it needed no code of its own; and the daily path was additionally moved onto
   > the 5-arg `recomputeDay` overload, which it was not using — read-side batching in the same loop.
3. **§7 table row 13:** replace the `⬜ **open**` status with `✅` and the four SHAs.
4. **§7 "Remaining work" line (`:757-758`):** drop `F7` from the ordering.
5. Under the F7 section, record the Step 1 manual-verification outcome verbatim — including "not
   run, no device available" if that is the truth.

- [ ] **Step 4: Commit**

```bash
rtk git add internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md && \
rtk git commit -m "docs: record F7 as landed and note where the shape differs from the write-up

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Acceptance criteria (whole plan)

| Criterion | Where it is proven |
| --- | --- |
| Syncing an N-day window performs exactly one `daily_summaries` write transaction (daily path) | Task 2 — `sync recomputes the whole window inside exactly one transaction` |
| The frozen-baseline clear is inside that same transaction | Task 2 — `sync clears frozen baselines and scores every day inside the transaction` |
| No Health Connect I/O happens inside a transaction | Task 2 — `sync opens no transaction around the Health Connect window read` |
| Resulting rows byte-identical to the per-day implementation on a fixture | Task 4 — `WalkForwardTransactionEquivalenceTest` |
| `workout_records` `modelTrimp` writes coalesce without changing data | Task 4 — the `workouts` half of the same assertion |
| Historical resync transactions are bounded and resume-safe | Task 3 — the three chunk tests + the pre-existing `ResyncCheckpointResumeTest` |
| Per-day failure semantics unchanged (log, continue, `SYNC_PARTIAL_FAILURE`) | Task 2 — pre-existing `DailySyncUseCaseTest` cases still pass unmodified in behaviour |
| Progress UX unchanged (`ForegroundSyncController` / `WorkInfo.progress`, not Room emissions) | Task 3 — `onProgress` still fires per day inside the chunk; no code path reads Room for progress |
| `DATA_FLOW.md` updated in the same commits | Tasks 1, 2, 3 |
| A routine sync triggers each observed query once, not once per day | Task 5 Step 1 (manual, device required) |

## Out of scope — do not do these here

- **Any scoring change.** Formulas, thresholds, coefficients, and the order in which days are
  recomputed all stay exactly as they are.
- **Buffering summaries / adding a bulk persist entry point.** `DailySummaryDao.upsertAll` already
  exists (`:35-36`) and stays unused by this plan; introducing a compute/persist split would break
  the read-after-write dependency the transaction preserves.
- **`RoomHealthIngestionStore`'s 5,000-row chunked ingest transactions** (`:80, :92`). The plan
  explicitly says leave those alone — cooperative cancellation between chunks is deliberate.
- **PERF-005 / DB-002 / SCORE-003** — owned by the architecture remediation plan (§4 of the
  performance plan).
- **F15, F13, F17, F18, F19, F20, F22, F23, F14, N1, N2** — separate items in
  `PERFORMANCE_OPTIMIZATION_PLAN.md` §7.
