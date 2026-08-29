# Data Flow & Architecture Blueprint

End-to-end map of how data moves through the app: from **Android Health Connect**
ingestion, into **Room/SQLite** local storage, through the pure-Kotlin **scoring engine**,
and out to the **Jetpack Compose** UI.

> **Reference-level by design.** This document names the calculation models, their defaults,
> and their inputs, and points to the exact source file + function that owns each formula.
> It deliberately does **not** reproduce coefficients or derivations — the mathematical
> "source of truth" stays in `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/**` (pure Kotlin, zero Android dependencies).
> When you change the pipeline, schema, use-cases, or scoring formulas, update this file in
> the same change (see the constraint in `.claude/CLAUDE.md`).

Paths below are rooted at the project root. Module prefixes are explicit, for example
`app/src/main/...`, `core/model/src/main/...`, `core/scoring/src/main/...`,
`core/database/src/main/...`, `core/healthconnect/src/main/...`, and `core/designsystem/src/main/...`.

---

## End-to-End Flow

```
┌──────────────────────────────┐
│  Android Health Connect API  │   sleep · heart rate · HRV · exercise · steps ·
│  (ingestion-only source)     │   weight · body fat · blood pressure · SpO2
└──────────────┬───────────────┘
               │ paginated readAllPages<T>() (pageToken) + permission checks
               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  core/healthconnect/.../domain/sync/HealthSyncUseCase — facade; owns       │
│  syncMutex, delegates only                                                 │
│   • sync(windowDays)        — → DailySyncUseCase: recent window (daily = 1  │
│                               day); ingest reaches 1 day back for cross-     │
│                               midnight sleep; recalc widens to absorb recent │
│                               out-of-window affected days (≤7d) else resync  │
│   • catchUpSync()           — gated by lastSyncTimestamp == 0; chunked 365- │
│                               day catchup sync using ResyncRangeUseCase      │
│   • resyncRange(...)        — → ResyncRangeUseCase: full historical, chunked│
│   • syncMutex               — serializes daily sync vs. resync              │
│  collaborators (core/healthconnect/.../domain/sync/):                      │
│   • DailySyncUseCase / ResyncRangeUseCase — the two flow orchestrators     │
│   • HealthIngestionCoordinator.ingestWindow(...) — read DTOs → map →        │
│                               filter → bounded upsert; shared by both flows  │
│   • StepCountFetcher        — per-device step reads (window + range)        │
│   • DailyRecomputeSupport   — per-day score recompute + auto-MaxHR refresh  │
│   • retryWithBackoff(...)   — bounded exponential backoff on HC/IO faults   │
└──────────────┬─────────────────────────────────────────────────────────────┘
               │ Domain HC DTO → Entity (mappers; deviceName; composite IDs)
               ▼
┌──────────────────────────────┐
│  RoomTransactionRunner       │   parent txn + 5,000-row HR/HRV transactions
└──────────────┬───────────────┘
               │ HR/HRV: conflict-targeted UPSERT on (sourceRecordRef, timestampMs) — updates mutable
               │   columns in place, near-no-op on identical re-ingest; others: @Upsert on stable id
               ▼
┌──────────────────────────────┐
│  HealthDatabase (SQLite v14) │   17 entities — single source of truth
└──────────────┬───────────────┘
               │ raw DAO reads (local; no further HC calls)
               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  core/database/.../core/database/data/repository/ScoringRepositoryImpl                 │
│  .computeDailySummary(day)                                                │
│   raw metrics → TRIMP/RAS → baselines → sleep/load/readiness → persist    │
│   delegates to pure-Kotlin core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/** (BaselineComputer,           │
│   RasCalculator, strategies/*, sleep/*)                                    │
└──────────────┬─────────────────────────────────────────────────────────────┘
               │ DailySummaryEntity persisted back to Room
               ▼
┌──────────────────────────────┐
│  ViewModels (StateFlow)      │   repo flows → combine() → stateIn()
└──────────────┬───────────────┘
               │ collectAsStateWithLifecycle()
               ▼
┌──────────────────────────────┐
│  Jetpack Compose UI          │   M3ScoreGaugeCard · Vico TrendChart · Canvas charts
└──────────────────────────────┘
```

---

## 1. Ingestion Layer (Health Connect → SQLite)

### 1.1 Health Connect access — authentication, permissions, paginated fetch

| Component                             | Path                                                | Responsibility                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| :------------------------------------ | :-------------------------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `HealthConnectRepository` (interface) | `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/HealthConnectRepository.kt` | Declares permission sets (critical / required / optional), `checkPermissions() → PermissionStatus` (Granted / Unavailable / Missing), and per-type read methods. Returns app-owned DTOs from `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/model/HealthConnectRecords.kt`, not Android Health Connect SDK record types, so sync/domain code stays Android-free. Throws `HealthConnectPermissionRevokedException` when access is revoked mid-flight.                                      |
| `HealthConnectRepositoryImpl`         | `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthConnectRepositoryImpl.kt` | Concrete HC client wrapper. Manages permissions, session reads, vitals, device discovery, and paginated fetch infrastructure (`readAllPages<T>()` and `readAllPagesStreaming<T>()`). Delegates step queries to `StepRecordReader` and workout distance/elevation totals to `IntervalTotalsReader`. Converts native `androidx.health.connect.client.records.*` instances to domain DTOs. |
| `StepRecordReader`                    | `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/StepRecordReader.kt`            | Collaborator for reading raw step records, single-range aggregated step counts (`readSteps`), and calendar-day grouped step totals (`readDailyStepTotals`, HC-003) via `aggregateGroupByPeriod` with fallback to per-day aggregation. |
| `IntervalTotalsReader`                | `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/IntervalTotalsReader.kt`        | Collaborator for bulk-reading interval totals (distance and elevation gain) and resolving cumulative metrics for exercise sessions via `SessionTotalsResolver`. |


**Permission model** (declared in the interface):

- **Critical:** `READ_SLEEP`, `READ_HEART_RATE`, `READ_HEART_RATE_VARIABILITY`, `READ_EXERCISE`
- **Required:** critical + `READ_HEALTH_DATA_HISTORY`
- **Optional:** `READ_STEPS`, `READ_WEIGHT`, `READ_BODY_FAT`, `READ_BLOOD_PRESSURE`,
  `READ_OXYGEN_SATURATION`, `READ_BODY_TEMPERATURE`

**Read methods:** `readSleepSessions`, `readHeartRateSamples` / `readHeartRateSamplesPaged`,
`readHrvSamples` / `readHrvSamplesPaged`,
`readExerciseSessions`, `readSteps` / `readDailyStepTotals`, `readWeightRecords`,
`readBodyFatRecords`, `readBloodPressureRecords`, `readOxygenSaturationRecords`,
`readBodyTemperatureRecords`, `hasBodyTemperaturePermission()` (dedicated single-permission
check, since body temperature is gated as its own dashboard-card/card-management concern rather
than folded into the generic optional-permission status), `discoverDevices`.

**Rate-Limit and Transient Fault Protection:**
Each Health Connect read is retried through `HealthConnectRetryPolicy`, which retries transient
IO, quota, and rate-limit failures with bounded exponential backoff and jitter. Cancellation is
rethrown. Room writes remain outside the read retry loop so ingestion failure boundaries stay
explicit and idempotent.

### 1.2 Sync engine — orchestration, chunking, idempotency

