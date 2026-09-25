# Readylytics Architecture, Health Connect, Performance, and Scoring Remediation Plan

Review baseline: commit `782dd99f` ("Feat/phase5", #294), 2026-09-25. Status: **planning only**. This document authorizes no implementation; it specifies findings, interfaces, invariants, and an ordered roadmap.

Chosen location: `internal-docs/plans/`, which is **tracked**. `docs/superpowers/plans/` holds seven prior plans and looks like the active convention, but `.gitignore:53` excludes `docs/superpowers/` entirely — none of those files is in git, so a plan placed there cannot be committed, reviewed, or read by anyone but its author. This document reuses the exact path of the prior plan (`ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md`), which was deleted in #294 on completion; that document's Phase 0–5 roadmap is finished and is treated here as **prior work**, not as an open backlog.

Sibling tracked plans in this directory: `CORE_SCORING_JVM_MIGRATION.md`, `IDEA_HOME_SCREEN_WIDGETS.md`.

Finding identifiers in this document (`HC-101`, `PERF-101`, …) are deliberately numbered from 101 so they can never be confused with the `HC-001`…`SEC-005` identifiers that the prior plan used and that survive in source comments, `internal-docs/DATA_FLOW.md`, and `benchmark/BASELINE.md`.

**Decision log.** Three open decisions were resolved by the repository owner on 2026-09-25 and are folded into the sections below; §14 records each one's rationale and now carries its outcome.

| Decision | Outcome | Effect |
|---|---|---|
| **OD-2** | **Narrow the workout heart-rate read to `EXERCISE`-tagged samples.** | WP-13 unblocked; PERF-101 step 2 proceeds with a display-vs-persisted TRIMP equality test. |
| **OD-3** | **Keep truncation** in `HeartRateFormulas.estimateMaxHr`; document it. | SCORE-104 becomes documentation-only; no historical score changes; no forced recompute. |
| **OD-4** | **Remove `resolvedHrMax` from `ScoringRunSnapshot` and bump the protocol to 4, bundled into WP-08.** | Checkpoint resume position resets **once**, in Release A, not twice. |
| **OD-1** | **Adopt bounded, allow-listed `DiagnosticFields`**; free-form message text stays refused. | SEC-101 moves from optional to in-scope for WP-22; `docs/privacy.md` gains a report-contents section. |
| **OD-5** | **Not a decision — resolved by evidence.** The SQLCipher multi-process key race was fixed and verified in July 2026; the fix is present in current code. | The benchmark blocker is stale documentation, not an open defect. WP-01 re-runs the two journeys and corrects `benchmark/BASELINE.md`. |
| **OD-6** | **Give the changes phase its own foreground budget**, sized from WP-01's `CHANGES-1K` measurement. | HC-103 step 2 becomes a named constant alongside the existing ingest budgets; escalation reuses `REQUIRES_HISTORICAL_RESYNC`. |

No decision in §14 remains open. Each is retained there with its rationale so the reasoning stays on record.

---

## 1. Executive Summary

**Current architectural condition is good.** Readylytics is a 15-module Gradle build (`:app`, 7 `:feature:*`, 7 `:core:*`, plus two benchmark modules) with ~1,014 production Kotlin files. Dependency direction is correct: feature modules depend on domain ports in `:core:model`, Room is the UI's single source of truth, Health Connect is an ingestion adapter only, and every formula lives in pure-Kotlin `:core:scoring`. The pipeline already has paged HR/HRV ingestion with a per-page sample budget, source-scoped authoritative replacement, staged deletion reconciliation, four-phase resumable resync with immutable run identity, frozen per-day baselines, walk-forward batched contexts, a durable dirty-range journal with generation fencing, a hot/warm tier with a per-minute coverage ledger, SQLCipher storage, and a sanitizing release log sink. **No rewrite and no further modularization is justified.**

**Most important correctness risks.** Three defects are confirmed by reading and can each produce user-visible wrong or missing data:

1. `DatabaseReadyStartupInitializer` gates the pending-dirty / stale-scoring-version recompute enqueue behind an unrelated preference migration's success (CACHE-101). A single failure in `migrateTrimpDefaultsIfNeeded()` silently skips every recompute gate for that launch, leaving journaled dirty days unpublished.
2. The Health Connect **changes** path never removes an `EXERCISE` record that belongs to a non-selected device (HC-102): the delete is skipped for `EXERCISE` and the upsert is skipped for a non-matching device, so nothing happens at all. Only a full historical resync's prune phase repairs it.
3. Settings-triggered "recompute today" calls bypass `HealthMutationCoordinator` entirely (CACHE-102), so they can interleave into a running walk-forward that is scoring against a frozen preference snapshot.

**Most important scalability risks.** Two paths do not survive the design target of >1,000,000 heart-rate records in 30 days:

- `fetchHeartRateSamplesByWorkout` issues one `HeartRateRepository.getByTimeRange` per workout cluster with a **45-day** span guard, unfiltered by record type, then copies and re-sorts the result (PERF-101). At target density that is ~1.5M entity rows plus a full domain mapping — an out-of-memory kill on a display path.
- `HeartRateDao.upsertAll` / `HrvDao.upsertAll` execute **one SQL statement per row** inside a Kotlin loop (PERF-102). `SourcePayloadWriter` chunks at 500 rows per *transaction* but not per *statement*, so a full historical ingest of the target dataset issues ~1M individual upserts.

**Most important Health Connect risk.** Retry is nested (HC-101): `HealthConnectRepositoryImpl.readAllPagesStreaming` wraps every page in its own `retryWithBackoff` with an independent `maxAttempts = 5`, while the caller has already wrapped the whole read in the window-scoped `ReadRetryBudget`. `retryWithBackoff` has a `budget` parameter for exactly this and it is never passed. Under provider quota pressure one window can make up to 25 Health Connect calls and burn ~75 s of backoff inside a 180 s `withTimeout`, converting a clean retryable failure into a window timeout. This directly contradicts `ReadRetryBudget`'s own KDoc.

**Confidence in the scoring engine is high, with one duplication defect and two dead divergent implementations.** No formula error, unit error, or window off-by-one was found in the reviewed calculations (TRIMP/Banister/Cheng/iTRIMP, everyday-HR load, HRV and RHR z-scores, sigma blending, readiness weighting, night validation). The defects are structural: `ResyncRangeUseCase` re-implements the Tanaka hrMax formula with different rounding than `HeartRateFormulas` (SCORE-101), and `BaselineComputer` carries two production-dead baseline implementations whose night-membership rules differ from the ones the pipeline actually uses (SCORE-103).

**Recommended strategy.** Repair the three correctness defects and the retry nesting first (they are all small, local, and independently testable), then bound the two large-dataset paths, then remove the duplicated/dead scoring and invalidation code, then the ownership and UI-structure work. Preserve every existing product decision: current-day-only pull-to-refresh, retention-bounded historical resync, "aging never invalidates retained summaries", idempotent-within-a-tier determinism, and the existing progress channel.

---

## 2. Repository Areas Reviewed

This is a static architecture and correctness audit of the repository at `782dd99f`. No Gradle build, device run, benchmark, or migration was executed as part of producing this document; every "confirmed" finding below follows directly from inspected source, and every claim about runtime cost is labelled as an expectation requiring measurement.

Paths are repository-relative. These prefixes apply throughout:

| Alias | Exact directory |
|---|---|
| `HC/` | `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/` |
| `DB/` | `core/database/src/main/kotlin/app/readylytics/health/core/database/` |
| `SCHEMA/` | `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/` |
| `MODEL/` | `core/model/src/main/kotlin/app/readylytics/health/core/model/` |
| `SCORE/` | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/` |
| `APP/` | `app/src/main/kotlin/app/readylytics/health/` |
| `WORKOUTS/` | `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/` |
| `SETTINGS/` | `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/` |

**Guidance and documentation.** `.claude/CLAUDE.md`, `AGENTS.md`, `GEMINI.md`, `README.md`, `ABOUT.md`, `internal-docs/DATA_FLOW.md` (388 KB; §1.1–1.4, §2.11, the component tables at §1.2, and the tier-determinism notes were read in detail), `internal-docs/INSIGHTS.md`, `internal-docs/INSIGHT_DETAILS.md`, `benchmark/BASELINE.md`, the seven existing plans in `docs/superpowers/plans/`, and the two in `internal-docs/plans/`. The prior remediation plan was recovered from `git show 782dd99f^:internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md` to establish what is already-closed work.

**Build and wiring.** `settings.gradle.kts`, `gradle/libs.versions.toml`, `build-logic/src/main/kotlin/readylytics.android-library-conventions.gradle.kts`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `config/detekt/detekt.yml`, all 15 `detekt-baseline.xml` files, `DB/di/{DatabaseModule,DatabaseRepositoryModule,DaoProvidersModule,ScoringSyncBindingsModule}.kt`, `HC/di/HealthConnectModule.kt`, `SCORE/di/`, `APP/di/{CoroutineDispatchersModule,DataStoreModule,RepositoryModule,FeaturePortModule,UtilModule,WorkerModule}.kt`.

**Ingestion.** `HC/data/healthconnect/{HealthConnectRepositoryImpl,HealthChangeSynchronizerImpl,HealthChangeSyncSupport,StepRecordReader,IntervalTotalsReader,WorkoutReadPreparer,WorkoutEnrichmentRefresher,DeviceLabel}.kt`; `HC/domain/sync/{HealthSyncUseCase,DailySyncUseCase,ResyncRangeUseCase,HistoricalIngestPhase,HistoricalPrunePhase,HistoricalRecomputePhase,HistoricalRunResolver,HealthIngestionCoordinator,HeartSampleStreamer,ReadRetryBudget,RetryWithBackoff,HealthConnectRetryPolicy,StepCountFetcher,ScanIdentities,DeviceSourceFilter,VitalsInputMapper}.kt`; `MODEL/domain/sync/mappers/`, `MODEL/domain/sync/link/`, `MODEL/domain/sync/{ScoreInvalidation,ScoringRunContext,ScoringRunSnapshot,HistoricalRunIdentity}.kt`.

**Storage.** All entities and DAOs under `SCHEMA/data/local/`; `DB/data/local/{HealthDatabase,DatabaseMigrations,RoomHealthIngestionStore,RoomHealthChangeIngestionStore,SourcePayloadWriter,SourceRefResolver,StagedDeletionReconciler,RoomScanStagingStore,RoomDirtyRangeStore,SessionLinkReconcilerImpl,AuthoritativeHeartRateReader,DataRollupManager,MinuteRollupStreamer,MinuteCoveragePublisher,WarmTierReconstructor,WarmTierRelinker,RetentionCleanup,HealthMutationCoordinatorImpl}.kt`; `DB/data/security/SqlCipherKeyManager.kt`; migrations `Migration9To10` … `Migration22To23`.

**Scoring.** `SCORE/domain/scoring/` and its `components/`, `strategies/`, `sleep/` subpackages; `SCORE/domain/{cardio,calculation,recommendation,insights,airecommendation,util,workouts}/`; `DB/data/repository/{ScoringRepositoryImpl,ScoringDayDataLoader,ScoringDayContextResolver,DailyTrimpComputer,ReadinessSummaryCoordinator,FinalSummaryAssembler,BaseSummaryAssembler,ResidualFatigueComputer,DirtySummaryPublisher,ScoringHistoryRepositoryImpl,ScoringSeriesLoader,ScoringHeartRateDataLoader}.kt` and `DB/data/repository/recommendation/`.

**Background, UI, privacy.** `APP/workers/{HealthResyncWorker,DataRollupWorker,DataCleanupWorker,BirthdayCheckWorker,RecalcDiagnosticRecorder,SyncNotifications}.kt`, `APP/DatabaseReadyStartupInitializer.kt`, `APP/HealthDashboardApplication.kt`, `APP/util/SecureFileLogSink.kt`, `MODEL/domain/util/{AppLog,SafeDiagnosticFormatter}.kt`, `APP/data/backup/` (exporter, writers, validator, restore operations, rotation), `APP/data/preferences/`, and the Dashboard / Workouts / Sleep / Vitals / Settings ViewModels and state factories.

**Validation infrastructure noted (not executed).** 557 JVM unit tests and 36 instrumented tests under `src/test` / `src/androidTest`; `:benchmark` (macrobenchmark: `StartupBenchmark`, `ScrollBenchmark`, `BaselineProfileGenerator`); `:database-benchmark` (`HealthPipelineBaselineBenchmark`, `ScoringWalkForwardBenchmark`, `ScanStagingScaleBenchmark`, `HealthDatasetMatrixVerificationTest`, `V7DatabaseMigrationBenchmark`, `V7DatabaseIngestMicrobenchmark`). `benchmark/BASELINE.md` records partial frame-timing numbers and an explicitly **pending** `dashboardVitalsTabSwitch` / `hotStart` result blocked on an SQLCipher key race.

**Dependency basis for every API claim.** `androidx.health.connect:connect-client` **1.1.0**, Room **2.8.5**, SQLCipher **4.19.0**, WorkManager **2.11.2**, Kotlin **2.4.20**, AGP **9.4.1**, Hilt **2.60.1**, coroutines **1.11.0**, Compose BOM **2026.09.00**, Vico **3.3.1**; `minSdk 26`, `compileSdk`/`targetSdk 37`. Every recommendation below is expressible against connect-client 1.1.0 as pinned — **no finding requires an SDK upgrade**, and none relies on restricted or experimental deduplication internals.

---

## 3. Current-State Architecture

```text
Health Connect 1.1.0
  ├─ raw reads (ReadRecordsRequest + pageToken)         ── HealthConnectRepositoryImpl
  ├─ Changes API (per-type tokens)                      ── HealthChangeSynchronizerImpl
  └─ aggregate step totals                              ── StepRecordReader / IntervalTotalsReader
        │
        ▼  domain DTOs (DomainHeartRateRecord, DomainSleepSessionRecord, …)
  DailySyncUseCase  (current day + 1 back-day, foreground)
  ResyncRangeUseCase (retention-bounded, 30-day chunks, 4 resumable phases, WorkManager)
        │  both serialized by HealthMutationCoordinator.withMutation
        ▼
  HealthIngestionCoordinator
    ├─ fetchBulkRecords: 9 concurrent reads, one shared ReadRetryBudget
    ├─ HeartSampleStreamer: paged HR/HRV, sliceBySampleBudget(TRANSFORM_SAMPLE_BUDGET)
    ├─ ScanStagingStore.stageIds  → scan_seen_ids / scan_type_state
    └─ StagedDeletionReconciler   → anti-join prune, only when scan state == COMPLETE
        ▼
  HealthIngestionStore / SourcePayloadWriter  (source-scoped authoritative replacement)
        ▼
  SQLCipher Room v23 — 26 entities
    health_source_records (integer FK parent)  heart_rate_records  hrv_records
    sleep_sessions/stages  workout_records(+route points)  step_records  vo2max_records
    daily_summaries  hr_minute_buckets  minute_coverage  hr_source_minute_contributions
    dirty_ranges  health_mutation_state  scan_seen_ids  scan_type_state  staged_*  audit_events
        ▼
  SessionLinkReconciler.reconcile(start, end, zoneThresholds)   ← once, full range, chunk-independent
        ▼
  walk-forward recompute, ascending date, one transaction per day
    DailyRecomputeSupport.recomputeDay → ScoringRepositoryImpl.computeAndPersistDailySummary
      ├─ ScoringDayContextResolver / ScoringDayDataLoader
      ├─ DailyTrimpComputer → CanonicalWorkoutResolver → ComputeWorkoutTrimpUseCase → RasCalculator
      ├─ ReadinessSummaryCoordinator → BaselineComputer / BaselineZScoreComputer / LoadScoringStrategy
      ├─ ResidualFatigueComputer, RasTotalsComputer, FinalSummaryAssembler
      └─ MorningRecommendationAssembler
        ▼
  DirtySummaryPublisher.captureDay / publishDay
    (generation-fenced; advances dirty_ranges cursors in the same transaction as the summary write)
        ▼
  daily_summaries + workout_records.modelTrimp + frozen baseline snapshot
        ▼
  repository Flows → feature StateFlow → collectAsStateWithLifecycle → Compose (M3)

Aging (never invalidates retained summaries, by design):
  raw HR older than 90 d ─ DataRollupManager ─▶ hr_minute_buckets (+ percentile sketch)
                                              ─▶ minute_coverage ledger (one tier/generation per minute)
  raw data older than retention ─ RetentionCleanup ─▶ keyset-batched delete

Reads resolve tier through AuthoritativeHeartRateReader, which owns the minute_coverage predicate.
Checkpoints (ResyncCheckpointStore) and Changes tokens (HealthChangeTokenStore) live in DataStore;
dirty work and mutation generation live in Room.
```

**Two-flow contract (unchanged, and this plan preserves it).** Pull-to-refresh routes through `ForegroundSyncController.triggerDailySync()` → `HealthSyncUseCase.sync(windowDays = 1)`; the Settings action enqueues `HealthResyncWorker` → `FullHistoricalResyncUseCase` → `HealthSyncUseCase.resyncRange()`. `HealthSyncUseCase.recomputeRange()` is the Health-Connect-free re-scoring path used when settings invalidate derived history.

**Ownership that is already correct and must not be disturbed.** `RetentionBounds` is the single source of retention→date math. `ScoreInvalidation.dependencyClosure` is the single invalidation-horizon function used in production. `AuthoritativeHeartRateReader` is the single owner of the hot/warm visibility predicate. `DailyRecomputeSupport.inRecomputeTransaction` is the single place either sync path opens a recompute transaction. `ScoringRepository.computeDailySummary` is the single point of scoring-math entry.

---

## 4. Findings Register

Severity is impact, not implementation order. **Confirmed** means the behavior follows directly from inspected code; it does not imply a device reproduction. **Suspected** means the code shape implies the risk but the magnitude needs measurement. Complexity: **S** = local change in one or two files, **M** = coordinated change across components, **L** = persistence or protocol migration.

### HC-101 — Per-page retry nests inside the window retry budget

| | |
|---|---|
| **Category** | Health Connect ingestion |
| **Severity** | High |
| **Confidence** | High |
| **Status** | Confirmed |
| **Complexity** | S |
| **Migration risk** | None (no persisted state) |

**Affected files and symbols**
- `HC/data/healthconnect/HealthConnectRepositoryImpl.kt:287` — `readAllPagesStreaming` wraps each `client.readRecords(...)` in `retryWithBackoff { … }` with no `budget` argument.
- `HC/domain/sync/RetryWithBackoff.kt:17-23` — `retryWithBackoff` accepts `budget: ReadRetryBudget? = null` and delegates entirely to it when non-null.
- `HC/domain/sync/ReadRetryBudget.kt:10-20` — KDoc states the nested `maxAttempts^2` shape was eliminated and that "the outer retry belongs to WorkManager's EXPONENTIAL backoff".
- `HC/domain/sync/HealthConnectRetryPolicy.kt:8` — `maxAttempts = 5`, `initialDelayMs = 1_000`, `maxDelayMs = 60_000`.
- `HC/domain/sync/HealthIngestionCoordinator.kt:216-279` — all nine bulk reads go through `retryBudget.execute(...)`.
- `HC/domain/sync/HeartSampleStreamer.kt:31,62` — `hrPages` / `hrvPages` go through `retryBudget.execute(...)`.
- `HC/domain/sync/StepCountFetcher.kt:83,101,149,176` — four further `retryWithBackoff` calls with no budget, in the same run.

**Current behavior.** Every Health Connect read reachable from `ingestWindow` is retried twice over: the repository retries each page up to 5 times with 1/2/4/8 s backoff, and when that finally throws, the window's `ReadRetryBudget` counts it as **one** attempt and retries the whole read up to 5 times.

**Evidence.** `readAllPagesStreaming` is the only page loop, and it is reached by `readSleepSessions`, `readExerciseSessions`, `readWeightRecords`, `readBodyFatRecords`, `readBloodPressureRecords`, `readOxygenSaturationRecords`, `readBodyTemperatureRecords`, `readStepsRecords`, `readVo2MaxRecords`, `readHeartRateSamplesPaged`, and `readHrvSamplesPaged` — i.e. every read the coordinator budgets. `retryWithBackoff`'s `budget` parameter has no caller anywhere in `src/main`.

**Root cause.** The prior HC-005 work removed the *outer window* wrapper in `HistoricalIngestPhase` and introduced `ReadRetryBudget`, but left the *inner per-page* wrapper in the repository and never threaded the budget down through `HealthConnectRepository`, whose interface has no parameter to carry it.

**Impact.** Up to 25 Health Connect calls and ≈75 s of in-process backoff for a single read under provider quota pressure, inside a `withTimeout(windowBudgetMs)` of 180 s (`HealthIngestionCoordinator.ingestWindow` default) or 3 min / extended for daily sync. The realistic failure mode is that a rate-limited provider produces a `HealthConnectWindowTimeoutException` — which drives adaptive chunk halving and, on the daily path, a `DEFERRED_DAILY_SYNC` user-visible failure — instead of a clean budget exhaustion that `HealthResyncWorker` would convert into `Result.retry()` and WorkManager's durable exponential backoff. It also multiplies quota pressure on the very provider that is already throttling.

**Recommended remediation.** Thread one budget per read through the port rather than removing the inner loop (removing it would lose retry for the standalone callers in `StepCountFetcher`).

```kotlin
// MODEL/domain/repository/HealthConnectRepository.kt  (sketch)
/** Opaque per-window retry scope; null means "this caller owns its own retry". */
interface ReadRetryScope { suspend fun <T> execute(label: String, block: suspend () -> T): T }

suspend fun readSleepSessions(
    from: Instant, to: Instant, retryScope: ReadRetryScope? = null,
): ReadOutcome<List<DomainSleepSessionRecord>>
```

`ReadRetryBudget` implements `ReadRetryScope` (it already has the exact signature). `readAllPagesStreaming` becomes `retryWithBackoff(budget = scope) { … }`; when `scope` is null it keeps today's independent policy. `HealthIngestionCoordinator` and `HeartSampleStreamer` pass `params.retryBudget` and stop wrapping. `StepCountFetcher` either creates and shares one budget per fetch window or keeps its independent scope explicitly.

**Dependencies.** None. Should land before any window-budget tuning so measurements are not confounded.

**Acceptance criteria.**
1. A fake `HealthConnectClient` that fails every `readRecords` with a rate-limit message causes **at most `maxAttempts` total** `readRecords` invocations across one `ingestWindow`, asserted by call count.
2. `retryWithBackoff` with `budget == null` retains today's behavior (existing tests unchanged).
3. Budget exhaustion propagates out of `ingestWindow` as the original transient exception, **not** as `HealthConnectWindowTimeoutException`, when the window budget has not elapsed.
4. `internal-docs/DATA_FLOW.md` §1.1 and the `HealthIngestionCoordinator` row are corrected: the sentence "Individual raw HC page fetches are retried via `retryWithBackoff` inside `HealthConnectRepositoryImpl.readAllPagesStreaming` (unchanged by HC-005/PERF-001)" must be replaced by the new single-scope description.

---

### HC-102 — Changes path never removes an EXERCISE record from a de-selected device

| | |
|---|---|
| **Category** | Health Connect ingestion / data integrity |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | Confirmed |
| **Complexity** | S |
| **Migration risk** | Low — repair pass needed for already-stale rows |

**Affected files and symbols**
- `HC/data/healthconnect/HealthChangeSynchronizerImpl.kt:416-443` — `processChangesPage`.
- Line 428: `if (dataType != HealthDataType.EXERCISE) { changeIngestionStore.deleteRecord(dataType, id) }`.
- Line 432: `if (selectedDevice == null || deviceLabel == selectedDevice) { … upsertRecord(…) }`.

**Current behavior.** For an `UpsertionChange` whose data type is `EXERCISE` and whose `DeviceLabel` does not match `deviceByDataType["EXERCISE"]`, neither branch runs: the stored row is not deleted and no upsert replaces it. Every other data type deletes first and then conditionally re-inserts, so a de-selected record is correctly removed.

**Evidence.** The `EXERCISE` exclusion is deliberate and documented in the surrounding comment ("deleting first would both discard the coalesce-against-existing merge and cascade-delete route points a Denied re-read must preserve"), but the comment only reasons about the *matching-device* case. The non-matching case falls through both guards.

**Root cause.** A guard introduced for update-in-place semantics (WP-09) was placed outside the device-selection branch, so it also suppresses the de-selection delete.

**Impact.** A workout that the user has excluded by source selection — or a workout whose provider changed its reported device/origin label — remains in `workout_records` and continues to contribute TRIMP, zone minutes, strain and RAS to every recomputed day until a full historical resync runs `HistoricalPrunePhase` / `SelectedSourcePrunerImpl`. Pull-to-refresh alone never converges. This is a silent over-count of training load, not a crash.

**Recommended remediation.** Restructure the branch so device selection is decided first:

```kotlin
is UpsertionChange -> {
    val keep = selectedDevice == null || deviceLabel == selectedDevice
    affectedDates += changeIngestionStore.affectedDatesForRecord(dataType, id, zoneId)
    when {
        !keep -> changeIngestionStore.deleteRecord(dataType, id)          // includes EXERCISE
        dataType == HealthDataType.EXERCISE -> upsertRecord(...)          // update-in-place
        else -> { changeIngestionStore.deleteRecord(dataType, id); upsertRecord(...) }
    }
}
```

Deleting an `EXERCISE` row cascades its `workout_route_points`; that is correct here because the record is being excluded, not re-read.

**Dependencies.** None.

**Acceptance criteria.**
1. Unit test in `core:healthconnect`: with `deviceByDataType["EXERCISE"] = "watchA"` and a stored workout from `watchA`, an `UpsertionChange` for the same record id now reporting `watchB` results in zero rows in `workout_records` for that id, and the change's date is in `affectedDates`.
2. The matching-device case still takes the update-in-place path: `modelTrimp`, route state, distance and elevation survive (existing `WorkoutReadPreparer` / `persistPreparedWorkouts` tests stay green).
3. A `DeletionChange` for `EXERCISE` continues to delete (unchanged).
4. One-time repair: covered by WP-04's bounded prune (below), not by a schema migration.

---

### HC-103 — Changes application is per-record and unbounded per run

| | |
|---|---|
| **Category** | Health Connect ingestion / performance |
| **Severity** | Medium |
| **Confidence** | High (shape) / Medium (magnitude — needs measurement) |
| **Status** | Confirmed shape, suspected magnitude |
| **Complexity** | M |
| **Migration risk** | Low |

**Affected files and symbols**
- `HC/data/healthconnect/HealthChangeSynchronizerImpl.kt:416-443` (`processChangesPage`), `:518-537` (`upsertRecord`), `:540-565` (`upsertSleep`, `upsertHeartRate`, `upsertHrv`), `:607-…` (vitals upserts).
- `HC/data/healthconnect/HealthChangeSynchronizerImpl.kt:124-154` — `while (hasMore) { … }` with no page cap and no time budget.
- `DB/data/local/RoomHealthChangeIngestionStore.kt` — `affectedDatesForRecord`, `deleteRecord`.

**Current behavior.** Each change in a page performs, individually: one `affectedDatesForRecord` query, one `deleteRecord` statement, and one single-element `HealthIngestionStore.persist(batch)` or `replaceHeartRateSources(listOf(...))`. The whole page runs inside one `transactionRunner.runInTransaction { }`. The page loop continues until `hasMore == false`, with no bound on total pages or elapsed time, and this runs on the **foreground** daily-sync path before any ingest budget applies.

**Evidence.** `pageSessionSpans` was already batched per page (R2-HC-003), proving the per-record pattern was recognised for session spans but not for the writes. The resync ingest path, by contrast, batches everything through `HealthIngestionBatch` and `SourcePayloadWriter`.

**Root cause.** The changes path was written record-at-a-time for correctness (each record needs its own affected-date derivation and its own conditional delete) and never revisited after the batched ingest path was built.

**Impact.** A user returning after an extended offline period, or a provider that backfills a long window, produces a large change set that is applied one record at a time inside a single Room transaction on the foreground path, with no opportunity to yield, checkpoint, or report progress. Expected symptoms: a long unresponsive pull-to-refresh, WAL growth, and — because tokens are only committed at the very end of `DailySyncUseCase.run` — full replay of the same work if the run fails afterwards.

**Recommended remediation.** Three independent, individually shippable steps:
1. **Batch within a page.** Group `changes` by kind, resolve `affectedDatesForRecord` for the whole page in one query (`affectedDatesForRecords(dataType, ids, zoneId)`), delete in one `IN`-list-free anti-join or chunked delete, and build a single `HealthIngestionBatch` per page.
2. **Bound the loop (OD-6 decided).** Give the changes phase **its own** foreground budget rather than folding it into the ingest budget: add `MAX_CHANGE_PAGES_PER_RUN` plus `DEFAULT_CHANGES_APPLY_BUDGET_MS` to `HC/domain/sync/SyncConstants.kt`, next to the existing `DEFAULT_DAILY_INGEST_BUDGET_MS` (3 min), `BACK_DAY_INGEST_BUDGET_MS` (5 min) and `EXTENDED_DAILY_INGEST_BUDGET_MS` (10 min). A separate constant keeps attribution intact — "changes took too long" stays distinguishable from "ingest took too long" in logs and in the failure reason, whereas folding it under the ingest budget would let a heavy change set silently starve the ingest that follows it and surface as a misattributed `DEFERRED_DAILY_SYNC`. On exhaustion, return an outcome that commits the pages already applied (tokens for those pages are already candidate-tracked in `nextTokens`) and escalates through the **existing** `REQUIRES_HISTORICAL_RESYNC` path — no new failure mode. Size the constant from WP-01's `CHANGES-1K` measurement, not by guess.
3. **Keep the transaction per page**, not per run — already true, but assert it with a test so a future refactor cannot widen it.

**Dependencies.** Step 1 benefits from PERF-102's multi-row upsert; do PERF-102 first or the batching gains less.

**Acceptance criteria.**
1. Applying a synthetic page of 1,000 `UpsertionChange`s issues O(1) — not O(n) — `affectedDatesFor…` and delete statements, asserted by a counting fake store.
2. A change set exceeding the page budget leaves a consistent database, commits the tokens for the pages it did apply, and the next run resumes from those tokens without reprocessing them.
3. Idempotency: applying the same change set twice produces byte-identical `daily_summaries` rows for the affected dates.

---

### HC-104 — Interval change tokens live in singleton mutable state and are never rebaselined by a resync

| | |
|---|---|
| **Category** | Health Connect ingestion / hidden state |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | Confirmed |
| **Complexity** | S |
| **Migration risk** | Low |

**Affected files and symbols**
- `HC/data/healthconnect/HealthChangeSynchronizerImpl.kt:49` — `private val stagedIntervalTokens = mutableMapOf<String, String>()` on a `@Singleton`.
- `:52` — cleared at the top of `applyPendingChanges()`.
- `:191-201` — `commitTokens(tokens)` drains it, regardless of which flow called.
- `:206-223` — `captureChangesTokens()` iterates `HealthDataType.entries` only; `IngestionTokenType.DISTANCE` and `IngestionTokenType.ELEVATION_GAINED` are absent.
- `HC/domain/sync/ResyncRangeUseCase.kt:485-494` — `finalizeRun` calls `changeSynchronizer.commitTokens(tokensToPromote)`.

**Current behavior.** Distance/elevation change tokens are accumulated into an instance field during `applyPendingChanges()` and flushed by whichever call to `commitTokens` happens next. `ResyncRangeUseCase.finalizeRun` calls `commitTokens` for a flow that never populated the map, and `captureChangesTokens()` — the resync's baseline capture — does not capture interval tokens at all.

**Evidence.** The two flows are serialized by `HealthMutationCoordinator`, so this is not a data race today; it is hidden cross-flow coupling through a singleton field plus an incomplete baseline.

**Root cause.** Interval tokens (OD-4) were added as a side-channel next to the typed `HealthDataType` token map instead of joining it.

**Impact.** (a) After a full historical resync re-ingests distance/elevation-derived workout enrichments, the interval tokens still point at a pre-resync position, so the next daily sync replays interval changes that the resync already incorporated — extra `WorkoutEnrichmentRefresher` work and extra affected dates, not wrong data. (b) The singleton field is a latent correctness hazard for any future change that removes the coordinator serialization or introduces a second synchronizer consumer.

**Recommended remediation.** Fold interval tokens into the same return/commit contract as typed tokens:
- Extend `HealthChangeSyncOutcome` with `nextIntervalTokens: Map<String, String>`, returned from `applyPendingChanges` and passed to `commitTokens(typed, interval)` by `DailySyncUseCase`.
- Delete the `stagedIntervalTokens` field.
- Extend `captureChangesTokens()` (or add a sibling) to capture interval baselines, and have `ResyncRangeUseCase.finalizeRun` promote them alongside the typed ones for the types it completed.

**Dependencies.** Touches the same file as HC-102/HC-103; sequence after HC-102.

**Acceptance criteria.**
1. `HealthChangeSynchronizerImpl` holds no mutable instance state (asserted by inspection plus a test that two interleaved `applyPendingChanges` calls on the same instance return independent outcomes).
2. A full resync followed immediately by a daily sync produces **zero** interval changes to apply.
3. `commitTokens` called by the resync path commits only tokens that resync itself captured.

---

### HC-105 — Bulk record reads materialize a whole chunk and stay resident during HR/HRV streaming

| | |
|---|---|
| **Category** | Health Connect ingestion / memory |
| **Severity** | Medium |
| **Confidence** | High (shape) / Medium (magnitude) |
| **Status** | Confirmed shape, suspected magnitude |
| **Complexity** | M |
| **Migration risk** | None |

**Affected files and symbols**
- `HC/domain/sync/HealthIngestionCoordinator.kt:216-279` — `fetchBulkRecords` returns `RawBulkRecords` with nine fully materialized lists.
- `:130-152` — `ingestWindowWithinBudget` holds `rawRecords` across `streamAndPersistHeartSamples` and `stageBulkScans`.
- `:332-380` — `stageBulkScans` maps each list again to an id list (`toIds`), doubling peak for the id strings.
- `HC/data/healthconnect/HealthConnectRepositoryImpl.kt:261-267` — `readAllPages` accumulates every page into one list.

**Current behavior.** For a 30-day resync chunk, steps, distance and elevation records (all of which several providers emit at minute granularity) plus sleep, exercise, weight, body fat, blood pressure, SpO₂, body temperature and VO₂ Max are each read fully into memory, persisted, then held alive while HR/HRV pages stream, then traversed again to build staging id lists.

**Evidence.** HR and HRV already have `TRANSFORM_SAMPLE_BUDGET`-bounded slicing (`HeartSampleStreamer`); the bulk types have no equivalent. `IntervalTotalsReader.readDistanceTotals(from, to)` reads the whole chunk's `DistanceRecord` set.

**Root cause.** The HC-001 streaming work bounded only the two types identified as million-scale; the "low-volume" classification of the other nine was asserted, not measured.

**Impact.** Peak heap during historical resync scales with chunk cardinality of the densest low-volume type rather than with a fixed budget. The design target says nothing about steps density, so this is a *bounded-ness* defect rather than a demonstrated OOM: the contract "at most one page in memory" should hold for every type, not two.

**Recommended remediation.**
1. Stage ids **during** the read rather than after it: have `readAllPagesStreaming` callers stage each page's ids and persist that page, so `RawBulkRecords` never has to outlive the read. This removes the second traversal and the retained lists.
2. Move `STEPS`, `DISTANCE` and `ELEVATION_GAINED` onto the streaming read path (`readStepsRecordsPaged` etc.), mirroring `readHeartRateSamplesPaged`.
3. Keep genuinely low-cardinality types (weight, body fat, BP, SpO₂, body temp, VO₂ Max, sleep, exercise) on the materializing path; they are bounded by human event frequency, and sessions are needed in full for HR tagging anyway.
4. Add a debug-only assertion or telemetry counter recording the maximum bulk-list size observed per chunk, so the "low-volume" claim becomes measured.

**Dependencies.** Should follow HC-101 (so retry accounting is settled before read shapes change).

**Acceptance criteria.**
1. `ingestWindow` peak retained bytes for a fixture chunk containing 43,200 `StepsRecord`s is bounded by the page budget, measured by `:database-benchmark` allocation tracking, not by the chunk's record count.
2. `IngestionSessionContext` still receives the complete unfiltered sleep/workout session lists for the window (required for HR tagging) — this is the one deliberate exception and must be asserted as such.
3. Deletion reconciliation results are unchanged: staged id sets for every type are identical before and after the refactor for a fixed fixture.

---

### HC-106 — Exercise route enrichment costs one IPC round trip per session

| | |
|---|---|
| **Category** | Health Connect ingestion / performance |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | Confirmed |
| **Complexity** | M |
| **Migration risk** | Low |

**Affected files and symbols**
- `HC/data/healthconnect/HealthConnectRepositoryImpl.kt:~410-460` — `readExerciseSessions(from, to, includeDetails = true)` calls `client.readRecord(ExerciseSessionRecord::class, session.metadata.id)` inside `sessions.map { … }`.

**Current behavior.** One extra binder round trip per exercise session in every chunk, purely to obtain `exerciseRouteResult`, which connect-client 1.1.0 does not return from a bulk `readRecords`.

**Evidence.** The code's own comment states this: "Routes are only returned by a per-record read, so this is an extra IPC round-trip per session." This is a genuine SDK constraint at 1.1.0, so the round trip cannot be eliminated — only avoided.

**Root cause.** Unconditional enrichment: the per-record read happens for every session on every resync, including sessions whose route state is already known and whose `sourceRevision` has not changed.

**Impact.** A 10-year retention resync over a user with 1,500 workouts performs 1,500 extra binder transactions, serialized inside the chunk's `withTimeout`. With adaptive chunk shrinking this can cascade into repeated window timeouts on workout-dense ranges.

**Recommended remediation.** Skip the per-record read when it cannot change anything:
- Read the stored `workout_records.routeState` and the existing route-point count for the chunk's session ids in one query before enrichment.
- Perform the per-record read only when `routeState == "NOT_AVAILABLE"`/`PENDING_CONSENT`, or when the session's start/end/exerciseType/device differ from the stored row (i.e. the record actually changed).
- Gate the whole enrichment on `hasExerciseRoutesPermission()` — already available as `HealthConnectRepositoryImpl`'s routes-permission check — so a user who has not granted route consent pays nothing.
- Keep the existing rule that a transient failure propagates (never coerced to "no route").

**Dependencies.** None; independent of HC-105.

**Acceptance criteria.**
1. Re-running a resync over an unchanged range performs **zero** `readRecord` calls for sessions whose stored route state is `IMPORTED` and whose input revision is unchanged.
2. Route consent granted after an earlier `ConsentRequired` still triggers exactly one enrichment read per affected session on the next resync.
3. `internal-docs/DATA_FLOW.md`'s exercise-route paragraph is updated with the new skip rule.

---

### PERF-101 — Workout HR batching can pull 45 days of all-type heart rate into memory

| | |
|---|---|
| **Category** | Large-volume performance |
| **Severity** | High |
| **Confidence** | High |
| **Status** | Confirmed |
| **Complexity** | S |
| **Migration risk** | None |

**Affected files and symbols**
- `WORKOUTS/WorkoutHeartRateBatcher.kt:14` — `CLUSTER_SPAN_GUARD_MS = TimeUnit.DAYS.toMillis(45)`.
- `WORKOUTS/WorkoutHeartRateBatcher.kt:100` — `heartRateRepository.getByTimeRange(spanStart, spanEnd)` per cluster.
- `WORKOUTS/WorkoutsViewModel.kt:294` (`loadRecentWorkouts`, page size 10) and `:322` (`loadWorkoutOnlyGains`).
- `DB/data/repository/HeartRateRepositoryImpl.kt:49-53` — `getByTimeRange` → `authoritativeReader.rangeIn(...).mergedSamples().map { mapToDomain(it) }`.
- `DB/data/local/AuthoritativeHeartRateReader.kt:53-58` — `mergedSamples()` allocates `rawSamples + warmBuckets.reconstructAsRecords()` and `sortedBy`.
- `SCHEMA/data/local/dao/HeartRateDao.kt` — `getVisibleByTimeRange` has no `LIMIT` and no `recordType` filter.

**Current behavior.** `clusterWorkoutsBySpan` greedily merges workouts until a cluster's start-to-end span would exceed 45 days, then issues **one** `getByTimeRange` for the whole cluster span. That query returns every visible heart-rate row of **every** record type (sleep, resting, exercise, everyday) in the span. `mergedSamples()` then, whenever any warm bucket overlaps, builds a second full list and sorts it. The result is mapped to `HeartRateRecordData` and only then sliced per workout by binary search.

**Evidence.** The span guard's own comment says it "caps how much continuous everyday-HR data one dragnet query can pull in" — but 45 days is precisely the worst case the design target describes. `sliceSamplesForWorkout` proves only the *post-fetch* work is bounded.

**Root cause.** The guard bounds wall-clock span, which is the wrong dimension: the cost is proportional to sample density × span, and density is exactly what varies by four orders of magnitude between users.

**Impact.** At the design target of >1,000,000 HR records per 30 days, a 45-day cluster is ~1.5M `HeartRateRecordEntity` rows, plus ~1.5M `HeartRateRecordData` objects, plus a full copy and sort if any warm bucket is in range. This is on the Workouts tab's display path (page of 10 workouts, and again for `loadWorkoutOnlyGains`), reached by ordinary navigation. Expected outcome is an `OutOfMemoryError` or a multi-second jank on `dispatchers.default`. Even at ordinary density (~1 sample/2 min everyday plus per-second workout samples) this is tens of thousands of objects per tab open.

**Recommended remediation.** Bound by rows, not by days, and stop fetching types the caller discards:
1. Add `countInRange` (already present on `HeartRateDao`) as a pre-flight: if the cluster's count exceeds a row budget (proposal: `MAX_CLUSTER_SAMPLES = 50_000`, to be confirmed by benchmark), split the cluster and re-issue.
2. Add a record-type-filtered repository read. `AuthoritativeHeartRateReader.rawByTypeInRange` already exists and is backed by `index_hr_v10_type_timestamp`; it needs a tier-merged sibling (`rangeInOfType`) so the warm side is filtered too, and a `HeartRateRepository.getByTimeRangeOfType(recordType, from, to)` port. Workout TRIMP only consumes `EXERCISE`-tagged samples (`SessionLinkReconcilerImpl.recomputeWorkouts` uses exactly that filter), and **OD-2 (decided 2026-09-25) approves the narrowing**: display metrics and persisted metrics are contractually the same input set. Ship it with a test asserting `GetWorkoutDisplayMetricsUseCase` and `DailyTrimpComputer` agree on TRIMP for a fixture containing a sample inside a workout window that is still tagged `RESTING` — the reconcile pass is the authority for that tagging, and a divergence there is a bug in reconciliation, not a reason to widen the read.
3. Make `mergedSamples()` avoid the copy+sort when `warmBuckets.isEmpty()` (already done) **and** when the two sides are disjoint by time, by merging two already-sorted lists in O(n) instead of concatenating and re-sorting.

**Dependencies.** Step 3 is shared with PERF-103. Step 2 is unblocked (OD-2 decided).

**Acceptance criteria.**
1. A `:database-benchmark` fixture with 1,000,000 HR rows across 30 days, 10 workouts spread over 45 days: `fetchHeartRateSamplesByWorkout` completes with peak additional heap below a stated budget and issues no single query returning more than `MAX_CLUSTER_SAMPLES` rows.
2. Output equality: for a small fixture, `fetchHeartRateSamplesByWorkout` returns element-identical maps before and after the change (the existing `WorkoutHeartRateBatcherTest` equality assertions are extended, not replaced).
3. `EXPLAIN QUERY PLAN` for the new type-filtered query uses `index_hr_v10_type_timestamp`, recorded in the test as a string assertion (the repo already uses this technique — see the `index_hr_v10_timestamp_source` comment on `HeartRateRecordEntity`).
4. Display-vs-persisted agreement: `GetWorkoutDisplayMetricsUseCase` and `DailyTrimpComputer` return the same TRIMP for a workout whose window contains a non-`EXERCISE`-tagged sample (OD-2 guard).

---

### PERF-102 — Heart-rate and HRV upserts execute one statement per row

| | |
|---|---|
| **Category** | Large-volume performance / Room |
| **Severity** | High |
| **Confidence** | High |
| **Status** | Confirmed |
| **Complexity** | M |
| **Migration risk** | None (no schema change) |

**Affected files and symbols**
- `SCHEMA/data/local/dao/HeartRateDao.kt:401-421` — `conflictTargetedUpsert(...)` plus `upsertAll(records)` which loops `for (record in records) { conflictTargetedUpsert(...) }`.
- `SCHEMA/data/local/dao/HrvDao.kt:162-182` — identical shape.
- `DB/data/local/SourcePayloadWriter.kt:168-169, 188-189, 271` — `rows.chunked(BATCH_SIZE = 500).forEach { dao.upsertAll(it) }` inside `transactionRunner.runInTransaction { }`.

**Current behavior.** Chunking is applied at the transaction boundary only. Inside a chunk, 500 separate `INSERT … ON CONFLICT … DO UPDATE` statements are executed, each a separate Room DAO suspend call.

**Evidence.** The `WHERE` clause on `conflictTargetedUpsert` (`beatsPerMinute IS NOT excluded.beatsPerMinute OR …`) is a good no-op-write suppression and must be preserved; it is expressible in a multi-row statement unchanged.

**Root cause.** `@Query`-based targeted upsert cannot take a list, so the list form was written as a Kotlin loop.

**Impact.** A full historical ingest of the design-target dataset performs ~1,000,000 DAO invocations for HR alone, each crossing Room's coroutine/connection machinery. This is the dominant cost of `HealthResyncWorker` on a dense history and directly determines whether a multi-year resync finishes inside WorkManager's foreground-service limits at `targetSdk 37`.

**Recommended remediation.** Add a multi-row variant while keeping the single-row one for the changes path:

```kotlin
// SCHEMA/data/local/dao/HeartRateDao.kt (sketch)
@Transaction
suspend fun upsertAll(records: List<HeartRateRecordEntity>) {
    records.chunked(UPSERT_ROWS_PER_STATEMENT).forEach { upsertChunk(it) }   // 100 rows x 6 binds = 600
}

@RawQuery   // builds "INSERT INTO heart_rate_records(...) VALUES (?,?,?,?,?,?),(...)
            //         ON CONFLICT(sourceRecordRef, timestampMs) DO UPDATE SET ... WHERE <same predicate>"
suspend fun upsertChunk(query: RoomRawQuery): Int
```

Constraints to respect: SQLite's default `SQLITE_MAX_VARIABLE_NUMBER` is 999 on older Android and 32,766 on modern ones — chunk at 100 rows (600 binds) to stay safe on `minSdk 26`. The conflict target must remain the unique index `(sourceRecordRef, timestampMs)` so `rowId` is preserved on update (the entity KDoc makes this load-bearing). The no-op suppression `WHERE` clause must be carried into the multi-row form verbatim, otherwise idempotent re-ingest starts dirtying rows and inflating the WAL.

**Dependencies.** None. Should land before HC-103's page batching so that work compounds.

**Acceptance criteria.**
1. Row-for-row equivalence: for a fixture of 10,000 records containing new rows, unchanged rows and changed rows, the resulting table contents and `rowId` values are identical to the per-row implementation.
2. A counting `SupportSQLiteDatabase`/driver wrapper shows statement count reduced by ≥ 50× for the same input.
3. `:database-benchmark` records before/after wall time for a 1,000,000-row ingest in `benchmark/BASELINE.md` as a new dated section (per that file's stated append-only convention).
4. Re-ingesting identical data produces zero row updates (assert via `changes()` or an audit counter), proving the suppression predicate survived.

---

### PERF-103 — Tier merge copies and re-sorts on every read and every Flow emission

| | |
|---|---|
| **Category** | Large-volume performance |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | Confirmed |
| **Complexity** | S |
| **Migration risk** | None |

**Affected files and symbols**
- `DB/data/local/AuthoritativeHeartRateReader.kt:53-58` — `mergedSamples()`.
- `:120-129` — `observeRange` re-reads the warm side on every hot-tier emission and re-merges.
- `DB/data/repository/HeartRateRepositoryImpl.kt:69-75, 93-98` — `observeByTimeRange` / `observeTimelineWithResolution` map the merged list again per emission.

**Current behavior.** Whenever any warm bucket overlaps the range, `mergedSamples()` allocates `rawSamples + reconstructAsRecords()` (a new list sized to both) and calls `sortedBy { it.timestampMs }` (another allocation plus an O(n log n) sort), even though both inputs are already ascending by timestamp.

**Evidence.** `getVisibleByTimeRange` has `ORDER BY h.timestampMs ASC, h.sourceRecordRef ASC`; `reconstructTimestampedSamples` produces ascending points per bucket and buckets are read in bucket order. Two sorted sequences merge in O(n).

**Root cause.** Convenience concatenation retained from before the coverage ledger guaranteed disjointness.

**Impact.** Three full-size allocations and a comparison sort per read, on the same paths PERF-101 already stresses, and once per Flow emission on live charts. The correctness claim in the KDoc ("the sort is stable, so raw rows keep their order among themselves") is preserved by a stable linear merge.

**Recommended remediation.** Replace with a two-pointer merge into a pre-sized `ArrayList(rawSamples.size + warmCount)`, taking raw rows first on ties to preserve the documented stability. Keep the `warmBuckets.isEmpty()` fast path.

**Dependencies.** Land together with PERF-101 step 3.

**Acceptance criteria.** Output list is element-identical (including order) to `sortedBy` for fixtures with interleaved, adjacent, and tie-timestamped rows; allocation count per call drops to one.

---

### PERF-104 — Tier-visibility queries LEFT JOIN on a computed key with no row bound

| | |
|---|---|
| **Category** | Room query efficiency |
| **Severity** | Medium |
| **Confidence** | Medium |
| **Status** | Suspected — requires `EXPLAIN QUERY PLAN` measurement |
| **Complexity** | M |
| **Migration risk** | Medium if an index or generated column is added |

**Affected files and symbols**
- `SCHEMA/data/local/dao/HeartRateDao.kt` — `getVisibleByTimeRange`, `getVisibleByTypeAndTimeRange`, `_observeVisibleByTimeRange`, `getVisibleMinuteBuckets`, `getVisibleSleepHrProjectionForSessions`, `getVisibleSleepHrSummaryForSessions`, `getVisibleMinHrInRange` — all share the predicate:
  `LEFT JOIN minute_coverage c ON c.bucketStartMs = ((h.timestampMs / 60000) - (CASE WHEN h.timestampMs % 60000 < 0 THEN 1 ELSE 0 END)) * 60000` plus a correlated `NOT EXISTS` against `hr_minute_buckets`.

**Current behavior.** For every candidate heart-rate row, SQLite computes the bucket expression, probes `minute_coverage` by primary key, and for `WARM`/`LEGACY_WARM` rows runs a correlated `NOT EXISTS` subquery against `hr_minute_buckets`. None of these queries has a `LIMIT`.

**Evidence.** The join key is derived arithmetic on `h.timestampMs`, so the join itself is a per-row PK probe (cheap individually) — but it is executed once per candidate row, and the `NOT EXISTS` adds a second probe for rows in warm minutes. The range filter still uses `index_hr_v10_timestamp` / `index_hr_v10_type_timestamp`, so selectivity is fine; the concern is constant factor × row count.

**Root cause.** The coverage predicate was correctly centralized (WP-17) but its cost per row was never measured against the million-row target.

**Impact.** Multiplies PERF-101's cost by a per-row constant. Independently, no read path here can be paged, so there is no way to bound a large range without changing the query shape.

**Recommended remediation.** Measure first, then choose:
1. Capture `EXPLAIN QUERY PLAN` for `getVisibleByTimeRange` over a 1,000,000-row fixture and record it in `benchmark/BASELINE.md`.
2. If the per-row probes dominate, add a keyset-paged variant (`getVisiblePageAfter(startMs, endMs, afterTs, afterRef, limit)`) mirroring the existing `getKeysetPage`, and move bulk consumers onto it. This is the lower-risk option: no schema change.
3. Only if paging is insufficient, consider materializing the bucket key as a Room generated/stored column with its own index — that is a schema migration (v23→v24) with a full-table backfill over the largest table in the database, and should not be undertaken without measurement.

**Dependencies.** Informs PERF-101's row budget.

**Acceptance criteria.** A recorded query plan before and after; bulk consumers use a bounded page size; no behavioral change to which rows are visible (assert against the existing `AuthoritativeHeartRateReaderTest` fixtures).

---

### DB-101 — Entity `init` invariants turn a bad dirty-range row into a read-time crash

| | |
|---|---|
| **Category** | Room / robustness |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | Confirmed |
| **Complexity** | S |
| **Migration risk** | Low |

**Affected files and symbols**
- `SCHEMA/data/local/entity/DirtyRangeEntity.kt:23-33` — three `require(...)` calls in `init`.
- `SCHEMA/data/local/dao/DirtyRangeDao.kt:14-20` — `pending(limit)`, `pendingForDay(epochDay)` return `DirtyRangeEntity`.
- `SCHEMA/data/local/dao/DirtyRangeDao.kt:74-75` — `trimExpiredPrefixes` mutates `nextEpochDay` by raw SQL, bypassing the constructor.
- Consumers: `APP/DatabaseReadyStartupInitializer.kt:181`, `APP/workers/HealthResyncWorker.kt:149`, `APP/workers/RecalcDiagnosticRecorder.kt:70`, `DB/data/repository/DirtySummaryPublisher.kt:40`.

**Current behavior.** Room instantiates `DirtyRangeEntity` when materializing query results, so the `require` checks run on **read**, not only on write. Raw-SQL updates (`trimExpiredPrefixes`, `advance`) can in principle produce a row that the constructor rejects; the next `pending()` read then throws `IllegalArgumentException` from inside a DAO call.

**Evidence.** Today's SQL does keep the invariants (`trimExpiredPrefixes` only raises `nextEpochDay` to a cutoff on rows whose `endEpochDayInclusive >= cutoff`, and `deleteExpired` removes the rest first, inside one `@Transaction`), so this is a latent hazard rather than a live bug — hence Medium, not High. But the failure mode is severe and non-obvious: `DirtySummaryPublisher.captureDay` runs inside the scoring transaction, so a throw there fails every daily recompute, and `HealthResyncWorker`'s drain loop would fail the worker permanently.

**Root cause.** Validation placed on a persistence type that Room also uses as a read model, with raw-SQL mutators that bypass it.

**Recommended remediation.** Move the invariant out of `init` and into the write path:
- Delete the `init` block from the entity.
- Add the same `require`s to `RoomDirtyRangeStore.append(...)` (the only production insert site) and to a new `DirtyRangeDao` `@Transaction` wrapper around `trimExpiredPrefixes`.
- Add a defensive repair query run by `discardBefore`: `DELETE FROM dirty_ranges WHERE nextEpochDay > endEpochDayInclusive + 1 OR startEpochDay > endEpochDayInclusive OR nextEpochDay < startEpochDay`, so a row that somehow violates the invariant is dropped rather than crashing reads.

**Dependencies.** None.

**Acceptance criteria.**
1. An instrumented test inserts a violating row via raw SQL and asserts `pending()` returns without throwing and the repair query removes it.
2. `RoomDirtyRangeStore.append` still rejects an invalid range at the call site.
3. Existing `DirtyMutationRecoveryInstrumentedTest` assertions remain green unchanged.

---

### DB-102 — A second, production-dead dirty-ticket publication protocol

| | |
|---|---|
| **Category** | Maintainability / cache correctness |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | Confirmed |
| **Complexity** | S |
| **Migration risk** | None |

**Affected files and symbols**
- `DB/data/repository/DirtySummaryPublisher.kt:74-138` — `publish(ticket, summary, zoneId, expectedSourceGeneration, stagedWorkoutUpdates, activeSnapshotId)`.
- `:150-165` — the `PublishableDayAssembly` overload of the same.
- `DB/data/local/RoomDirtyRangeStore.kt:58-62` — `rebindSnapshot`.
- `SCHEMA/data/local/dao/DirtyRangeDao.kt:55-63` — `updateSnapshotId`.
- The **used** protocol: `DirtySummaryPublisher.captureDay` / `publishDay`, called from `DB/data/repository/ScoringDayDataLoader.kt:31, 98` and `ScoringRepositoryImpl.kt:158`.

**Current behavior.** Two complete publication protocols coexist. `captureDay`/`publishDay` (capture-before-read, generation-fenced, advances every ticket whose cursor is the published day) is the one production uses. The ticket-scoped `publish(...)` — with its `activeSnapshotId` mismatch abort, its own `advance`/`deleteCompleted` sequence, and the `rebindSnapshot`/`updateSnapshotId` pair that exists to recover from that abort — has no `src/main` caller.

**Evidence.** Repository-wide search for `activeSnapshotId`, `rebindSnapshot` and `updateSnapshotId` finds only the definitions plus instrumented tests.

**Root cause.** The snapshot-fenced protocol was superseded by the capture/publish protocol and never removed.

**Impact.** Two contradictory answers to "what invalidates a ticket" live in the same class. The dead path's `activeSnapshotId` abort, if ever re-enabled, would interact badly with SCORE-101 (divergent snapshot ids) and could strand tickets permanently, which is exactly the `STARTUP_PENDING_DIRTY` re-enqueue loop #292 was fixing. Keeping it is an active trap.

**Recommended remediation.** Delete `publish(ticket, …)` (both overloads), `rebindSnapshot`, and `DirtyRangeDao.updateSnapshotId`; migrate any instrumented test that exercises them onto `captureDay`/`publishDay`. Keep `DirtyRangeEntity.scoringSnapshotId` as a **diagnostic** column (it is read by `RecalcDiagnosticRecorder`) and say so in its KDoc.

**Dependencies.** None; do it before CACHE-101/102 so the invalidation model has one shape while those are edited.

**Acceptance criteria.** No `src/main` symbol named `rebindSnapshot`/`updateSnapshotId`/`activeSnapshotId` remains; the dirty-drain instrumented tests still cover advance, generation fencing, completion delete, and retention trimming.

---

### CACHE-101 — The startup recompute gate is suppressed by an unrelated migration's failure

| | |
|---|---|
| **Category** | Incremental recalculation |
| **Severity** | High |
| **Confidence** | High |
| **Status** | Confirmed |
| **Complexity** | S |
| **Migration risk** | None |

**Affected files and symbols**
- `APP/DatabaseReadyStartupInitializer.kt:84-94`:
  ```kotlin
  val migrationCompleted = runNonFatal("TRIMP normalization migration") {
      physiologyPreferences.get().migrateTrimpDefaultsIfNeeded()
  }
  val settings = settingsRepository.get()
  if (migrationCompleted) {
      runNonFatal("Recompute-only resync check") {
          scheduleRecomputeResyncIfNeeded(settings.userPreferences.first())
      }
  }
  ```
- `:192-204` — `runNonFatal` returns `true` iff the block did not throw; it does **not** report whether a migration occurred.
- `:141-169` — `scheduleRecomputeResyncIfNeeded` owns three independent gates: stale `scoringVersion`, un-backfilled canonical `modelTrimp`, and pending dirty tickets.
- `:176-188` — `pendingDirtyTickets()`.

**Current behavior.** The name `migrationCompleted` reads as "a migration ran", but it is `runNonFatal`'s did-not-throw boolean. The practical effect today is that the gate runs on essentially every launch (good). The defect is the coupling: if `migrateTrimpDefaultsIfNeeded()` throws for any reason — a DataStore read failure, a corrupt preferences proto, a transient IO error — the whole recompute gate is skipped for that launch, and `runNonFatal` swallows the exception so nothing surfaces.

**Evidence.** `runNonFatal`'s own body returns `true`/`false` purely on exception, and `migrateTrimpDefaultsIfNeeded()` returns `Unit`.

**Root cause.** A `Boolean` that means "no exception" was reused as if it meant "state changed", and an unrelated gate was nested under it.

**Impact.** Three distinct healing mechanisms — scoring-version upgrades, canonical TRIMP backfill, and the durable dirty-range drain — all stop running whenever an unrelated preference migration fails. Because the dirty journal is the *only* mechanism that repairs days invalidated by source deletions and authoritative replacements, this can leave permanently stale `daily_summaries` with no user-visible signal and no retry path other than a manual full resync.

**Recommended remediation.**
1. Unnest: call `scheduleRecomputeResyncIfNeeded(...)` unconditionally, inside its own `runNonFatal`.
2. Rename `migrationCompleted` to `trimpDefaultsMigrationSucceeded` and use it only for what it means (log context).
3. Have `runNonFatal` log at ERROR (it already does) **and** record a counter the diagnostic report can surface, so a repeatedly failing startup step is discoverable.

```kotlin
val trimpDefaultsMigrationSucceeded = runNonFatal("TRIMP normalization migration") { … }
runNonFatal("Recompute-only resync check") {
    scheduleRecomputeResyncIfNeeded(settingsRepository.get().userPreferences.first())
}
```

**Dependencies.** None. Highest-value single-line-class change in this plan.

**Acceptance criteria.**
1. Unit test: a `PhysiologyPreferences` fake whose `migrateTrimpDefaultsIfNeeded()` throws still results in `workerScheduler.scheduleResyncWorker(recomputeOnly = true, …)` being called when a pending dirty ticket exists.
2. Trigger attribution is unchanged: `STARTUP_SCORING_VERSION` > `STARTUP_TRIMP_BACKFILL` > `STARTUP_PENDING_DIRTY` precedence preserved.
3. No enqueue happens when all three gates are clear (guards against reintroducing the nightly multi-week recalc that #292 removed).

---

### CACHE-102 — Settings-triggered recomputes bypass the mutation coordinator

| | |
|---|---|
| **Category** | Incremental recalculation / concurrency |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | Confirmed |
| **Complexity** | M |
| **Migration risk** | Low |

**Affected files and symbols**
- `SETTINGS/SleepSettingsViewModel.kt:66-68` — `recomputeToday()` → `scoringRepository.computeAndPersistDailySummary(LocalDate.now(clock))`, invoked from `appScope.launch` on several `SettingsEvent`s.
- `SETTINGS/ThresholdSettingsViewModel.kt:127` — same call inside `viewModelScope.launch`.
- `APP/domain/user/UserUseCase.kt:32` — same call in `updateBirthday`.
- `DB/data/repository/ScoringRepositoryImpl.kt:145-172` — `computeAndPersistDailySummary` takes `calculationMutex`, **not** `HealthMutationCoordinator`.
- `DB/data/repository/DirtySummaryPublisher.kt:39` — `check(state.maintenanceOperationId == null) { "MAINTENANCE_PENDING" }` inside `captureDay`.
- `HC/domain/sync/HealthSyncUseCase.kt:52, 59, 90, 114` — every sync flow wraps in `coordinator.withMutation`.

**Current behavior.** `calculationMutex` serializes one day's computation. It does not serialize a *run*. A durable resync's walk-forward releases the mutex between days, so a settings-triggered `computeAndPersistDailySummary(today)` can execute between day *N* and day *N+1* of a run that is scoring against `plan.effectivePrefs` — the preference snapshot frozen into the run identity — while the interloper uses the newly written preferences.

**Evidence.** `internal-docs/DATA_FLOW.md` states the coordinator is the serialization boundary and that "startup baseline backfill uses the same coordinator boundary, so it never races a sync/resync". These three call sites are the exception.

**Root cause.** `HealthMutationCoordinator` was introduced at the sync facade; direct `ScoringRepository` consumers outside `:core:healthconnect` were not migrated.

**Impact.** (a) Today's summary can be written with new preferences and then overwritten by the run's own recompute of today with old preferences, or vice versa — the final value depends on interleaving. (b) During a backup/restore maintenance window, `captureDay` throws `IllegalStateException("MAINTENANCE_PENDING")` from a ViewModel coroutine; `SleepSettingsViewModel.recomputeToday()` has no catch, so this propagates into `appScope` — an application-scoped, non-cancelling scope — and is reported as an unhandled exception.

**Recommended remediation.**
1. Make the coordinator boundary intrinsic: have `ScoringRepositoryImpl.computeAndPersistDailySummary` acquire `HealthMutationCoordinator.withMutation` itself when it is not already held. The coordinator must therefore be **reentrant** (the sync flows already hold it); add an explicit reentrancy check rather than a second lock type.
2. Alternatively (simpler, and the recommended first step): introduce a narrow `RecomputeTodayUseCase` in `:core:database` that wraps `withMutation { computeAndPersistDailySummary(today) }` and wrap its body in a `Result`, and point all three call sites at it. This keeps the repository's contract unchanged and gives the settings paths a place to handle `MAINTENANCE_PENDING` by degrading to "enqueue a bounded recompute-only worker" instead of throwing.
3. Fix `UserUseCase.updateBirthday` ordering (SCORE-105) at the same time.

**Dependencies.** Coordinate with DB-102 so the publication protocol is settled first.

**Acceptance criteria.**
1. A test that starts a fake multi-day walk-forward and issues a settings recompute concurrently observes the settings recompute serialized **after** the run, never interleaved.
2. With `maintenanceOperationId != null`, the settings path returns a failure result and enqueues a bounded recompute instead of throwing into `appScope`.
3. No deadlock: a sync flow already inside `withMutation` that reaches `computeAndPersistDailySummary` still completes (reentrancy test).

---

### CACHE-103 — Two invalidation horizons coexist; one has no production caller

| | |
|---|---|
| **Category** | Incremental recalculation / maintainability |
| **Severity** | Low |
| **Confidence** | High |
| **Status** | Confirmed |
| **Complexity** | S |
| **Migration risk** | None |

**Affected files and symbols**
- `MODEL/domain/sync/ScoreInvalidation.kt:24` — `MAX_DEPENDENT_WINDOW_DAYS = 84L`.
- `:113-121` — `affectedRange(changed, today)`: widens forward by 84 days.
- `:92-107` — `dependencyClosure(changed, reason, retentionStart, today)`: widens to the full retained suffix for every reason except `RECOMMENDATION_EXAMPLES`.
- Production callers of `dependencyClosure`: `DB/data/local/SourcePayloadWriter.kt:242`, `DB/data/local/RoomHealthChangeIngestionStore.kt:147, 250`. Production callers of `affectedRange`: **none**.
- `internal-docs/DATA_FLOW.md:200` describes `affectedRange` as "kept as the bound for any bounded recompute request via `WorkerScheduler.scheduleResyncWorker`'s `startDate`/`endDate`" — but `HealthResyncWorker.resolveExplicitRange` builds the range straight from input data, and `FullHistoricalResyncUseCase.resolveExecutionRange` clamps it to retention without calling `affectedRange`.

**Current behavior.** The object documents and exports two mutually inconsistent dependency horizons. The 84-day one is dead; the full-suffix one is live. The depth-guard test that enforces `MAX_DEPENDENT_WINDOW_DAYS` still runs and is still valuable — it prevents a new lookback constant from silently exceeding 84 days — but it now guards a constant no production path uses as a horizon.

**Impact.** Maintainability only. A future contributor reading `ScoreInvalidation` can reasonably conclude that an 84-day widening is the model, and under-cover a new invalidation source.

**Recommended remediation.** Keep `MAX_DEPENDENT_WINDOW_DAYS` and its depth-guard test (rename its KDoc to "longest single scoring lookback — used to assert no lookback grows past this, **not** as an invalidation horizon"). Delete `affectedRange(changed, today)` unless WP-04's bounded prune adopts it; if it is adopted, add the caller and keep it. Correct the `DATA_FLOW.md` row either way.

**Acceptance criteria.** Exactly one function in `ScoreInvalidation` returns a dependency horizon, or each exported one has at least one production caller; `ScoreInvalidationTest`'s depth guard still fails the build when a lookback constant exceeds 84.

---

### SCORE-101 — The Tanaka hrMax formula is duplicated with divergent rounding

| | |
|---|---|
| **Category** | Scoring / duplicated business rule |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | Confirmed implementation bug (duplication + divergence) |
| **Complexity** | S |
| **Migration risk** | Low — changes a hashed identity, see below |

**Affected files and symbols**
- `SCORE/domain/util/HeartRateFormulas.kt:11` — `fun estimateMaxHr(ageYears: Int): Int = (208 - 0.7 * ageYears).toInt()` (truncates toward zero), and `:18-23` `resolveMaxHeartRate(prefs): Float`.
- `HC/domain/sync/ResyncRangeUseCase.kt:297-302, 508-509` — `(TANAKA_BASE - TANAKA_FACTOR * prefs.age).toFloat()` with `TANAKA_BASE = 208`, `TANAKA_FACTOR = 0.7` — **no truncation**.
- Consumers of the *other* value: `SCORE/domain/scoring/ResolveDailyBaselinesUseCase.kt:47`, `SCORE/domain/scoring/HrMaxProvider.kt:27,36`, `SCORE/domain/scoring/GetWorkoutDisplayMetricsUseCase.kt:58`, `APP/data/backup/BackupSnapshotExporter.kt:46-47`, `DB/data/repository/DailyTrimpComputer.kt:71-73`.
- `MODEL/domain/sync/ScoringRunSnapshot.kt:33, 119-135` — `resolvedHrMax` is a hashed field of the snapshot.
- `MODEL/domain/sync/HistoricalRunIdentity.kt:52-54, 76-80` — `scoringSnapshotId = sha256Hex(Json.encodeToString(snapshot))`.

**Current behavior.** For a user with `autoCalculateMaxHr = true` and an age where `0.7 × age` is not an integer (i.e. any age not a multiple of 10), `ResyncRangeUseCase` computes `resolvedHrMax` as e.g. `183.5` for age 35, while every other producer computes `183.0`. Those two values hash to different `scoringSnapshotId`s.

**Evidence.** `estimateMaxHr` returns `Int`; `ResyncRangeUseCase` never converts through `Int`. Both feed `ScoringRunSnapshot.capture(prefs, resolvedHrMax)`.

**Root cause.** The formula was inlined into the resync to avoid a `:core:healthconnect` → `:core:scoring` dependency for one function, and the truncation was not reproduced.

**Impact, bounded honestly.** The resync's `scoringSnapshotId` is used as `plan.selectionHash` for checkpoint identity and is compared only against other values produced the same way (`HistoricalRunResolver.resolve`), so **today** the divergence is self-consistent and does not corrupt scores. Two concrete exposures remain:
- `CanonicalWorkoutResolver.isMatchingPrior` (`SCORE/.../CanonicalWorkoutResolver.kt:71-80`) compares a stored `modelTrimpSnapshotId` against a freshly computed one. It is reached only for sample-less workouts (`resolveMissingSamples`), where a mismatch downgrades a previously computed TRIMP to `null`/`UNAVAILABLE`. If the resync ever writes `modelTrimpSnapshotId` from the run identity rather than from `DailyTrimpComputer.resolveScoringIdentity`, every HR-less workout loses its TRIMP on the next daily pass — the exact class of churn that #290 addressed.
- Any future comparison between the resync snapshot id and the backup/display snapshot id would mismatch for most users.

This is therefore a **latent** correctness defect and a **present** duplicated-rule defect.

**Recommended remediation.**
1. Delete the duplicate. `:core:healthconnect` already depends on `:core:scoring` (`DailySyncUseCase` imports `RasSourceModeBootstrapUseCase` and `components.Phase`), so `HeartRateFormulas.resolveMaxHeartRate(prefs)` can be called directly from `prepareRequestedRun`. Remove `TANAKA_BASE`/`TANAKA_FACTOR`.
2. Add an architecture/unit test asserting that **every** production producer of `scoringSnapshotId` derives `resolvedHrMax` from `HeartRateFormulas.resolveMaxHeartRate` or from a stored frozen `hrMax`, so a third implementation cannot reappear.
3. See SCORE-102 for removing the field entirely, which makes the divergence structurally impossible.

**Migration consideration.** Changing the resync's `resolvedHrMax` changes `scoringSnapshotId`, which invalidates any **in-flight** `ResyncCheckpointStore` checkpoint for affected users — `HistoricalRunResolver` will treat the saved run as a different run and restart at the requested range start. That is the designed behavior for a changed scoring snapshot and is safe (the range is idempotent), but it must be called out in the release notes for the build that ships it, because an interrupted multi-year resync will restart rather than resume once.

**Acceptance criteria.**
1. `ResyncRangeUseCase` contains no arithmetic on `208`/`0.7`.
2. A parameterized test over ages 18…90 asserts `HistoricalRunIdentity.create(...).scoringSnapshotId == HistoricalRunIdentity.computeSnapshotId(prefs, HeartRateFormulas.resolveMaxHeartRate(prefs))` for `autoCalculateMaxHr = true` and `= false`.
3. `ABOUT.md`, `docs/about.md` and the in-app About strings are checked for an hrMax rounding statement and corrected if one exists (documentation-sync rule).

---

### SCORE-102 — `resolvedHrMax` is a redundant hashed field

| | |
|---|---|
| **Category** | Scoring / maintainability |
| **Severity** | Low |
| **Confidence** | High |
| **Status** | Confirmed — **adopted** (OD-4, decided 2026-09-25) |
| **Complexity** | M (protocol version bump) |
| **Migration risk** | Medium — stored checkpoints carry the hash; bundled with WP-08 so the reset is paid once |

**Affected files and symbols**
- `MODEL/domain/sync/ScoringRunSnapshot.kt:33` (`resolvedHrMax`), `:137-145` (`capturePart1` already captures `maxHeartRate` and `autoCalculateMaxHr`), `:157-165` (`capturePart3` already captures `age`).
- `MODEL/domain/sync/HistoricalRunIdentity.kt:35` — `CURRENT_PROTOCOL_VERSION = 3`.

**Current behavior.** `resolvedHrMax` is a pure function of three fields already inside the same snapshot. Its only effect on the hash is to embed whichever rounding the caller happened to apply.

**Remediation (adopted; lands inside WP-08, in the same commit series as SCORE-101).** Drop `resolvedHrMax` from `ScoringRunSnapshot` and bump `CURRENT_PROTOCOL_VERSION` to 4. `HistoricalRunResolver` already restarts a run whose snapshot does not match, and `ResyncCheckpointStoreImpl` already handles legacy/mismatched checkpoints by restarting cleanly, so the migration cost is one restarted in-flight resync per affected user. Backup manifests store the id as an opaque string and only round-trip it.

**Acceptance criteria.** `CURRENT_PROTOCOL_VERSION == 4`; `ScoringRunSnapshot` has no `resolvedHrMax`; `ResyncCheckpointStoreImpl`'s legacy-checkpoint test covers a stored v3 snapshot and asserts a clean restart; a restored v3 backup does not fail `RestoreInventoryValidator`; `HistoricalRunIdentity.create` and `computeSnapshotId` no longer take a `resolvedHrMax` argument, which removes the parameter SCORE-101's divergence travelled through.

**Why bundled with WP-08.** SCORE-101 alone already changes the resync's `scoringSnapshotId` and therefore resets in-flight checkpoints once. Shipping the field removal separately would reset them a second time for no additional user benefit.

---

### SCORE-103 — Two production-dead baseline implementations with different night membership

| | |
|---|---|
| **Category** | Scoring / duplicated business rule |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | Confirmed (maintainability; no live wrong value) |
| **Complexity** | S |
| **Migration risk** | None |

**Affected files and symbols**
- `SCORE/domain/scoring/BaselineComputer.kt:238-283` — `computeAdaptiveBaselineRhrBpm(dayMidnight, …)`: **no production caller**.
- `:291-318` — `computeHrvBaseline(dayMidnight, hrvBaselineOverride)`: **no production caller**.
- The live implementations: `:183-223` `computeAdaptiveBaselineRhrBpmBetween(...)` (called from `SCORE/domain/scoring/ResolveDailyBaselinesUseCase.kt:52` and `DB/data/repository/recommendation/MorningRecoveryLoader.kt:304`) and `:329-…` `computeHrvBaselineBetween(...)` (called from `DB/data/repository/FinalSummaryAssembler.kt:279` and `DB/data/repository/ReadinessSummaryCoordinator.kt:257`).

**Current behavior and the divergence.** The two HRV implementations do not agree on what a "night" is:

| | `computeHrvBaseline` (dead) | `computeHrvBaselineBetween` (live) |
|---|---|---|
| Night membership | `sleepDayAssembler.filterValidBaselineSessions(sessions)` — **session**-level | `buildHistoricalSleepDays(...).filter { it.canContributeToBaseline }` — **sleep-day**-level, after `SleepDayPolicy` core/supplemental merging |
| Per-night value | `hrvMap[sessionId].mean()` — mean of that **session's** raw RMSSD samples | `sleepDay.hrvMean` — the assembled sleep **day's** HRV mean |
| Aggregate | `median()` of session means, rounded | `median()` of day means, rounded |

For a user with biphasic or segmented sleep (multiple sessions per night, which `SleepDayPolicy` and `coreMergeGapMinutes` exist to merge), the two produce different medians from the same raw data. The RHR pair does **not** have this problem: both variants route through `historicalRhrWindow(...)`.

**Evidence.** Repository-wide search finds `computeHrvBaseline(` and `computeAdaptiveBaselineRhrBpm(` only in `BaselineComputer` itself and in tests.

**Root cause.** The `…Between` point-in-time variants were added for walk-forward correctness (WP-11/WP-22) and the originals were left as a "live/single-day path" that no longer exists.

**Impact.** No wrong value is produced today. The risk is (a) a future call site picking the dead variant and silently changing the documented "30-day median of nightly RMSSD averages" semantics for biphasic sleepers, and (b) tests pinning the dead semantics, which makes the live semantics look less covered than it is.

**Recommended remediation.** Delete both dead functions. Move any test that exercised them onto the `…Between` variants with the same fixtures, and keep an explicit biphasic fixture asserting that a two-session night contributes **one** value to the HRV baseline median. If a single-day convenience entry point is genuinely wanted, express it as `computeHrvBaselineBetween(dayMidnight, dayMidnight + 1d, …)` rather than a second implementation.

**Acceptance criteria.**
1. `BaselineComputer` exposes exactly one HRV-baseline and one RHR-baseline implementation.
2. A biphasic-sleep fixture (two core sessions separated by less than `coreMergeGapMinutes`) yields one contributed night, asserted directly.
3. `ABOUT.md` / `docs/about.md` / in-app About strings' description of the HRV baseline is confirmed to match the sleep-day semantics, and corrected if it describes per-session averaging (documentation-sync rule).

---

### SCORE-104 — `estimateMaxHr` truncates where the rest of the codebase rounds

| | |
|---|---|
| **Category** | Scoring / numerical |
| **Severity** | Low |
| **Confidence** | High |
| **Status** | **Decided (OD-3, 2026-09-25): keep truncation.** Documentation-only. |
| **Complexity** | S |
| **Migration risk** | **None** — behavior unchanged; no recompute triggered |

**Affected files and symbols**
- `SCORE/domain/util/HeartRateFormulas.kt:11` — `(208 - 0.7 * ageYears).toInt()`.
- `SCORE/domain/scoring/HrMaxProvider.kt:36` — `Math.round(HeartRateFormulas.resolveMaxHeartRate(prefs))` (rounds the already-truncated value — a no-op).
- `APP/domain/user/UserUseCase.kt` — `calculateMaxHeartRate(age)` path.

**Current behavior.** Age 35 → `208 − 24.5 = 183.5` → `183`. Rounding would give `184`. `hrMax` feeds `hrR = (hrAvg − rhr) / (hrMax − rhr)` in every TRIMP model and the Cheng branch's upper-zone normalization, so a 1 bpm difference shifts all training-load values slightly.

**Why this is not filed as a bug.** Tanaka et al. 2001 gives a continuous regression; neither truncation nor rounding is "the" correct discretization, and the app has shipped truncation. A ≤1 bpm discretization is well inside the formula's own confidence interval, while changing it would shift every auto-hrMax user's historical training load.

**Decision (OD-3, 2026-09-25): keep truncation.** No arithmetic changes. The defect is that the discretization is undocumented, so the remediation is documentation plus a pin test.

**Remediation.**
1. State the discretization explicitly wherever the Tanaka formula appears in user-facing copy: `ABOUT.md`, `docs/about.md`, and the relevant `about_*` string in `app/src/main/res/values/strings.xml` — e.g. "estimated as 208 − 0.7 × age, rounded down to a whole beat per minute".
2. Add a KDoc note on `HeartRateFormulas.estimateMaxHr` recording that truncation is deliberate and load-bearing, so a future contributor does not "fix" it to `roundToInt()`.
3. Pin it: `assertEquals(183, HeartRateFormulas.estimateMaxHr(35))` plus a case where `0.7 × age` is integral (`estimateMaxHr(30) == 187`).
4. `HrMaxProvider.getRoundedHrMax`'s `Math.round(...)` over an already-truncated value is a no-op; leave it (it is correct for the stored-`hrMax` branch) but note why in a comment.

**Acceptance criteria.** `ABOUT.md`, `docs/about.md`, the in-app `about_*` strings and the `domain/scoring/**DocumentationDriftTest*` fixtures state the discretization explicitly; the two pin assertions exist; **no production arithmetic changed** (assert by diff review — this package must not touch `estimateMaxHr`'s body).

---

### SCORE-105 — `updateBirthday` recomputes today before persisting the derived hrMax

| | |
|---|---|
| **Category** | Scoring / ordering |
| **Severity** | Low |
| **Confidence** | High |
| **Status** | Confirmed |
| **Complexity** | S |
| **Migration risk** | None |

**Affected files and symbols**
- `APP/domain/user/UserUseCase.kt:28-42` — `settingsRepo.updateBirthday(date)` → `computeAndPersistDailySummary(LocalDate.now(clock))` → *then* `settingsRepo.updateMaxHeartRate(maxHr)` when `autoCalculateMaxHr` → then `scheduleResyncWorker(recomputeOnly = true)`.

**Current behavior.** Today's summary is written with the new age but the **old** `maxHeartRate`. The queued recompute-only worker heals it, so the wrong value is transient — but it is displayed until the worker completes, and if the worker is cancelled the stale value persists until the next sync.

**Recommended remediation.** Persist every affected preference first, then recompute today, then enqueue the durable pass. Fold the recompute call into CACHE-102's `RecomputeTodayUseCase`.

**Acceptance criteria.** A test asserts the write order: birthday → maxHeartRate → recompute → enqueue.

---

### SCORE-106 — Zero-valued math fallbacks are safe only by call-site convention

| | |
|---|---|
| **Category** | Scoring / numerical robustness |
| **Severity** | Low |
| **Confidence** | High |
| **Status** | Maintainability concern |
| **Complexity** | S |
| **Migration risk** | None |

**Affected files and symbols**
- `SCORE/domain/util/MathUtils.kt:9,14,25,35,46` — `mean()`, `median()` (both overloads) and `stdev()` (both overloads) return `0f` for empty / size-`< 2` input; the `…OrNull()` variants return `null`.
- Call sites that currently guard correctly: `SCORE/domain/scoring/strategies/LoadScoringStrategy.kt:95-98` (`takeIf { it.size > 1 }?.stdev()?.takeIf { it > 0f } ?: (mu * 0.05f).coerceAtLeast(1f)`), `SCORE/domain/scoring/SleepBaselineMetrics.kt:95` (`.stdev().takeIf { it > 0f }`).
- Call site where the `0f` fallback is load-bearing: `LoadScoringStrategy.kt:41` — `w * lnHrvValues.stdev() + (1f - w) * sigmaPrior`, where `w` is clamped to 0 when the sample count is below `HRV_SIGMA_BLEND_MIN_N`, so `stdev()` returning `0f` is multiplied by `0f`. Correct, but only because of a coupling between two functions.

**Current behavior.** `stdev()` returning `0f` is a division-by-zero hazard for any z-score computed as `(x − mu) / sigma`. Every present call site guards it; nothing enforces that.

**Recommended remediation.** Deprecate the non-null `stdev()` overloads in favour of `stdevOrNull()`, forcing each call site to state its fallback explicitly (`LoadScoringStrategy.hrvSigma` becomes `?: 0f` with a comment explaining the `w == 0` coupling). Keep `mean()`/`median()` as-is — a `0f` mean/median is nonsensical but never a divisor.

**Acceptance criteria.** No production call to `List<Float>.stdev()` / `List<Int>.stdev()` remains; `Restoration.MIN_LN_SIGMA` clamping in `hrvSigma` is unchanged and asserted.

---

### ARCH-101 — A full SQLCipher re-encryption runs inside a Hilt provider

| | |
|---|---|
| **Category** | Architecture / startup |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | Confirmed |
| **Complexity** | M |
| **Migration risk** | Medium — touches the legacy plaintext→encrypted upgrade path |

**Affected files and symbols**
- `DB/di/DatabaseModule.kt:43-50` — `provideDatabase(...)` calls `sqlCipherKeyManager.migrateIfNeeded(dbFile)` and then `requireDatabaseReady(databaseReadinessGate)` before `Room.databaseBuilder(...)`.
- `DB/data/security/SqlCipherKeyManager.kt:281-330` — `migrateIfNeeded` opens a temp encrypted database, `ATTACH`es the plaintext file, runs `SELECT sqlcipher_export('main','plaintext')`, `DETACH`es, and `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`.
- `DB/di/DatabaseModule.kt:79-83` — `requireDatabaseReady` throws `IllegalStateException` when the external v7 migration has not completed.

**Current behavior.** The first injection of `HealthDatabase` — which can be triggered by any `@HiltViewModel` construction, i.e. on the **main thread** during the first frame — synchronously copies and re-encrypts the entire database, and can throw out of the provider.

**Evidence.** `provideDatabase` is `@Singleton` and unqualified; DAOs are provided from it in `DaoProvidersModule`, and ViewModels inject DAO-backed repositories. `DatabaseReadinessGate`, `V7DatabaseMigrator` and `DatabaseMigrationUiState` exist precisely to keep heavy migration work off this path, but the SQLCipher re-encryption did not join them.

**Impact.** For an existing user upgrading from the plaintext era with a multi-hundred-megabyte database, this is a main-thread `sqlcipher_export` of the whole file — a guaranteed ANR. It affects only that one-time upgrade, but that is exactly the launch where a crash is least recoverable. The `requireDatabaseReady` throw is a second, smaller instance of the same shape: a provider that throws produces a Hilt provision exception rather than a routed UI state.

**Recommended remediation.**
1. Move `migrateIfNeeded` behind the existing readiness gate: make it a step of the same off-main-thread migration flow that `DatabaseMigrationUiState` already surfaces, and have `DatabaseReadinessGate.inspect()` report "plaintext database present" as a not-ready state with its own UI.
2. Keep `requireDatabaseReady` in the provider as a **guard** (it should now be unreachable), but ensure every DB-touching entry point resolves `HealthDatabase` through `dagger.Lazy` after observing readiness — `HealthResyncWorker`, `DataCleanupWorker`, `DataRollupWorker` and `DatabaseReadyStartupInitializer` already do this; the ViewModel graph does not.
3. Add a debug-build `StrictMode` disk-read/write penalty assertion around first DB provision so a future regression is caught.

**Dependencies.** Must not change the v7 external-migration contract. Sequence after Phase 1 correctness work.

**Acceptance criteria.**
1. An instrumented test with a plaintext database file present shows `DatabaseReadiness` reporting not-ready and the migration running off the main thread, with `provideDatabase` never performing the export.
2. No ViewModel construction can provision `HealthDatabase` before readiness is `Ready` (assert via the existing `CleanArchTest` style, or a Hilt test).
3. `internal-docs/DATA_FLOW.md` §1.4's readiness-gate description is updated.

---

### ARCH-102 — Dashboard and Workouts ViewModels carry orchestration, loading and presentation together

| | |
|---|---|
| **Category** | Architecture / maintainability |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | Confirmed |
| **Complexity** | M |
| **Migration risk** | Low (no persisted state) |

**Affected files and symbols**
- `feature/dashboard/.../DashboardViewModel.kt` — 603 lines, **18** constructor dependencies, owns date validation, card management delegation, insight derivation, prompt generation, fatigue ticking and permission checks.
- `WORKOUTS/WorkoutsViewModel.kt` — 464 lines, **9** chained `combine` operators building `WorkoutsUiState`, plus `loadRecentWorkouts`, `loadWorkoutOnlyGains`, `loadWeeklyTraining` performing repository I/O inline.
- Supporting evidence of scale: `feature/dashboard/.../usecase/DashboardMetricPresentationFactory.kt` (665 lines), `WORKOUTS/WorkoutsStateFactory.kt` (556 lines), `WORKOUTS/WorkoutPerformanceCharts.kt` (815 lines — over the 800-line hard limit in `.claude/CLAUDE.md`).
- Detekt baselines record the accumulated debt: **278** suppressed issues across 15 modules — 121 `LongMethod`, 51 `TooManyFunctions`, 47 `ReturnCount`, 27 `CyclomaticComplexMethod`, 26 `LongParameterList`, 6 `LargeClass`.

**Current behavior.** Both ViewModels are the single assembly point for data loading, derived-state computation and UI-state shaping. They are already partly decomposed (delegates, state factories, presentation factories), so the remaining problem is boundary placement rather than absence of structure.

**Impact.** Maintainability and test surface, not correctness. `WorkoutPerformanceCharts.kt` at 815 lines violates the repository's own stated hard limit and should be split regardless.

**Recommended remediation (bounded — do not treat this as a modularization programme).**
1. Split `WorkoutPerformanceCharts.kt` below 800 lines (repo rule compliance).
2. Extract the three `loadX` repository-I/O functions out of `WorkoutsViewModel` into a `WorkoutsDataLoader` in the same feature module, so the ViewModel only combines already-loaded state. This is also the natural home for PERF-101's row budget.
3. Collapse the 9-stage `combine` chain (UI-101).
4. Leave `DashboardViewModel`'s dependency count alone unless a specific change needs it; 18 dependencies in a screen that genuinely aggregates 18 concerns is not by itself a defect.

**Acceptance criteria.** No production file exceeds 800 lines; `WorkoutsViewModel` performs no direct repository call; detekt baseline entries for the touched files are **removed**, not re-baselined (per the repo's boyscout rule).

---

### DI-101 — Backend paths read the system clock instead of the injected `Clock`

| | |
|---|---|
| **Category** | Dependency injection / determinism |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | Confirmed |
| **Complexity** | S |
| **Migration risk** | None |

**Affected files and symbols** (non-UI, behaviour-affecting)
- `APP/workers/HealthResyncWorker.kt:186` — `LocalDate.now(prefs.scoringZone())` in `discardExpiredDirtyRanges`.
- `APP/workers/HealthResyncWorker.kt:315` — `LocalDate.now(prefs.scoringZone())` in `coversRetainedHistory`, which decides whether the stored `scoringVersion` may be advanced.
- `APP/data/preferences/UserPreferencesMapperExtensions.kt:271` — `if (birthDate > LocalDate.now()) null else …` uses the **device** zone, not the scoring zone, to validate a stored birthday.
- `feature/vitals/.../cardio/CardioFitnessDetailViewModel.kt:76`, `WORKOUTS/WorkoutsViewModel.kt:186`, `feature/dashboard/.../DashboardFlowIntermediate.kt:104` — `LocalDate.now(zoneId)` with the correct zone but the ambient clock.

**Current behavior.** `Clock` is injected and available at all of these sites (`HealthResyncWorker` can take it; the ViewModels above already inject `Clock` elsewhere in the same module). The rest of the codebase is disciplined about this — `SelectedDateRepository`, `DateTransition`, `DateRangeService`, `BirthdayDateRule`, `SourcePayloadWriter`, `RoomHealthChangeIngestionStore`, `ForegroundSyncController` and `DailySyncUseCase` all use the injected clock.

**Impact.** `coversRetainedHistory` is the gate that decides whether a completed recompute may advance `scoringVersion`; testing it across a midnight or DST boundary requires the injected clock. `UserPreferencesMapperExtensions`'s device-zone comparison can reject a valid birthday for a user whose scoring zone is ahead of their device zone. UI default parameters (`VitalsViewModel`, `HeartRateUiModels`, `WorkoutsStateFactory`, `DateSwitcher`, `BirthdayDatePickerField`, `OnboardingScreen`) are **acceptable** — they are placeholder defaults immediately overwritten by state — and are explicitly out of scope.

**Recommended remediation.** Inject `Clock` into `HealthResyncWorker` (it is `@HiltWorker`, so this is one constructor parameter) and thread it into `discardExpiredDirtyRanges` / `coversRetainedHistory`. Route `UserPreferencesMapperExtensions`'s birthday check through the scoring zone. Pass the existing injected clock at the three ViewModel sites. Add a detekt or architecture test forbidding `LocalDate.now()` / `Instant.now()` / `System.currentTimeMillis()` in `:core:*` and in `APP/workers/**`, with an allow-list for the UI default-parameter cases and for genuinely wall-clock sinks (`SecureFileLogSink`, backup filenames, audit `occurredAt`).

**Acceptance criteria.** The forbidding test exists and passes; `coversRetainedHistory` has a test that crosses a DST boundary in a non-UTC scoring zone.

---

### UI-101 — `WorkoutsUiState` is rebuilt through nine chained `combine` stages

| | |
|---|---|
| **Category** | Compose / UI architecture |
| **Severity** | Medium |
| **Confidence** | Medium |
| **Status** | Suspected — needs recomposition measurement |
| **Complexity** | S |
| **Migration risk** | None |

**Affected files and symbols**
- `WORKOUTS/WorkoutsViewModel.kt:~230-287` — nine `.combine(...)` operators, each producing `state.copy(...)`, after `.flowOn(dispatchers.default)` and before `.stateIn(..., WhileSubscribed(5_000))`.

**Current behavior.** Each of the nine upstreams re-emits the entire `WorkoutsUiState` on any change, so a single `isSyncing` tick allocates nine successive copies of a state object that transitively holds the workout list and chart data. `DashboardViewModel` documents having already solved this ("the expensive transform is driven only by the data flows; realtime sync state is merged in afterwards via a cheap copy"); Workouts has not had the same treatment.

**Impact.** Allocation churn and — depending on the stability of the nested types — avoidable recomposition of the workouts list during a resync, which is exactly when `isSyncing`/`recalcProgress` tick frequently. The magnitude needs a Compose recomposition count or a macrobenchmark to state honestly.

**Recommended remediation.** Group the nine upstreams into two or three `combine` calls over small holder data classes (`WorkoutsChromeState`, `WorkoutsManagementState`), so the heavy data transform runs once and the cheap chrome merge runs once. Verify the nested state types are `@Immutable`/stable.

**Acceptance criteria.** Recomposition count for the workouts list during 30 s of simulated sync ticking, recorded before and after in `benchmark/BASELINE.md`; no change to emitted state values for a fixed input sequence.

---

### SEC-101 — Release diagnostics discard message text entirely

| | |
|---|---|
| **Category** | Security / privacy / operability |
| **Severity** | Low |
| **Confidence** | High |
| **Status** | Hardening opportunity — **not** a vulnerability. **Adopted** (OD-1, decided 2026-09-25) |
| **Complexity** | S |
| **Migration risk** | Low; the bounded-field constraint is what keeps the disclosure surface from growing |

**Affected files and symbols**
- `APP/util/SecureFileLogSink.kt:67-101` — `isLoggable` drops `DEBUG`; `log` discards `message` and emits only `safeDiagnostic(reason, throwable)`.
- `MODEL/domain/util/SafeDiagnosticFormatter.kt:20-41` — emits `reason.name`, each throwable's `javaClass.name`, and up to `MAX_FRAMES_PER_THROWABLE` frames.
- `APP/HealthDashboardApplication.kt:197-222` — debug installs a raw logcat sink; release installs `secureLogSink`.
- `app/src/main/AndroidManifest.xml` — `allowBackup="false"`, `dataExtractionRules`, `fullBackupContent`, `networkSecurityConfig` set; only `MainActivity`, `PrivacyRationaleActivity` and the `ViewPermissionUsageActivity` alias (permission-guarded) are exported; both providers are `exported="false"`.

**Assessment.** The privacy posture is sound and no confirmed vulnerability was found: no health values, record ids, device names, dates or counts reach either logcat or the encrypted file in release. The cost is operability — a field bug report contains a `DiagnosticReason` enum and stack frames with no context about which day, range or record type failed.

**Remediation (adopted, OD-1).** Introduce a typed, allow-listed structured field set that the formatter is permitted to emit:

```kotlin
// MODEL/domain/util/DiagnosticFields.kt (sketch)
data class DiagnosticFields(
    val dataType: HealthDataType? = null,      // enum
    val phase: ResyncPhase? = null,            // enum
    val dayOffsetFromToday: Int? = null,       // relative, never an absolute date
    val recordCount: CountBucket? = null,      // 0 / 1-10 / 11-100 / 100+
)
enum class CountBucket { NONE, FEW, DOZENS, MANY }
```

Every field is a bounded enum, a coarse bucket, or a relative integer offset — **never** a timestamp, an absolute date, a record id, a device or origin name, a health value, or a raw count. `DomainLogger`'s call sites pass fields explicitly; a site that passes nothing behaves exactly as today.

**Explicitly refused.** Free-form message text behind a per-tag allow-list. Tag allow-lists drift as tags are added, and the first formatted value someone interpolates leaks silently. The field set is the boundary precisely because it is enumerable and therefore testable.

**Why this is worth doing.** Measured against this plan's own findings: HC-102's stale workout currently surfaces as nothing at all, and CACHE-101's suppressed gate surfaces as `OPERATION_FAILED` with a `PhysiologyPreferences` frame — the actual consequence (dirty days never drained) is invisible. A user reporting "my training load looks too high" produces a report that cannot distinguish any cause in §4.

**Residual privacy consideration, recorded honestly.** Data type + phase + day-offset is, in aggregate across one shared report, a coarse behavioural fingerprint. It is not identifying and carries no health values, but it is more than zero. OD-1 accepted that trade in exchange for triageable field reports.

**Acceptance criteria.**
1. A test enumerates every field `SafeDiagnosticFormatter` can emit and asserts each is an enum constant, a `CountBucket`, a relative integer, or a class name — the test must fail if a `String` field is added.
2. No absolute date, timestamp, record id, device name or health value is reachable from any emittable field (assert by reflection over `DiagnosticFields`).
3. `docs/privacy.md` gains a section describing exactly what a diagnostic report contains, in user-readable terms.
4. Release logcat output is unchanged in shape — sanitized, one line per event.

---

### DOC-101 — `DATA_FLOW.md` cites moved paths and a retired invalidation bound

| | |
|---|---|
| **Category** | Documentation |
| **Severity** | Medium (the repo treats a stale `DATA_FLOW.md` as a broken build) |
| **Confidence** | High |
| **Status** | Confirmed |
| **Complexity** | S |
| **Migration risk** | None |

**Confirmed drift**
1. `internal-docs/DATA_FLOW.md:182` lists `DailyRecomputeSupport` at `core/healthconnect/src/main/kotlin/.../domain/sync/DailyRecomputeSupport.kt`. The file is at `core/database/src/main/kotlin/app/readylytics/health/core/database/domain/sync/DailyRecomputeSupport.kt` (see the import in `HC/domain/sync/DailySyncUseCase.kt:5`).
2. `:2407` lists `DatabaseReadinessGate` at `app/src/main/kotlin/app/readylytics/health/data/migration/DatabaseReadinessGate.kt`. The file is at `core/database/src/main/kotlin/app/readylytics/health/core/database/data/migration/DatabaseReadinessGate.kt`.
3. `:200` states `ScoreInvalidation.affectedRange` is "kept as the bound for any bounded recompute request via `WorkerScheduler.scheduleResyncWorker`'s `startDate`/`endDate`". No production caller invokes it (CACHE-103).
4. `:179` states the nine bulk reads are "each retried through this window's single shared `ReadRetryBudget`" without noting the inner per-page `retryWithBackoff` that also applies to them (HC-101). The HR/HRV nesting **is** documented; the bulk-read nesting is not.

**Recommended remediation.** Correct all four in the same change as the code fix each belongs to (per the repository's synchronous-update rule), not as a separate documentation pass. Add a cheap guard: a JVM test that extracts every `` `path/to/File.kt` `` token from `DATA_FLOW.md`'s component tables and asserts the file exists.

**Acceptance criteria.** The path-existence test exists and passes; the four statements above are corrected.

---

## 5. Scoring and Metric Verification Matrix

Reviewed against the implementation at `782dd99f`. "Verified" means the implemented arithmetic, units, bounds and window edges match the intent expressed in code documentation and in `ABOUT.md`/`internal-docs/DATA_FLOW.md` as far as static reading can establish; it is not a claim that the underlying science was re-derived.

| Metric / score | Implementation | Source inputs | Documented / intended rule | Implemented behavior | Review result | Findings | Required action |
|---|---|---|---|---|---|---|---|
| Max heart rate (auto) | `SCORE/domain/util/HeartRateFormulas.estimateMaxHr` | `prefs.age` | Tanaka 2001: `208 − 0.7·age` | `(208 − 0.7·age).toInt()` — truncates | Verified formula; discretization undocumented and duplicated | SCORE-101, SCORE-104 | Remove the duplicate in `ResyncRangeUseCase` (WP-08); **keep truncation** and document it (OD-3 decided) |
| Workout TRIMP (Banister) | `RasCalculator.calculateDailyTrimp`, `TrimpModel.BANISTER` | duration min, `hrAvg`, `rhrBaseline`, `hrMax`, `gender`, `banisterMultiplier` | `t · hrR · a · e^(b·hrR) · multiplier`, sex-specific `a`,`b` | As written; `hrR` clamped to `[0,1]`; returns `0f` when `hrMax ≤ rhr` or `hrAvg < rhr + MIN_HR_ABOVE_RHR_BPM` | **Verified** | — | None |
| Workout TRIMP (Cheng / LT) | same, `TrimpModel.CHENG` | + `ltBpm = prefs.zone3MaxBpm` | piecewise on HR vs LT, continuous at HR = LT with weight 0.5 | Lower branch `0.5·(hr−rhr)/max(lt−rhr,1)`; upper `0.5 + a·f·e^(β·f)`, `f` clamped `[0,1]` | **Verified**, including the continuity claim (both branches give 0.5 at HR = LT) | — | None. Note it ignores `banisterMultiplier` by design |
| Workout TRIMP (iTRIMP) | same, `TrimpModel.I_TRIMP` | + `itrimB` | Manzi 2009 exponential weighting, no sex factor | `t · hrR · e^(b·hrR)` | **Verified** | — | None |
| Per-workout TRIMP integration | `ComputeWorkoutTrimpUseCase.integrateSamples` | in-window HR samples | Riemann sum over sample-to-sample intervals plus a lead-in from workout start to first sample | Lead-in uses first sample's BPM; each sample covers `[t_i, t_{i+1})`, last covers `[t_n, workoutEnd)` | **Verified** — no double counting, no gap | — | None |
| Workout TRIMP without samples | `ComputeWorkoutTrimpUseCase.computeWithoutSamples` | `workoutAvgHr`, duration | single-interval TRIMP; zero when duration ≤ 0 | As written | **Verified** | — | None |
| Canonical workout reuse | `CanonicalWorkoutResolver.isMatchingPrior` | `sourceRevision`, `scoringSnapshotId`, `algorithmRevision` | reuse a prior TRIMP only when all three match | As written; reached only from `resolveMissingSamples` | Verified, but the snapshot-id producers diverge | SCORE-101 | Unify snapshot-id derivation |
| Everyday-HR load | `EverydayHeartRateLoadCalculator.calculate` | per-minute bucket means (hot ∪ warm), sleep/workout intervals | exclude buckets overlapping sleep or a workout; exclude zone 0; 1-minute TRIMP each | Half-open overlap `start < i.end && i.start < end`; `bucketIndex` range-checked against `(dayEnd−dayStart)/60000` so 23/25-hour DST days are handled | **Verified** | — | None |
| Load coverage confidence | same | counted minutes | NONE / LOW ≤179 / MEDIUM ≤479 / HIGH; valid ≥180 | As written | **Verified** | — | None |
| HRV z-score | `LoadScoringStrategy.computeHrvZScore` | current RMSSD, `muHistory`, `sigmaHistory`, `sigmaPrior`, frozen ln μ/σ, override | log-normal: `(ln(x) − μ) / σ`; frozen ≻ override ≻ history | As written; all inputs floored at `0.001` before `ln`; returns `null` when no baseline reference or RMSSD ≤ 0 | **Verified** | — | None |
| HRV σ blending | `LoadScoringStrategy.hrvSigma` | `ln` HRV history, `sigmaPrior` | blend sample σ with prior by sample count, floor at `MIN_LN_SIGMA` | `w = ((n − MIN_N)/(MAX_N − MIN_N)).coerceIn(0,1)`; `w·stdev + (1−w)·prior`, floored | **Verified**; safe only because `w = 0` when `stdev()` would return its `0f` fallback | SCORE-106 | Make the fallback explicit |
| HRV sub-score | `LoadScoringStrategy.computeHrvScore` | z | `50 + 25·z`, clamped `[0,100]`, with upper-tail saturation | As written; saturation applied only above `saturationZ` (deliberately asymmetric) | **Verified** | — | None |
| RHR z-score | `LoadScoringStrategy.computeRhrZScore` | current nocturnal RHR, RHR history, frozen σ, override | `(x − μ)/σ`; μ = override ≻ median(history); σ = frozen ≻ sample σ ≻ `max(0.05·μ, 1)` | As written; `null` when history empty and no override; elvis chain right-associates correctly | **Verified** | — | None |
| RHR baseline | `BaselineComputer.computeAdaptiveBaselineRhrBpmBetween` | sleep sessions in `[from − BASELINE_DAYS, to)` | median of per-sleep-day nadirs eligible via `historicalRhrWindow` | As written; frozen snapshot short-circuits; falls back to `DEFAULT_RHR_BPM` | **Verified**; a dead second implementation exists | SCORE-103 | Delete the dead variant |
| HRV baseline | `BaselineComputer.computeHrvBaselineBetween` | sleep sessions in the same window | median of nightly RMSSD means | median of **sleep-day** `hrvMean` where `canContributeToBaseline` | **Verified for the live path**; the dead variant uses **session**-level membership and raw-sample means — different result for biphasic sleep | SCORE-103 | Delete the dead variant; add a biphasic fixture |
| Readiness | `LoadScoringStrategy.computeReadinessScore` | `sRest`, sleep score, load score, flags | weighted sum, capped at `ILLNESS_MAX_SCORE` on `ILLNESS_ONSET`, clamped `[0,100]` | As written | **Verified** (weights live in `ScoringConstants.Readiness` and are covered by the documentation-drift tests) | — | None |
| Load score from strain ratio | `LoadScoringStrategy.computeLoadScore` | strain ratio | flat 100 inside the sweet spot, Gaussian decay above | `100·e^(−k·excess²)` clamped `[0,100]` | **Verified** | — | None |
| Night validation | `LoadScoringStrategy.validateNight` | RMSSD, sleep RHR, duration, deep/REM minutes | plausibility bounds; stage fractions invalid vs merely suspicious | As written; `durationMinutes > 0` guards both fraction divisions | **Verified** | — | None |
| Late nadir | `LoadScoringStrategy.isLateNadir` | min-HR timestamp, session start, duration | nadir occurs after `LATE_NADIR_THRESHOLD` of the session | As written; `false` when duration ≤ 0 | **Verified** | — | None |
| Sleep score | `ComputeSleepMetricsUseCase.computeSleepScore` → `ScoringCalculator.computeSleepScore` | duration, efficiency, deep/REM minutes, goal hours, `sRest`, age, targets, fragmentation, weight profile, regularity, hypersomnia ratio | Balanced default Duration 40 / Architecture 20 / Restoration 25 / Fragmentation 15, regularity multiplier 0.92–1.00 | Delegated to `ScoringCalculator` with the profile-selected weights | **Verified by delegation**; weights and the drift tests are the authority | — | None |
| Sleep RHR percentile | `SleepPercentileRhrCalculator` | per-session sleep HR samples | user `restingHrPercentile` over the night's samples | Median of historic RHRs for the baseline | **Verified** | — | None |
| Residual fatigue | `ResidualFatigueComputer` + `ComputeResidualFatigueUseCase` | workout impulses, half-life, gain | exponential decay accumulator advanced once per recomputed day | Walk-forward context prefetches retained history once (`buildWalkForwardFatigueContext`) and mutates in chronological order | **Verified structurally**; order dependence is intentional and the walk-forward enforces it | — | None |
| Daily RAS | `RasCalculator.calculateDailyRas` | daily TRIMP, scaling factor | `trimp · factor`, capped at `Ras.DAILY_CAP` | As written | **Verified** | — | None |
| VO₂ Max (Uth / Materko) | `UthVo2MaxCalculator`, `MaterkoAdaptedVo2MaxCalculator`, `Vo2MaxSourceResolver` | hrMax, RHR, wearable series | source mode selects wearable vs estimate | Wearable series prefetched once per walk-forward (`buildWalkForwardVo2MaxContext`) | Not re-derived; no defect found | — | None |
| Training stress balance | `TrainingStressBalanceCalculator` | acute/chronic series | ACWR / CTL-style balance | Consumes the batched TRIMP context | Not re-derived; no defect found | — | None |
| Recommendation examples | `SelectWorkoutRecommendationExamples`, `WorkoutExampleLoader` | 30-day workout lookback ending at wake time | eligibility bounded by `EXAMPLE_SELECTION_LOOKBACK_DAYS = 30` | `ScoreInvalidation.exampleFanOutRange` models the fan-out and is merged (never substituted) in `DailySyncUseCase.resolveInlineOldestTargetDay` | **Verified** | — | None |
| Warm-tier reconstruction | `WarmTierReconstructor`, `HrMinuteBucketEntity` p5/p25/p50/p75/p95 | minute buckets | piecewise-linear over 7 anchors (v15+); 3-point min/avg/max for legacy | As documented; drift bounds enforced by `WarmTierReconstructionPropertyTest` | **Verified as a deliberate product decision** (idempotent within a tier) | see §12 | Surface the tier boundary in docs, not code |

---

## 6. Health Connect Ingestion Matrix

Per record type, as implemented at `782dd99f` against connect-client **1.1.0**. "Resync" = `ResyncRangeUseCase` / `HealthIngestionCoordinator`; "Changes" = `HealthChangeSynchronizerImpl`.

| Record type | Read strategy | Paging / batching | Dedup key | Update behavior | Deletion behavior | Persistence target | Recalculation trigger | Performance risk | Findings |
|---|---|---|---|---|---|---|---|---|---|
| `HeartRateRecord` | Resync + daily: `readHeartRateSamplesPaged` (streamed, resumable page token). Changes: per-record upsert | Page-by-page; each page further sliced by `TRANSFORM_SAMPLE_BUDGET` samples; Room sub-batched at 500 rows/transaction | `(sourceRecordRef, timestampMs)` unique index; parent is `health_source_records.sourceRecordId` | Source-scoped authoritative replacement (`SourcePayloadWriter`); `conflictTargetedUpsert` updates BPM/type/session/device and suppresses no-op writes | Resync: staged anti-join prune, only when `scan_type_state == COMPLETE`. Changes: `deleteRecord` by id. Rollup quarantine may leave rows invisible rather than deleted | `heart_rate_records` (+ `hr_minute_buckets` after 90 d) | Dirty range via `SourcePayloadWriter.dependencyClosure`, then walk-forward | **Highest volume type.** Per-row upsert statements; per-record changes application | PERF-102, HC-101, HC-103 |
| `HeartRateVariabilityRmssdRecord` | Same as HR, `readHrvSamplesPaged` | Same; one RMSSD per record so slicing is by parent count | `(sourceRecordRef, timestampMs)` unique index | Same | Same | `hrv_records` | Same | Same shape, lower volume | PERF-102, HC-101 |
| `SleepSessionRecord` (+ stages) | Bulk `readAllPages`, one of the 9 concurrent reads. Chunks fetch one day past the chunk end for cross-midnight sessions | Whole window materialized | HC record `id` (`sleep_sessions.id` PK) | Delete-then-reinsert (changes) / upsert (resync); stages filtered to surviving session ids | Staged anti-join prune; changes delete by id | `sleep_sessions`, `sleep_stages` | Affected dates + dirty range | Bounded by human event frequency | HC-101, HC-105 |
| `ExerciseSessionRecord` | Bulk read + **one extra `readRecord` per session** for `exerciseRouteResult`; distance/elevation totals from two bulk interval reads | Whole window; route read is O(sessions) IPC | HC record `id` (`workout_records.id` PK) | **Update-in-place** via `persistPreparedWorkouts` (preserves `modelTrimp`, route points, distance, elevation) | Staged anti-join prune. **Changes path does not delete a de-selected-device record** | `workout_records`, `workout_route_points` | Affected dates + dirty range; metrics filled by `SessionLinkReconciler.recomputeWorkouts` | Per-session IPC; unconditional enrichment | **HC-102**, HC-106 |
| `ExerciseRoute` | Only via per-session `readRecord`; `ConsentRequired` mapped, never coerced to "absent" on transient failure | n/a | parent session id | Replaced with the session | Cascade with the workout row | `workout_route_points` | none (display only) | IPC per session | HC-106 |
| `StepsRecord` | Bulk `readAllPages` + `StepCountFetcher` (aggregate daily totals and per-device windows) | Whole window; `StepCountFetcher` has its own unbudgeted retries | HC record `id` | Delete-then-reinsert / upsert | Staged anti-join prune | `step_records`; day totals into `daily_summaries.stepCount` | `StepAttribution.resolve` per recomputed day | Can be minute-granular → unbounded chunk materialization | HC-105, HC-101 |
| `DistanceRecord` | Bulk via `IntervalTotalsReader`; **independent change token** (`IngestionTokenType.DISTANCE`) | Whole window | HC record `id` | `WorkoutEnrichmentRefresher.refreshForIntervalChanges` recomputes affected workouts | Interval deletion resolved against stored `getIntervalSource` bounds | `workout_records.totalDistanceMeters` | Affected dates from the refresher | Token lives in singleton mutable state; never rebaselined by resync | HC-104, HC-105 |
| `ElevationGainedRecord` | Same as Distance | Whole window | HC record `id` | Same | Same | `workout_records.elevationGainMeters` | Same | Same | HC-104, HC-105 |
| `WeightRecord` | Bulk read, device-filtered | Whole window | row id `"${hcId}_${timestampMs}"`, `sourceId` carried separately | Delete-then-reinsert / upsert | Staged anti-join prune on the composite row id | `weight_records` → `daily_summaries.weightKg` | Affected dates | Low volume | HC-101 |
| `BodyFatRecord` | Same | Whole window | `"${hcId}_${timestampMs}"` | Same | Same | `body_fat_records` | Same | Low | HC-101 |
| `BloodPressureRecord` | Same | Whole window | `"${hcId}_${timestampMs}"` | Same | Same | `blood_pressure_records` | Same | Low | HC-101 |
| `OxygenSaturationRecord` | Same | Whole window | `"${hcId}_${timestampMs}"` | Same | Same | `oxygen_saturation_records` → `avgSleepingSpo2` | Same | Low | HC-101 |
| `BodyTemperatureRecord` | Same | Whole window | `"${hcId}_${timestampMs}"` | Same | Same | `body_temperature_records` → `avgSleepingBodyTemp` | Same | Low | HC-101 |
| `Vo2MaxRecord` | Bulk read, permission-gated (`hasVo2MaxPermission()` → `ReadOutcome.Denied`), device-filtered before persist; `CompleteTypeScan` built from the **unfiltered** read | Whole window | HC record `id` | Delete-then-reinsert / upsert | Staged anti-join prune | `vo2max_records` | Walk-forward VO₂ Max context | Low | HC-101 |

**Cross-cutting ingestion properties verified.**
- **Permission vs empty data.** `ReadOutcome` distinguishes `Available` / `Denied` / `Unsupported`; a `Denied` type stages no ids and produces no `CompleteTypeScan`, so it can never be reconciled away. `applyPendingChanges` reads `getGrantedPermissions()` once and fails the whole sync rather than treating a lookup failure as "nothing granted".
- **Feature availability.** `HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY` and `FEATURE_READ_HEALTH_DATA_IN_BACKGROUND` are checked via `client.features.getFeatureStatus(...)`.
- **Token lifecycle.** Per-type tokens; revocation suspends (deletes) the type's token; a missing token escalates to full resync; `changesTokenExpired` escalates; a `SecurityException` on a still-granted type skips the run **without** suspending, so a transient background refusal cannot trigger a full resync.
- **Idempotency.** Every write path is an upsert on a stable key; deletions converge by staged-id anti-join, never `deleteAll()`. `clearFrozenBaselines(range)` is the only up-front mutation and is recomputed in the same pass.
- **Chunk-independence.** `SessionLinkReconciler.reconcile` runs once over the complete range after all chunks, re-deriving every `(recordType, sessionId)` via `SessionLinker.resolve` (sleep > workout > resting, tiebreak `(startTime, id)`), then recomputing affected workout metrics. Verified present in both `DailySyncUseCase` and `ResyncRangeUseCase`.
- **Timezone.** Day boundaries come from `ScoringRunContext.capture(prefs, instant)` → `RetentionBounds.resolveHistoricalWindow`, using the stored scoring zone, not the device zone. `HistoricalRunIdentity` carries `zoneId` and `ResyncExecutionPlan.create` asserts `runContext.zoneId.id == activeRun.zoneId`.
- **Process death / resumption.** `ResyncCheckpointStore` records phase, next date, immutable run identity, per-type completion and HR/HRV page tokens; a rejected page token replays the chunk from scratch with clean staging.

---

## 7. Large-Dataset Analysis

**Scenario (design target).** ≥1,000,000 `HeartRateRecord` samples within a 30-day period (≈23 samples/second sustained, i.e. a continuously-recording chest strap plus a watch); multi-year Health Connect history; retention disabled (`ABSOLUTE_MAX_DAYS` = 3650); repeated incremental sync; two or more contributing origins; partial updates to historical dates.

### 7.1 What already holds

| Property | Mechanism | Status |
|---|---|---|
| At most one HC page of HR/HRV in memory | `readHeartRateSamplesPaged` + `HeartSampleStreamer` | Holds |
| Transform buffer bounded by **samples**, not parent records | `sliceBySampleBudget(TRANSFORM_SAMPLE_BUDGET)` | Holds |
| Room writes sub-batched | `SourcePayloadWriter` `chunked(500)` per transaction | Holds for transactions, **not** for statements (PERF-102) |
| No heap-resident scanned-id set | `RoomScanStagingStore` → `scan_seen_ids` | Holds |
| Deletion reconciliation without bind-variable lists | anti-join `delete*NotStaged`, keyset `pageUnstagedAuthoritativeSources` | Holds |
| Rollup streams rather than materializes | `MinuteRollupStreamer` keyset paging + `index_hr_v10_timestamp_source` | Holds |
| Retention delete is keyset-batched | `deleteBeforeTimestampBatch(limit)` | Holds |
| Walk-forward fetches each lookback series once, not per day | `buildWalkForward{Trimp,Baseline,Fatigue,Vo2Max,Ras}Context` | Holds |
| Cancellation is cooperative | `ensureActive()` + `yield()` in every long loop | Holds |
| Per-day commit, not per-range | `inRecomputeTransaction` per day | Holds |

### 7.2 Bottlenecks, with expected complexity

| Path | Current complexity | Memory behavior | Expected bottleneck | Target | Findings |
|---|---|---|---|---|---|
| HC read → transform | O(n) time, O(page ∧ budget) memory | Bounded | Provider IPC and JSON/parcel cost; amplified up to 25× under quota pressure | Bounded retry: ≤ `maxAttempts` calls per window | HC-101 |
| Bulk type reads per chunk | O(m) time, **O(m) memory** where m = chunk cardinality of the densest bulk type | Unbounded in m | Steps/distance/elevation at minute granularity | Same page-bounded contract as HR/HRV | HC-105 |
| Room HR upsert | O(n) time, **n separate SQL statements** | Bounded | ~1M statement executions for the target dataset | ≤ n/100 statements; measured wall-time improvement recorded | PERF-102 |
| Deletion reconciliation | O(n) in SQL, set-based | Bounded | Full-table anti-join within the chunk window | Unchanged; verify index use under 1M rows | PERF-104 |
| Hot→warm rollup | O(n) streamed, day-chunked | Bounded | Per-minute aggregation + percentile sketch | Unchanged | — |
| Walk-forward recompute | O(days) queries, each slicing prefetched maps | O(retained days) for the contexts | For 3650 days: five prefetched series, each one entry per day — acceptable | Keep O(days), never O(samples) | — |
| Workout HR display | **O(span × density)** per cluster, unfiltered by type, plus a copy and an O(n log n) sort | **Unbounded** — ~1.5M rows at target density | Out-of-memory on the Workouts tab | Row-budgeted clusters; type-filtered query; O(n) merge | PERF-101, PERF-103 |
| Tier-visibility reads | O(n) rows × 1–2 index probes each, **no `LIMIT`** | Proportional to result set | Constant factor × row count | Keyset-paged variant for bulk consumers | PERF-104 |
| Changes application | O(changes) queries + O(changes) statements, unbounded page count | Bounded per page | Foreground stall after a long offline period | O(1) statements per page; bounded pages per run | HC-103 |

### 7.3 Measurable success criteria (no invented numbers)

The plan deliberately does **not** state target millisecond figures; `benchmark/BASELINE.md` is the place for measured values, and its own convention is append-a-dated-section. The criteria are:

1. **Bounded memory.** For a 1,000,000-row / 30-day fixture, peak retained heap during `ingestWindow` and during `fetchHeartRateSamplesByWorkout` is a function of the configured budgets, not of the fixture size. Demonstrated by running the same benchmark at 250k, 500k and 1,000,000 rows and showing peak heap does not scale with row count.
2. **No full-history materialization.** No production query returns more than its configured page/row budget. Demonstrated by a counting driver wrapper asserting max result-set size per query on the benchmark run.
3. **No ANR.** `StrictMode` disk-read/write violations on the main thread: zero during app start with a plaintext-era database present (ARCH-101) and during Workouts-tab navigation on the 1M fixture.
4. **Resumable synchronization.** Killing `HealthResyncWorker` at each of the four phases and restarting produces the same final `daily_summaries` contents as an uninterrupted run over the same fixture.
5. **Deterministic results.** Running `resyncRange` twice over an unchanged fixture produces byte-identical `daily_summaries` rows and zero `workout_records` updates on the second pass. Across the hot/warm boundary the weaker, documented guarantee applies (idempotent within a tier).
6. **Date-scoped recomputation.** A single changed HR source on day *D* results in a dirty range whose closure is `[D, today]` (per `dependencyClosure`) and in recomputation of exactly those days — not of the whole retention window.
7. **Index-supported queries.** `EXPLAIN QUERY PLAN` for `getVisibleByTimeRange`, `getVisibleByTypeAndTimeRange`, `pagePlausibleSamplesForRollup` and the new type-filtered workout read shows index use with no `SCAN heart_rate_records` and no `USE TEMP B-TREE FOR ORDER BY`.
8. **Worker completion.** A full 10-year resync over the 1M-per-30-day fixture either completes or checkpoints and resumes within WorkManager's constraints at `targetSdk 37`; a `Result.retry()` must never lose committed days.

---

## 8. Target Architecture

The target is the current architecture with five boundaries tightened. Nothing moves modules; no new module is created.

**1. One retry scope per read.** `HealthConnectRepository`'s read functions accept an optional `ReadRetryScope`. When present, the repository's page loop delegates every attempt to it; when absent, it keeps its own bounded policy for standalone callers. `ReadRetryBudget` implements `ReadRetryScope`. Consequence: the number of Health Connect calls for one ingest window is exactly bounded by one policy, and WorkManager's `EXPONENTIAL` backoff remains the only outer retry.

```kotlin
interface ReadRetryScope { suspend fun <T> execute(label: String, block: suspend () -> T): T }
internal class ReadRetryBudget(...) : ReadRetryScope     // unchanged semantics
```

**2. One page-bounded contract for every record type.** `readAllPagesStreaming` becomes the only read shape used by ingestion; callers stage ids and persist per page. `RawBulkRecords` shrinks to the genuinely low-cardinality session/vital types that HR tagging needs in full, and that exception is documented in the type, not in a comment.

**3. One mutation boundary.** `HealthMutationCoordinator.withMutation` wraps **every** write that mutates raw data or derived summaries, including settings-triggered single-day recomputes. Expressed as a `RecomputeTodayUseCase` in `:core:database` so `:feature:settings` and `APP/domain/user` do not reach past it:

```kotlin
// DB/domain/scoring/RecomputeTodayUseCase.kt (sketch)
class RecomputeTodayUseCase @Inject constructor(
    private val coordinator: HealthMutationCoordinator,
    private val scoringRepository: ScoringRepository,
    private val workerScheduler: WorkerScheduler,
    private val clock: Clock,
    private val settingsRepo: SettingsRepository,
) {
    /** Recomputes today under the shared mutation boundary; degrades to a durable
     *  bounded recompute when a maintenance operation holds the database. */
    suspend operator fun invoke(): RecomputeOutcome
}
```

**4. One publication protocol.** `DirtySummaryPublisher` exposes only `captureDay` / `publishDay`. `DirtyRangeEntity` is a plain persistence row with no constructor invariants; validation lives on the write path in `RoomDirtyRangeStore`, and `discardBefore` repairs any violating row. `ScoreInvalidation` exposes exactly one dependency-horizon function.

**5. One hrMax derivation.** `HeartRateFormulas.resolveMaxHeartRate(prefs)` (or a stored frozen `hrMax`) is the only source of `resolvedHrMax` anywhere, enforced by test.

**Unchanged and load-bearing.** Dependency direction (`:feature:*` → `:core:model` ports; `:core:scoring` and `:core:model` Android-free for logic); `RetentionBounds` as the single retention→date authority; `AuthoritativeHeartRateReader` as the single tier-visibility owner; `DailyRecomputeSupport.inRecomputeTransaction` as the single recompute-transaction opener; `ScoringRepository.computeDailySummary` as the single scoring entry; `@IoDispatcher` / `@DefaultDispatcher` / `@MainDispatcher` injection with `@Singleton` scope; ViewModels exposing `StateFlow`/`SharedFlow` only and Compose collecting with `collectAsStateWithLifecycle`.

**DI scopes after remediation.** `HealthDatabase` stays `@Singleton` but is provisioned only after `DatabaseReadiness.Ready`; every DB-touching entry point resolves it through `dagger.Lazy`. `HealthConnectClient`, `HealthConnectRepositoryImpl`, `HealthChangeSynchronizerImpl`, `HealthSyncUseCase`, `HealthIngestionCoordinator`, `ScoringRepositoryImpl`, `AuthoritativeHeartRateReader`, `DataRollupManager` and the stores remain `@Singleton`. `HealthChangeSynchronizerImpl` becomes stateless.

---

## 9. Phased Implementation Roadmap

Every phase lists the finding IDs it closes. Every Critical/High finding appears in Phase 1 or Phase 2.

### Phase 0 — Baseline and safety rails

**Objective.** Establish the measurements the later phases are judged against, and nothing else. No production behavior changes.

**Findings addressed.** None directly; enables PERF-101, PERF-102, PERF-104, UI-101, and the §7.3 criteria.

**Steps.**
1. Add a 1,000,000-row / 30-day heart-rate fixture generator to `:database-benchmark` (extend `HealthParentFixture` / `CurrentSchemaBenchmarkFixture`), parameterized at 250k / 500k / 1M so scaling can be shown.
2. Add a counting `SQLiteDriver`/`SupportSQLiteOpenHelper` wrapper used only by benchmarks that records statement count and maximum result-set size per query.
3. Record `EXPLAIN QUERY PLAN` for `getVisibleByTimeRange`, `getVisibleByTypeAndTimeRange`, `pagePlausibleSamplesForRollup` and `getKeysetPage` against the 1M fixture.
4. Record baseline wall time and peak heap for: `ingestWindow` over a dense 30-day chunk, `SourcePayloadWriter` HR upsert of 1M rows, `fetchHeartRateSamplesByWorkout` for a 10-workout page spanning 45 days, and a 365-day walk-forward recompute.
5. Append all of the above to `benchmark/BASELINE.md` as one new dated section (that file's stated convention is append-only).
6. Add the `DATA_FLOW.md` path-existence test from DOC-101 (it will fail on the two stale paths — fix those two lines in this phase so the guard lands green).

**Prerequisites.** None. **Schema/API changes.** None. **Rollback.** Delete the benchmark additions.
**Risks.** Benchmark fixtures at 1M rows are slow to build; generate once and cache the `.db` file.
**Completion criteria.** A dated `BASELINE.md` section exists with all four measurements at three fixture sizes, plus four recorded query plans; the path-existence test is green.

---

### Phase 1 — Correctness and data integrity

**Objective.** Close every confirmed defect that can produce wrong, missing or non-converging data. All changes are small and independently revertible.

**Findings addressed.** CACHE-101, HC-102, CACHE-102, SCORE-101, **SCORE-102**, SCORE-105, DB-101, DB-102, CACHE-103, SCORE-103, SCORE-106.

**Steps.**
1. **CACHE-101** — unnest the startup recompute gate; rename the boolean.
2. **HC-102** — restructure `processChangesPage`'s upsertion branch so device de-selection deletes for `EXERCISE` too.
3. **DB-102** — delete the dead ticket-scoped publication protocol (`publish(ticket, …)`, `rebindSnapshot`, `updateSnapshotId`), migrating the instrumented tests.
4. **DB-101** — remove `DirtyRangeEntity`'s `init` invariants; move them to `RoomDirtyRangeStore.append`; add the repair delete to `discardBefore`.
5. **CACHE-102 + SCORE-105** — introduce `RecomputeTodayUseCase` under `withMutation`, point `SleepSettingsViewModel`, `ThresholdSettingsViewModel` and `UserUseCase` at it, and fix the birthday write ordering. Verify the coordinator is reentrant.
6. **SCORE-101 + SCORE-102** — delete the duplicated Tanaka arithmetic in `ResyncRangeUseCase`; add the all-producers-agree test; then, in the same commit series, drop `resolvedHrMax` from `ScoringRunSnapshot` and bump `HistoricalRunIdentity.CURRENT_PROTOCOL_VERSION` to 4 (OD-4).
7. **SCORE-103** — delete `BaselineComputer.computeHrvBaseline` and `computeAdaptiveBaselineRhrBpm`; migrate their tests; add the biphasic HRV-baseline fixture.
8. **CACHE-103** — resolve `ScoreInvalidation` to one horizon function; keep the depth-guard test with corrected KDoc.
9. **SCORE-106** — deprecate the non-null `stdev()` overloads; make each call site's fallback explicit.
10. Synchronously update `internal-docs/DATA_FLOW.md` for items 2, 3, 4, 5, 6, 7, 8 (DOC-101 items 3 and 4 land here).

**Prerequisites.** Phase 0 item 6 (the doc guard) so doc edits are verified.
**Schema/API changes.** No Room schema change. `DirtyRangeDao.updateSnapshotId` is removed — a DAO method deletion; the `scoringSnapshotId` **column** stays. `HistoricalRunIdentity.CURRENT_PROTOCOL_VERSION` goes 3→4 and `ScoringRunSnapshot` loses a field — a DataStore-persisted checkpoint protocol change, not a database migration.
**Migration strategy.** SCORE-101 and SCORE-102 together change the resync's `scoringSnapshotId` once, which invalidates in-flight resync checkpoints once. `HistoricalRunResolver` already handles a mismatched saved run by restarting at the requested range start, and the range is idempotent — no data loss, one restarted resync for users who happen to be mid-run at upgrade.
**Rollback strategy.** Every step is a self-contained revert. Nothing writes a new persisted shape, so a downgrade is safe.
**Risks.** Step 5 could deadlock if `HealthMutationCoordinator` is not reentrant — verify before wiring, and add the reentrancy test first.
**Required validation.** New unit tests per finding's acceptance criteria; `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest`, then `./gradlew lintRelease`; the dirty-drain instrumented tests.
**Completion criteria.** Every Phase 1 finding's acceptance criteria met; no new detekt issue and no new baseline entry.

---

### Phase 2 — Health Connect and database scalability

**Objective.** Make ingestion and the workout display path bounded by configured budgets rather than by dataset size.

**Findings addressed.** HC-101, PERF-102, PERF-101, PERF-103, HC-103, HC-105, HC-106, PERF-104, HC-104.

**Steps.**
1. **HC-101** — add `ReadRetryScope` to the `HealthConnectRepository` port; thread `params.retryBudget` from `HealthIngestionCoordinator` and `HeartSampleStreamer`; decide `StepCountFetcher`'s scope explicitly.
2. **PERF-102** — multi-row upsert for `HeartRateDao` and `HrvDao`, chunked at 100 rows, preserving the conflict target and the no-op suppression predicate.
3. **PERF-101 + PERF-103** — row-budgeted workout clustering; type-filtered tier-merged read (OD-2 approved); linear two-pointer merge in `mergedSamples()`.
4. **PERF-104** — record query plans on the 1M fixture; add keyset-paged tier-visible reads for bulk consumers if the measurement warrants it. Do **not** add a generated column without evidence.
5. **HC-103** — batch the changes page (single affected-dates query, single delete, single `HealthIngestionBatch`) and bound it with its own `DEFAULT_CHANGES_APPLY_BUDGET_MS` + `MAX_CHANGE_PAGES_PER_RUN` (OD-6), sized from WP-01's `CHANGES-1K` measurement.
6. **HC-105** — stage-during-read; move `STEPS`, `DISTANCE`, `ELEVATION_GAINED` onto the streaming read path; add max-bulk-list telemetry.
7. **HC-106** — skip per-session route reads when nothing can have changed and when route consent is absent.
8. **HC-104** — fold interval tokens into `HealthChangeSyncOutcome` / `commitTokens`; capture interval baselines in the resync; delete the singleton field.
9. Update `internal-docs/DATA_FLOW.md` §1.1, §1.2 (`HealthIngestionCoordinator`, `HistoricalIngestPhase`, `HealthChangeSynchronizerImpl` rows) and the exercise-route paragraph in the same commits.

**Prerequisites.** Phase 0 (measurements), Phase 1 (so correctness fixes are not confounded with perf changes).
**Schema/API changes.** `HealthConnectRepository` port gains an optional parameter (source-compatible). New DAO methods; **no** Room schema version bump unless PERF-104 step 4's generated-column option is chosen — in which case it becomes v23→v24 with a backfill over `heart_rate_records`, which is out of scope for this phase and would need its own work package.
**Migration strategy.** None required; all changes are read/write-shape changes over an unchanged schema.
**Rollback strategy.** Each step reverts independently. Step 2 is the only one that changes how rows are written; keep the per-row `upsertAll` implementation behind the same function name so a revert is one commit.
**Risks.** Step 2 must not silently lose the `WHERE` suppression predicate (would inflate WAL and dirty rows on every re-ingest). Step 3's type filter is a semantic narrowing approved by OD-2 and must be guarded by the display-vs-persisted TRIMP equality test, not merely by element-identity on already-correct fixtures. Step 6 changes staging order — deletion reconciliation equality must be asserted on a fixture.
**Required validation.** §7.3 criteria 1, 2, 5, 7; the full pre-commit gate; instrumented deletion-reconciliation and resume tests.
**Completion criteria.** Peak heap independent of fixture size for the two bounded paths; statement count for a 1M ingest reduced by ≥50×; a new dated `BASELINE.md` section with after-numbers next to Phase 0's before-numbers.

---

### Phase 3 — Architecture and dependency ownership

**Objective.** Remove the two remaining ownership violations.

**Findings addressed.** ARCH-101, DI-101.

**Steps.**
1. **ARCH-101** — move `SqlCipherKeyManager.migrateIfNeeded` behind `DatabaseReadinessGate`; extend `DatabaseReadiness` with a "plaintext database present" state and surface it through the existing `DatabaseMigrationUiState`; keep `requireDatabaseReady` as an unreachable guard; ensure ViewModel-reachable graph entries resolve `HealthDatabase` lazily; add the debug `StrictMode` assertion.
2. **DI-101** — inject `Clock` into `HealthResyncWorker`; route the birthday validation through the scoring zone; pass the injected clock at the three ViewModel sites; add the forbidding architecture test with its documented allow-list.

**Prerequisites.** Phases 1–2.
**Schema/API changes.** `DatabaseReadiness` gains an enum case — a pure in-memory type; no persisted representation changes.
**Migration strategy.** The plaintext→encrypted upgrade must remain resumable: if the process dies mid-export, the temp file is discarded and the plaintext original is untouched (the current `Files.move` is already atomic and last). Preserve that ordering exactly.
**Rollback strategy.** Step 1 is the riskiest change in the plan because it touches the one-time upgrade path for legacy installs. Ship it alone, behind no flag but with a dedicated instrumented test matrix (plaintext present / already encrypted / interrupted mid-export / v5,v6,v7 external-migration states).
**Risks.** A regression here bricks launch for legacy users. Do not combine with any other change in the same release.
**Required validation.** The four-state instrumented matrix above; §7.3 criterion 3.
**Completion criteria.** No disk I/O in `provideDatabase`; the forbidding clock test passes.

---

### Phase 4 — Incremental recalculation and performance polish

**Objective.** Close the remaining invalidation and efficiency items once the model has one shape.

**Findings addressed.** residual work from CACHE-103 and HC-103; targeted repair of HC-102's already-stale data.

**Steps.**
1. **Bounded repair pass for HC-102.** Users upgrading past the HC-102 fix may already hold stale workouts from de-selected devices. Add a one-shot, retention-bounded prune driven by `SelectedSourcePrunerImpl` over `workout_records` only, enqueued once via `WorkerScheduler.scheduleResyncWorker(recomputeOnly = true, startDate, endDate)` with a dedicated `RecalcTrigger`. This is the one place `ScoreInvalidation.affectedRange` could legitimately be used if the team keeps it (CACHE-103).
2. **Verify no invalidation storm.** Assert that a single changed source produces exactly one dirty ticket and that its closure is `[changedDay, today]`, not a per-day fan-out.
3. **Flow invalidation.** Confirm `observeVisibleByTimeRange`'s `distinctUntilChanged` still suppresses redundant emissions after PERF-103's merge change, and that `observeSleepSession` deliberately bypasses it (it must, so warm-bucket invalidations are not swallowed — this is already documented and must not regress).

**Prerequisites.** Phases 1–3.
**Schema/API changes.** None.
**Migration strategy.** The repair pass runs once per install, gated by a preference flag, and is idempotent.
**Rollback strategy.** Remove the enqueue; the flag stays unset.
**Risks.** The repair pass must be bounded by retention and must not widen into a full resync (that is the regression #292 fixed).
**Completion criteria.** §7.3 criterion 6 demonstrated; one repair enqueue per install, never repeating.

---

### Phase 5 — Compose and long-term maintainability

**Objective.** Only structural UI work and the repository's own file-size and detekt rules.

**Findings addressed.** ARCH-102, UI-101, SCORE-104 (documentation-only, OD-3), SEC-101 (adopted, OD-1). SCORE-102 moved to Phase 1 under OD-4.

**Steps.**
1. Split `WORKOUTS/WorkoutPerformanceCharts.kt` (815 lines) below the 800-line hard limit.
2. Extract `WorkoutsDataLoader` from `WorkoutsViewModel`; collapse the nine-stage `combine` chain into two or three stages over holder types (UI-101).
3. Burn down detekt baseline entries for every file touched in Phases 1–5 (boyscout rule) and record the remaining count; do not re-baseline anything.
4. SCORE-104 (OD-3 decided: **keep truncation**): documentation-only — state the discretization in `ABOUT.md`, `docs/about.md` and the in-app `about_*` string, add the `estimateMaxHr` KDoc note and the two pin assertions. **No arithmetic change**, so this step carries no recompute and no release-note risk.
5. SEC-101 (OD-1 adopted): add the bounded `DiagnosticFields`, the reflection test that forbids a `String` field, and the `docs/privacy.md` report-contents section. (SCORE-102 already landed in Phase 1 with WP-08 per OD-4.)

**Prerequisites.** Phases 1–4.
**Schema/API changes.** None — the protocol bump moved to Phase 1 (OD-4).
**Rollback strategy.** All steps independently revertible.
**Risks.** None materially; SCORE-104 is documentation-only under OD-3, so no historical score changes.
**Completion criteria.** No file over 800 lines; detekt baseline count for touched modules strictly decreased; documentation-drift tests green.

---

## 10. Ordered Work Packages

Each package is one reviewable commit (or a tight series). `GATE` below means the mandatory pre-commit sequence from `.claude/CLAUDE.md`:

```bash
./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest
# and, once all coding tasks in the package are done:
./gradlew lintRelease
```

New or deleted files require `codegraph index`; structural moves require `codegraph sync`.

---

### WP-01 — Benchmark fixtures, counting driver, and recorded baselines
- **Purpose.** Produce the before-numbers every performance package is judged against.
- **Findings.** Enables PERF-101/102/104, UI-101, §7.3.
- **Files.** `database-benchmark/src/main/kotlin/.../{HealthParentFixture,CurrentSchemaBenchmarkFixture,HealthPipelineBaselineBenchmark,ScoringWalkForwardBenchmark}.kt`; new `CountingDriverWrapper.kt`; `benchmark/BASELINE.md`.
- **Sequence.** Parameterized fixture generator (250k/500k/1M) → counting driver → run the four measurements → capture four query plans → measure `CHANGES-1K` (sizing input for OD-6's `DEFAULT_CHANGES_APPLY_BUDGET_MS`) → re-run `dashboardVitalsTabSwitch` and `hotStart` and replace their stale SQLCipher blocker note (OD-5) → append one dated `BASELINE.md` section.
- **Acceptance.** Dated section present with all measurements at three sizes; fixture build is cached so reruns are practical; `CHANGES-1K` recorded; the two previously-blocked journeys carry real numbers or a newly-evidenced blocker.
- **Validation.** `./gradlew :database-benchmark:assembleDebug`; `./gradlew :benchmark:connectedBenchmarkAndroidTest`; the benchmark runs themselves; `GATE`.
- **Depends on.** —

### WP-02 — `DATA_FLOW.md` path guard and the two stale paths
- **Purpose.** Make documentation drift a build failure before any doc-touching work starts.
- **Findings.** DOC-101 (items 1, 2).
- **Files.** new `app/src/test/kotlin/.../DataFlowPathReferenceTest.kt` (or `:core:model` if it needs no Android); `internal-docs/DATA_FLOW.md` lines ~182 and ~2407.
- **Sequence.** Write the extractor+assertion → observe two failures → correct the two paths → green.
- **Acceptance.** Every `` `…/*.kt` `` token in the component tables resolves to an existing file.
- **Validation.** `GATE`.
- **Depends on.** —

### WP-03 — Unnest the startup recompute gate
- **Purpose.** Stop an unrelated migration failure from suppressing every recompute gate.
- **Findings.** CACHE-101.
- **Files.** `APP/DatabaseReadyStartupInitializer.kt`; `app/src/test/kotlin/.../DatabaseReadyStartupInitializerTest.kt`.
- **Sequence.** Add the failing test (throwing `PhysiologyPreferences` fake + pending ticket → expect enqueue) → unnest and rename → green.
- **Acceptance.** CACHE-101's three criteria.
- **Validation.** `GATE`.
- **Depends on.** —

### WP-04 — Delete the dead dirty-ticket publication protocol
- **Purpose.** One publication protocol, so later invalidation edits have one shape.
- **Findings.** DB-102.
- **Files.** `DB/data/repository/DirtySummaryPublisher.kt`; `DB/data/local/RoomDirtyRangeStore.kt`; `SCHEMA/data/local/dao/DirtyRangeDao.kt`; `core/database/src/androidTest/.../DirtyMutationRecoveryInstrumentedTest.kt`.
- **Sequence.** Migrate instrumented tests onto `captureDay`/`publishDay` → delete `publish(ticket, …)` overloads, `rebindSnapshot`, `updateSnapshotId` → document `scoringSnapshotId` as diagnostic-only.
- **Acceptance.** DB-102's criteria; instrumented coverage of advance / generation fencing / completion delete / retention trim retained.
- **Validation.** `GATE` + `./gradlew :core:database:connectedDebugAndroidTest`.
- **Depends on.** —

### WP-05 — Move dirty-range invariants off the read path
- **Purpose.** A malformed journal row must not crash every scoring read.
- **Findings.** DB-101.
- **Files.** `SCHEMA/data/local/entity/DirtyRangeEntity.kt`; `DB/data/local/RoomDirtyRangeStore.kt`; `SCHEMA/data/local/dao/DirtyRangeDao.kt`; instrumented test.
- **Sequence.** Instrumented test inserting a violating row via raw SQL (fails today) → remove `init` → add write-path `require`s → add repair delete to `discardBefore` → green.
- **Acceptance.** DB-101's three criteria.
- **Validation.** `GATE` + connected tests.
- **Depends on.** WP-04.

### WP-06 — Delete a de-selected-device workout on the changes path
- **Purpose.** Make pull-to-refresh converge on source-selection changes for `EXERCISE`.
- **Findings.** HC-102.
- **Files.** `HC/data/healthconnect/HealthChangeSynchronizerImpl.kt` (`processChangesPage`); `core/healthconnect/src/test/kotlin/.../HealthChangeSynchronizerImplTest.kt`; `internal-docs/DATA_FLOW.md` (`HealthChangeSynchronizerImpl` row).
- **Sequence.** Failing test for the de-selected `EXERCISE` case → restructure the branch → verify the matching-device update-in-place tests stay green → doc update.
- **Acceptance.** HC-102's criteria 1–3.
- **Validation.** `GATE`.
- **Depends on.** —

### WP-07 — `RecomputeTodayUseCase` under the mutation boundary
- **Purpose.** Serialize settings-triggered recomputes against durable runs; handle maintenance windows.
- **Findings.** CACHE-102, SCORE-105.
- **Files.** new `DB/domain/scoring/RecomputeTodayUseCase.kt`; `DB/di/DatabaseRepositoryModule.kt`; `SETTINGS/SleepSettingsViewModel.kt`; `SETTINGS/ThresholdSettingsViewModel.kt`; `APP/domain/user/UserUseCase.kt`; `DB/data/local/HealthMutationCoordinatorImpl.kt` (reentrancy).
- **Sequence.** Reentrancy test for the coordinator → add the use case → repoint the three call sites → fix `updateBirthday` ordering → `MAINTENANCE_PENDING` degradation test.
- **Acceptance.** CACHE-102 criteria 1–3; SCORE-105's ordering assertion.
- **Validation.** `GATE`; `codegraph index` (new file).
- **Depends on.** WP-04, WP-05.

### WP-08 — One hrMax derivation, and remove the redundant hashed field
- **Purpose.** Remove the duplicated Tanaka formula and its divergent rounding, then remove the field that made the divergence reachable. Bundled per **OD-4** so the checkpoint reset is paid once.
- **Findings.** SCORE-101, SCORE-102.
- **Files.** `HC/domain/sync/ResyncRangeUseCase.kt`; `MODEL/domain/sync/ScoringRunSnapshot.kt`; `MODEL/domain/sync/HistoricalRunIdentity.kt`; `APP/data/backup/BackupSnapshotExporter.kt`; `DB/data/repository/DailyTrimpComputer.kt`; `SCORE/domain/scoring/GetWorkoutDisplayMetricsUseCase.kt`; `APP/data/preferences/ResyncCheckpointStoreImpl.kt` + its proto; new parameterized test in `:core:model`; `internal-docs/DATA_FLOW.md` (`HealthSyncUseCase` / checkpoint identity text).
- **Sequence.** Two commits, one series:
  1. *(SCORE-101)* Parameterized age test asserting all producers agree (fails today) → call `HeartRateFormulas.resolveMaxHeartRate` in `prepareRequestedRun` → delete `TANAKA_BASE`/`TANAKA_FACTOR` → add the all-producers architecture assertion.
  2. *(SCORE-102)* Drop `resolvedHrMax` from `ScoringRunSnapshot`; remove the parameter from `HistoricalRunIdentity.create` / `computeSnapshotId` and from the four call sites; bump `CURRENT_PROTOCOL_VERSION` to 4 → extend the `ResyncCheckpointStoreImpl` legacy-checkpoint test with a stored v3 snapshot asserting a clean restart → assert a v3 backup archive still passes `RestoreInventoryValidator` (the id is an opaque round-tripped string).
- **Acceptance.** SCORE-101's criteria 1–3 **and** SCORE-102's acceptance criteria.
- **Validation.** `GATE`; `./gradlew :app:testDebugUnitTest --tests '*ResyncCheckpointStore*'`; restore-validation tests.
- **Release note (Release A).** In-flight resync checkpoints reset **once**; an interrupted multi-year resync restarts at the range start instead of resuming. No data loss — the range is idempotent. See §12.
- **Depends on.** —

### WP-09 — Single baseline implementation
- **Purpose.** Remove two dead baseline functions whose night membership differs from the live ones.
- **Findings.** SCORE-103.
- **Files.** `SCORE/domain/scoring/BaselineComputer.kt`; `core/scoring/src/test/kotlin/.../BaselineComputerTest.kt`; `ABOUT.md` / `docs/about.md` / `app/src/main/res/values/strings.xml` if the HRV-baseline wording describes per-session averaging.
- **Sequence.** Migrate tests onto the `…Between` variants → add the biphasic fixture → delete the dead functions → verify the documentation-drift tests.
- **Acceptance.** SCORE-103's criteria 1–3.
- **Validation.** `GATE`; `./gradlew :core:scoring:testDebugUnitTest --tests '*DocumentationDrift*'`.
- **Depends on.** —

### WP-10 — One invalidation horizon; explicit math fallbacks
- **Purpose.** Remove the retired 84-day horizon from the live API; stop `stdev()`'s silent `0f`.
- **Findings.** CACHE-103, SCORE-106.
- **Files.** `MODEL/domain/sync/ScoreInvalidation.kt`; `SCORE/domain/util/MathUtils.kt`; `SCORE/domain/scoring/strategies/LoadScoringStrategy.kt`; `SCORE/domain/scoring/SleepBaselineMetrics.kt`; `internal-docs/DATA_FLOW.md:200`.
- **Sequence.** Decide `affectedRange`'s fate with WP-17 (keep only if the repair pass uses it) → update KDoc and the depth-guard test's comment → deprecate non-null `stdev()` and make each fallback explicit.
- **Acceptance.** CACHE-103 and SCORE-106 criteria.
- **Validation.** `GATE`.
- **Depends on.** WP-03 (so the invalidation model is edited once).

### WP-11 — One retry scope per Health Connect read
- **Purpose.** Bound Health Connect calls per ingest window to one policy.
- **Findings.** HC-101; DOC-101 item 4.
- **Files.** `MODEL/domain/repository/HealthConnectRepository.kt`; `HC/data/healthconnect/HealthConnectRepositoryImpl.kt`; `HC/domain/sync/{ReadRetryBudget,RetryWithBackoff,HealthIngestionCoordinator,HeartSampleStreamer,StepCountFetcher}.kt`; `internal-docs/DATA_FLOW.md` §1.1 and the `HealthIngestionCoordinator` row.
- **Sequence.** Call-counting fake client test (fails today: expects ≤5, observes up to 25) → add `ReadRetryScope` to the port → thread the budget → remove the now-redundant outer `retryBudget.execute` wrappers where the scope is passed → decide and document `StepCountFetcher`'s scope → doc update.
- **Acceptance.** HC-101's criteria 1–4.
- **Validation.** `GATE`.
- **Depends on.** WP-01 (so retry changes are measured).

### WP-12 — Multi-row heart-rate and HRV upserts
- **Purpose.** Collapse ~1M statements into ~10k.
- **Findings.** PERF-102.
- **Files.** `SCHEMA/data/local/dao/HeartRateDao.kt`; `SCHEMA/data/local/dao/HrvDao.kt`; `DB/data/local/SourcePayloadWriter.kt`; `database-benchmark/.../HealthPipelineBaselineBenchmark.kt`; `benchmark/BASELINE.md`.
- **Sequence.** Equivalence test (new/unchanged/changed rows, `rowId` preservation) → `@RawQuery` multi-row upsert chunked at 100 rows → keep the suppression predicate verbatim → statement-count assertion via WP-01's counting driver → record after-numbers.
- **Acceptance.** PERF-102's criteria 1–4.
- **Validation.** `GATE` + `./gradlew :core:database:connectedDebugAndroidTest` + the benchmark.
- **Risk.** Bind-variable limit on `minSdk 26` — 100 rows × 6 binds = 600, under the 999 floor.
- **Depends on.** WP-01.

### WP-13 — Bound the workout heart-rate fetch
- **Purpose.** Remove the only confirmed unbounded materialization on a display path.
- **Findings.** PERF-101, PERF-103.
- **Files.** `WORKOUTS/WorkoutHeartRateBatcher.kt`; `WORKOUTS/WorkoutsViewModel.kt`; `DB/data/local/AuthoritativeHeartRateReader.kt`; `DB/data/repository/HeartRateRepositoryImpl.kt`; `MODEL/domain/repository/HeartRateRepository.kt`; `SCHEMA/data/local/dao/HeartRateDao.kt`; tests in `feature/workouts` and `core/database`.
- **Sequence.** (OD-2 approved — proceed.) Linear two-pointer merge in `mergedSamples()` with element-identity test → add `rangeInOfType` + `getByTimeRangeOfType` → add the display-vs-persisted TRIMP equality test over a fixture containing a non-`EXERCISE`-tagged sample inside a workout window → narrow `fetchHeartRateSamplesByWorkout` to `EXERCISE` → replace the 45-day span guard with a `countInRange`-driven row budget → benchmark at 1M rows.
- **Acceptance.** PERF-101 criteria 1–4 (including the OD-2 agreement guard); PERF-103's identity and allocation criteria.
- **Validation.** `GATE` + connected tests + benchmark; record the query plan.
- **Depends on.** WP-01.

### WP-14 — Batch and bound the changes page
- **Purpose.** Make foreground change application O(1) statements per page and bounded per run.
- **Findings.** HC-103.
- **Files.** `HC/data/healthconnect/HealthChangeSynchronizerImpl.kt`; `DB/data/local/RoomHealthChangeIngestionStore.kt`; `MODEL/domain/sync/HealthChangeSyncOutcome.kt`; `HC/domain/sync/DailySyncUseCase.kt`; **`HC/domain/sync/SyncConstants.kt`** (new `DEFAULT_CHANGES_APPLY_BUDGET_MS` + `MAX_CHANGE_PAGES_PER_RUN`); `internal-docs/DATA_FLOW.md`.
- **Sequence.** Counting-fake test for a 1,000-change page → batched affected-dates and delete → single `HealthIngestionBatch` per page → add the two constants, sized from WP-01's `CHANGES-1K` number (OD-6) → budget exhaustion returns a continuation outcome and escalates via the existing `REQUIRES_HISTORICAL_RESYNC` path → idempotency test.
- **Acceptance.** HC-103's criteria 1–3; budget exhaustion produces no new failure mode and no uncommitted-but-tokenized page.
- **Validation.** `GATE`.
- **Depends on.** WP-01 (budget sizing), WP-06, WP-12.

### WP-15 — Page-bounded bulk reads and stage-during-read
- **Purpose.** Extend the "one page in memory" contract to every record type that can be dense.
- **Findings.** HC-105.
- **Files.** `HC/data/healthconnect/{HealthConnectRepositoryImpl,StepRecordReader,IntervalTotalsReader}.kt`; `HC/domain/sync/HealthIngestionCoordinator.kt`; `MODEL/domain/repository/HealthConnectRepository.kt`; `internal-docs/DATA_FLOW.md`.
- **Sequence.** Allocation-tracked benchmark with 43,200 `StepsRecord`s → paged reads for `STEPS`/`DISTANCE`/`ELEVATION_GAINED` → stage ids per page → assert staged-id-set equality against the pre-change fixture → keep sessions materialized and document that exception in the type.
- **Acceptance.** HC-105's criteria 1–3.
- **Validation.** `GATE` + benchmark + deletion-reconciliation equality test.
- **Depends on.** WP-11.

### WP-16 — Conditional exercise-route enrichment
- **Purpose.** Stop paying one IPC per session per resync.
- **Findings.** HC-106.
- **Files.** `HC/data/healthconnect/{HealthConnectRepositoryImpl,WorkoutReadPreparer}.kt`; `SCHEMA/data/local/dao/WorkoutDao.kt`; `internal-docs/DATA_FLOW.md`.
- **Sequence.** Bulk query for stored `routeState` + input revision by session id → skip predicate → consent gate → tests for "unchanged skips", "consent newly granted enriches once", "transient failure still propagates".
- **Acceptance.** HC-106's criteria 1–3.
- **Validation.** `GATE`.
- **Depends on.** WP-15.

### WP-17 — Interval tokens in the outcome; one-shot workout repair
- **Purpose.** Remove the singleton token field; rebaseline interval tokens after a resync; repair workouts already stranded by HC-102.
- **Findings.** HC-104; repair for HC-102; possibly CACHE-103's `affectedRange` consumer.
- **Files.** `HC/data/healthconnect/HealthChangeSynchronizerImpl.kt`; `MODEL/domain/sync/HealthChangeSyncOutcome.kt`; `HC/domain/sync/{DailySyncUseCase,ResyncRangeUseCase}.kt`; `DB/data/local/SelectedSourcePrunerImpl.kt`; `APP/workers/` + `WorkerScheduler`; `MODEL/domain/sync/RecalcTrigger.kt`.
- **Sequence.** Move interval tokens into the outcome and delete the field → capture interval baselines in `captureChangesTokens` → add the flag-gated, retention-bounded, workouts-only repair enqueue with its own `RecalcTrigger`.
- **Acceptance.** HC-104's criteria 1–3; the repair runs exactly once per install and never widens to a full resync.
- **Validation.** `GATE`.
- **Depends on.** WP-06, WP-14.

### WP-18 — Tier-visibility query plans and optional paging
- **Purpose.** Convert PERF-104 from suspected to measured, and page bulk consumers if warranted.
- **Findings.** PERF-104.
- **Files.** `SCHEMA/data/local/dao/HeartRateDao.kt`; `DB/data/local/AuthoritativeHeartRateReader.kt`; `benchmark/BASELINE.md`.
- **Sequence.** Record plans on the 1M fixture → if per-row probes dominate, add `getVisiblePageAfter(...)` mirroring `getKeysetPage` and move bulk consumers onto it → re-record.
- **Acceptance.** PERF-104's criteria; **explicitly no schema change** in this package.
- **Validation.** `GATE` + benchmark.
- **Depends on.** WP-13.

### WP-19 — Database provisioning off the main thread
- **Purpose.** Remove a full SQLCipher re-encryption from a Hilt provider.
- **Findings.** ARCH-101.
- **Files.** `DB/di/DatabaseModule.kt`; `DB/data/security/SqlCipherKeyManager.kt`; `DB/data/migration/DatabaseReadinessGate.kt`; `MODEL/domain/migration/DatabaseReadiness.kt`; `APP/domain/migration/DatabaseMigrationUiState.kt`; `APP/data/migration/V7DatabaseMigrator.kt`; instrumented tests.
- **Sequence.** Four-state instrumented matrix (plaintext / encrypted / interrupted export / v5,v6,v7 external migration) → add the readiness state → move the export → lazy DB resolution in the ViewModel-reachable graph → debug `StrictMode` assertion.
- **Acceptance.** ARCH-101's criteria 1–3.
- **Validation.** `GATE` + `./gradlew :core:database:connectedDebugAndroidTest` + a manual legacy-upgrade check on a device. The existing cross-process key-race tests (`SqlCipherKeyManagerCrossProcessRaceTest`, `SqlCipherKeyManagerTest`) must stay green — this package moves the *migration* out of the provider and must not disturb the `FileLock`/`ReentrantLock` critical section that closed the key race (OD-5).
- **Ship alone.** No other change in the same release.
- **Depends on.** Phases 1–2 complete.

### WP-20 — Injected clock in backend paths
- **Purpose.** Make retention/version gates testable across midnight and DST.
- **Findings.** DI-101.
- **Files.** `APP/workers/HealthResyncWorker.kt`; `APP/data/preferences/UserPreferencesMapperExtensions.kt`; `feature/vitals/.../CardioFitnessDetailViewModel.kt`; `WORKOUTS/WorkoutsViewModel.kt`; `feature/dashboard/.../DashboardFlowIntermediate.kt`; new architecture test.
- **Sequence.** Add the forbidding test with its allow-list (fails) → inject and thread `Clock` → DST test for `coversRetainedHistory` → green.
- **Acceptance.** DI-101's criteria.
- **Validation.** `GATE`.
- **Depends on.** WP-19.

### WP-21 — Workouts UI structure
- **Purpose.** Repo file-size compliance and a cheaper state pipeline.
- **Findings.** ARCH-102, UI-101.
- **Files.** `WORKOUTS/WorkoutPerformanceCharts.kt` (split); new `WORKOUTS/WorkoutsDataLoader.kt`; `WORKOUTS/WorkoutsViewModel.kt`; `feature/workouts/detekt-baseline.xml`.
- **Sequence.** Split the charts file → extract the loader → collapse the combine chain into holder types → verify stability annotations → measure recomposition before/after → remove the touched files' baseline entries.
- **Acceptance.** ARCH-102's criteria; UI-101's criteria.
- **Validation.** `GATE`; `codegraph index` (new files) and `codegraph sync` (structural move).
- **Depends on.** WP-13.

### WP-22 — Decision-gated items and final documentation reconciliation
- **Purpose.** Land the documentation-only OD-3 outcome, the adopted OD-1 diagnostic fields, and close DOC-101. (OD-4's outcome already shipped in WP-08; OD-5 needed no work beyond WP-01's re-run.)
- **Findings.** SCORE-104 (documentation-only), SEC-101 (adopted), DOC-101.
- **Files.** `ABOUT.md`, `docs/about.md`, `app/src/main/res/values/strings.xml`, `SCORE/domain/util/HeartRateFormulas.kt` (KDoc only), `internal-docs/DATA_FLOW.md`, `docs/privacy.md`, new `MODEL/domain/util/DiagnosticFields.kt`, `MODEL/domain/util/SafeDiagnosticFormatter.kt`, `APP/util/SecureFileLogSink.kt`.
- **Sequence.** SCORE-104: document the truncation in `ABOUT.md` → mirror into `docs/about.md` → mirror into the in-app `about_*` string → add the `estimateMaxHr` KDoc note and the two pin assertions → confirm `estimateMaxHr`'s body is untouched by diff review. Then SEC-101: add `DiagnosticFields` with enum/bucket/relative-offset fields only → thread it through `SafeDiagnosticFormatter` → add the reflection test that fails on any `String` field → write the `docs/privacy.md` report-contents section. Then the final `DATA_FLOW.md` reconciliation and the four-point documentation review checklist.
- **Acceptance.** The Definition of Done in §15.
- **Validation.** `GATE` + `./gradlew lintRelease` + `./gradlew :core:scoring:testDebugUnitTest --tests '*DocumentationDrift*'`.
- **Depends on.** All prior packages; OD-1 resolved (OD-3 and OD-4 already decided).

---

## 11. Performance Validation Plan

All measurements append a new dated section to `benchmark/BASELINE.md` (that file's own stated convention: "Do not overwrite this entry — instead, append a new dated section"). No target numbers are asserted in this plan; WP-01 establishes them.

**Fixtures.**
- `HR-1M-30D` — 1,000,000 `heart_rate_records` across 30 days, two source origins, sleep sessions on each night, 12 workouts, generated at 250k/500k/1M scale points.
- `HR-MULTIYEAR` — 3,650 days of sparse data (≈1 sample/2 min) with a dense final 30 days, to exercise the retention/rollup/walk-forward interaction.
- `STEPS-DENSE-30D` — 43,200 minute-granular `StepsRecord`s in one 30-day chunk (HC-105).
- `CHANGES-1K` — a single Changes page with 1,000 upsertions and 200 deletions across HR, sleep and exercise (HC-103).
- `BIPHASIC-60D` — 60 nights, half with two core sessions inside `coreMergeGapMinutes` (SCORE-103).

**Measurements.**

| # | What | Harness | Recorded |
|---|---|---|---|
| 1 | Health Connect read + transform for one dense 30-day chunk | `:database-benchmark` `HealthPipelineBaselineBenchmark` with a fake paged client | wall time, peak heap, HC call count |
| 2 | Room HR/HRV upsert of 1M rows | `HealthPipelineBaselineBenchmark` + WP-01 counting driver | wall time, **statement count**, WAL growth |
| 3 | Re-ingest of identical data | same | row-update count (must be 0) |
| 4 | Heart-rate aggregation: `getVisibleMinuteBuckets`, `sleepProjectionForSessions`, `minBpmInRange` | `:database-benchmark` | wall time, query plan |
| 5 | `fetchHeartRateSamplesByWorkout` for a 10-workout page spanning 45 days | `:database-benchmark` (pure-JVM repository against a Room fixture) | peak heap, max result-set size, wall time |
| 6 | Incremental recomputation: one changed HR source on day *D* | `ScoringWalkForwardBenchmark` | days recomputed (must equal the closure size), wall time |
| 7 | Full historical rebuild over `HR-MULTIYEAR` | `ScoringWalkForwardBenchmark` | wall time, peak heap, checkpoint count |
| 8 | Interrupted-sync recovery | instrumented: kill at INGEST / PRUNE / RECONCILE / RECOMPUTE, restart | final `daily_summaries` equality vs an uninterrupted run |
| 9 | Memory scaling | measurements 1, 2, 5 at 250k/500k/1M | peak heap must be flat, not linear |
| 10 | Background worker completion | instrumented `HealthResyncWorker` over `HR-MULTIYEAR` | completion or resumable checkpoint; no lost committed days |
| 11 | Compose: Workouts list recomposition during 30 s of simulated sync ticking | `:benchmark` macrobenchmark + recomposition counting | recomposition count before/after WP-21 |
| 12 | Cold start with a plaintext-era database present | `:benchmark` `StartupBenchmark` + `StrictMode` | main-thread disk violations (must be 0 after WP-19) |

**Pre-existing gap to close — stale documentation, not an open defect (OD-5).** `benchmark/BASELINE.md:17, 38, 46` still records `dashboardVitalsTabSwitch` and `hotStart` as *pending, blocked by an SQLCipher key race on tab navigation during clean-install runs*. That blocker no longer exists. `internal-docs/plans/KNOWN_ISSUE_sqlcipher_multiprocess_key_race.md` was deleted in `dfc58507` ("cleanup", #183) **as completed work**, alongside the finished `PERFORMANCE_OPTIMIZATION_PLAN.md`; its own status line reads "Fixed", and it records the verification: a Robolectric 8-thread convergence test, a genuine two-OS-process instrumented test, and a manual fresh-install repro, 4/4 on a physical Samsung SM-A576B. The fix is present in current code — `DB/data/security/SqlCipherKeyManager.kt` holds a static `ReentrantLock` plus a cross-process advisory `FileLock` around both `getOrCreateDbKey()` and `validateKeyDecryption()`, with a durable `commit()` key write — and both tests still exist (`app/src/androidTest/.../SqlCipherKeyManagerCrossProcessRaceTest.kt`, `app/src/debug/.../racetest/KeyRaceTestService.kt`, `core/database/src/test/.../SqlCipherKeyManagerTest.kt`). **Action:** WP-01 re-runs the two journeys and replaces the stale blocker note. If a run does fail, that is a *new* regression to investigate on its own evidence — not a continuation of this one.

---

## 12. Migration and Compatibility Risks

**Room migrations.** This plan proposes **no** schema version bump in Phases 0–4. Schema v23 is unchanged by WP-01…WP-21. Two optional items would change persisted shapes and are explicitly gated:
- PERF-104's generated bucket column (v23→v24) — deferred behind measurement in WP-18; if adopted it requires a backfill over `heart_rate_records`, the largest table, and must be its own work package with its own resumability story.
- SCORE-102's `ScoringRunSnapshot` field removal — a `HistoricalRunIdentity.CURRENT_PROTOCOL_VERSION` bump (3→4), not a Room migration. **OD-4 adopted it and bundled it into WP-08**, so it ships in Release A together with SCORE-101 and the two share a single checkpoint reset.

**Existing user data.** No package deletes or rewrites user rows except WP-17's one-shot repair, which deletes only `workout_records` (and cascaded route points) belonging to a de-selected device — i.e. rows the user has already excluded by configuration. It is retention-bounded and flag-gated.

**Stale synchronization tokens.** WP-08 changes the resync's `scoringSnapshotId` (SCORE-101) and, in the same series, removes a hashed snapshot field and bumps the checkpoint protocol to 4 (SCORE-102, per OD-4). `HistoricalRunResolver.resolve` treats a checkpoint whose `scoringSnapshotId` differs as a different run and restarts at the requested range start; `ResyncCheckpointStoreImpl` already restarts cleanly on legacy/mismatched checkpoints. **Consequence:** a user who is mid-resync at the moment of upgrade loses their resume position **once** and re-runs the range — bundling the two changes is precisely what keeps it to once rather than twice. The range is idempotent, so no data is lost, but the run takes longer that one time. This must appear in the release note for Release A. Backup archives are unaffected: `scoringSnapshotId` is stored and validated as an opaque string. WP-17 changes interval-token handling; the worst case there is one extra interval-change replay.

**Partial migrations and interrupted workers.** Every long-running path already checkpoints (resync phases, rollup day-chunks, retention keyset batches, dirty-ticket cursors). WP-19 is the one package that touches a non-checkpointed migration — the plaintext→encrypted export. Its existing ordering (write temp → `sqlcipher_export` → atomic `Files.move`) is crash-safe and must be preserved verbatim; a crash before the move leaves the plaintext original intact.

**Score changes after bug fixes.** Two packages can change displayed values:
- **WP-06 + WP-17** remove workouts from de-selected devices. Training load, strain and RAS will **decrease** for affected days. This is the correct value; it will look like a regression to a user who has been seeing double-counted load. Surface it in the release note.
- **SCORE-104 is no longer a score-changing item.** OD-3 selected truncation — the already-shipped behavior — so WP-22 is documentation-only: no `hrMax` change, no TRIMP shift, no forced recompute, no release note.
- WP-09 and WP-13's type filter must **not** change values — they are covered by element-identity and drift tests precisely so a silent change is caught.

**Backward compatibility.** `HealthConnectRepository` gains an optional parameter (source-compatible). No public API leaves the app. Backup archive schema is untouched (`BackupInventoryPolicy.requiredTables` unchanged); a v22 archive still restores.

**Rollback feasibility.** Phases 0–2 and 4–5 are revertible commit-by-commit with no persisted-state consequences. Phase 3 (WP-19) is the exception: once a user's database has been re-encrypted off the main thread, a revert is still safe (the old code detects an already-encrypted file and returns early at the magic-header check), but a revert would restore the ANR risk for users who have not yet upgraded.

**Release sequencing.**
1. Release A — WP-01…WP-10 (Phase 0 + Phase 1, including the WP-08 checkpoint-protocol bump). Low risk. Release note: resync resume position resets once; de-selected-device workouts now removed, so training load may decrease on affected days.
2. Release B — WP-11…WP-18 (Phase 2). Medium risk, heavy benchmark gating.
3. Release C — WP-19 **alone**. Highest risk; legacy-upgrade path.
4. Release D — WP-20…WP-22 (Phases 3 tail, 4, 5) plus the decision-gated items.

---

## 13. Documentation Updates

Per `.claude/CLAUDE.md`, each of these must land **in the same change** as the code it describes — a stale `internal-docs/DATA_FLOW.md` is treated as a broken build.

| Document | What must change | Work packages |
|---|---|---|
| `internal-docs/DATA_FLOW.md` §1.1 (retry) | Replace the "individual raw HC page fetches are retried via `retryWithBackoff` … (unchanged by HC-005/PERF-001)" text with the single `ReadRetryScope` description | WP-11 |
| `internal-docs/DATA_FLOW.md` §1.2 component tables | Correct `DailyRecomputeSupport` and `DatabaseReadinessGate` paths; update `HealthChangeSynchronizerImpl`, `HealthIngestionCoordinator`, `HistoricalIngestPhase`, `HealthSyncUseCase`, `DataRollupWorker`/`DataCleanupWorker` rows for every behavioral change | WP-02, WP-06, WP-11, WP-14, WP-15, WP-16, WP-17 |
| `internal-docs/DATA_FLOW.md` `ScoreInvalidation` row | Remove the claim that `affectedRange` bounds `scheduleResyncWorker` requests, or add the caller | WP-10, WP-17 |
| `internal-docs/DATA_FLOW.md` §1.4 (storage/readiness) | New `DatabaseReadiness` state for a plaintext database; provider no longer migrates | WP-19 |
| `internal-docs/DATA_FLOW.md` §2.x (scoring) | Single baseline implementation; single hrMax derivation; explicit `stdev` fallbacks | WP-08, WP-09, WP-10 |
| `internal-docs/DATA_FLOW.md` exercise-route paragraph | Conditional enrichment skip rule | WP-16 |
| `ABOUT.md` | hrMax discretization statement — **"208 − 0.7 × age, rounded down"** (OD-3 decided: keep truncation); HRV-baseline night semantics if the current wording describes per-session averaging | WP-09, WP-22 |
| `docs/about.md` | Must match `ABOUT.md` exactly (documentation review checklist item 2) | WP-09, WP-22 |
| In-app `about_*` / `tooltip_*` strings in `app/src/main/res/values/strings.xml` | Must agree with `ABOUT.md` (checklist item 3); all user-facing strings stay in `strings.xml` | WP-09, WP-22 |
| `docs/privacy.md` | **Required** (SEC-101 adopted, OD-1): a section describing exactly what a diagnostic report contains, in user-readable terms | WP-22 |
| `docs/index.md`, `docs/backup-and-data.md` | No change expected — this plan alters no collection, retention, sharing, telemetry, backup behavior or contact detail. Re-confirm at WP-22. | WP-22 |
| `benchmark/BASELINE.md` | One appended dated section per measured package (WP-01, WP-12, WP-13, WP-15, WP-18, WP-21) | several |
| `.github/ISSUE_TEMPLATE/*.md` ↔ `report_email_*_template` strings | No change expected; re-confirm at WP-22 | WP-22 |

**Documentation review checklist (from `.claude/CLAUDE.md`) to run before approving WP-09 and WP-22:** (1) implementation matches `ABOUT.md`; (2) `docs/about.md` matches `ABOUT.md`; (3) the in-app About page, tooltips and onboarding scoring explanations agree with `ABOUT.md`; (4) `domain/scoring/**DocumentationDriftTest*` pass.

---

## 14. Open Decisions

This section originally listed the decisions that could not be resolved from repository evidence. **All six were closed on 2026-09-25** — five by the repository owner, and OD-5 by evidence that had not been found at the time of the first draft. Each is retained with its rationale and its outcome so the reasoning stays on record; **nothing in this plan is blocked on an unmade decision.**

### OD-1 — Should release diagnostics carry structured, allow-listed context?
- **Why it matters.** Today a release diagnostic contains a `DiagnosticReason` enum plus class names and stack frames — no day, range, record type or phase. A field bug report is close to unactionable, which raises the cost of every future defect in this pipeline. Adding context necessarily widens what leaves the device when a user shares a report.
- **Options.** (a) Keep as-is. (b) Add a typed `DiagnosticFields` with bounded enums and coarse buckets only (`HealthDataType?`, `ResyncPhase?`, day-offset-from-today, a count bucket). (c) Add free-form message text for a subset of tags.
- **Recommended default.** (b). It is the only option that improves triage without reintroducing free-form strings, and it is testable (every emittable field is an enum or a bucket). (c) is not recommended — a tag allow-list drifts and eventually leaks a formatted value.
- **DECIDED 2026-09-25 — option (b): adopt bounded `DiagnosticFields`; (c) stays refused.** Fields are limited to bounded enums, a coarse `CountBucket`, and a *relative* day offset; never an absolute date, timestamp, record id, device or origin name, health value, or raw count. The constraint is enforced by a reflection test over `DiagnosticFields` that fails if a `String` field is ever added, so the boundary cannot erode silently.
- **Trade accepted, on the record.** Data type + phase + day offset is a coarse behavioural fingerprint in aggregate within one shared report. It carries no health values and is not identifying, but it is more than the current zero. The decision accepts that in exchange for field reports that can distinguish the causes in §4 — today HC-102 surfaces as nothing at all and CACHE-101 surfaces as an undifferentiated `OPERATION_FAILED`.
- **Affects.** SEC-101 (now in scope, not optional), WP-22, `docs/privacy.md`.

### OD-2 — May the workout heart-rate read be narrowed to `EXERCISE`-tagged samples?
- **Why it matters.** PERF-101's cheapest and largest win is filtering the query by `recordType`. `SessionLinkReconcilerImpl.recomputeWorkouts` already filters to `RecordType.EXERCISE` when computing persisted workout metrics, and `HealthChangeSynchronizerImpl.upsertExercise` uses `changeIngestionStore.heartRateSamplesForMetrics(RecordType.EXERCISE.name, …)`. But `GetWorkoutDisplayMetricsUseCase` currently receives **all** types in the window via `getByTimeRange`. If a provider ever tags a sample inside a workout window as `RESTING` (e.g. a session-straddling sample not yet reconciled), display and persisted TRIMP would diverge after the narrowing.
- **What must be decided.** Whether display metrics are contractually the same input set as persisted metrics.
- **Recommended default.** **Yes, narrow it** — the two should agree by definition, and the reconcile pass exists to make the tagging authoritative.
- **DECIDED 2026-09-25 — narrow it.** Display metrics and persisted metrics are contractually the same input set. WP-13 ships the narrowing together with a test asserting `GetWorkoutDisplayMetricsUseCase` and `DailyTrimpComputer` produce the same TRIMP for a fixture containing a sample inside a workout window still tagged `RESTING`. A divergence in that test is a bug in `SessionLinkReconciler`, and must be fixed there — never by widening the read back to all record types.
- **Affects.** PERF-101 (criterion 4), WP-13. **Unblocked.**

### OD-3 — Truncate or round the Tanaka hrMax?
- **Why it matters.** `estimateMaxHr` truncates (`(208 − 0.7·age).toInt()`), so ~90% of auto-hrMax users get an hrMax 0.1–0.9 bpm below the regression value. `hrMax` is the denominator of `hrR` in every TRIMP model. Changing it changes every historical load value after the next full recompute; not changing it leaves an undocumented discretization in a documented formula.
- **Options.** (a) Keep truncation, document it explicitly. (b) Switch to `roundToInt()`, ship with a forced full-history recompute and a release note.
- **Recommended default.** **(a)**. Readylytics' documented rule is preserving established scoring behavior; a ≤1 bpm discretization is well inside the formula's own confidence interval, and the cost of (b) — every user's historical training load shifting — is not justified by the accuracy gain.
- **DECIDED 2026-09-25 — option (a): keep truncation.** No arithmetic changes anywhere. SCORE-104 becomes documentation-only: state the discretization in `ABOUT.md`, `docs/about.md` and the in-app `about_*` string, add a KDoc note on `estimateMaxHr` recording that truncation is deliberate so a future contributor does not "fix" it, and pin `estimateMaxHr(35) == 183` and `estimateMaxHr(30) == 187`.
- **Affects.** SCORE-104, WP-22. **No score change, no forced recompute, no release note.**

### OD-4 — Remove `resolvedHrMax` from `ScoringRunSnapshot` (protocol v3→v4)?
- **Why it matters.** The field is derivable from three other fields in the same snapshot; keeping it is the structural reason SCORE-101 was possible. Removing it costs one protocol bump and one restarted in-flight resync per affected user — on top of the restart WP-08 already causes.
- **Options.** (a) Keep the field; SCORE-101's fix plus the all-producers test is sufficient. (b) Remove it and bump to v4, in the **same release** as WP-08 so users pay the checkpoint reset only once.
- **Recommended default.** **(b), bundled with WP-08.** Two separate releases each resetting resume position is worse than one, and removing the field makes the class of defect structurally impossible.
- **DECIDED 2026-09-25 — option (b), bundled with WP-08.** `resolvedHrMax` is dropped from `ScoringRunSnapshot` and `CURRENT_PROTOCOL_VERSION` goes 3→4 in the same commit series as SCORE-101, so the checkpoint reset is paid once, in Release A. SCORE-102 therefore moves from Phase 5 into **Phase 1**.
- **Affects.** SCORE-102, WP-08, §12. **No longer gates WP-22.**

### OD-5 — Is the SQLCipher multi-process key race still open? — **RESOLVED BY EVIDENCE; not a decision**
- **Why it was listed.** `benchmark/BASELINE.md` records two benchmark journeys as blocked by the race, and `internal-docs/plans/KNOWN_ISSUE_sqlcipher_multiprocess_key_race.md` is absent from the working tree. The first draft of this plan treated the absence as an unrecorded outcome and, correctly under that reading, refused to assert a resolution.
- **What the evidence actually shows (checked 2026-09-25).** The doc was deleted in `dfc58507` ("cleanup", #183) **as completed work**, together with the finished `PERFORMANCE_OPTIMIZATION_PLAN.md`. Its own status line reads *"Fixed"*, and it records the verification: a Robolectric 8-thread convergence test, a genuine two-OS-process instrumented test, and a manual fresh-install reproduction — 4/4 on a physical Samsung SM-A576B. The fix is present in current code: `DB/data/security/SqlCipherKeyManager.kt` holds a static `ReentrantLock` plus a cross-process advisory `FileLock` around **both** `getOrCreateDbKey()` and `validateKeyDecryption()`, with a durable `commit()` key write. Both tests still exist: `app/src/androidTest/.../SqlCipherKeyManagerCrossProcessRaceTest.kt` (driving two real `Service` processes via `app/src/debug/.../racetest/KeyRaceTestService.kt`) and `core/database/src/test/.../SqlCipherKeyManagerTest.kt`.
- **Outcome.** No decision required. The race is closed; the residue is **stale documentation** in `benchmark/BASELINE.md:17, 38, 46`. WP-01 re-runs `dashboardVitalsTabSwitch` and `hotStart` and replaces the blocker note. A failure in that re-run is a *new* regression to investigate on its own evidence, not a continuation of this one.
- **Method note.** The first draft's caution was right given what it had looked at, and the correction came from reading the deleted file's content rather than only its deletion. Worth repeating for any future "was this fixed or descoped?" question: `git show <deletion-commit>^:<path>` answers it directly.
- **Affects.** §11 pre-existing gap, WP-01, WP-19 (regression guard only).

### OD-6 — What is the acceptable foreground cost ceiling for one pull-to-refresh?
- **Why it matters.** HC-103's page/time budget needs a number, and `DailySyncUseCase`'s existing `DEFAULT_DAILY_INGEST_BUDGET_MS` / `BACK_DAY_INGEST_BUDGET_MS` / `EXTENDED_DAILY_INGEST_BUDGET_MS` constants were chosen before the changes path was identified as unbounded. There is no recorded product target for "how long may pull-to-refresh take before it degrades to a background worker".
- **Options.** (a) Reuse the existing ingest budgets for the changes phase too. (b) Introduce a separate, smaller changes budget. (c) Bound by page count only.
- **Recorded context.** The abstract half of this question is already answered by shipped behavior: `SyncConstants` sets `DEFAULT_DAILY_INGEST_BUDGET_MS` to 3 min, `BACK_DAY_INGEST_BUDGET_MS` to 5 min and `EXTENDED_DAILY_INGEST_BUDGET_MS` to 10 min. The product already accepts that a pull-to-refresh can occupy minutes. The real gap is narrower: `applyPendingChanges()` runs **before** any of those budgets and has none of its own.
- **Recommended default.** (b) with the value chosen from WP-01's measurement of `CHANGES-1K`, not guessed. Escalation on exhaustion should reuse the existing `REQUIRES_HISTORICAL_RESYNC` path rather than adding a new one.
- **DECIDED 2026-09-25 — option (b): a separate `DEFAULT_CHANGES_APPLY_BUDGET_MS`.** Rejected (a) because folding the changes phase under the ingest budget lets a heavy change set silently starve the ingest that follows it and surface as a misattributed `DEFERRED_DAILY_SYNC`; a separate constant keeps "changes took too long" distinguishable from "ingest took too long" in both logs and the failure reason. Rejected (c) because page cost varies by orders of magnitude — 1,000 heart-rate upsertions is not 1,000 weight upsertions — so a page count alone is not a cost bound. Both `MAX_CHANGE_PAGES_PER_RUN` and the time budget are applied; the time budget is the real guard and the page count is a cheap secondary stop.
- **Noted, deliberately out of scope.** The existing 3/5/10-minute budgets appear in no user-facing documentation. A refresh that spins for ten minutes is expected behavior that nothing tells the user about. That is a product/UX question, not a remediation item, and is left for a separate look.
- **Affects.** HC-103 (step 2), WP-01 (sizing measurement), WP-14.

---

## 15. Definition of Done

**Architecture**
- [ ] No disk I/O or database migration executes inside a Hilt provider (ARCH-101); a debug `StrictMode` assertion guards it.
- [ ] `HealthChangeSynchronizerImpl` holds no mutable instance state (HC-104).
- [ ] Every mutation of raw data or derived summaries — including settings-triggered recomputes — passes through `HealthMutationCoordinator.withMutation` (CACHE-102).
- [ ] `DirtySummaryPublisher` exposes exactly one publication protocol; `ScoreInvalidation` exposes exactly one dependency horizon (DB-102, CACHE-103).
- [ ] No production file exceeds the 800-line hard limit; `WorkoutsViewModel` performs no direct repository call (ARCH-102).
- [ ] Detekt baseline entries for every touched file are removed rather than re-baselined; no new `@Suppress` was added without explicit human approval.

**Health Connect correctness**
- [ ] One bounded retry scope per ingest window; a rate-limited provider receives at most `maxAttempts` calls per window (HC-101).
- [ ] An `EXERCISE` record from a de-selected device is removed by the changes path, and already-stranded rows are repaired once (HC-102, WP-17).
- [ ] Changes application is O(1) statements per page and bounded per run, and remains idempotent under replay (HC-103).
- [ ] Interval tokens are returned in the sync outcome and rebaselined by a full resync (HC-104).
- [ ] Every record type honours the "at most one page in memory" contract, with sessions as the one documented exception (HC-105).
- [ ] Exercise-route enrichment performs zero IPC for unchanged sessions and none without route consent (HC-106).
- [ ] Permission `Denied` / `Unsupported` still never causes a reconciliation deletion (regression-guarded).

**Large-volume performance**
- [ ] Peak heap for `ingestWindow` and `fetchHeartRateSamplesByWorkout` is flat across 250k/500k/1M fixtures (§7.3 criterion 1, criterion 9).
- [ ] The workout heart-rate read is narrowed to `EXERCISE`-tagged samples and `GetWorkoutDisplayMetricsUseCase` agrees with `DailyTrimpComputer` on a mis-tagged-sample fixture (PERF-101 / OD-2).
- [ ] No production query returns more than its configured budget (§7.3 criterion 2).
- [ ] HR/HRV ingest statement count reduced by ≥50× for a 1M-row ingest (PERF-102).
- [ ] Zero main-thread disk violations during start with a plaintext-era database and during Workouts navigation on the 1M fixture (§7.3 criterion 3).
- [ ] A full historical resync over `HR-MULTIYEAR` completes or checkpoints and resumes with no lost committed days (§7.3 criterion 8).

**Database behavior**
- [ ] A malformed `dirty_ranges` row cannot throw from a read; `discardBefore` repairs it (DB-101).
- [ ] Schema version remains 23 unless a gated migration was explicitly approved; any adopted migration has a recorded backfill, storage-impact and rollback note (§12).
- [ ] `EXPLAIN QUERY PLAN` recorded for the four hot queries shows index use with no table scan and no temp B-tree (§7.3 criterion 7).

**Scoring correctness**
- [ ] Exactly one derivation of `resolvedHrMax` exists, enforced by test (SCORE-101).
- [ ] `ScoringRunSnapshot` no longer carries `resolvedHrMax`; `CURRENT_PROTOCOL_VERSION == 4`; a stored v3 checkpoint restarts cleanly and a v3 backup archive still restores (SCORE-102 / OD-4).
- [ ] `HeartRateFormulas.estimateMaxHr` is **unchanged** and its truncation is documented in `ABOUT.md`, `docs/about.md`, the in-app `about_*` string and a KDoc note, with both pin assertions present (SCORE-104 / OD-3).
- [ ] Exactly one HRV-baseline and one RHR-baseline implementation exists; a biphasic night contributes one value (SCORE-103).
- [ ] No production call to the non-null `stdev()` overloads (SCORE-106).
- [ ] `updateBirthday` persists every derived preference before recomputing (SCORE-105).
- [ ] Every entry in §5's verification matrix is either "Verified" with no action, or has a landed work package.

**Deterministic recomputation**
- [ ] Running `resyncRange` twice over an unchanged fixture produces byte-identical `daily_summaries` and zero `workout_records` updates on the second pass (§7.3 criterion 5).
- [ ] Killing the worker at each of the four phases and restarting reproduces the uninterrupted result (§7.3 criterion 4, §11 measurement 8).
- [ ] A single changed HR source recomputes exactly its dependency closure (§7.3 criterion 6).
- [ ] The tier-boundary caveat (idempotent **within** a tier) remains documented and unchanged in behavior.

**Security and privacy**
- [ ] No health value, record id, device name, timestamp or raw count reaches logcat or the encrypted log file in release (re-verified after any SEC-101 change).
- [ ] `allowBackup="false"`, the two `exported="false"` providers, and the permission-guarded activity alias are unchanged.
- [ ] Every emittable diagnostic field is an enum constant, a `CountBucket`, a relative integer offset, or a class name, asserted by a reflection test that fails if a `String` field is added (SEC-101 / OD-1).
- [ ] No absolute date, timestamp, record id, device name or health value is reachable from `DiagnosticFields`; `docs/privacy.md` describes the report contents in user-readable terms.

**Documentation**
- [ ] The `DATA_FLOW.md` path-existence test is green (DOC-101).
- [ ] Every row in §13 has landed with its work package.
- [ ] The four-point documentation review checklist passed for WP-09 and WP-22.
- [ ] `ABOUT.md`, `docs/about.md` and the in-app About strings agree; the scoring documentation-drift/presence tests pass.

**Validation and benchmarks**
- [ ] `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest` green for every package; `./gradlew lintRelease` green at the end of each release.
- [ ] `benchmark/BASELINE.md` contains a dated before-section (WP-01) and dated after-sections for WP-12, WP-13, WP-15, WP-18 and WP-21.
- [ ] `dashboardVitalsTabSwitch` and `hotStart` carry real numbers in `benchmark/BASELINE.md` and the stale SQLCipher blocker note is removed; the cross-process key-race tests remain green (OD-5).
- [ ] The changes phase has its own `DEFAULT_CHANGES_APPLY_BUDGET_MS`, sized from a recorded `CHANGES-1K` measurement, and exhausting it escalates through the existing `REQUIRES_HISTORICAL_RESYNC` path (OD-6).
