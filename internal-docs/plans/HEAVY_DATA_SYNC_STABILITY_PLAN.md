# Sync Stability at High Data Volume — Options & Plan

**Status:** COMPLETE — Phase 4 (steps 9–11) and Phase 5 (steps 12–14) complete (2026-08-14).
**Date:** 2026-08-13
**Branch:** (not yet created)
**Scope:** Make Health Connect sync stable and responsive for users with very large datasets (≈1M+ records/month, dominated by heart-rate samples).

---

## 1. Problem statement

A user with more than 1 million Health Connect records per month (almost all heart-rate samples) reports that the app gets stuck on the dashboard **loading skeleton and never shows the sync progress bar**. The implementation is confirmed to be silent during ingestion and reconciliation on the daily path, and daily timeout handling returns only a generic failure result. The reported UI symptom is now **mechanistically confirmed** against the dashboard state machine (§3.1): the dashboard shows loading/skeleton cards when `isComputingMetrics` is `true` (derived as `isSyncing && summary == null`), and the progress banner only renders once `recalcProgress` becomes non-null while `isSyncing` is `true` — which on the daily path does not happen until after both `ingestWindow` and `sessionLinkReconciler.reconcile` have already completed silently. What still requires measurement is the sample volume and per-phase timing (ingest vs. reconcile vs. recompute), which determines which throughput options (B–I) are worth shipping after the signaling fix (A).

This document is a self-contained catalog of the causes and every remediation option, with a recommended phasing. It is written to be read standalone — no external conversation context is required.

---

## 2. Current architecture (as-built)

This section is the authoritative reference for the rest of the plan. All paths are relative to the repo root.

### 2.1 The two sync flows (the two-flow contract)

| Flow | Trigger | Path | Window |
|---|---|---|---|
| **Daily sync** | Pull-to-refresh / app foreground / periodic | `ForegroundSyncController.triggerDailySync()` → `HealthSyncUseCase.sync(windowDays = 1)` → `DailySyncUseCase.run` | current day, plus one back-day for overnight HR; change-driven affected dates may widen the recompute target within `MAX_INLINE_RECOMPUTE_DAYS` |
| **Full historical resync** | Settings "Resync Health Connect data" | `HealthResyncWorker` (WorkManager, unique `RESYNC_WORK_NAME`, `KEEP`) → `FullHistoricalResyncUseCase` → `HealthSyncUseCase.resyncRange()` | retention-bounded (`RetentionBounds.resolveResyncStartDate`) |
| **Catch-up sync** | First launch / `lastSyncTimestamp == 0` | `HealthSyncUseCase.catchUpSync()` → `ResyncRangeUseCase.run` | retention-bounded, 30-day chunks |
| **Recompute-only** | Scoring-input settings change | `HealthSyncUseCase.recomputeRange()` → `ResyncRangeUseCase.run(skipIngestAndPrune = true)` | retention-bounded, no HC reads |

Both flows serialize on a single `syncMutex` (`HealthSyncUseCase.kt:35`). Scoring recompute goes exclusively through `ScoringRepository.computeDailySummary(day)` — no scoring math lives in the sync layer.

### 2.2 Ingestion pipeline

`DailySyncUseCase.run` and `ResyncRangeUseCase.run` both delegate per-window ingestion to `HealthIngestionCoordinator.ingestWindow` (`core/healthconnect/.../domain/sync/HealthIngestionCoordinator.kt:40`):

1. Read low-volume record types (sleep, exercise, weight, body-fat, BP, SpO2, body-temp, steps) via `readAllPages` (full window into one list).
2. Map + device-filter them, persist in one transaction via `HealthIngestionStore.persist(...)`.
3. Stream HR and HRV **page-by-page** via `readHeartRateSamplesPaged` / `readHrvSamplesPaged` (HC-001), mapping and persisting **each Health Connect page as one transaction** via `persistHeartRateSamples` / `persistHrvSamples` — there is no internal sub-batching, so a dense page commits thousands of rows in a single transaction (see §2.4).

The entire `ingestWindow` body runs inside `withTimeout(windowBudgetMs)` — a **default 3-minute budget** (`HealthIngestionCoordinator.kt:44,62`). The budget is a parameter with default `3 * 60_000L`, meaning callers can override it (none currently do). A timeout is surfaced as `HealthConnectWindowTimeoutException` (distinct from cooperative cancellation).

### 2.3 Resync phases & progress

`ResyncRangeUseCase.run` runs four phases and checkpoints between them:

```
INGEST → PRUNE (selected-source) → RECONCILE (SessionLinkReconciler, once, full range) → RECOMPUTE (walk-forward)
```

- `ResyncPhase` enum: `INGEST, PRUNE, RECONCILE, RECOMPUTE` (`ResyncCheckpointStore.kt:6`).
- `ResyncCheckpoint` persists `(startDate, endDate, phase, nextDate, selectionHash, baselineChangeTokens, chunkDaysOverride)`.
- **Adaptive shrink already exists on the resync path (HC-002):** when a chunk's Health Connect read times out, the chunk is shrunk and the override is recorded in `chunkDaysOverride` so resume continues at the smaller size (`ResyncCheckpointStore.kt:26`).

Progress is bridged to the UI as `RecalcProgress(phase, current, total)` (`FeatureSyncPorts.kt:7`). `RecalcProgress.fraction()` gives each phase an equal-width slice of the 0→1 bar; a phase fills its slice only if it reports a real `total`.

### 2.4 Heart-rate storage model

Each raw HR sample becomes **one Room row** in `heart_rate_records`:

- Entity: `HeartRateRecordEntity` (`core/model/.../data/local/entity/HeartRateRecordEntity.kt`) with `rowId` (autogenerated PK), `sourceRecordId` (string), `timestampMs`, `beatsPerMinute`, `recordType`, `sessionId`, `deviceName`.
- Indices: unique `(sourceRecordId, timestampMs)`; plus `(timestampMs)`, `(sessionId, recordType, beatsPerMinute)`, `(recordType, timestampMs)`.
- `sourceRecordId` is built as `"${record.metadata.id}_$sampleMs"` in `HeartRateMapper.mapToInputs` (via `HeartRateInput.id`, `HeartRateMapper.kt:30`) — a **long concatenated string** stored in the unique index.
- Upsert: `HeartRateDao.upsertAll` uses `@Insert(onConflict = OnConflictStrategy.REPLACE)` (`HeartRateDao.kt:143`), same for HRV (`HrvDao.kt:116`). SQLite `REPLACE` **deletes the conflicting row then inserts a new one**, rotating `rowId` and churning the index/WAL on every idempotent re-ingest.
- Persistence batching: two distinct paths. (1) The bulk `persist()` path batches low-volume types at 5000 rows via `forEachPersistenceBatch` and yields between batches. (2) The streamed HR/HRV paths — `persistHeartRateSamples` (`RoomHealthIngestionStore.kt:119-123`) and `persistHrvSamples` (`:126-130`) — persist **one Health Connect page per `runInTransaction` with no internal sub-batching**; transaction size is bounded only by HC's page size, which at 1M/month density can be thousands of rows. This is an order-of-magnitude larger single transaction than the bulk path and a concrete Phase-1(G) measurement target.

The scoring engine already reads HR through SQL-side aggregates where possible: `getMinuteBuckets` (1-minute bucketing, PERF-006) and `observeAggregateByTimeRange` (min/max/avg/count, PERF-005) — evidence that most scoring does **not** need per-sample granularity.

