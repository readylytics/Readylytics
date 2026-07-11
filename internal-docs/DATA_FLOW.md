# Data Flow & Architecture Blueprint

End-to-end map of how data moves through the app: from **Android Health Connect**
ingestion, into **Room/SQLite** local storage, through the pure-Kotlin **scoring engine**,
and out to the **Jetpack Compose** UI.

> **Reference-level by design.** This document names the calculation models, their defaults,
> and their inputs, and points to the exact source file + function that owns each formula.
> It deliberately does **not** reproduce coefficients or derivations — the mathematical
> "source of truth" stays in `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/**` (pure Kotlin, zero Android dependencies).
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
               │ @Upsert by stable HC id (idempotent; overlap → replace)
               ▼
┌──────────────────────────────┐
│  HealthDatabase (SQLite v2)  │   11 entities — single source of truth
└──────────────┬───────────────┘
               │ raw DAO reads (local; no further HC calls)
               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  core/database/.../data/repository/ScoringRepositoryImpl                 │
│  .computeDailySummary(day)                                                │
│   raw metrics → TRIMP/RAS → baselines → sleep/load/readiness → persist    │
│   delegates to pure-Kotlin core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/** (BaselineComputer,           │
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
| `HealthConnectRepository` (interface) | `core/model/src/main/kotlin/app/readylytics/health/domain/repository/HealthConnectRepository.kt` | Declares permission sets (critical / required / optional), `checkPermissions()` and `checkExerciseRoutePermission()` → `PermissionStatus` (Granted / Unavailable / Missing), and per-type read methods. Returns app-owned DTOs from `core/model/src/main/kotlin/app/readylytics/health/domain/model/HealthConnectRecords.kt`, not Android Health Connect SDK record types, so sync/domain code stays Android-free. Throws `HealthConnectPermissionRevokedException` when access is revoked mid-flight.                                      |
| `HealthConnectRepositoryImpl`         | `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRepositoryImpl.kt` | Concrete HC client wrapper. Generic `readAllPages<T>()` loops on HC `pageToken` until exhausted; catches `SecurityException` → `HealthConnectPermissionRevokedException` with operation, record type, and original platform message for diagnosis; converts native `androidx.health.connect.client.records.*` instances to domain DTOs before returning them. Optional records (weight/body-fat/BP/SpO2) fall back to empty lists when their optional permission is missing. Exercise-route permission is checked separately so its absence remains recoverable in the workout detail flow without blocking regular sync. Steps use `AggregateRequest(StepsRecord.COUNT_TOTAL)` only when "All devices" is selected; selected-device paths read raw `StepsRecord`s, convert to DTOs, and aggregate locally. |

**Permission model** (declared in the interface):

- **Critical:** `READ_SLEEP`, `READ_HEART_RATE`, `READ_HEART_RATE_VARIABILITY`, `READ_EXERCISE`, `READ_STEPS`
- **Required:** critical + `READ_HEALTH_DATA_HISTORY`
- **Optional:** `READ_WEIGHT`, `READ_BODY_FAT`, `READ_BLOOD_PRESSURE`, `READ_OXYGEN_SATURATION`, `READ_EXERCISE_ROUTES`. The route permission is queried separately by `WorkoutDetailViewModel`, which can show recovery UI rather than treating it as a sync-blocking missing permission.
- **Per-session route consent (distinct from the permission above):** even with `READ_EXERCISE_ROUTES` granted, Health Connect gates the actual route points for most sessions (any recorded by another app, e.g. Strava/Garmin) behind a separate one-time `ExerciseRouteResult.ConsentRequired` dialog per exercise session, requested via `androidx.health.connect.client.contracts.ExerciseRouteRequestContract` (an `ActivityResultContract<String, ExerciseRoute?>` keyed by session id). `HealthConnectRepositoryImpl.readExerciseRoute()` returns this as `RouteReadResult.ConsentRequired`, distinct from `RouteReadResult.NoRoute`. `WorkoutDetailViewModel` surfaces `RouteDataState.ConsentRequired` and — critically — does **not** persist `routeState = "NOT_AVAILABLE"` for this case, leaving it `PENDING_FOREGROUND_LOAD` so the read retries once the user grants consent. Collapsing `ConsentRequired` into `NoRoute` was the root cause of a bug where GPS workouts never showed route/pace/elevation data.

**Read methods:** `readSleepSessions`, `readHeartRateSamples`, `readHrvSamples`,
`readExerciseSessions`, `readSteps` / `readStepsRange`, `readWeightRecords`,
`readBodyFatRecords`, `readBloodPressureRecords`, `readOxygenSaturationRecords`,
`discoverDevices`.

**Rate-Limit and Transient Fault Protection:**
Each Health Connect read is retried through `HealthConnectRetryPolicy`, which retries transient
IO, quota, and rate-limit failures with bounded exponential backoff and jitter. Cancellation is
rethrown. Room writes remain outside the read retry loop so ingestion failure boundaries stay
explicit and idempotent.

### 1.2 Sync engine — orchestration, chunking, idempotency

| Component                     | Path                                         | Responsibility                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| :---------------------------- | :------------------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `HealthSyncUseCase`           | `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/HealthSyncUseCase.kt`           | Facade owning `syncMutex`; its public methods delegate to `DailySyncUseCase` (`sync`), `ResyncRangeUseCase` (`resyncRange`), and chunked catch-up resync (`catchUpSync`), which carry the behavior described in this row. `catchUpSync` is gated by `lastSyncTimestamp == 0` and executes `resyncRangeUseCase.run` in 30-day chunks over a 365-day range. `sync(windowDays, onProgress)` recent-window sync — note the **ingestion fetch starts one day earlier than the scored window** (`today − windowDays`), because overnight sleep sessions begin the previous evening; clipping at the scored window's midnight would drop a night's pre-midnight HR/HRV samples (lower HRV mean, higher RHR percentile). After recent-window ingest, `sync()` runs `SessionLinkReconciler.reconcile(...)` over the ingested overlap before scoring so pull-to-refresh preserves the same canonical HR/HRV session links as historical resync. Raw Health Connect reads stay in the sync engine, but Room writes now flow through `HealthIngestionStore.persist(batch)` and frozen-baseline clearing through `HealthIngestionStore.clearFrozenBaselines(start, endExclusive)`, keeping DAO/transaction details in data layer. The recalc loop covers `windowDays`, widened down to the earliest **recent** out-of-window affected day (within `MAX_INLINE_RECOMPUTE_DAYS` = 7 of today, in `DailySyncUseCase`) so last night's sleep (dated yesterday) or backfilled HR/HRV recomputes inline; only changes older than that inline bound escalate to durable historical resync. `resyncRange(start, end, chunkDays = 30, onProgress)` full historical runs **four resumable phases**: chunked ingest → selected-source prune → full-range session-link reconcile → walk-forward recompute. Before first ingest it captures Changes API baseline tokens and stores them with the immutable range, current phase, next date, and device-selection hash in `ResyncCheckpointStore`; retries reuse the same baseline, while legacy/mismatched checkpoints restart cleanly. **Every ingestion chunk of `resyncRange` starts one day early and fetches sleep/exercise sessions one day past the chunk end** so HR/HRV samples at either side of a 30-day boundary can still be assigned to cross-midnight sessions. Metric sample reads remain capped to the chunk. Step totals are rebuilt only for the remaining recompute range on resume, preserving selected-device correctness without re-running completed raw ingest. After all chunks are ingested, a single `SessionLinkReconciler.reconcile(...)` pass starting one day before the start date (i.e. `start - 1 day` to `end`) re-derives HR/HRV session linkage and recomputes affected workout metrics (see 1.2.1) — this makes the result independent of chunk alignment. Historical resync progress reports recomputed calendar days, not internal ingest/prune/reconcile units, but resumed recompute starts with the already-completed day offset. `HealthIngestionCoordinator.ingestWindow(start, end, prefs)` remains the single read→map→filter funnel (shared by both flows); `StepCountFetcher` performs per-device step reads (recent window + historical range); `DailyRecomputeSupport` runs the per-day score recompute and auto-MaxHR refresh; `retryWithBackoff(maxAttempts = 4, initialDelayMs = 1000)` (shared helper in `RetryWithBackoff.kt`) handles transient HC/IO faults (never swallows `CancellationException`); `syncMutex` (owned by the facade) serializes daily vs. resync. |
| `DailySyncUseCase`            | `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailySyncUseCase.kt`            | Foreground daily-sync orchestrator (the `sync(windowDays)` body). Runs under the facade's `syncMutex`. Owns the recent-window ingest → reconcile-over-overlap → frozen-baseline clear → walk-forward recompute, the cross-midnight reach-back, change-token commit, and the `REQUIRES_HISTORICAL_RESYNC` decision. |
| `ResyncRangeUseCase`          | `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCase.kt`          | Full-historical-resync orchestrator (the `resyncRange(...)` body). Runs under the facade's `syncMutex`. Owns the four resumable phases (chunked ingest → selected-source prune → full-range session-link reconcile → walk-forward recompute), checkpoint capture/resume, and baseline-token promotion. |
| `HealthIngestionCoordinator`  | `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/HealthIngestionCoordinator.kt`  | Single read→map→device-filter→upsert funnel for one HC window (`ingestWindow`), shared by both flows. Holds the entity→input mappers. Room writes go through `HealthIngestionStore.persist(batch)`. |
| `StepCountFetcher`            | `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/StepCountFetcher.kt`            | Per-device daily step reads. `fetchWindow(...)` for the recent window (semaphore-capped concurrent reads when no device filter); `fetchRange(...)` for the resync recompute range (chunked, retry-wrapped). |
| `DailyRecomputeSupport`       | `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailyRecomputeSupport.kt`       | Shared per-day helpers: `recomputeDay(day, steps)` → `ScoringRepository.computeAndPersistDailySummary` (single point of daily score persistence; no math here), and `refreshAutoMaxHr(prefs)`. |
| `ForegroundSyncController`    | `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ForegroundSyncController.kt`    | Foreground state + progress bridge. `triggerDailySync()` = pull-to-refresh (current day only, `windowDays = 1`); `triggerImmediateSync()` = first-launch catch-up; `onBackgroundRecalc{Started,Progress,Finished}()` publish WorkManager job progress into `isSyncing` / `recalcProgress` StateFlows + `syncCompletedEvent`. |
| `FullHistoricalResyncUseCase` | `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/FullHistoricalResyncUseCase.kt` | Resolves the retention-bounded start date via `RetentionBounds.resolveResyncStartDate()` and delegates to `HealthSyncUseCase.resyncRange(start, today)`. Checkpoint/resume behavior stays in the sync engine; no math. |
| `HealthResyncWorker`          | `app/src/main/kotlin/app/readylytics/health/workers/HealthResyncWorker.kt`              | `@HiltWorker` durable foreground service (`FOREGROUND_SERVICE_TYPE_DATA_SYNC`). Runs the resync use case, emits `WorkInfo` progress (`setProgressAsync`), posts a determinate "day X of Y" notification, bridges progress to `ForegroundSyncController`; `Result.retry()` on transient failure. Retries resume from persisted resync checkpoint instead of restarting whole history. |
| `WorkerScheduler`             | `core/model/src/main/kotlin/app/readylytics/health/workers/WorkerScheduler.kt`                 | Enqueues unique work. `scheduleResyncWorker()` (`RESYNC_WORK_NAME`, `ExistingWorkPolicy.KEEP`, expedited, exponential backoff) + `cancelResyncWorker()`; `schedulePeriodicSync(intervalMinutes)` (`PERIODIC_SYNC_WORK_NAME`, `ExistingPeriodicWorkPolicy.UPDATE`, exponential backoff) + `cancelPeriodicSync()`; also backup / birthday / data-cleanup workers. |
| `PeriodicHealthSyncWorker`    | `app/src/main/kotlin/app/readylytics/health/workers/PeriodicHealthSyncWorker.kt`        | "Background Sync" toggle in Settings. `@HiltWorker` periodic **standard (non-foreground) worker** calling `HealthSyncUseCase.sync(windowDays = 2)` (shares `syncMutex` with the other two flows), bridges progress to `ForegroundSyncController`, shows/dismisses a silent transient notification (`SyncNotifications.BACKGROUND_SYNC_CHANNEL_ID`) via `NotificationManagerCompat` directly — no `setForeground()`, since `READ_HEALTH_DATA_IN_BACKGROUND` already permits background HC reads and starting a foreground service from a periodic background worker risks `ForegroundServiceStartNotAllowedException` on API 34+. Ordinary failures return `Result.retry()`; `REQUIRES_HISTORICAL_RESYNC` enqueues `WorkerScheduler.scheduleResyncWorker()` and finishes successfully so durable catch-up runs once without periodic retry churn. |
| `DataCleanupWorker`           | `app/src/main/kotlin/app/readylytics/health/workers/DataCleanupWorker.kt`               | Daily retention enforcement; cutoff resolved via `RetentionBounds.resolveRetentionCutoffMs()` (shared with resync). Delegates to `RetentionCleanup`. No-op when retention disabled. |
| `RetentionCleanup`            | `core/database/src/main/kotlin/app/readylytics/health/data/local/RetentionCleanup.kt`             | Executes transactional deletions of data strictly older than the cutoff across all 9 sensitive tables. |
| `RetentionBounds`             | `core/model/src/main/kotlin/app/readylytics/health/domain/util/RetentionBounds.kt`             | Single source of truth for retention→date math: enabled → `today − retentionDays`; disabled → `today − ABSOLUTE_MAX_DAYS` (3650 / 10y). |
| `RoomTransactionRunner`       | `core/database/src/main/kotlin/app/readylytics/health/data/local/RoomTransactionRunner.kt`        | Wraps `HealthDatabase.withTransaction { … }` so an entire ingest window upserts atomically. |
| `HealthChangeSynchronizer`    | `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/HealthChangeSynchronizer.kt`    | Reconciles differential Health Connect Changes API responses (upsertions and deletions) incrementally during daily/foreground sync. Resolves dates of deleted records via local DB lookup. Composite-key metric changes delete every Room row owned by the HC source record before re-upsert. The synchronizer returns candidate next tokens but never persists them; the daily-sync flow (`DailySyncUseCase`) commits them only after requested-window ingest, reconciliation, step fetch, and scoring all succeed. Changes older than the requested scoring window leave tokens uncommitted and return `REQUIRES_HISTORICAL_RESYNC`, routing correction through the durable worker without widening foreground scoring. |
| `HealthChangeTokenStore`      | `core/model/src/main/kotlin/app/readylytics/health/domain/sync/HealthChangeTokenStore.kt`      | Atomically stores differential Changes Tokens per Health Connect data type after derived summaries are durable. Replayed pages are safe because Room ingestion is idempotent by HC record ID. |
| `ResyncCheckpointStore`       | `core/model/src/main/kotlin/app/readylytics/health/domain/sync/ResyncCheckpointStore.kt`       | Stores the resumable historical-resync checkpoint: fixed range, current phase (`INGEST` / `PRUNE` / `RECONCILE` / `RECOMPUTE`), next date, selection hash, and pre-ingest baseline Changes Tokens. Cleared only after recompute succeeds and baseline tokens are promoted atomically. |
| `ResyncCheckpointStoreImpl`   | `app/src/main/kotlin/app/readylytics/health/data/preferences/ResyncCheckpointStoreImpl.kt` | Proto DataStore implementation of historical-resync checkpoint persistence. |
| `SelectedSourcePruner`        | `core/model/src/main/kotlin/app/readylytics/health/domain/sync/SelectedSourcePruner.kt`        | Executes transactional, device-scoped deletes of records not matching the selected device within the resync date range, using the stored scoring timezone for deterministic day boundaries. |
| `SelectedSourcePrunerImpl`    | `core/database/src/main/kotlin/app/readylytics/health/data/local/SelectedSourcePrunerImpl.kt`     | Concrete implementation of `SelectedSourcePruner`. Deletes non-selected-device records within scoring-zone-derived epoch boundaries; ambient device timezone never changes which edge-day records are pruned. |

### 1.2.1 Session-link reconciliation — chunk-independent determinism

| Component               | Path                                        | Responsibility                                                                                                                                                                                                                                                                                                                                                                                                              |
| :---------------------- | :------------------------------------------ | :-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SessionLinker`         | `core/model/src/main/kotlin/app/readylytics/health/domain/sync/link/SessionLinker.kt`         | Pure function `resolve(sampleMs, sleepSessions, workoutSessions): SampleLink`. Single source of truth for "which session does this HR/HRV sample belong to?" — sleep > workout > resting precedence, ties on overlapping spans broken by earliest `(startTime, id)`. Mathematically equivalent to the forward-pointer logic in `HeartRateMapper`/`HrvMapper` for ascending-sorted samples.                                  |
| `SessionLinkReconciler` | `core/model/src/main/kotlin/app/readylytics/health/domain/sync/link/SessionLinkReconciler.kt` | Domain-side reconcile port. Data implementation (`core/database/src/main/kotlin/app/readylytics/health/data/local/SessionLinkReconcilerImpl.kt`) runs post-ingestion **once per `resyncRange` call** (not per chunk): loads the complete sleep + workout session spans for `[start - 1 day, end]`, re-tags every HR/HRV row in range via `SessionLinker.resolve`, and recomputes `trimp`/zone-minutes/`avgHr`/`durationMinutes` for every workout in range via `WorkoutMapper.computeMetrics`. Processes heart rate and HRV records in keyset-paged chunks of 5000 records, running each batch in a transaction, and runs a transaction per workout recomputation. Only changed rows are upserted. |

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

### 1.3 Mappers — domain HC DTO → Room entity

All mappers consume the domain DTOs returned by `HealthConnectRepository`; those DTOs already
carry the stable `deviceName` derived from `DeviceLabel.from(device, dataOrigin)` in the Health
Connect adapter. Mappers copy that label and use deterministic composite IDs
(`${hcRecordId}_${timestampMs}`) so re-ingestion is idempotent. Native Health Connect SDK records
are intentionally confined to `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRepositoryImpl.kt`.

**Read methods:** `readSleepSessions`, `readHeartRateSamples`, `readHrvSamples`,
`readExerciseSessions`, `readSteps` / `readStepsRange`, `readWeightRecords`,
`readBodyFatRecords`, `readBloodPressureRecords`, `readOxygenSaturationRecords`,
`discoverDevices`.

**Rate-Limit and Transient Fault Protection:**
Each Health Connect read is retried through `HealthConnectRetryPolicy`, which retries transient
IO, quota, and rate-limit failures with bounded exponential backoff and jitter. Cancellation is
rethrown. Room writes remain outside the read retry loop so ingestion failure boundaries stay
explicit and idempotent.

### 1.2 Sync engine — orchestration, chunking, idempotency

| Component                     | Path                                         | Responsibility                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| :---------------------------- | :------------------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `HealthSyncUseCase`           | `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/HealthSyncUseCase.kt`           | Facade owning `syncMutex`; its public methods delegate to `DailySyncUseCase` (`sync`), `ResyncRangeUseCase` (`resyncRange`), and chunked catch-up resync (`catchUpSync`), which carry the behavior described in this row. `catchUpSync` is gated by `lastSyncTimestamp == 0` and executes `resyncRangeUseCase.run` in 30-day chunks over a 365-day range. `sync(windowDays, onProgress)` recent-window sync — note the **ingestion fetch starts one day earlier than the scored window** (`today − windowDays`), because overnight sleep sessions begin the previous evening; clipping at the scored window's midnight would drop a night's pre-midnight HR/HRV samples (lower HRV mean, higher RHR percentile). After recent-window ingest, `sync()` runs `SessionLinkReconciler.reconcile(...)` over the ingested overlap before scoring so pull-to-refresh preserves the same canonical HR/HRV session links as historical resync. Raw Health Connect reads stay in the sync engine, but Room writes now flow through `HealthIngestionStore.persist(batch)` and frozen-baseline clearing through `HealthIngestionStore.clearFrozenBaselines(start, endExclusive)`, keeping DAO/transaction details in data layer. The recalc loop covers `windowDays`, widened down to the earliest **recent** out-of-window affected day (within `MAX_INLINE_RECOMPUTE_DAYS` = 7 of today, in `DailySyncUseCase`) so last night's sleep (dated yesterday) or backfilled HR/HRV recomputes inline; only changes older than that inline bound escalate to durable historical resync. `resyncRange(start, end, chunkDays = 30, onProgress)` full historical runs **four resumable phases**: chunked ingest → selected-source prune → full-range session-link reconcile → walk-forward recompute. Before first ingest it captures Changes API baseline tokens and stores them with the immutable range, current phase, next date, and device-selection hash in `ResyncCheckpointStore`; retries reuse the same baseline, while legacy/mismatched checkpoints restart cleanly. **Every ingestion chunk of `resyncRange` starts one day early and fetches sleep/exercise sessions one day past the chunk end** so HR/HRV samples at either side of a 30-day boundary can still be assigned to cross-midnight sessions. Metric sample reads remain capped to the chunk. Step totals are rebuilt only for the remaining recompute range on resume, preserving selected-device correctness without re-running completed raw ingest. After all chunks are ingested, a single `SessionLinkReconciler.reconcile(...)` pass starting one day before the start date (i.e. `start - 1 day` to `end`) re-derives HR/HRV session linkage and recomputes affected workout metrics (see 1.2.1) — this makes the result independent of chunk alignment. Historical resync progress reports recomputed calendar days, not internal ingest/prune/reconcile units, but resumed recompute starts with the already-completed day offset. `HealthIngestionCoordinator.ingestWindow(start, end, prefs)` remains the single read→map→filter funnel (shared by both flows); `StepCountFetcher` performs per-device step reads (recent window + historical range); `DailyRecomputeSupport` runs the per-day score recompute and auto-MaxHR refresh; `retryWithBackoff(maxAttempts = 4, initialDelayMs = 1000)` (shared helper in `RetryWithBackoff.kt`) handles transient HC/IO faults (never swallows `CancellationException`); `syncMutex` (owned by the facade) serializes daily vs. resync. |
| `DailySyncUseCase`            | `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailySyncUseCase.kt`            | Foreground daily-sync orchestrator (the `sync(windowDays)` body). Runs under the facade's `syncMutex`. Owns the recent-window ingest → reconcile-over-overlap → frozen-baseline clear → walk-forward recompute, the cross-midnight reach-back, change-token commit, and the `REQUIRES_HISTORICAL_RESYNC` decision. |
| `ResyncRangeUseCase`          | `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCase.kt`          | Full-historical-resync orchestrator (the `resyncRange(...)` body). Runs under the facade's `syncMutex`. Owns the four resumable phases (chunked ingest → selected-source prune → full-range session-link reconcile → walk-forward recompute), checkpoint capture/resume, and baseline-token promotion. |
| `HealthIngestionCoordinator`  | `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/HealthIngestionCoordinator.kt`  | Single read→map→device-filter→upsert funnel for one HC window (`ingestWindow`), shared by both flows. Holds the entity→input mappers. Room writes go through `HealthIngestionStore.persist(batch)`; low-volume parent records commit first, then HR/HRV samples commit in cooperative 5,000-row batches. If interrupted, the checkpoint stays on the current HC window and stable-ID upserts make the retry safe without deleting prior valid data. |
| `StepCountFetcher`            | `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/StepCountFetcher.kt`            | Per-device daily step reads. `fetchWindow(...)` for the recent window (semaphore-capped concurrent reads when no device filter); `fetchRange(...)` for the resync recompute range (chunked, retry-wrapped). |
| `DailyRecomputeSupport`       | `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailyRecomputeSupport.kt`       | Shared per-day helpers: `recomputeDay(day, steps)` → `ScoringRepository.computeAndPersistDailySummary` (single point of daily score persistence; no math here), and `refreshAutoMaxHr(prefs)`. |
| `ForegroundSyncController`    | `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ForegroundSyncController.kt`    | Foreground state + progress bridge. `triggerDailySync()` = pull-to-refresh (current day only, `windowDays = 1`); `triggerImmediateSync()` = first-launch catch-up; `onBackgroundRecalc{Started,Progress,Finished}()` publish WorkManager job progress into `isSyncing` / `recalcProgress` StateFlows + `syncCompletedEvent`. |
| `FullHistoricalResyncUseCase` | `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/FullHistoricalResyncUseCase.kt` | Resolves the retention-bounded start date via `RetentionBounds.resolveResyncStartDate()` and delegates to `HealthSyncUseCase.resyncRange(start, today)`. Checkpoint/resume behavior stays in the sync engine; no math. |
| `HealthResyncWorker`          | `app/src/main/kotlin/app/readylytics/health/workers/HealthResyncWorker.kt`              | `@HiltWorker` durable foreground service (`FOREGROUND_SERVICE_TYPE_DATA_SYNC`). Runs the resync use case, emits `WorkInfo` progress (`setProgressAsync`), posts a determinate "day X of Y" notification, bridges progress to `ForegroundSyncController`; `Result.retry()` on transient failure, but confirmed permission failures stop with `Result.failure()` so WorkManager does not loop. Checkpoints remain available for a new sync after access is restored. |
| `WorkerScheduler`             | `core/model/src/main/kotlin/app/readylytics/health/workers/WorkerScheduler.kt`                 | Enqueues unique work. `scheduleResyncWorker()` (`RESYNC_WORK_NAME`, `ExistingWorkPolicy.KEEP`, expedited, exponential backoff) + `cancelResyncWorker()`; `schedulePeriodicSync(intervalMinutes)` (`PERIODIC_SYNC_WORK_NAME`, `ExistingPeriodicWorkPolicy.UPDATE`, exponential backoff) + `cancelPeriodicSync()`; also backup / birthday / data-cleanup workers. |
| `PeriodicHealthSyncWorker`    | `app/src/main/kotlin/app/readylytics/health/workers/PeriodicHealthSyncWorker.kt`        | "Background Sync" toggle in Settings. `@HiltWorker` periodic **standard (non-foreground) worker** calling `HealthSyncUseCase.sync(windowDays = 2)` (shares `syncMutex` with the other two flows), bridges progress to `ForegroundSyncController`, shows/dismisses a silent transient notification (`SyncNotifications.BACKGROUND_SYNC_CHANNEL_ID`) via `NotificationManagerCompat` directly — no `setForeground()`, since `READ_HEALTH_DATA_IN_BACKGROUND` already permits background HC reads and starting a foreground service from a periodic background worker risks `ForegroundServiceStartNotAllowedException` on API 34+. Ordinary failures return `Result.retry()`; `REQUIRES_HISTORICAL_RESYNC` enqueues `WorkerScheduler.scheduleResyncWorker()` and finishes successfully so durable catch-up runs once without periodic retry churn. |
| `DataCleanupWorker`           | `app/src/main/kotlin/app/readylytics/health/workers/DataCleanupWorker.kt`               | Daily retention enforcement; cutoff resolved via `RetentionBounds.resolveRetentionCutoffMs()` (shared with resync). Delegates to `RetentionCleanup`. No-op when retention disabled. |
| `RetentionCleanup`            | `core/database/src/main/kotlin/app/readylytics/health/data/local/RetentionCleanup.kt`             | Executes transactional deletions of data strictly older than the cutoff across all 9 sensitive tables. |
| `RetentionBounds`             | `core/model/src/main/kotlin/app/readylytics/health/domain/util/RetentionBounds.kt`             | Single source of truth for retention→date math: enabled → `today − retentionDays`; disabled → `today − ABSOLUTE_MAX_DAYS` (3650 / 10y). |
| `RoomTransactionRunner`       | `core/database/src/main/kotlin/app/readylytics/health/data/local/RoomTransactionRunner.kt`        | Wraps `HealthDatabase.withTransaction { … }`. Ingestion commits parent/low-volume records together, then HR and HRV in bounded 5,000-row transactions with cancellation checks between batches. A failed window may contain partial new upserts, but never deletes prior valid rows; its unchanged checkpoint causes an idempotent replay. |
| `HealthChangeSynchronizer`    | `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/HealthChangeSynchronizer.kt`    | Reconciles differential Health Connect Changes API responses (upsertions and deletions) incrementally during daily/foreground sync. Resolves dates of deleted records via local DB lookup. Composite-key metric changes delete every Room row owned by the HC source record before re-upsert. The synchronizer returns candidate next tokens but never persists them; the daily-sync flow (`DailySyncUseCase`) commits them only after requested-window ingest, reconciliation, step fetch, and scoring all succeed. Changes older than the requested scoring window leave tokens uncommitted and return `REQUIRES_HISTORICAL_RESYNC`, routing correction through the durable worker without widening foreground scoring. |
| `HealthChangeTokenStore`      | `core/model/src/main/kotlin/app/readylytics/health/domain/sync/HealthChangeTokenStore.kt`      | Atomically stores differential Changes Tokens per Health Connect data type after derived summaries are durable. Replayed pages are safe because Room ingestion is idempotent by HC record ID. |
| `ResyncCheckpointStore`       | `core/model/src/main/kotlin/app/readylytics/health/domain/sync/ResyncCheckpointStore.kt`       | Stores the resumable historical-resync checkpoint: fixed range, current phase (`INGEST` / `PRUNE` / `RECONCILE` / `RECOMPUTE`), next date, selection hash, and pre-ingest baseline Changes Tokens. Cleared only after recompute succeeds and baseline tokens are promoted atomically. |
| `ResyncCheckpointStoreImpl`   | `app/src/main/kotlin/app/readylytics/health/data/preferences/ResyncCheckpointStoreImpl.kt` | Proto DataStore implementation of historical-resync checkpoint persistence. |
| `SelectedSourcePruner`        | `core/model/src/main/kotlin/app/readylytics/health/domain/sync/SelectedSourcePruner.kt`        | Executes transactional, device-scoped deletes of records not matching the selected device within the resync date range, using the stored scoring timezone for deterministic day boundaries. |
| `SelectedSourcePrunerImpl`    | `core/database/src/main/kotlin/app/readylytics/health/data/local/SelectedSourcePrunerImpl.kt`     | Concrete implementation of `SelectedSourcePruner`. Deletes non-selected-device records within scoring-zone-derived epoch boundaries; ambient device timezone never changes which edge-day records are pruned. |

### 1.2.1 Session-link reconciliation — chunk-independent determinism

| Component               | Path                                        | Responsibility                                                                                                                                                                                                                                                                                                                                                                                                              |
| :---------------------- | :------------------------------------------ | :-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SessionLinker`         | `core/model/src/main/kotlin/app/readylytics/health/domain/sync/link/SessionLinker.kt`         | Pure function `resolve(sampleMs, sleepSessions, workoutSessions): SampleLink`. Single source of truth for "which session does this HR/HRV sample belong to?" — sleep > workout > resting precedence, ties on overlapping spans broken by earliest `(startTime, id)`. Mathematically equivalent to the forward-pointer logic in `HeartRateMapper`/`HrvMapper` for ascending-sorted samples.                                  |
| `SessionLinkReconciler` | `core/model/src/main/kotlin/app/readylytics/health/domain/sync/link/SessionLinkReconciler.kt` | Domain-side reconcile port. Data implementation (`core/database/src/main/kotlin/app/readylytics/health/data/local/SessionLinkReconcilerImpl.kt`) runs post-ingestion **once per `resyncRange` call** (not per chunk): loads the complete sleep + workout session spans for `[start - 1 day, end]`, re-tags every HR/HRV row in range via `SessionLinker.resolve`, and recomputes `trimp`/zone-minutes/`avgHr`/`durationMinutes` for every workout in range via `WorkoutMapper.computeMetrics`. Processes heart rate and HRV records in keyset-paged chunks of 5000 records, running each batch in a transaction, and runs a transaction per workout recomputation. Only changed rows are upserted. |

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

### 1.3 Mappers — domain HC DTO → Room entity

All mappers consume the domain DTOs returned by `HealthConnectRepository`; those DTOs already
carry the stable `deviceName` derived from `DeviceLabel.from(device, dataOrigin)` in the Health
Connect adapter. Mappers copy that label and use deterministic composite IDs
(`${hcRecordId}_${timestampMs}`) so re-ingestion is idempotent. Native Health Connect SDK records
are intentionally confined to `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRepositoryImpl.kt`.

| Mapper                       | Path                                        | DTO → Entity                                                                                                                                       |
| :--------------------------- | :------------------------------------------ | :------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SleepDataMapper`            | `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/SleepDataMapper.kt`     | `DomainSleepSessionRecord` → `SleepSessionEntity` + `List<SleepStageEntity>` (sums deep/REM/light/awake, computes efficiency).                     |
| `HeartRateMapper`            | `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HeartRateMapper.kt`     | `List<DomainHeartRateRecord>` → `List<HeartRateRecordEntity>`; assigns `recordType` (SLEEP / EXERCISE / RESTING) and `sessionId` by time matching. |
| `HrvMapper`                  | `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HrvMapper.kt`           | RMSSD records → `HrvRecordEntity`; links to sleep session or marks RESTING.                                                                        |
| `WorkoutMapper`              | `core/model/src/main/kotlin/app/readylytics/health/data/healthconnect/WorkoutMapper.kt`               | `DomainExerciseSessionRecord` (+ HR samples) → `WorkoutRecordEntity`; derives elapsed `durationMinutes`, zone minutes, avg HR, TRIMP. Route-bearing sessions persist `routeState = PENDING_FOREGROUND_LOAD`; route points are deliberately fetched only later in the foreground (see §1.1 for the per-session consent gate that keeps this state retryable until the user consents). Workout load/intensity categories are **not** persisted here. |
| `StepsMapper`                | `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/StepsMapper.kt`         | `DomainStepsRecord` or aggregate count → `StepRecordEntity`.                                                                                       |
| `WeightDataMapper`           | `core/healthconnect/src/main/kotlin/app/readylytics/health/data/mapper/WeightDataMapper.kt`           | `DomainWeightRecord` → `WeightRecordEntity` (kg).                                                                                                  |
| `BodyFatDataMapper`          | `core/healthconnect/src/main/kotlin/app/readylytics/health/data/mapper/BodyFatDataMapper.kt`          | `DomainBodyFatRecord` → `BodyFatRecordEntity` (%).                                                                                                 |
| `BloodPressureDataMapper`    | `core/healthconnect/src/main/kotlin/app/readylytics/health/data/mapper/BloodPressureDataMapper.kt`    | `DomainBloodPressureRecord` → `BloodPressureRecordEntity` (systolic/diastolic mmHg).                                                               |
| `OxygenSaturationDataMapper` | `core/healthconnect/src/main/kotlin/app/readylytics/health/data/mapper/OxygenSaturationDataMapper.kt` | `DomainOxygenSaturationRecord` → `OxygenSaturationRecordEntity` (%).                                                                               |

### 1.4 Room storage — `HealthDatabase` (`@Database(version = 6)`)

Defined in `core/database/src/main/kotlin/app/readylytics/health/data/local/HealthDatabase.kt`;
entities in `core/model/src/main/kotlin/app/readylytics/health/data/local/entity/`, DAOs in
`core/model/src/main/kotlin/app/readylytics/health/data/local/dao/`. **The database is the single source of truth; the UI never reads Health
Connect directly.**

`DatabaseMigrations` registers v1→v2, v2→v3, v3→v4, v4→v5, and v5→v6 migrations for existing installs.
- Version 4 adds the metadata-only `audit_events` table.
- Version 5 adds `supplementalSleepDurationMinutes` and `napCount` to `daily_summaries`.
- Version 6 adds the `workout_route_points` table (foreign key cascade to `workout_records.id`) and route/performance fields (`routeState`, `avgSpeedKmh`, `avgPaceMinKm`, `elevationGainMeters`, `totalDistanceMeters`) to `workout_records` for foreground route ingestion.

| Entity                         | Table                       | Primary key                            | Notable columns                                                                                                                                           |
| :----------------------------- | :-------------------------- | :------------------------------------- | :-------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SleepSessionEntity`           | `sleep_sessions`            | `id: String` (HC id)                   | start/end time, deep/REM/light/awake min, efficiency, `deviceName`                                                                                        |
| `SleepStageEntity`             | `sleep_stages`              | `id: Long` (auto)                      | `sessionId` (FK), `(sessionId, startTime)` unique — cleared per-session before re-upsert                                                                  |
| `HeartRateRecordEntity`        | `heart_rate_records`        | `id: String` (`${hcId}_${ms}`)         | `timestampMs`, `recordType`, `sessionId`, `deviceName`                                                                                                    |
| `HrvRecordEntity`              | `hrv_records`               | `id: String` (composite)               | RMSSD ms, `timestampMs`, `recordType`, `sessionId`                                                                                                        |
| `WorkoutRecordEntity`          | `workout_records`           | `id: String` (HC id)                   | zone1–5 min, TRIMP, avg HR, `startTime`, `deviceName`, `routeState`, `avgSpeedKmh`, `avgPaceMinKm`, `elevationGainMeters`, `totalDistanceMeters`          |
| `WorkoutRoutePointEntity`      | `workout_route_points`      | `id: Long` (auto)                      | `workoutId` (FK to `workout_records` ON DELETE CASCADE), `latitude`, `longitude`, `altitude`, `timestampMs`, horizontal/vertical accuracies                |
| `WeightRecordEntity`           | `weight_records`            | `id: String` (composite)               | kg, `timestampMs`, `deviceName`                                                                                                                           |
| `BodyFatRecordEntity`          | `body_fat_records`          | `id: String` (composite)               | %, `timestampMs`, `deviceName`                                                                                                                            |
| `BloodPressureRecordEntity`    | `blood_pressure_records`    | `id: String` (composite)               | systolic/diastolic, `timestampMs`, `deviceName`                                                                                                           |
| `OxygenSaturationRecordEntity` | `oxygen_saturation_records` | `id: String` (composite)               | %, `timestampMs`, `deviceName`                                                                                                                            |
| `DailySummaryEntity`           | `daily_summaries`           | `dateMidnightMs: Long`                 | computed scores (sleep/load/readiness), frozen baselines (`hrv_mu_mssd`, `hrv_sigma_mssd`, `rhr_bpm`, `rhr_sigma`, `hr_max`, …), weight/BP/SpO2 snapshots |
| `InsightDismissalEntity`       | `insight_dismissals`        | `(dateMidnightMs: Long, type: String)` | `type: String` (LATE_NADIR, SICK_INDICATOR, STRONG_RECOVERY_SIGNAL, LOAD_SPIKE_RECOVERY_STRAIN, …) — represents dismissed dashboard insights                                                       |
| `AuditEventEntity`             | `audit_events`              | `id: Long` (auto)                      | `type`, `occurredAtEpochMs`, optional coarse `detail` for local backup/restore/key-lifecycle events                                                       |

Backup/restore and key-lifecycle operations append local audit events to `audit_events` through
`AuditTrailRepository`. Audit events are metadata-only: operation type, timestamp, and coarse
result detail. They do not store health samples, backup contents, passwords, encryption keys, or
Health Connect payloads.

**Staged Restore Design:**
Restore is staged. Database replacement is atomic within Room. Preferences are restored after the
database transaction commits because Room and DataStore cannot share a transaction. If a later
stage fails, the app returns an explicit partial-success result requiring restart and instructs
the user to rerun restore.

**Encryption & Key Management:**
Local encryption keys are versioned (e.g., `readylytics_master_key_v1`) and protected via Android Keystore.
On supported devices, keys are StrongBox-backed, with fallback to standard Keystore. Current key version
and StrongBox status are tracked in `KeyMetadataStore` (backed by SharedPreferences). Safe database key
rotation is managed by `DatabaseKeyRotator`, which rekeys the SQLCipher database connection in-place and
logs the operation status to the local audit trail. Keys are hardware-bound and do not support cloud backup.

**Idempotency contract:** every DAO uses `@Upsert` keyed on the stable primary key, so
re-fetching a record **replaces** rather than duplicates. There is no blanket `deleteAll()`
in the sync path — a worker that dies mid-resync leaves prior valid data intact, and a retry
re-runs the same range cleanly. `DailySummaryDao` additionally exposes `updateBaselines()`
and `clearFrozenBaselinesBetween(fromMs, toExclusiveMs)` (the only up-front baseline mutation
during sync/resync, scoped to the recomputed scoring range and rebuilt in the same walk-forward
pass).

**Stable-order scoring contract:** sleep HR/HRV DAO reads used by the scoring pipeline return
deterministic order (`timestampMs`, then stable `id`, or BPM plus timestamp/id for percentile
queries). This keeps HRV means, percentile HR picks, frozen-baseline replay, and near-boundary
Sleep/Readiness display rounding stable across app-open recalculation, background sync, and
historical resync. Recent sync and historical resync both run `SessionLinkReconciler` after
ingestion so overlap upserts cannot replace canonical session links with mapper-local links before
scoring.

---

## 2. Processing & Scoring Engine Pipeline (SQLite → Calculations)

**All calculation logic lives in `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/**`and is pure Kotlin (zero Android
dependencies).** The repository layer fetches Room entities and feeds them in; the engine
returns computed values; the repository persists them. This separation is an invariant —
keep Android types out of`core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/\*\*`.

### 2.1 Coordinator

| Component                       | Path                                       | Responsibility                                                                                                                                                                                                                                                                                                                                                                                          |
| :------------------------------ | :----------------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `ScoringRepository` (interface) | `core/model/src/main/kotlin/app/readylytics/health/domain/repository/ScoringRepository.kt`   | Contract for daily computation in domain types: `computeDailySummary(day): DailySummary`, `persist(summary)`, `computeAndPersistDailySummary(day, steps?)`, `toReadinessResult(summary)`. Domain callers never depend on Room entity shapes.                                                                                                                                                                                                                  |
| `ScoringRepositoryImpl`         | `core/database/src/main/kotlin/app/readylytics/health/data/repository/ScoringRepositoryImpl.kt` | `computeDailySummary(day)` orchestrates: raw DAO fetch → daily TRIMP/RAS → baseline resolve/freeze → sleep + load + readiness → return domain `DailySummary`. `computeAndPersistDailySummary(day, steps?)` is the production sync entrypoint: it mutex-locks, snapshots preferences once, computes with that snapshot, optionally applies a fresh step count, then maps back through `DailySummaryMapper` and writes `DailySummaryEntity` using the **same scoring zone snapshot**. Hosts the "Calibrating" gate (< 7 sleep sessions in the last 42 days → raw sleep/recovery measurements only; Sleep Score, Load Score, and Readiness remain hidden). **Both sync flows recompute exclusively through this repository — formulas are never duplicated in the sync engine.** Day boundaries (`dateMidnightMs`), affected-date attribution, raw fetch windows, TRIMP bucketing, and persisted summary midnight all resolve through the **stored scoring timezone** (`UserPreferences.scoringZoneId` → `scoringZone()`, seeded once from the device zone by a DataStore migration), not `ZoneId.systemDefault()`, so identical SQLite + preferences reproduce identical scores across devices/timezones. Historical workout and everyday-HR TRIMP DAOs return timestamp/value rows; `TrimpDateBucketer` converts each timestamp with that stored zone before ATL/CTL aggregation, and the ATL/CTL history fetch start is derived from `targetDate.minusDays(84).atStartOfDay(zone)` rather than subtracting fixed 24-hour durations, so DST boundaries stay calendar-correct. The same stored zone is threaded through `DailySummaryMapper` when data crosses domain/storage boundary, plus `CircadianConsistencyRepository`, `HrvBaselineProvider`, `RhrBaselineProvider`, and `ComputeHistoricalBaselinesUseCase`. |

### 2.2 Resting Heart Rate (RHR) — percentile "nocturnal floor"

| Component                                          | Path                                                   | Model / inputs                                                                                                                                                                                                                                                                                                                                                                                                        |
| :------------------------------------------------- | :----------------------------------------------------- | :-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SleepPercentileRhrCalculator`                     | `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/SleepPercentileRhrCalculator.kt` | `collect(session, dayMidnight, percentile)` — sorts overnight sleep HR samples and takes the configured **percentile** as the nightly resting nadir; baseline = median of historical nightly percentile values over a 30-day sleep-session window. Default percentile = `SettingsDefaults.RESTING_HR_PERCENTILE` (**5th**; user-configurable, validator range **1–15** in `domain/validation/SettingsValidators.kt`). |
| `BaselineComputer.computeAdaptiveBaselineRhrBpm()` | `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/BaselineComputer.kt`                   | Live recompute variant of the same 30-day / percentile logic, filtering invalid sessions (insufficient samples / failed sleep validation).                                                                                                                                                                                                                                                                            |

### 2.3 Training Impulse (TRIMP) — multi-model engine

| Component                          | Path                                                 | Model / default                                                                                                                                                                                                                              |
| :--------------------------------- | :--------------------------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `TrimpModel` (enum)                | `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/TrimpModel.kt`                       | `BANISTER`, `I_TRIMP`, `CHENG`.                                                                                                                                                                                                              |
| `RasCalculator`                    | `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/RasCalculator.kt`                    | `calculateDailyTrimp(..., trimpModel = TrimpModel.BANISTER)` switches per model — **BANISTER is the operational default** (default parameter value). `calculateDailyRas()` converts TRIMP → RAS via a profile scaling factor (capped at 75). |
| `ComputeWorkoutTrimpUseCase`       | `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/ComputeWorkoutTrimpUseCase.kt`       | Per-workout integration over HR samples; reads the user-selected model from `prefs.trimpModel`.                                                                                                                                              |
| `ComputeWorkoutLoadMetricsUseCase` | `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/ComputeWorkoutLoadMetricsUseCase.kt` | Single per-workout load source for workout history/detail UI: resolves precise TRIMP + gained strain from DB-backed workout samples, then derives `WorkoutLoadClassification` from unrounded workout TRIMP and elapsed `durationMinutes`. **Base load** comes from total TRIMP, **intensity** comes from TRIMP/min, and intensity may promote load by at most one step; numeric TRIMP itself remains unchanged. |
| `GetWorkoutDisplayMetricsUseCase`  | `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/GetWorkoutDisplayMetricsUseCase.kt`  | Unified display metrics provider for workouts. Orchestrates 42-day history fetching and delegates calculations to `ComputeWorkoutLoadMetricsUseCase` to return UI-ready TRIMP/strain values plus derived overall-load and intensity labels. |
| `ScoringConfigFactory`             | `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/ScoringConfigFactory.kt`             | Threads `userPreferences.trimpModel` into the scoring config.                                                                                                                                                                                |

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

### 2.4 Baselines & calibration

**Physiology profiles** are now exactly **Athlete / Active / Sedentary**
(`data/preferences/PhysiologyProfile.kt`); `Active` is the default. The removed
`GENERAL` and `SHIFT_WORKER` profiles map to `ACTIVE` at the proto read boundary
(`UserPreferences.toDomainProfile`) and are canonicalized in storage by a one-time
`DataStoreModule` migration — the proto enumerators `PROFILE_GENERAL`/`PROFILE_SHIFT_WORKER`
stay reserved (never reused) so old payloads/backups still deserialize.

`core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/BaselineComputer.kt` computes and snapshots per-day frozen baselines:
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

**Phase model** (`core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/components/Phase.kt` + `PhaseCalculator.kt`) classifies
each day's `totalValidHrvNights` (baseline-usable session count, computed in
`ComputeSleepMetricsUseCase`) into one of four phases, each carrying a `ConfidenceLevel`:
Calibration 0-6 (Not Ready), Early Baseline 7-20 (Low), Maturing 21-59 (Medium), Mature 60+
(High). The result is persisted per day as `snapshotCalibrationPhase` on
`DailySummaryEntity`/`DailySummary` for dashboard + About display. This is independent of
the diagnostic, days-since-install `phaseName` inlined in `AuditTrailFactory` (debug/audit
trail only, not part of `computeConfigHash`).

The historical backfill (`core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/BackfillHistoricalBaselinesUseCase` →
`ComputeHistoricalBaselinesUseCase`) wipes derived baselines and recomputes the entire
history at app start. It resolves all per-day HRV/RHR windows via the **batched**
`BaselineComputer.computeBackfillBaselines()` — a fixed, small number of DB reads for the
whole range instead of ~11 queries per day — which reproduces the per-day
`compute*Between` window/validity/nadir math exactly. Daily baseline upper bounds are
treated as exclusive next-day midnights before hitting Room's inclusive `getBetween`
predicate, so a session ending exactly at the next midnight belongs to the next day. The
same backfill path also carries the RHR history used to freeze `rhrSigma` for later RHR
z-score restoration (guarded by equivalence tests). The
per-day UPDATEs are collapsed into a single transaction by the backfill use-case.

### 2.4.1 Biphasic sleep-day aggregation

`core/database/src/main/kotlin/app/readylytics/health/data/repository/ScoringRepositoryImpl.kt`
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
| `SleepScoringStrategy`       | `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/strategies/SleepScoringStrategy.kt` | Sleep score = **Duration 50% + Architecture 25% + Restoration 25%** (Restoration from HRV & RHR z-scores).                  |
| `LoadScoringStrategy`        | `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/strategies/LoadScoringStrategy.kt`  | Load score from the **Strain Ratio** (ATL/CTL): `sr ≤ 1.3 → 100`, `sr > 1.3 → 100·exp(−2.5·(sr−1.3)²)`. Feeds the readiness composite (0.4 restoration + 0.3 sleep + 0.3 load). Only `ILLNESS_ONSET` (cap 50) caps the readiness number and requires two consecutive nights; strong recovery, workout-impact, and rest-day flags are informational only and do not cap the score. |
| `RasScoringStrategy`         | `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/strategies/RasScoringStrategy.kt`   | **CTL (42-day)** and **ATL (7-day)** exponential moving averages of daily TRIMP.                                            |
| `ComputeSleepMetricsUseCase` | `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/ComputeSleepMetricsUseCase.kt`      | Consumes the `SleepDayAggregate` / core-isolated `scoringSession` from `ScoringRepositoryImpl.resolveSleepAggregation(...)`, then assembles sleep/readiness metrics for the day from the strategies + baselines. |
| `CircadianConsistencyRepository` | `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/CircadianConsistencyRepository.kt` | Live bed/wake-time consistency score. The allowed deviation **threshold** resolves through the single `CircadianThresholdDefaults.resolveThreshold(profile, override)` (Athlete 20 / Active 30 / Sedentary 45 min; override wins). The encrypted `circadianThresholdOverride` is the user knob; a legacy non-default flat `consistencyThresholdMinutes` is honored as an override for back-compat. The former per-profile strategy classes are deleted — there is now one resolver. |

Supporting helpers live in `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/components/` and `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/`
(architecture targets, restoration weights, nadir analysis, HR coverage validation).
`CircadianThresholdDefaults` (`core/model/src/main/kotlin/app/readylytics/health/domain/circadian/`) is the single threshold source, consumed
by both the live repository above and the diagnostic config built in `ScoringConfigFactory`.

### 2.6 Everyday Heart-Rate Load

| Component | Path | Output |
| :--- | :--- | :--- |
| `EverydayHeartRateLoadCalculator` | `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/EverydayHeartRateLoadCalculator.kt` | Pure Kotlin. Buckets a day's non-sleep, non-workout HR samples into 1-minute windows, classifies each via `HrZoneClassifier`, and accumulates per-minute TRIMP via `RasCalculator.calculateDailyTrimp` (Zone 0 excluded from TRIMP, included in `coverageMinutes`). Returns `EverydayHrLoadResult` (`nonWorkoutTrimp`, `totalEverydayTrimp = workoutOnlyTrimp + nonWorkoutTrimp`, `coverageMinutes`, `validBucketCount`, `confidence: LoadCoverageConfidence`). |
| `ScoringRepositoryImpl.computeDailySummary` | `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/ScoringRepositoryImpl.kt` | Fetches the day's non-sleep, non-workout HR samples plus sleep/workout intervals, feeds `EverydayHeartRateLoadCalculator`, and persists both `*WorkoutOnly` and `*EverydayHr` variants (TRIMP, RAS, total RAS, ATL, CTL, Strain Ratio, Load Score, Readiness) plus `everydayCoverageMinutes`/`everydayLoadConfidence` on `DailySummaryEntity`. |
| `LoadSourceSelector` | `core/model/src/main/kotlin/app/readylytics/health/domain/model/LoadSourceSelector.kt` | Pure projection. Picks `*WorkoutOnly` or `*EverydayHr` per field from `UserPreferences.strainLoadSourceMode` (TRIMP/ATL/CTL/Strain Ratio/Load Score/Readiness) and `rasSourceMode` (daily/total RAS); also derives `needsRecalc` and `readinessLowConfidence`. |
| `DailyMetricsMapper` | `core/model/src/main/kotlin/app/readylytics/health/domain/model/DailyMetricsMapper.kt` | Builds `DailyMetrics` exclusively through `LoadSourceSelector` for all user-visible strain/load/RAS/readiness fields, so switching either source preference re-projects already-stored data instantly with no recompute. |

The two source preferences (`strainLoadSourceMode`, `rasSourceMode`, both on `UserPreferences`)
are independent: Readiness always derives from `strainLoadSourceMode`, never from
`rasSourceMode`. Coefficients and thresholds live solely in
`EverydayHeartRateLoadCalculator.kt` / `ScoringConstants` — see ABOUT.md for the
user-facing description of `coverageMinutes`, `validBucketCount`, and confidence tiers.

---

## 3. Presentation Layer (Calculated States → UI)

### 3.1 ViewModels → `StateFlow`

ViewModels collect repository flows, fuse them with `combine()`, and expose immutable
`*UiState` via `stateIn()`. Screens collect with `collectAsStateWithLifecycle()`.

| ViewModel                                                                                                   | Path                                                          | Exposes                                                                                                                                                                                                                    |
| :---------------------------------------------------------------------------------------------------------- | :------------------------------------------------------------ | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `DashboardViewModel`                                                                                        | `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModel.kt` | `uiState: StateFlow<DashboardUiState>` (summary, card map, circadian, RAS, `recalcProgress`); `onRefresh()` → `foregroundSyncController.triggerDailySync()`.                                                               |
| `SyncViewModel`                                                                                             | `ui/sync/SyncViewModel.kt`                                    | `uiState` (sealed sync state machine), `isSyncing`, `recalcProgress` (forwarded from `ForegroundSyncController`).                                                                                                          |
| `VitalsViewModel`                                                                                           | `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsViewModel.kt`         | HRV / RHR / SpO2 daily trends + baseline bands.                                                                                                                                                                            |
| `SleepViewModel`                                                                                            | `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepViewModel.kt`                      | Sleep summary, stage timeline, circadian consistency, sleep window/duration trend data.                                                                                                                                    |
| `WorkoutsViewModel` / `WorkoutDetailViewModel`                                                              | `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/`                                 | Daily TRIMP/strain trends, RAS breakdown; per-workout TRIMP/strain/HRR. Per-workout load cards/rows consume `ComputeWorkoutLoadMetricsUseCase` so history and detail show the same rounded TRIMP and gained-strain values plus the same derived **overall load** and optional **intensity** label. For post-workout HRR, `WorkoutDetailViewModel` extends both the Health Connect pull and Room heart-rate query through `workout end + 3 minutes + hrrToleranceSeconds`; `RecoveryMetricsMapper` then matches each 1/2/3-minute target to the nearest sample inside an inclusive `±hrrToleranceSeconds` window (default `30` seconds). The workout chart and end-of-workout HR input stay clamped to samples at or before the workout end, so the extended recovery read does not widen the plotted workout timeline or shift `endHr`. |
| `HeartRateDetailViewModel`                                                                                  | `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/heartrate/HeartRateDetailViewModel.kt`| Intra-day HR samples + zone totals.                                                                                                                                                                                        |
| `StepDetailViewModel` / `WeightDetailViewModel` / `BloodPressureDetailViewModel` / `BodyFatDetailViewModel` | `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/`                                     | Per-metric trends, statuses, formatted display.                                                                                                                                                                            |

### 3.2 UI state wrappers

`@Immutable` data classes: `DashboardUiState` (+ intermediates in
`feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardFlowIntermediate.kt`,
`feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardLoadingState.kt`), per-screen
`*UiState` (`HeartRateDetailUiState`, `VitalsUiState`, `SleepUiState`, `WorkoutsUiState`, …),
and `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/SettingsState.kt` (incl. `SyncSettingsState` with resync current/total
progress).

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
| `SleepStagesChart` / `SleepArchitectureBar`                                                   | `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/`              | Sleep stage Canvas visualizations.                                                                                                         |
| `VicoChartTooltipOverlay` / `DataPointTooltip`                                                | `ui/components/`                                  | Touch interception + floating tooltip overlay for Vico charts.                                                                             |
| `ReorderableCardGrid` (+ `reorder/DragController`)                                            | `ui/components/`                                  | Drag-and-drop dashboard card grid.                                                                                                         |
| `SleepTrendChart`                                                                             | `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendChart.kt` | Vico stacked column & line dual-axis sleep window & duration chart.                                                                        |                                                                        |

### 3.4 Recalc-progress trace (background job → UI)

```
HealthResyncWorker
  → ForegroundSyncController.onBackgroundRecalcProgress(current, total)
     → ForegroundSyncController.recalcProgress: StateFlow<RecalcProgress?>
        → SyncViewModel.recalcProgress
           → MainScaffold (collectAsStateWithLifecycle)
              → RecalcProgressBanner("Recalculating day X of Y")
```

The same `RecalcProgress` also drives the foreground-service notification and the Settings
resync dialog (via `WorkInfo` observed through `getWorkInfosForUniqueWorkFlow`). When a
historical recompute resumes from checkpoint, this progress starts at the already-completed
day offset instead of resetting to zero.

---

## 4. Component & File Registry

| Source File Path                                                           | Layer / Responsibility                              | Associated Metric / Formula                                                              |
| :------------------------------------------------------------------------- | :-------------------------------------------------- | :--------------------------------------------------------------------------------------- |
| `core/model/src/main/kotlin/app/readylytics/health/domain/repository/HealthConnectRepository.kt`                             | Ingestion — HC contract, permissions                | —                                                                                        |
| `core/model/src/main/kotlin/app/readylytics/health/domain/model/HealthConnectRecords.kt`                                      | Ingestion — Android-free HC DTO boundary            | app-owned sleep / HR / HRV / exercise / steps / optional metric records                  |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRepositoryImpl.kt`                        | Ingestion — paginated HC reads                      | `readAllPages<T>()` (pageToken)                                                          |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/HealthSyncUseCase.kt`                                         | Ingestion — sync facade (owns `syncMutex`)          | delegates `sync` → `DailySyncUseCase`, `resyncRange` → `ResyncRangeUseCase`              |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailySyncUseCase.kt`                                          | Ingestion — daily-sync orchestrator                 | recent-window ingest → reconcile → walk-forward recompute                               |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCase.kt`                                        | Ingestion — historical-resync orchestrator          | 4-phase resumable resync (ingest/prune/reconcile/recompute)                             |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/HealthIngestionCoordinator.kt`                               | Ingestion — read→map→filter→upsert funnel           | `ingestWindow` (shared by both flows) + entity→input mappers                            |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/StepCountFetcher.kt`                                          | Ingestion — per-device step reads                   | `fetchWindow` (recent) / `fetchRange` (historical)                                       |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/RetryWithBackoff.kt`                                          | Ingestion — transient-fault retry                   | bounded exponential backoff; never swallows `CancellationException`                     |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailyRecomputeSupport.kt`                                     | Ingestion — per-day recompute helpers               | `recomputeDay` → `ScoringRepository` (no math) + `refreshAutoMaxHr`                     |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/HealthChangeSynchronizer.kt`                                  | Ingestion — sync changes port                       | Applies pending changes; captures/commits token maps                                    |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthChangeSynchronizerImpl.kt`                       | Ingestion — sync changes implementation             | Applies changes and returns uncommitted candidate tokens                                |
| `core/model/src/main/kotlin/app/readylytics/health/domain/sync/HealthChangeTokenStore.kt`                                    | Ingestion — token store port                        | Atomic per-type token-map persistence                                                   |
| `app/src/main/kotlin/app/readylytics/health/data/preferences/HealthChangeTokenStoreImpl.kt`                           | Ingestion — token store implementation              | Proto DataStore implementation of change token storage                                  |
| `app/src/main/proto/health_change_tokens.proto`                                         | Ingestion — change token schema                     | Proto schema for change tokens                                                          |
| `core/model/src/main/kotlin/app/readylytics/health/domain/sync/ResyncCheckpointStore.kt`                                     | Ingestion — checkpoint store port                   | Port for resumable historical resync checkpoints                                       |
| `app/src/main/kotlin/app/readylytics/health/data/preferences/ResyncCheckpointStoreImpl.kt`                            | Ingestion — checkpoint store implementation         | Proto DataStore implementation of resync checkpoint storage                            |
| `app/src/main/proto/resync_checkpoint.proto`                                            | Ingestion — checkpoint schema                       | Resync phase/date plus pre-ingest baseline token map                                    |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ForegroundSyncController.kt`                                  | Ingestion — foreground trigger + progress           | daily sync (1 day); recalc progress publish                                              |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/FullHistoricalResyncUseCase.kt`                               | Ingestion — resync orchestration                    | retention-bounded range                                                                  |
| `core/model/src/main/kotlin/app/readylytics/health/domain/util/RetentionBounds.kt`                                           | Ingestion — retention math                          | `today − retentionDays` / `ABSOLUTE_MAX_DAYS`                                            |
| `app/src/main/kotlin/app/readylytics/health/workers/HealthResyncWorker.kt`                                            | Ingestion — durable background resync               | `WorkInfo` progress, foreground notification                                             |
| `app/src/main/kotlin/app/readylytics/health/workers/PeriodicHealthSyncWorker.kt`                                      | Ingestion — periodic background sync (2-day window) | silent transient notification                                                            |
| `core/model/src/main/kotlin/app/readylytics/health/workers/WorkerScheduler.kt`                                               | Ingestion — work scheduling                         | unique resync (KEEP) + periodic sync (UPDATE)                                            |
| `app/src/main/kotlin/app/readylytics/health/workers/DataCleanupWorker.kt`                                             | Ingestion — retention enforcement                   | retention cutoff (shared)                                                                |
| `core/database/src/main/kotlin/app/readylytics/health/data/local/RetentionCleanup.kt`                                           | Ingestion — retention cleanup                       | transactional deletion across all 9 sensitive tables                                    |
| `core/database/src/main/kotlin/app/readylytics/health/data/local/RoomTransactionRunner.kt`                                      | Ingestion — atomic transaction                      | per-window upsert                                                                        |
| `core/model/src/main/kotlin/app/readylytics/health/domain/sync/link/SessionLinker.kt`                                        | Ingestion — session linkage                         | pure `resolve()`: sleep > workout > resting precedence                                   |
| `core/model/src/main/kotlin/app/readylytics/health/domain/sync/link/SessionLinkReconciler.kt`                                | Ingestion — post-resync reconcile                   | re-tags HR/HRV by session, recomputes workout TRIMP/zones                                |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/SleepDataMapper.kt`                                    | Ingestion — mapper                                  | sleep session + stages                                                                   |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HeartRateMapper.kt`                                    | Ingestion — mapper                                  | HR samples → SLEEP/EXERCISE/RESTING                                                      |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HrvMapper.kt`                                          | Ingestion — mapper                                  | RMSSD samples                                                                            |
| `core/model/src/main/kotlin/app/readylytics/health/data/healthconnect/WorkoutMapper.kt`                                      | Ingestion — mapper                                  | zone minutes + workout TRIMP                                                             |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/StepsMapper.kt`                                        | Ingestion — mapper                                  | raw selected-device steps / aggregate all-device steps                                   |
| `data/mapper/{Weight,BodyFat,BloodPressure,OxygenSaturation}DataMapper.kt` | Ingestion — mappers                                 | weight / body fat / BP / SpO2                                                            |
| `core/database/src/main/kotlin/app/readylytics/health/data/local/HealthDatabase.kt`                                             | Storage — Room DB (v6)                              | 13 entities; v1→v6 migrations wired through `DatabaseMigrations`                         |
| `core/model/src/main/kotlin/app/readylytics/health/data/local/entity/WorkoutRoutePointEntity.kt`                               | Storage — raw GPS route point                       | coordinates, altitude, timestamp, accuracies, cascade FK to workout                      |
| `core/model/src/main/kotlin/app/readylytics/health/data/local/entity/DailySummaryEntity.kt`                                  | Storage — computed-day snapshot                     | scores + frozen baselines                                                                |
| `core/model/src/main/kotlin/app/readylytics/health/data/local/entity/InsightDismissalEntity.kt`                              | Storage — insight dismissal                         | dateMidnightMs + type                                                                    |
| `core/database/src/main/kotlin/app/readylytics/health/data/local/entity/AuditEventEntity.kt`                                  | Storage — local audit events                        | metadata-only backup/restore/key-lifecycle events                                        |
| `core/model/src/main/kotlin/app/readylytics/health/data/local/entity/*.kt` (sleep, HR, HRV, workout, weight, …)              | Storage — raw metric entities                       | upsert by stable HC id                                                                   |
| `core/model/src/main/kotlin/app/readylytics/health/data/local/dao/InsightDismissalDao.kt`                                    | Storage — insight dismissal DAO                     | observe / dismiss / restore                                                              |
| `core/database/src/main/kotlin/app/readylytics/health/data/local/dao/AuditEventDao.kt`                                       | Storage — local audit DAO                           | append / observe recent metadata events                                                  |
| `core/model/src/main/kotlin/app/readylytics/health/data/local/dao/*.kt`                                                      | Storage — DAOs                                      | `@Upsert`, `clearFrozenBaselines`, `deleteBeforeTimestamp`                               |
| `core/model/src/main/kotlin/app/readylytics/health/domain/model/InsightType.kt`                                              | Domain — insight model                              | enum class and RecoveryFlag mapper                                                       |
| `core/scoring/src/main/kotlin/app/readylytics/health/domain/dashboard/dashboard/InsightDeriver.kt`                                       | Domain — insight logic                              | derives active set + ordered visible queue/current insight                               |
| `core/model/src/main/kotlin/app/readylytics/health/domain/repository/ScoringRepository.kt`                                   | Processing — coordinator contract                   | `computeDailySummary(day)`                                                               |
| `core/database/src/main/kotlin/app/readylytics/health/data/repository/ScoringRepositoryImpl.kt`                                 | Processing — scoring orchestrator                   | TRIMP/RAS → baselines → scores; "Calibrating" gate                                       |
| `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/SleepPercentileRhrCalculator.kt`                     | Processing — RHR                                    | **RHR nocturnal-floor percentile** (default 5th, 30-day window)                          |
| `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/TrimpModel.kt`                                             | Processing — TRIMP model enum                       | BANISTER / I_TRIMP / CHENG                                                               |
| `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/RasCalculator.kt`                                          | Processing — TRIMP/RAS                              | **TRIMP (default BANISTER)**; RAS = TRIMP × scaling (cap 75)                             |
| `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/ComputeWorkoutTrimpUseCase.kt`                             | Processing — per-workout TRIMP                      | model from `prefs.trimpModel`                                                            |
| `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/GetWorkoutDisplayMetricsUseCase.kt`                        | Processing — per-workout display metrics            | orchestrates 42-day history fetching and delegates to `ComputeWorkoutLoadMetricsUseCase` |
| `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/BaselineComputer.kt`                                       | Processing — baselines                              | hrMax / RHR / HRV mu·sigma / RAS factor; freeze + calibration                            |
| `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/strategies/SleepScoringStrategy.kt`                        | Processing — sleep score                            | Duration 50% / Architecture 25% / Restoration 25%                                        |
| `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/strategies/LoadScoringStrategy.kt`                         | Processing — load/readiness                         | Strain Ratio (ATL/CTL); readiness composite                                              |
| `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/strategies/RasScoringStrategy.kt`                          | Processing — training load                          | CTL (42-day) / ATL (7-day) EMA                                                           |
| `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/ComputeSleepMetricsUseCase.kt`                             | Processing — sleep metrics assembly                 | sleep + restoration                                                                      |
| `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModel.kt` | UI — dashboard state                                | summary, cards, RAS, recalc progress                                                     |
| `ui/sync/SyncViewModel.kt`                                                 | UI — sync state                                     | `recalcProgress` forward                                                                 |
| `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsViewModel.kt`         | UI — vitals state                                   | HRV / RHR / SpO2 trends + bands                                                          |
| `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepViewModel.kt`                      | UI — sleep state                                    | sleep score, stage timeline, sleep window/duration trend data                            |
| `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModel.kt`             | UI — workouts state                                 | TRIMP / strain / RAS                                                                     |
| `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/SettingsState.kt`             | UI — settings state                                 | `SyncSettingsState` resync progress                                                      |
| `ui/scaffold/MainScaffold.kt`                                                                              | UI — scaffold + banner                              | "Recalculating day X of Y"                                                               |
| `ui/components/InsightCard.kt`                                                                             | UI — component                                      | dismissible M3 health insight card + slim rerun restore state                            |
| `ui/components/M3ScoreGaugeCard.kt`                                                                        | UI — visualization                                  | soft arc gauge metric card                                                               |
| `ui/components/TrendCharts.kt`                                                                             | UI — Vico charts                                    | line trends (Bezier, gradient)                                                           |
| `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/heartrate/HrTimelineChart.kt`         | UI — Canvas chart                                   | intra-day HR + zones                                                                     |
| `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepStagesChart.kt`                      | UI — Canvas chart                                   | sleep stage timeline                                                                     |
| `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/RasWeeklyBar.kt`                 | UI — Canvas chart                                   | 7-day RAS breakdown                                                                      |
| `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendChart.kt`                      | UI — Vico chart                                     | stacked column & line dual-axis sleep window & duration chart                            |

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

---

Keep this document synchronized with the source.