| Component                     | Path                                         | Responsibility                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| :---------------------------- | :------------------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `HealthSyncUseCase`           | `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HealthSyncUseCase.kt`           | Facade owning `syncMutex`; its public methods delegate to `DailySyncUseCase` (`sync`), `ResyncRangeUseCase` (`resyncRange`, `recomputeRange`), and chunked catch-up resync (`catchUpSync`), which carry the behavior described in this row. `catchUpSync` is gated by `lastSyncTimestamp == 0` and executes `resyncRangeUseCase.run` in 30-day chunks over a 365-day range. `recomputeRange(start, end, onProgress)` (SCORE-007, see 1.2.2) runs under the same `syncMutex` and delegates to `resyncRangeUseCase.run(..., skipIngestAndPrune = true)` — a Health-Connect-free re-scoring pass for settings changes that alter historical scoring inputs (TRIMP model/params, HR zones, hrMax, RAS scaling factor) without altering ingested data. Its resumable identity includes a versioned canonical scoring-preference fingerprint, so a changed scoring snapshot invalidates the saved checkpoint and restarts at the requested range start; recompute-only checkpoints contain no Changes API tokens. `withSyncLock { block }` exposes the same mutex to non-sync callers (e.g. app-start baseline backfill) so they never race a sync/resync. `sync(windowDays, onProgress)` recent-window sync — note the **ingestion fetch starts one day earlier than the scored window** (`today − windowDays`), because overnight sleep sessions begin the previous evening; clipping at the scored window's midnight would drop a night's pre-midnight HR/HRV samples (lower HRV mean, higher RHR percentile). After recent-window ingest, `sync()` runs `SessionLinkReconciler.reconcile(...)` over the ingested overlap before scoring so pull-to-refresh preserves the same canonical HR/HRV session links as historical resync. Raw Health Connect reads stay in the sync engine, but Room writes now flow through `HealthIngestionStore.persist(batch)` and frozen-baseline clearing through `HealthIngestionStore.clearFrozenBaselines(start, endExclusive)`, keeping DAO/transaction details in data layer. The recalc loop covers `windowDays`, widened down to the earliest **recent** out-of-window affected day (within `MAX_INLINE_RECOMPUTE_DAYS` = 7 of today, in `DailySyncUseCase`) so last night's sleep (dated yesterday) or backfilled HR/HRV recomputes inline; only changes older than that inline bound escalate to durable historical resync. `resyncRange(start, end, chunkDays = 30, onProgress)` full historical runs **four resumable phases**: chunked ingest → selected-source prune → full-range session-link reconcile → walk-forward recompute. Before first ingest it captures Changes API baseline tokens and stores them with the immutable range, current phase, next date, and device-selection hash in `ResyncCheckpointStore`; retries reuse the same baseline, while legacy/mismatched checkpoints restart cleanly. **Every ingestion chunk of `resyncRange` starts one day early and fetches sleep/exercise sessions one day past the chunk end** so HR/HRV samples at either side of a 30-day boundary can still be assigned to cross-midnight sessions. Metric sample reads remain capped to the chunk. Step totals are rebuilt only for the remaining recompute range on resume, preserving selected-device correctness without re-running completed raw ingest. After all chunks are ingested, a single `SessionLinkReconciler.reconcile(...)` pass starting one day before the start date (i.e. `start - 1 day` to `end`) re-derives HR/HRV session linkage and recomputes affected workout metrics (see 1.2.1) — this makes the result independent of chunk alignment. Historical resync progress reports recomputed calendar days, not internal ingest/prune/reconcile units, but resumed recompute starts with the already-completed day offset. `HealthIngestionCoordinator.ingestWindow(start, end, prefs)` remains the single read→map→filter funnel (shared by both flows); `StepCountFetcher` performs per-device step reads (recent window + historical range); `DailyRecomputeSupport` runs the per-day score recompute and auto-MaxHR refresh; `retryWithBackoff(maxAttempts = 4, initialDelayMs = 1000)` (shared helper in `RetryWithBackoff.kt`) handles transient HC/IO faults (never swallows `CancellationException`); `syncMutex` (owned by the facade) serializes daily vs. resync. |
| `DailySyncUseCase`            | `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/DailySyncUseCase.kt`            | Foreground daily-sync orchestrator (the `sync(windowDays)` body). Runs under the facade's `syncMutex`. Owns the recent-window ingest → reconcile-over-overlap → frozen-baseline clear → walk-forward recompute, the cross-midnight reach-back, change-token commit, and the `REQUIRES_HISTORICAL_RESYNC` decision. Before the walk-forward it builds one `WalkForwardTrimpContext` + `WalkForwardBaselineContext` over the widened `[oldestTargetDay, today]` range (`DailyRecomputeSupport.buildWalkForward*`) and passes both to every recomputed day, so the 84-day TRIMP series and 56-day baseline sleep window are fetched once per sync rather than once per synced day — the same PERF-002/WP-20/WP-22 shape `ResyncRangeUseCase` already uses. The frozen-baseline clear and the entire walk-forward run inside one `DailyRecomputeSupport.inRecomputeTransaction { }` (F7), so a routine sync produces a single Room invalidation round on `daily_summaries`/`workout_records` instead of one per synced day. Health Connect I/O (window ingest, reconcile, step fetch) always completes before that transaction opens. The recent-window ingest is split into today's segment `[todayMidnight, windowEnd)` and the overnight back-day reach-back `[ingestStart, todayMidnight)` (B′), each under its own `withTimeout` budget; a segment that times out is retried once with an extended budget, a still-failing today segment returns `DEFERRED_DAILY_SYNC` (no historical-worker escalation), and a still-failing back-day segment is best-effort so today still scores. Cancellation rolls the window back; the next sync redoes the same idempotent range. |
| `ResyncRangeUseCase`          | `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/ResyncRangeUseCase.kt`          | Full-historical-resync orchestrator (the `resyncRange(...)` body). Runs under the facade's `syncMutex`. Owns the four resumable phases (chunked ingest → selected-source prune → full-range session-link reconcile → walk-forward recompute), checkpoint capture/resume, and baseline-token promotion. `run(..., skipIngestAndPrune = false)`: when `true` (see 1.2.2), ingest and prune are forced off, the checkpoint starts at `RECONCILE`, and the checkpoint identity is namespaced with `RECOMPUTE_ONLY_V2` plus the device-selection hash and a canonical projection of every scoring/reconciliation preference. Matching scoring snapshots resume; changed scoring inputs restart from the requested start date, while operational/UI settings do not invalidate the checkpoint. Recompute-only checkpoints deliberately store no Changes API tokens and never capture/apply/commit tokens or update `lastSyncTimestamp`; full-resync checkpoints retain the mandatory non-empty-token resume guard. **Adaptive chunk shrink (HC-002):** if a chunk's `ingestWindow` throws `HealthConnectWindowTimeoutException`, the ingest loop halves the effective chunk size (floor 1 day), persists it as the checkpoint's `chunkDaysOverride` so a killed worker resumes at the shrunk size, and retries the same chunk start — never the identical oversized window. Once a chunk succeeds it grows back to the caller-supplied `chunkDays` for the next chunk. If the 1-day floor itself times out, the exception propagates out as a distinct `Result.failure("...", "RESYNC_WINDOW_TIMEOUT")` (not the generic `RESYNC_ERROR`), which `HealthResyncWorker` still resolves via its normal `Result.retry()` backoff. Its RECOMPUTE phase runs in 30-day units (`RECOMPUTE_CHECKPOINT_INTERVAL_DAYS`), each unit one Room transaction via `DailyRecomputeSupport.inRecomputeTransaction { }` (F7), checkpointed only after that transaction commits. Transaction-rollback and checkpoint-resume boundaries therefore coincide: a kill, cancellation, or per-day failure discards at most one unit and the stored checkpoint still points at that unit's first day, so the retry idempotently redoes exactly what was lost. |
| `HealthIngestionCoordinator`  | `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HealthIngestionCoordinator.kt`  | Single read→map→device-filter→upsert funnel for one HC window (`ingestWindow(start, end, prefs, onProgress)`), shared by both flows. `onProgress` (optional, default `null`) fires `(ResyncPhase.INGEST, pagesIngested, 0)` after each HR/HRV page persists — an indeterminate running page count, not a determinate total; `DailySyncUseCase` passes its own `onProgress` through here, `ResyncRangeUseCase` reports chunk-level `INGEST` progress separately and does not thread per-page granularity through this parameter. Sessions and low-volume record types are fetched and persisted first, in one `HealthIngestionStore.persist(batch)` transaction — that same call also sub-batches HR/HRV samples at ≤5,000 rows per transaction (see the streamed path below); HR and HRV samples then stream page-by-page via `readHeartRateSamplesPaged`/`readHrvSamplesPaged` (HC-001), each page tagged against this window's already-known sessions and persisted immediately through `HealthIngestionStore.persistHeartRateSamples`/`persistHrvSamples`, both of which internally sub-batch at ≤5,000 rows per transaction regardless of the source HC page size — at most one HC page of samples is held in memory at once. Workouts are persisted with zero HR-derived metrics at this point (mirroring the changes-path pattern); `SessionLinkReconciler.recomputeWorkouts`, which both sync flows always run immediately after ingestion, fills in the real values once every HR sample in range is stored. The entire window read (sessions, low-volume types, and both streamed passes) runs inside one `withTimeout(windowBudgetMs)`; a `TimeoutCancellationException` from that timeout is caught here and rethrown as `HealthConnectWindowTimeoutException` (HC-002) — deliberately not a `CancellationException` subtype, so callers can never mistake "this window is too dense for its budget" for cooperative cancellation. If interrupted, the checkpoint stays on the current HC window and stable-ID upserts make the retry safe without deleting prior valid data. |
| `StepCountFetcher`            | `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/StepCountFetcher.kt`            | Per-device daily step reads. `fetchWindow(...)` for the recent window (semaphore-capped concurrent reads when no device filter); `fetchRange(...)` for the resync recompute range — the "all devices" path issues one grouped `readDailyStepTotals` call per chunk (HC-003) instead of one `readSteps` aggregate call per calendar day; the device-selected path stays raw-record-based, chunked, retry-wrapped. |
| `DailyRecomputeSupport`       | `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/DailyRecomputeSupport.kt`       | Shared per-day helpers: `recomputeDay(day, steps)` → `ScoringRepository.computeAndPersistDailySummary` (single point of daily score persistence; no math here), and `refreshAutoMaxHr(prefs)`. **PERF-002/WP-20/WP-22:** every multi-day walk-forward (both `DailySyncUseCase.run` and `ResyncRangeUseCase`'s RECOMPUTE phase) instead calls `buildWalkForwardTrimpContext`/`buildWalkForwardBaselineContext` once up front, then `recomputeDay(day, steps, prefs, contexts)` per day with one shared `WalkForwardContexts` holder (`trimp`/`baseline`/`fatigue`, each nullable; a single overload — the old partially-populated variants were a latent per-day-requery trap) — `ScoringRepositoryImpl` slices that shared in-memory state per day instead of each day independently re-querying its own 84-day TRIMP window or 30-/56-day baseline window; math is unchanged, only the I/O is batched. Also owns `inRecomputeTransaction { }`, the single place either sync path opens a recompute transaction (F7). Reads inside it observe the transaction's own uncommitted writes, which the walk-forward requires (day N sums days N-1..N-6 and reads day N-1). |
| `ForegroundSyncController`    | `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/ForegroundSyncController.kt`    | Foreground state + progress bridge. `triggerDailySync()` = pull-to-refresh (current day only, `windowDays = 1`); `triggerImmediateSync()` = first-launch catch-up; `onBackgroundRecalc{Started,Progress,Finished}()` publish WorkManager job progress into `isSyncing` / `recalcProgress` StateFlows + `syncCompletedEvent`. A `DEFERRED_DAILY_SYNC` failure result (dense daily window that timed out even after its extended-budget retry) is logged and dropped — no `getOrThrow()`, no historical-worker escalation, no completion event. |
| `FullHistoricalResyncUseCase` | `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/FullHistoricalResyncUseCase.kt` | Snapshots preferences once, resolves one `RetentionBounds.HistoricalWindow` from the current instant in that snapshot's stored scoring timezone, and delegates its inclusive `startDate..endDate` to `HealthSyncUseCase.resyncRange`. The window also carries the exact scoring-zone `startTimeMs` used by cleanup and the startup canonical-TRIMP gate, so date and instant boundaries cannot diverge when the device zone differs. Checkpoint/resume behavior stays in the sync engine; no math. `execute(recomputeOnly = false, onProgress)`: when `true` (see 1.2.2) delegates to `HealthSyncUseCase.recomputeRange(start, end, onProgress)` instead. |
| `HealthResyncWorker`          | `app/src/main/kotlin/app/readylytics/health/workers/HealthResyncWorker.kt`              | Before resolving its lazy Room-backed `FullHistoricalResyncUseCase` or lazy `ForegroundSyncController`, it requires `DatabaseReadiness.Ready`; otherwise it retries without opening Room or constructing the controller graph. Once ready, this `@HiltWorker` durable foreground service (`FOREGROUND_SERVICE_TYPE_DATA_SYNC`) runs the resync use case, emits `WorkInfo` progress (`setProgressAsync`), posts a determinate "day X of Y" notification, and bridges progress to `ForegroundSyncController`; on success, `persistPostRecomputeState()` bumps `UserPreferences.scoringVersion` (if stale) and snapshots the sleep scoring inputs into `last_recalc_*` on `user_preferences` (both full resync and recompute-only). `Result.retry()` on transient failure, but confirmed permission failures stop with `Result.failure()` so WorkManager does not loop. Checkpoints remain available for a new sync after access is restored. Reads the boolean `KEY_RECOMPUTE_ONLY` input-data flag (default `false`, see 1.2.2) and forwards it as `FullHistoricalResyncUseCase.execute(recomputeOnly = ...)`. |
| `DatabaseMigrationWorker` / `DatabaseMigrationController` | `app/src/main/kotlin/app/readylytics/health/workers/DatabaseMigrationWorker.kt`; `app/src/main/kotlin/app/readylytics/health/domain/migration/DatabaseMigrationController.kt` | The required external v7 migration runs as its own non-expedited unique one-time foreground work (`database_v7_migration`, `ExistingWorkPolicy.KEEP`, exponential backoff, `FOREGROUND_SERVICE_TYPE_DATA_SYNC`). It publishes phase and copied/total-row `WorkInfo` progress through a migration-specific notification/channel; insufficient-space bytes are terminal failure output, ordinary migration failure retries, and cancellation is rethrown. The controller combines the pre-Room readiness inspection with this unique-work progress in a `StateFlow`; migration progress is deliberately separate from historical health-resync progress. |
| `MainActivity` / `HealthDashboardApplication` startup gate | `app/src/main/kotlin/app/readylytics/health/MainActivity.kt`; `app/src/main/kotlin/app/readylytics/health/HealthDashboardApplication.kt`; `app/src/main/kotlin/app/readylytics/health/DatabaseReadyStartupInitializer.kt` | Both entry points observe the domain-facing `DatabaseMigrationController` before resolving Room-backed graphs. `MainActivity` renders a blocking Material 3 migration screen for every non-ready state through the dependency-free `DatabaseReadinessTheme`, starts/resumes required migration once from `LaunchedEffect`, and creates the preference-backed `ThemeViewModel`, `SyncViewModel`, or resolves the lazy `LocalRestoreManager` only in the `Ready` branch. The application injects `HealthSyncUseCase`, `BackfillHistoricalBaselinesUseCase`, and the broad `SettingsRepository` as `dagger.Lazy`; settings are indirectly Room-backed through `UIPreferences` → `HealthDeviceRepository` → DAOs, so the initializer resolves all three lazies only after `DatabaseReadiness.Ready`. An `AtomicBoolean`-guarded initializer then runs baseline backfill under `syncMutex` and schedules backup, birthday, and cleanup work exactly once per completed process initialization. It enqueues one durable background recompute (`WorkerScheduler.scheduleResyncWorker(recomputeOnly = true)`) when either `storedScoringVersion < CURRENT_SCORING_VERSION` or a workout inside `RetentionBounds.resolveHistoricalWindow(prefs).startTimeMs..` still has `modelTrimp IS NULL`; the worker owns convergence and the version bump. The backfill gate uses the same stored-scoring-zone instant as cleanup and the worker's recompute start, never device/system midnight. Periodic sync is scheduled at the stored interval only when `backgroundSyncEnabled`; otherwise its unique work is cancelled. An ordinary incomplete initialization resets the guard and returns a retryable status; the application coordinator retries at bounded 500 ms / 2 s / 8 s delays while the current readiness remains Ready, without depending on another equal `StateFlow` emission. `collectLatest` cancels that retry chain as soon as readiness changes; cancellation resets the guard and is always rethrown. A v5/v6 database therefore cannot be opened indirectly by normal startup while the external migration owns the file. |
| `WorkerScheduler`             | `core/model/src/main/kotlin/app/readylytics/health/core/model/workers/WorkerScheduler.kt`                 | Enqueues unique work. `scheduleDatabaseMigration()` owns the distinct v7 migration chain described above. `scheduleResyncWorker(recomputeOnly = false)` uses the shared `RESYNC_WORK_NAME` chain, expedited execution, and exponential backoff; explicit full resync uses `ExistingWorkPolicy.KEEP`, while recompute-only settings requests use `ExistingWorkPolicy.APPEND_OR_REPLACE` to append a durable successor. The request type is passed through as `KEY_RECOMPUTE_ONLY` input data (see 1.2.2). Multiple rapid settings changes may create redundant local passes, but the newest preferences are eventually captured without adding a second work name or progress channel. Also provides `cancelResyncWorker()`; `schedulePeriodicSync(intervalMinutes)` (`PERIODIC_SYNC_WORK_NAME`, `ExistingPeriodicWorkPolicy.UPDATE`, exponential backoff, requires battery not low but no charging or device-idle constraint) + `cancelPeriodicSync()`; and backup / birthday / data-cleanup workers. |
| `PeriodicHealthSyncWorker`    | `app/src/main/kotlin/app/readylytics/health/workers/PeriodicHealthSyncWorker.kt`        | "Background Sync" toggle in Settings. Before resolving its lazy Room-backed `HealthSyncUseCase` or lazy `ForegroundSyncController`, it requires `DatabaseReadiness.Ready`; otherwise it retries without opening Room or constructing the controller graph. Once ready, this `@HiltWorker` periodic **standard (non-foreground) worker** calls `HealthSyncUseCase.sync(windowDays = 2)` (shares `syncMutex` with the other two flows), bridges progress to `ForegroundSyncController`, shows/dismisses a silent transient notification (`SyncNotifications.BACKGROUND_SYNC_CHANNEL_ID`) via `NotificationManagerCompat` directly — no `setForeground()`, since `READ_HEALTH_DATA_IN_BACKGROUND` already permits background HC reads and starting a foreground service from a periodic background worker risks `ForegroundServiceStartNotAllowedException` on API 34+. Ordinary failures return `Result.retry()`; `REQUIRES_HISTORICAL_RESYNC` enqueues `WorkerScheduler.scheduleResyncWorker()` and finishes successfully so durable catch-up runs once without periodic retry churn. |
| `LocalBackupWorker`           | `app/src/main/kotlin/app/readylytics/health/workers/LocalBackupWorker.kt`                | Before resolving its lazy Room-backed `LocalBackupManager`, it requires `DatabaseReadiness.Ready`; otherwise it retries without opening Room. Ready runs preserve the existing local encrypted backup result mapping. |
| `DataCleanupWorker`           | `app/src/main/kotlin/app/readylytics/health/workers/DataCleanupWorker.kt`               | Daily retention enforcement; before resolving lazy `RetentionCleanup`, it requires `DatabaseReadiness.Ready` and retries without opening Room otherwise. Cutoff is `RetentionBounds.resolveHistoricalWindow(prefs).startTimeMs`, exposed via `resolveRetentionCutoffMs`: scoring-zone midnight shared with resync/startup, never device-zone midnight. No-op when retention is disabled. |
| `DataRollupWorker`            | `app/src/main/kotlin/app/readylytics/health/workers/DataRollupWorker.kt`                | Daily hot→warm rollup; resolves the 90-day `RetentionBounds.resolveHotTierCutoffMs()` and delegates to `DataRollupManager`. `Result.retry()` on transient failure. |
| `DataRollupManager`           | `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/DataRollupManager.kt`  | Atomically downsamples raw `heart_rate_records` older than the cutoff into `hr_minute_buckets` then deletes the raw rows (`MinuteBucketDao.rollupIntoBucketsBefore` + `HeartRateDao.deleteBeforeTimestamp` in one transaction). |
| `RetentionCleanup`            | `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RetentionCleanup.kt`             | Executes deletions of data strictly older than the cutoff across all 12 sensitive tables. **DB-002:** `heart_rate_records` and `hrv_records` (the two high-volume tables) are deleted via `deleteBeforeTimestampBatch`, each call bounded to 10,000 rows and run in its own transaction, looping until a batch returns fewer than 10,000 deletes — so a large first-time cleanup opens many bounded transactions instead of one unbounded delete/WAL growth spike, and a killed worker mid-loop leaves already-deleted rows deleted (idempotent restart: `WHERE timestampMs < cutoff` simply matches fewer rows next time). The remaining 10 low-volume tables (`sleep_sessions`, `hr_minute_buckets`, `workout_records`, `daily_summaries`, `weight_records`, `body_fat_records`, `blood_pressure_records`, `oxygen_saturation_records`, `body_temperature_records`, `step_records`) are deleted together in one single transaction, as before. |
| `RetentionBounds`             | `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/util/RetentionBounds.kt`             | Single source of truth for retention→date/instant math. `resolveHistoricalWindow(prefs, now)` derives `endDate` in `prefs.scoringZone()`, then enabled → `startDate = endDate − retentionDays`, disabled → `endDate − ABSOLUTE_MAX_DAYS` (3650 / 10y), and binds that date to scoring-zone midnight as `startTimeMs`. Inclusion is `workout.startTime >= startTimeMs`; cleanup deletes the complementary `< startTimeMs` set. Also owns the fixed 90-day hot/warm boundary (`HOT_TIER_WINDOW_DAYS`, `resolveHotTierCutoffMs`). |
| `RoomTransactionRunner`       | `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RoomTransactionRunner.kt`        | Wraps `HealthDatabase.withTransaction { … }`. Ingestion commits parent/low-volume records together, then HR and HRV in bounded 5,000-row transactions with cancellation checks between batches. A failed window may contain partial new upserts, but never deletes prior valid rows; its unchanged checkpoint causes an idempotent replay. Also wraps the sync/resync walk-forward recompute via `DailyRecomputeSupport`. |
| `HealthChangeSynchronizer`    | `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HealthChangeSynchronizer.kt`    | Reconciles differential Health Connect Changes API responses (upsertions and deletions) incrementally during daily/foreground sync. Resolves dates of deleted records via local DB lookup — steps resolve via the new `step_records` raw table (HC-005), every other type via its own scoring table. Composite-key metric changes delete every Room row owned by the HC source record before re-upsert
(`getBySourceRecordId`/`deleteBySourceRecordId` on the six composite-id DAOs use a sargable
`id >= x||'_' AND id < x||'\`'` range predicate against the id index, not a `substr()` scan —
PERF-003). HR/HRV/exercise upserts resolve real overlapping sleep/workout session spans from local DB (`getOverlapping`) and, for exercise, real stored HR samples, so a changes-path row is link/metric-correct at write time rather than only after the next `DailySyncUseCase` reconcile pass (HC-004). The synchronizer returns candidate next tokens but never persists them; the daily-sync flow (`DailySyncUseCase`) commits them only after requested-window ingest, reconciliation, step fetch, and scoring all succeed. Changes older than the requested scoring window leave tokens uncommitted and return `REQUIRES_HISTORICAL_RESYNC`, routing correction through the durable worker without widening foreground scoring. |
| `HealthChangeTokenStore`      | `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/HealthChangeTokenStore.kt`      | Atomically stores differential Changes Tokens per Health Connect data type after derived summaries are durable. Replayed pages are safe because Room ingestion is idempotent by HC record ID. |
| `ResyncCheckpointStore`       | `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/ResyncCheckpointStore.kt`       | Stores the resumable resync checkpoint: fixed range, current phase (`INGEST` / `PRUNE` / `RECONCILE` / `RECOMPUTE`), next date, selection identity, token map, and an optional `chunkDaysOverride` (HC-002: the shrunk ingest chunk size to resume with after a Health Connect window timeout; `null` means use the caller-supplied `chunkDays`). A recompute selection identity contains a versioned canonical scoring-preference fingerprint; a changed scoring snapshot therefore invalidates the checkpoint and restarts recompute at the requested range start. Full resyncs require pre-ingest baseline Changes Tokens and clear only after recompute succeeds and tokens are promoted; local recomputes intentionally persist an empty token map and clear after successful scoring. |
| `ResyncCheckpointStoreImpl`   | `app/src/main/kotlin/app/readylytics/health/data/preferences/ResyncCheckpointStoreImpl.kt` | Proto DataStore implementation of historical-resync checkpoint persistence. `chunk_days_override` (proto field 7, additive) round-trips as `chunkDaysOverride: Int?` — proto `0` (the proto3 default) decodes to domain `null`/"no override"; legacy checkpoints saved before this field existed decode the same way. |
| `SelectedSourcePruner`        | `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/SelectedSourcePruner.kt`        | Executes transactional, device-scoped deletes of records not matching the selected device within the resync date range, using the stored scoring timezone for deterministic day boundaries. |
| `SelectedSourcePrunerImpl`    | `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/SelectedSourcePrunerImpl.kt`     | Concrete implementation of `SelectedSourcePruner`. Deletes non-selected-device records within scoring-zone-derived epoch boundaries; ambient device timezone never changes which edge-day records are pruned. |

### 1.2.1 Session-link reconciliation — chunk-independent determinism

| Component               | Path                                        | Responsibility                                                                                                                                                                                                                                                                                                                                                                                                              |
| :---------------------- | :------------------------------------------ | :-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SessionLinker`         | `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/link/SessionLinker.kt`         | Pure function `resolve(sampleMs, sleepSessions, workoutSessions): SampleLink`. Single source of truth for "which session does this HR/HRV sample belong to?" — sleep > workout > resting precedence, ties on overlapping spans broken by earliest `(startTime, id)`. Mathematically equivalent to the forward-pointer logic in `HeartRateMapper`/`HrvMapper` for ascending-sorted samples. Kept as the single-lookup reference implementation (used where span lists are small, e.g. HC-004 changes-path linking) and as `SessionLinkSweep`'s correctness oracle. |
| `SessionLinkSweep`      | `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/link/SessionLinkSweep.kt`      | PERF-001: stateful sweep-line equivalent of `SessionLinker.resolve` for samples visited in non-decreasing `sampleMs` order (exactly the order Room's `getKeysetPage` ascending `(timestampMs, id)` pagination delivers). Each span enters and leaves a small per-type active window at most once across a full sweep, so a full-history reconcile runs in roughly O(samples + sessions) instead of `SessionLinker`'s O(samples × sessions) — the dominant CPU cost of a multi-year resync before this change. |
| `SessionLinkReconciler` | `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/link/SessionLinkReconciler.kt` | Domain-side reconcile port. Data implementation (`core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/SessionLinkReconcilerImpl.kt`) runs post-ingestion **once per `resyncRange` call** (not per chunk): loads the complete sleep + workout session spans for `[start - 1 day, end]`, builds one `SessionLinkSweep` per HR/HRV pass, re-tags every row in range via `sweep.resolve(timestampMs)`, and recomputes `trimp`/zone-minutes/`avgHr`/`durationMinutes` for every workout in range via `ZoneThresholds.computeMetrics` (`core/model/src/main/kotlin/app/readylytics/health/core/model/domain/heartrate/ZoneThresholds.kt`, extracted from the old `WorkoutMapper` as a pure domain calculator). Processes heart rate and HRV records in keyset-paged chunks of 5000 records, running each batch in a transaction. Workout recomputation (`recomputeWorkouts`, HC-002) is batched in groups of `WORKOUT_BATCH_SIZE` (20) by `workoutSpans` order (startTime ASC, from `WorkoutDao.getOverlapping`): each batch fetches its own workout rows via `WorkoutDao.getByIds`, fetches EXERCISE-type HR samples spanning the whole batch's time range via `HeartRateDao.getByTypeAndTimeRange` (DB-001, index-backed, no in-memory type filtering), computes metrics per workout via `ZoneThresholds.computeMetrics` (which internally clips `hrSamples` to each workout's own `[startMs, endMs]`, so sharing the batch's HR list across its workouts is safe), and upserts the whole batch in one transaction — cutting both DB reads and transaction count from O(workouts) to O(workouts / 20). Only changed rows are upserted. |

**Why this exists:** during chunked ingestion, `HeartRateMapper`/`HrvMapper` only see the
sleep/workout sessions present in the _current_ Health Connect fetch window. A sleep
session straddling a chunk boundary can have its samples split across two windows, each
tagging only the subset it saw (the rest fall to `RESTING`). Because chunk boundaries are
anchored to the resync start date — which depends on the user's retention setting — this
made `currentNocturnalRhr`/`currentHrvMean`/workout TRIMP retention-dependent for the same
underlying data. The reconcile pass re-derives tagging from the full session list, making
the result a pure function of the data, independent of chunking.

> **Three sync flows, one engine:** pull-to-refresh (`triggerDailySync`, 1 day), full historical
> resync (`resyncRange`, retention-bounded, foreground service), and periodic background sync
> (`PeriodicHealthSyncWorker`, 2 days, `ExistingPeriodicWorkPolicy.UPDATE`). All three call into
> `HealthSyncUseCase` and share `syncMutex`; ingestion stays upsert-by-HC-id, so overlapping
> windows are idempotent. Health Connect Changes Tokens are used for incremental synchronization:
> daily and periodic sync flows apply differential changes since the last committed token and score
> their requested 1-day/2-day window, widened down to the earliest **recent** out-of-window affected
> day (within `MAX_INLINE_RECOMPUTE_DAYS` = 7 of today) so last night's sleep (dated yesterday) or
> backfilled HR/HRV is recomputed inline and tokens commit normally — no full resync. Candidate
> tokens advance only after that (possibly widened) window succeeds. Only an affected date older than
> that inline bound — or an expired/missing token — keeps tokens replayable and schedules full
> historical resync.
> Full resync captures baseline tokens before its first HC read, stores them in every checkpoint phase,
> and promotes them only after walk-forward recompute succeeds. Changes made during the scan therefore
> remain visible from that baseline on the next sync; killed workers resume with the same baseline.

To bound memory and keep long reconciles cooperative, HR and HRV rows use ordered keyset paging on (timestampMs, id) in 5000-row batches. Each batch commits independently, checks cancellation, and yields before the next page. Settings device selection reads distinct ingested device names from Room only; Health Connect device discovery is not part of Settings or onboarding.

### 1.2.2 Settings-driven scoring recompute (SCORE-007) — recent-window vs. historical

Some Settings changes only affect the *display* of already-correct data (e.g. step goal); others
change an input to the scoring formulas themselves (TRIMP model/params, HR zones, hrMax source,
RAS scaling factor, physiology profile), which means every historical day's persisted
TRIMP/RAS/ATL/CTL was computed under stale coefficients and must be re-scored — without
re-fetching anything from Health Connect. `HealthDataRefresh` (`core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/FeatureSyncPorts.kt`)
is the port each Settings ViewModel calls after persisting a preference, and it exposes both:

- `refreshAffectedWindow()` — existing recent-window path (`ForegroundSyncController`/`HealthSyncUseCase.sync`), for display-only or narrow-window changes (e.g. `StepGoalChanged`, `SyncPreferenceChanged`).
- `refreshHistorical()` — new historical-scope path for scoring-input changes (e.g. `TrimpModelChanged`, `BanisterMultiplierChanged`, `ChengBetaChanged`, `ItrimBChanged`, `ResetTrimpToProfileDefaults`, `MaxHeartRateChanged`, `ZonePercentagesChanged`, `ZoneBpmsChanged`, `RasScalingFactorChanged`, `ResetRasScalingFactor`, physiology-profile edits).

Flow for `refreshHistorical()`:

`FullHistoricalResyncUseCase` snapshots preferences and resolves one
`RetentionBounds.resolveHistoricalWindow(prefs)` from the current instant. Its `endDate` is today in
`prefs.scoringZone()`; its `startDate` and exact scoring-zone-midnight `startTimeMs` are the shared
recompute/seed, startup-backfill, and cleanup boundary.

```
ViewModel.onEvent(...)                       (feature/settings/.../*ViewModel.kt)
  → displaySettings.updateXxx(...)           (persist the new preference)
  → healthDataRefresh.refreshHistorical()    (HealthDataRefresh port)
      → HealthDataRefreshAdapter.refreshHistorical()   (app/.../domain/sync/HealthDataRefreshAdapter.kt)
          → WorkerScheduler.scheduleResyncWorker(recomputeOnly = true)
              → HealthResyncWorker (KEY_RECOMPUTE_ONLY = true input data)
                  → FullHistoricalResyncUseCase.execute(recomputeOnly = true, onProgress)
                      → HealthSyncUseCase.recomputeRange(start, today, onProgress)
                          → ResyncRangeUseCase.run(..., skipIngestAndPrune = true)
                              → RECONCILE (unchanged) → walk-forward RECOMPUTE only
```

This reuses the durable `HealthResyncWorker` foreground service, its progress notification, and the
same `RESYNC_WORK_NAME` chain rather than adding a parallel worker/notification path. Explicit full
resync uses `ExistingWorkPolicy.KEEP`; recompute-only settings requests use
`ExistingWorkPolicy.APPEND_OR_REPLACE` so they become durable successors behind active historical
work. Multiple rapid settings changes may create redundant local passes, but the final queued pass
eventually captures the newest preferences through the existing progress channel. Because
`skipIngestAndPrune = true` skips Health Connect entirely, it
also skips Changes-Token commits and `lastSyncTimestamp` updates (see 1.2's `ResyncRangeUseCase`
row) — a recompute-only pass must never be mistaken for a completed HC sync. `UserUseCase`'s two
auto-maxHR paths (initial calculation and reset-to-default) also call
`scheduleResyncWorker(recomputeOnly = true)` directly, since a changed hrMax is the same
historical-scope input as `MaxHeartRateChanged`.

### 1.3 Mappers — domain HC DTO → ingestion Input

All mappers consume the domain DTOs returned by `HealthConnectRepository`; those DTOs are produced
by the package-level `toDomain()` extension functions in `HealthConnectRecordConverters.kt`
(`core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthConnectRecordConverters.kt`),
the single unified conversion point for every native Health Connect SDK record type (ARCH-002). Native
Health Connect SDK records are intentionally confined to that file plus
`core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthConnectRepositoryImpl.kt`.
`HealthConnectRecordConverters` maps `SleepSessionRecord.STAGE_TYPE_OUT_OF_BED` directly to
`DomainSleepStageType.AWAKE` so out-of-bed intervals contribute to awake/WASO duration rather than being dropped.

The five sync mappers below (ARCH-001) are now pure Kotlin, living in `core/model` and returning
plain `*Input` DTOs (`SleepSessionInput`/`SleepStageInput`/`HeartRateInput`/`HrvInput`/`WorkoutInput`/
`StepRecordInput`, defined in `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/HealthIngestionStore.kt`)
instead of Room entities directly — they carry the stable `deviceName` derived from
`DeviceLabel.from(device, dataOrigin)` and deterministic composite IDs (`${hcRecordId}_${timestampMs}`)
so re-ingestion is idempotent, but entity construction itself happens one layer down, in
`RoomHealthIngestionStore`'s `toEntity()` extensions (`core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RoomHealthIngestionStore.kt`).

| Mapper                       | Path                                        | DTO → Input                                                                                                                                       |
| :--------------------------- | :------------------------------------------ | :------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SleepDataMapper`            | `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/mappers/SleepDataMapper.kt`     | `DomainSleepSessionRecord` → `SleepSessionInput` + `List<SleepStageInput>` (sums deep/REM/light/awake, computes efficiency). **HC-006/WP-11:** when `stages` is empty (a stage-less HC session), `durationMinutes` falls back to the raw session span (`endTime - startTime`) instead of the stage-minute sum (which would be 0) — see 2.5's note on the Architecture reweight. |
| `HeartRateMapper`            | `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/mappers/HeartRateMapper.kt`     | `List<DomainHeartRateRecord>` → `List<HeartRateInput>`; assigns `recordType` (SLEEP / EXERCISE / RESTING) and `sessionId` via `SessionLinkSweep`. |
| `HrvMapper`                  | `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/mappers/HrvMapper.kt`           | RMSSD records → `List<HrvInput>`; links to sleep session or marks RESTING via `SessionLinkSweep`.                                                                        |
| `WorkoutMapper`              | `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/mappers/WorkoutMapper.kt`               | `DomainExerciseSessionRecord` → `WorkoutInput`; derives elapsed `durationMinutes` only. Zone minutes/avg HR/TRIMP are zero at this stage — populated later by `ZoneThresholds.computeMetrics` during the reconcile pass (see 1.2.1). Workout load/intensity categories are **not** persisted here. Maps the session's route points into `WorkoutInput.routePoints` (→ `workout_route_points` rows, §1.4) and, when Health Connect supplies no direct distance/speed/elevation aggregates, derives fallback `totalDistanceMeters`/`avgSpeedKmh`/`elevationGainMeters` from the route via `RouteDistanceCalculator` (`core/model/.../domain/util/RouteDistanceCalculator.kt`, pure haversine path sum + 3 m ascent-anchored elevation gain). `routeState` (IMPORTED / PERMISSION_REQUIRED / NOT_AVAILABLE) is captured verbatim from `ExerciseRouteResult`. |
| `StepsMapper`                | `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/mappers/StepsMapper.kt`         | `DomainStepsRecord` or aggregate count → `StepRecordInput`.                                                                                       |
| `WeightDataMapper`           | `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/mapper/WeightDataMapper.kt`           | `DomainWeightRecord` → `WeightRecordEntity` (kg).                                                                                                  |
| `BodyFatDataMapper`          | `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/mapper/BodyFatDataMapper.kt`          | `DomainBodyFatRecord` → `BodyFatRecordEntity` (%).                                                                                                 |
| `BloodPressureDataMapper`    | `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/mapper/BloodPressureDataMapper.kt`    | `DomainBloodPressureRecord` → `BloodPressureRecordEntity` (systolic/diastolic mmHg).                                                               |
| `OxygenSaturationDataMapper` | `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/mapper/OxygenSaturationDataMapper.kt` | `DomainOxygenSaturationRecord` → `OxygenSaturationRecordEntity` (%).                                                                               |
| `BodyTemperatureDataMapper`  | `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/mapper/BodyTemperatureDataMapper.kt`  | `DomainBodyTemperatureRecord` → `BodyTemperatureRecordEntity` (°C). Ingested through `HealthIngestionCoordinator` exactly like the other optional-permission metrics — same upsert/idempotency contract, no special-casing. |

### 1.4 Room storage — `HealthDatabase` (`@Database(version = 14)`)

Defined in `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/HealthDatabase.kt`;
entities in `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/`, DAOs in
`core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/`. All Room DAO interfaces
and entities live in `core:database-schema` (`app.readylytics.health.core.databaseschema.data.local.{dao,entity}`).
`core:database` keeps `HealthDatabase`, `AuditEventDao`/`AuditEventEntity`, `Converters`, migrations,
and the generated DAO impls. `core:healthconnect` mappers import DAOs/entities from
`core:database-schema`. **The database is the single source of truth; the UI never reads Health
Connect directly.**

`DatabaseMigrations` registers only the small Room migrations v1→v2, v2→v3, v3→v4, v4→v5,
and v5→v6. Existing v5/v6 files complete their upgrade before Room opens: after a fail-closed
free-space preflight, a v5 file receives the additive v5→v6 setup in one SQLCipher transaction,
then the external v6→v7 state machine runs. Insufficient space returns the required/available-byte
failure without mutating any legacy table. Room has no v6→v7 migration or one-shot
`MIGRATION_6_7` `INSERT SELECT`: `DatabaseReadinessGate` inspects the encrypted file before Room
construction, and `DatabaseModule` refuses to open any existing database that has not reached v7
through the external `V7DatabaseMigrator`. Pure readiness, phase, progress, result, and
`DatabaseReadinessInspector` contracts live in
`core/model/src/main/kotlin/app/readylytics/health/core/model/domain/migration/DatabaseMigrationModels.kt`;
the SQLCipher/file-backed `DatabaseReadinessGate` remains in the app data layer and implements that
inspector. The v5→v6 statements are shared through
`DatabaseUpgradeSql.V5_TO_V6`, preventing the Room and external paths from drifting.
`V7DatabaseMigrator` copies HR and HRV into shadow tables in committed 10,000-row keyset pages. A
durable single-row checkpoint stores the current copy/index/validation phase plus both last source
ids and copied/total counts, so cancellation or process death resumes at the next page or phase.
The original v6 tables remain authoritative throughout all copy and index phases; no source table
is deleted or renamed before validation and atomic cutover. Once a checkpoint exists, resume reuses
the already-accepted preflight instead of recalculating against the expanded database and shadow
files. Readiness/open/preflight failures return the migration's typed `Failed` result; cooperative
`CancellationException` is still rethrown. Legacy ids are normalized only when they end with the
exact `_<timestampMs>` suffix.
Secondary indexes are checkpointed one transaction at a time; equal source/target/fixed-start
counts and unique `(sourceRecordId, timestampMs)` groups are required during validation and are
checked again under the final `BEGIN IMMEDIATE` write lock immediately before the destructive
renames. The atomic table swap removes the checkpoint and advances `user_version` to 7.
That cutover also installs Room v7's generated schema identity in `room_master_table`, so the first
Room open accepts the externally migrated schema. A unit drift check keeps the exported schema's
top-level identity, its `room_master_table` setup query, and the migrator-owned identity constant
synchronized; migration instrumentation drops `room_master_table` from its plaintext validation
copy so `MigrationTestHelper` must inspect the physical tables and indexes.
`DatabaseMigrationWorker` performs this state machine as foreground `dataSync` work and publishes
phase plus copied/total-row progress; the migration screen gates normal app content, and
Room-backed startup, sync, backup, and cleanup work remain blocked or retry until
`DatabaseReadinessGate` reports ready — that is, `user_version` has reached the externally
migrated v7 floor and has not passed `HealthDatabase.DATABASE_VERSION`. The gate's upper bound is
the `DATABASE_VERSION` constant itself rather than a hand-copied literal: Room owns every step
from v7 up to the current version, so a gate pinned to one exact version would reject the schema
Room had just migrated to and strand the app on the migration screen after the next bump.
`DatabaseMigrationController` likewise re-inspects the database before honouring a `FAILED`
`WorkInfo`, since WorkManager replays a previous run's terminal record on the next cold start and
that stale failure must not outrank a database that now reports ready.
Version 4 adds
the metadata-only `audit_events` table; it does not change Health Connect
ingestion tables or scoring formulas. Version 5 adds two nullable `daily_summaries` columns,
`supplementalSleepDurationMinutes` and `napCount`, for nap/supplemental-sleep tracking; it does
not change any other table or scoring formula. Version 6 (SCORE-001, HC-005, DB-002): adds a
nullable `workout_records.modelTrimp` column (the user-selected TRIMP model's value, lazily
backfilled by the next walk-forward recompute — see §2.3); adds the `step_records` table (13th
entity) holding raw per-record steps rows purely so a later Health Connect `DeletionChange` for
steps can resolve the deleted record's own date range (§1.2) — it is never read for scoring, daily
step totals still come from `StepCountFetcher`'s aggregate/device-filtered reads; and drops the
`daily_summaries` index on `dateMidnightMs`, redundant with that column already being the primary
key. Version 7 (DB-001) rebuilds `heart_rate_records` and `hrv_records` onto an autoincrement
`rowId` primary key: the previous `id` (the Health Connect record id) is renamed `sourceRecordId`
and is no longer unique on its own — a unique index on `(sourceRecordId, timestampMs)` replaces it,
because re-ingestion can otherwise see the same source id more than once within a resync window.
Version 8 adds the `body_temperature_records` table (14th entity) holding raw skin/body-temperature
samples ingested from Health Connect; it does not change any other table or scoring formula.
Version 9 adds a nullable `daily_summaries.avgSleepingBodyTemp` column: a nightly-average
body-temperature cache that `ScoringRepositoryImpl` computes by averaging `body_temperature_records`
samples within that day's sleep-session window, mirroring exactly how `avgSleepingSpo2` is already
computed there. It is a pure display/insight field, never read by any `domain/scoring/**` formula.
Version 10 (Option F + Option D) normalizes per-row source identity out of the hot tier and opens
the warm tier: it adds the `health_source_records` dimension table (15th entity, base UUID →
autoincrement integer id) and the `hr_minute_buckets` warm-tier aggregate table (16th entity), then
rebuilds `heart_rate_records` and `hrv_records` to reference the dimension id via an integer
`sourceRecordRef` FK (`MIGRATION_9_10`, on-delete cascade) instead of storing the full
`sourceRecordId` TEXT on every row. Idempotency moves to the unique `(sourceRecordRef, timestampMs)`
index; the base UUID is recovered by taking everything up to the first `_` of the legacy
`sourceRecordId` (`<uuid>_<timestampMs>`). See §2.6 and the 3-tier lifecycle note below.
Version 11 adds the `workout_route_points` table (17th entity, `Migration10To11`): normalized route
points per workout (`(workoutId, timestampMs)` index, cascade-deleted with the parent workout). It
adds no new primary scoring input; route points and the route-derived
`workout_records.totalDistanceMeters`/`avgSpeedKmh`/`elevationGainMeters`/`routeState` columns are
display/insight data. Route points are populated by `HealthConnectRepositoryImpl.readExerciseSessions`
— Health Connect bulk `readRecords` does **not** return exercise routes, so each session is additionally
fetched via `readRecord` and the `exerciseRouteResult` is mapped through `ExerciseSessionRecord.toDomain(routeResult)`
(`core/healthconnect/.../HealthConnectRecordConverters.kt`); a per-session route failure degrades to
`NoData` (`NOT_AVAILABLE`) so a missing/revoked route permission never aborts an exercise sync pass.
That per-record read costs one extra IPC round-trip per session, so `readExerciseSessions` takes an
`includeDetails` flag (default `true`). Ingestion (`HealthIngestionCoordinator`) passes `true`;
`discoverDevices` passes `false`, because device discovery only reads `deviceName` and would otherwise
issue one route read per workout in its scan window.
Version 12 (`Migration11To12`) adds the `index_step_records_startTime` index on `step_records` (`startTime`)
for keyset pagination, efficient range queries, and retention cleanup; keyset `pageAfter` methods added to all
13 backup-facing DAOs (`HeartRateDao`, `HrvDao`, `SleepSessionDao`, `WorkoutDao`, `DailySummaryDao`, `WeightRecordDao`,
`BodyFatRecordDao`, `BloodPressureRecordDao`, `OxygenSaturationRecordDao`, `BodyTemperatureRecordDao`, `StepRecordDao`,
`MinuteBucketDao`, `WorkoutRoutePointDao`).
Version 13 (`Migration12To13`) adds the nullable `residualFatigue` REAL column to `daily_summaries`
(`core/database/.../data/local/migration/Migration12To13.kt`); existing rows are `NULL` until the scoring
pipeline populates the value. The Residual Fatigue pipeline (per-workout impulse → exponential decay,
walk-forward accumulator, single-day fallback, shadow mode) is documented in §2.8.
Version 14 (`Migration13To14`) adds `index_workout_records_endTime_id` on
`workout_records(endTime, id)`. It preserves all workout and daily-summary rows and supports the stable
canonical residual-fatigue impulse order used by exact retained-history reconstruction.

**Workout distance and elevation come from separate records, not the session.** An
`ExerciseSessionRecord` carries no distance — the recording app writes `DistanceRecord` and
`ElevationGainedRecord` over the same window. Integrating the GPS polyline instead
(`RouteDistanceCalculator.pathDistanceMeters`) systematically under-reads by roughly 1-3% versus the
source app, because chord-summing a sampled track cuts every corner. Under `includeDetails`,
`readExerciseSessions` therefore issues **two bulk reads per window** (never per session) for those
record types and attributes them in memory via `SessionTotalsResolver` (`core/model/.../domain/util/`,
pure Kotlin): a total counts only if its `dataOrigin` package matches the session's **and** its
midpoint falls inside the session — midpoint containment keeps a boundary-straddling record in exactly
one session regardless of chunk alignment, and same-package matching prevents double-counting when a
phone writes step-derived distance alongside a watch's GPS distance. The result populates
`DomainExerciseSessionRecord.totalDistanceMeters`/`elevationGainMeters`, with `avgSpeedMps` derived
from the same distance so pace can never disagree with distance. `WorkoutMapper` keeps the
route-derived fallbacks for sources that write no such records. Both permissions
(`READ_DISTANCE`, `READ_ELEVATION_GAINED`) are **optional** — `readIntervalTotals` swallows a
permission error and returns an empty list, so an ungranted permission silently falls back to the
route-derived value instead of failing the sync pass.
The changes path (`HealthChangeSynchronizerImpl`) can never carry routes (the Changes API excludes
them), so those workouts land with `routeState = NOT_AVAILABLE` until a full resync re-reads them.
Delta-synced sessions DO get distance/elevation enrichment: the EXERCISE upsert branch reads
`DistanceRecord`/`ElevationGainedRecord` over the session's own window and attributes them via the
same `SessionTotalsResolver` rule as the full path (`sessionTotalFor`), degrading to null on a
missing optional permission — so `totalDistanceMeters` no longer depends on which sync pass wrote
the row.
On-demand single workout route sync is provided by `SyncWorkoutRouteUseCase` (`core/model/.../domain/sync/SyncWorkoutRouteUseCase.kt`):
when route permission is granted or the user opens a workout requiring permission, it reads the session from Health Connect,
maps route points, and updates the local Room database atomically via `HealthIngestionStore.persistSingleWorkoutRoute`.
**Exercise routes are not a normal Health Connect permission.** `android.permission.health.READ_EXERCISE_ROUTES` is
declared in `android.health.connect.HealthPermissions` like any data-type permission, but Health Connect keeps it out of
the bulk permission sheet: it appears only under the app's **"Additional access"** page (beside background and past-data
access) as a tri-state **Always allow / Ask every time / Don't allow**, defaulting to "Ask every time". Requesting it
alongside data types is silently dropped — verified on a clean install, where every requested data-type permission came
back with the `USER_SET` flag while routes came back with no user decision recorded at all. It is therefore **excluded
from `optionalPermissions`** and from the onboarding permission bullets (`PermissionBullets.healthPermissionLabelRes`),
while remaining **declared in `AndroidManifest.xml`** — that declaration is what makes the "Access exercise routes" row
exist at all (guarded by `HealthConnectManifestPermissionsTest`).

A workout sitting at `routeState = PERMISSION_REQUIRED` therefore has two ways out.
**(a) User set routes to "Always allow"** in Health Connect settings: `WorkoutDetailViewModel.loadWorkout` sees
`hasExerciseRoutesPermission()` return true, re-runs the use case, and the session re-read carries the route — the grant
card never appears.
**(b) Grant card → per-session consent dialog:** tapping the card runs `rememberExerciseRouteRequest`
(`app/.../ui/health/ExerciseRoutePermissionRequest.kt` — the launcher lives in the app module because feature modules may
not import Health Connect types), which launches `ExerciseRouteRequestContract` for this session id, matching the
platform's own "Ask every time" default. That dialog is a one-time grant returning the polyline in its own result;
re-reading the session afterwards still reports `ConsentRequired`. So the app module converts via
`ExerciseRoute.toDomainRoutePoints()` (`core/healthconnect/.../HealthConnectRecordConverters.kt`) and passes the points
through `WorkoutDetailViewModel.onRoutePermissionResult` into `SyncWorkoutRouteUseCase(workoutId, grantedRoutePoints)`,
which substitutes them onto the session (`routeState = IMPORTED`) before `WorkoutMapper` derives
distance/speed/elevation. An empty/absent granted list leaves the session read untouched. If the dialog cannot start, the
helper deep-links to the "Additional access" page via `android.health.connect.action.MANAGE_HEALTH_PERMISSIONS` +
`android.intent.extra.PACKAGE_NAME`, where the user can switch routes to "Always allow" and stop the per-workout prompt.
`workout_route_points` is part of the encrypted local backup: `LocalBackupManager` streams it as the
`workoutRoutePoints` JSON array (written **after** `workouts`, since the FK cascades from
`workout_records`), and `LocalRestoreManager` reloads it in that order. Without this, restore's
`workoutDao.deleteAll()` would cascade every stored route away with no way to recover it short of a
full historical resync. Backups predating the key simply carry no `workoutRoutePoints` array and are
skipped by the restore reader's `else -> skipValue()` branch.

**3-tier health-data lifecycle (hot → warm → cold).**
- **Hot tier (0–90 days):** raw 1-second `heart_rate_records`/`hrv_records` keyed by integer
  `sourceRecordRef`. `RetentionBounds.resolveHotTierCutoffMs()` is the single 90-day boundary.
- **Warm tier (90 days → retention cutoff):** 1-minute `hr_minute_buckets` per
  `(bucketStartMs, recordType, sessionId)`. `DataRollupWorker` (daily periodic) drives
  `DataRollupManager.rollupExpiredHotTier(cutoffMs)`, which atomically downsamples raw HR older than
  the boundary into buckets and deletes the raw rows — a crash can never drop a sample (either the
  raw row survives or it is already folded into a bucket). `ScoringRepositoryImpl` merges hot+warm
  minute buckets for the everyday-HR load (weighted avg is bit-identical to the raw AVG) and rebuilds
  workout exercise samples from warm buckets; `ScoringHistoryRepositoryImpl` reconstructs a sleep
  session's sample stream from its warm buckets when raw rows are gone. Hot-path reads are unchanged;
  warm fallbacks fire only when raw data is absent.
  **Plausibility tier-consistency:** the warm rollup (`MinuteBucketDao.rollupIntoBucketsBefore`) and the
  everyday-HR load reads both filter implausible samples (`beatsPerMinute BETWEEN 30 AND 230`); the
  hot-path sleep-RHR reads apply the same predicate (`HeartRateDao.getSleepHrSamplesForSession`,
  `getSleepHrProjectionForSessions`, `getAvgSleepHrForSessions`) so the sleep percentile RHR and avg RHR
  are bit-consistent whether read from raw or reconstructed warm samples. `observeSleepHrTimelineForSession`
  (UI chart) intentionally stays unfiltered.
- **Cold tier:** the permanent `daily_summaries` (computed cache). `RetentionCleanup` prunes raw
  HR/HRV and warm buckets older than the stored-scoring-zone boundary from
  `RetentionBounds.resolveRetentionCutoffMs(prefs)`; retention
  semantics are otherwise unchanged (a storage optimization, not a new user-facing data contract).

| Entity                         | Table                       | Primary key                            | Notable columns                                                                                                                                           |
| :----------------------------- | :-------------------------- | :------------------------------------- | :-------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SleepSessionEntity`           | `sleep_sessions`            | `id: String` (HC id)                   | start/end time, deep/REM/light/awake min, efficiency, `deviceName`                                                                                        |
| `SleepStageEntity`             | `sleep_stages`              | `id: Long` (auto)                      | `sessionId` (FK), `(sessionId, startTime)` unique — cleared per-session before re-upsert; queried chronologically via `SleepStageDao.getStagesForSession` and exposed via `SleepSessionRepository.getSessionStages` for fragmentation analysis |
| `HeartRateRecordEntity`        | `heart_rate_records`        | `rowId: Long` (auto)                   | `sourceRecordRef` (FK → `health_source_records.id`), `(sourceRecordRef, timestampMs)` unique; `timestampMs`, `recordType`, `sessionId`, `deviceName` |
| `HrvRecordEntity`              | `hrv_records`               | `rowId: Long` (auto)                   | `sourceRecordRef` (FK → `health_source_records.id`), `(sourceRecordRef, timestampMs)` unique; RMSSD ms, `timestampMs`, `recordType`, `sessionId`     |
| `HealthSourceRecordEntity`     | `health_source_records`     | `id: Long` (auto)                      | `sourceRecordId` (base UUID, unique), `recordType`, `createdAtMs` — normalized source identity                                                      |
| `HrMinuteBucketEntity`         | `hr_minute_buckets`         | `(bucketStartMs, recordType, sessionId)` | 1-minute warm-tier aggregates: `minBpm`/`maxBpm`/`avgBpm`/`sampleCount`; `sessionId` is `""` for no-session minutes                                      |
| `WorkoutRecordEntity`          | `workout_records`           | `id: String` (HC id)                   | zone1–5 min, TRIMP, avg HR, `startTime`, `deviceName`, `modelTrimp`; route-derived display metrics `totalDistanceMeters`/`avgSpeedKmh`/`elevationGainMeters` (nullable) and `routeState` (IMPORTED/PERMISSION_REQUIRED/NOT_AVAILABLE) — display/insight fields, never scoring inputs |
| `WorkoutRoutePointEntity`      | `workout_route_points`      | `id: Long` (auto)                      | `workoutId` (FK → `workout_records.id`, cascade delete), lat/lon/altitude, `timestampMs`, horizontal/vertical accuracy; `(workoutId, timestampMs)` indexed. Upserted alongside each workout ingest; replaced by `OnConflictStrategy.REPLACE` on identical `(workoutId, timestampMs)` — idempotent under chunked refetch |
| `WeightRecordEntity`           | `weight_records`            | `id: String` (composite)               | kg, `timestampMs`, `deviceName`                                                                                                                           |
| `BodyFatRecordEntity`          | `body_fat_records`          | `id: String` (composite)               | %, `timestampMs`, `deviceName`                                                                                                                            |
| `BloodPressureRecordEntity`    | `blood_pressure_records`    | `id: String` (composite)               | systolic/diastolic, `timestampMs`, `deviceName`                                                                                                           |
| `OxygenSaturationRecordEntity` | `oxygen_saturation_records` | `id: String` (composite)               | %, `timestampMs`, `deviceName`                                                                                                                            |
| `BodyTemperatureRecordEntity`  | `body_temperature_records`  | `id: String` (composite)               | `celsius`, `timestampMs`, `deviceName`                                                                                                                     |
| `DailySummaryEntity`           | `daily_summaries`           | `dateMidnightMs: Long`                 | computed scores (sleep/load/readiness), frozen baselines (`hrv_mu_mssd`, `hrv_sigma_mssd`, `rhr_bpm`, `rhr_sigma`, `hr_max`, …), weight/BP/SpO2/body-temp snapshots (`avgSleepingBodyTemp` — nightly average, never a scoring input) |
| `InsightDismissalEntity`       | `insight_dismissals`        | `(dateMidnightMs: Long, type: String)` | `type: String` (LATE_NADIR, SICK_INDICATOR, STRONG_RECOVERY_SIGNAL, LOAD_SPIKE_RECOVERY_STRAIN, …) — represents dismissed dashboard insights                                                       |
| `AuditEventEntity`             | `audit_events`              | `id: Long` (auto)                      | `type`, `occurredAtEpochMs`, optional coarse `detail` for local backup/restore/key-lifecycle events                                                       |

Backup/restore and key-lifecycle operations append local audit events to `audit_events` through
`AuditTrailRepository`. Audit events are metadata-only: operation type, timestamp, and coarse
result detail. They do not store health samples, backup contents, passwords, encryption keys, or
Health Connect payloads.

**Staged Restore Design:**
Restore is staged. Database replacement is atomic within Room. Preferences and layout configurations (dashboard cards, vitals layout, and sleep tab layout configurations via `SleepLayoutRepository`) are restored after the
database transaction commits because Room and DataStore cannot share a transaction. If a later
stage fails, the app returns an explicit partial-success result requiring restart and instructs
the user to rerun restore. Backup manifests v5, v6, and v7 restore into the current v7 entities.
Sleep/heart-rate/HRV/workout/daily-summary tables are cleared and replaced unconditionally on every
restore (their keys have been present in every supported backup format). The six raw-vitals tables
(weight, body fat, blood pressure, SpO2, body temperature, steps) are cleared and replaced only when
their corresponding JSON key is present in the backup being restored, so restoring an older backup
that predates these tables leaves the current local rows for them untouched.
For v5/v6 payloads, legacy HR/HRV composite IDs normalize to
`(sourceRecordId, timestampMs)` by removing only an exact trailing `_<timestampMs>` suffix.
As of v10, backups also carry `health_source_records` and `hr_minute_buckets`; HR/HRV rows
serialize the integer `sourceRecordRef` directly, and restore re-decodes older schema-7–9
`sourceRecordId`-format rows (and pre-v7 legacy rows) back into `sourceRecordRef` via
`SourceRecordDao.getOrCreateSourceRef`, resolving the base UUID the same way the migration does.

**Encryption & Key Management:**
Local encryption keys are versioned (e.g., `readylytics_master_key_v1`) and protected via Android Keystore.
On supported devices, keys are StrongBox-backed, with fallback to standard Keystore. Current key version
and StrongBox status are tracked in `KeyMetadataStore` (backed by SharedPreferences). Safe database key
rotation is managed by `DatabaseKeyRotator`, which rekeys the SQLCipher database connection in-place and
logs the operation status to the local audit trail. Keys are hardware-bound and do not support cloud backup.

**Idempotency contract:** every DAO upserts keyed on a stable identity. `HeartRateDao`/`HrvDao`
use a conflict-targeted `INSERT ... ON CONFLICT(sourceRecordRef, timestampMs) DO UPDATE SET
recordType=excluded.recordType, sessionId=excluded.sessionId, deviceName=excluded.deviceName
WHERE (mutable columns differ)` (a plain `@Query`, per-row loop in a non-abstract `upsertAll`
inside the batch transaction) — this updates mutable columns in place with a stable `rowId` and
is a near-no-op (SQLite `changes() = 0`) on an identical re-ingest, unlike the former
`@Insert(onConflict = REPLACE)` which deleted+reinserted and rotated `rowId`. The `WHERE`
predicate ensures the session-link reconciler's post-ingest re-tags still propagate. All other
DAOs use `@Upsert` keyed on the stable primary key, so
re-fetching a record replaces rather than duplicates. Workout bulk ingestion is a raw-record
merge rather than an unconditional replacement of every column. `RoomHealthIngestionStore`
updates Health Connect-owned workout fields for the stable workout `id` while preserving the
existing nullable `modelTrimp`, because that column is scoring-owned derived state. New rows and
rows already invalidated to `modelTrimp = null` remain null until their scoring day is recomputed.
Route points ride the same workout ingest: each `WorkoutInput.routePoints` list is inserted into
`workout_route_points` after deleting prior points for that workout ID via
`WorkoutRoutePointDao.deleteForWorkouts(...)` in the same transaction as the parent workout,
preventing duplicate points across repeated syncs or resync passes. The delete/insert is scoped to
**workouts whose current pass actually produced route points** — a pass that read no route (transient
`RemoteException`/IO error, or revoked route consent, both of which degrade to `NoData`) leaves the
stored points untouched. For the same reason `totalDistanceMeters`/`avgSpeedKmh`/`elevationGainMeters`
fall back to the existing row when the fresh input is null, and a stored `routeState = IMPORTED`
survives a routeless pass. This mirrors `persistSingleWorkoutRoute` and keeps the ingestion
idempotency contract (a failed pass never destroys prior valid data).
There is no blanket `deleteAll()` in the sync path — a worker that dies mid-resync leaves prior
valid data intact, and a retry re-runs the same range cleanly. `DailySummaryDao` additionally
exposes `updateBaselines()` and `clearFrozenBaselinesBetween(fromMs, toExclusiveMs)` (the only
up-front baseline mutation during sync/resync, scoped to the recomputed scoring range and rebuilt
in the same walk-forward pass).

**Stable-order scoring contract:** sleep HR/HRV DAO reads used by the scoring pipeline return
deterministic order (`timestampMs`, then stable `sourceRecordRef`, or BPM plus timestamp/ref for
percentile queries). This keeps HRV means, percentile HR picks, frozen-baseline replay, and
near-boundary Sleep/Readiness display rounding stable across app-open recalculation, background
sync, and historical resync. Recent sync and historical resync both run `SessionLinkReconciler`
after ingestion so overlap upserts cannot replace canonical session links with mapper-local links
before scoring.

### 1.5 Body Temperature — 14-day baseline, elevated-deviation threshold, and display surfaces

Raw ingestion, the nightly-average `avgSleepingBodyTemp` cache, and the entity/table shape are
covered above (§1.3, §1.4 version-9 note, entity table). This subsection covers everything built on
top of that cache — all of it deliberately outside `domain/scoring/**` and never read by any scoring
formula:

- **Baseline.** `BodyTemperatureBaselineCalculator`
  (`core/model/src/main/kotlin/app/readylytics/health/core/model/domain/service/BodyTemperatureBaselineCalculator.kt`)
  is a pure-Kotlin plain trailing average over the 14 calendar days immediately before the target
  date (`BASELINE_WINDOW_DAYS = 14`); it returns `null` ("Calibrating") until at least 14 non-null
  `avgSleepingBodyTemp` values exist in that window. `bodyTemperatureStatus(today, baseline, threshold)`
  (in `VitalAssessment.kt`, `core/model/.../domain/model/`) flags a day when `|today − baseline| >= threshold`,
  in either direction. This is intentionally independent
  of the HRV/RHR scoring-baseline machinery (`BaselineComputer`, `ScoringHistoryRepository` — see
  §2.4): a plain average rather than a log-normal EWMA, computed from the already-cached display
  field, and never persisted anywhere the scoring pipeline reads from.
  `BodyTemperatureBaselineProvider`
  (`core/model/src/main/kotlin/app/readylytics/health/core/model/domain/service/BodyTemperatureBaselineProvider.kt`)
  exposes `observeBaseline(date)` for one selected date at a time — mirroring how
  `HrvBaselineProvider` is consumed — by observing `DailySummaryRepository` emissions for the
  14-day window and delegating to the calculator. Room summary emissions (including recalculation)
  recompute this display-only baseline reactively, while scoring-zone preference changes
  independently resubscribe its window.
- **Elevated-deviation threshold.** A user-configurable preference,
  `UserPreferences.bodyTempElevatedThresholdCelsius`
  (`core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/UserPreferences.kt`), default
  `1.0f`, clamped to `[0.25, 1.5]` °C
  (`SettingsDefaults.BODY_TEMP_ELEVATED_THRESHOLD_CELSIUS` /
  `MIN_BODY_TEMP_ELEVATED_THRESHOLD_CELSIUS` / `MAX_BODY_TEMP_ELEVATED_THRESHOLD_CELSIUS`,
  `core/model/.../data/preferences/SettingsDefaults.kt`; validated by
  `SettingsValidators.BODY_TEMP_ELEVATED_THRESHOLD_RULE`). `ThresholdPreferences.updateBodyTempElevatedThreshold`
  persists it (coerced) to proto DataStore; `ThresholdSettingsViewModel`/`ThresholdSettings.kt`
  (`feature/settings/`) expose it as a slider (0.25°C step) that survives app restart. This threshold
  only feeds the dashboard-card/Vitals-chart deviation badge below — it is never read by
  `domain/scoring/**`.
- **Dashboard card.** `DashboardMetricPresentationFactory` (§3, `feature/dashboard/.../usecase/`)
  builds `CardId.BODY_TEMPERATURE`'s `UniversalMetricPresentation` from `summary.avgSleepingBodyTemp`,
  the resolved baseline, and `preferences.bodyTempElevatedThresholdCelsius`: `MetricStatus.CALIBRATING`
  when there's no reading yet, `NEUTRAL` while the baseline itself is still calibrating (shows a
  "Calibrating" secondary label), `WARNING` when `bodyTemperatureStatus(...)` (core/model
  `VitalAssessment.kt`) returns `WARNING` — `abs(value − baseline) >= threshold` — else `NEUTRAL`.
  Value/unit display converts through `UnitConverter.celsiusToDisplayTemperature`
  per `preferences.unitSystem` (°C/°F), and the secondary text shows the signed delta from baseline in
  the same display unit. The card is registered in `DashboardCardCatalog` (VALUE/BAR/GAUGE modes) and
  gated end-to-end on the optional `READ_BODY_TEMPERATURE` permission: `CardManagementDelegate`
  (`feature/dashboard/.../domain/dashboard/CardManagementDelegate.kt`) refuses to persist
  `CardId.BODY_TEMPERATURE` as visible — and `DashboardFlowIntermediate`'s card-state flow filters it
  out of the live management sheet — whenever `hasBodyTemperaturePermission()` reports `false`, so a
  revoked permission both hides the card and cannot silently re-enable it later.
- **Vitals trend chart.** `VitalsViewModel` (`feature/vitals/.../overview/VitalsViewModel.kt`) observes
  the per-day baseline via `BodyTemperatureBaselineProvider.observeBaseline(date)` alongside the existing
  HRV/RHR/SpO2 trend series. Room summary emissions (including recalculation) recalculate the
  display-only baseline stream, while scoring-zone preference changes independently resubscribe its
  window, so the baseline/legend remains aligned with chart summary updates without date navigation.
  `VitalsStateFactory` converts the raw/baseline Celsius values to the display unit and
  derives fixed chart-axis bounds (35.5–39.0 °C, unit-converted). `VitalsTrendSection` renders it as
  a fourth `TrendChart`/`TrendCard` (test tag `BodyTemperatureTrendChart`) with the baseline plotted
  as a reference line, using the same shared Vico chart component as the other Vitals trends — no
   bespoke chart implementation. All four Vitals trends share one point-construction path
   (`VitalsStateFactory.buildVitalsChartSeries`): the range selector renders raw daily points for
   7D/30D, calendar-month averages for 180D, and ISO-week-octad (8-week) averages for 360D,
   bucketed by the pure-Kotlin `bucketBy`/`buildPeriodAverageSummary` in
   `core/ui/.../common/TrendPeriodAggregation.kt`. The RHR/HRV charts additionally
   plot a muted per-bucket *historical baseline* line (`historicalRhrBaseline`/`historicalHrvBaseline`)
   derived from each day's frozen baseline (`DailyMetricsMapper.rhrBaselineRounded`/
   `hrvBaselineRounded`, frozen-only rows, honoring any override). The whole-range average
   (`historicalRhrBaselineAverage`/`historicalHrvBaselineAverage`) drives the background zone bands
   (`historicalRhrZoneBands`/`historicalHrvZoneBands`); the "Baseline" legend scalar instead prefers
   the current day's frozen baseline (`VitalsPresentationState.rhr/hrv.baseline`, the same rounded
value the dashboard displays), falling back to the whole-range average only when the current day's
    baseline is unavailable — so the chart's baseline label always agrees with the dashboard.
    This overlay spans all four ranges at range-appropriate granularity:
    7D keeps one point per frozen day (unaveraged), 30D pairs days into non-overlapping 2-day
   buckets via `bucketByFixedSize` (anchored at the window's `startDate`, independent of
   `TrendGranularity`, with each point placed on its bucket's last day so the line ends on the
   window's final day), and 180D/360D keep the existing calendar `bucketBy` path unchanged. The
   whole-range average is always the mean of every frozen per-day baseline — never a mean of
   bucket averages. The flat today-baseline `HorizontalLine` is replaced by this line whenever
   historical baseline data is present, at any range; the legend scalar is labeled "Baseline" at
   every range. The averaged ranges additionally show a
   latest-bucket-vs-previous-bucket summary row (`PeriodAverageSummaryRow`) beneath the baseline
   legend; zone-band and baseline decorations apply unchanged since bucketed points keep the same
   day-offset x-axis.

---

## 2. Processing & Scoring Engine Pipeline (SQLite → Calculations)

**All calculation logic lives in `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/**` and is pure Kotlin (zero Android
dependencies).** Scoring code reads history through the `ScoringHistoryRepository` port in
`core/model`; its methods expose pure `SleepSession`, `HeartRateRecord`, `SleepHrSample`, and
`DailySummary` values rather than Room entities or DAO projections. The data-layer implementation
maps Room rows at that boundary with `SleepSessionMapper`, `HeartRateRecordMapper`, and
`DailySummaryMapper` in `core/database/.../data/mapper/`. The engine returns domain values and the
repository maps them back for persistence; the mapper serializes recovery flags, contributors, and
diagnostics from the domain `ReadinessResult` as one coherent result. This separation is an invariant — keep Room/DAO types
out of `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/**`.

### 2.1 Coordinator

| Component                       | Path                                       | Responsibility                                                                                                                                                                                                                                                                                                                                                                                          |
| :------------------------------ | :----------------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `ScoringRepository` (interface)   | `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/ScoringRepository.kt`   | Contract for daily computation in domain types: `computeDailySummary(day): DailySummary`, `persist(summary)`, `computeAndPersistDailySummary(day, steps?)`, `toReadinessResult(summary)`. Domain callers never depend on Room entity shapes; targetDate is explicit (no ambient `LocalDate.now()` defaults) and date resolution uses injected `Clock`. |
| `ScoringDayDataLoader`            | `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringDayDataLoader.kt` | Single data loader owning all 10 Room DAOs for scoring pipeline data access: loads workouts, exercise HR samples, hot/warm merged minute buckets, sleep sessions, latest body metrics, historical daily summaries, and TRIMP points; handles `workout_records.modelTrimp` write-backs and daily summary persistence. `ScoringRepositoryImpl` delegates all SQLite DAO interactions to this loader. |
| `ReadinessSummaryCoordinator`     | `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ReadinessSummaryCoordinator.kt` | Coordinates sleep aggregation (`resolveSleepAggregation`) and calibrated/uncalibrated daily summary finalization (`computeUncalibratedSummary`, `computeCalibratedSummary`). Sits in `core:database`'s data layer (rather than pure `core:scoring`) because it is `suspend`, depends on Room DAOs via `ScoringDayDataLoader` and on `ScoringHistoryRepository`, and consumes `SleepSessionEntity` for sleep aggregation; hence the `Coordinator` suffix rather than `*UseCase`. Internal parameter-object decomposition (pure relocation, no new data hop): both compute methods now take a shared `ReadinessBaseInputs` (session, currentSessionIds, baseSummary, avgSpo2, avgBodyTemp); `computeCalibratedSummary` additionally takes `CalibratedScoringContext` (targetDate, zoneId, nextDayMidnightMs, dailyTrimpRaw, trimpEverydayHr, initialBaselines, scoringConfig, prefs, sleepDayPolicy, trimpContext, baselineContext) and its trimp-bucketing preamble was extracted into the private `resolveTrimpSeries` helper. Formulas unchanged. |
| `RasTotalsComputer`               | `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/RasTotalsComputer.kt` | Internal decomposition of `ScoringRepositoryImpl` (extracted from `computeRas`/`sumRasLastSixDays`): computes daily RAS and total-RAS fields (workout-only and everyday-HR) from raw TRIMP + frozen/session scaling factor, summing the previous six days' summaries via `ScoringDayDataLoader.loadPreviousDaysSummaries`. Pure relocation — no new data source, no schema change, formulas identical. |
| `ScoringDayContextResolver`       | `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringDayContextResolver.kt` | Internal decomposition of `ScoringRepositoryImpl`: resolves the per-day `ScoringDayContext` (scoring zone, midnight boundaries, `SleepDayPolicy`, prior `DailySummary`, frozen `InitialBaselines`, `ScoringConfig`) via `scoringConfigFactory` + `ResolveDailyBaselinesUseCase` + `ScoringHistoryRepository`. Pure relocation. |
| `DailyTrimpComputer`              | `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/DailyTrimpComputer.kt` | Internal decomposition of `ScoringRepositoryImpl`: computes workout TRIMP (`processWorkouts`, with `modelTrimp` write-back) and everyday-HR TRIMP (`resolveEverydayTrimp`), and publishes per-day TRIMP into the shared walk-forward `WalkForwardTrimpContext` (`publishTrimpToContext`). Pure relocation. |
| `BaseSummaryAssembler`            | `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/BaseSummaryAssembler.kt` | Internal decomposition of `ScoringRepositoryImpl`: builds the base `DailySummary` (TRIMP/RAS/latest-body-metrics/sleep-aggregate fields) via `ScoringDayDataLoader.loadLatestBodyMetrics`. Pure relocation. |
| `CalibrationGate`                 | `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/CalibrationGate.kt` | Internal decomposition of `ScoringRepositoryImpl`: hosts the "Calibrating" gate (`isCalibrated`, ≥ `MIN_SESSIONS_FOR_CALIBRATION` valid sessions) over the resolved `ScoringDayContext`. Pure relocation. |
| `ScoringTelemetry`                | `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringTelemetry.kt` | Internal decomposition of `ScoringRepositoryImpl`: logs the RAS config telemetry line (`appliedSf`, physiology profile, pre/post RAS totals). Pure relocation. |
| `ScoringHistoryRepository`        | `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/ScoringHistoryRepository.kt` | Pure-domain history port used by baseline and sleep scoring. `ScoringHistoryRepositoryImpl` in `core/database` owns DAO access and converts persistence rows/projections to domain models before returning them; date-bearing summary reads require the scoring `ZoneId` so `DailySummaryMapper` preserves deterministic calendar-day identity. |
| `ScoringRepositoryImpl`           | `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringRepositoryImpl.kt` | All entry points (`computeDailySummary(day)` and `computeAndPersistDailySummary(day, steps?)`) are serialized by `calculationMutex` because `computeDailySummary` writes `workout_records.modelTrimp` updates back to SQLite via `ScoringDayDataLoader`. `ScoringRepositoryImpl` no longer touches DAOs directly; all scoring data access flows through `ScoringDayDataLoader` (day loads, walk-forward trimp context, modelTrimp write-back, persist). `computeDailySummary(day)` orchestrates: data loader fetch → daily TRIMP/RAS → baseline resolve/freeze → sleep aggregation & finalize via `ReadinessSummaryCoordinator` → return domain `DailySummary`. `computeAndPersistDailySummary(day, steps?)` is the production sync entrypoint: it mutex-locks, snapshots preferences once, computes with that snapshot, optionally applies a fresh step count, then maps back through `DailySummaryMapper` and writes `DailySummaryEntity` using the **same scoring zone snapshot**. The "Calibrating" gate (< 7 sleep sessions in the last 42 days → raw sleep/recovery measurements only; Sleep Score, Load Score, and Readiness remain hidden) is hosted by the `CalibrationGate` collaborator. **Both sync flows recompute exclusively through this repository — formulas are never duplicated in the sync engine.** Day boundaries (`dateMidnightMs`), affected-date attribution, raw fetch windows, TRIMP bucketing, and persisted summary midnight all resolve through the **stored scoring timezone** (`UserPreferences.scoringZoneId` → `scoringZone()`, seeded once from the device zone by a DataStore migration), not `ZoneId.systemDefault()`, so identical SQLite + preferences reproduce identical scores across devices/timezones. Historical workout and everyday-HR TRIMP DAOs return timestamp/value rows; `TrimpDateBucketer` converts each timestamp with that stored zone before ATL/CTL aggregation, and the ATL/CTL history fetch start is derived from `targetDate.minusDays(84).atStartOfDay(zone)` rather than subtracting fixed 24-hour durations, so DST boundaries stay calendar-correct. The same stored zone is threaded through `DailySummaryMapper` when data crosses domain/storage boundary, plus `CircadianConsistencyRepository`, `HrvBaselineProvider`, `RhrBaselineProvider`, and `ComputeHistoricalBaselinesUseCase`. |

### 2.2 Resting Heart Rate (RHR) — percentile "nocturnal floor"

| Component                                          | Path                                                   | Model / inputs                                                                                                                                                                                                                                                                                                                                                                                                        |
| :------------------------------------------------- | :----------------------------------------------------- | :-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SleepPercentileRhrCalculator`                     | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/sleep/SleepPercentileRhrCalculator.kt` | `collect(session, dayMidnight, percentile)` — sorts overnight sleep HR samples and takes the configured **percentile** as the nightly resting nadir; baseline = median of historical nightly percentile values over a 30-day sleep-session window. Default percentile = `SettingsDefaults.RESTING_HR_PERCENTILE` (**5th**; user-configurable, validator range **1–15** in `domain/validation/SettingsValidators.kt`). |
| `BaselineComputer.computeAdaptiveBaselineRhrBpm()` | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/BaselineComputer.kt`                   | Live recompute variant of the same 30-day / percentile logic, filtering invalid sessions (insufficient samples / failed sleep validation).                                                                                                                                                                                                                                                                            |

### 2.3 Training Impulse (TRIMP) — multi-model engine

| Component                          | Path                                                 | Model / default                                                                                                                                                                                                                              |
| :--------------------------------- | :--------------------------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `TrimpModel` (enum)                | `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/scoring/TrimpModel.kt`                       | `BANISTER`, `I_TRIMP`, `CHENG`.                                                                                                                                                                                                              |
| `RasCalculator`                    | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/RasCalculator.kt`                    | `calculateDailyTrimp(..., trimpModel = TrimpModel.BANISTER)` switches per model — **BANISTER is the operational default** (default parameter value). `calculateDailyRas()` converts TRIMP → RAS via a profile scaling factor (capped at 75). |
| `ComputeWorkoutTrimpUseCase`       | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeWorkoutTrimpUseCase.kt`       | Per-workout integration over HR samples; reads the user-selected model from `prefs.trimpModel`. A no-sample workout with `endTime <= startTime` canonicalizes to `0f`: backup-restored Edwards `trimp` is never returned as selected-model output. |
| `ComputeDailyTrimpUseCase`         | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeDailyTrimpUseCase.kt`         | Aggregates workout TRIMP values into daily raw TRIMP and derives model TRIMP updates for workouts.                                                                                                                                            |
| `ComputeWorkoutLoadMetricsUseCase` | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeWorkoutLoadMetricsUseCase.kt` | Single per-workout load source for workout history/detail UI: resolves precise TRIMP + gained strain from DB-backed workout samples, then derives `WorkoutLoadClassification` from unrounded workout TRIMP and elapsed `durationMinutes`. **Base load** comes from total TRIMP, **intensity** comes from TRIMP/min, and intensity may promote load by at most one step; numeric TRIMP itself remains unchanged. |
| `GetWorkoutDisplayMetricsUseCase`  | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/GetWorkoutDisplayMetricsUseCase.kt`  | Unified display metrics provider for workouts. Orchestrates 42-day history fetching in the stored scoring timezone and delegates calculations to `ComputeWorkoutLoadMetricsUseCase` to return UI-ready TRIMP/strain values plus derived overall-load and intensity labels. Callers that already hold a preferences snapshot pass it through so history boundaries and calculation inputs cannot drift within one emission. |
| `ScoringConfigFactory`             | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ScoringConfigFactory.kt`             | Threads `userPreferences.trimpModel` into the scoring config.                                                                                                                                                                                |

Workout history and dashboard strain-increase observers use the same stored scoring-zone
snapshot for selected-day boundaries, tenure guards, history fetches, workout date attribution,
and per-workout display metrics. Data tenure (whether the user has at least seven days of
history under the selected `strainLoadSourceMode`) is derived per mode. Under
`EVERYDAY_HEART_RATE` it comes from the daily-summary window each observer already fetches for
its 42-day ATL/CTL lookback (`LoadSourceSelector.selectEarliestDataDate(summaries)`, counting
only rows whose everyday-HR TRIMP has actually been computed) — no extra DB query, and sound
because daily summaries exist densely, one row per ingested calendar day, so a fetch-window
slice is a safe proxy for true earliest history. Under `WORKOUT_ONLY` it comes from
`WorkoutRepository.getEarliestWorkoutTimestamp()`, a small bounded `MIN(startTime)` suspend
query: workout events are sparse, so a fetch-window-bounded derivation would understate tenure
for a user resuming after a break longer than the fetch window and silently hide the strain
delta. Their Room-backed flows remain subscribed while tenure is below the seven-day
minimum, so newly ingested history can unlock a result without a screen restart. The dashboard
observer fetches the selected day plus the preceding six days and the 42-day ATL/CTL lookback,
then delegates the final delta to the pure scoring helper; it does not persist or independently
render that value. Both observers pass their already-fetched daily-summary window into
`GetWorkoutDisplayMetricsUseCase.execute(historicalSummaries = ...)` for per-workout display
metrics, so N displayed workouts cost one 42-day history fetch, not N. That use case re-clamps
any caller-supplied window to the same 42-day span its own fetch would have used, so a wider
pre-fetched list can never shift a workout's gained strain relative to the Workout Detail
screen (which supplies no window and self-fetches).

**History pagination boundary.** Blood-pressure, workout, weight, and body-fat history are read
with Room `LIMIT/OFFSET` pagination rather than unbounded range loads.
`BloodPressureDetailViewModel`, `WeightDetailViewModel`, and `BodyFatDetailViewModel` each
combine the selected range/date with a `_currentPage` flow and read
`*Repository.getByDateRangePaged`/`countByDateRange` (newest-first
`ORDER BY timestampMs DESC, id DESC`, half-open `[fromMs, toMs)`) for the visible page, keeping
the full-range query only for the chart series. `WorkoutsViewModel` scopes the history page to
`[displayFromMs, selectedDayEndMs)` via `WorkoutRepository.getInRangePaged`/`countByTimeRange`
(`WorkoutDao.getPagedInRange`, newest-first `ORDER BY startTime DESC, id DESC`); the full 42-day
daily-summary flows stay subscribed for charts/scoring and are what re-drive the page after a
sync, since workout history is no longer a reactive `observeSince` flow. The `WORKOUT_ONLY`
daily strain delta is derived from every selected-day workout via a separate
`getInRange(selectedMidnightMs, selectedDayEndMs)` read, independent of the paged history view,
so the card total still matches the rounded per-row gains. Weight history keeps the full-range
records for the chart and per-row delta (the first row of a page may delta against a record on a
previous, older page) while rendering only the paged subset. Page size is 10 on all screens.

Daily score display values are projected through `DailyMetricsMapper` /
`DailyMetricsRepository`. UI screens may use raw `DailySummary` floats for chart
geometry and dial progress, but visible Sleep Score, Readiness, Restoration, TRIMP,
RAS, RHR/HRV baselines, SpO2, and Strain Ratio text must use `DailyMetrics`
rounded/display fields or the workout-specific `GetWorkoutDisplayMetricsUseCase`
result.

**Variants (reference only — see `RasCalculator.calculateDailyTrimp` for the implementation):**

- **BANISTER** _(default)_ — classic exponential HR-reserve TRIMP (Banister / Morton), sex-specific weighting.
- **CHENG** — LT-TRIMP, piecewise around the lactate-threshold zone (requires a zone-3 / LT bound).
- **I_TRIMP** — individualized exponential TRIMP (Manzi et al.).

**SCORE-001 — one persisted TRIMP series feeds workout-only ATL/CTL, not two.**
`WorkoutRecordEntity` (`core/database-schema/.../core/databaseschema/data/local/entity/WorkoutRecordEntity.kt`) carries two
independent per-workout values that must not be confused:

- `trimp` — zone-minutes ("zone TRIMP"), computed at reconcile time by `ZoneThresholds.computeMetrics`
  from `zone1Minutes..zone5Minutes` (Edwards-style, no HR-reserve/sex/model inputs). This is UI-only
  zone-minutes data (per-workout detail screens) and is never fed into ATL/CTL.
- `modelTrimp` — the user-selected-model TRIMP (Banister/Cheng/iTRIMP per `prefs.trimpModel`),
  written onto the entity by `ScoringRepositoryImpl.computeDailySummary`'s per-workout loop (the
  same value `ComputeWorkoutTrimpUseCase.execute` already produced for `dailyTrimpRaw`). Nullable
  additive column (v5→v6); a row keeps `modelTrimp = null` until the next walk-forward recompute
  touches it. `SessionLinkReconcilerImpl.recomputeWorkouts` cannot populate it (no RHR
  baseline/hrMax/gender available at that call site) and intentionally leaves it null.
- Backup restore accepts historical rows whose timestamps may be equal or reversed. When such a
  row has no in-range HR samples, `ComputeWorkoutTrimpUseCase` returns canonical `0f` for
  `endTime <= startTime`; it never returns the row's nonzero Edwards-style `trimp`. The daily pass
  persists `modelTrimp = 0f`, closing the startup backfill gate without publishing a fatigue
  impulse or relabeling Edwards data as selected-model TRIMP.
- `WorkoutDao.getTrimpPoints` (which feeds the workout-only ATL/CTL series in
  `ScoringRepositoryImpl.computeDailySummary`) reads `COALESCE(modelTrimp, trimp)` — rows already
  touched by a walk-forward recompute contribute their model TRIMP; untouched historical rows fall
  back to zone TRIMP until backfilled. `computeDailySummary` also injects the current day's
  freshly computed `dailyTrimpRaw` directly into that series (mirroring how `trimpEverydayHr` is
  injected into the everyday-HR series), so today's value never depends on the just-issued
  `workoutDao.upsertAll` being visible through the same bucketed read.
- Idempotent overlap refetches must never demote a previously recomputed workout from `modelTrimp`
  back to zone `trimp`. Both the bulk window path (`RoomHealthIngestionStore`) and the incremental
  changes path (`HealthChangeSynchronizerImpl`) preserve `existing?.modelTrimp` and existing route
  metadata during their stable-ID upsert. When an exercise `UpsertionChange` arrives, its scoring
  date is reported for walk-forward recompute; `DailySyncUseCase` widens and recomputes the
  contiguous affected range if the underlying HR samples or session bounds changed.
- A TRIMP-model or -parameter settings change (see 1.2.2) must invalidate every persisted
  historical day, not just a recent window, or the COALESCE transition mixes model-A and model-B
  values inside the same ATL/CTL EMA — this is exactly what `HealthDataRefresh.refreshHistorical()`
  exists to prevent.

**Banister multiplier normalization — profile-independent TRIMP magnitude.** The Banister multiplier is a
pure magnitude scaler on TRIMP output, so profile-dependent defaults made TRIMP values non-comparable
across physiology profiles. `PhysiologyProfile.banisterMultiplier` is therefore **1.0 for every profile**
(`core/model/.../data/preferences/PhysiologyProfile.kt`); `defaultChengBeta`/`defaultItrimB` stay
profile-specific (they shape the model, they do not scale its output). Users can still override the
multiplier in Advanced Settings (`rasCalibration`, range 0.5–2.5). Existing users are migrated by a
one-time DataStore migration: `PhysiologyPreferences.migrateTrimpDefaultsIfNeeded()`
(`app/.../data/preferences/PhysiologyPreferences.kt`) runs at app startup in
`DatabaseReadyStartupInitializer` (before any scoring) and normalizes via
`TrimpMigrationHelper.migrateRasCalibration(storedValue, profile, alreadyMigrated)`, setting the
`trimp_normalization_migrated` proto flag (field 90) to prevent re-running. The migration is
**profile-aware**: it normalizes both an unset `rasCalibration` (proto3 0.0) and a stored value that exactly
equals the legacy per-profile default for the user's *stored* physiology profile
(`LegacyBanisterMultipliers` — ATHLETE 1.00, ACTIVE 1.35, SEDENTARY 1.75). Any other value — including a
legacy default belonging to a *different* profile — is a genuine user override and is preserved verbatim.
Without this, every onboarded user kept the old profile default (`OnboardingViewModel.saveProfile` always
wrote `setRasCalibration(profile.banisterMultiplier)`), so the "1.0 for every profile" claim above would
have reached zero existing users. The multiplier change is a
historical-scope scoring input, so `SettingsDefaults.CURRENT_SCORING_VERSION` is bumped to **2**: startup
enqueues a recompute-only resync (`WorkerScheduler.scheduleResyncWorker(recomputeOnly = true)` — gated on
the migration succeeding) whenever `storedScoringVersion < CURRENT_SCORING_VERSION`, and
`HealthResyncWorker.persistPostRecomputeState()` bumps the stored version only after the recompute
succeeds (see §1.2.2 and the startup-gate row in §1.2).

**PERF-002/WP-20 — batched TRIMP series in the walk-forward.** The ATL/CTL/strain-ratio/load-score
assembly for both the workout-only and everyday-HR series is extracted from
`ScoringRepositoryImpl.computeDailySummary` into `BuildLoadSeriesUseCase` (pure; takes the resolved
`dailyTrimpByDate`/`everydayTrimpByDate` maps, runs the unchanged `ScoringCalculator.compute*EmaWithDecay`
math). A multi-day walk-forward (`ResyncRangeUseCase`'s RECOMPUTE phase) fetches
`WorkoutDao.getTrimpPoints`/`DailySummaryDao.getEverydayTrimpPoints` **once** for the whole range
(`HealthSyncUseCase`/`ScoringRepositoryImpl.fetchWalkForwardTrimpContext`, extended 84 days back)
into a `WalkForwardTrimpContext` (two `TreeMap<LocalDate, Float>`), instead of every recomputed day
re-querying its own 84-day lookback; each day slices that shared map (`subMap(fromDate, targetDate)`,
reproducing the exact per-day DB-query bound) and publishes its own freshly computed TRIMP value
back into the shared map for subsequent days to see — byte-identical to the per-day DB path by
construction. `ScoringRepositoryImpl.computeAndPersistDailySummary`'s single-day overload (daily
sync, ad-hoc calls) is unaffected — it still queries per day, since there's no walk-forward range to
amortize the fetch across.

**PERF-006/WP-21 — SQL-bucketed everyday-HR load.** `HeartRateDao.getMinuteBuckets(dayStart, dayEnd)`
performs the 1-minute bucketing and the 30-230 bpm plausibility filter in SQL (`GROUP BY` +
`WHERE...BETWEEN`, `ORDER BY bucketIndex ASC` so `EverydayHeartRateLoadCalculator`'s
floating-point `+=` TRIMP accumulation stays order-identical to the old Kotlin-side bucketing),
returning ≤1,440 `HrMinuteBucketRow` rows/day instead of a full-day `SELECT *` (up to 86k rows at
1 Hz). For rolled-up (warm) days, `ScoringRepositoryImpl.mergedMinuteBuckets` unions
`HeartRateDao.getMinuteBuckets` with `MinuteBucketDao.getMinuteBuckets` (the warm tier) per minute
by weighted average — bit-identical to the raw AVG — so the everyday-HR load sees the same value on
either tier. `EverydayHrLoadInput.hrBuckets` replaces the old raw-sample list; the calculator no
longer buckets or filters — it only excludes sleep/workout-overlapping buckets and runs the
zone/TRIMP formula per pre-averaged bucket. `ComputeWorkoutTrimpUseCase.HeartRateSample` (the
workout-TRIMP path's per-sample type) still consumes timestamped samples; when a workout's raw rows
have been rolled up, `ScoringRepositoryImpl.exerciseSamplesForWorkout` rebuilds a timestamped
sample stream from its warm exercise buckets, so variable-duration integration keeps working. The
fetch+assemble step is extracted into `AssembleEverydayLoadInputUseCase`.

### 2.3.1 Weekly Training Analytics — aggregation, week boundaries, activity breakdown

Pure-Kotlin weekly aggregation layer feeding the Workouts tab's weekly visualizations (weekly totals, this-week-vs-last-week daily cumulative chart, per-activity-type volume, training mix). All components live in `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/workouts/weekly/` and have zero Android dependencies.

| Component                          | Path                                                 | Responsibility |
| :--------------------------------- | :--------------------------------------------------- | :------------- |
| `WeekBounds`                       | `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/util/WeekBounds.kt` | Single source of truth for "this week"/"last week" `DateRange` boundaries given the user's configured `DayOfWeek` week start. Provides `currentWeekToDate`, `currentWeekFull`, `previousWeekFull`, and `previousWeekToDate` — all weekly aggregation must resolve through here so every visualization agrees on the same week definition. Pure Kotlin, no Android dependencies. |
| `ComputeWeeklyTrainingStatsUseCase`| `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/workouts/weekly/ComputeWeeklyTrainingStatsUseCase.kt` | Single-pass aggregation over `WorkoutData` producing `WeeklyTrainingStats` — weekly totals (`PeriodTotals`), current-vs-previous comparison (`PeriodComparison`), a 7-entry cumulative daily chart (`DailyTrainingVolume`), per-activity-type volume (`ActivityVolume`), and training mix (`TrainingMixItem`). All four visualizations share one pass so they can never disagree on workout inclusion, duration, activity classification, or week boundaries. Workout dates are resolved via the caller-supplied `ZoneId`. Comparison windows are identical for every anchor date: the current side is the configured week to date (week start through the anchor, inclusive — no fabricated days after it), and the previous side is the ENTIRE previous configured week (`WeekBounds.previousWeekFull`) — last week is finished history and is never truncated. The cumulative daily chart covers both full 7-day windows. Delegates activity-type grouping to `WeeklyActivityBreakdown`. |
| `WeeklyActivityBreakdown`          | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/workouts/weekly/WeeklyActivityBreakdown.kt` | Internal helper: per-`WorkoutLayoutType` volume aggregation (distance or duration, per `ActivityMetricTypeMapper`) and training-mix percentage calculation. Shared by Activity Volume and Training Mix so their groupings never drift apart. |
| `ActivityMetricTypeMapper`         | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/workouts/weekly/ActivityMetricTypeMapper.kt` | Maps each `WorkoutLayoutType` to `ActivityMetricType.DISTANCE` (running, walking, cycling, swimming, hiking) or `ActivityMetricType.DURATION` (strength, yoga, pilates, elliptical, rowing, stairs, HIIT, other). |
| `WeeklyTrainingModels`             | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/workouts/weekly/WeeklyTrainingModels.kt` | Domain models: `WeeklyTrainingStats`, `PeriodTotals`, `PeriodComparison`, `DailyTrainingVolume`, `ActivityVolume`, `TrainingMixItem`, `ActivityMetricType` enum. Pure data classes, no logic. |

The user-configurable week start day is persisted in `UserPreferences.weekStartDay` (proto `WEEK_START_DAY` field, default `MONDAY` via `SettingsDefaults.DEFAULT_WEEK_START_DAY`), serialized/deserialized through `UserPreferencesSerializer`/`UserPreferencesMapperExtensions`, and exposed through `UIPreferences.weekStartDay`. The `WeekStartDayPicker` composable in `core/ui` provides the selection UI.

`cumulativeDailyTraining` is presented by the "Training time comparison" card (`WeeklyVolumeTrendChartCard` in `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WeeklyVolumeTrendChart.kt`) — a Vico two-line cumulative chart rendered inside `WeeklyTrainingSection`, below the stat-card row, so both are governed by the single `WorkoutChartId.WEEKLY_TRAINING` entry in `WorkoutsChartFactory.kt`. The weekly total and its vs-last-week delta are shown only on the stat cards; the chart itself carries no headline. Both consume the same `WeeklyTrainingStats` instance already held on `WorkoutsUiState.weeklyTraining`, so no additional data fetching was introduced.

`activityVolumes` (joined with `trainingMix` for ranking) is presented by the "Activity volume" section (`ActivityVolumeSection.kt` in `feature/workouts`, chart id `WorkoutChartId.ACTIVITY_VOLUME`, also registered in `WorkoutsChartFactory.kt`): one row per current-week activity type ranked by this week's share of training time (`buildActivityVolumeRows` — pure UI-layer join, top 3 inline, full list behind "View all"), showing the type's metric per `ActivityMetricTypeMapper` with its like-for-like percent change. This is pure presentation of the already-aggregated `WeeklyTrainingStats`; no new computation or data fetching.

`trainingMix` is presented by the "Training mix" section (`TrainingMixSection.kt` in `feature/workouts`, chart id `WorkoutChartId.TRAINING_MIX`, registered in `WorkoutsChartFactory.kt`): a donut chart (Canvas arc segments) displaying the distribution of training time across activity types for the current week to date, with total training time at the donut center and an activity breakdown legend below (activity dot, icon, name, and percentage share). Aggregated via `ComputeWeeklyTrainingStatsUseCase` -> `WeeklyActivityBreakdown.trainingMix` -> `TrainingMixSection`, this is pure presentation of the already-aggregated `WeeklyTrainingStats.trainingMix`; no new computation or data fetching.

> **Not a scoring formula.** This use case performs pure aggregation (sums, counts, percentages) over already-scored `WorkoutData` from Room — it does not compute TRIMP, RAS, or any load/recovery metric. It reads Room via `WorkoutRepository.getInRange` and never touches Health Connect. Week boundaries are the only configurable input; the aggregation math itself is fixed.

### 2.4 Baselines & calibration

**Physiology profiles** are now exactly **Athlete / Active / Sedentary**
(`data/preferences/PhysiologyProfile.kt`); `Active` is the default. The removed
`GENERAL` and `SHIFT_WORKER` profiles map to `ACTIVE` at the proto read boundary
(`UserPreferences.toDomainProfile`) and are canonicalized in storage by a one-time
`DataStoreModule` migration — the proto enumerators `PROFILE_GENERAL`/`PROFILE_SHIFT_WORKER`
stay reserved (never reused) so old payloads/backups still deserialize.

`core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/BaselineComputer.kt` computes and snapshots per-day frozen baselines:
`hrMax`, `rhrBpm`, `rhrSigma`, HRV `mu`/`sigma` (with profile-prior blending for new users),
`rasScalingFactor`, and physiology profile. Baselines freeze once
calibrated (≥ 7 valid sessions); before that, `ScoringRepositoryImpl` reports
**"Calibrating"** and emits raw sleep/recovery measurements without Sleep Score,
Load Score, or Readiness.

Once a daily baseline snapshot exists, scoring for that day uses the frozen
`hrvMuMssd`, `hrvSigmaMssd`, `rhrBpm`, and `rhrSigma` values instead of
recomputing them from whatever historical rows happen to be present later.
Normal sync, 60-day resync, 365-day resync, and unlimited resync must therefore
produce the same `DailySummaryEntity` for a target day when raw records,
preferences, and scoring zone are identical.

**Displayed HRV Baseline (ms)** is the geometric statistic, `exp(mu)` where `mu` is the
frozen `hrvMuMssd` (mean of `ln(nightly RMSSD)`) — this matches the statistic Restoration
z-scores are computed against. `HrvBaselineProvider.getPreciseHrvBaseline`/
`getRoundedHrvBaseline` and `DailyMetricsMapper.hrvBaselineRounded` both resolve
`exp(hrvMuMssd)` first, falling back to `prefs.hrvBaselineOverride`, then to
`BaselineComputer.computeHrvBaseline`'s arithmetic median (ms) only as a last resort when
no geometric `mu` is available yet (e.g. very early calibration). The arithmetic median
stored on `DailySummary.hrvBaseline` is never the primary display source.

**Phase model** (`core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/components/Phase.kt` + `PhaseCalculator.kt`) classifies
each day's `totalValidHrvNights` (baseline-usable session count, computed in
`ComputeSleepMetricsUseCase`) into one of four phases, each carrying a `ConfidenceLevel`:
Calibration 0-6 (Not Ready), Early Baseline 7-20 (Low), Maturing 21-59 (Medium), Mature 60+
(High). The result is persisted per day as `snapshotCalibrationPhase` on
`DailySummaryEntity`/`DailySummary` for dashboard + About display. This is independent of
the diagnostic, days-since-install `phaseName` inlined in `AuditTrailFactory` (debug/audit
trail only, not part of `computeConfigHash`).

The historical backfill (`core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/BackfillHistoricalBaselinesUseCase` →
`ComputeHistoricalBaselinesUseCase`) runs at app start under `HealthSyncUseCase.withSyncLock`
(serialized against daily sync / resync, so it never reads or rewrites a day mid-walk-forward) and
is **incremental, true-freeze**: it only computes baselines for rows whose
`baselineCalculatedAtDate` is still `null` (never-frozen — newly ingested history, or a day the
batched computation couldn't produce a value for yet). A day that already has a frozen baseline is
never wiped or rewritten by this pass, so a second consecutive launch with no new unfrozen days is
a 0-write no-op, and `DataCleanupWorker`/`RetentionCleanup` deleting old raw rows past retention can
no longer silently change an already-frozen day's stored baseline on the next launch. It resolves
all per-day HRV/RHR windows for the unfrozen subset via the **batched**
`BaselineComputer.computeBackfillBaselines()` — a fixed, small number of DB reads for the whole
subset instead of ~11 queries per day — which reproduces the per-day `compute*Between`
window/validity/nadir math exactly. Daily baseline upper bounds are treated as exclusive next-day
midnights before hitting Room's inclusive `getBetween` predicate, so a session ending exactly at
the next midnight belongs to the next day. The same backfill path also carries the RHR history used
to freeze `rhrSigma` for later RHR z-score restoration (guarded by equivalence tests). The per-day
UPDATEs are collapsed into a single transaction by the backfill use-case.

**PERF-002/WP-22 (resync/daily-sync walk-forward baselines):** the sleep-session-to-per-day
aggregation machinery (`filterValidBaselineSessions`, `buildHistoricalSleepDays`, and the
`HistoricalSleepDay` per-night data class) is extracted out of `BaselineComputer` into
`HistoricalSleepDayAssembler` (same package), shared by every windowed (`*Between`) and live
(`dayMidnight`-anchored) baseline method. `computeAdaptiveBaselineRhrBpmBetween`/
`computeHrvBaselineBetween`/`computeHrvWindowsBetween` each gained an optional `prefetchedSessions`
parameter (default `null`, behavior unchanged): the RECOMPUTE walk-forward calls
`BaselineComputer.prefetchWalkForwardSessions(startDate, endDate, zoneId)` once (covering the
widest lookback, `maxOf(56, 60)` = 60 days, covering both the 56-day HRV sigma window and the
60-day circadian consistency window) and passes the same in-memory list to every recomputed day,
which slices it to that day's exact `startTime >= from AND endTime <= to` bound instead of
re-querying the DB. Circadian regularity resolution (`SleepModifierResolver` → `CircadianConsistencyRepository.scoreFor`)
likewise consumes this prefetched superset without issuing per-day session queries — same
batched-I/O-only pattern as WP-20's TRIMP series, same equivalence guarantee (identical math,
only the data source changes).

### 2.4.1 Biphasic sleep-day aggregation

`core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringRepositoryImpl.kt`
keeps raw sleep rows intact. `SleepSessionEntity` and `SleepStageEntity` remain the persisted
source records; the scorer derives a day-specific domain aggregate on demand instead of rewriting
those raw tables.

`resolveSleepAggregation(targetDate, zoneId, prefs)` fetches overlapping sleep sessions from the
core scoring window around the target day, builds `SleepDayPolicy` from the current settings, and
projects the raw sessions into `SleepDayAggregate` via `SleepDayAggregator.aggregateForScoreDay(...)`.
The aggregate is score-day aware: `supplementalCutoffMinutesOfDay` decides whether a segment belongs
to the current score day or rolls forward to the next day, `coreMergeGapMinutes` defines the merged
core cluster, and `minimumCountedSleepSegmentMinutes` filters out short fragments.

The aggregate contract is:
- raw sessions and stages stay stored unchanged
- the longest merged cluster becomes the core sleep block
- tie-breaks are deterministic: longer total duration, later final wake time, earlier start time,
  then the smallest stable session id
- non-core segments become `SupplementalSleepBlock`s
- total duration is `core + supplemental`
- stage totals are `core stage totals + architecture-eligible supplemental stage totals`
- HRV / RHR / restoration inputs use the core window only through the derived `scoringSession`

`canonicalizeOverlaps(...)` makes overlap resolution deterministic before aggregation. It prefers
segments marked as coming from the selected source, then resolves by duration, tracked-stage
coverage, and stored source identity. If package metadata is not available for a record, the stored
source/device fallback captured on ingest is used so the overlap choice stays stable across daily
sync, historical resync, retention-window changes, and restarts.

### 2.5 Sleep & Load scoring strategies

| Component                    | Path                                                | Output                                                                                                                      |
| :--------------------------- | :-------------------------------------------------- | :-------------------------------------------------------------------------------------------------------------------------- |
| `SleepScoringStrategy`       | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/strategies/SleepScoringStrategy.kt` | Sleep score = **Duration 40% + Architecture 20% + Restoration 25% + Fragmentation 15%** (default Balanced profile; selectable profiles). Continuous logistic curves for duration and efficiency, configurable oversleep dead zone (`hypersomniaOnsetRatio`), and schedule regularity penalty-only multiplier (0.92–1.00). |
| `SleepFragmentationCalculator` | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/sleep/SleepFragmentationCalculator.kt` | Pure Kotlin. Calculates Wake After Sleep Onset (`wasoMinutes`, wakefulness strictly between sleep onset and final awakening) and discrete awakening count (awake segments ≥90s) with adult grace allowance (20 min WASO / 2 awakenings). |
| `SleepModifierResolver`      | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/sleep/SleepModifierResolver.kt` | Assembles `SleepModifiers` (`fragmentation` and `regularityScore`). Fetches stages via `SleepSessionRepository.getSessionStages(sessionId)` (`SleepStageDao.getStagesForSession`) and live regularity via `CircadianConsistencyRepository.scoreFor(targetDate)`. Suppresses fragmentation when stages are missing or suspicious while retaining regularity. |
| `LoadScoringStrategy`        | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/strategies/LoadScoringStrategy.kt`  | Load score from the **Strain Ratio** (ATL/CTL): `sr ≤ 1.3 → 100`, `sr > 1.3 → 100·exp(−2.5·(sr−1.3)²)`. Feeds the readiness composite (0.4 restoration + 0.3 sleep + 0.3 load). Delegates recovery flag evaluation to `RecoveryFlagEvaluator`. Only `ILLNESS_ONSET` (cap 50) caps the readiness number and requires two consecutive nights; strong recovery, workout-impact, and rest-day flags are informational only and do not cap the score. |
| `RecoveryFlagEvaluator`      | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/components/RecoveryFlagEvaluator.kt` | Evaluates daily `RecoveryFlag` set (`CALIBRATING`, `HRV_MISSING`, `SUSPICIOUS_STAGE_RATIO`, `NADIR_DELAYED`, `ILLNESS_ONSET`, `STRONG_RECOVERY_SIGNAL`, `WORKOUT_IMPACT`, `REST_DAY_SUCCESS`, `REST_DAY_NO_IMPACT`). Suppresses HRV-comparison insights when HRV is missing. |
| `RasScoringStrategy`         | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/strategies/RasScoringStrategy.kt`   | **CTL (42-day)** and **ATL (7-day)** exponential moving averages of daily TRIMP.                                            |
| `ComputeSleepMetricsUseCase` | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeSleepMetricsUseCase.kt`      | Consumes the `SleepDayAggregate` / core-isolated `scoringSession` from `ScoringRepositoryImpl.resolveSleepAggregation(...)`, then assembles sleep/readiness metrics for the day from the strategies + baselines + modifiers. Delegating Z-score calculations to `BaselineZScoreComputer` and restoration scoring to `RestorationScoreAssembler`. |
| `BaselineZScoreComputer`     | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/BaselineZScoreComputer.kt`         | Pure collaborator for `ComputeSleepMetricsUseCase`. Computes HRV and RHR Z-scores, and nocturnal RHR delta BPM against active or frozen baselines. |
| `RestorationScoreAssembler`  | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/RestorationScoreAssembler.kt`      | Pure collaborator for `ComputeSleepMetricsUseCase`. Assembles restoration score `sRest` with saturation bounds and late-nadir penalty. |
| `CircadianConsistencyRepository` | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/CircadianConsistencyRepository.kt` | Live bed/wake-time consistency score. The allowed deviation **threshold** resolves through the single `CircadianThresholdDefaults.resolveThreshold(profile, override)` (Athlete 20 / Active 30 / Sedentary 45 min; override wins). The encrypted `circadianThresholdOverride` is the user knob; a legacy non-default flat `consistencyThresholdMinutes` is honored as an override for back-compat. `Ready` presentation status delegates to the shared `core:model` circadian-status classifier; `Calibrating` and `MissingData` keep their dedicated statuses. The former per-profile strategy classes are deleted — there is now one resolver. Exposes `scoreFor(targetDate)` as a direct scoring input for the sleep regularity multiplier. |

Supporting helpers live in `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/components/` and `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/sleep/`
(architecture targets via `SleepArchitectureTargetFactory`, continuity curves via `SleepContinuityCurves`, restoration weights, nadir analysis, HR coverage validation).
`CircadianThresholdDefaults` (`core/model/src/main/kotlin/app/readylytics/health/core/model/domain/circadian/`) is the single threshold source, consumed
by both the live repository above and the diagnostic config built in `ScoringConfigFactory`. `ScoringConfig` encapsulates scoring settings (including `hypersomniaOnsetRatio` and `sleepWeightProfile`) in its deterministic config hash.

**HC-006/WP-11 — stage-less sessions reweight instead of scoring 0%.** A stage-less HC
sleep session has zero deep/rem/light minutes despite a positive (raw-span fallback) duration.
`LoadScoringStrategy.validateNight`'s deep/rem *fraction* checks read 0/duration as trivially valid
(not the intended signal), so `ComputeSleepMetricsUseCase` additionally flags `stagesSuspicious =
true` whenever `deepSleepMinutes == remSleepMinutes == lightSleepMinutes == 0` with
`durationMinutes > 0`. `stagesSuspicious` is the same flag `SleepScoringStrategy.computeSleepScore`
already used for its Architecture-unavailable reweight: Architecture and Fragmentation drop out (0%),
and the score renormalizes Duration and Restoration according to the active weight profile.
`ScoringRepositoryImpl.toSleepDaySegment` and `BaselineComputer.toSleepDaySegment` both also fall back
`durationMinutes` to the raw session span when an **already-persisted** session still carries the pre-fix
stored `durationMinutes = 0` (e.g. during a settings-driven recompute-only pass, which never re-reads
Health Connect) — this is what actually prevents `SleepDaySegment`'s `durationMinutes > 0` invariant from
throwing for those rows.

### 2.6 Everyday Heart-Rate Load

| Component | Path | Output |
| :--- | :--- | :--- |
| `EverydayHeartRateLoadCalculator` | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/EverydayHeartRateLoadCalculator.kt` | Pure Kotlin. **PERF-006/WP-21:** consumes already SQL-bucketed, already plausibility-filtered `HrMinuteBucketRow` rows (`HeartRateDao.getMinuteBuckets`) — no longer buckets or filters raw samples itself. Classifies each bucket's `avgBpm` via `HrZoneClassifier` and accumulates per-minute TRIMP via `RasCalculator.calculateDailyTrimp` (Zone 0 excluded from TRIMP, included in `coverageMinutes`). Returns `EverydayHrLoadResult` (`nonWorkoutTrimp`, `totalEverydayTrimp = workoutOnlyTrimp + nonWorkoutTrimp`, `coverageMinutes`, `validBucketCount`, `confidence: LoadCoverageConfidence`). |
| `AssembleEverydayLoadInputUseCase` | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/AssembleEverydayLoadInputUseCase.kt` | PERF-006/WP-21/UI-002 extraction from `ScoringRepositoryImpl`. Pure; builds `EverydayHrLoadInput` from the caller-resolved bucket rows + intervals and runs `EverydayHeartRateLoadCalculator`. |
| `AssembleDailySummaryUseCase` | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/AssembleDailySummaryUseCase.kt` | Pure summary assembler for uncalibrated and calibrated pipeline branches in `ScoringRepositoryImpl`. |
| `BuildLoadSeriesUseCase` | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/BuildLoadSeriesUseCase.kt` | PERF-002/WP-20/UI-002 extraction from `ScoringRepositoryImpl`. Pure; runs `ScoringCalculator.compute*EmaWithDecay`/`computeStrainRatio`/`computeLoadScore` for both the workout-only and everyday-HR series given the caller-resolved `dailyTrimpByDate`/`everydayTrimpByDate` maps. |
| `WalkForwardTrimpContext` / `WalkForwardBaselineContext` / `WalkForwardFatigueContext` | `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/` | PERF-002/WP-20/WP-22. Held by a multi-day walk-forward (`ResyncRangeUseCase`'s RECOMPUTE phase) across every recomputed day: the TRIMP series (two `TreeMap<LocalDate, Float>`, fetched once via `ScoringRepository.fetchWalkForwardTrimpContext`), the RHR/HRV baseline sleep-session superset (fetched once via `fetchWalkForwardBaselineContext`, delegating to `BaselineComputer.prefetchWalkForwardSessions`), and — WP-27, see §2.8 — the residual-fatigue workout-impulse series + running accumulator (`fetchWalkForwardFatigueContext`). |
| `ScoringRepositoryImpl.computeDailySummary` | `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringRepositoryImpl.kt` | Fetches the day's SQL-bucketed HR rows (`HeartRateDao.getMinuteBuckets`) plus sleep/workout intervals, feeds them to `AssembleEverydayLoadInputUseCase`, then `BuildLoadSeriesUseCase` for the ATL/CTL/strain/load assembly, and persists both `*WorkoutOnly` and `*EverydayHr` variants (TRIMP, RAS, total RAS, ATL, CTL, Strain Ratio, Load Score, Readiness) plus `everydayCoverageMinutes`/`everydayLoadConfidence` on `DailySummaryEntity`. Accepts one `WalkForwardContexts` holder carrying the optional `WalkForwardTrimpContext`/`WalkForwardBaselineContext`/`WalkForwardFatigueContext` (single `computeAndPersistDailySummary` signature, §2.6/§2.8) to slice shared in-memory data instead of re-querying per day. `ComputeResidualFatigueUseCase` is injected like every other scoring use case (grouped with them in `ScoringDayUseCases`), never hand-constructed. |
| `LoadSourceSelector` | `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/model/LoadSourceSelector.kt` | Pure mode-aware projection: `select*` functions pick the `*WorkoutOnly`/`*EverydayHr` variant column or derived value (TRIMP, ATL, CTL, strain ratio, RAS, earliest data date) matching `strainLoadSourceMode`/`rasSourceMode`. Zero Android dependencies. |
| `DailyMetricsMapper` | `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/model/DailyMetricsMapper.kt` | Builds `DailyMetrics` exclusively through `LoadSourceSelector` for all user-visible strain/load/RAS/readiness fields, so switching either source preference re-projects already-stored data instantly with no recompute. |

The two source preferences (`strainLoadSourceMode`, `rasSourceMode`, both on `UserPreferences`)
are independent: Readiness always derives from `strainLoadSourceMode`, never from
`rasSourceMode`. Coefficients and thresholds live solely in
`EverydayHeartRateLoadCalculator.kt` / `ScoringConstants` — see ABOUT.md for the
user-facing description of `coverageMinutes`, `validBucketCount`, and confidence tiers.

### 2.7 Scoring Regression Testing & Golden Fixtures

To guard scoring calculations against unintended architectural or algorithmic regressions, the repository maintains characterization golden snapshot fixtures:

| Test Suite | Path | Responsibility |
| :--- | :--- | :--- |
| `ScoringGoldenSnapshotTest` | `core/database/src/test/kotlin/app/readylytics/health/core/database/domain/scoring/golden/ScoringGoldenSnapshotTest.kt` | Locks 6 representative single-day execution paths against checked-in JSON fixtures in `core/database/src/test/resources/golden/` (`day_with_workouts_and_frozen_snapshot.json`, `day_with_sleep_spanning_midnight.json`, `day_with_no_sleep_session.json`, `day_with_early_return_uncalibrated.json`, `day_with_hrmax_from_prefs_vs_snapshot.json`, `day_with_nap_and_supplemental_sleep.json`). Uses mock-free fakes and in-memory `HealthDatabase`. Regenerated via `-Dupdate.golden=true`. |
| `GoldenFixtureWalkForwardTest` | `core/database/src/test/kotlin/app/readylytics/health/core/database/domain/scoring/golden/GoldenFixtureWalkForwardTest.kt` | Multi-year walk-forward regression test locking end-to-end multi-day scoring state against `core/database/src/test/resources/golden/scoring_walk_forward_golden.json`. |
| `ScoringEquivalenceGoldenTest` | `core/database/src/test/kotlin/app/readylytics/health/core/database/domain/scoring/golden/ScoringEquivalenceGoldenTest.kt` | Verifies mathematical equivalence between hot-tier (raw 1s samples) and warm-tier (1-minute bucket rollups) TRIMP and sleep percentile RHR calculation paths. |

### 2.8 Residual Fatigue (shadow mode)

Residual Fatigue models the decay of recent training load as an exponentially decaying sum of per-workout
TRIMP impulses. **Phase 1 is strictly shadow mode:** the value is computed and persisted on every
`DailySummary` but never read by any user-visible score. It **does not modify Readiness**, Load Score, or any
other user-facing score — `computeReadinessScore()` is untouched, Readiness stays
`0.40 · Restoration + 0.30 · Sleep + 0.30 · Load`, and no formula consumes `residualFatigue`.

**Pipeline & formula location.** Each workout contributes an impulse of `fatigueGain · trimp` keyed by its
`endTime`, decaying with half-life `halfLifeHours`. An impulse always uses the selected-model canonical
per-workout TRIMP calculated by `ComputeWorkoutTrimpUseCase`; Edwards `trimp` is never a fallback. During a
walk-forward, `ComputeDailyTrimpUseCase` publishes every computed per-workout value (including zero) beside
the daily total, and `DailyTrimpComputer` registers those values with the fatigue context immediately after
the workout pass. Everyday-HR TRIMP is **never** an impulse source, so the value is independent of
`LoadSourceMode`. The formula — at
evaluation time `t`, `F(t) = Σ gain · trimp_i · 2^(−(t − end_i) / halfLife)` — lives in pure Kotlin in
`ComputeResidualFatigueUseCase` (`core/scoring/.../domain/scoring/`): `compute()` is the stateless
summation, `advanceAccumulator()` the incremental decay+add step used by the walk-forward. `ResidualFatigueComputer`
(`core/database/.../data/repository/`) owns the per-day snapshot: it resolves the config from the
preferences snapshot and evaluates at **next-day midnight in the stored scoring zone**
(`ScoringDayContext.nextDayMidnightMs`), and `FinalSummaryAssembler` stamps the result onto the assembled
`DailySummary` (`DailySummary.residualFatigue`, persisted `NULL` when disabled). Zero-TRIMP and future
workouts contribute nothing; rest days add no impulse, fatigue simply decays.
Equal/reversed-timestamp backup rows with no usable HR canonicalize to zero before publication, even when
their stored Edwards `trimp` is nonzero and `modelTrimp` was `NULL`; zero is persisted for convergence and
never enters the positive-only fatigue accumulator.

Every snapshot is therefore an *end-of-day* value. For the current day the evaluation point is in the
future, so today's row is a projection to the end of today, not fatigue at the moment of the sync. This
keeps the series deterministic and wall-clock independent; a live "fatigue now" evaluation is out of scope
for Phase 1 and must not be derived by reading the stored current-day value. The evaluation point must not
be capped at `now` — that would make a day's value depend on when it happened to be recomputed.

**Parameter validation.** `ResidualFatigueConfig` requires finite `halfLifeHours`/`fatigueGain` in its
`init` block, and `ResidualFatigueConfig.clamped(...)` — the constructor `ResidualFatigueComputer` uses —
coerces stored preferences into the `SettingsDefaults.MIN_/MAX_RESIDUAL_FATIGUE_*` bounds, falling back to
the shipped default for a non-finite value. Coercion rather than rejection is deliberate: a bad stored pref
degrades that day to the nearest valid parameter instead of failing the recompute. `compute()` and
`advanceAccumulator()` additionally return a finite zero on a non-positive half-life, since `halfLifeMs` is
the divisor in the decay exponent and a zero would otherwise produce a `NaN` that reaches
`daily_summaries` and the backup JSON.

**Walk-forward integration (WP-27).** Both walk-forward orchestrators (`DailySyncUseCase`,
`ResyncRangeUseCase`) build one `WalkForwardFatigueContext` per run via
`DailyRecomputeSupport.buildWalkForwardFatigueContext(...)` (alongside the WP-20 TRIMP and WP-22 baseline
contexts, as one `WalkForwardContexts` holder) and pass it oldest-day-first to every recomputed day. The context prefetches only historical seed
rows whose workout `startTime` precedes the walk-forward start boundary. Historical resync starts at the
`RetentionBounds.HistoricalWindow.startDate`, whose scoring-zone midnight is the same `startTimeMs` used by
startup's `countUnbackfilledSince` gate and retention cleanup; therefore a row is either inside all three
domains (`startTime >= startTimeMs`) or outside all three, including when the device zone differs.
`WorkoutDao.getCanonicalFatigueSeed`
reads every such retained row with non-null positive `modelTrimp` from Room in stable `(endTime, id)` order;
there is no bounded lookback, Health Connect query, or extra raw-HR scan. Partitioning by workout start keeps a
boundary-straddling workout in the seed so it is neither recalculated nor omitted; if its end is after the first
evaluation, it remains pending. Current-range persisted TRIMP is never preloaded. A priority queue ordered by
`(endTime, workoutId)` receives both seed rows and freshly calculated canonical current-range outputs.
Registration is idempotent by workout ID, so a retried day cannot add an impulse twice, and each evaluation
drains only entries whose end is at or before that timestamp. After the deterministic Room/index and queue
ordering cost, reconstruction and evaluation are **O(W + D)**.

**Never-backfilled canonical TRIMP (HIGH-2).** `modelTrimp` is backfilled lazily by the walk-forward, so a
retained workout that no walk-forward has touched (restore from a pre-SCORE-001 backup, or a day whose
recompute failed) has `modelTrimp IS NULL`. The seed and fallback queries filter those rows out — correct,
since Edwards `trimp` is never a fatigue fallback — which would otherwise leave the day silently *low*
rather than *unknown*. Two things address that. First, `WorkoutDao.countUnbackfilledBefore` /
`countUnbackfilledThrough`, surfaced by `ScoringDayDataLoader.loadUnbackfilledCountBefore` /
`loadUnbackfilledCountThrough`, let the fatigue paths detect a dropped row (one `COUNT(*)` per walk-forward,
not per day). Second, startup self-heals: `WorkoutDao.countUnbackfilledSince` behind the
`WorkoutTrimpBackfillStatus` port (`core/model`, implemented by `WorkoutTrimpBackfillStatusImpl` in
`core/database`) is a second gate on `DatabaseReadyStartupInitializer.scheduleRecomputeResyncIfNeeded`,
alongside the stale-`scoringVersion` gate — both share one `scheduleResyncWorker(recomputeOnly = true)`
enqueue per launch, bounded by the scoring-zone `RetentionBounds.resolveHistoricalWindow(prefs).startTimeMs`.
That is the same instant as cleanup's cutoff and the first recompute day's midnight, so a device/scoring-zone
gap cannot retain and repeatedly enqueue a row that the walk-forward can never touch. It converges: a recompute
writes `modelTrimp` for every workout it touches (including `0f`), so the count reaches zero and the gate
stops firing. This also closes the matching `COALESCE(modelTrimp, trimp)` inconsistency in ATL/CTL.
The remaining piece closes the loop: `WalkForwardFatigueContext.seedIncomplete` is set from
`loadUnbackfilledCountBefore` when a walk-forward context is fetched, and `ResidualFatigueComputer.compute`
returns `null` — not a low value — whenever that flag is set (walk-forward path) or
`loadUnbackfilledCountThrough` is nonzero (single-day fallback path). A partial walk over never-backfilled
history therefore persists `NULL` for every affected day until the startup self-heal's recompute-only
resync lands and backfills the retained workout — at which point the seed is complete again and the walk
reconstructs the same exact value as a full walk from `historyStartDate`.

The **single-day fallback** (no walk-forward, e.g. ad-hoc day recompute) analogously reads every retained
canonical impulse with `endTime <= evaluationTime` and sums it with
`ComputeResidualFatigueUseCase.compute()`. Thus full, partial, resumed, and single-day walks reconstruct the
same exact retained-history value. `advanceAccumulator` remains the single source of truth for the incremental
step, keeping it mathematically identical to the summation path (exponential decay + superposition). Both
paths stay shadow-only and independent of workout-only versus everyday-HR load-source mode.

**Settings (proto fields 91–93).** `residual_fatigue_enabled` / `residual_fatigue_half_life_hours` /
`residual_fatigue_gain` on `user_preferences` — defaults `true` / 24 h / 1.0 via
`SettingsDefaults.RESIDUAL_FATIGUE_*`, guardrails 6–96 h / 0.1–5.0 enforced in
`PhysiologyPreferences.toValidFatigueHalfLife`/`toValidFatigueGain`,
`SettingsValidators.FATIGUE_HALF_LIFE_RULE`/`FATIGUE_GAIN_RULE`, and at the domain boundary by
`ResidualFatigueConfig.clamped`. The two sliders in `AdvancedResidualFatigueSection` use
`RESIDUAL_FATIGUE_HALF_LIFE_SLIDER_STEPS = 89` and `RESIDUAL_FATIGUE_GAIN_SLIDER_STEPS = 48`: M3
`Slider.steps` counts the stops *between* the endpoints, so those give exactly 1-hour and 0.1 increments
and put the documented 24 h / 1.00 defaults on a stop. Resolved per-day from the preferences
snapshot inside `ResidualFatigueComputer.compute`. Changing fatigue settings invalidates resync
checkpoints and triggers a historical recompute via `HealthDataRefresh.refreshHistorical()`.

---

## 3. Presentation Layer (Calculated States → UI)

### 3.1 ViewModels → `StateFlow`

ViewModels collect repository flows, fuse them with `combine()`, and expose immutable
`*UiState` via `stateIn()`. Screens collect with `collectAsStateWithLifecycle()`.

| ViewModel                                                                                                   | Path                                                          | Exposes                                                                                                                                                                                                                    |
| :---------------------------------------------------------------------------------------------------------- | :------------------------------------------------------------ | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `DashboardViewModel`                                                                                        | `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModel.kt` | `uiState: StateFlow<DashboardUiState>` (summary, card map, circadian, RAS, `recalcProgress`); `onRefresh()` → `foregroundSyncController.triggerDailySync()`. Dashboard presentation factories classify raw metric values once to create card status, while `DashboardMetricScalePreparer` provides marker geometry and unavailable reasons only. The overnight-average SpO2 card uses: below 90% Poor; 90–94% Warning; 95–97% Neutral; 98% and above Optimal. |
| `SyncViewModel`                                                                                             | `ui/sync/SyncViewModel.kt`                                    | `uiState` (sealed sync state machine), `isSyncing`, `recalcProgress` (forwarded from `ForegroundSyncController`).                                                                                                          |
| `VitalsViewModel`                                                                                           | `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsViewModel.kt`         | HRV / RHR / SpO2 / body temperature trends (daily points for 7D/30D, monthly averages for 180D, quarterly averages for 360D) + baseline bands (body temperature via `BodyTemperatureBaselineProvider` — see §1.5).                                                                                     |
| `SleepViewModel`                                                                                            | `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepViewModel.kt`                      | Sleep summary, stage timeline, circadian consistency, sleep window/duration trend data, sleep HR timeline.                                                                                                                                    |
| `WorkoutsViewModel` / `WorkoutDetailViewModel`                                                              | `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/`                                 | Daily TRIMP/strain trends, RAS breakdown; per-workout TRIMP/strain/HRR. Per-workout load cards/rows consume `ComputeWorkoutLoadMetricsUseCase` so history and detail show the same rounded TRIMP and gained-strain values plus the same derived **overall load** and optional **intensity** label. For post-workout HRR, `WorkoutDetailViewModel` extends both the Health Connect pull and Room heart-rate query through `workout end + 3 minutes + hrrToleranceSeconds`; `RecoveryMetricsMapper` then matches each 1/2/3-minute target to the nearest sample inside an inclusive `±hrrToleranceSeconds` window (default `30` seconds). The workout chart and end-of-workout HR input stay clamped to samples at or before the workout end, so the extended recovery read does not widen the plotted workout timeline or shift `endHr`. |
| `HeartRateDetailViewModel`                                                                                  | `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/heartrate/HeartRateDetailViewModel.kt`| Intra-day HR samples + zone totals.                                                                                                                                                                                        |
| `StepDetailViewModel` / `WeightDetailViewModel` / `BloodPressureDetailViewModel` / `BodyFatDetailViewModel` | `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/`                                     | Per-metric trends, statuses, formatted display.                                                                                                                                                                            |

### 3.2 UI state wrappers

`@Immutable` data classes: `DashboardUiState` (+ intermediates in
`feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardFlowIntermediate.kt`), per-screen
`*UiState` (`HeartRateDetailUiState`, `VitalsUiState`, `SleepUiState`, `WorkoutsUiState`, …),
and `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/SettingsState.kt` (incl. `SyncSettingsState` with resync current/total
progress).

**PERF-005/WP-23 — HR observation.** `DashboardFlowIntermediate.createDashboardHrFlow` (dashboard
day-summary card) observes `HeartRateRepository.observeAggregateByTimeRange` — SQL
`MIN`/`MAX`/`AVG`/`COUNT` (`HeartRateDao.observeAggregateByTimeRange`, `HAVING COUNT(*) > 0` so an
empty range maps to `null` rather than a row of NULLs) — instead of the full raw-row
`observeByTimeRange`, so a resync's 5,000-row ingest batches invalidating the Flow re-run a
single-row aggregate instead of re-materializing/re-mapping the whole day. `HeartRateDaySummary` no
longer carries `hourlySamples` (the hourly bucket it fed was computed but never rendered by any live
UI). `HeartRateDetailViewModel` (which genuinely needs the full per-sample series for its
chart/zone-totals) keeps observing raw rows via `observeByTimeRange`, but `.debounce(500)`s the Flow
so a burst of ingest-batch invalidations collapses into one re-render.

### 3.3 Compose render & visualization components

| Component                                                                                     | Path                                              | Role                                                                                                                                       |
| :-------------------------------------------------------------------------------------------- | :------------------------------------------------ | :----------------------------------------------------------------------------------------------------------------------------------------- |
| `MainScaffold` / `RecalcProgressBanner`                                                       | `ui/scaffold/MainScaffold.kt`                     | Root scaffold, bottom nav, pull-to-refresh; renders the determinate "Recalculating day X of Y" banner (`R.string.recalculating_progress`). |
| `AboutScreen` / `AboutViewModel`                                                              | `feature/about/`                                  | Feature-owned About explanation flow and dismissal preference write (`AboutPreferences.updateAboutDismissed(true)`).                         |
| `InsightDetailSheet` / `InsightDetailRepository`                                              | `feature/insights/`                               | Feature-owned insight explanation sheet + resource-backed detail assembly. `MainNavHost` owns selected insight state and composes the sheet for dashboard via callback + composable slot, avoiding feature-to-feature edges. |
| `M3ScoreGaugeCard`                                                                             | `ui/components/M3ScoreGaugeCard.kt`                | Soft Arc Metric Card gauge layout (status-colored) with comparison delta pill.                                                              |
| `MetricCard` / `MetricTooltip`                                                                | `ui/components/MetricCard.kt`, `MetricTooltip.kt` | Status-colored metric cards with tooltips.                                                                                                 |
| `TrendCharts`                                                                                 | `ui/components/TrendCharts.kt`                    | Vico line charts (`TrendChart`, `MultiSeriesTrendChart`) — Bezier curves, gradient fills, M3 tonal mapping.                                |
| `SingleBloodPressureChart` / `BloodPressureSplitChart`                                        | `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/bloodpressure/`                       | Vico dual-series synchronized BP charts.                                                                                                   |
| `HrTimelineChart` / `StepsBar`                                                                | `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/`                                     | Custom `Canvas` visualizations in feature:vitals.                                                                                          |
| `RasWeeklyBar`                                                                                | `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/`                                 | Custom `Canvas` visualization in feature:workouts.                                                                                         |
| `TrainingMixSection` / `TrainingMixCard`                                                      | `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/`                                 | Custom `Canvas` donut chart visualization of weekly training distribution by activity type.                                                |
| `RouteContourCard` / `WorkoutPerformanceCharts`                                               | `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/`                                 | Custom on-device `Canvas` route contour visualization (Douglas-Peucker line simplification) and pace/speed/elevation charts.               |
| `SleepStagesChart` / `SleepArchitectureBar` / `SleepHrChart`                                  | `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/`              | Sleep stage + sleep HR Canvas visualizations.                                                                                              |
| `VicoChartTooltipOverlay` / `DataPointTooltip`                                                | `ui/components/`                                  | Touch interception + floating tooltip overlay for Vico charts.                                                                             |
| `ReorderableCardGrid` (+ `reorder/DragController`)                                            | `ui/components/`                                  | Drag-and-drop dashboard card grid.                                                                                                         |
| `SleepTrendChart`                                                                             | `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendChart.kt` | Vico stacked column & line dual-axis sleep window & duration chart.                                                                        |                                                                        |

### 3.4 Recalc-progress trace (background job → UI)

`ResyncRangeUseCase.run()` reports `(phase, current, total)` at the start of each of its four
phases and, for `INGEST`/`RECOMPUTE`, again after each unit of work (a chunk / a day) completes.
**PERF-002/WP-20:** this per-day `onProgress` signal is unchanged, but the RECOMPUTE phase's
*durable checkpoint save* is no longer written after every day — it now saves every
`RECOMPUTE_CHECKPOINT_INTERVAL_DAYS` (30) days, or on the final day, since recompute is idempotent
and a kill-and-resume redoing up to one interval's worth of already-correct work is cheaper than a
proto-DataStore write per day across a multi-year resync.

`DailySyncUseCase.run()` (the daily pull-to-refresh path — see §2.2/HC-009) feeds the identical
`RecalcProgress` pipeline: it fires `(INGEST, pagesIngested, 0)` after each streamed HC HR/HRV page
persists during `HealthIngestionCoordinator.ingestWindow`, `(RECONCILE, 0, 0)` once immediately
before `SessionLinkReconciler.reconcile`, then determinate `(RECOMPUTE, completedDays, totalDays)`
across its walk-forward. Both `INGEST` and `RECONCILE` are indeterminate on this path (`total = 0`)
since neither a page count nor a reconcile pass has a known total up front — see Phase 1 of
`internal-docs/plans/HEAVY_DATA_SYNC_STABILITY_PLAN.md`.

`HealthResyncWorker` (background WorkManager resync), `ForegroundSyncController.executeSync`
(foreground first-launch `catchUpSync`), and `DailySyncUseCase.run()` (foreground daily sync) all
funnel through the same `RecalcProgress(phase, current, total)` type:

```
ResyncRangeUseCase.run() / DailySyncUseCase.run()
  → onProgress(phase, current, total)                              // phase-start + per-unit signals
     → HealthResyncWorker / ForegroundSyncController.executeSync / triggerDailySync
        → ForegroundSyncController.recalcProgress: StateFlow<RecalcProgress?>
           → SyncViewModel.recalcProgress
              → MainScaffold's RecalcProgressBanner / SyncProgressScreen (collectAsStateWithLifecycle)
```

`RecalcProgress.fraction()` (`core/model/.../FeatureSyncPorts.kt`) is the single shared computation
all three UI surfaces (bottom banner, full-screen `SyncProgressScreen`, foreground-service
notification) use to render one continuous `LinearProgressIndicator`. Each `ResyncPhase` value owns
an equal-width slice of the bar, derived generically from `phase.ordinal / ResyncPhase.entries.size`
(25% each for the current 4 phases: `INGEST` 0-25%, `PRUNE` 25-50%, `RECONCILE` 50-75%, `RECOMPUTE`
75-100%). On the **historical resync path**, `INGEST` (chunked HC re-fetch) and `RECOMPUTE`
(walk-forward days) report real `current`/`total` and fill smoothly within their slice; `PRUNE` and
`RECONCILE` are single non-chunked passes with no natural sub-progress, so they simply hold the bar
at their slice's start until the next phase begins. On the **daily sync path**, `INGEST` and
`RECONCILE` are always indeterminate (`total = 0`, guarded by `fraction()`'s `total > 0` check) and
likewise hold at their slice's start — `MainScaffold`/`SyncProgressScreen` render a page-count label
instead of a "N of M" count for indeterminate `INGEST`. When a historical resync resumes from
checkpoint, progress starts directly at the resumed phase/offset instead of resetting to zero.

**Settings gating signal.** The Settings screen derives "is a resync running" from
`HistoricalResyncController.state.running` (`WorkManager.getWorkInfosForUniqueWorkFlow(RESYNC_WORK_NAME)`
checking for `WorkInfo.State.RUNNING` or `WorkInfo.State.ENQUEUED`). This durable WorkInfo state
reliably reflects active or enqueued work across the unique work chain. The previous ephemeral
`ForegroundSyncGateway.isResyncing` bridge was removed. All scoring-input Settings controls — Resync button, Data-Sources
Apply, Recalculate scores, HR zones, physiology profile, TRIMP/RAS sliders, sleep scoring inputs
(goal/weight-profile/oversleep/merge-gap/etc.), HRV/RHR baseline overrides, resting-HR percentile,
HRR tolerance, circadian threshold, and load-source pickers — disable while `isResyncing` is `true`, so no
resync or recompute can be kicked off mid-resync. Display/theme, unit-system, retention, step-goal,
backup, and issue-reporting controls stay enabled.

**Recalculate-scores baseline.** The "Recalculate scores" button enables only while the three
sleep-scoring inputs (weight profile, goal sleep hours, hypersomnia onset percent) differ from the
values history was last computed with. The baseline lives on `user_preferences` as the optional
`lastRecalcSleepScoreWeightProfile` / `lastRecalcGoalSleepHours` / `lastRecalcHypersomniaOnsetPercent`
fields (proto fields 86–88, folded in from the deleted `SleepScoreRecalcBaselineStore`). They are written
by the resync worker via `SettingsRepository.updateSleepScoreRecalcBaseline(...)` on successful completion
(absence = "never recalced"), ensuring standard backup/restore carries the baseline. `SleepSettingsViewModel`
exposes `hasPendingSleepScoreRecalc` from this comparison against those stored fields (falling back to factory
defaults when unset).

---

## 4. Component & File Registry

| Source File Path                                                           | Layer / Responsibility                              | Associated Metric / Formula                                                              |
| :------------------------------------------------------------------------- | :-------------------------------------------------- | :--------------------------------------------------------------------------------------- |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/HealthConnectRepository.kt`                             | Ingestion — HC contract, permissions                | —                                                                                        |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/model/HealthConnectRecords.kt`                                      | Ingestion — Android-free HC DTO boundary            | app-owned sleep / HR / HRV / exercise / steps / optional metric records                  |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthConnectRepositoryImpl.kt`                        | Ingestion — paginated HC reads                      | `readAllPages<T>()` (pageToken)                                                          |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/StepRecordReader.kt`                                   | Ingestion — step queries & aggregation              | raw step reads, `readSteps`, `readDailyStepTotals` (HC-003)                               |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/IntervalTotalsReader.kt`                               | Ingestion — distance & elevation interval totals    | `readDistanceTotals`, `readElevationTotals`, `resolveTotal`                              |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HealthSyncUseCase.kt`                                         | Ingestion — sync facade (owns `syncMutex`)          | delegates `sync` → `DailySyncUseCase`, `resyncRange` → `ResyncRangeUseCase`              |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/DailySyncUseCase.kt`                                          | Ingestion — daily-sync orchestrator                 | recent-window ingest → reconcile → walk-forward recompute                               |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/ResyncRangeUseCase.kt`                                        | Ingestion — historical-resync orchestrator          | 4-phase resumable resync (ingest/prune/reconcile/recompute)                             |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HealthIngestionCoordinator.kt`                               | Ingestion — read→map→filter→upsert funnel           | `ingestWindow` (shared by both flows) + entity→input mappers                            |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/StepCountFetcher.kt`                                          | Ingestion — per-device step reads                   | `fetchWindow` (recent) / `fetchRange` (historical)                                       |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/RetryWithBackoff.kt`                                          | Ingestion — transient-fault retry                   | bounded exponential backoff; never swallows `CancellationException`                     |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/DailyRecomputeSupport.kt`                                     | Ingestion — per-day recompute helpers               | `recomputeDay` → `ScoringRepository` (no math) + `refreshAutoMaxHr`                     |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HealthChangeSynchronizer.kt`                                  | Ingestion — sync changes port                       | Applies pending changes; captures/commits token maps                                    |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthChangeSynchronizerImpl.kt`                       | Ingestion — sync changes implementation             | Applies changes and returns uncommitted candidate tokens                                |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/HealthChangeTokenStore.kt`                                    | Ingestion — token store port                        | Atomic per-type token-map persistence                                                   |
| `app/src/main/kotlin/app/readylytics/health/data/preferences/HealthChangeTokenStoreImpl.kt`                           | Ingestion — token store implementation              | Proto DataStore implementation of change token storage                                  |
| `app/src/main/proto/health_change_tokens.proto`                                         | Ingestion — change token schema                     | Proto schema for change tokens                                                          |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/ResyncCheckpointStore.kt`                                     | Ingestion — checkpoint store port                   | Port for resumable historical resync checkpoints                                       |
| `app/src/main/kotlin/app/readylytics/health/data/preferences/ResyncCheckpointStoreImpl.kt`                            | Ingestion — checkpoint store implementation         | Proto DataStore implementation of resync checkpoint storage                            |
| `app/src/main/proto/resync_checkpoint.proto`                                            | Ingestion — checkpoint schema                       | Resync phase/date; pre-ingest tokens required for full resync, intentionally empty for local recompute |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/ForegroundSyncController.kt`                                  | Ingestion — foreground trigger + progress           | daily sync (1 day); recalc progress publish                                              |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/FullHistoricalResyncUseCase.kt`                               | Ingestion — resync orchestration                    | retention-bounded range                                                                  |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/util/RetentionBounds.kt`                                           | Ingestion — retention math                          | `today − retentionDays` / `ABSOLUTE_MAX_DAYS`                                            |
| `app/src/main/kotlin/app/readylytics/health/workers/HealthResyncWorker.kt`                                            | Ingestion — durable background resync               | `WorkInfo` progress, foreground notification                                             |
| `app/src/main/kotlin/app/readylytics/health/workers/PeriodicHealthSyncWorker.kt`                                      | Ingestion — periodic background sync (2-day window) | silent transient notification                                                            |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/workers/WorkerScheduler.kt`                                               | Ingestion — work scheduling                         | full resync (KEEP), recompute successor (APPEND_OR_REPLACE), periodic sync (UPDATE)       |
| `app/src/main/kotlin/app/readylytics/health/workers/DataCleanupWorker.kt`                                             | Ingestion — retention enforcement                   | retention cutoff (shared)                                                                |
| `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RetentionCleanup.kt`                                           | Ingestion — retention cleanup                       | 12 sensitive tables: HR/HRV batched (10k rows/tx, DB-002), other 10 in one transaction    |
| `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RoomTransactionRunner.kt`                                      | Ingestion — atomic transaction                      | per-window upsert                                                                        |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/link/SessionLinker.kt`                                        | Ingestion — session linkage                         | pure `resolve()`: sleep > workout > resting precedence                                   |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/link/SessionLinkReconciler.kt`                                | Ingestion — post-resync reconcile                   | re-tags HR/HRV by session, recomputes workout TRIMP/zones                                |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthConnectRecordConverters.kt`                      | Ingestion — unified `toDomain()` converters (ARCH-002) | native HC record → domain DTO, all record types                                       |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/mappers/SleepDataMapper.kt`                                    | Ingestion — mapper                                  | sleep session + stages                                                                   |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/mappers/HeartRateMapper.kt`                                    | Ingestion — mapper                                  | HR samples → SLEEP/EXERCISE/RESTING                                                      |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/mappers/HrvMapper.kt`                                          | Ingestion — mapper                                  | RMSSD samples                                                                            |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/mappers/WorkoutMapper.kt`                                      | Ingestion — mapper                                  | elapsed duration only; zone minutes/TRIMP populated later by `ZoneThresholds.computeMetrics` |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/mappers/StepsMapper.kt`                                        | Ingestion — mapper                                  | raw selected-device steps / aggregate all-device steps                                   |
| `data/mapper/{Weight,BodyFat,BloodPressure,OxygenSaturation}DataMapper.kt` | Ingestion — mappers                                 | weight / body fat / BP / SpO2                                                            |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/model/BodyCompositionAssessment.kt` | Domain — canonical BMI/body-fat status seam         | assesses categories/statuses and owns the category-band/visual reference metadata consumed by cards, history labels, and trend-chart coloring |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/service/BmiService.kt`               | Domain — facade (delegates)                         | `classify()` → `BodyCompositionAssessment.assessBmi(bmi).status`                        |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/model/VitalStatusClassifiers.kt`      | Domain — canonical steps/heart-rate status seams     | `StepsStatusClassifier` and `HeartRateStatusClassifier` classify display statuses         |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/service/HealthMetricsService.kt`     | Domain — canonical BP status seam and facade         | delegates BMI/body-fat assessments; owns blood-pressure assessment and component chart-band metadata derived from the same thresholds |
| `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/calculation/HealthMetricsCalculator.kt` | Domain — facade (delegates)                     | `assessBmi()`/`assessBodyFatPercent()` → `BodyCompositionAssessment`; `assessBloodPressure()` → `HealthMetricsService` |
| `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/HealthDatabase.kt`                                             | Storage — Room DB (v14)                             | 17 entities; pre-bridge Room migration chain ends at v6; external migration owns v7; Room owns v7→v14 |
| `app/src/main/kotlin/app/readylytics/health/data/migration/DatabaseReadinessGate.kt`                                            | Storage — pre-Room readiness guard                  | missing or v7..`DATABASE_VERSION` ready; v5/v6 or resumable metadata require external migration |
| `app/src/main/kotlin/app/readylytics/health/data/migration/V7DatabaseMigrator.kt`                                               | Storage — resumable external v7 migration           | preflight; 10k keyset copy/checkpoint; per-index transactions; validated atomic cutover  |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/migration/DatabaseMigrationModels.kt`                                 | Domain — migration contracts                        | readiness inspector/state; phase/progress/result models                                  |
| `app/src/main/kotlin/app/readylytics/health/data/security/SqlCipherKeyManager.kt`                                               | Storage — scoped encrypted DB access                | opens raw SQLCipher DB only inside a callback and zeroes plaintext key bytes              |
| `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/DailySummaryEntity.kt`             | Storage — computed-day snapshot                     | scores + frozen baselines                                                                |
| `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/InsightDismissalEntity.kt`         | Storage — insight dismissal                         | dateMidnightMs + type                                                                    |
| `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/entity/AuditEventEntity.kt`                       | Storage — local audit events                        | metadata-only backup/restore/key-lifecycle events                                        |
| `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/WorkoutRoutePointEntity.kt`        | Storage — workout route points                      | normalized coordinates per workout; cascade-deleted with workout                          |
| `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/*.kt` (sleep, HR, HRV, workout, weight, …) | Storage — raw metric entities                  | upsert by stable HC id                                                                   |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/util/RouteSimplifier.kt`                                 | Domain — route simplification                       | Douglas-Peucker point reduction for on-device Canvas rendering                           |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/util/RouteDistanceCalculator.kt`                         | Domain — route metrics calculation                  | haversine path distance and cumulative elevation calculations                            |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/SyncWorkoutRouteUseCase.kt`                         | Domain — on-demand workout route sync               | single-workout route retrieval from Health Connect and Room atomic persistence           |
| `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/InsightDismissalDao.kt`               | Storage — insight dismissal DAO                     | observe / dismiss / restore                                                              |
| `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/dao/AuditEventDao.kt`              | Storage — local audit DAO                           | append / observe recent metadata events                                                  |
| `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/*.kt`                                 | Storage — DAOs                                      | `@Upsert`, `clearFrozenBaselines`, `deleteBeforeTimestamp`                               |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/model/InsightType.kt`                                              | Domain — insight model                              | enum class and RecoveryFlag mapper                                                       |
| `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/dashboard/InsightDeriver.kt`                                       | Domain — insight logic                              | derives active set + ordered visible queue/current insight                               |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/ScoringRepository.kt`                                   | Processing — coordinator contract                   | `computeDailySummary(day)`                                                               |
| `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringDayDataLoader.kt`                               | Processing — scoring data loader                    | encapsulates 10 DAOs, loads/persists scoring entities                                   |
| `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ReadinessSummaryCoordinator.kt`                         | Processing — readiness summary coordinator          | coordinates sleep aggregation & calibrated/uncalibrated summary assembly               |
| `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringRepositoryImpl.kt`                                 | Processing — scoring orchestrator                   | TRIMP/RAS → baselines → scores; "Calibrating" gate                                       |
| `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/sleep/SleepPercentileRhrCalculator.kt`                     | Processing — RHR                                    | **RHR nocturnal-floor percentile** (default 5th, 30-day window)                          |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/scoring/TrimpModel.kt`                                             | Processing — TRIMP model enum                       | BANISTER / I_TRIMP / CHENG                                                               |
| `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/RasCalculator.kt`                                          | Processing — TRIMP/RAS                              | **TRIMP (default BANISTER)**; RAS = TRIMP × scaling (cap 75)                             |
| `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeWorkoutTrimpUseCase.kt`                             | Processing — per-workout TRIMP                      | model from `prefs.trimpModel`                                                            |
| `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeDailyTrimpUseCase.kt`                               | Processing — daily workout TRIMP aggregation        | aggregates workout TRIMP & model TRIMP updates                                           |
| `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/GetWorkoutDisplayMetricsUseCase.kt`                        | Processing — per-workout display metrics            | orchestrates 42-day history fetching and delegates to `ComputeWorkoutLoadMetricsUseCase` |
| `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ResolveDailyBaselinesUseCase.kt`                           | Processing — baseline resolution                    | resolves pre- and post-computation baselines & frozen snapshots                          |
| `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/AssembleDailySummaryUseCase.kt`                           | Processing — summary assembly                       | assembles uncalibrated & calibrated DailySummary snapshots                               |
| `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/BaselineComputer.kt`                                       | Processing — baselines                              | hrMax / RHR / HRV mu·sigma / RAS factor; freeze + calibration                            |
| `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/strategies/SleepScoringStrategy.kt`                        | Processing — sleep score                            | Duration 40% / Architecture 20% / Restoration 25% / Fragmentation 15% (default Balanced profile) |
| `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/strategies/LoadScoringStrategy.kt`                         | Processing — load/readiness                         | Strain Ratio (ATL/CTL); readiness composite                                              |
| `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/components/RecoveryFlagEvaluator.kt`                      | Processing — recovery flags                         | Evaluates recovery flags (illness onset, strong recovery, workout impact, rest day, missing data) |
| `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/strategies/RasScoringStrategy.kt`                          | Processing — training load                          | CTL (42-day) / ATL (7-day) EMA                                                           |
| `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeSleepMetricsUseCase.kt`                             | Processing — sleep metrics assembly                 | sleep + restoration                                                                      |
| `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/BaselineZScoreComputer.kt`                                 | Processing — Z-score computation                    | HRV & RHR Z-scores, nocturnal RHR delta BPM                                              |
| `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/RestorationScoreAssembler.kt` | Processing — restoration assembly | restoration score (sRest) assembly and contributor subscores |
| `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/ComputeResidualFatigueUseCase.kt` | Processing — residual fatigue (pure) | `compute()` summation + `advanceAccumulator()` decay/add step (§2.8) |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/scoring/ResidualFatigueConfig.kt` | Domain — fatigue parameters | enabled / halfLifeHours / fatigueGain (shadow mode, §2.8) |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/WalkForwardFatigueContext.kt` | Processing — walk-forward accumulator | prefetched impulse series + running accumulated fatigue (WP-27) |
| `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ResidualFatigueComputer.kt` | Processing — fatigue snapshot | per-day snapshot at next-day midnight; exact retained-history seed (§2.8) |
| `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/FinalSummaryAssembler.kt` | Processing — summary assembly | stamps `residualFatigue` onto the assembled `DailySummary` (§2.8) |
| `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModel.kt` | UI — dashboard state                                | summary, cards, RAS, recalc progress                                                     |
| `ui/sync/SyncViewModel.kt`                                                 | UI — sync state                                     | `recalcProgress` forward                                                                 |
| `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsViewModel.kt`         | UI — vitals state                                   | HRV / RHR / SpO2 / body temperature trends + bands                                       |
| `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsTrendSection.kt`      | UI — Vico chart                                     | body temperature trend chart + baseline reference line (display-only, see §1.5)          |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/service/BodyTemperatureBaselineCalculator.kt`    | Domain — display-only baseline (non-scoring)         | 14-day plain trailing average baseline                                                      |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/service/BodyTemperatureBaselineProvider.kt`      | Domain — display-only baseline (non-scoring)         | `observeBaseline(date)` stream; Room summary/scoring-zone emissions recalculate dashboard + Vitals baseline |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/data/mapper/BodyTemperatureDataMapper.kt`       | Ingestion — mapper                                   | body temperature (°C)                                                                    |
| `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepViewModel.kt`                      | UI — sleep state                                    | sleep score, stage timeline, sleep window/duration trend data, `sleepHrSamples`          |
| `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModel.kt`             | UI — workouts state                                 | TRIMP / strain / RAS                                                                     |
| `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsStateFactory.kt`           | UI — workouts state assembly                        | Pure presentation state assembly, series padding, and RAS breakdown                      |
| `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/SettingsState.kt`             | UI — settings state                                 | `SyncSettingsState` resync progress                                                      |
| `ui/scaffold/MainScaffold.kt`                                                                              | UI — scaffold + banner                              | "Recalculating day X of Y"                                                               |
| `ui/components/InsightCard.kt`                                                                             | UI — component                                      | dismissible M3 health insight card + slim rerun restore state                            |
| `ui/components/M3ScoreGaugeCard.kt`                                                                        | UI — visualization                                  | soft arc gauge metric card                                                               |
| `ui/components/TrendCharts.kt`                                                                             | UI — Vico charts                                    | line trends (Bezier, gradient)                                                           |
| `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/heartrate/HrTimelineChart.kt`         | UI — Canvas chart                                   | intra-day HR + zones                                                                     |
| `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepStagesChart.kt`                      | UI — Canvas chart                                   | sleep stage timeline                                                                     |
| `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepHrChart.kt`                          | UI — Canvas chart                                   | sleep HR timeline                                                                        |
| `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/RasWeeklyBar.kt`                 | UI — Canvas chart                                   | 7-day RAS breakdown                                                                      |
| `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/TrainingMixSection.kt`            | UI — Canvas chart                                   | Weekly training mix donut chart & breakdown                                               |
| `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendChart.kt`                      | UI — Vico chart                                     | stacked column & line dual-axis sleep window & duration chart                            |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sleep/SleepLayoutRepository.kt`                      | UI — sleep layout contract                          | Interface for observing and updating sleep top cards, trend charts, and metric card configurations |
| `app/src/main/kotlin/app/readylytics/health/data/preferences/SleepLayoutRepositoryImpl.kt`                     | UI — sleep layout store implementation              | Proto DataStore persistence, default auto-healing/appending, and proto/domain mapping for sleep tab layout |
| `app/src/main/kotlin/app/readylytics/health/data/preferences/SleepLayoutConfigurationsSerializer.kt`         | UI — sleep layout serializer                        | Proto DataStore serializer for `SleepLayoutConfigurationsProto`                          |
| `app/src/main/kotlin/app/readylytics/health/data/preferences/SleepLayoutMapper.kt`                             | UI — sleep layout mapper                            | Bidirectional conversion between proto DTOs and domain sleep layout configurations       |
| `app/src/main/proto/sleep_layout_configurations.proto`                                                          | UI — sleep layout schema                            | Proto schema for sleep tab layout configurations (top cards, trend charts, metric cards) |
| `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/overview/SleepFlowIntermediate.kt`        | UI — sleep tab reactive flow assembly               | Merges daily summary domain state with reactive `SleepLayoutRepository` layout configurations |
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/workouts/WorkoutsLayoutRepository.kt`             | UI — workouts layout contract                       | Interface for observing and updating workout cards, diagram, and history configurations |
| `app/src/main/kotlin/app/readylytics/health/data/preferences/WorkoutsLayoutRepositoryImpl.kt`                | UI — workouts layout store implementation           | Proto DataStore persistence, default auto-healing/appending, and proto/domain mapping for workouts tab layout |
| `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsFlowIntermediate.kt`       | UI — workouts tab reactive flow assembly            | Merges workout/daily-summary domain state with reactive `WorkoutsLayoutRepository` layout configurations |

### 3.5 Dashboard Insight Card Derivation & Dismissal Flow

```
DailySummary.recoveryFlags (from Room)
   │
   ▼ (observe selected date)
createDashboardBasicInputsFlow
   │
   ▼ combines with
insightDismissalDao.observeForDate(date) (observing dismissed types)
   │
   ▼
DashboardBasicInputs (with dismissedInsightTypes)
   │
   ▼ passed to
DashboardViewModel.transformToUiState
   │
   ▼ calls
InsightDeriver.derive(recoveryFlags, dismissedInsightTypes)
   │
   ▼ produces
DerivedInsights(active, visibleQueue, current, dismissedCount)
    │
    ▼ mapped to
DashboardUiState (activeInsightTypes, visibleInsightQueue, currentInsight, dismissedInsightCount)
    │
    ▼ rendered by
DashboardCardFactory (single CardId.INSIGHTS slot with AnimatedContent)
```

**Dismissal & Restoration Actions:**

- **Dismiss:** User clicks close button on `InsightCard` → emits `DashboardEvent.DismissInsight` → launches coroutine → `InsightDismissalDao.dismiss(InsightDismissalEntity)` → persists dismissal to database, immediately triggering flow update; if more active insights remain, the single insight slot rotates to the next queued insight.
- **Restore:** When all active insights for the selected date are dismissed, the same `CardId.INSIGHTS` slot renders the slim rerun card. User taps it → emits `DashboardEvent.RestoreInsights` → launches coroutine → `InsightDismissalDao.restoreAllForDate(dateMs)` → deletes all dismissals for date, immediately restoring the first queued insight.

### 3.6 AI Recommendation prompt export (manual copy-to-external-AI-chat)

An offline-first dashboard card (`AiRecommendationCard`, `CardId.AI_RECOMMENDATION`) lets the user
copy a static setup prompt and a populated daily prompt to paste into an external AI chat app. There
is no in-app LLM/network call. The daily prompt is generated on demand from persisted data only —
no score is recomputed (see the scoring section for how the underlying columns are produced).

```
Room DAO
  │  DailySummaryDao.getByDate / getSince
  │  WorkoutDao.getWorkoutsInRange (bounded epoch range)
  ▼
GetDailyPromptDataUseCase (core/scoring/.../domain/airecommendation/)
  │  today's + yesterday's persisted DailySummary rows
  │  yesterday workouts via WorkoutRepository.getInRange([yesterdayMidnight, todayMidnight))
  │  pattern workouts via WorkoutRepository.getInRange([today−3mo, tomorrowMidnight))
  │  per-workout GetWorkoutDisplayMetricsUseCase (same path the Workouts screens use)
  │  active Training Load source via UserPreferencesReader.strainLoadSourceMode
  ▼
DailyPromptData (pure) → DailyPromptFormatter.format(...)  (pure, stable English, Sections A–H)
  ▼
DashboardViewModel.dailyPromptText (one-shot StateFlow<String?>)   [DashboardEvent.RequestDailyPromptCopy]
  ▼
DashboardRoute → LocalClipboardManager.setText + SnackbarHostState  (setup prompt is copied
  synchronously from the static `R.string.ai_init_prompt` resource)
```

Key behaviors:
- `WorkoutRepository.getInRange(fromMs, toMs)` is a thin delegation to
  `WorkoutDao.getWorkoutsInRange` (added for this feature; bounded, no schema change).
- `WorkoutRepository.getRoutePoints(workoutId)` delegates to
  `WorkoutRoutePointDao.getRoutePoints` and maps rows to domain `WorkoutRoutePoint` models in ascending
  `timestampMs` order for route map / distance displays.
- The prompt labels which Training Load source is active; `LoadSourceSelector` projects the
  active-source ATL/CTL/ratio/load/readiness columns. RAS totals are informational only.
- `GetDailyPromptDataUseCase` parses the persisted everyday coverage confidence once and only
  when Everyday heart-rate load is the selected source, then passes the typed value to
  `resolveAdvisorConfidence`. Base confidence derives from the calibration `Phase` × whether today
  is missing HRV or sleep-stage signals: Low during Calibration/Early Baseline, Medium (or Low if
  signals are missing) once Maturing, and High (or Medium if signals are missing) once Mature.
  `LOW` coverage caps an otherwise high advisor confidence at medium; `NONE` coverage lowers it one
  level with a low floor. Absent, medium, and high coverage leave the calibration/recovery-signal
  confidence unchanged. Workout-only prompts pass no everyday coverage confidence.
- `ComputeWorkoutPatternSummaryUseCase` (Section G) groups the three-month window by exercise type
  (frequency, avg TRIMP, avg duration, preferred weekdays) and computes rest-day average, rest-day
  gap, and current training streak. Workout day boundaries resolve in the user's configured
  scoring zone (`preferences.scoringZone()`), matching every other date-boundary computation.
- The formatter is pure Kotlin; unavailable values render as "insufficient data", never fabricated.
  UI copy on the card itself is localized via `feature/dashboard` resources; the prompt text itself
  is stable English to stay machine-parseable and comparable with the template docs.
- Default card presence: `AI_RECOMMENDATION` is in `SettingsDefaults.DEFAULT_DASHBOARD_CARDS`;
  `CardConfigurationRepositoryImpl` appends missing defaults once, visibly, after the highest stored
  position for existing installs.

### 3.7 Sleep Tab Layout Customization & Proto DataStore Persistence Pipeline

The Sleep tab supports customizable layout ordering, visibility toggling, and display mode selection across three distinct card/chart groups: **Top Cards** (score gauge, sleep duration, efficiency, consistency), **Trend Charts** (sleep duration/window, HR/HRV trends, stage timelines), and **Metric Cards** (RHR, HRV, SpO2, body temperature, circadian metrics).

```
SleepManagementBottomSheet / SleepOverviewScreen (UI interaction)
  │
  ▼ emits layout updates (reorder, toggle visibility, change display mode, reset defaults)
SleepViewModel
  │
  ▼ delegates to SleepLayoutDelegate (wrapping SleepTopCardManagementDelegate / LayoutManagementDelegate (charts) / SleepMetricCardManagementDelegate)
SleepFlowIntermediate (combines repository flows with daily summary state)
  │
  ▼ updates layout state via
SleepLayoutRepository (core/model/.../domain/sleep/SleepLayoutRepository.kt)
  │
  ▼ implemented by
SleepLayoutRepositoryImpl (app/.../data/preferences/SleepLayoutRepositoryImpl.kt)
  │
  ▼ mapped by SleepLayoutMapper (toTopCardProto / toChartProto / toMetricCardProto)
DataStore<SleepLayoutConfigurationsProto> ("sleep_layout_configurations.pb")
  │  Proto schema: app/src/main/proto/sleep_layout_configurations.proto
  │  Serializer: SleepLayoutConfigurationsSerializer
  ▼
Local backup/restore pipeline (LocalBackupManager & LocalRestoreManager)
```

Key behaviors:
- **Proto Schema:** `sleep_layout_configurations.proto` defines `SleepTopCardConfigurationProto`, `SleepChartConfigurationProto`, `SleepMetricCardConfigurationProto`, and `SleepLayoutConfigurationsProto`.
- **Domain Seam:** Pure domain models (`SleepTopCardConfiguration`, `SleepChartConfiguration`, `SleepMetricCardConfiguration`) and ID enums (`SleepTopCardId`, `SleepChartId`, `SleepMetricCardId`) live in `core/model` (zero Android dependencies).
- **Auto-Healing Defaults:** On initialization or repository flow observation, `SleepLayoutRepositoryImpl` checks stored configurations against `SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS`, `DEFAULT_SLEEP_CHARTS`, and `DEFAULT_SLEEP_METRIC_CARDS`. Any missing default items are automatically appended after the highest stored position. This ensures forward-compatibility when new cards or charts are introduced in app updates without overwriting existing user reordering or visibility choices.
- **Backup & Restore Integration:** `LocalBackupManager` streams active sleep layout configurations (`sleepTopCards`, `sleepCharts`, `sleepMetricCards`) into `UserPreferencesBackup` within encrypted ZIP backups. `LocalRestoreManager` restores these stored configurations back to `SleepLayoutRepository` (Proto DataStore) during the post-database preference restoration stage.

---

### 3.8 Workouts Tab Layout Customization & Proto DataStore Persistence Pipeline

The Workouts tab supports customizable layout ordering, visibility toggling, and display mode selection across three groups: **Cards** (Strain Ratio, Readiness, RAS Daily — reusing the shared `CardId`/`CardConfiguration` model from `core/model/.../domain/dashboard`), **Diagrams** (the ACWR/TRIMP training-load chart), and **History** (recent workout list, status legend).

```
WorkoutsManagementBottomSheet / WorkoutsScreen (UI interaction)
  │
  ▼ emits layout updates (reorder, toggle visibility, change display mode, reset defaults)
WorkoutsViewModel
  │
  ▼ delegates to CardManagementDelegate / LayoutManagementDelegate (charts & history)
WorkoutsFlowIntermediate (combines repository flows with daily-summary/workout state)
  │
  ▼ updates layout state via
WorkoutsLayoutRepository (core/model/.../domain/workouts/WorkoutsLayoutRepository.kt)
  │
  ▼ implemented by
WorkoutsLayoutRepositoryImpl (app/.../data/preferences/WorkoutsLayoutRepositoryImpl.kt)
  │
  ▼ mapped by WorkoutsLayoutMapper (toCardProto / toChartProto / toHistoryProto)
DataStore<WorkoutsLayoutConfigurationsProto> ("workouts_layout_configurations.pb")
  │  Proto schema: app/src/main/proto/workouts_layout_configurations.proto
  │  Serializer: WorkoutsLayoutConfigurationsSerializer
  ▼
Local backup/restore pipeline (LocalBackupManager & LocalRestoreManager)
```

Key behaviors:
- **Proto Schema:** `workouts_layout_configurations.proto` defines `WorkoutCardConfigurationProto`, `WorkoutChartConfigurationProto`, `WorkoutHistoryConfigurationProto`, and `WorkoutsLayoutConfigurationsProto`.
- **Domain Seam:** Cards reuse the existing `CardId`/`CardConfiguration` (already shared with Dashboard/Sleep/Vitals). `WorkoutChartConfiguration`/`WorkoutChartId` and `WorkoutHistoryConfiguration`/`WorkoutHistoryId` are new pure domain models in `core/model` (zero Android dependencies).
- **Auto-Healing Defaults:** On initialization or repository flow observation, `WorkoutsLayoutRepositoryImpl` checks stored configurations against `SettingsDefaults.DEFAULT_WORKOUT_CARDS`, `DEFAULT_WORKOUT_CHARTS`, and `DEFAULT_WORKOUT_HISTORY`. Any missing default items are automatically appended after the highest stored position.
- **RAS Daily dual rendering:** `CardId.RAS_DAILY`'s VALUE mode (default) renders the rich weekly-breakdown card (`RasWeeklyCard`); switching to GAUGE mode renders a compact dial of today's RAS score, matching Strain Ratio/Readiness.
- **Backup & Restore Integration:** `LocalBackupManager` streams active workout layout configurations (`workoutCards`, `workoutCharts`, `workoutHistory`) into `UserPreferencesBackup` within encrypted ZIP backups. `LocalRestoreManager` restores these back to `WorkoutsLayoutRepository` (Proto DataStore) during the post-database preference restoration stage.

---

Keep this document synchronized with the source.