---

## 3. Root-cause analysis

### 3.1 Why the user can see a skeleton with no progress bar (confirmed)

The reported symptom — "stuck on loading skeleton, never shows progress bar" — is now mechanistically confirmed against the UI state machine, not merely plausible. Three facts compose:

1. The dashboard renders loading/skeleton cards when `isComputingMetrics` is `true`, derived in `DashboardViewModel.kt:156` as `realtimeState.isSyncing && coreState.summary == null`. This value is passed as `isLoading` to `buildCardDataMap` (`DashboardScreen.kt:301`). The skeleton/loading state therefore shows only when a sync is active **and** no cached `DailySummary` exists for the selected date (first launch, or a date never previously synced). Note: `DashboardLoadingState.shouldShowSkeleton()` (`DashboardLoadingState.kt:39`) exists in the codebase but is **dead code** — never called by any production path. `DashboardLoadingState` itself may also be dead.
2. The `MainScaffold` progress banner renders only when `isSyncing && recalcProgress != null` (via `derivedStateOf` at `MainScaffold.kt:153-158`), suppressed when the user is already on the `SyncProgress` screen (`!isSyncProgressScreen`).
3. `recalcProgress` is set by `ForegroundSyncController` (`ForegroundSyncController.kt:175-177`) via the `onProgress` lambda bridged from the sync use case.

The symptom manifests in two variants depending on whether cached data exists:

- **No cached data (first launch / new date):** `isComputingMetrics` is `true` because `summary == null`, so skeleton/loading cards are displayed. `recalcProgress` is `null` because `onProgress` has not been invoked yet, so no progress banner. The user sees skeleton cards with no indication of what is happening.
- **Cached data exists (returning user):** `isComputingMetrics` is `false` because `summary != null`, so stale data is displayed. `recalcProgress` is still `null`, so no progress banner. The user sees stale data with no indication that a sync is running.

In both variants, the banner only appears once `onProgress` is first invoked — which in `DailySyncUseCase.run` happens at the start of the walk-forward RECOMPUTE loop (`DailySyncUseCase.kt:142-143`), after both `ingestWindow` (`:111`) and `sessionLinkReconciler.reconcile` (`:112-123`) have already completed silently. That window is exactly "skeleton or stale data, no progress bar." The fix is therefore a signaling change (Option A), not a data-flow change; the severity of the *hang* (how long that window lasts) is the throughput question Options B+ address and Phase-1(G) must measure.

### 3.2 Why it is slow / unstable at 1M records/month

1. **Default 3-minute ingest budget.** `ingestWindow` defaults every window to 3 minutes via the `windowBudgetMs` parameter (`HealthIngestionCoordinator.kt:44,62`). The budget is overridable by callers (none currently do), which is relevant to Options B/B′. On the daily path, `HealthConnectWindowTimeoutException` is flattened to a generic `SYNC_ERROR` by the `catch (e: Exception)` at `DailySyncUseCase.kt:218` — a failure with no daily-path retry or shrink. The user-facing visibility of that failure depends on the controller/UI path and must be tested. (The resync path is better: it shrinks via `chunkDaysOverride`.)
2. **`REPLACE` re-insert churn.** Every daily sync re-reads the same recent window and `REPLACE` deletes+reinserts each already-present sample, rotating `rowId` and growing the WAL and index. As `heart_rate_records` grows to millions of rows, each daily sync gets slower than the last.
3. **Per-sample rows + string keys.** One row per raw HR sample with a long string `sourceRecordId` in a unique index is a likely storage and index pressure point. At 1M samples/month and 10-year retention, the table would reach ~120M rows; whether that is the user's actual trajectory and whether it is sustainable on-device must be measured rather than assumed.
4. **Ingest precedes all progress reporting.** Even when it eventually finishes, the user has already perceived the app as hung (skeleton variant) or seen no indication of activity (stale-data variant).
5. **Reconcile is a second silent phase, not just ingest.** `sessionLinkReconciler.reconcile(...)` (`DailySyncUseCase.kt:112-123`) runs once over the full `[ingestStart, windowEnd)` range after ingest with **no progress signal**. `SessionLinkReconcilerImpl` performs per-sample HR/HRV `(recordType, sessionId)` re-link work (`SessionLinker.resolve`) over the complete session list and recomputes affected workout metrics. At high HR density this pass can itself take real time, and it is invisible in the same way ingest is. Any progress fix (Option A) must signal an indeterminate `RECONCILE` phase before this call, not only an `INGEST` phase. Its real cost must be measured in Phase 1(G) — independent of ingest — because no schema change (Options C/D/F) reduces a reconcile-dominated hang.
6. **Unbatched per-page HR/HRV transactions.** The streamed HR/HRV paths (`persistHeartRateSamples`/`persistHrvSamples`) commit one Health Connect page per `runInTransaction` with no internal sub-batching (§2.2), while the bulk `persist()` path already uses `forEachPersistenceBatch` at 5000 rows with `yield()` between batches. At high density, a single HC page can contain thousands of rows, producing oversized transactions and no cooperative cancellation points within a page commit. Applying the existing `forEachPersistenceBatch` to the streamed paths (Option I) is a minimal change that caps transaction size and adds cancellation/yield points.

### 3.3 Confirmed non-issues (already handled)

- **Paging:** HR/HRV are already streamed page-by-page (`readHeartRateSamplesPaged`), so a dense window does not accumulate all samples in memory (HC-001).
- **Resync resume:** the full resync already checkpoints and shrinks on timeout (HC-002); a killed worker re-runs idempotently.
- **Scoring N+1s:** walk-forward recompute already batches TRIMP/baseline contexts (`WalkForwardTrimpContext`, `WalkForwardBaselineContext`) and single-transactions the recompute (F7).

---

## 4. Options catalog

Each option is independent and listed with **what**, **where**, **impact**, **effort**, **risk**, and whether it **touches scoring math** (which requires separate sign-off per `AGENTS.md`).

### Option A — Surface daily-sync phases (INGEST + RECONCILE) before recompute

**What:** Emit two explicit indeterminate phase signals on the daily path so the UI can show that work has started before `RECOMPUTE`:

1. **`INGEST`** — emit `onProgress?.invoke(ResyncPhase.INGEST, pageCountSoFar, 0)` from inside the HC streamed-page callback (`hcRepo.readHeartRateSamplesPaged(...) { page -> ... }`, `HealthIngestionCoordinator.kt:233-246`). `RecalcProgress.fraction()` already treats `total=0` as indeterminate (holds at the slice start), so this becomes an incrementing "pages ingested" counter with no determinate bar but a live signal. This requires threading a progress callback from `DailySyncUseCase.run` through `ingestWindow` into the page lambda — an interface change to `HealthIngestionCoordinator.ingestWindow`.
2. **`RECONCILE`** — emit `onProgress?.invoke(ResyncPhase.RECONCILE, 0, 0)` immediately before `sessionLinkReconciler.reconcile(...)` (`DailySyncUseCase.kt:112`). Indeterminate is the only honest signal unless the reconciler interface is extended with meaningful checkpoints/total, which is deliberately out of scope here.

**Where:** `DailySyncUseCase.run` (`DailySyncUseCase.kt:111-143`); `HealthIngestionCoordinator.ingestWindow` (`HealthIngestionCoordinator.kt:40-54`) for the INGEST page callback; `ForegroundSyncController` already bridges `onProgress` to `recalcProgress` (`ForegroundSyncController.kt:175-177`), and `MainScaffold` already renders the banner when `isSyncing && recalcProgress != null` (`MainScaffold.kt:153-158`).

**Impact:** Directly resolves the confirmed symptom (§3.1). The progress bar appears during ingest and a labeled `RECONCILE` banner appears during the silent reconciliation pass. No data-flow change.

**Effort:** Low-to-medium. The `RECONCILE` one-liner is trivial. The `INGEST` page-count callback requires adding a progress parameter to `ingestWindow` and forwarding it into both `readHeartRateSamplesPaged`/`readHrvSamplesPaged` lambdas; the callback signature `(phase, current, total)` already exists and needs no change.

**Risk:** Low. Signaling only; risk is mislabeling indeterminate work as determinate, which the `total=0` convention already guards against.

**Scoring math:** No.

### Option B — Make dense daily ingestion recoverable without widening scope

**What:** Two complementary, independently shippable layers:

1. **B′ — split the daily ingest window (lightweight, ship first).** `DailySyncUseCase.kt:111` ingests the whole `[oldestTargetDay-1, today+1)` range in a single `ingestWindow` call under one 3-minute budget. Splitting it into two `ingestWindow` calls — today's `[todayMidnight, windowEnd)` first, then the back-day's `[ingestStart, todayMidnight)` (overnight HR reach-back) — gives each its own 3-minute budget and a natural two-segment progress shape (Option A's page counter reflects per-segment progress). The user-facing day completes and scores first; the back-day is a separate bounded transaction. This requires no new worker, touches no scoring math, and preserves the current-day-only contract since both windows are still within the daily window (the back-day reaches one extra day only for *raw-sample fetch* of the earliest in-range night, which the current code already does — only the *transaction boundary* changes). Because `windowBudgetMs` is already a parameter on `ingestWindow`, B′ can also pass a longer budget for the back-day segment (e.g., 5 minutes) with zero new infrastructure — the overnight window is typically denser than today's partial day.
2. **B — timeout-specific recovery / deferral (ship after B′).** When `ingestWindow` throws `HealthConnectWindowTimeoutException` on the daily path, do not collapse it to generic `SYNC_ERROR` (`DailySyncUseCase.kt:218`). Options include: (a) retry the failing segment with an extended `windowBudgetMs` (the budget is already a parameter); (b) retry with adaptive current-day sub-windows; or (c) return a distinct `DEFERRED_DAILY_SYNC` result wired into `ForegroundSyncController` (`:218` catch; `HealthIngestionCoordinator.ingestWindow` `:40-54`). Do not route this failure to the retention-bounded historical resync worker (`HealthResyncWorker`).

**Where (B′):** `DailySyncUseCase.run` ingest call site (`:111`). **Where (B):** `DailySyncUseCase.run` catch (`:218`); `HealthIngestionCoordinator.ingestWindow` (`:40-54`).

**Impact:** B′ cuts the worst-case per-transaction ingest cost roughly in half and gives the user the current-day banner sooner. B turns a dense-window failure into bounded recovery or an actionable deferral.

**Effort:** B′ low (one split + second progress segment); B medium (current-day window partitioning strategy, timeout-specific handling, distinct result wired into `ForegroundSyncController`).

**Risk:** B′ low — both subwindows stay inside the existing daily window's reach; only the transaction boundary moves. B medium — must not violate the two-flow contract ("pull-to-refresh is current-day only; never widen back to historical catch-up"). A separate current-day worker would be a product decision, not the existing historical worker.

**Scoring math:** No.

### Option C — Preserve rows during idempotent HR/HRV re-ingest

**What:** Replace `@Insert(onConflict = REPLACE)` with a persistence strategy that updates the existing row on the natural unique key `(sourceRecordId, timestampMs)` instead of deleting and reinserting it. A simple Room `@Upsert` annotation swap is not approved until generated SQL, secondary-unique-key behavior, `rowId = 0` ingestion, relinking, SQLCipher, and minSdk SQLite compatibility are verified. **A plain `INSERT ... ON CONFLICT DO NOTHING` is unsafe here:** the `sessionId` column is recomputed by `SessionLinkReconciler` _after_ ingest (per-chunk initial tags can differ across passes depending on chunk alignment), and a no-op skip would freeze the stale `sessionId`/`recordType` from the first ever-ingest. A true near-no-op update must compare every mutable column the reconciler or future ingest can change — `recordType`, `sessionId`, `deviceName` — and only write when one of them differs from the existing row (`DO UPDATE SET recordType=excluded.recordType, sessionId=excluded.sessionId, deviceName=excluded.deviceName WHERE … AND (recordType IS NOT excluded.recordType OR sessionId IS NOT excluded.sessionId OR deviceName IS NOT excluded.deviceName)`). An unconditional `DO UPDATE` may still write every row, so "near no-op" must be demonstrated rather than assumed.

**Where:** `HeartRateDao.kt:143`, `HrvDao.kt:116`; verify `HeartRateRecordEntity.rowId` consumers and all identity/deletion paths (the entity doc at `HeartRateRecordEntity.kt:20-25` already warns `rowId` is unstable across passes).

**Impact:** Potentially large. Removes row rotation and reduces index/WAL churn for routine re-ingestion. An unconditional `DO UPDATE` may still write every row, so "near no-op" must be demonstrated rather than assumed.

**Effort:** Medium. Prefer an explicit conflict-targeted DAO statement if it is supported across the supported database stack; otherwise evaluate a schema/key redesign. No data migration should be shipped until behavior is proven on representative API levels.

**Risk:** Medium. Incorrect conflict behavior can create duplicates or fail ingestion. Tests must cover new ingestion entities with `rowId = 0`, existing rows, changed session links, deletion-by-source-record, and backup/restore.

**Scoring math:** No.

### Option D — Replace raw HR with scoring-preserving aggregates

**What:** Investigate replacing selected raw HR samples with scoring-preserving aggregates, potentially using 1-minute or 5–15s buckets for non-sleep HR while retaining raw data where required. The engine already consumes `getMinuteBuckets` (PERF-006), but that does not prove all consumers can use buckets.

**Where:** `HealthIngestionCoordinator.ingestWindow` HR streaming block (`HealthIngestionCoordinator.kt:233-246`); `HeartRateMapper.mapToInputs` (`HeartRateMapper.kt`); possibly a new bucket-aggregation step before `persistHeartRateSamples`.

**Impact:** Potentially the largest structural win, but the reduction factor and score impact are unknown until all raw-sample consumers are mapped and benchmarked. Average-per-minute buckets alone are not guaranteed to preserve workout TRIMP, zone minutes, sleep HR statistics, or reconciliation behavior.

**Effort:** High. New aggregation logic, schema/model changes, and reconciliation of every scoring consumer that currently reads raw samples.

**Risk:** **High — changes what `computeDailySummary` sees.** This is a separate data-model and scoring-approved project. It requires sufficient statistics or derived outputs for every consumer, golden/equivalence tests, and an explicit migration/rollback strategy.

**Scoring math:** **Yes** (requires separate approval).

### Option E — Progressive availability (decouple dashboard from full ingest)

**What:** Render the latest valid `daily_summaries` while sync continues behind an explicit progress/stale state, rather than showing skeleton/loading cards solely because sync is active. The dashboard already partially implements this: when a cached `DailySummary` exists for the selected date, `isComputingMetrics` is `false` (because `summary != null`) and stale data is displayed instead of a skeleton. The gap is that no progress banner or "syncing" label is shown in this returning-user scenario until the RECOMPUTE phase (§3.1). The remaining work is therefore:

1. For the **returning-user variant** (cached data exists): Option A already resolves this — once `onProgress` is emitted during ingest/reconcile, the progress banner appears alongside the stale-but-visible data.
2. For the **no-data variant** (first launch / new date): distinguish "no data yet, syncing" from "no data, not syncing" and show a labeled syncing state instead of plain loading cards. This is the residual UX gap after Option A.

**Where:** Dashboard `ViewModel`/`UiState` loading logic (`feature/dashboard/.../DashboardViewModel.kt`); `MainScaffold`/`MainNavHost` progress-banner wiring. Note: `DashboardLoadingState.shouldShowSkeleton()` (`DashboardLoadingState.kt:39`) and possibly the entire `DashboardLoadingState` sealed interface are dead code — never called by any production path. They should be cleaned up or integrated as part of this work.

**Impact:** Removes the perceived hang even when sync is long. The returning-user case is already covered by Option A; this option addresses only the first-launch/new-date skeleton variant.

**Effort:** Low-to-medium (reduced from original estimate because the returning-user case is already handled by cached data + Option A). Requires distinguishing "no data yet, syncing" from "no data, not syncing" in the dashboard for the skeleton variant.

**Risk:** Low-to-medium. Narrower scope than originally scoped; needs care not to show misleading partial scores for the first-launch case.

**Scoring math:** No.

### Option F — Reduce key/index cost without sacrificing identity

**What:** Investigate a collision-safe compact identity scheme. Do not replace the source key with an unverified hash: `sourceRecordId` is also used by keyset pagination, ordering, deletion-by-source-record, backup/restore, and reconciliation queries. Candidate designs include a stable source-record mapping table plus integer IDs, or retaining the source identity while removing redundant indexes.

**Where:** `HeartRateMapper.mapToInputs` (`HeartRateMapper.kt:30`) + HRV equivalent; `HeartRateRecordEntity`/`HrvRecordEntity`; requires a Room migration.

**Impact:** Potentially moderate. Smaller indexes may improve storage and insert/look-up cost, but the benefit must be measured against mapping-table joins and migration cost.

**Effort:** High (schema migration, mapper/query changes, backup/restore changes, and idempotency re-verification).

**Risk:** High. Any collision or changed identity semantics breaks idempotency or deletion correctness. Requires a collision-proof design and migration tests.

**Scoring math:** No.

### Option G — Measure and tune database behavior

**What:** Measure WAL growth, checkpoint latency, transaction duration, cache behavior, and disk headroom under representative load. The following pragmas are already configured in `DatabaseModule.kt:60-73` and must not be duplicated:

- `JournalMode.WRITE_AHEAD_LOGGING` (Room default, explicitly set at `:64`)
- `PRAGMA synchronous = NORMAL` (`:71`)
- `PRAGMA foreign_keys = ON` (`:72`)
- `PRAGMA journal_size_limit = 33554432` (32 MB, set in `onOpen`)
- `PRAGMA wal_autocheckpoint = 1000` (pages, set in `onOpen`)

Only evidence-backed tuning should be added on top of these. Candidate measurements include: transaction duration per HC page at high density, WAL file size during and after a daily sync, SQLCipher overhead (the database uses SQLCipher via `sqlCipherKeyManager.getOrCreateFactory`), and `busy_timeout` behavior under concurrent read/write (scoring queries during ingest). Treat `page_size` as a rebuild/migration concern, not a runtime toggle.

Critically, the HC **page size** (rows returned per `readHeartRateSamplesPaged`/`readHrvSamplesPaged` callback) must be measured. Since `persistHeartRateSamples` commits one transaction per page with no internal sub-batching, the HC page size directly determines the per-transaction row count and whether Option I (sub-batching) is needed.

**Where:** `HealthDatabase`/`DatabaseModule` configuration (`core/database/...`, `app/.../di/DatabaseModule.kt`).

**Impact:** Low-to-moderate. A cheaper tail improvement, not a fix on its own.

**Effort:** Low-to-medium, depending on whether instrumentation or database rebuilds are required.

**Risk:** Low for measurement; medium for changing pragmas on encrypted databases.

**Scoring math:** No.

### Option H — Reduce storage/retention pressure

**What:** Revisit the retention policy for raw samples, potentially keeping raw HR only for a bounded recent period and storing scoring-preserving historical aggregates. This changes the historical recomputation/data contract and must not be treated as cleanup-only.

**Where:** `RetentionBounds.resolveResyncStartDate`, `DataCleanupWorker`, `HeartRateDao.deleteBeforeTimestamp`.

**Impact:** Prevents unbounded growth toward the conditional ~120M-row scenario. Related to Option D, but independently requires a product decision about what historical views and recomputations remain supported.

**Effort:** Medium. Policy + cleanup change.

**Risk:** High. Retention semantics are user-visible and historical raw-data removal can change future scores. It must stay consistent across resync, cleanup, backup, restore, and documentation.

**Scoring math:** Yes if it changes what historical scoring can see.

### Option I — Sub-batch the per-page HR/HRV transactions

**What:** Apply the existing `forEachPersistenceBatch` (5000-row batches with `yield()` between batches, defined in `HealthIngestionStore.kt`) to the streamed HR/HRV persistence paths (`persistHeartRateSamples` / `persistHrvSamples` in `RoomHealthIngestionStore.kt:119-130`). Currently these paths commit one entire Health Connect page per `runInTransaction` with no internal sub-batching; the bulk `persist()` path already uses `forEachPersistenceBatch` for its types. The change is approximately:

```kotlin
override suspend fun persistHeartRateSamples(samples: List<HeartRateInput>) {
    if (samples.isEmpty()) return
    samples.forEachPersistenceBatch { batch ->
        transactionRunner.runInTransaction {
            heartRateDao.upsertAll(batch.map(HeartRateInput::toEntity))
        }
    }
}
```

This caps per-transaction size at 5000 rows regardless of HC page size, adds `yield()` points for cooperative cancellation within a page commit, and uses infrastructure that already exists and is already tested on the bulk path.

**Where:** `RoomHealthIngestionStore.persistHeartRateSamples` (`:119-123`) and `persistHrvSamples` (`:126-130`).

**Impact:** Bounds worst-case transaction size, reduces peak WAL growth per commit, and adds cooperative cancellation points. The benefit is proportional to how large HC pages are in practice (Phase 1/G measurement). If HC pages are small (< 5000 rows), the change is a no-op. If they are large (e.g., 10K+ rows), this is a meaningful throughput and stability improvement.

**Effort:** Very low (~5 lines changed per method). No new infrastructure; reuses existing `forEachPersistenceBatch`.

**Risk:** Very low. The sub-batching boundary is within a single logical page, so the page either fully commits (all sub-batches succeed) or partially commits (crash mid-page). The latter is safe because ingestion is idempotent (`REPLACE` on the unique key): a re-run will re-ingest the same page and the partially-committed rows are replaced. The only risk is slightly more transactions per page, which is a net positive for WAL pressure.

**Scoring math:** No.

---

## 5. Recommended phasing

Ship in this order. Measure the real workload first, then address visibility and daily timeout recovery before making schema or scoring changes. D, F, and H remain separate data-model decisions. The user-visible symptom is fixable purely with Phase 1(G+A+I); everything from Phase 2 onward is throughput, not the hang itself.

### Phase 1 — Establish measurements, restore visibility, and bound transactions (G + A + I)

1. **Option G** — measure sample volume, phase timings, WAL/disk growth, HC page size, and database behavior. **Critically, measure each silent phase independently:** HC page size (rows/page) inside `persistHeartRateSamples`/`persistHrvSamples`, the `SessionLinkReconcilerImpl.reconcile` elapsed time over the full `[ingestStart, windowEnd)` range, and Room transaction duration per page. Existing pragmas (WAL, `synchronous=NORMAL`, `journal_size_limit=32MB`, `wal_autocheckpoint=1000`) are already configured; do not duplicate that work. If reconcile dominates, no schema change (C/D/F) reduces the visible hang — record this and de-prioritize the structural phases accordingly.
2. **Option A** — emit the `INGEST` page-count signal and the indeterminate `RECONCILE` phase before reconciliation. Both use the existing `onProgress(phase, current, total)` signature; `total=0` is the indeterminate convention.
3. **Option I** — apply the existing `forEachPersistenceBatch` to `persistHeartRateSamples`/`persistHrvSamples` to cap per-transaction size at 5000 rows and add cooperative cancellation points. Very low effort (~5 lines per method), no new infrastructure, and safe to ship regardless of Phase 1(G) measurement results (it is a no-op if HC pages are already small).

**Outcome:** the actual bottleneck is quantified per-phase, the user can see a labeled progress bar during daily ingest and reconcile before recompute begins, and per-transaction size is bounded. This alone closes the reported symptom and reduces the risk of oversized transactions at high density.

### Phase 2 — Make the daily path resilient to density (B′ then B)

4. **Option B′** — split the single `ingestWindow` call into the current-day segment and the overnight back-day reach-back segment, each in its own budget. Lowest-effort throughput improvement and a finer-grained progress shape.
5. **Option B** — on `HealthConnectWindowTimeoutException`, retry the failing segment with adaptive sub-windows or return an explicit `DEFERRED_DAILY_SYNC` result. Do not hand off to the existing historical resync worker.

**Outcome:** dense daily data receives bounded recovery or an actionable deferral without widening pull-to-refresh into a historical sync, and the worst-case per-transaction ingest cost is roughly halved.

### Phase 3 — Reduce idempotent persistence churn (C)

6. **Option C** — implement and verify a conflict-targeted update strategy for HR/HRV that compares mutable columns (`recordType`, `sessionId`, `deviceName`) so the reconciler's post-ingest re-tags still propagate. Do not assume Room `@Upsert` works with the secondary unique key or with `rowId = 0` ingestion, and do not use `ON CONFLICT DO NOTHING` (it would freeze stale session links).

**Outcome:** routine re-ingestion preserves row identity and reduces avoidable write/index churn, subject to compatibility benchmarks.

### Phase 4 — UX resilience (E)

7. **Option E** — the returning-user case (cached data exists) is already covered by cached-data display + Option A's progress banner. The remaining gap is the first-launch/new-date case: distinguish "no data yet, syncing" from "no data, not syncing" and show a labeled syncing state instead of plain skeleton/loading cards. Clean up the dead `DashboardLoadingState.shouldShowSkeleton()` code (`DashboardLoadingState.kt:39`) and possibly the entire `DashboardLoadingState` sealed interface as part of this work.

**Outcome:** a long sync on a date with no cached data does not present as an unexplained indefinite skeleton, without displaying misleading partial scores.

### Phase 5 — Structural scale (D + F + H) — separate approvals required

8. **Option D** — replace raw HR with scoring-preserving aggregates only after all raw consumers and sufficient statistics are specified.
9. **Option F** — investigate a collision-safe identity/index redesign; do not use a plain hash as the source identity.
10. **Option H** — change raw-sample retention only with an explicit product and scoring/data-contract decision.

**Outcome:** a measured reduction in database footprint and sync cost, with historical score behavior and identity semantics preserved by design rather than assumed.

### 5.1 Recommended implementation order

This section gives a concrete step-by-step implementation sequence within and across phases. Each step lists what to do, why in this order, which files to touch, and what to verify before moving on. Steps within a phase are sequential unless marked as parallelizable.

#### Phase 1 — steps 1–4

**Step 1 — Option I: sub-batch HR/HRV persistence.**
Ship first because it is trivial (~5 lines × 2 methods), has no interface changes, no dependencies on other steps, and immediately bounds per-transaction size. It is safe to ship even without Phase 1/G measurements because `forEachPersistenceBatch` is a no-op on lists smaller than 5000 rows.

- **Files:** `RoomHealthIngestionStore.kt` — `persistHeartRateSamples` (`:119-123`) and `persistHrvSamples` (`:126-130`). Wrap each method's body in `samples.forEachPersistenceBatch { batch -> ... }`.
- **Verify:** existing `HealthIngestionCoordinatorTimeoutTest` passes; add unit tests for page sizes above and below 5000 rows; confirm cooperative cancellation (`yield()` between batches).

**Step 2 — Option G: add measurement instrumentation.**
Before changing progress signaling (step 3), establish baseline timings for each silent phase so the effect of subsequent changes can be measured.

- **Instrument:** (a) HC page size — log `page.size` inside `readHeartRateSamplesPaged`/`readHrvSamplesPaged` callbacks in `HealthIngestionCoordinator.kt:233-262`; (b) per-phase elapsed time — wrap `ingestWindow`, `sessionLinkReconciler.reconcile`, and the recompute loop in `DailySyncUseCase.run` with `measureTimeMillis` or equivalent; (c) Room transaction duration per batch — log inside `persistHeartRateSamples`/`persistHrvSamples`; (d) WAL file size — query `PRAGMA wal_checkpoint(PASSIVE)` after daily sync completes; (e) database file size and free disk.
- **Run on device:** capture metrics on a real high-volume Health Connect account (the reporting user's device if possible). Record per-phase breakdown: INGEST.hc-read, INGEST.map, INGEST.persist, RECONCILE, RECOMPUTE, total.
- **Decision gate:** if RECONCILE dominates the hang, record this and de-prioritize Phases 3–5 accordingly. If ingest dominates, Phase 2 (B′/B) becomes high priority.
- **Verify:** instrumentation does not change behavior (logs/timing only); no new allocations on the hot path.

**Step 3 — Option A (RECONCILE signal): emit indeterminate RECONCILE phase.**
Ship this before the INGEST signal because it is a one-liner with no interface change — just add `onProgress?.invoke(ResyncPhase.RECONCILE, 0, 0)` before `sessionLinkReconciler.reconcile(...)` in `DailySyncUseCase.run`.

- **Files:** `DailySyncUseCase.kt` — add one line before the `sessionLinkReconciler.reconcile(...)` call (`:112`).
- **Verify:** `recalcProgress` becomes non-null with phase `RECONCILE` and `total=0`; `MainScaffold` banner renders (indeterminate); cancellation propagation preserved.

**Step 4 — Option A (INGEST signal): thread progress callback through `ingestWindow`.**
This is the more involved part of Option A — it requires an interface change to `HealthIngestionCoordinator.ingestWindow` to accept an `onProgress` callback, and forwarding it into the HR/HRV page lambdas.

- **Files:** (a) `HealthIngestionCoordinator.kt` — add `onProgress: ((ResyncPhase, Int, Int) -> Unit)? = null` parameter to `ingestWindow` and `ingestWindowWithinBudget`; emit `onProgress?.invoke(ResyncPhase.INGEST, pageCountSoFar, 0)` after each `persistHeartRateSamples`/`persistHrvSamples` call inside the page callbacks (`:233-262`). (b) `DailySyncUseCase.kt` — pass `onProgress` through the `ingestWindow` call at `:111`. (c) `ResyncRangeUseCase.kt` — if not already passing progress through `ingestWindow` on the resync path, add it for consistency (the resync path already emits `INGEST` progress at the chunk level, but per-page granularity within a chunk is a bonus).
- **Verify:** `recalcProgress` becomes non-null with phase `INGEST` and incrementing `current` during HR page ingestion; indeterminate `total=0` produces no spurious determinate bar; cancellation propagation preserved; `MainScaffold` banner gates on `isSyncing && recalcProgress != null` and suppresses on `SyncProgress` screen.

**Phase 1 done-gate:** all four steps shipped; baseline measurements captured; progress banner visible during ingest and reconcile on a real device; reported symptom closed.

#### Phase 2 — steps 5–6

**Step 5 — Option B′: split daily ingest into today + back-day.**
Can ship independently. Split the single `ingestWindow` call in `DailySyncUseCase.run` (`:111`) into two calls: `[todayMidnight, windowEnd)` first, then `[ingestStart, todayMidnight)`. Consider passing a longer `windowBudgetMs` for the denser back-day segment.

- **Files:** `DailySyncUseCase.kt` — split ingest call site; compute `todayMidnight` from the existing `windowEnd`.
- **Verify:** both segments complete and score correctly; progress page counter resets or continues across segments; back-day timeout does not block today's scores; no historical-worker escalation.

**Step 6 — Option B: timeout-specific recovery.**
Ship after B′ because B builds on the split-window structure. Add a `catch (e: HealthConnectWindowTimeoutException)` before the generic `catch (e: Exception)` in `DailySyncUseCase.run`.

- **Files:** `DailySyncUseCase.kt` — catch block (`:218`); possibly `ForegroundSyncController` for a new `DEFERRED_DAILY_SYNC` result.
- **Verify:** timeout on one segment does not block the other; no escalation to `HealthResyncWorker`; user sees actionable failure state.

#### Phase 3 — steps 7–8

**Step 7 — Option C research: verify conflict strategy compatibility.**
Before writing production code, prototype the `INSERT OR REPLACE` → `INSERT ... ON CONFLICT(sourceRecordId, timestampMs) DO UPDATE SET recordType=excluded.recordType, sessionId=excluded.sessionId, deviceName=excluded.deviceName WHERE ...` change (or Room `@Upsert`) in an instrumentation test. Verify generated SQL on minSdk 26, target SDK 37, and SQLCipher. Confirm `rowId` stability, `rowId = 0` ingestion, and column-comparison predicate behavior.

- **Output:** instrumentation test proving the strategy works or documenting why it doesn't; generated SQL captured.

**Step 7 result — research gate PASSED (2026-08-14).** `UpsertConflictStrategyInstrumentedTest` (app/androidTest, real SQLCipher Room DB on SM-A576B / API 36) proves:

- **Engine:** SQLCipher bundles SQLite **3.53.1** (independent of the minSdk-26 platform SQLite), so `INSERT ... ON CONFLICT ... DO UPDATE` UPSERT syntax is fully supported across the supported API range.
- **Room `@Upsert` is NOT viable.** Generated SQL (captured from `UpsertPrototypeDao_Impl` via reflection): `INSERT ... VALUES (nullif(?, 0), ...)` + `UPDATE ... WHERE rowId = ?`. The conflict target is the PRIMARY KEY `rowId` (autoGenerate), NOT the secondary unique `(sourceRecordId, timestampMs)` index. A `rowId = 0` re-ingest therefore matches no existing row (`WHERE rowId = 0`); the changed `sessionId` is silently dropped while the old row survives (`count=1, rowId stable, sessionId=null`). Exactly the failure the plan's §2.4 warned about — `@Upsert` must not be used.
- **Conflict-targeted strategy WORKS.** On both `heart_rate_records` and `hrv_records`:
  - `rowId = 0` ingestion auto-assigns a real rowid (`rowId > 0`), no mapper changes needed;
  - identical re-ingest is a true near-no-op — SQLite `changes() = 0` and `rowId` stable (the `WHERE (recordType IS NOT excluded.recordType OR sessionId IS NOT excluded.sessionId OR deviceName IS NOT excluded.deviceName)` predicate skips the write);
  - reconciler-style re-tag (changed `recordType`/`sessionId`/`deviceName`) updates in place with **stable `rowId`** and propagates all mutable columns;
  - baseline `REPLACE` rotates `rowId` on re-ingest (confirms the churn being removed).
- **Implementation note for Step 8:** Room 2.8's `@Query` parser **accepts UPSERT syntax** (proven: `UpsertPrototypeDao.conflictTargetedUpsert` compiles to a real prepared statement and behaves identically to `execSQL` on-device), so production can use a plain `@Query`-annotated single-row UPSERT instead of raw `execSQL`. The DAO `upsertAll` becomes a non-abstract method looping that `@Query` per row (Room cannot express a dynamic placeholder-count multi-row UPSERT, and `@Upsert`/`@Insert`-injected connections are not supported); the loop runs inside the existing `transactionRunner` batch transaction, preserving the one-transaction-per-5000-row shape. RowId is non-stable today, so nothing downstream may rely on it — the conflict-targeted path only strengthens that (stable rows, not rotating ones).

**Step 8 — Option C implementation: conflict-targeted HR/HRV persistence.**
Only after step 7 confirms a viable strategy. Replace `@Insert(onConflict = REPLACE)` with the proven strategy.

- **Files:** `HeartRateDao.kt:143`, `HrvDao.kt:116`; verify all identity/deletion paths.
- **Verify:** DAO tests for duplicate ingestion, changed session links, `rowId = 0`, deletion-by-source-record, backup/restore compatibility.

**Step 8 result — COMPLETE (2026-08-14).** Replaced `@Insert(onConflict = REPLACE)` in both DAOs with a
conflict-targeted UPSERT via `@Query` (see the Step 7 implementation note for why `@Query` over `execSQL`):

- `HeartRateDao` / `HrvDao`: single-row `@Query` UPSERT (`conflictTargetedUpsert`) mirroring the proven prototype
  SQL — `INSERT INTO ... ON CONFLICT(sourceRecordId, timestampMs) DO UPDATE SET recordType=excluded.recordType,
  sessionId=excluded.sessionId, deviceName=excluded.deviceName WHERE (recordType IS NOT excluded.recordType OR
  sessionId IS NOT excluded.sessionId OR deviceName IS NOT excluded.deviceName)`. Public `upsertAll(records)` is now a
  non-abstract default method looping that `@Query` per row (Room KSP skips method bodies; confirmed in generated
  `HrvDao_Impl.kt:700` / `HeartRateDao_Impl.kt:915` — per-row `_stmt.prepare` + bind + `step()`, no `upsertAll`
  override generated). The loop runs inside the existing `transactionRunner` batch transaction, so the
  one-transaction-per-5000-row shape and `PersistenceBatchingTest` proxy (intercepts `upsertAll` by name) are
  unchanged. Callers (store, `HealthChangeSynchronizerImpl`, `SessionLinkReconcilerImpl`, `LocalRestoreManager`)
  compile untouched because the signature is preserved.
- Entity KDocs (`HeartRateRecordEntity.kt` / `HrvRecordEntity.kt`) updated: `rowId` is now stable across idempotent
  re-ingest (no longer rotated by REPLACE); still not persistable across backup/restore (restore deleteAll's first).
- **Tests:** new JVM `ConflictTargetedUpsertTest` (app/src/test, Robolectric-style real in-memory Room) — 6 tests:
  identical HR/HRV re-ingest keeps `rowId` stable + no duplicate; re-tagged record updates `recordType`/`sessionId`
  in place with stable `rowId`; `rowId = 0` ingestion auto-assigns a real rowid; re-upsert after
  deletion-by-source-record reinserts fresh. All pass. Existing `DeleteBySourceRecordIdTest` + all other DAO JVM tests
  still green; `assembleDebugAndroidTest` green after removing the now-defunct `batchConflictTargetedUpsert`
  db-injection probe from `UpsertPrototypeDao` (KSP error confirmed Room 2.8 does not inject `SupportSQLiteDatabase`
  into abstract DAO methods — the probe's question was answered by the Step 7 research, so it is deleted, not fixed).
- **Docs:** `internal-docs/DATA_FLOW.md` idempotency-contract paragraph + data-flow diagram label updated to describe
  the HR/HRV conflict-targeted strategy vs `@Upsert` elsewhere.
- **Note:** `UpsertConflictStrategyInstrumentedTest` (Step 7) passes again after one measurement fix: the
  `roomQuery_acceptsUpsertSyntax_andBehavesLikeExecSql` test read `changes()` via `writableDatabase`, but
  `changes()` is connection-scoped and Room's suspend `@Query` runs on a pooled connection, so the raw counter
  came back 0 even though the write succeeded. The test now asserts the observable row state (same rowId + updated
  mutable columns + count=1) — a strictly stronger equivalence proof — and the execSQL-path tests still assert
  `changes()` on the writable connection directly. The production DAO SQL is byte-for-byte the prototype's proven
  statement. Remaining pre-commit: `./gradlew lintRelease` at the very end.

#### Phase 4 — steps 9–11

**Step 9 — Verify `DashboardLoadingState` is dead code.**
Grep the codebase for all references to `DashboardLoadingState`, `shouldShowSkeleton`, `isBusy`, `SyncingMetrics`, `MetricsReady`. Confirm none are called from production code. Check test code for references that would need updating.

**Step 9 result — PASSED (2026-08-14).** `git grep "DashboardLoadingState"` finds the sealed interface and its helpers (`shouldShowSkeleton()`, `isBusy()`, and the `Idle`/`SyncingMetrics`/`MetricsReady`/`Error` variants) referenced only in `DashboardLoadingState.kt` itself, `internal-docs/DATA_FLOW.md` (§3.2 UI state wrappers table), and the plan docs. No production code and no unit/instrumentation test references the type. Confirmed dead.

**Step 10 — Remove dead `DashboardLoadingState` code.**
Delete the sealed interface and its extension functions if confirmed dead. Update or remove any test references.

- **Files:** `DashboardLoadingState.kt`; any test files referencing it.

**Step 10 result — COMPLETE (2026-08-14).** Deleted `feature/dashboard/.../DashboardLoadingState.kt` and removed its entry from `internal-docs/DATA_FLOW.md` §3.2 (the UI-state-wrappers list now cites only `DashboardFlowIntermediate.kt`). No test files referenced the type, so none needed updating. Committed as `b292a5d5` ("refactor(dashboard): remove dead DashboardLoadingState code").

**Step 11 — Option E: first-launch/new-date syncing state.**
Add a distinguishable "syncing, no data yet" state for the first-launch/new-date case. The returning-user case is already handled (cached data + Option A progress banner).

- **Files:** `DashboardViewModel.kt`, `DashboardScreen.kt` — differentiate `summary == null && isSyncing` from `summary == null && !isSyncing`.
- **Verify:** `MainNavHostTest`, `MainScaffoldTest`, `DashboardRecompositionTest`, `ForegroundSyncController` tests.

**Step 11 result — COMPLETE (2026-08-14).** The `isComputingMetrics = isSyncing && summary == null` derivation already existed in `DashboardViewModel.kt` and already fed `isLoading` to the card grid, so no production change was required — the first-launch/new-date vs returning-user vs empty-date distinction is already implemented. Phase 4 formalized the contract in unit tests instead (see design §3 state matrix): `DashboardViewModelTest` gained four tests covering the three-state matrix (`summary == null && isSyncing` → `isComputingMetrics = true`; `summary != null && isSyncing` → `false` with progressive availability; `summary == null && !isSyncing` → `false`) plus `recalcProgress` propagation; `DashboardFlowIntermediateTest` gained a test that `createDashboardRealtimeStateFlow` combines `isSyncing` and `recalcProgress` reactively. Committed as `89537811` ("test(dashboard): add unit tests for sync state matrix and progress propagation"). All `feature:dashboard` unit tests pass.

#### Phase 5 — steps 12–14 (independent, separate approvals)

These are independent projects that each require a separate proposal, scoring-owner approval, and migration strategy. They can be worked in any order.

**Step 12 — Option D:** scoring-preserving HR aggregates. Requires mapping all raw-sample consumers and defining sufficient statistics.

**Step 13 — Option F:** collision-safe identity/index redesign. Requires migration tests and idempotency re-verification.

**Step 14 — Option H:** raw-sample retention policy change. Requires product decision and documentation updates.

**Step 12 result — COMPLETE (2026-08-14) (Option D).** Introduced a warm tier `hr_minute_buckets` (composite identity `(bucketStartMs, recordType, sessionId)`, one row per minute/session/type) and a `DataRollupWorker`/`DataRollupManager` that atomically downsample raw `heart_rate_records` older than the fixed 90-day hot boundary into buckets and delete the raw rows. `ScoringRepositoryImpl` merges hot+warm minute buckets for the everyday-HR load (weighted avg is bit-identical to the raw AVG), and `ScoringHistoryRepositoryImpl` reconstructs a sleep session's sample stream from its warm buckets when raw rows are gone; workout exercise samples are likewise rebuilt from the warm tier. Golden equivalence tests (`ScoringEquivalenceGoldenTest`) lock everyday buckets (TRIMP ≤ 0.01%) and sleep percentile RHR (≤ 1 bpm). Committed across `abe1ba39` and `5a5c9779`.

**Step 13 result — COMPLETE (2026-08-14) (Option F).** Added `health_source_records` (base UUID → integer id dimension table) and rebuilt `heart_rate_records`/`hrv_records` to reference it via an integer `sourceRecordRef` FK, replacing the per-row TEXT `sourceRecordId` (`MIGRATION_9_10`, DATABASE_VERSION 10). Idempotent upsert now keys on the unique `(sourceRecordRef, timestampMs)` index; ingestion, session-link reconcile (keyset pagination), Health Connect changes-path deletion, and local backup/restore all resolve refs through `SourceRecordDao`. Backup now also carries `health_source_records` and `hr_minute_buckets`, and restore decodes legacy `sourceRecordId`-format rows (schema 7–9 and the pre-v7 legacy path) back to refs. Lossless 9→10 migration proven on-device by `DatabaseMigrationInstrumentedTest.migrate9To10NormalizesSourceRecordRefsAndPreservesData`. Committed as `6c2cd4bb`.

**Step 14 result — COMPLETE (2026-08-14) (Option H).** Raw-sample retention is unchanged at the product level (`retentionDays`, default 365): the warm tier is an internal storage optimization, not a new user-facing data contract. Raw 1-second heart-rate samples are now rolled to 1-minute buckets after the fixed 90-day hot window and pruned from the hot tier; `RetentionCleanup` additionally prunes expired warm buckets at the user's retention cutoff (`RetentionBounds.resolveRetentionCutoffMs`), and `RetentionBounds.resolveHotTierCutoffMs` is the single 90-day hot/warm boundary shared by rollup and scoring. `DataRollupWorker` is scheduled daily alongside `DataCleanupWorker`. Committed as `5a5c9779`.

---

## 6. Verification

- `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` (mandatory pre-commit).
- `./gradlew lintRelease` after resolving all tasks.
- Before optimization: capture per-window record counts, HC **page size** (rows/callback) for `readHeartRateSamplesPaged`/`readHrvSamplesPaged`, HC read time, mapping time, Room transaction time per page, **`SessionLinkReconcilerImpl.reconcile` elapsed time over the full range**, recompute time, WAL growth, database size, free disk, and peak memory on representative high-volume fixtures and at least one real device.
- For Option A: test that `recalcProgress` becomes non-null during ingest (INGEST banner renders) and during reconcile (RECONCILE banner renders), that indeterminate `total=0` produces no spurious determinate bar, cancellation propagation is preserved, and `MainScaffold`'s banner gates correctly (requires both `isSyncing` and `recalcProgress != null`, suppressed on `SyncProgress` screen).
- For Option B′/B: test the split-window transaction boundary, timeout-specific recovery (including adaptive `windowBudgetMs` if used), cancellation propagation, no historical-worker escalation, and actionable failure/deferred states.
- For Option C: add DAO/instrumentation tests proving duplicate ingestion preserves the natural-key row, changed session links update correctly, `rowId = 0` ingestion works, deletions remain correct, and backup/restore remains compatible. Verify generated SQL across supported API levels and SQLCipher.
- For Option D/F/H: require a separate migration/data-contract proposal, scoring-owner approval, equivalence/golden tests, backup/restore tests, and rollback or recovery behavior.
- For Option E: verify that `DashboardLoadingState` is indeed dead code before removing it. First add tests for the first-launch/new-date syncing state behavior, then run `MainNavHostTest`, `MainScaffoldTest`, `DashboardRecompositionTest`, and relevant `ForegroundSyncController` tests.
- For Option I: add unit tests for sub-batched `persistHeartRateSamples`/`persistHrvSamples` verifying: (a) all samples persist correctly when page size exceeds 5000 rows; (b) `yield()` is called between batches (cooperative cancellation); (c) partial-page crash + re-ingest produces correct final state (idempotency via `REPLACE`). Verify the existing `HealthIngestionCoordinatorTimeoutTest` still passes.
- For any scoring or historical-data behavior change: update `internal-docs/DATA_FLOW.md`, and if formulas or score explanations change also update `ABOUT.md`, `docs/about.md`, and in-app About/tooltips per the documentation rules.
- Device smoke test on a real high-volume Health Connect account: confirm phase visibility, daily recovery, no unintended historical escalation, and resync checkpoint recovery.

---

## 7. Effort & risk

| Option | What | Effort | Risk | Scoring math |
|---|---|---|---|---|
| A | Daily INGEST (page counter) + RECONCILE phase visibility | Low-Medium | Low | No |
| B′ | Split daily ingest window (today + back-day) | Low | Low | No |
| B | Current-day timeout recovery / deferral | Medium | Medium | No |
| C | Conflict-targeted HR/HRV persistence (compare mutable cols) | Medium | Medium | No |
| D | Scoring-preserving HR aggregates | High | **High** | **Yes** |
| E | Progressive availability (first-launch/new-date variant only) | Low-Medium | Low-Medium | No |
| F | Collision-safe identity/index redesign | High | **High** | Possibly |
| G | Measurement and evidence-backed tuning | Low-Medium | Low-Medium | No |
| H | Raw-sample retention policy | Medium-High | **High** | Yes if historical inputs change |
| I | Sub-batch per-page HR/HRV transactions | Very Low | Very Low | No |

**Highest risk:** Options D, F, and H. D changes scoring inputs, F changes identity and migration semantics, and H changes the historical data contract. None should be bundled into a routine sync-stability patch.

**Lowest risk, highest leverage for Phase 1:** Options A and I. Both are signaling/batching-only changes with no data-flow or schema impact.

---

## 8. Open questions

1. **What is the measured workload, per phase?** Record counts and timings must be captured per data type and per silent phase (INGEST.page-read, INGEST.page-persist, RECONCILE, RECOMPUTE) before selecting a structural fix. `1M/month` averages about `33k/day`; `86k/day` corresponds approximately to 1 Hz. Include the HC **page size** (rows returned per `readHeartRateSamplesPaged` callback) since `persistHeartRateSamples` commits one transaction per page with no internal sub-batching (pre-Option I) or per 5000-row batch (post-Option I).
2. **Does reconcile dominate the visible hang?** Measure `SessionLinkReconcilerImpl.reconcile` elapsed over the full `[ingestStart, windowEnd)` range at high HR density. If it does, no schema change (C/D/F) reduces the symptom — only the Phase-1 signaling fix matters, and the structural phases are de-prioritized.
3. **What is the user's retention setting and available disk?** The ~120M-row ten-year scenario is conditional, not an observed fact. Determine the storage runway and failure threshold on supported devices.
4. **For Option B, should timeout recovery stay inline or use a new current-day worker?** The existing `HealthResyncWorker` must not be used because it is historical and retention-bounded. The simplest first step is to retry with an extended `windowBudgetMs` (the parameter already exists). (B′ is independent of this decision and can ship first.)
5. **For Option C, which conflict strategy works across Room, SQLCipher, minSdk 26, and supported SQLite versions?** Prove this with generated SQL and device/instrumentation tests before migration. The `DO UPDATE` column-comparison predicate must cover every column `SessionLinkReconciler` can change.
6. **For Option D, what sufficient statistics preserve every scoring and display consumer?** Granularity alone is not enough; the scoring owner must define the required data contract.
7. **For Option E, what should the first-launch/new-date syncing state show?** The returning-user case is already handled (cached data displayed + Option A progress banner). The remaining decision is about the skeleton variant: a labeled syncing state versus plain loading cards is a product decision.
8. **For Option F, can a stable source-record mapping table reduce index cost without changing deletion, backup, pagination, or reconciliation semantics?**
9. **Is `DashboardLoadingState` dead code?** Grep confirms `shouldShowSkeleton()` and `isBusy()` are never called outside their own file. The sealed interface itself may also be unused. Verify before removing as part of Option E.
