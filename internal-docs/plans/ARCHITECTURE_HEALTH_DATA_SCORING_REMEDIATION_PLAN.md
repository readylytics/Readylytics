# Architecture, Health Connect, Performance & Scoring Engine Remediation Plan

> **Status:** PROPOSED — written 2026-08-31 against `main` @ `1bdff741`.
> **For agentic workers:** Sections 1–8 are the audit. Sections 9–10 are the executable
> roadmap; work packages use checkbox (`- [ ]`) steps. Use
> `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans`
> to execute task-by-task.

**Goal:** Close the correctness, determinism, scalability and boundary defects found in a
full-repository audit of the Health Connect ingestion pipeline, the Room storage/tiering model,
the scoring engine, and the module/DI architecture — without changing any scoring formula or
user-facing product semantics.

**Architecture:** Incremental. Every change is additive or behavior-preserving except where a
finding proves current behavior is wrong; those changes are gated behind a recompute and are
individually revertible. No rewrite is proposed: the repository's layering is sound and three
prior remediation rounds already landed the structural work.

**Tech Stack (verified from `gradle/libs.versions.toml`, `build-logic/`, `app/build.gradle.kts`):**
Kotlin 2.4.10 · AGP 9.3.2 · KSP 2.3.11 · minSdk 26 / targetSdk 37 / compileSdk 37 ·
Health Connect `androidx.health.connect:connect-client` **1.1.0** · Room **2.8.4** ·
`androidx.sqlite` 2.7.0 · SQLCipher (`net.zetetic:sqlcipher-android`) 4.18.0 ·
Hilt 2.60.1 · WorkManager 2.11.2 · DataStore 1.2.1 (+ protobuf 4.36.0) · Compose BOM 2026.08.00 ·
Material3 1.5.0-alpha27 · Vico 3.3.0 · Tink 1.23.0 · detekt 1.23.8 · konsist 0.17.3.
Room schema version **14** (`HealthDatabase.DATABASE_VERSION`).

**Spec / inputs this plan argues from:**
- `AGENTS.md` and `.claude/CLAUDE.md` (the two-flow sync contract, idempotency contract,
  documentation-sync rules).
- `internal-docs/DATA_FLOW.md` (222 KB; the authoritative pipeline map).
- `ABOUT.md`, `docs/about.md` (score methodology, public copy).
- `internal-docs/plans/CORE_SCORING_JVM_MIGRATION.md` (DEFERRED, blocked on AGP 9.4.0 stable).
- Landed prior rounds, referenced not re-litigated: `HEAVY_DATA_SYNC_STABILITY_PLAN.md`
  (phases 1–4 landed; phase 5 = options D/F/H landed via `01c944f7`),
  `PERFORMANCE_OPTIMIZATION_PLAN.md`, `POST_REMEDIATION_FOLLOWUPS.md`,
  `.superpowers/sdd/2026-08-27-hc-db-performance-phase2/`,
  `.superpowers/sdd/2026-08-27-phase-3-architecture-refactor/`,
  the four `docs/superpowers/plans/2026-08-29*/2026-08-31*` residual-fatigue plans.

---

## Global Constraints

Every task's requirements implicitly include this section. Values copied verbatim from
`AGENTS.md` / `.claude/CLAUDE.md`.

- **Room DB is the single source of truth. Health Connect is ingestion-only. UI must NEVER
  access Health Connect directly.**
- **Pull-to-refresh = CURRENT DAY ONLY** via `ForegroundSyncController.triggerDailySync()` →
  `HealthSyncUseCase.sync(windowDays = 1)`. Never widen back to a 60-day catch-up.
- **Settings "Resync Health Connect data" = FULL HISTORICAL RESYNC** via `HealthResyncWorker`
  (`RESYNC_WORK_NAME`, `ExistingWorkPolicy.KEEP`) → `FullHistoricalResyncUseCase` →
  `HealthSyncUseCase.resyncRange()`. Never run inline on a ViewModel.
- **Idempotency is non-negotiable:** ingestion is upsert keyed by stable HC record `id`.
  No blanket `deleteAll()`. Only `clearFrozenBaselines(range)` is mutated up front.
- **Retention window** resolves exclusively through `RetentionBounds`
  (`retentionDaysEnabled ? today − retentionDays : today − ABSOLUTE_MAX_DAYS (3650)`).
  Hot/warm boundary is the fixed `HOT_TIER_WINDOW_DAYS = 90`.
- **Session-link reconcile must stay a single full-range pass** (`SessionLinkReconciler.reconcile`)
  — never scoped per chunk.
- **Concurrency:** daily sync and resync share `HealthSyncUseCase.syncMutex`. Walk-forward loops
  stay cooperative (`ensureActive()` + `yield()`); never swallow `CancellationException`.
- **Scoring math is OFF-LIMITS to data-flow work.** Both flows recompute exclusively through
  `ScoringRepository.computeDailySummary(day)`. No formula is changed by this plan except where a
  finding is classified *confirmed implementation bug* and the change is explicitly listed.
- **Logic isolation:** business/calculation logic is pure Kotlin, zero Android dependencies.
- **Strings:** all user-facing strings live in `app/src/main/res/values/strings.xml`, referenced
  via `stringResource(R.string.…)`.
- **File size:** target ≤ 400 lines, hard limit ≤ 800 lines.
- **Pre-commit (mandatory, every commit):**
  `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest`,
  then `./gradlew lintRelease` once all coding tasks are done.
- **Detekt discipline:** no new issues; boyscout-fix pre-existing issues in touched files.
  A new `@Suppress` or baseline edit requires explicit human approval before merge.
- **Documentation sync is load-bearing:** any change to ingestion, Room schema, scoring
  use-cases/coordinators or scoring formulas MUST update `internal-docs/DATA_FLOW.md` in the same
  change; formula/threshold/copy changes additionally require `ABOUT.md`, `docs/about.md` and the
  in-app `about_*`/`tooltip_*` strings.
- **Indexing:** run `codegraph index` after creating files, `codegraph sync` after structural moves.
- **Never uninstall the production app** (`app.readylytics.health`) without explicit permission.

### Finding-ID namespace

The codebase already carries an *implemented* finding register from earlier rounds
(`HC-001`…`HC-009`, `PERF-001`…`PERF-006`, `SCORE-001`…`SCORE-007`, `DB-001`/`DB-002`,
`ARCH-001`/`ARCH-002`, `DI-002`, `UI-002`) referenced in ~180 source comments. To avoid
collision, **every finding in this plan is prefixed `R2-`** (round two): `R2-HC-001`,
`R2-SCORE-003`, etc. Use the `R2-` prefix in code comments too.

---

## 1. Executive Summary

**Current architectural condition: good, and materially better than the average codebase of this
size.** 16 Gradle modules, ~177 kLOC Kotlin, an acyclic module graph
(`core:model` ← `core:database-schema` ← `core:database` ← `core:healthconnect` ← features),
injected dispatchers, no `GlobalScope`, no `runBlocking` in production code, a documented and
checkpoint-resumable four-phase resync, conflict-targeted UPSERTs, keyset pagination, SQL-side
aggregation, SQLCipher-at-rest, and a 222 KB data-flow document that is *accurate* in almost every
place I cross-checked it. Three prior remediation rounds did real work. `./gradlew detekt
testDebugUnitTest` is **green** on `main` (verified: `BUILD SUCCESSFUL`, 562 tasks, all
up-to-date/cached).

**The defects that remain are concentrated in exactly one place: the hot→warm tier boundary
introduced by `01c944f7`, and the two ingestion paths that were never unified.**

Most important correctness risks:

1. **`R2-DB-001` (High, confirmed).** Tier selection is all-or-nothing per session. A sleep
   session or workout straddling the 90-day rollup boundary keeps only its raw half —
   `ScoringHistoryRepositoryImpl.getSleepHrSamplesForSession` returns `hot` whenever
   `hot.isNotEmpty()`, silently discarding the rolled-up half. This corrupts the RHR percentile
   and nightly average HR for boundary nights.
2. **`R2-CACHE-001` (High, confirmed).** `DataRollupWorker` and `DataCleanupWorker` mutate the raw
   data that `daily_summaries` was derived from, and invalidate nothing. There is no dependency
   edge from a tier transition or a retention deletion to the derived scores that read it.
3. **`R2-ARCH-001` (High, confirmed).** `WorkoutDetailViewModel:233` calls
   `hcRepo.readHeartRateSamples(start, recoveryWindowEnd)` — the UI reads Health Connect
   directly, against the repository's most explicit stated invariant, and merges those
   *device-unfiltered* samples with device-filtered Room rows via `distinctBy { it.timestamp }`.
4. **`R2-HC-001` (High, confirmed).** Full resync is additive-only. Deletion convergence exists
   only through the Changes API; when a token expires the flow escalates to a resync that
   re-ingests but never removes locally-held records the user deleted in Health Connect.

Most important scalability risks (the 1M-record scenario, §7):

- **`R2-PERF-001`** — `WarmTierReconstructor` re-expands each warm bucket into `sampleCount`
  **boxed** `Int`s / `Pair<Long,Int>`s. The warm tier saves disk and gives back nothing in memory
  on the historical-rebuild path that needs it most.
- **`R2-PERF-002`** — `DataRollupManager.rollupExpiredHotTier` runs an unbounded
  `INSERT…SELECT … GROUP BY` plus an unbounded `DELETE` in one transaction, while
  `RetentionCleanup` — the same class of operation, in the same module — correctly batches at
  10,000 rows.
- **`R2-PERF-003`** — `HeartRateDao.upsertAll` executes one statement per row.
- **`R2-HC-002`** — `retryWithBackoff` wraps the *entire* paged HR/HRV read including
  persistence, so a failure on page N restarts the window at page 0, inside a
  `withTimeout(windowBudgetMs)` the retry then usually cannot meet.

Most important Health Connect risks: `R2-HC-001` (deletion convergence), `R2-HC-002`
(no intra-window checkpoint), `R2-HC-004` (steps semantics differ between the two flows),
`R2-HC-005` (`lastSyncTimestamp` set on an incomplete sync).

**Confidence in the scoring engine: high for the formulas, medium for determinism.** The pure
formula layer (`core:scoring`) is well-referenced, constant-driven, tested with golden snapshots
and documentation-drift tests, and I found **no arithmetic error** in the TRIMP models, the
Banister/Cheng/iTRIMP branches, the baseline windowing, or the RAS conversion. What is *not*
sound is reproducibility over time: identical Health Connect data does not reproduce identical
scores once rollup has run (`R2-DB-004`), and day attribution in ~20 UI files uses
`ZoneId.systemDefault()` rather than the stored scoring zone (`R2-SCORE-003`).

**Recommended overall strategy:** six ordered phases. Fix tier correctness and invalidation
first (Phase 1), because every later measurement is meaningless while historical reads are
lossy. Then bound the tier/ingest operations (Phase 2). Then unify the two ingestion paths
behind the existing `HealthIngestionStore` port (Phase 3) — that single change closes four
divergence findings at once. Then targeted invalidation and allocation work (Phase 4), then
UI/maintainability (Phase 5). Estimated 18 commit-sized work packages.

---

## 2. Repository Areas Reviewed

**Guidance & documentation**
`AGENTS.md`, `.claude/CLAUDE.md`, `GEMINI.md`, `README.md`, `ABOUT.md`,
`internal-docs/DATA_FLOW.md`, `internal-docs/INSIGHTS.md`, `internal-docs/INSIGHT_DETAILS.md`,
`internal-docs/plans/CORE_SCORING_JVM_MIGRATION.md`, `docs/{index,about,privacy,backup-and-data,
customization,insights}.md`, deleted-but-recovered `internal-docs/plans/{PERFORMANCE_OPTIMIZATION_PLAN,
POST_REMEDIATION_FOLLOWUPS,HEAVY_DATA_SYNC_STABILITY_PLAN,DETEKT_BASELINE_BURNDOWN}.md`
(via `git show`), `.superpowers/sdd/*/`.

**Build & configuration**
`settings.gradle.kts`, `gradle/libs.versions.toml`, `build-logic/src/main/kotlin/
readylytics.android-library-conventions.gradle.kts`, `app/build.gradle.kts`,
`app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/{file_paths,data_extraction_rules,
full_backup_content,network_security_config}.xml`.

**Health Connect ingestion (`core:healthconnect`, 50 files / 9.4 kLOC)**
`data/healthconnect/HealthConnectRepositoryImpl.kt`, `HealthChangeSynchronizerImpl.kt` (750 lines),
`HealthConnectRecordConverters.kt`, `IntervalTotalsReader.kt`, `StepRecordReader.kt`,
`DeviceLabel.kt`, `HealthConnectExceptionUtils.kt`;
`data/mapper/{Weight,BodyFat,BloodPressure,OxygenSaturation,BodyTemperature}DataMapper.kt`,
`MapperHelpers.kt`;
`domain/sync/{HealthSyncUseCase,DailySyncUseCase,ResyncRangeUseCase,HealthIngestionCoordinator,
ForegroundSyncController,FullHistoricalResyncUseCase,HealthChangeSynchronizer,DeviceSourceFilter,
StepCountFetcher,RetryWithBackoff,HealthConnectRetryPolicy,SyncConstants}.kt`;
`di/HealthConnectModule.kt`.

**Storage (`core:database` 139 files / 22.8 kLOC, `core:database-schema` 35 files / 2.6 kLOC)**
`data/local/{HealthDatabase,DatabaseMigrations,DatabaseUpgradeSql,DataRollupManager,
RetentionCleanup,RoomHealthIngestionStore,RoomTransactionRunner,RoomWalDiagnostics,
SelectedSourcePrunerImpl,SessionLinkReconcilerImpl,WarmTierReconstructor,Converters,
HealthRecordDaos,HealthIngestionInputMappers,HealthIngestionVitalsMappers}.kt`,
`data/local/migration/Migration{9To10,10To11,11To12,12To13,13To14}.kt`;
all 17 entities under `core/database-schema/.../data/local/entity/`;
all DAOs under `core/database-schema/.../data/local/dao/` (HeartRate, Hrv, MinuteBucket,
SleepSession, SleepStage, Workout, WorkoutRoutePoint, DailySummary, SourceRecord, StepRecord,
Weight, BodyFat, BloodPressure, OxygenSaturation, BodyTemperature, InsightDismissal) plus
`core/database/.../dao/AuditEventDao.kt`;
`data/repository/*` (30 files incl. `ScoringRepositoryImpl`, `ScoringDayDataLoader`,
`ScoringHistoryRepositoryImpl`, `ReadinessSummaryCoordinator`, `DailyTrimpComputer`,
`RasTotalsComputer`, `ResidualFatigueComputer`, `CalibrationGate`);
`data/security/{SqlCipherKeyManager,AndroidKeystoreKeyProvider,KeyProvider}.kt`;
`data/migration/DatabaseReadinessGate.kt`; `domain/sync/DailyRecomputeSupport.kt`;
`di/{DatabaseModule,DaoProvidersModule,DaoProvidersSupplementModule,DatabaseRepositoryModule,
ScoringSyncBindingsModule}.kt`.

**Scoring (`core:scoring` 182 files / 20.3 kLOC, `core:model` 266 files / 17.4 kLOC)**
`domain/scoring/{RasCalculator,BaselineComputer,ComputeSleepMetricsUseCase,ComputeWorkoutTrimpUseCase,
ComputeDailyTrimpUseCase,ComputeResidualFatigueUseCase,EverydayHeartRateLoadCalculator,
ComputeHistoricalBaselinesUseCase,BackfillHistoricalBaselinesUseCase,ResolveDailyBaselinesUseCase,
CompositeScoringCalculator,ScoringConfig,ScoringConfigFactory,HistoricalSleepDayAssembler,
TrimpDateBucketer,WorkoutLoadClassifier,BuildLoadSeriesUseCase,GenerateResidualFatigueCurveUseCase}.kt`,
`domain/scoring/sleep/*`, `domain/scoring/components/*`, `domain/scoring/strategies/*`,
`domain/insights/*`, `domain/util/{HeartRateFormulas,MathUtils}.kt`,
`domain/workouts/weekly/*`; `core/model/.../domain/scoring/ScoringConstants.kt`,
`core/model/.../domain/sync/mappers/{HeartRate,Hrv,SleepData,Workout,Steps}Mapper.kt`,
`core/model/.../domain/sync/link/{SessionLinker,SessionLinkSweep}.kt`,
`core/model/.../domain/util/RetentionBounds.kt`, `.../domain/util/AppLog.kt`,
`core/model/.../data/preferences/UserPreferences.kt`,
`core/model/.../domain/preferences/PreferenceAliases.kt`.

**App shell & workers (`:app`, 221 files / 31.4 kLOC)**
`workers/{HealthResyncWorker,PeriodicHealthSyncWorker,DataCleanupWorker,DataRollupWorker,
LocalBackupWorker,DatabaseMigrationWorker,BirthdayCheckWorker,WorkerSchedulerImpl,
SyncNotifications}.kt`; `di/{CoroutineDispatchersModule,DataStoreModule,FeaturePortModule,
RepositoryModule,UtilModule,WorkerModule,AndroidResourceProvider}.kt`;
`MainActivity.kt`, `util/{SecureFileLogSink,LogSlotStore}.kt`,
`crashreport/{DiagnosticLogFileExport,CrashReportFileExport}.kt`,
`data/backup/LocalBackupManager.kt`.

**Features (`feature:*`, 7 modules)** all ViewModels (largest: `DashboardViewModel` 598,
`WorkoutsViewModel` 452, `WorkoutDetailViewModel` 442, `VitalsViewModel` 420, `SleepViewModel` 371),
`feature/dashboard/usecase/DashboardMetricPresentationFactory.kt` (622),
`feature/workouts/WorkoutPerformanceCharts.kt` (815), `feature/sleep/SleepStagesChart.kt` (756).

**Toolchain verification run:** `./gradlew detekt testDebugUnitTest --console=plain` →
`BUILD SUCCESSFUL in 17s`, 562 actionable tasks (4 from cache, 558 up-to-date). No detekt issues,
no failing unit tests on `main`. Instrumented tests were not run (see `POST_REMEDIATION_FOLLOWUPS`
item 3 — the instrumented suite is known not to be reliably runnable on a local physical device).

---

## 3. Current-State Architecture

### 3.1 Module graph (verified from each `build.gradle.kts`)

```
core:model            (no project deps — domain models, ports, preferences, pure mappers, RetentionBounds)
   ^
core:database-schema  (-> core:model)                     entities + DAOs only
   ^
core:scoring          (-> core:model)                     pure formulas, zero Android logic deps
   ^
core:database         (-> core:model, core:database-schema, core:scoring)
   ^
core:healthconnect    (-> core:model, core:database-schema, core:scoring, core:database)
   ^
:app  +  feature:{dashboard,sleep,vitals,workouts,settings,insights,onboarding,about}
core:designsystem <- core:ui (-> core:model, core:designsystem)
```

Acyclic. `core:scoring` is still an Android library only because of the AGP blocker documented in
`CORE_SCORING_JVM_MIGRATION.md`; it has no Android *logic* dependency.

### 3.2 End-to-end flow

```
Health Connect (connect-client 1.1.0)
 │
 ├─ FLOW A · daily  ForegroundSyncController.triggerDailySync()
 │     → HealthSyncUseCase.sync(windowDays=1)      [syncMutex]
 │     → DailySyncUseCase.run
 │         1. settingsRepo.migrateDeviceSelectionIfNeeded() ; rasSourceModeBootstrapUseCase()
 │         2. recomputeSupport.refreshAutoMaxHr(prefs)      (may write maxHeartRate, re-reads prefs)
 │         3. zoneId = prefs.scoringZone() ; today = LocalDate.now(clock.withZone(zoneId))
 │         4. changeSynchronizer.applyPendingChanges()      ← Changes API, per HealthDataType
 │              · token missing + permission granted → requiresFullResync
 │              · changesTokenExpired               → requiresFullResync
 │              · per page, inside transactionRunner.runInTransaction:
 │                    UpsertionChange → getAffectedDatesForDeletedRecord → deleteRecordLocal
 │                                      → getDatesForRecord → upsertRecord
 │                    DeletionChange  → getAffectedDatesForDeletedRecord → deleteRecordLocal
 │              · nextTokens[type] staged; NOT committed here
 │         5. window widening: standardDays ∪ affectedDates, floored at
 │            today − MAX_INLINE_RECOMPUTE_DAYS(7); older ⇒ REQUIRES_HISTORICAL_RESYNC
 │         6. ingestSegment(todayMidnight..windowEnd, budget 3 min)
 │            ingestSegment(oldestTargetDay−1d..todayMidnight, budget 5 min)
 │            (both retry once at EXTENDED_DAILY_INGEST_BUDGET_MS = 10 min)
 │         7. sessionLinkReconciler.reconcile(ingestStart, windowEnd−1, zoneThresholds)
 │         8. stepCountFetcher.fetchWindow(...)  → stepsMap
 │         9. buildWalkForward{Trimp,Baseline,Fatigue}Context(oldestTargetDay, today, zoneId)
 │        10. inRecomputeTransaction {                       ← ONE transaction, whole window
 │                clearFrozenBaselines(oldestTargetDay, today+1, zoneId)
 │                for day in oldest..today: recomputeSupport.recomputeDay(day, steps, prefs, ctx)
 │            }
 │        11. commitTokens(nextTokens) ; updateLastSyncTimestamp(now)
 │
 └─ FLOW B · historical  Settings "Resync" → HealthResyncWorker (OneTimeWork, KEEP,
       FOREGROUND_SERVICE_TYPE_DATA_SYNC) → FullHistoricalResyncUseCase
       → HealthSyncUseCase.resyncRange(start,end,chunkDays=30)   [same syncMutex]
       → ResyncRangeUseCase.run — four resumable phases, checkpointed in ResyncCheckpointStore:
             INGEST    chunked 30d (adaptive halving to MIN_CHUNK_DAYS=1 on window timeout,
                       persisted as chunkDaysOverride) ; each chunk starts 1 day early
             PRUNE     SelectedSourcePruner.prune(selections, zoneId)
             RECONCILE SessionLinkReconciler.reconcile(start−1d .. end+1d−1ms)   ← once, full range
             RECOMPUTE 30-day units (RECOMPUTE_CHECKPOINT_INTERVAL_DAYS), one Room transaction
                       per unit, checkpoint saved only after commit
       `recomputeRange(...)` = the same body with `skipIngestAndPrune = true`, checkpoint identity
       namespaced `RECOMPUTE_ONLY_V2|<deviceSelectionHash>|<scoringCheckpointIdentity()>`.

INGESTION (shared funnel · HealthIngestionCoordinator.ingestWindow, inside withTimeout(budget))
    readSleepSessions / readExerciseSessions(includeDetails) / readWeight / readBodyFat /
    readBloodPressure / readOxygenSaturation / readBodyTemperature / readStepsRecords
        → each wrapped in retryWithBackoff, each fully materialized (bounded volume)
    → SleepDataMapper.mapSleepSession / WorkoutMapper.mapExerciseSession
    → DeviceSourceFilter.filterToDevice(…, prefs.deviceByDataType[type])
    → sleep stages filtered to surviving session ids (FK safety)
    → healthIngestionStore.persist(HealthIngestionBatch(...))          ← ONE transaction
    → readHeartRateSamplesPaged { page ->                              ← streamed
           HeartRateMapper.mapToInputs(page, sleepInputs, workoutInputs)   (SessionLinkSweep)
           DeviceSourceFilter.filterToDevice
           healthIngestionStore.persistHeartRateSamples(...)  }
    → readHrvSamplesPaged { … HrvMapper.mapToInputs(page, sleepInputs) … }

PERSISTENCE (Room 2.8.4 + SQLCipher, WAL, synchronous=NORMAL, foreign_keys=ON, schema v14)
    health_source_records (uuid → autoincrement id, unique sourceRecordId)
    heart_rate_records  (rowId PK; FK sourceRecordRef CASCADE; UNIQUE(sourceRecordRef,timestampMs);
                         conflict-targeted UPSERT preserving rowId)
    hrv_records         (same shape)
    hr_minute_buckets   (PK bucketStartMs, recordType, sessionId)      ← warm tier
    sleep_sessions / sleep_stages (FK CASCADE) / workout_records / workout_route_points (FK CASCADE)
    weight / body_fat / blood_pressure / oxygen_saturation / body_temperature / step_records
                        (PK = "<hcRecordId>_<timeMs>")
    daily_summaries     (PK dateMidnightMs)                            ← cold tier / computed cache
    insight_dismissals, audit_events

3-TIER LIFECYCLE
    hot   0–90 d      raw 1 s heart_rate_records / hrv_records
    warm  90 d–cutoff hr_minute_buckets  (DataRollupWorker daily →
                      DataRollupManager.rollupExpiredHotTier(RetentionBounds.resolveHotTierCutoffMs()))
    cold  permanent   daily_summaries    (RetentionCleanup prunes everything below the retention cutoff)

AGGREGATION → SCORING
    ScoringRepositoryImpl.computeAndPersistDailySummary(day, steps?, prefs, contexts)
        [calculationMutex]  → ScoringDayDataLoader (owns all 10 scoring DAOs)
        · loadMergedMinuteBuckets(hot ∪ warm, per-minute weighted merge)  → EverydayHeartRateLoadCalculator
        · loadWorkouts + loadExerciseHrSamples(getByTypeAndTimeRange EXERCISE)
              → ComputeWorkoutTrimpUseCase → RasCalculator.calculateDailyTrimp (Banister/Cheng/iTRIMP)
        · BaselineComputer (RHR percentile / HRV mu 7d / sigma 56d) → frozen snapshot on the day row
        · ReadinessSummaryCoordinator.{resolveSleepAggregation,computeUncalibratedSummary,
                                       computeCalibratedSummary}
        · CalibrationGate (< 7 sleep sessions in 42 d ⇒ "Calibrating")
        · persist DailySummaryEntity via DailySummaryMapper, stored scoring zone

CACHE / STATE → UI
    daily_summaries + raw tables observed as Room Flows (distinctUntilChanged at the DAO wrapper)
    → feature ViewModels → StateFlow → Compose collectAsStateWithLifecycle
```

### 3.3 What is verifiably solid (do not "improve" these)

- The four-phase resync with transaction-boundary == checkpoint-boundary. This is genuinely
  correct: `inRecomputeTransaction` commits a 30-day unit, *then* the checkpoint is saved, so a
  kill loses at most one unit and the retry idempotently redoes exactly that unit.
- `SessionLinkSweep` — O(samples + sessions) sweep with a property test against
  `SessionLinker.resolve` as oracle. Correct and fast.
- `HeartRateDao.conflictTargetedUpsert` — `ON CONFLICT(sourceRecordRef, timestampMs) DO UPDATE …
  WHERE (recordType IS NOT excluded.recordType OR …)`. Preserves `rowId`, makes an identical
  re-ingest a `changes() = 0` no-op. Materially better than `REPLACE`.
- SQL-side aggregation: `observeAggregateByTimeRange`, `getMinuteBuckets` (both tiers),
  `getAvgSleepHrForSessions`, `getSleepHrProjectionForSessions`.
- `RetentionCleanup`'s 10,000-row bounded batches for the two high-volume tables.
- `retryWithBackoff` never swallowing `CancellationException`; `HealthConnectWindowTimeoutException`
  deliberately *not* a `CancellationException` so callers can distinguish density from cancellation.
- Adaptive chunk halving persisted via `chunkDaysOverride`.
- `ScoringConstants` — every non-obvious constant carries a `REF` to primary literature.
- The stored-scoring-zone discipline inside the sync + scoring core (`prefs.scoringZone()`).
- Coroutine hygiene: injected `@IoDispatcher`/`@DefaultDispatcher`, no `GlobalScope`, no
  production `runBlocking`, lambda-gated logging through a sink (`AppLog.kt`).
- Security posture: `allowBackup="false"`, scoped `dataExtractionRules`/`fullBackupContent`,
  SQLCipher with a Keystore-wrapped 256-bit key and a documented cross-process lock.

---

## 4. Findings Register

Severity: Critical / High / Medium / Low. Confidence: High / Medium / Low.
Status: **confirmed** (proved by reading the code path end to end) or **suspected**
(evidence is strong but a runtime/instrumented check is required to close it).

### 4.1 Architecture

---

#### `R2-ARCH-001` — ViewModel reads Health Connect directly and merges unfiltered samples

| | |
|---|---|
| **Category** | Architecture / data integrity |
| **Severity** | High |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutDetailViewModel.kt:207-285` (`loadWorkout`)
- `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/HealthConnectRepository.kt:75` (`readHeartRateSamples`)
- `core/healthconnect/.../data/healthconnect/HealthConnectRepositoryImpl.kt:267-273`

**Current behavior.** `loadWorkout` calls `hcRepo.readHeartRateSamples(start, recoveryWindowEnd)`
directly, flattens every record's samples into `HeartRatePoint`s, separately loads
`heartRateRepository.getByTimeRange(...)` from Room, then merges:

```kotlin
val allSamples = (hcSamples + dbSamples)
    .distinctBy { it.timestamp }
    .sortedBy { it.timestamp }
```

`allSamples` then feeds `ChartDataMapper.mapToChartData`, `endHr`, and
`RecoveryMetricsMapper.mapRecoveryMetrics` (HRR-1min / HRR-2min).

**Evidence.** `AGENTS.md` §Core Architecture: *"Room DB is single source of truth. Health Connect
is ingestion-only. UI must NEVER access Health Connect directly."* This is the only production
call site of `readHeartRateSamples` outside tests (`grep` over `core app feature`, excluding
`build/` and test source sets, returns exactly this one).

**Root cause.** The recovery-window HR (workout end → +3 min + tolerance) is *not* in
`heart_rate_records` when it falls outside a session, was device-filtered out, or predates a
sync. Rather than widening the Room read, the ViewModel was given a Health Connect escape hatch.

**Impact.**
1. Contract violation — a second, unmanaged read path to HC from the UI thread's coroutine.
2. **Data-integrity bug:** `hcSamples` are *not* passed through `DeviceSourceFilter`. A user who
   selected one watch as the `HEART_RATE` source still sees a second device's samples in the
   workout chart and in the HRR recovery metric, because `distinctBy { it.timestamp }` keeps
   whichever of the two lists came first — and `hcSamples` is first.
3. HC availability/permission state now affects a detail screen that should render from Room.

**Recommended remediation.** Delete the HC read from the ViewModel. Move the recovery-window
concern into the data layer: add `HeartRateRepository.getRecoveryWindowSamples(workoutId,
endMs, toleranceSeconds)` backed by `HeartRateDao.getByTimeRange` **plus** the warm-bucket
fallback (see `R2-UI-002`), and make ingestion responsible for the recovery window by extending
the workout ingest window by `hrrToleranceSeconds + 3 min` (`WorkoutMapper` already knows the
session bounds). If the product decision is that HC must remain the fallback, route it through a
`suspend` use-case in `core:healthconnect` that applies `DeviceSourceFilter` with
`prefs.deviceByDataType[HealthDataType.HEART_RATE.name]` — the ViewModel must not call `hcRepo`.

**Dependencies.** None. Independent of every other finding, though it is cleanest after
`R2-UI-002` provides the warm fallback.

**Implementation complexity.** M. **Migration risk.** Low — read-path only; no schema change.

**Acceptance criteria**
- `grep -rn "hcRepo\.\|healthConnectRepository\." feature/ --include=*.kt` outside test sources
  returns no read of sample/record data (permission checks such as `hasExerciseRoutesPermission`
  may remain, or move behind a port).
- A konsist rule fails the build if any `feature:*` module references
  `HealthConnectRepository` sample-read members.
- Unit test: two devices in the same window, `HEART_RATE` selection = device A ⇒ chart and
  HRR metrics contain zero device-B samples.

---

#### `R2-ARCH-002` — Two ingestion persistence paths; the changes path bypasses the store port

| | |
|---|---|
| **Category** | Architecture / SOLID (dependency inversion) |
| **Severity** | High |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `core/healthconnect/.../data/healthconnect/HealthChangeSynchronizerImpl.kt:52-70` (constructor),
  `:226-247` (`processChangesPage`), `:248-450` (`upsertRecord`), `:556-640`
  (`getAffectedDatesForDeletedRecord`, `deleteRecordLocal`), `:641-750` (private entity mappers)
- `core/healthconnect/.../domain/sync/HealthIngestionCoordinator.kt` (the *other* path)
- `core/model/.../domain/sync/HealthIngestionStore.kt` (the port both should use)
- `core/database/.../data/local/RoomHealthIngestionStore.kt`

**Current behavior.** `HealthIngestionCoordinator` persists exclusively through the
`HealthIngestionStore` port (`persist(HealthIngestionBatch)`, `persistHeartRateSamples`,
`persistHrvSamples`). `HealthChangeSynchronizerImpl` — the *other* half of the same ingestion
concern — injects **13 DAOs** directly (`sleepSessionDao`, `sleepStageDao`, `heartRateDao`,
`hrvDao`, `workoutDao`, `weightRecordDao`, `bodyFatRecordDao`, `bloodPressureRecordDao`,
`oxygenSaturationRecordDao`, `bodyTemperatureRecordDao`, `stepRecordDao`, `sourceRecordDao`) plus
`TransactionRunner` and `Context`, and re-declares its own private
`SleepSessionInput.toEntity()`, `WorkoutInput.toEntity()`, `HeartRateInput.toEntity(ref)`,
`HrvInput.toEntity(ref)`, `SleepStageInput.toEntity()` mappers at file scope (lines 641-750) —
duplicating `core/database/.../data/local/HealthIngestionInputMappers.kt`.

**Evidence.** The two mapper sets have already drifted: `R2-ARCH-003` (device-name
representation), `R2-HC-003` (per-sample source-ref lookup exists only in the changes path).
`core:healthconnect` declares `implementation(project(":core:database-schema"))` purely to reach
those DAOs.

**Root cause.** The `HealthIngestionStore` port was introduced for the bulk path (per
`DATA_FLOW.md`) and the Changes API path was never migrated onto it.

**Impact.** Every ingestion invariant must be enforced twice and is currently enforced once.
This finding is the *root cause* of `R2-ARCH-003` and `R2-HC-003`, and it is why a schema or
mapping change can silently apply to one path only.

**Recommended remediation.** Extend `HealthIngestionStore` with the operations the changes path
needs and migrate `HealthChangeSynchronizerImpl` onto it:

```kotlin
interface HealthIngestionStore {
    // existing
    suspend fun persist(batch: HealthIngestionBatch)
    suspend fun persistHeartRateSamples(samples: List<HeartRateInput>)
    suspend fun persistHrvSamples(samples: List<HrvInput>)
    suspend fun clearFrozenBaselines(start: LocalDate, endExclusive: LocalDate, zoneId: ZoneId)
    // new — owned by core:database, used by the changes path
    suspend fun affectedDatesForRecord(type: HealthDataType, hcRecordId: String, zoneId: ZoneId): Set<LocalDate>
    suspend fun deleteRecord(type: HealthDataType, hcRecordId: String)
    suspend fun sessionSpansOverlapping(startMs: Long, endMs: Long): SessionSpans   // sleep + workout
}
```

Then delete the file-scope mappers at `HealthChangeSynchronizerImpl.kt:641-750` in favour of
`HealthIngestionInputMappers.kt`, and drop `core:database-schema` from `core:healthconnect`'s
dependencies if nothing else needs it.

**Dependencies.** Blocks the clean fixes for `R2-ARCH-003` and `R2-HC-003` (both can be
point-patched first if schedule demands, but the duplication returns).

**Implementation complexity.** L. **Migration risk.** Medium — behavior-preserving refactor of
a 750-line file on the critical sync path; requires the existing
`core/healthconnect/src/test/.../DailySyncUseCaseTest.kt` and change-path tests to stay green.

**Acceptance criteria**
- `HealthChangeSynchronizerImpl`'s constructor injects no `*Dao` type.
- No `toEntity`/`toInput` mapper is declared in `core:healthconnect`.
- Existing change-synchronizer tests pass unmodified.
- `core/healthconnect/build.gradle.kts` no longer needs `:core:database-schema` (or a comment
  records why it still does).

---

#### `R2-ARCH-003` — "Unknown device" is `""` in one ingestion path and `null` in the other

| | |
|---|---|
| **Category** | Architecture / data integrity |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `core/healthconnect/.../data/mapper/MapperHelpers.kt:35-36`
  (`extractDeviceName(deviceName: String?): String = deviceName?.takeIf { it.isNotBlank() } ?: ""`)
- `core/healthconnect/.../data/mapper/{Weight,BodyFat,BloodPressure,OxygenSaturation,BodyTemperature}DataMapper.kt`
- `core/healthconnect/.../domain/sync/HealthIngestionCoordinator.kt` (`deviceName = record.deviceName`, nullable)
- `core/database-schema/.../dao/HeartRateDao.kt` (`getDistinctDeviceNames`, `deleteRecordsNotMatchingDevice`)

**Current behavior.** For the five scalar vitals types, the Changes API path writes `""` when the
device label is unknown; the bulk ingest path writes SQL `NULL` for the same record.

**Evidence.**
```sql
-- getDistinctDeviceNames  (excludes both, so consistent here)
WHERE deviceName IS NOT NULL AND deviceName != ''
-- deleteRecordsNotMatchingDevice  (treats them identically — also consistent)
WHERE ... AND (deviceName != :deviceName OR deviceName IS NULL)
```
The current DAO predicates happen to normalize both forms, so this is a latent rather than an
active defect — but each entity carries an `Index(value = ["timestampMs", "deviceName"])`, and
any future predicate written against one representation silently misses the other.

**Root cause.** `R2-ARCH-002` — two mapper sets, no shared normalization.

**Impact.** Latent divergence; index selectivity split across two encodings of the same fact.

**Recommended remediation.** Pick `null` as the single representation (it is what the entity
default declares: `val deviceName: String? = null`). Change `MapperHelpers.extractDeviceName`
to return `String?`, and add a one-shot normalization to the migration in Phase 2:
`UPDATE <table> SET deviceName = NULL WHERE deviceName = ''` for the five vitals tables.

**Dependencies.** Cleanest after `R2-ARCH-002`. **Complexity.** S. **Migration risk.** Low
(idempotent `UPDATE`, no schema change, but it *is* a data migration — bump to v15 or run it as a
`Migration14To15` no-op-safe statement).

**Acceptance criteria**
- `SELECT COUNT(*) FROM weight_records WHERE deviceName = ''` is 0 after migration (and the same
  for the other four tables).
- `MapperHelpers.extractDeviceName` returns `String?`.

---

#### `R2-ARCH-004` — `UserPreferences` is a `domain` typealias onto a `data` class, imported both ways

| | |
|---|---|
| **Category** | Architecture / Clean Code |
| **Severity** | Low |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `core/model/.../data/preferences/UserPreferences.kt:14` (the `data class`)
- `core/model/.../domain/preferences/PreferenceAliases.kt:12`
  (`typealias UserPreferences = app.readylytics.health.core.model.data.preferences.UserPreferences`)
- `HealthChangeSynchronizerImpl.kt:29` imports `core.model.data.preferences.UserPreferences`;
  `DailySyncUseCase.kt` / `ResyncRangeUseCase.kt` import `core.model.domain.preferences.UserPreferences`

**Current behavior.** One type, two import paths, used inconsistently *inside the same package*.

**Impact.** No runtime effect (typealias). Costs readers: a `domain` port surface that is really
a `data` class makes the layering claim in `AGENTS.md` weaker than it looks, and a future move of
the class breaks whichever call sites used the non-canonical path.

**Recommended remediation.** Standardize on the `domain` alias in every non-`data` package
(mechanical import rewrite), and add a konsist rule asserting no production file outside
`core/model/.../data/preferences/` imports the `data` path directly.

**Complexity.** S. **Migration risk.** None. **Acceptance criteria:** konsist rule green;
`grep -rn "core.model.data.preferences.UserPreferences" --include=*.kt` matches only the
declaration, the alias, and files under `data/`.

---

### 4.2 Dependency Injection

---

#### `R2-DI-001` — `HealthConnectClient` is service-located inside the change synchronizer

| | |
|---|---|
| **Category** | DI / testability |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `HealthChangeSynchronizerImpl.kt:53` (`@ApplicationContext private val context: Context`)
- `HealthChangeSynchronizerImpl.kt:71` (`private val client by lazy { HealthConnectClient.getOrCreate(context) }`)
- `core/healthconnect/.../di/HealthConnectModule.kt`

**Current behavior.** The class takes `Context` and constructs its own `HealthConnectClient`
via the static `getOrCreate`. `HealthConnectRepositoryImpl` in the same module receives its
collaborators through Hilt.

**Root cause.** Historical; the client was never promoted to a binding.

**Impact.** `Context` is injected for no reason other than to reach a static factory; the class
cannot be unit-tested without Robolectric or a static mock; two different lifetimes of the same
client can exist in the graph.

**Recommended remediation.** Add to `HealthConnectModule`:

```kotlin
@Provides @Singleton
fun provideHealthConnectClient(@ApplicationContext context: Context): HealthConnectClient =
    HealthConnectClient.getOrCreate(context)
```
inject `HealthConnectClient` into both `HealthConnectRepositoryImpl` and
`HealthChangeSynchronizerImpl`, and drop `Context` from the latter's constructor.

**Dependencies.** Do together with `R2-ARCH-002` (same constructor).
**Complexity.** S. **Migration risk.** Low.
**Acceptance criteria:** `HealthChangeSynchronizerImpl` constructor contains no `Context`; a
plain JVM unit test can construct it with a fake `HealthConnectClient`.

---

#### `R2-DI-002` — Time is injected in three places and read statically in the rest

| | |
|---|---|
| **Category** | DI / determinism / testability |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- Injected `java.time.Clock`: `HealthSyncUseCase.kt:32`, `DailySyncUseCase.kt:53`
- Static reads: `RetentionBounds.resolveHotTierCutoffMs(now: Instant = Instant.now())`,
  `RetentionBounds.resolveHistoricalWindow(prefs, now = Instant.now())`,
  `DailySyncUseCase` telemetry (`System.currentTimeMillis()` ×3),
  `ResyncRangeUseCase` telemetry (×8) and `settingsRepo.updateLastSyncTimestamp(System.currentTimeMillis())`,
  `app/.../workers/DataRollupWorker.kt:30`, `CrashReportFileExport.suggestedFilename` (`Instant.now()`)

**Current behavior.** `RetentionBounds` accepts an `Instant` with a `now()` default, and every
production caller takes the default. `ResyncRangeUseCase` has no `Clock` at all, so its
`lastSyncTimestamp` write and its telemetry are untestable without wall-clock tolerance.

**Impact.** The hot/warm cutoff, the retention cutoff and `lastSyncTimestamp` cannot be pinned in
tests — which is exactly what the tier-boundary findings (`R2-DB-001`, `R2-DB-004`,
`R2-CACHE-001`) need in order to be regression-tested.

**Recommended remediation.** Inject `Clock` into `ResyncRangeUseCase`, `DataRollupWorker`,
`DataCleanupWorker`, and make `RetentionBounds`' `now` parameter **required** (no default) so
every call site must name its time source. Keep `RetentionBounds` pure.

**Dependencies.** Prerequisite for the Phase-0 characterization tests.
**Complexity.** S-M. **Migration risk.** None (compile-time).
**Acceptance criteria:** `grep -n "Instant.now()\|System.currentTimeMillis()"` in
`core/healthconnect/src/main`, `core/model/.../RetentionBounds.kt` and `app/.../workers/` returns
only the DI-module binding for the default `Clock`.

---

### 4.3 Health Connect ingestion

---

#### `R2-HC-001` — Full resync is additive-only: it cannot converge on Health Connect deletions

| | |
|---|---|
| **Category** | Health Connect / data integrity |
| **Severity** | High |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `core/healthconnect/.../domain/sync/ResyncRangeUseCase.kt:216-330` (INGEST phase),
  `:330-380` (PRUNE phase → `SelectedSourcePruner.prune`)
- `core/healthconnect/.../data/healthconnect/HealthChangeSynchronizerImpl.kt:226-247`
  (the only deletion-aware path)
- `core/database/.../data/local/SelectedSourcePrunerImpl.kt`
- `core/database/.../data/local/RetentionCleanup.kt`

**Current behavior.** Local rows are removed by exactly three mechanisms:
`DeletionChange`/`UpsertionChange` handling in the Changes API path, device-selection pruning
(`deleteRecordsNotMatchingDevice`-style, keyed on `deviceName`), and retention cleanup
(`timestampMs < cutoff`). The resync INGEST phase only upserts.

**Evidence.** `ResyncRangeUseCase`'s own KDoc states the design: *"no blanket delete is
performed, so a worker killed/failed mid-pass leaves prior valid data intact."* Correct for
crash-safety; it also means the resync has no delete semantics at all. Meanwhile
`applyPendingChanges` returns `requiresFullResync = true` in four situations —
`token.isNullOrBlank()` with permission granted, `response.changesTokenExpired`,
`SecurityException`, and `isTokenExpiredException(e)` — and `DailySyncUseCase` then returns
`REQUIRES_HISTORICAL_RESYNC`, whose only recovery is the additive resync.

**Root cause.** Deletion convergence was delegated entirely to the Changes API, and the escalation
path for a *lost* Changes token leads to a mechanism that cannot delete.

**Impact.** A user who deletes health records in Health Connect while the app is not syncing long
enough for the token to expire keeps those records locally, permanently, and they keep
contributing to TRIMP, baselines and every derived score. Health Connect change tokens are
documented as expiring after ~30 days of non-use, so this is reachable by an ordinary user who
does not open the app for a month.

**Recommended remediation.** Give the resync a bounded, correct delete step. Two viable designs:

- **Option A (recommended) — set reconciliation per chunk.** During INGEST, each 30-day chunk
  already reads every session/record id Health Connect holds for that window. Collect the HC ids
  per record type per chunk and, in the same transaction that persists the chunk, delete local
  rows in that window whose id is absent from the HC set. Cost: one extra `SELECT id` per
  window per type; the HR/HRV tables are covered transitively because
  `health_source_records` is the id dimension and `heart_rate_records` cascades from it.
- **Option B — token re-baseline only.** On `requiresFullResync`, after the resync completes,
  compare local ids against a fresh HC read of the same window. Simpler, but doubles the read.

Option A must be **opt-in per phase and skipped when `skipIngestAndPrune = true`** (a
recompute-only pass reads no HC data and must never delete).

**Dependencies.** Interacts with `R2-CACHE-001` — deletions must enqueue affected dates for
recompute. **Complexity.** L. **Migration risk.** Medium-High: a bug here deletes user data.
Gate behind a preference-free feature flag constant for one release and log the delete counts
via the existing `ResyncTelemetry` tag before enabling.

**Acceptance criteria**
- Integration test: ingest 3 workouts + 2 sleep sessions; delete one of each from the fake HC
  source; run `resyncRange` over the same window; local row counts converge and the affected
  days are recomputed.
- Test: `recomputeRange` (`skipIngestAndPrune = true`) deletes nothing.
- Test: a cancelled resync mid-chunk deletes nothing outside the committed chunk.
- `ResyncTelemetry` logs `[INGESTION] reconciled deletes: hr=… sleep=… workout=…`.

---

#### `R2-HC-002` — Retry restarts the whole paged window, inside the same timeout budget

| | |
|---|---|
| **Category** | Health Connect / performance / robustness |
| **Severity** | High |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `core/healthconnect/.../domain/sync/HealthIngestionCoordinator.kt:236-275` (the two
  `retryWithBackoff { hcRepo.read…SamplesPaged(...) { … persist … } }` blocks)
- `core/healthconnect/.../domain/sync/RetryWithBackoff.kt`
- `core/healthconnect/.../data/healthconnect/HealthConnectRepositoryImpl.kt:233-251`
  (`readAllPagesStreaming`, `pageToken` loop)

**Current behavior.**
```kotlin
retryWithBackoff {
    hrSampleCount = 0
    hcRepo.readHeartRateSamplesPaged(windowStart, windowEnd) { page ->
        …map…filter…
        healthIngestionStore.persistHeartRateSamples(filteredHr)
        pagesIngested++
        onProgress?.invoke(ResyncPhase.INGEST, pagesIngested, 0)
    }
}
```
The retry unit is *the entire window*, and `readAllPagesStreaming` restarts from
`pageToken = null`. The whole thing runs inside `withTimeout(windowBudgetMs)` established by
`ingestWindowWithinBudget`.

**Evidence.** `readAllPagesStreaming` holds `pageToken` in a local `var` with no external
checkpoint; `HealthConnectRetryPolicy` retries up to 4 attempts with backoff, and the elapsed
backoff delays are themselves inside the timeout.

**Root cause.** The retry was placed around the streaming call rather than around a single page.

**Impact.** On a dense window (§7: ~86,400 HR samples/day at 1 Hz), a transient rate-limit on
page ~900 discards ~900 pages of already-*persisted* work, re-reads them from the HC IPC, and
re-runs ~1M no-op UPSERTs — then very likely exhausts the 3/5/10-minute budget and throws
`HealthConnectWindowTimeoutException`, which in the resync triggers chunk halving that does not
address the real cause. Correctness is preserved (upsert is idempotent); throughput and battery
are not.

**Recommended remediation.** Move the retry inside the page loop and make the page cursor
resumable:

```kotlin
// HealthConnectRepository
suspend fun readHeartRateSamplesPaged(
    from: Instant, to: Instant,
    startPageToken: String? = null,
    onPage: suspend (page: List<DomainHeartRateRecord>, nextPageToken: String?) -> Unit,
)
```
so `HealthIngestionCoordinator` can (a) wrap only `client.readRecords(...)` in
`retryWithBackoff`, and (b) persist `nextPageToken` per record type in the resync checkpoint
(`ResyncCheckpoint` gains `hrPageToken`/`hrvPageToken`, defaulted `null`, cleared on chunk
advance). A page-level retry never re-persists a committed page.

**Dependencies.** The checkpoint field addition touches `ResyncCheckpointStore` (DataStore-backed,
additive). **Complexity.** M-L. **Migration risk.** Low — nullable additive checkpoint fields;
an old checkpoint deserializes with `null` and behaves exactly as today.

**Acceptance criteria**
- Fake HC source fails on page 3 of 10 once: exactly 10 page reads and 10 persists total
  (not 13), and the ingest completes.
- Killing the worker mid-window and resuming re-reads only from the stored page token.
- Benchmark (§11) shows no regression on the happy path.

---

#### `R2-HC-003` — Changes path issues one `getOrCreateSourceRef` per *sample*

| | |
|---|---|
| **Category** | Health Connect / performance |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `HealthChangeSynchronizerImpl.kt:281-303` (HEART_RATE branch), `:304-322` (HRV branch)
- `core/database-schema/.../dao/SourceRecordDao.kt` (`getOrCreateSourceRef`)

**Current behavior.**
```kotlin
val entities = hrInputs.map { input ->
    input.toEntity(
        sourceRecordDao.getOrCreateSourceRef(
            sourceRecordId = input.id.substringBefore('_'),   // same value for every sample
            recordType = "HEART_RATE",
            createdAtMs = input.timestampMs,
        ),
    )
}
```
Every sample of one `HeartRateRecord` resolves the *same* `sourceRecordId` — a
`HeartRateRecord` can carry hundreds of samples. Additionally, each record triggers
`sleepSessionDao.getOverlapping(startMs, endMs)` and `workoutDao.getOverlapping(startMs, endMs)`.

**Evidence.** `input.id` is built by `HeartRateMapper` as `"${record.id}_$sampleMs"`;
`substringBefore('_')` therefore yields the identical HC record id for every element of
`hrInputs`.

**Impact.** N redundant round-trips per record inside one Room transaction. On a large
backfill delivered through the Changes API this multiplies the changes-path cost by the average
samples-per-record factor.

**Recommended remediation.** Hoist the lookup:
```kotlin
val ref = sourceRecordDao.getOrCreateSourceRef(record.metadata.id, "HEART_RATE", startMs)
val entities = hrInputs.map { it.toEntity(ref) }
```
and hoist the two `getOverlapping` calls to once per *page* (union of the page's record time
range) rather than once per record. Both fall out naturally when the path moves onto
`HealthIngestionStore` (`R2-ARCH-002`), whose `sessionSpansOverlapping` sketch takes a range.

**Dependencies.** Best done with `R2-ARCH-002`; safe to point-fix first.
**Complexity.** S. **Migration risk.** None.
**Acceptance criteria:** unit test with a 200-sample `HeartRateRecord` asserts
`getOrCreateSourceRef` is invoked exactly once (MockK `verify(exactly = 1)`).

---

#### `R2-HC-004` — Step semantics differ between the daily flow and the resync flow

| | |
|---|---|
| **Category** | Health Connect / determinism |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `core/healthconnect/.../domain/sync/DailySyncUseCase.kt:262` — `val steps = stepsMap[dayToScore]`
- `core/healthconnect/.../domain/sync/ResyncRangeUseCase.kt:472-477`:
  ```kotlin
  val stepsForDay = when {
      skipIngestAndPrune -> null
      stepsDevice != null -> stepsMap[day] ?: 0L
      else -> stepsMap[day]
  }
  ```
- `core/database/.../domain/sync/DailyRecomputeSupport.kt:42-45` (contract: `null` ⇒ preserve
  the stored step count)

**Current behavior.** With a step source device selected, a day with no step data scores as
`0` steps under the resync and *preserves whatever was previously stored* under the daily sync.

**Root cause.** The `?: 0L` normalization was added to one flow only.

**Impact.** The same day yields two different `stepCount` values (and therefore any
step-dependent insight, e.g. `StepShortfallRule`) depending on which flow last touched it. This
directly contradicts the resync-determinism property the repository tests elsewhere
(`ScoringSyncScopeOutputsDeterminismTest`).

**Recommended remediation.** Extract the decision into one shared pure function and call it from
both flows:
```kotlin
// core/model/.../domain/sync/StepAttribution.kt
fun resolveStepsForDay(day: LocalDate, stepsMap: Map<LocalDate, Long>,
                       stepsDeviceSelected: Boolean, recomputeOnly: Boolean): Long?
```
**Open decision (see §14 OD-2):** which semantic is correct — `0` (a selected device that
reported nothing genuinely walked 0) or *preserve*. `0` is the better default and matches the
resync, but it is a user-visible change on the daily path, so it needs confirmation.

**Dependencies.** None. **Complexity.** S. **Migration risk.** Low, but user-visible: a
historical day can change from a preserved value to `0`.
**Acceptance criteria:** a parameterized test drives both flows over the same fixture and asserts
identical `stepCount` for every day in the range.

---

#### `R2-HC-005` — `lastSyncTimestamp` is written by a sync that reports `REQUIRES_HISTORICAL_RESYNC`

| | |
|---|---|
| **Category** | Health Connect / state machine |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `core/healthconnect/.../domain/sync/DailySyncUseCase.kt:296-305`
- `core/healthconnect/.../domain/sync/HealthSyncUseCase.kt:67-85` (`catchUpSync` gate)

**Current behavior.**
```kotlin
if (!requiresHistoricalResync) { changeSynchronizer.commitTokens(outcome.nextTokens) }
settingsRepo.updateLastSyncTimestamp(System.currentTimeMillis())     // ← unconditional
if (requiresHistoricalResync) { Result.failure("Requires historical resync", "REQUIRES_HISTORICAL_RESYNC") }
else { Result.success(Unit) }
```
Tokens are correctly withheld, but `lastSyncTimestamp` is written on the failure path too.

**Evidence.** `catchUpSync` is gated exactly on this value:
```kotlin
if (prefs.lastSyncTimestamp > 0L) { … return@withLock Result.success(Unit) }
```

**Root cause.** The unconditional write predates the `requiresHistoricalResync` branch.

**Impact.** On a first launch whose very first daily sync escalates to
`REQUIRES_HISTORICAL_RESYNC`, `lastSyncTimestamp` becomes non-zero, so the genuine first-launch
`catchUpSync` is permanently skipped. The user is then dependent on the resync worker having been
enqueued — recoverable via the Settings resync button, but the automatic first-launch history
backfill is lost silently. Also, `DATA_FLOW.md` describes `lastSyncTimestamp` as meaning *"data
was actually re-ingested up to here"* (quoted in `ResyncRangeUseCase`'s own comment justifying why
the recompute-only path must not write it) — the daily path violates that same rule.

**Recommended remediation.** Move the write inside the success branch:
```kotlin
if (requiresHistoricalResync) {
    Result.failure("Requires historical resync", "REQUIRES_HISTORICAL_RESYNC")
} else {
    changeSynchronizer.commitTokens(outcome.nextTokens)
    settingsRepo.updateLastSyncTimestamp(clock.millis())
    Result.success(Unit)
}
```
(also fixes the `System.currentTimeMillis()` half of `R2-DI-002` here).

**Dependencies.** None. **Complexity.** S. **Migration risk.** Low. Users already in the bad
state need `ForegroundSyncController` to enqueue a resync when it sees
`REQUIRES_HISTORICAL_RESYNC` — verify that it does (it appears to; confirm in the work package).

**Acceptance criteria:** unit test — a daily sync returning `REQUIRES_HISTORICAL_RESYNC` leaves
`lastSyncTimestamp` unchanged; a successful one advances it.

---

#### `R2-HC-006` — Token-expiry detection matches on exception message substrings

| | |
|---|---|
| **Category** | Health Connect / robustness |
| **Severity** | Low |
| **Confidence** | Medium |
| **Status** | suspected |

**Affected files and symbols**
- `HealthChangeSynchronizerImpl.kt:470-474`
  ```kotlin
  private fun isTokenExpiredException(e: Exception): Boolean {
      val msg = e.message?.lowercase() ?: ""
      return msg.contains("expired") || msg.contains("invalid token") || msg.contains("token not found")
  }
  ```

**Current behavior.** Secondary detection path for an expired changes token, used when the SDK
throws instead of setting `response.changesTokenExpired`.

**Impact.** English-substring matching against a message the Health Connect client is free to
change. A miss falls through to `throw e`, which `DailySyncUseCase` flattens to `SYNC_ERROR`;
the user sees a generic failure and no resync is escalated. A false positive triggers an
unnecessary full resync.

**Why suspected, not confirmed.** With connect-client 1.1.0 the primary
`response.changesTokenExpired` flag is the documented mechanism and covers the normal case; I
could not prove from source that the throwing path is reachable in 1.1.0.

**Recommended remediation.** Keep the flag as primary; narrow the fallback to the concrete
exception types the SDK version in use actually throws (verify against the
`connect-client` 1.1.0 sources on the build classpath), and log at WARN when the fallback fires
so the frequency is observable before further tightening.

**Complexity.** S. **Migration risk.** None.
**Acceptance criteria:** the fallback logs a distinguishable WARN; a unit test covers both the
flag path and the fallback path.

---

### 4.4 Room / local data model

---

#### `R2-DB-001` — Hot/warm tier selection is all-or-nothing per session; boundary-straddling sessions lose half their samples

| | |
|---|---|
| **Category** | Database / scoring correctness |
| **Severity** | High |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `core/database/.../data/repository/ScoringHistoryRepositoryImpl.kt:44-60`
  (`getSleepHrProjectionForSessions`), `:61-78` (`getAvgSleepHrForSessions`),
  `:82-87` (`getSleepHrSamplesForSession`)
- `core/database/.../data/repository/ScoringDayDataLoader.kt:62-68` (`loadWorkoutSamples`)
- `core/database/.../data/local/DataRollupManager.kt:24-29`
- `core/model/.../domain/util/RetentionBounds.kt:44-49` (`resolveHotTierCutoffMs`)
- consumer: `core/database/.../data/repository/ReadinessSummaryCoordinator.kt:155`

**Current behavior.** Three of the four tier-aware reads decide "hot **or** warm" per session,
never "hot **plus** warm":

```kotlin
// getSleepHrSamplesForSession
val hot = heartRateDao.getSleepHrSamplesForSession(sessionId)
if (hot.isNotEmpty()) return hot                                    // ← truncates
return minuteBucketDao.getBucketsForSession("SLEEP", sessionId).reconstructSampleValues().sorted()

// getSleepHrProjectionForSessions
val hotSessionIds = hot.map { it.sessionId }.toSet()
val warmOnly = sessionIds.filter { it !in hotSessionIds }           // ← session is "hot" if ANY sample is hot

// getAvgSleepHrForSessions
val warmOnly = sessionIds.filter { it !in hot }                     // ← same

// ScoringDayDataLoader.loadWorkoutSamples
val hot = hotSamples.filter { it.timestampMs in workout.startTime..workout.endTime }
if (hot.isNotEmpty()) return hot                                    // ← truncates
```

`loadMergedMinuteBuckets` (the everyday-HR path) is the one that gets it right — it unions both
tiers per minute.

**Evidence.** `DataRollupManager.rollupExpiredHotTier(cutoffMs)` rolls up and deletes strictly by
`timestampMs < cutoffMs`, where `cutoffMs = now − 90 days` at the moment `DataRollupWorker` runs.
Nothing aligns that instant to a session boundary. A sleep session running 23:00→07:00 across the
cutoff instant therefore ends up with part of its samples in `hr_minute_buckets` and part still in
`heart_rate_records`.

`DATA_FLOW.md:438-439` states the intended contract: *"Hot-path reads are unchanged; warm
fallbacks fire only when raw data is absent."* The partial case is not covered by that sentence
and not handled by the code.

**Root cause.** The tier fallback was written as a binary "raw exists / raw gone" test rather than
a coverage-aware union.

**Impact.** For each boundary-straddling sleep session, the RHR percentile
(`BaselineComputer.rhrHistoryBetween` → `HistoricalSleepDayAssembler` →
`getSleepHrProjectionForSessions`) and the nightly average HR are computed from a truncated
sample set biased toward whichever half survived. Because the RHR percentile feeds
`rhrBaseline` → `RasCalculator.calculateDailyTrimp` → daily TRIMP → ATL/CTL/strain ratio, the
error propagates into Load and Readiness for a rolling window after the boundary. The same holds
for a workout straddling the cutoff via `loadWorkoutSamples` → `ComputeWorkoutTrimpUseCase`.
Affects at most a few sessions per rollup run, but silently and permanently (the frozen baseline
snapshot preserves the wrong value).

**Recommended remediation.** Make every tier read coverage-aware. Preferred shape — union by
time range rather than by presence:

```kotlin
// ScoringHistoryRepositoryImpl
override suspend fun getSleepHrSamplesForSession(sessionId: String): List<Int> {
    val hot  = heartRateDao.getSleepHrSamplesForSession(sessionId)          // already 30..230 filtered
    val warm = minuteBucketDao.getBucketsForSession("SLEEP", sessionId)
    if (warm.isEmpty()) return hot
    // rollup deletes the raw rows it folded in, so hot and warm never cover the same minute
    return (hot + warm.reconstructSampleValues()).sorted()
}
```
The union is safe precisely because rollup is transactional (`R2-PERF-002` notwithstanding): a
minute is either raw or bucketed, never both. Apply the same union to
`getSleepHrProjectionForSessions`, `getAvgSleepHrForSessions` (weight the warm contribution by
`sampleCount`, as that method already does), and `ScoringDayDataLoader.loadWorkoutSamples`.

Add an invariant test asserting hot and warm never overlap for a session id, so the union can
never double-count.

**Dependencies.** Fix *before* `R2-PERF-001` (the allocation fix touches the same call sites) and
before any §11 benchmark, since benchmarks over lossy reads are meaningless.

**Implementation complexity.** M. **Migration risk.** Low code risk; **user-visible score
change** for affected historical days — must be paired with a recompute (`R2-CACHE-001`).

**Acceptance criteria**
- Test fixture: one sleep session whose samples straddle a cutoff; roll up; assert
  `getSleepHrSamplesForSession` returns `hotCount + warmSampleCount` values and that the
  percentile equals the pre-rollup percentile within the documented tolerance (see `R2-DB-004`).
- Same for `loadWorkoutSamples` and a straddling workout.
- Invariant test: no `(recordType, sessionId)` has both a raw row and a bucket covering the same
  minute after a rollup.

---

#### `R2-DB-002` — Rollup destroys device provenance and merges devices into one bucket

| | |
|---|---|
| **Category** | Database / provenance |
| **Severity** | Medium-High |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `core/database-schema/.../dao/MinuteBucketDao.kt:44-60` (`rollupIntoBucketsBefore`)
- `core/database-schema/.../entity/HrMinuteBucketEntity.kt:16`
  (`primaryKeys = ["bucketStartMs", "recordType", "sessionId"]`, `val deviceName: String? = null`)
- `core/database/.../data/local/SelectedSourcePrunerImpl.kt`
- `core/database-schema/.../dao/HeartRateDao.kt` (`getDistinctDeviceNames`,
  `deleteRecordsNotMatchingDevice`)

**Current behavior.** The rollup statement writes provenance away:

```sql
INSERT OR REPLACE INTO hr_minute_buckets
  (bucketStartMs, bucketEndMs, minBpm, maxBpm, avgBpm, sampleCount, recordType, sessionId, deviceName)
SELECT (timestampMs/60000)*60000, (timestampMs/60000)*60000 + 60000,
       MIN(beatsPerMinute), MAX(beatsPerMinute), AVG(beatsPerMinute), COUNT(*),
       recordType, COALESCE(sessionId, ''), NULL          -- ← deviceName discarded
FROM heart_rate_records
WHERE timestampMs < :beforeMs AND beatsPerMinute BETWEEN 30 AND 230
GROUP BY (timestampMs/60000)*60000, recordType, COALESCE(sessionId, '')   -- ← no device in GROUP BY
```

**Evidence.** The entity carries a `deviceName` column that the only writer sets to `NULL`, and
the primary key excludes it, so two devices contributing samples in the same minute are averaged
together irreversibly.

**Root cause.** The warm tier was designed around the assumption that ingestion is always
device-filtered. That assumption is false when the user has selected no device for
`HEART_RATE` (`deviceFor(HealthDataType.HEART_RATE)` returns `null` ⇒
`DeviceSourceFilter.filterToDevice` keeps everything).

**Impact.**
1. `SelectedSourcePruner` cannot prune warm data. A user who selects a device *after* rollup keeps
   the other device's contribution in every historical bucket, forever.
2. `getDistinctDeviceNames` (the Settings device picker) cannot see devices whose data is
   entirely warm — a device that stopped contributing >90 days ago disappears from the picker
   while its data still influences scores.
3. Two-device minutes are averaged, which is not a physiologically meaningful value.

**Recommended remediation.** Preserve provenance in the warm tier:
- Add `deviceName` to the primary key: `primaryKeys = ["bucketStartMs", "recordType",
  "sessionId", "deviceName"]` — requires `deviceName` to be non-null, so store `''` for unknown
  (SQLite PKs may contain NULL, which would break uniqueness; `''` is the safe encoding here,
  and is the one place `''` is correct even after `R2-ARCH-003`).
- Carry it through: `SELECT … , COALESCE(deviceName, '')` and add it to `GROUP BY`.
- Give `MinuteBucketDao` a `deleteBucketsNotMatchingDevice(fromMs, toMs, deviceName)` and call it
  from `SelectedSourcePrunerImpl`.
- Union warm device names into `getDistinctDeviceNames`.

**Migration.** Room v14 → v15. `hr_minute_buckets` must be recreated (PK change):
`CREATE TABLE hr_minute_buckets_new (…)`, `INSERT … SELECT …, ''`, `DROP`, `ALTER … RENAME`,
recreate the two indices. Existing buckets keep `deviceName = ''` (provenance genuinely unknown
for already-rolled data — accept and document); new rollups carry the real label. Storage impact:
one short TEXT column per bucket (~10–30 bytes × ~1,440 buckets/day/type) — negligible.
Rollback: v15 → v14 is not supported by Room; rollback = ship the previous APK, which reads the
new table fine only if the PK change is *additive* — it is not, so **this migration is
one-directional**; see §12.

**Dependencies.** Do with `R2-ARCH-003` (both touch `deviceName` encoding) in the same schema bump.
**Complexity.** M. **Migration risk.** Medium (table recreate on a table that can hold millions
of rows — must be batched or accepted as a one-time long migration; measure in §11).

**Acceptance criteria**
- Migration test (`room-testing` `MigrationTestHelper`) v14→v15 preserves row count and values.
- Two devices in one minute produce two buckets.
- `SelectedSourcePruner` removes warm buckets of non-selected devices in the pruned range.
- `getDistinctDeviceNames` includes a device present only in warm buckets.

---

#### `R2-DB-003` — Plausibility filtering is inconsistent across sibling `HeartRateDao` queries

| | |
|---|---|
| **Category** | Database / scoring consistency |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols** — all in `core/database-schema/.../dao/HeartRateDao.kt`:

| query | `beatsPerMinute BETWEEN 30 AND 230`? |
|---|---|
| `getAvgSleepHr(sessionId)` | **no** |
| `getAvgSleepHrForSessions(sessionIds)` | yes |
| `getAvgSleepHrPerSession(fromMs)` | **no** |
| `getSleepHrSamplesForSession(sessionId)` | yes |
| `getSleepHrSamplesForSessions(sessionIds)` | **no** |
| `getSleepHrProjectionForSessions(sessionIds)` | yes |
| `getSleepHrSampleCount(sessionId)` | **no** |
| `getSleepHrSampleAtOffset(sessionId, offset)` | **no** |
| `getMinHrTimestamp(sessionId)` | **no** |
| `getMinHrInRange(startMs, endMs)` | **no** |
| `getMinuteBuckets(dayStart, dayEnd)` | yes |
| `observeAggregateByTimeRange` | **no** |

**Current behavior.** The same physiological quantity (a session's sleeping HR) is computed with
and without the plausibility gate depending on which overload the caller reached.

**Evidence.** `DATA_FLOW.md:440-445` explicitly claims tier consistency for the filtered
subset — *"the warm rollup and the hot-path sleep-RHR reads apply the same predicate"* — and
names three methods. The unfiltered siblings are not covered by that claim, and the rollup
*does* filter, so any warm-tier value is filtered while its hot-tier counterpart from an
unfiltered query is not. That makes the tier inconsistency of `R2-DB-004` worse than it needs
to be.

**Root cause.** The filter was added query-by-query as call sites were optimized.

**Impact.** `getSleepHrSampleCount` + `getSleepHrSampleAtOffset` form an offset-percentile pair —
internally consistent (both unfiltered), but producing a different percentile from
`getSleepHrProjectionForSessions` (filtered) for the same session. Which one runs depends on the
code path, not on the data.

**Recommended remediation.** Define the predicate once and apply it to every query that feeds
scoring. Practical approach given Room's lack of SQL fragments: introduce named constants in the
DAO's KDoc and add the `AND beatsPerMinute BETWEEN 30 AND 230` clause to the eight unfiltered
scoring queries. Deliberately leave `observeAggregateByTimeRange` and
`observeSleepHrTimelineForSession` unfiltered **only if** the product decision is that the raw
timeline chart shows raw data — record that decision in `DATA_FLOW.md` (see §14 OD-3).

Also **verify and pin the sorted-input assumption**: `BaselineComputer.rhrHistory` indexes
`samples[index]` for its percentile and depends on `getSleepHrProjectionForSessions`'s
`ORDER BY sessionId ASC, beatsPerMinute ASC, …`. That ordering is present today; add a test that
fails if it is removed.

**Dependencies.** Must land with a recompute (`R2-CACHE-001`) since it changes historical values.
**Complexity.** S-M. **Migration risk.** Low code risk; user-visible score change.

**Acceptance criteria**
- A table-driven test enumerates every scoring-facing `HeartRateDao` query and asserts an
  implausible sample (e.g. 250 bpm) is excluded.
- Percentile computed via the batched path equals the percentile via the per-session path for the
  same fixture.

---

#### `R2-DB-004` — Scores are not reproducible across the hot→warm transition

| | |
|---|---|
| **Category** | Database / determinism |
| **Severity** | Medium-High |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `core/database/.../data/local/WarmTierReconstructor.kt:13-25`
- `core/database-schema/.../dao/MinuteBucketDao.kt:44-60`
- consumers: `ScoringHistoryRepositoryImpl`, `ScoringDayDataLoader.loadWorkoutSamples`

**Current behavior.** Reconstruction replays each bucket as `sampleCount` copies of
`round(avgBpm)`:

```kotlin
internal fun List<HrMinuteBucketEntity>.reconstructSampleValues(): List<Int> =
    flatMap { bucket -> List(bucket.sampleCount) { round(bucket.avgBpm).toInt() } }

internal fun List<HrMinuteBucketEntity>.reconstructTimestampedSamples(): List<Pair<Long, Int>> =
    flatMap { bucket ->
        val stepMs = if (bucket.sampleCount > 1) 60_000L / bucket.sampleCount else 0L
        List(bucket.sampleCount) { i -> bucket.bucketStartMs + (i * stepMs).coerceAtMost(59_999L) to round(bucket.avgBpm).toInt() }
    }
```

**Evidence.** A minute of real samples `[52, 54, 58, 61]` becomes four copies of `56`. Any
percentile, `MIN`, `MAX`, or variable-interval TRIMP integration over the reconstructed stream
differs from the same computation over the raw stream. `minBpm`/`maxBpm` *are* stored on the
bucket but reconstruction ignores them.

**Root cause.** Reconstruction was designed to keep downstream consumers unchanged (a flat sample
list), which necessarily discards within-minute distribution.

**Impact.** The repository's stated resync-idempotency property — *"a retry must re-run the same
range idempotently"* and the `ScoringSyncScopeOutputsDeterminismTest` guarantee — holds only
*within* a tier. A resync of a day that has since been rolled up produces different scores than
the original computation. This is arguably an acceptable engineering trade (that is the point of
a lossy warm tier), but it is **currently undocumented** and it is *not* what `AGENTS.md` claims.

**Recommended remediation.** Two parts, both required:
1. **Reduce the loss where it is cheap.** For percentile/min consumers, use the stored
   `minBpm`/`maxBpm` rather than replaying the mean: reconstruct each bucket as a 3-point
   summary `(minBpm, avgBpm, maxBpm)` weighted `(1, sampleCount − 2, 1)` when
   `sampleCount ≥ 3`. This preserves the nadir — which is precisely what
   `BaselineComputer`'s RHR percentile is looking for — at zero storage cost.
2. **Document the residual.** Add a "Determinism across tiers" subsection to
   `internal-docs/DATA_FLOW.md` §3-tier lifecycle and a sentence to `ABOUT.md` /
   `docs/about.md` stating that scores for days older than 90 days are recomputed from 1-minute
   aggregates and may differ marginally from their original values. Amend `AGENTS.md`'s
   idempotency bullet to say "idempotent *within a tier*".

**Open decision (§14 OD-1):** whether the 3-point reconstruction is acceptable, or whether the
product wants bit-exact historical reproducibility (which would require keeping raw samples —
i.e. abandoning the warm tier — or storing a per-minute percentile sketch).

**Dependencies.** Requires `R2-DB-001` first (the union), and must be measured with the §11
harness before/after. **Complexity.** M. **Migration risk.** Low code risk; changes historical
scores again — bundle with the single Phase-1 recompute.

**Acceptance criteria**
- Property test: for synthetic minutes, the 3-point reconstruction's p‑`n` percentile is closer to
  the raw percentile than the flat-mean reconstruction's, for all `n ∈ {5, 10, 25, 50}`.
- Documented drift bound is asserted by a test (e.g. sleep-RHR delta ≤ 1 bpm on the fixture set),
  and that bound is the number written into `DATA_FLOW.md`.

---

### 4.5 Performance

---

#### `R2-PERF-001` — Warm-tier reconstruction re-materializes every sample as a boxed object

| | |
|---|---|
| **Category** | Performance / memory |
| **Severity** | High |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `core/database/.../data/local/WarmTierReconstructor.kt:13-25`
- `ScoringHistoryRepositoryImpl.kt:56, :85`; `ScoringDayDataLoader.kt:74`

**Current behavior.** `reconstructSampleValues(): List<Int>` and
`reconstructTimestampedSamples(): List<Pair<Long, Int>>` both `flatMap` a bucket into
`sampleCount` elements. `List<Int>` in Kotlin is `List<java.lang.Integer>` — boxed.
`List<Pair<Long,Int>>` is worse: a `Pair` object plus a boxed `Long` plus a boxed `Int` per
sample.

**Evidence / arithmetic.** One 8-hour sleep session at 1 Hz = 28,800 samples. Boxed `Integer`
outside the `IntegerCache` range (HR > 127) is a fresh 16-byte object plus an 8-byte array slot
≈ 24 B/sample ≈ **0.7 MB per night**. `BaselineComputer.rhrHistoryBetween` runs over a
`ScoringConstants.BASELINE_DAYS = 30`-day window and `HRV_SIGMA_WINDOW_DAYS = 56` for sigma; a
full historical rebuild touches every retained night. At 30 warm nights that is **~21 MB of
transient boxed objects per baseline window**, re-allocated per recomputed day unless the
walk-forward context caches it. `reconstructTimestampedSamples` for a 90-minute warm workout at
1 Hz = 5,400 `Pair`s ≈ 0.26 MB, per workout, per recompute.

**Root cause.** The reconstruction API returns collections shaped for the old raw-sample
consumers instead of exposing the aggregate.

**Impact.** The warm tier's stated benefit is storage *and* bounded processing; it delivers
storage only. On the 10-year rebuild path this is the dominant allocation source and the most
likely GC-pressure/OOM contributor.

**Recommended remediation.** Two levels, both worthwhile:
1. **Cheap and immediate:** return primitive-backed containers — `IntArray` for values,
   `LongArray` + `IntArray` for timestamped samples — and change the three call sites. No
   behavior change; eliminates all boxing.
2. **Correct and larger:** stop reconstructing at all for the consumers that only need an
   aggregate. `getAvgSleepHrForSessions` already does this (weighted mean, no expansion). Give
   `BaselineComputer`'s percentile path a bucket-aware percentile that walks
   `(value, weight)` pairs — with `R2-DB-004`'s 3-point summary this is `3 × bucketCount`
   elements instead of `sampleCount`, i.e. **~1,440 × 3 = 4,320 elements per night instead of
   28,800**.

**Dependencies.** Do after `R2-DB-001` (same call sites) and design jointly with `R2-DB-004`
(the 3-point summary is what makes level 2 possible).
**Complexity.** M. **Migration risk.** None (internal function signatures; `internal` visibility
already limits blast radius to `core:database`).

**Acceptance criteria**
- No `List<Int>` / `List<Pair<Long, Int>>` remains in `WarmTierReconstructor`.
- §11 memory benchmark: peak allocation of a 30-day warm baseline window drops by ≥ 80 %.
- Scores unchanged for the level-1 change (bit-identical golden snapshots).

---

#### `R2-PERF-002` — Hot→warm rollup is one unbounded transaction

| | |
|---|---|
| **Category** | Performance / robustness |
| **Severity** | High |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `core/database/.../data/local/DataRollupManager.kt:24-29`
- `core/database-schema/.../dao/MinuteBucketDao.kt:44-60` (`rollupIntoBucketsBefore`),
  `HeartRateDao.kt` (`deleteBeforeTimestamp`)
- `app/.../workers/DataRollupWorker.kt:30`
- contrast: `core/database/.../data/local/RetentionCleanup.kt:39-46` (`deleteInBatches`, 10,000)

**Current behavior.**
```kotlin
suspend fun rollupExpiredHotTier(cutoffMs: Long): Int =
    transactionRunner.runInTransaction {
        minuteBucketDao.rollupIntoBucketsBefore(cutoffMs)   // unbounded INSERT…SELECT…GROUP BY
        heartRateDao.deleteBeforeTimestamp(cutoffMs)        // unbounded DELETE
    }
```
No `LIMIT`, no batching, no cursor. `RetentionCleanup` — the sibling operation on the same tables,
in the same package — was explicitly fixed for exactly this problem under `DB-002` and batches at
10,000 rows per transaction.

**Evidence.** `RetentionCleanup`'s own comment states the rationale: *"so a large first-time
cleanup opens many bounded transactions instead of one unbounded delete (WAL growth)."* The
rollup did not receive the same treatment.

**Impact.** The first rollup after a large historical backfill aggregates and deletes the entire
>90-day raw HR corpus in a single transaction. At the §7 scenario (≥ 1M records/30 days,
multi-year history) that is tens of millions of rows: unbounded WAL growth, a multi-minute
transaction holding the write lock against every foreground Flow, and — because
`DataRollupWorker` is a plain `CoroutineWorker` — a real chance of being killed and repeating the
whole thing on the next run. The `GROUP BY (timestampMs/60000)*60000, recordType, sessionId` also
cannot use `index_hr_v10_timestamp` for the grouping expression, so it materializes a large
temporary B-tree.

**Recommended remediation.** Batch by time window, not by row count, so each unit is a whole
number of minutes and the invariant "a minute is either raw or bucketed" is never violated
mid-flight:

```kotlin
suspend fun rollupExpiredHotTier(cutoffMs: Long): Int {
    var total = 0
    var windowStart = heartRateDao.minTimestampBefore(cutoffMs) ?: return 0
    while (windowStart < cutoffMs) {
        val windowEnd = minOf(windowStart + ROLLUP_WINDOW_MS, cutoffMs)   // e.g. 1 day
        total += transactionRunner.runInTransaction {
            minuteBucketDao.rollupIntoBucketsBetween(windowStart, windowEnd)
            heartRateDao.deleteBetweenTimestamps(windowStart, windowEnd)
        }
        windowStart = windowEnd
    }
    return total
}
```
Each day-sized unit is idempotent (`INSERT OR REPLACE` + range delete), so a killed worker
resumes correctly with no checkpoint state at all. Align `ROLLUP_WINDOW_MS` to UTC-minute
boundaries (the bucket key is `(timestampMs/60000)*60000`, so any day boundary in UTC works;
use UTC-midnight, not scoring-zone midnight, to match the bucket key arithmetic).

**Dependencies.** Independent, but must land before the §11 large-dataset benchmark to be
measurable. Pair with `R2-CACHE-001` so each rolled window enqueues its dates for recompute.
**Complexity.** M. **Migration risk.** Low — no schema change; new DAO queries only.

**Acceptance criteria**
- `DataRollupManager` opens ≥ `ceil(daysToRoll)` transactions, none containing more than one
  day's rows (assert via a counting `TransactionRunner` fake).
- Killing the rollup mid-loop and re-running produces the same final bucket set (idempotency
  test).
- §11 benchmark: peak WAL size during rollup of a 1M-row corpus stays bounded (target: WAL does
  not exceed the size produced by one day's rollup by more than 2×).

---

#### `R2-PERF-003` — `HeartRateDao.upsertAll` executes one statement per row

| | |
|---|---|
| **Category** | Performance |
| **Severity** | Medium-High |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `core/database-schema/.../dao/HeartRateDao.kt` (`upsertAll` default method looping
  `conflictTargetedUpsert`), and the equivalent in `HrvDao.kt`
- caller: `core/database/.../data/local/RoomHealthIngestionStore.kt`
  (`persistHeartRateSamples`)

**Current behavior.**
```kotlin
suspend fun upsertAll(records: List<HeartRateRecordEntity>) {
    for (record in records) { conflictTargetedUpsert(...) }     // one @Query call per row
}
```

**Evidence.** The conflict-targeted UPSERT itself is excellent (`R2` §3.3) — the problem is
purely the invocation shape. Each call is a separate Room `@Query` dispatch: argument binding,
statement acquisition, and a suspend-function hop per row.

**Impact.** At the §7 scenario a dense month is ~1M–2.6M HR rows; that is 1M–2.6M individual
statement executions per full ingest of that window, and the same again on every idempotent
re-ingest (the `WHERE` predicate makes each a `changes() = 0` no-op, but the dispatch cost is
still paid).

**Recommended remediation.** Batch the UPSERT into multi-row `VALUES` groups. Room supports this
via a `@RawQuery`-free approach if the batch size is fixed, but the practical and
maintainable route is a hand-written `SupportSQLiteStatement` loop inside one prepared statement:

```kotlin
// RoomHealthIngestionStore (core:database owns the SQLite detail; the DAO keeps its typed API)
private const val HR_UPSERT_BATCH = 500
// prepare once per batch, bind + executeInsert per row on the SAME compiled statement
```
This keeps the exact same SQL semantics (including the `DO UPDATE … WHERE` predicate) while
paying statement compilation once per batch instead of once per row. Measure first (§11):
if Room 2.8.4's statement caching already makes this a wash, record that and close the finding
as "measured, no action".

**Dependencies.** Measure in Phase 0 before implementing. **Complexity.** M.
**Migration risk.** Low, but this is the hottest write path in the app — requires the §11
before/after benchmark and the existing ingestion tests to be green.

**Acceptance criteria**
- §11 `database-benchmark` result: HR upsert throughput for 100k rows improves measurably, or the
  finding is closed with the measurement recorded in the plan.
- Idempotent re-ingest of the same 100k rows still results in zero row mutations.

---

#### `R2-PERF-004` — `HeartRateMapper` makes three full-page passes with boxed intermediates

| | |
|---|---|
| **Category** | Performance / allocation |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `core/model/.../domain/sync/mappers/HeartRateMapper.kt:12-38`
- `core/model/.../domain/sync/mappers/HrvMapper.kt:11-33`

**Current behavior.**
```kotlin
val allSamples = records
    .flatMap { record -> record.samples.map { sample -> record to sample } }   // 1: List<Pair<..,..>>
    .sortedBy { (_, sample) -> sample.time.toEpochMilli() }                    // 2: full copy + sort
return allSamples.map { (record, sample) -> HeartRateInput(...) }              // 3: full copy
```

**Evidence.** Three complete materializations of the page, one of them a `Pair`-boxed
intermediate, plus a `toEpochMilli()` call per element inside the sort comparator (so
`O(n log n)` conversions rather than `n`).

**Root cause.** `SessionLinkSweep` legitimately requires non-decreasing input, so *a* sort is
needed; the shape around it is what costs.

**Impact.** Per HC page, per ingest, on the hottest path. Not a correctness issue and not the
dominant cost (that is the DB write, `R2-PERF-003`), but it is allocation churn in exactly the
loop the §7 scenario runs a million times.

**Recommended remediation.** Single pass into a pre-sized `ArrayList`, sorting once on a
precomputed key:
- build `ArrayList<HeartRateInput>(records.sumOf { it.samples.size })` directly,
- compute `sampleMs` once per sample and keep it in the input (it already is `timestampMs`),
- sort the result list by `timestampMs` **before** resolving links, then run the sweep in place
  (`for (i in list.indices) { val link = sweep.resolve(list[i].timestampMs); list[i] = list[i].copy(...) }`)
  or, better, sort `(recordIndex, sampleIndex)` keys and emit in order.

Keep `SessionLinkSweep`'s contract (never pass a smaller `sampleMs` than a previous call) — it is
load-bearing and property-tested.

**Dependencies.** None. **Complexity.** S-M. **Migration risk.** Low; covered by
`SessionLinkSweepPropertyTest` and the ingestion tests.

**Acceptance criteria**
- Golden test: mapper output for a fixture page is element-identical (order and content) before
  and after.
- Allocation benchmark on a 5,000-sample page shows ≥ 50 % fewer allocated objects.

---

#### `R2-PERF-005` — Database provider does Keystore + file I/O synchronously in the Hilt graph

| | |
|---|---|
| **Category** | Performance / startup |
| **Severity** | Medium |
| **Confidence** | Medium |
| **Status** | suspected |

**Affected files and symbols**
- `core/database/.../di/DatabaseModule.kt:41-72` (`provideDatabase`)
- `core/database/.../data/security/SqlCipherKeyManager.kt`
  (`migrateIfNeeded`, `withCrossProcessKeyLock`, `System.loadLibrary("sqlcipher")` in `init`)
- `core/database/.../data/migration/DatabaseReadinessGate.kt`

**Current behavior.**
```kotlin
@Provides @Singleton
fun provideDatabase(context: Context, sqlCipherKeyManager: SqlCipherKeyManager,
                    databaseReadinessGate: DatabaseReadinessGate): HealthDatabase {
    val dbFile = context.getDatabasePath("health_dashboard.db")
    sqlCipherKeyManager.migrateIfNeeded(dbFile)      // file I/O + AES/Keystore, blocking
    requireDatabaseReady(databaseReadinessGate)      // throws IllegalStateException if not ready
    …
}
```
Plus `SqlCipherKeyManager.init { System.loadLibrary("sqlcipher") }` and a cross-process
`FileLock` acquired on the first helper access.

**Why suspected.** Whether this lands on the main thread depends entirely on which injection site
resolves `HealthDatabase` first. Workers use `Lazy<…>` and gate on `DatabaseReadiness`, which
suggests the hazard was already considered — and the earlier `PERFORMANCE_OPTIMIZATION_PLAN`
item **F13 ("Move SQLCipher key validation off the pre-frame main thread")** targeted exactly
this. I could not confirm from source whether F13 landed as a change to this provider or
elsewhere. **This must be measured, not assumed.**

**Impact if confirmed.** Blocking file I/O, an AES-GCM Keystore unwrap, a `FileLock`, and a
native library load on the critical startup path, before the first frame.

**Recommended remediation (only if measurement confirms).** Keep `provideDatabase` allocation-only
(Room's builder is already lazy — `build()` does not open the file); move `migrateIfNeeded` and
the readiness check into the existing `DatabaseMigrationWorker` / a `Startup` initializer on a
background dispatcher, and let `requireDatabaseReady` become a suspend gate rather than a
`check()` that throws out of a Hilt provider.

**Complexity.** M. **Migration risk.** Medium — startup ordering is load-bearing and
`KNOWN_ISSUE_sqlcipher_multiprocess_key_race.md` documents a real prior race here. Do not change
this without reading that note.

**Acceptance criteria**
- A macrobenchmark (`:benchmark`) startup trace shows no `migrateIfNeeded`/Keystore work on the
  main thread, or the finding is closed with the trace attached as evidence that it never was.

---

### 4.6 Scoring engine

Classification per the audit brief: *confirmed implementation bug* / *likely implementation bug* /
*documentation mismatch* / *scientific-validation concern* / *product decision requiring
confirmation* / *maintainability concern*.

---

#### `R2-SCORE-001` — Undocumented 5 bpm TRIMP dead-zone creates a discontinuity

| | |
|---|---|
| **Category** | Scoring — **documentation mismatch** |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `core/scoring/.../domain/scoring/RasCalculator.kt:36-42`
- `core/model/.../domain/scoring/ScoringConstants.kt` (no constant for it)
- `ABOUT.md`, `docs/about.md`, `internal-docs/DATA_FLOW.md` (no mention found)

**Current behavior.**
```kotlin
val hrr = hrMax - rhrBaseline
if (hrr <= 0) return 0f
val hrR = ((hrAvg - rhrBaseline) / hrr).coerceIn(0f, 1f)
if (hrAvg < (rhrBaseline + 5)) return 0f      // ← magic literal, undocumented
if (hrR <= 0) return 0f
```

**Evidence.** The formula is otherwise fully constant-driven and every non-obvious constant in
`ScoringConstants` carries a `REF` tag. This `5` is a bare literal in the hottest formula in the
app, and it applies to **all three** TRIMP models.

**Impact.** At `rhrBaseline + 4.99` bpm TRIMP is exactly 0; at `+5.0` it jumps to
`duration × hrR × a × e^(b·hrR)`. For a resting-HR baseline of 60 and hrMax 190, `hrR` at the
threshold is `5/130 ≈ 0.038`, so the jump is small in absolute TRIMP — but it is a genuine
discontinuity applied per minute in `EverydayHeartRateLoadCalculator`, where a user hovering
near the threshold gets a step change in daily everyday load. More importantly it is a scoring
rule the user-facing methodology documentation does not state.

**This is not proposed as a formula change.** The dead-zone is plausibly deliberate noise
rejection. The defect is that it is undocumented and unnamed.

**Recommended remediation.** Promote to `ScoringConstants.Trimp.MIN_HR_ABOVE_RHR_BPM = 5f` with a
comment recording the rationale, reference it in `RasCalculator`, and add the rule to
`ABOUT.md` / `docs/about.md` / `DATA_FLOW.md` (the documentation-drift tests
`domain/scoring/**DocumentationDriftTest*` should be extended to assert its presence).
**Open decision (§14 OD-4):** whether to keep the hard cutoff or replace it with a smooth ramp.
Recommended default: **keep as-is**, document it. Changing it changes every historical score.

**Complexity.** S (documentation + constant extraction only).
**Migration risk.** None if the value is unchanged.
**Acceptance criteria:** documentation-drift test asserts the constant appears in `ABOUT.md`;
golden scoring snapshots are bit-identical after the refactor.

---

#### `R2-SCORE-002` — `BaselineComputer` falls back to the device zone for its frozen-baseline lookup

| | |
|---|---|
| **Category** | Scoring — **likely implementation bug** |
| **Severity** | Medium |
| **Confidence** | Medium-High |
| **Status** | confirmed (code path), suspected (reachability) |

**Affected files and symbols**
- `core/scoring/.../domain/scoring/BaselineComputer.kt:130-136`
  ```kotlin
  val frozenSummary = scoringHistoryRepository.getDailySummaryByDate(
      fromMs,
      sleepDayPolicy?.scoringZoneId ?: ZoneId.systemDefault(),   // ← device zone fallback
  )
  ```
- `core/scoring/.../domain/scoring/HistoricalSleepDayAssembler.kt` (same pattern)

**Current behavior.** When `sleepDayPolicy` is null, the frozen-baseline freeze check resolves
its day in the *device* zone rather than the stored scoring zone.

**Evidence.** `DATA_FLOW.md` is explicit that *"Day boundaries (`dateMidnightMs`), affected-date
attribution, raw fetch windows, TRIMP bucketing, and persisted summary midnight all resolve
through the stored scoring timezone … not `ZoneId.systemDefault()`, so identical SQLite +
preferences reproduce identical scores across devices/timezones."* This call site is an
exception to that rule.

**Impact.** For a user whose device zone differs from the stored scoring zone (travel, or a
device-zone change after the scoring zone was seeded), the freeze check can read the *wrong day's*
summary and therefore recompute a baseline that should have stayed frozen, or skip recomputing
one that should have been refreshed. Reachability depends on how many call sites pass
`sleepDayPolicy = null` — that is the part I could not close by reading alone.

**Recommended remediation.** Make the zone a required parameter (no `systemDefault()` fallback
anywhere in `core:scoring`); thread `prefs.scoringZone()` from the callers. Add a konsist rule
banning `ZoneId.systemDefault()` in `core:scoring` and `core:database` production sources.

**Dependencies.** Same mechanism as `R2-SCORE-003`; do together.
**Complexity.** S-M. **Migration risk.** Low code risk; may change scores for affected users
(for the better).

**Acceptance criteria**
- `grep -rn "ZoneId.systemDefault()" core/scoring/src/main core/database/src/main` returns
  nothing; konsist rule enforces it.
- Test with device zone `UTC+13` and scoring zone `UTC-8` yields identical baselines to the
  same-zone case.

---

#### `R2-SCORE-003` — UI day attribution uses the device zone, not the stored scoring zone

| | |
|---|---|
| **Category** | Scoring — **confirmed implementation bug** (consistency) |
| **Severity** | Medium-High |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols.** 61 occurrences of `ZoneId.systemDefault()` across 40 production
files. The consequential ones — those computing a *date key* used to look up or bucket stored
scores:
- `feature/workouts/.../WorkoutDetailViewModel.kt:252-263` (`workoutDate`, `midnight`,
  `thirtyDaysAgo` → `dailySummaryRepository.getByDate(midnight)` / `getSince(thirtyDaysAgo)`)
- `feature/vitals/.../{weight/WeightDetailViewModel,bodyfat/BodyFatDetailViewModel,
  bloodpressure/BloodPressureDetailViewModel,steps/StepDetailViewModel,
  heartrate/HeartRateDetailViewModel}.kt`
- `feature/sleep/.../{SleepViewModel,SleepUiState}.kt`
- `feature/workouts/.../{WorkoutsStateFactory,WorkoutListSection,ResidualFatigueChartHelpers,
  ResidualFatigueAxis,ResidualFatigueCurveChart,WorkoutDetailHeader}.kt`
- `feature/dashboard/.../DashboardFlowIntermediate.kt`
- `core/ui/.../{ChartDefaults,ChartUtils,DayOffsetLabelCache,TimeRange,DateFormatUtils}.kt`
- `core/scoring/.../{BaselineComputer,HistoricalSleepDayAssembler}.kt` (see `R2-SCORE-002`)

Presentation-only formatting uses (`DateFormatUtils`, axis labels) are arguably correct in the
device zone and are explicitly **out of scope** — see the remediation split below.

**Current behavior.** Scores are computed and stored keyed on `dateMidnightMs` in
`prefs.scoringZone()`. The UI then computes its own midnight in `ZoneId.systemDefault()` and
looks up that key.

**Impact.** When the device zone differs from the stored scoring zone by enough to cross
midnight, `dailySummaryRepository.getByDate(midnight)` misses or returns the neighbouring day —
the workout detail screen shows the wrong day's summary, the 30-day RAS breakdown window is
offset by a day, and Vitals/Sleep detail screens bucket into the wrong day.

**Recommended remediation.** Split the concern explicitly:
1. **Date-key math must use the stored scoring zone.** Introduce a single injected
   `ScoringZoneProvider` (or thread `prefs.scoringZone()`, which every affected ViewModel already
   reads) and replace `ZoneId.systemDefault()` at every site that produces a value passed to a
   repository or used as a map key.
2. **Display formatting may keep the device zone**, but the choice must be explicit: rename the
   `core:ui` helpers to make it obvious (`formatForDisplayInDeviceZone(...)`) and document it in
   `DATA_FLOW.md`.
3. Add a konsist rule: no `ZoneId.systemDefault()` in `feature:*` ViewModels or in
   `core:scoring`/`core:database` production sources. `core:ui` formatting helpers are the
   allow-list.

**Dependencies.** Do with `R2-SCORE-002`. `R2-ARCH-001` touches the same ViewModel.
**Complexity.** M-L (mechanical but wide). **Migration risk.** Low — read-path only, no stored
data changes.

**Acceptance criteria**
- Konsist rule green.
- Robolectric/unit test per affected ViewModel: device zone `Pacific/Auckland`, scoring zone
  `America/Los_Angeles` ⇒ the summary shown for a given workout equals the summary the scoring
  engine persisted for that workout's scoring-zone date.

---

#### `R2-SCORE-004` — iTRIMP is implemented as a Banister-shaped curve without individualization

| | |
|---|---|
| **Category** | Scoring — **scientific-validation concern** (not a bug) |
| **Severity** | Low |
| **Confidence** | High |
| **Status** | confirmed (as a documentation/expectation issue) |

**Affected files and symbols**
- `core/scoring/.../domain/scoring/RasCalculator.kt:73-77`
- `ScoringConstants.Trimp.ITRIMP_B = 2.1f`
- `ABOUT.md` / `docs/about.md` TRIMP-model copy

**Current behavior.**
```kotlin
TrimpModel.I_TRIMP -> durationMinutes * hrR * exp(itrimB * hrR)   // no `a` coefficient
```
with `REF: Manzi et al. 2009`.

**Evidence.** Manzi's iTRIMP derives the exponential weighting factor from an *individual*
blood-lactate/HR profile. This implementation uses a fixed `b = 2.1` (user-adjustable via
`prefs.itrimB`) and omits the sex-specific `a` present in the Banister branch, so its output is
on a different scale from `BANISTER` and `CHENG`.

**Impact.** None on correctness — the model is internally consistent, the constant is
user-tunable, and cross-model comparison is not offered in the UI. The concern is purely that the
`REF` tag implies a fidelity the implementation does not claim.

**Recommended remediation.** Documentation only: state in `ScoringConstants`, `ABOUT.md` and
`docs/about.md` that this is a *Manzi-inspired* fixed-exponent variant with a user-tunable `b`,
not the individualized lactate-profile model. **Do not change the formula** — per the audit
brief, a heuristic is not a defect, and changing it would move every historical Load score for
users on this model.

**Complexity.** S. **Migration risk.** None.
**Acceptance criteria:** documentation-drift test covers the new wording.

---

#### `R2-SCORE-005` — `MathUtils` returns `0f` for empty inputs, conflating "no data" with "zero"

| | |
|---|---|
| **Category** | Scoring — **maintainability concern** |
| **Severity** | Low-Medium |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `core/scoring/.../domain/util/MathUtils.kt` (`mean`, `median` ×2, `stdev` ×2)
- consumer: `BaselineComputer.resolveBaselineRhrBpm` / `resolveBaselineRhrRounded`

**Current behavior.** `mean()`, `median()` return `0f` on an empty list; `stdev()` returns `0f`
for `size < 2`.

**Evidence.** `BaselineComputer` compensates at one call site —
`rhrValues.median().takeIf { it > 0f } ?: ScoringConstants.DEFAULT_RHR_BPM` — proving the sentinel
is known to be dangerous. `resolveBaselineRhrRounded` does **not** compensate:
`(rhrBaselineOverride ?: rhrValues.median()).roundToInt()` returns `0` for an empty history.

**Impact.** A `0` RHR baseline flowing into `RasCalculator.calculateDailyTrimp` gives
`hrr = hrMax - 0 = hrMax` and `hrR = hrAvg/hrMax`, silently inflating TRIMP rather than failing.
Reachability is gated by `CalibrationGate` (< 7 sessions ⇒ "Calibrating") so it is unlikely to
surface, which is why this is Low-Medium and not High — but the sentinel is a trap.

**Recommended remediation.** Make the emptiness explicit at the type level:
`fun List<Float>.meanOrNull(): Float?`, `medianOrNull()`, `stdevOrNull()`; keep the `0f` variants
only where a caller has proven the list is non-empty, or delete them. Update the two
`BaselineComputer` resolvers to `?: ScoringConstants.DEFAULT_RHR_BPM`.

**Complexity.** S. **Migration risk.** Low; compile-time surfaced.
**Acceptance criteria:** `resolveBaselineRhrRounded(emptyList(), null)` returns
`DEFAULT_RHR_BPM.roundToInt()`, not `0`; golden snapshots unchanged.

---

### 4.7 Incremental recalculation & cache correctness

---

#### `R2-CACHE-001` — Tier rollup and retention cleanup invalidate nothing

| | |
|---|---|
| **Category** | Cache / invalidation |
| **Severity** | High |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `app/.../workers/DataRollupWorker.kt`, `app/.../workers/DataCleanupWorker.kt`
- `core/database/.../data/local/DataRollupManager.kt`,
  `core/database/.../data/local/RetentionCleanup.kt`
- `core/database/.../data/repository/ScoringRepositoryImpl.kt` (the only recompute entry point)
- `core/database/.../domain/sync/DailyRecomputeSupport.kt`

**Current behavior.** Both workers mutate raw data and return. Neither clears a frozen baseline,
enqueues a recompute, nor records which dates were affected. `daily_summaries` rows derived from
the mutated raw data keep their pre-mutation values until some unrelated flow happens to
recompute that date.

**Evidence.** `RetentionCleanup.deleteBefore` deletes from `daily_summaries` too — so the summary
for a *deleted* day is removed. But the **rolling windows** that read across the cutoff are not
recomputed: `ScoringConstants.CHRONIC_DAYS = 42` and the 84-day TRIMP history fetch mean a
surviving day within 42–84 days of the cutoff had its inputs truncated and its score is now
stale-but-present. Likewise rollup changes what every future recompute of a pre-90-day date will
produce (`R2-DB-004`) without touching the already-stored value.

**Root cause.** There is no dependency model tying "raw rows for date D changed" to "recompute
dates D..D+windowLength". The two sync flows compute affected dates inline
(`HealthChangeSynchronizer.affectedDates`, then widen to a contiguous walk-forward), but that
logic lives inside `DailySyncUseCase` and is unavailable to the workers.

**Impact.** Two observable inconsistencies:
1. After retention cleanup, ATL/CTL/strain-ratio for the ~42 days above the cutoff are computed
   from a window that no longer has its full history, but the *stored* values still reflect the
   old full window. Scrolling to those days shows numbers the engine would no longer produce.
2. After rollup, the app's own determinism tests would disagree with the stored values.

**Recommended remediation.** Introduce an explicit, minimal invalidation model — the smallest
thing that closes the gap without inventing infrastructure:

```kotlin
// core/model/.../domain/sync/ScoreInvalidation.kt   (pure)
object ScoreInvalidation {
    /** Longest rolling window any stored score depends on, in days. */
    const val MAX_DEPENDENT_WINDOW_DAYS = 84       // CHRONIC_DAYS(42) < TRIMP history(84)

    /** Dates whose stored summary must be recomputed when raw data in [changed] is mutated. */
    fun affectedRange(changed: ClosedRange<LocalDate>, today: LocalDate): ClosedRange<LocalDate> =
        changed.start .. minOf(changed.endInclusive.plusDays(MAX_DEPENDENT_WINDOW_DAYS.toLong()), today)
}
```
Then:
- `DataRollupManager` and `RetentionCleanup` return the date range they touched.
- The two workers enqueue a **recompute-only** resync for `ScoreInvalidation.affectedRange(...)`
  via the *existing* `HealthResyncWorker` path with `KEY_RECOMPUTE_ONLY = true` (the mechanism
  already exists for `SCORE-007`, `ExistingWorkPolicy.APPEND_OR_REPLACE`) — no new progress
  channel, no new worker, no scoring change.
- Because that path runs under `syncMutex` and is checkpoint-resumable, correctness and
  cancellation behavior come for free.

Document `MAX_DEPENDENT_WINDOW_DAYS` as the single place the dependency depth is stated, and add
a test that fails if any scoring lookback constant exceeds it.

**Dependencies.** Requires `R2-DI-002` (pinnable clock) for testing; pairs with `R2-DB-001`,
`R2-DB-004`, `R2-PERF-002`. **Complexity.** M. **Migration risk.** Medium — this makes two
previously-silent workers enqueue real recompute work; must respect battery/charging constraints
and must not fire on every cleanup run when nothing was deleted (return an empty range ⇒ no
enqueue).

**Acceptance criteria**
- Rollup of N days enqueues exactly one recompute-only resync covering
  `[rolledStart, min(rolledEnd + 84, today)]`, and none when nothing was rolled.
- Same for cleanup.
- Test: stored ATL/CTL for a day 30 days above the retention cutoff equals a freshly computed
  value after cleanup + the enqueued recompute.
- A test enumerates every scoring lookback constant and asserts
  `≤ MAX_DEPENDENT_WINDOW_DAYS`.

---

#### `R2-CACHE-002` — The daily walk-forward is one all-or-nothing transaction with no checkpoint

| | |
|---|---|
| **Category** | Cache / robustness |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `core/healthconnect/.../domain/sync/DailySyncUseCase.kt:239-286` (`inRecomputeTransaction { … }`)
- contrast: `ResyncRangeUseCase.kt:456-525` (30-day units, checkpoint after commit)

**Current behavior.** The daily flow wraps `clearFrozenBaselines` plus the entire walk-forward —
up to `windowDays + MAX_INLINE_RECOMPUTE_DAYS` days, so up to 8 days in practice — in one
transaction, with no checkpoint. Its own comment states: *"Cancellation does roll the window
back, which is fine: the next sync redoes the same idempotent range."*

**Impact.** Correct, but it means a user who backgrounds the app mid-sync loses the whole
window's recompute and must wait for a full redo, including the `clearFrozenBaselines` write.
The resync path solved exactly this with the transaction-boundary == checkpoint-boundary design;
the daily path did not adopt it. The window is small (≤ 8 days) so severity is Medium, not High.

**Recommended remediation.** Reuse the resync's proven shape at a smaller unit: split the daily
walk-forward into per-day transactions with the same "commit, then advance" discipline. The
`clearFrozenBaselines` call must stay in the *first* unit's transaction. Given the window is
≤ 8 days, the invalidation-storm rationale for `F7` (one Flow invalidation round per sync instead
of one per day) still argues for keeping it as one transaction — so this is a genuine trade.

**Open decision (§14 OD-5):** keep the single transaction (fewer Flow invalidations, all-or-nothing)
or split per day (resumable, up to 8 invalidation rounds). Recommended default: **keep as-is** and
close this finding as "accepted trade, documented", unless the §11 measurement shows the
all-or-nothing redo is user-visible.

**Complexity.** S (documentation) or M (split). **Migration risk.** Low.
**Acceptance criteria:** the trade is recorded in `DATA_FLOW.md` with the measured redo cost, or
the split lands with per-day checkpointing and the Flow-invalidation count measured in §11.

---

### 4.8 Compose / UI architecture

---

#### `R2-UI-001` — `WorkoutDetailViewModel.loadWorkout` is a single 80-line orchestration body

| | |
|---|---|
| **Category** | UI architecture / maintainability |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `feature/workouts/.../WorkoutDetailViewModel.kt:207-300` (`loadWorkout`), file is 442 lines

**Current behavior.** One coroutine body performs: repository load, route-permission check and
conditional route sync, Health Connect read (`R2-ARCH-001`), Room read, sample merge, chart
mapping, `endHr` derivation, day-key math (`R2-SCORE-003`), daily-summary load, 30-day summary
load, RAS breakdown mapping, recovery-metrics mapping, and display-metrics computation.

**Impact.** Untestable in pieces; every one of the three other findings that touch this file
(`R2-ARCH-001`, `R2-SCORE-003`, `R2-UI-002`) has to edit the same method.

**Recommended remediation.** Extract a `WorkoutDetailLoader` (or a set of small use-cases) that
returns a single immutable `WorkoutDetailData`, leaving the ViewModel to map that into UI state.
The repository already has the pattern: `feature/vitals/.../VitalsStateFactory`,
`feature/workouts/.../WorkoutsStateFactory`, `feature/dashboard/.../DashboardMetricPresentationFactory`.
Follow it rather than inventing a new shape.

**Dependencies.** Do this **first** among the four findings touching this file, so the others
edit small units. **Complexity.** M. **Migration risk.** Low — covered by the existing
994-line `WorkoutDetailViewModelTest`.

**Acceptance criteria**
- `WorkoutDetailViewModel.kt` ≤ 400 lines (repo target).
- `loadWorkout` contains no data access beyond one loader call plus state mapping.
- `WorkoutDetailViewModelTest` passes unmodified (or with mechanical constructor updates only).

---

#### `R2-UI-002` — UI heart-rate reads have no warm-tier fallback: charts go blank after 90 days

| | |
|---|---|
| **Category** | UI / data completeness |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `core/database-schema/.../dao/HeartRateDao.kt` — `_observeSleepHrTimelineForSession`,
  `getByTimeRange`, `observeByTimeRange`, `observeAggregateByTimeRange`, `getMinHrInRange`
  (none consult `hr_minute_buckets`)
- `core/database/.../data/repository/HeartRateRepositoryImpl.kt:23-26`
- consumers: `feature/sleep/.../SleepHrChart.kt`, `feature/vitals/.../heartrate/HrTimelineChart.kt`,
  `feature/workouts/.../WorkoutDetailViewModel.kt`

**Current behavior.** The scoring reads got tier awareness (`ScoringHistoryRepositoryImpl`,
`ScoringDayDataLoader`); the UI reads did not.

**Evidence.** `DATA_FLOW.md:444` acknowledges `observeSleepHrTimelineForSession` specifically in
the tier discussion, but the query itself has no bucket union.

**Impact.** Once `DataRollupWorker` has run, the sleep HR timeline chart, the vitals HR timeline
and the workout HR chart are empty (or, for a straddling session, partial — see `R2-DB-001`) for
any date older than 90 days, even though the data exists in `hr_minute_buckets` at 1-minute
resolution. This is also *why* `R2-ARCH-001`'s Health Connect escape hatch exists.

**Recommended remediation.** Add tier-aware read methods to `HeartRateRepositoryImpl` mirroring
what `ScoringHistoryRepositoryImpl` does, returning a `HeartRateSeries` that carries its own
resolution so the chart can label it ("1-minute averages"). For the observable timeline, combine
the two DAO `Flow`s with `kotlinx.coroutines.flow.combine` and `distinctUntilChanged`.

**Dependencies.** Enables `R2-ARCH-001`'s clean fix. Depends on `R2-DB-001`'s union semantics.
**Complexity.** M. **Migration risk.** Low — read path only.

**Acceptance criteria**
- After a rollup, the sleep HR chart for a 120-day-old session renders 1-minute points.
- The chart surfaces the reduced resolution to the user (string in `strings.xml`).

---

### 4.9 Security & privacy

---

#### `R2-SEC-001` — Exported diagnostic logs are written to cache in plaintext and never deleted

| | |
|---|---|
| **Category** | Privacy — **privacy risk / hardening** |
| **Severity** | Medium |
| **Confidence** | High |
| **Status** | confirmed |

**Affected files and symbols**
- `app/.../crashreport/DiagnosticLogFileExport.kt` (whole file)
- `app/.../MainActivity.kt:216-236` (`sendMigrationDiagnostics`)
- `app/.../util/SecureFileLogSink.kt` (the encrypted-at-rest sink this bypasses)
- `app/src/main/res/xml/file_paths.xml` (`cache-path name="diagnostic_logs"`)

**Current behavior.**
```kotlin
fun write(directory: File, text: String): File {
    val targetDir = File(directory, DIAGNOSTIC_LOG_DIR).apply { mkdirs() }
    val file = File.createTempFile(FILE_PREFIX, ".txt", targetDir)   // new file every time
    file.writeText(text)
    return file
}
```
`MainActivity` calls `secureLogSink.readLogsDecrypted()`, writes the decrypted text to
`cacheDir/diagnostic_logs/`, and shares it via `FileProvider`. Nothing ever deletes it.

**Evidence.** `createTempFile` guarantees a *new* file per invocation, so repeated diagnostics
exports accumulate. The `SecureFileLogSink` exists precisely so these logs are encrypted at rest;
the export defeats that for an unbounded, growing set of copies. The same accumulation pattern
applies to `crash_reports/` and `logcat_capture/` (also `cache-path` entries).

**Assessment.** This is a **privacy risk**, not a vulnerability: the files are in the app's
private `cacheDir`, unreadable by other apps on a non-rooted device, and the `FileProvider`
grants are scoped. The risk is (a) plaintext health/diagnostic data persisting indefinitely,
(b) surviving in a device backup path unless excluded (verify against
`data_extraction_rules.xml` / `full_backup_content.xml`), and (c) growing without bound.

**Recommended remediation.**
- Delete the exported file once the share `Intent` resolves (or on next app start), and prune the
  three cache directories to the newest N files / M hours on startup.
- Reuse a single fixed filename instead of `createTempFile` so at most one copy exists.
- Confirm `diagnostic_logs`, `crash_reports` and `logcat_capture` are excluded in
  `data_extraction_rules.xml` and `full_backup_content.xml` (with `allowBackup="false"` this is
  belt-and-braces, but D2D transfer rules still apply).
- Update `docs/privacy.md` if the retention behavior described there does not match.

**Complexity.** S. **Migration risk.** None.
**Acceptance criteria**
- After three consecutive exports, `cacheDir/diagnostic_logs` contains ≤ 1 file.
- A startup prune test asserts files older than the retention bound are removed.
- `docs/privacy.md` and `docs/backup-and-data.md` reflect the actual behavior.

---

#### `R2-SEC-002` — SQLCipher password array is retained for the helper's lifetime (accepted)

| | |
|---|---|
| **Category** | Security — **optional defense in depth** |
| **Severity** | Low |
| **Confidence** | High |
| **Status** | confirmed, **no action recommended** |

`SqlCipherKeyManager`'s KDoc already documents this precisely: transient plaintext byte arrays
are zeroed with `.fill(0)`, but *"the raw SQLCipher password array is intentionally retained by
SQLCipher's helper for the helper's lifecycle … and therefore cannot be zeroed here."* That is a
property of the library, not a defect here. Recorded so a future audit does not re-raise it.
No threat model in this repository justifies further hardening (and per the audit brief, root
detection and certificate pinning are explicitly **not** recommended — the app makes no network
calls of consequence and ships `network_security_config.xml`).

---

## 5. Scoring and Metric Verification Matrix

"Review result" is one of: **OK** (implementation matches documentation and is internally
consistent), **DOC** (documentation mismatch), **BUG** (implementation defect),
**DET** (determinism/consistency defect), **VAL** (scientific-validation note only).

| Metric / score | Implementation | Source inputs | Documented / intended rule | Implemented behavior | Result | Findings | Action |
|---|---|---|---|---|---|---|---|
| **Max HR (Tanaka)** | `HeartRateFormulas.estimateMaxHr` | `prefs.age` | `208 − 0.7 × age` (Tanaka 2001) | `(208 - 0.7 * ageYears).toInt()` — truncates, does not round | OK | — | none (truncation ≤ 1 bpm, consistent) |
| **Effective hrMax** | `HeartRateFormulas.resolveMaxHeartRate` | `prefs.autoCalculateMaxHr`, `age`, `maxHeartRate` | auto ⇒ Tanaka, else manual | as documented; frozen per day in `daily_summaries.hrMax` | OK | — | none |
| **TRIMP — Banister** | `RasCalculator.calculateDailyTrimp` | duration, `hrAvg`, `rhrBaseline`, `hrMax`, `gender`, `banisterMultiplier` | `t × ΔHRratio × a × e^(b·ΔHRratio)`, a/b = 0.64/1.92 ♂, 0.86/1.67 ♀ | exactly that, × `banisterMultiplier`; `hrR` clamped `[0,1]` | OK | — | none |
| **TRIMP — dead zone** | same, line 40 | `hrAvg`, `rhrBaseline` | *not documented anywhere* | `if (hrAvg < rhrBaseline + 5) return 0f` — all three models | **DOC** | `R2-SCORE-001` | extract constant + document; do not change value |
| **TRIMP — Cheng (LT)** | same, lines 51-70 | + `ltBpm = prefs.zone3MaxBpm` | piecewise on HR vs LT, continuous at HR=LT with weight 0.5 | verified continuous: lower branch at `hrAvg=lt` ⇒ `0.5·(lt−rhr)/(lt−rhr)=0.5`; upper at `f=0` ⇒ `0.5+a·0·e⁰=0.5` | OK | — | none |
| **TRIMP — Cheng guard** | same, line 55 | `ltBpm` | no fallback if LT unknown | `if (ltBpm <= 0f) return 0f` — silently zero, not "unavailable" | OK (documented "no fallback") | — | none |
| **TRIMP — iTRIMP** | same, lines 72-77 | `itrimB` (default 2.1) | `REF: Manzi 2009` | `t × hrR × e^(2.1·hrR)`; no `a`, no lactate individualization | **VAL** | `R2-SCORE-004` | documentation only |
| **Workout TRIMP integration** | `ComputeWorkoutTrimpUseCase.integrateSamples` | exercise HR samples, workout bounds | variable-interval integration over the session | leading segment `[start, first)` at first sample's bpm, then `[sᵢ, sᵢ₊₁)` at `sᵢ`'s bpm, last `[sₗ, end)` — **verified no double counting; total covered time = end − start** | OK | — | none |
| **Workout TRIMP, no samples** | `computeWithoutSamples` | `workoutAvgHr`, duration | single-block TRIMP | as documented; `duration ≤ 0 ⇒ 0f` (guards reversed/equal backup timestamps) | OK | — | none |
| **Workout TRIMP inputs (warm)** | `ScoringDayDataLoader.loadWorkoutSamples` | raw HR **or** buckets | union of tiers | `if (hot.isNotEmpty()) return hot` — truncates a straddling workout | **BUG** | `R2-DB-001` | union both tiers |
| **Workout TRIMP inputs (reconstruction)** | `reconstructTimestampedSamples` | buckets | replay a bucket as samples | `sampleCount` copies of `round(avgBpm)` at evenly spaced offsets; min/max discarded | **DET** | `R2-DB-004`, `R2-PERF-001` | 3-point summary + document drift |
| **Everyday-HR TRIMP** | `EverydayHeartRateLoadCalculator.calculate` | merged 1-min buckets, sleep/workout intervals | per-minute TRIMP over non-sleep, non-workout, zone > 0 buckets | as documented; `hrBuckets` pre-filtered 30–230 in SQL; ascending order preserved for FP-stable `+=` | OK | — | none |
| **Everyday-HR tier merge** | `ScoringDayDataLoader.loadMergedMinuteBuckets` | hot + warm | weighted per-minute union | **correct** — the only tier read that unions | OK | — | reference implementation for `R2-DB-001` |
| **Daily RAS** | `RasCalculator.calculateDailyRas` | dailyTrimp, `rasScalingFactor` | `trimp × factor`, capped at `DAILY_CAP = 75` | as documented | OK | — | none |
| **RAS scaling default** | `getDefaultRasScalingFactor` | `physiologyProfile` | ATHLETE .15 / ACTIVE .18 / SEDENTARY .25 | as documented | OK | — | none |
| **Acute load (ATL)** | `ComputeDailyTrimpUseCase` + `TrimpDateBucketer` | 7-day TRIMP | `ACUTE_DAYS = 7` avg | as documented; bucketed in stored scoring zone; 84-day fetch start derived calendar-wise (DST-correct) | OK | — | none |
| **Chronic load (CTL)** | same | 42-day TRIMP | `CHRONIC_DAYS = 42` avg | as documented | OK | — | none |
| **Strain ratio** | `ScoringConstants.Strain` | ATL/CTL | sweet spot ≤ 1.3, quadratic decay `k = 2.5`, no floor | as documented (`REF: Gabbett 2016`) | OK | — | none |
| **RHR baseline (percentile)** | `BaselineComputer.rhrHistoryBetween` → `HistoricalSleepDayAssembler` | sleep sessions + sleep HR | intra-session adaptive percentile over a 30-day window, median of nadirs | as documented **but** inputs truncated for straddling sessions, and filtered/unfiltered depending on the query overload | **BUG** | `R2-DB-001`, `R2-DB-003` | union tiers; unify the plausibility predicate |
| **RHR percentile index** | `BaselineComputer.rhrHistory:71` | `getSleepHrProjectionForSessions` | index into a bpm-sorted list | correct **only because** the DAO orders `beatsPerMinute ASC` — undefended | OK (fragile) | `R2-DB-003` | add an ordering-regression test |
| **RHR baseline resolve** | `resolveBaselineRhrBpm` / `resolveBaselineRhrRounded` | override, median, default 60 | override → median → `DEFAULT_RHR_BPM` | `resolveBaselineRhrRounded` omits the `takeIf { it > 0f }` guard ⇒ returns `0` on empty | **BUG** (latent) | `R2-SCORE-005` | nullable math helpers |
| **Frozen baseline gate** | `computeAdaptiveBaselineRhrBpmBetween` | `daily_summaries.baselineCalculatedAtDate` | frozen ⇒ return null ⇒ caller uses stored | as documented, **but** day resolved with `ZoneId.systemDefault()` when `sleepDayPolicy == null` | **BUG** | `R2-SCORE-002` | require the scoring zone |
| **HRV μ / σ windows** | `BaselineComputer.computeHrvWindows` | nightly RMSSD | μ 7 d, σ 56 d, blend n 7→60 | as documented (`REF: Plews 2013`, `Buchheit 2014`) | OK | — | none |
| **HRV score saturation** | `ScoringConstants` | z(lnHRV) | saturate at z = 1.5, slope 0.25 | as documented | OK | — | none |
| **ln-σ floor** | `Restoration.MIN_LN_SIGMA` | HRV σ | 0.04 floor | as documented | OK | — | none |
| **Sleep score weights** | `ComputeSleepMetricsUseCase`, `SleepScoringStrategy` | stage/duration/efficiency/WASO | Balanced: Duration 40 %, Architecture 20 %, Restoration 25 %, Fragmentation 15 %; 5 selectable profiles | as documented | OK | — | none |
| **Regularity multiplier** | `Sleep.REGULARITY_{FLOOR,SPAN}` | circadian consistency | penalty-only 0.92–1.00 | as documented | OK | — | none |
| **Sleep duration curve** | `Sleep.DURATION_*`, `HYPERSOMNIA_*` | TST / goal | logistic below goal (normalized so ratio 1.0 = 100), flat dead zone, Gaussian above | as documented | OK | — | none |
| **Sleep efficiency** | `Sleep.EFFICIENCY_{MIDPOINT,SLOPE}` | SE % | continuous logistic, midpoint 77.5, slope 0.18 | as documented | OK | — | none |
| **Fragmentation** | `SleepFragmentationCalculator` | WASO, awakenings ≥ 90 s | grace 20 min / 2 events, decay 0.010·min / 0.08·event | as documented | OK | — | none |
| **Stage plausibility** | `ScoringConstants` | stage fractions | deep ≤ .40, REM ≤ .45, deep+REM ≤ .70 | as documented; drives `stagesSuspicious` | OK | — | none |
| **Late nadir penalty** | `Restoration.LATE_NADIR_*` | nadir position | ×0.95 when nadir in the last third (0.67) | as documented (`REF: Trinder 2001`) | OK | — | none |
| **Nightly avg sleep HR** | `getAvgSleepHr` vs `getAvgSleepHrForSessions` | sleep HR | one value per session | single-session query is **unfiltered**, batched query filters 30–230 | **BUG** | `R2-DB-003` | unify |
| **Readiness composition** | `CompositeScoringCalculator` | Restoration/Sleep/Load | 0.4 / 0.3 / 0.3 | as documented | OK | — | none |
| **Recovery flags** | `RecoveryFlagEvaluator`, `Readiness.*` | z(HRV), z(RHR), ΔRHR | strong recovery z>1.5 ∧ z<−2.0; illness z<−1.5 ∧ ΔRHR ≥ 5 ⇒ cap 50 | as documented (`REF: Le Meur 2013`, `Mishra 2020`) | OK | — | none |
| **Calibration gate** | `CalibrationGate` | sleep sessions in 42 d | < 7 ⇒ raw measurements only | as documented (`MIN_SESSIONS_FOR_CALIBRATION = 7`) | OK | — | none |
| **Residual fatigue** | `ComputeResidualFatigueUseCase`, `ResidualFatigueComputer`, `WalkForwardFatigueContext` | workout impulses, half-life, gain | exponential decay from canonical workout TRIMP; `null` when a retained workout was never backfilled | covered by the four 2026-08-29/31 plans; `seedIncomplete` handling verified present | OK | — | none (prior round) |
| **Step count** | `StepCountFetcher` + `recomputeDay(steps)` | HC aggregate reads | `null` ⇒ preserve stored | daily flow passes `null`; resync passes `?: 0L` when a device is selected | **DET** | `R2-HC-004` | one shared resolver + OD-2 |
| **Weekly training stats** | `ComputeWeeklyTrainingStatsUseCase` | workouts, start-of-week pref | per-week aggregation | not re-audited (landed #249/#251/#252/#253, tests present) | — | — | none |
| **Insights** | `InsightEngine` + 13 rules | daily summaries | per-rule thresholds in `InsightConstants` | not re-audited beyond the HRV-missing fix (#254) | — | — | none |

**Cross-cutting scoring note.** No arithmetic error was found in any formula. Every scoring
finding above is about *which inputs reach the formula* (tier truncation, filter inconsistency,
zone attribution) or *about the documentation of a rule*, not about the mathematics.

---

## 6. Health Connect Ingestion Matrix

Read strategy is the bulk path unless noted; every type additionally flows through the Changes
API path in `HealthChangeSynchronizerImpl`. Client version: `connect-client` **1.1.0**.

| HC record type | `HealthDataType` | Bulk read | Paging / batching | Dedup key (storage) | Update behavior | Deletion behavior | Persistence target | Recalc trigger | Performance risks | Findings |
|---|---|---|---|---|---|---|---|---|---|---|
| `SleepSessionRecord` | `SLEEP` | `readSleepSessions` (full materialize) | `readAllPages` (`pageToken` loop) | `sleep_sessions.id` = HC id | `UpsertionChange` ⇒ delete-then-upsert; stages deleted per session then re-inserted | `DeletionChange` ⇒ `deleteById`, stages cascade (FK) | `sleep_sessions` + `sleep_stages` | affected dates = session start..end (scoring zone) | bounded volume; whole-window retry | `R2-HC-001` |
| `SleepSessionRecord.stages` | (with `SLEEP`) | derived via `SleepDataMapper.mapSleepSessionStages` | n/a | `(sessionId, startTime)` unique | replaced with the parent | FK `CASCADE` | `sleep_stages` | with parent | — | — |
| `HeartRateRecord` | `HEART_RATE` | **streamed** `readHeartRateSamplesPaged` | per HC page; persisted per page | `health_source_records.sourceRecordId` (HC id) → `sourceRecordRef`; row unique `(sourceRecordRef, timestampMs)` | conflict-targeted UPSERT (updates `recordType`/`sessionId`/`deviceName`, preserves `rowId`) | `deleteBySourceRecordRef` + `deleteBySourceRecordId`; also FK `CASCADE` | `heart_rate_records`, then `hr_minute_buckets` after 90 d | walk-forward over affected dates | **highest volume**; whole-window retry; one statement per row; boxed mapper intermediates; rollup unbounded | `R2-HC-002`, `R2-HC-003`, `R2-PERF-001/2/3/4`, `R2-DB-001/2/4` |
| `HeartRateVariabilityRmssdRecord` | `HRV` | **streamed** `readHrvSamplesPaged` | per HC page | same source-ref scheme, unique `(sourceRecordRef, timestampMs)` | conflict-targeted UPSERT | `deleteBySourceRecordRef` | `hrv_records` (**no warm tier**) | walk-forward | high volume; **HRV has no rollup**, so retention is its only bound | `R2-HC-002`, OD-6 |
| `ExerciseSessionRecord` | `EXERCISE` | `readExerciseSessions(includeDetails = true)` | `readAllPages` | `workout_records.id` = HC id | delete-then-upsert; `modelTrimp`, distance/speed/elevation and `routeState = IMPORTED` preserved from the existing row | `deleteById`; route points cascade | `workout_records` (+ `workout_route_points`) | reconcile then walk-forward | metrics computed twice (ingest zeros, then reconcile) by design (`HC-004`) | `R2-HC-001` |
| `ExerciseRouteResult` | (with `EXERCISE`) | via `record.exerciseRouteResult` / `syncWorkoutRouteUseCase` | n/a | `(workoutId, timestampMs)` index, autogen PK | replaced with parent unless `IMPORTED` is being preserved | FK `CASCADE` | `workout_route_points` | none (display only) | permission-gated (`READ_EXERCISE_ROUTES`) | — |
| `DistanceRecord` | (with `EXERCISE`) | `readIntervalTotals` bulk / `sessionTotalFor<DistanceRecord>` per session in the changes path | `pageToken` loop per session | not stored raw — folded into `workout_records.totalDistanceMeters` | recomputed with the session | n/a | `workout_records` | with parent | **changes path does a full paged read per session** | `R2-HC-003` (same class of issue) |
| `ElevationGainedRecord` | (with `EXERCISE`) | as above | as above | folded into `elevationGainMeters` | as above | n/a | `workout_records` | with parent | as above | — |
| `StepsRecord` | `STEPS` | `readStepsRecords` (raw, **not** device-filtered by design) **plus** `StepCountFetcher` aggregate/device-filtered reads for the visible total | `readAllPages` | `step_records.id` = raw HC id | upsert | `deleteById` (date range resolved from the stored row **before** the delete) | `step_records` (deletion-support only); daily total → `daily_summaries.stepCount` | affected dates | raw rows stored purely to resolve deletions (`HC-005`) | `R2-HC-004` |
| `WeightRecord` | `WEIGHT` | `readWeightRecords` | `readAllPages` | `weight_records.id` = `"<hcId>_<timeMs>"` | delete-by-prefix then upsert (changes path); **bulk path only upserts** | `deleteBySourceRecordId(hcId)` (prefix match) | `weight_records` | affected date | a retimed record leaves a stale row if only the bulk path runs | `R2-HC-001`, `R2-ARCH-003` |
| `BodyFatRecord` | `BODY_FAT` | `readBodyFatRecords` | `readAllPages` | `"<hcId>_<timeMs>"` | as above | as above | `body_fat_records` | affected date | as above | as above |
| `BloodPressureRecord` | `BLOOD_PRESSURE` | `readBloodPressureRecords` | `readAllPages` | `"<hcId>_<timeMs>"` | as above | as above | `blood_pressure_records` | affected date | as above | as above |
| `OxygenSaturationRecord` | `OXYGEN_SATURATION` | `readOxygenSaturationRecords` | `readAllPages` | `"<hcId>_<timeMs>"` | as above | as above | `oxygen_saturation_records` | affected date (`avgSleepingSpo2`) | as above | as above |
| `BodyTemperatureRecord` | `BODY_TEMPERATURE` | `readBodyTemperatureRecords` | `readAllPages` | `"<hcId>_<timeMs>"` | as above | as above | `body_temperature_records` | affected date (`avgSleepingBodyTemp`, display only) | as above | as above |

### Cross-cutting ingestion behavior

| Concern | Implementation | Assessment |
|---|---|---|
| **Permissions** | declared in `AndroidManifest.xml` (14 health read permissions incl. `READ_HEALTH_DATA_HISTORY`, `READ_HEALTH_DATA_IN_BACKGROUND`, `READ_EXERCISE_ROUTES`); checked before queries; optional types degrade rather than fail (`asHealthConnectSecurityCause()`) | OK |
| **Permission revocation** | `HealthConnectPermissionRevokedException` rethrown (not flattened) so `ForegroundSyncController` routes to the recovery deep-link; `HealthResyncWorker` returns `Result.failure()` rather than looping | OK |
| **Change tokens** | per `HealthDataType`, captured via `captureChangesTokens()`, staged during the run, committed only after derived summaries are durable | OK, except the escalation path | 
| **Token expiry** | `response.changesTokenExpired` (primary) + message-substring fallback | `R2-HC-006` |
| **Missing token + permission granted** | ⇒ `requiresFullResync` | correct, but the resync cannot delete — `R2-HC-001` |
| **Rate limits / IO** | `retryWithBackoff` (4 attempts, 1 s initial, exponential) via `HealthConnectRetryPolicy` | OK, but retry granularity is wrong — `R2-HC-002` |
| **Read-window sizing** | resync 30-day chunks with adaptive halving to 1 day (`chunkDaysOverride` persisted); daily split into today/back-day segments with 3/5/10-minute budgets | OK |
| **Cancellation** | `CancellationException` rethrown everywhere; `HealthConnectWindowTimeoutException` deliberately not a `CancellationException`; loops `ensureActive()` + `yield()` | OK |
| **Process death / resumption** | resync: four-phase checkpoint, transaction boundary == checkpoint boundary; daily: none (small window) | OK / `R2-CACHE-002` |
| **Timestamp precision** | epoch millis throughout; bucket key `(timestampMs/60000)*60000` (UTC-aligned) | OK |
| **Timezone & DST** | day boundaries via `prefs.scoringZone()` in sync/scoring; `atStartOfDay(zone)` (calendar-correct, not fixed 24 h) | OK in core, broken in UI — `R2-SCORE-003` |
| **Local-date attribution** | sleep sessions attributed across `start..end`; ingest windows reach back 1 day for cross-midnight sessions | OK |
| **Atomicity** | one transaction per bulk batch, per HR/HRV page, per changes page, per recompute unit | OK |
| **Idempotency** | upsert by stable id; no blanket delete; conflict-targeted UPSERT is a no-op on identical data | OK within a tier — `R2-DB-004` |
| **Provenance** | `deviceName` per row, `DeviceSourceFilter`, `SelectedSourcePruner` | lost on rollup — `R2-DB-002`; encoded two ways — `R2-ARCH-003` |

---

## 7. Large-Dataset Analysis

### 7.1 The design scenario

Per the audit brief, the pipeline must survive:

- **> 1,000,000 heart-rate records in a 30-day period** (≈ 1 Hz continuous, or multiple devices
  at lower rates). For reference, pure 1 Hz for 30 days = **2,592,000** samples;
  86,400/day; 1,440 minute-buckets/day.
- multi-year Health Connect history (up to `ABSOLUTE_MAX_DAYS = 3650`),
- unlimited local retention (`retentionDaysEnabled = false`),
- repeated incremental synchronization,
- multiple contributing devices / data origins,
- partial updates to historical dates.

### 7.2 Ingestion bottlenecks

| Stage | Current complexity | Current memory | Expected bottleneck | Target |
|---|---|---|---|---|
| HC page read (`readAllPagesStreaming`) | O(n) IPC, one page resident | one page | IPC + provider-side query | unchanged — already streamed |
| `HeartRateMapper.mapToInputs` | O(p log p) per page, **3 full-page materializations**, `Pair` boxing | 3 × page | allocation churn | 1 materialization, no boxing (`R2-PERF-004`) |
| `SessionLinkSweep.resolve` | amortized O(1)/sample | O(sessions) | none | unchanged |
| `DeviceSourceFilter` | O(p) | 1 × page | none | unchanged |
| `persistHeartRateSamples` | **O(n) individual statements** | one page | **dominant write cost** | batched prepared statement (`R2-PERF-003`) |
| whole-window retry | O(n) *re-done* per retry | — | budget exhaustion under load | page-token checkpoint (`R2-HC-002`) |
| `withTimeout(budget)` | 3 / 5 / 10 min | — | dense windows time out ⇒ chunk halving to 1 day | unchanged (correct mechanism) |

**Peak-memory risk during ingest is bounded and acceptable today** — one HC page at a time, plus
the window's sessions. The remaining ingest risk is throughput, not memory.

### 7.3 Database bottlenecks

| Operation | Current | Risk at scale |
|---|---|---|
| HR insert | one `INSERT … ON CONFLICT` per row inside a per-page transaction | 2.6 M statement dispatches per dense month (`R2-PERF-003`) |
| Index maintenance | 4 indices on `heart_rate_records` (`(sourceRecordRef,timestampMs)` unique, `timestampMs`, `(sessionId,recordType,beatsPerMinute)`, `(recordType,timestampMs)`) | 4 B-tree updates per inserted row — the real per-row cost; measure before adding any index |
| Rollup | **unbounded** `INSERT…SELECT…GROUP BY` + unbounded `DELETE` in one transaction | WAL blow-up, multi-minute write-lock hold, worker kill/repeat (`R2-PERF-002`) |
| Retention cleanup | 10,000-row bounded batches, own transaction each | **correct** — the model to copy |
| `GROUP BY (timestampMs/60000)*60000` | expression grouping, no usable index | temp B-tree proportional to rows scanned; bounded once `R2-PERF-002` lands |
| Walk-forward recompute | 30-day units, one transaction each, checkpointed after commit | correct |
| Room Flow invalidation | one round per transaction (`F7`) | correct |
| `daily_summaries` | ~3,650 rows at 10 years; PK `dateMidnightMs`, no extra index needed (`DB-002` note) | negligible |
| `hr_minute_buckets` | 1,440/day/(recordType,sessionId) ⇒ ~525 k rows/year single-stream | fine; grows with `R2-DB-002`'s device split — measure |

### 7.4 Recomputation bottlenecks

The 10-year rebuild (`resyncRange` over 3,650 days) is the worst case:

1. **`R2-PERF-001` dominates.** Each recomputed day whose baseline window reaches into the warm
   tier expands buckets to `sampleCount` boxed objects. A 30-day baseline window of warm nights
   is ~864,000 boxed `Integer`s ≈ 21 MB transient, per window build. The walk-forward contexts
   (`buildWalkForwardBaselineContext`) already fetch once per run rather than per day — that
   optimization (`PERF-002`/`WP-22`) is exactly what makes this survivable today.
2. **`R2-DB-001` makes it wrong as well as slow** for straddling sessions.
3. Per-day CPU is bounded and pure (`core:scoring`), running on the injected default dispatcher
   inside the Room transaction context.

### 7.5 Query complexity of the hot paths

| Query | Index used | Complexity |
|---|---|---|
| `getKeysetPage(startMs,endMs,lastTs,lastRef,limit)` | `index_hr_v10_timestamp` | O(log n + limit) — correct keyset pagination |
| `getByTypeAndTimeRange(type,start,end)` | `index_hr_v10_type_timestamp` | O(log n + matched) |
| `getSleepHrProjectionForSessions(ids)` | `index_hr_v10_session_type_bpm` | O(Σ per-session matches); covering for `(sessionId, beatsPerMinute)` |
| `observeAggregateByTimeRange` | `index_hr_v10_timestamp` | single-row aggregate — O(matched), no materialization |
| `getMinuteBuckets` (hot) | `index_hr_v10_timestamp` + temp group | O(matched) with a 1,440-entry temp |
| `getMinuteBuckets` (warm) | `(bucketStartMs,bucketEndMs)` | O(matched) |
| `deleteBeforeTimestampBatch` | subselect on `timestampMs ASC LIMIT` | O(log n + limit) |
| `rollupIntoBucketsBefore` | full range scan + temp group | **O(all rows below cutoff)** — the unbounded one |

No missing index was identified for any existing query. **Do not add indices speculatively** —
`heart_rate_records` already carries four, and each costs a B-tree write per inserted row on the
hottest path in the app.

### 7.6 Bounded-processing strategy (proposed, repository-grounded)

| Parameter | Value | Grounding |
|---|---|---|
| HC read page | provider-chosen (`pageToken` loop) | unchanged; do not hard-code |
| HR/HRV persist batch | **500 rows/prepared-statement batch** | new; must be measured (§11) before being fixed |
| Rollup window | **1 day per transaction** | mirrors the bucket key's UTC-minute alignment; keeps each unit ≤ ~86 k rows |
| Retention delete batch | **10,000 rows** | existing, proven (`DB-002`) |
| Recompute unit | **30 days per transaction** | existing (`RECOMPUTE_CHECKPOINT_INTERVAL_DAYS`) |
| Resync ingest chunk | **30 days**, halving to 1 | existing (`HC-002`) |
| Max dependent window | **84 days** | `ScoreInvalidation.MAX_DEPENDENT_WINDOW_DAYS`, from the 84-day TRIMP history fetch |

### 7.7 Target success criteria (measurable, no invented numbers)

- **Bounded memory:** no code path materializes more than one HC page, one recompute unit, or one
  rollup window. Specifically: `WarmTierReconstructor` allocates no boxed objects; peak heap
  during a 10-year rebuild does not scale with total history length.
- **No full-history materialization:** no production query returns an unbounded row set without a
  keyset cursor or an aggregate. (`getSince`/`getByTimeRange` on `heart_rate_records` must be
  audited per call site — see WP-16.)
- **No ANR:** no Room transaction on the main thread; no rollup/cleanup transaction longer than
  one bounded unit.
- **Resumable synchronization:** killing any worker at any point and restarting converges to the
  same state, with re-done work bounded by one unit (one chunk / one 30-day recompute unit / one
  rollup day / one 10 k delete batch / one HC page after `R2-HC-002`).
- **Deterministic results:** two resyncs of the same range over unchanged HC data and unchanged
  preferences produce byte-identical `daily_summaries` **within a tier**, and the cross-tier
  drift is bounded by the number documented under `R2-DB-004`.
- **Date-scoped recomputation:** every raw-data mutation (ingest, rollup, cleanup, changes-path
  delete) results in a recompute of exactly `ScoreInvalidation.affectedRange(...)` — no more,
  no less.
- **Indexed queries:** every scoring query's `EXPLAIN QUERY PLAN` shows an index scan or search,
  never `SCAN TABLE heart_rate_records` (asserted by a `:database-benchmark` test).

---

## 8. Target Architecture

Only the deltas from today's architecture (§3) are described; everything not mentioned stays.

### 8.1 Component responsibilities after remediation

| Component | Responsibility | Change |
|---|---|---|
| `HealthConnectRepository` (`core:healthconnect`) | the **only** Health Connect reader; exposes streamed, resumable page reads | + `startPageToken` on the paged reads (`R2-HC-002`) |
| `HealthIngestionCoordinator` | read → map → device-filter → persist, for one window | + page-level retry; + per-chunk id-set reconciliation for deletions (`R2-HC-001`) |
| `HealthChangeSynchronizer` | Changes API delta application | **no DAOs**; persists exclusively through `HealthIngestionStore`; `HealthConnectClient` injected (`R2-ARCH-002`, `R2-DI-001`) |
| `HealthIngestionStore` (port, `core:model`) | the **single** persistence boundary for ingestion | + `affectedDatesForRecord`, `deleteRecord`, `sessionSpansOverlapping` |
| `RoomHealthIngestionStore` (`core:database`) | owns every DAO/transaction detail of ingestion | + batched HR/HRV writes (`R2-PERF-003`) |
| `DataRollupManager` | hot→warm rollup | day-windowed, idempotent, returns the touched date range (`R2-PERF-002`) |
| `RetentionCleanup` | warm/cold pruning | returns the touched date range |
| `ScoreInvalidation` (new, pure, `core:model`) | the **only** statement of scoring dependency depth | new |
| `WarmTierReconstructor` | tier reconstruction | primitive arrays + 3-point summaries (`R2-PERF-001`, `R2-DB-004`) |
| `ScoringHistoryRepositoryImpl` / `ScoringDayDataLoader` | tier-aware scoring reads | **union** hot ∪ warm everywhere (`R2-DB-001`) |
| `HeartRateRepositoryImpl` | tier-aware **UI** reads | new tier awareness (`R2-UI-002`) |
| `ScoringRepository` | the only recompute entry point | unchanged |
| `WorkoutDetailLoader` (new, `feature:workouts`) | loads everything the detail screen needs, from Room only | new (`R2-UI-001`, `R2-ARCH-001`) |

### 8.2 Dependency direction

Unchanged and still acyclic. Two edges get *weaker*:
`core:healthconnect → core:database-schema` should disappear once the changes path stops
injecting DAOs; `feature:workouts → HealthConnectRepository` disappears entirely.

### 8.3 Ownership rules (the invariants this plan installs)

| Concern | Sole owner |
|---|---|
| Health Connect I/O | `core:healthconnect` — and never a `feature:*` module |
| Ingestion persistence | `HealthIngestionStore` / `RoomHealthIngestionStore` |
| Transaction boundaries | `TransactionRunner`, invoked by `core:database` and by the two sync use-cases; never by a mapper or a ViewModel |
| Scoring formulas | `core:scoring` (pure) |
| Recompute triggering | `ScoringRepository`, reached only via `DailyRecomputeSupport` |
| Invalidation depth | `ScoreInvalidation.MAX_DEPENDENT_WINDOW_DAYS` |
| Retention / tier boundaries | `RetentionBounds` |
| Date-key zone | `prefs.scoringZone()` — enforced by konsist in `core:scoring`, `core:database`, `feature:*` ViewModels |
| Display-format zone | `core:ui` formatting helpers (explicitly device-zone, explicitly named) |
| Dispatchers | injected `@IoDispatcher` / `@DefaultDispatcher` |
| Time | injected `java.time.Clock`; `RetentionBounds` takes `now` as a required parameter |
| DI scope | everything on the sync/scoring path is `@Singleton` in `SingletonComponent`; `HealthConnectClient` becomes a `@Provides @Singleton` binding |
| UI state | feature `*StateFactory` / `*PresentationFactory` classes; ViewModels expose `StateFlow` only |

---

## 9. Phased Implementation Roadmap

Six phases, strictly ordered. Every phase is independently shippable: at each phase boundary the
app builds, all tests pass, and the user-visible behavior is either unchanged or improved in the
ways listed. A phase may be paused indefinitely without leaving the codebase in a half-migrated
state.

**Ordering rationale.** Phase 1 comes before every measurement because benchmarks over lossy
historical reads (`R2-DB-001`) measure the wrong thing. Phase 2 bounds the operations Phase 1
made correct. Phase 3 removes the structural duplication that caused four of the Phase-1/2
findings, and is deliberately *after* them so the refactor lands on already-correct code. Phase 4
is optimization that only makes sense once ownership is clear. Phase 5 is what remains.

Every phase inherits the Global Constraints. Every commit runs the mandatory pre-commit chain.

---

### Phase 0 — Baseline and Safety Rails

**Objective.** Make the defects in Phases 1–4 *measurable* and *pinnable* before changing any
behavior. Nothing in this phase changes production behavior.

**Included findings.** `R2-DI-002` (enabling). Characterization for `R2-DB-001`, `R2-DB-004`,
`R2-PERF-001`, `R2-PERF-002`, `R2-PERF-003`, `R2-PERF-005`.

**Prerequisites.** None.

**Exact implementation steps**

1. **Inject `Clock` everywhere time is read** (`R2-DI-002`).
   - Add `private val clock: Clock` to `ResyncRangeUseCase`, `DataRollupWorker`,
     `DataCleanupWorker`.
   - Replace `System.currentTimeMillis()` in `ResyncRangeUseCase` (telemetry ×8,
     `updateLastSyncTimestamp`) and `DailySyncUseCase` (telemetry ×3) with `clock.millis()`.
   - Change `RetentionBounds.resolveHotTierCutoffMs(now: Instant)`,
     `resolveHistoricalWindow(prefs, now: Instant)` and `resolveRetentionCutoffMs(prefs, now: Instant)`
     to take `now` **without a default**; fix every call site.
   - `app/.../di/UtilModule.kt` already binds a `Clock` (verify; if not, add
     `@Provides @Singleton fun provideClock(): Clock = Clock.systemDefaultZone()`).
2. **Tier-boundary characterization tests** (`core/database/src/test/.../data/local/`,
   new `TierBoundaryCharacterizationTest.kt`). These tests **assert today's wrong behavior** and
   are flipped in Phase 1 — each one carries a `// R2-DB-001: asserts CURRENT (incorrect)
   behavior; flipped in WP-03` comment.
   - a sleep session straddling the cutoff ⇒ `getSleepHrSamplesForSession` returns only the hot
     half;
   - `getSleepHrProjectionForSessions` classifies a partially-rolled session as fully hot;
   - `loadWorkoutSamples` returns only the hot half for a straddling workout.
3. **Tier-drift characterization** (`R2-DB-004`): compute the sleep-RHR percentile and workout
   TRIMP for a fixture day before and after `rollupExpiredHotTier`; record the deltas as the
   *current* drift baseline in the test's assertion messages.
4. **Benchmarks** in the existing `:database-benchmark` module (it already exists — extend it,
   do not create a new module):
   - `HrUpsertBenchmark` — 100 k rows through `RoomHealthIngestionStore.persistHeartRateSamples`;
   - `RollupBenchmark` — rollup of a 30-day, 1 Hz corpus; record wall time **and** peak WAL size
     (`RoomWalDiagnostics.walFileSizeInfo()` already exists);
   - `WarmReconstructionBenchmark` — allocation count/bytes for a 30-day warm baseline window;
   - `HistoricalRebuildBenchmark` — `resyncRange` over 365 days with `skipIngestAndPrune = true`.
   - Fixture generator: extend `app/src/profileSupport/.../BenchmarkDataSeeder.kt`.
5. **`EXPLAIN QUERY PLAN` assertion test** (`:database-benchmark`): for each scoring query listed
   in §7.5, assert the plan contains `USING INDEX` and never `SCAN TABLE heart_rate_records`.
6. **Startup trace for `R2-PERF-005`**: capture a `:benchmark` macrobenchmark startup trace and
   record whether `SqlCipherKeyManager.migrateIfNeeded` / Keystore work appears on the main
   thread. **Write the answer into this document** (§14 OD-7) — the finding is closed either way.

**Expected file-level changes.** `core/model/.../RetentionBounds.kt`,
`core/healthconnect/.../{ResyncRangeUseCase,DailySyncUseCase}.kt`,
`app/.../workers/{DataRollupWorker,DataCleanupWorker}.kt`, `app/.../di/UtilModule.kt`,
new tests under `core/database/src/test/`, new benchmarks under `database-benchmark/`,
`app/src/profileSupport/.../BenchmarkDataSeeder.kt`.

**Schema or API changes.** None (internal signatures only).

**Migration strategy.** None required.

**Rollback strategy.** Revert the commits; no persisted state is touched.

**Risks.** Making `RetentionBounds`' `now` parameter required touches every call site — a missed
site is a compile error, not a runtime defect. Low risk.

**Required validation.** Full pre-commit chain. Benchmarks run and their numbers recorded in
§11's results table (fill in the placeholders).

**Completion criteria.**
- [ ] `grep -rn "Instant.now()\|System.currentTimeMillis()" core/healthconnect/src/main core/model/src/main/kotlin/app/readylytics/health/core/model/domain/util/RetentionBounds.kt app/src/main/kotlin/app/readylytics/health/workers` returns nothing.
- [ ] Characterization tests exist, pass, and are annotated with the work package that flips them.
- [ ] All four benchmarks produce recorded baseline numbers in §11.
- [ ] `R2-PERF-005` is resolved to confirmed-or-closed with a trace as evidence.

---

### Phase 1 — Correctness and Data Integrity

**Objective.** Make historical reads lossless, make invalidation explicit, and remove the two
determinism divergences. This phase **changes user-visible historical scores**; it therefore ends
with exactly one recompute, not several.

**Included findings.** `R2-DB-001` (High), `R2-CACHE-001` (High), `R2-DB-003` (Medium),
`R2-DB-004` (Medium-High), `R2-HC-004` (Medium), `R2-HC-005` (Medium), `R2-SCORE-005` (Low-Medium),
`R2-SCORE-001` (documentation).

**Prerequisites.** Phase 0 complete (pinnable clock + characterization tests).

**Exact implementation steps**

1. **Union the tiers** (`R2-DB-001`). In `ScoringHistoryRepositoryImpl`, rewrite
   `getSleepHrSamplesForSession`, `getSleepHrProjectionForSessions` and `getAvgSleepHrForSessions`
   to union hot ∪ warm instead of choosing. In `ScoringDayDataLoader`, rewrite
   `loadWorkoutSamples` the same way. Flip the Phase-0 characterization tests.
2. **Add the non-overlap invariant test**: after a rollup, no `(recordType, sessionId)` has both a
   raw row and a bucket covering the same minute. This is what makes the union safe.
3. **Unify the plausibility predicate** (`R2-DB-003`). Add
   `AND beatsPerMinute BETWEEN 30 AND 230` to the eight unfiltered scoring queries in
   `HeartRateDao` (`getAvgSleepHr`, `getAvgSleepHrPerSession`, `getSleepHrSamplesForSessions`,
   `getSleepHrSampleCount`, `getSleepHrSampleAtOffset`, `getMinHrTimestamp`, `getMinHrInRange`,
   and the `HrvDao` equivalents if any exist). Leave `observeAggregateByTimeRange` and
   `_observeSleepHrTimelineForSession` unfiltered and record that decision (OD-3). Add the
   table-driven exclusion test and the `ORDER BY beatsPerMinute` regression test.
4. **3-point warm reconstruction** (`R2-DB-004`). In `WarmTierReconstructor`, replace the
   flat-mean replay with `(minBpm × 1, avgBpm × (sampleCount − 2), maxBpm × 1)` for
   `sampleCount ≥ 3`; keep the flat replay for `sampleCount < 3`. Add the property test comparing
   percentile error against the raw stream. Record the measured drift bound.
5. **`ScoreInvalidation` + worker wiring** (`R2-CACHE-001`).
   - New pure file `core/model/.../domain/sync/ScoreInvalidation.kt` with
     `MAX_DEPENDENT_WINDOW_DAYS = 84` and `affectedRange(changed, today)`.
   - Test asserting every scoring lookback constant (`ACUTE_DAYS`, `CHRONIC_DAYS`,
     `BASELINE_DAYS`, `HRV_SIGMA_WINDOW_DAYS`, `CIRCADIAN_CONSISTENCY_WINDOW_DAYS`,
     `MATURE_DATA_TENURE_DAYS`, the 84-day TRIMP fetch) is ≤ `MAX_DEPENDENT_WINDOW_DAYS`.
     **Note:** `CIRCADIAN_CONSISTENCY_WINDOW_DAYS = 60` and `MATURE_DATA_TENURE_DAYS = 60` are
     both under 84 — if this test fails on a constant I have not enumerated, raise
     `MAX_DEPENDENT_WINDOW_DAYS` to that value rather than weakening the test.
   - `DataRollupManager.rollupExpiredHotTier` and `RetentionCleanup.deleteBefore` return the
     `ClosedRange<LocalDate>?` they touched (`null` ⇒ nothing).
   - `DataRollupWorker` / `DataCleanupWorker` enqueue a recompute-only resync over
     `ScoreInvalidation.affectedRange(...)` through the existing
     `WorkerSchedulerImpl` → `HealthResyncWorker(KEY_RECOMPUTE_ONLY = true)`,
     `ExistingWorkPolicy.APPEND_OR_REPLACE`. **No new worker, no new progress channel.**
6. **One shared step resolver** (`R2-HC-004`). New pure
   `core/model/.../domain/sync/StepAttribution.kt`; call it from both `DailySyncUseCase` and
   `ResyncRangeUseCase`. Resolve OD-2 before implementing; the recommended default is the
   resync's `?: 0L` semantic.
7. **`lastSyncTimestamp` on success only** (`R2-HC-005`). Move the write into the success branch
   of `DailySyncUseCase`; use `clock.millis()`. Verify `ForegroundSyncController` enqueues the
   resync on `REQUIRES_HISTORICAL_RESYNC` and add a test if it does not.
8. **Nullable math helpers** (`R2-SCORE-005`). Add `meanOrNull`/`medianOrNull`/`stdevOrNull` to
   `MathUtils`; fix `BaselineComputer.resolveBaselineRhrRounded` to fall back to
   `DEFAULT_RHR_BPM`.
9. **Document the TRIMP dead zone** (`R2-SCORE-001`). Extract
   `ScoringConstants.Trimp.MIN_HR_ABOVE_RHR_BPM = 5f`; update `RasCalculator`; add the rule to
   `ABOUT.md`, `docs/about.md`, `internal-docs/DATA_FLOW.md`, and extend the documentation-drift
   test. **Value unchanged** — golden snapshots must stay bit-identical.
10. **Ship the recompute.** Bump `UserPreferences.scoringVersion` once for the whole phase, so
    `HealthResyncWorker.persistPostRecomputeState()`'s existing staleness mechanism triggers a
    single recompute-only pass rather than one per change.

**Expected file-level changes.**
`core/database/.../data/repository/{ScoringHistoryRepositoryImpl,ScoringDayDataLoader}.kt`,
`core/database/.../data/local/{WarmTierReconstructor,DataRollupManager,RetentionCleanup}.kt`,
`core/database-schema/.../dao/HeartRateDao.kt` (+ `HrvDao.kt`),
new `core/model/.../domain/sync/{ScoreInvalidation,StepAttribution}.kt`,
`core/model/.../domain/scoring/ScoringConstants.kt`,
`core/scoring/.../domain/scoring/{RasCalculator,BaselineComputer}.kt`,
`core/scoring/.../domain/util/MathUtils.kt`,
`core/healthconnect/.../domain/sync/{DailySyncUseCase,ResyncRangeUseCase}.kt`,
`app/.../workers/{DataRollupWorker,DataCleanupWorker,WorkerSchedulerImpl}.kt`,
`ABOUT.md`, `docs/about.md`, `internal-docs/DATA_FLOW.md`.

**Schema or API changes.** No Room schema change. Internal API changes to
`WarmTierReconstructor` (`internal`), `RetentionBounds`, `MathUtils`, and the two cleanup
managers' return types.

**Migration strategy.** No data migration. **One** `scoringVersion` bump drives a single
recompute-only historical pass on first launch after update, using the existing durable worker
with its existing progress banner and notification.

**Rollback strategy.** Revert the commits and ship the previous APK. Stored `daily_summaries`
will hold post-fix values; the old code recomputes them back to pre-fix values on the next
resync, so rollback is safe but scores will move twice. No schema change means no data is
stranded.

**Risks.**
- Historical scores change. This is intended and unavoidable — the previous values were computed
  from truncated inputs. The release notes must say so.
- The `R2-CACHE-001` wiring makes two previously-silent workers enqueue real work. Guard: return
  `null` when nothing was rolled/deleted so nothing is enqueued on a no-op run; keep the existing
  worker constraints.
- Step semantics (`R2-HC-004`) is a user-visible behavior change gated on OD-2.

**Required validation.**
- Full pre-commit chain per commit.
- Phase-0 characterization tests flipped and green.
- Golden scoring snapshots (`ScoringGoldenSnapshotTest`) reviewed: differences must be explainable
  by exactly the findings in this phase, and the new expected values committed deliberately.
- `ScoringSyncScopeOutputsDeterminismTest` and `ResidualFatigueScoringIntegrityTest` green.
- `BackfillBaselinesUseCaseTest`, `ScoringRepositoryImplTest` green.

**Completion criteria.**
- [ ] No tier read chooses between hot and warm; all four union.
- [ ] Non-overlap invariant test green.
- [ ] Every scoring-facing `HeartRateDao` query applies the plausibility predicate; the two
      display queries are documented exceptions.
- [ ] Warm reconstruction uses min/avg/max; drift bound measured and written into `DATA_FLOW.md`.
- [ ] Rollup and cleanup enqueue exactly one bounded recompute each, and nothing on a no-op run.
- [ ] Both sync flows produce identical `stepCount` for the same fixture.
- [ ] A failing daily sync leaves `lastSyncTimestamp` untouched.
- [ ] `ABOUT.md` / `docs/about.md` / `DATA_FLOW.md` updated; documentation-drift tests green.

---

### Phase 2 — Health Connect and Database Scalability

**Objective.** Bound every unbounded operation and make ingestion resumable at page granularity.
Behavior-preserving except for `R2-HC-001`, which adds deletion convergence.

**Included findings.** `R2-PERF-002` (High), `R2-HC-002` (High), `R2-HC-001` (High),
`R2-DB-002` (Medium-High), `R2-ARCH-003` (Medium), `R2-PERF-003` (Medium-High).

**Prerequisites.** Phase 0 benchmarks (to prove the changes help) and Phase 1 (correct reads).

**Exact implementation steps**

1. **Window the rollup** (`R2-PERF-002`). Add
   `MinuteBucketDao.rollupIntoBucketsBetween(fromMs, toMs)` and
   `HeartRateDao.deleteBetweenTimestamps(fromMs, toMs)` plus
   `HeartRateDao.minTimestampBefore(cutoffMs)`. Rewrite `DataRollupManager` as the day-windowed
   loop from `R2-PERF-002`'s remediation. Keep the return value from Phase 1 step 5.
2. **Page-token-resumable ingest** (`R2-HC-002`).
   - `HealthConnectRepository.readHeartRateSamplesPaged` / `readHrvSamplesPaged` gain
     `startPageToken: String? = null` and pass `nextPageToken` to `onPage`.
   - `HealthConnectRepositoryImpl.readAllPagesStreaming` starts from `startPageToken` and wraps
     **only** `client.readRecords(...)` in `retryWithBackoff`.
   - `HealthIngestionCoordinator` moves its `retryWithBackoff` off the whole streamed read.
   - `ResyncCheckpoint` gains `hrPageToken: String? = null`, `hrvPageToken: String? = null`
     (additive, old checkpoints deserialize to `null`); cleared on chunk advance.
3. **Deletion convergence** (`R2-HC-001`). Implement Option A (per-chunk set reconciliation):
   - `HealthIngestionCoordinator.ingestWindow` collects the HC id set per record type;
   - a new `HealthIngestionStore.reconcileWindow(type, windowStart, windowEnd, hcIds)` deletes
     local rows in the window absent from `hcIds`, in the same transaction as the chunk's
     persist;
   - **never** invoked when `skipIngestAndPrune = true`;
   - deleted date ranges feed `ScoreInvalidation.affectedRange` so the walk-forward covers them;
   - `ResyncTelemetry` logs the delete counts.
   - Ship behind a `const val RECONCILE_DELETIONS = true` compile-time constant for one release so
     it can be flipped without a revert.
4. **Warm-tier provenance** (`R2-DB-002`) — **Room v14 → v15**.
   - `HrMinuteBucketEntity`: `deviceName: String = ""` (non-null), PK becomes
     `["bucketStartMs", "recordType", "sessionId", "deviceName"]`.
   - `rollupIntoBucketsBetween`: `COALESCE(deviceName, '')` in both the `SELECT` and the
     `GROUP BY`.
   - `MinuteBucketDao.deleteBucketsNotMatchingDevice(fromMs, toMs, deviceName)`; call it from
     `SelectedSourcePrunerImpl`.
   - `getDistinctDeviceNames` unions warm bucket device names.
   - `Migration14To15`: create the new table, `INSERT … SELECT …, ''`, drop, rename, recreate both
     indices. Add to `DatabaseMigrations.all`.
5. **Normalize `deviceName`** (`R2-ARCH-003`) in the same migration:
   `UPDATE <table> SET deviceName = NULL WHERE deviceName = ''` for `weight_records`,
   `body_fat_records`, `blood_pressure_records`, `oxygen_saturation_records`,
   `body_temperature_records`. Change `MapperHelpers.extractDeviceName` to return `String?`.
6. **Batched HR/HRV writes** (`R2-PERF-003`) — **only if the Phase-0 benchmark justifies it**.
   Implement the 500-row prepared-statement batch inside `RoomHealthIngestionStore`, leaving the
   DAO's typed API unchanged. Re-run `HrUpsertBenchmark`; if the improvement is not material,
   close the finding with the measurement recorded here instead.

**Expected file-level changes.**
`core/database-schema/.../dao/{MinuteBucketDao,HeartRateDao,HrvDao}.kt`,
`core/database-schema/.../entity/HrMinuteBucketEntity.kt`,
`core/database/.../data/local/{DataRollupManager,RoomHealthIngestionStore,SelectedSourcePrunerImpl,DatabaseMigrations}.kt`,
new `core/database/.../data/local/migration/Migration14To15.kt`,
`core/database/.../data/local/HealthDatabase.kt` (`DATABASE_VERSION = 15`),
`core/healthconnect/.../data/healthconnect/HealthConnectRepositoryImpl.kt`,
`core/healthconnect/.../domain/sync/{HealthIngestionCoordinator,ResyncRangeUseCase}.kt`,
`core/healthconnect/.../data/mapper/MapperHelpers.kt`,
`core/model/.../domain/repository/HealthConnectRepository.kt`,
`core/model/.../domain/sync/{HealthIngestionStore,ResyncCheckpoint}.kt`,
`internal-docs/DATA_FLOW.md`.

**Schema or API changes.** **Room 14 → 15** (`hr_minute_buckets` PK change + data normalization).
`HealthConnectRepository` paged-read signatures. `HealthIngestionStore` gains `reconcileWindow`.
`ResyncCheckpoint` gains two nullable fields.

**Migration strategy.** `Migration14To15` recreates `hr_minute_buckets`. On a large warm tier this
is a long-running migration — it must be measured in §11 and, if it exceeds a few seconds, moved
into the existing `DatabaseMigrationWorker` path rather than running on first open. Existing
buckets get `deviceName = ''` (provenance unknown for already-rolled data — accept and document).
Old resync checkpoints deserialize with `null` page tokens and behave exactly as today.

**Rollback strategy.** **This phase is not rollback-safe below v15** — Room has no downgrade path
for the PK change. Rollback means shipping the previous APK, which will fail to open a v15
database. Mitigation: ship Phase 2 as its own release, keep the previous release available for a
staged rollout halt, and verify the migration on a large real database before rollout. The
deletion-convergence step is separately revertible via `RECONCILE_DELETIONS`.

**Risks.**
- **Deletion convergence deletes user data if it is wrong.** Highest-risk change in the plan.
  Mitigations: only inside a chunk's own window; never on recompute-only; telemetry-first for one
  release; explicit tests for cancellation and partial chunks.
- Migration duration on a multi-year warm tier.
- Page-token resumption interacting with the adaptive chunk halving — a shrunk chunk must clear
  the stored page token (different window ⇒ different tokens). Test this explicitly.

**Required validation.**
- `MigrationTestHelper` v14→v15 test: row count and values preserved.
- Deletion-convergence integration tests (present, absent, cancelled, recompute-only).
- Page-failure resumption test (fails once on page 3 of 10 ⇒ 10 reads, 10 persists).
- Chunk-shrink-clears-page-token test.
- Re-run all four Phase-0 benchmarks; rollup WAL bounded, upsert throughput recorded.
- Full pre-commit chain.

**Completion criteria.**
- [ ] No unbounded transaction remains in `core:database` (`DataRollupManager` batched by day).
- [ ] A retry never re-persists a committed HC page.
- [ ] A resync converges on records deleted in Health Connect, and never deletes on a
      recompute-only pass.
- [ ] Two devices in one minute produce two warm buckets; `SelectedSourcePruner` prunes warm data.
- [ ] `SELECT COUNT(*) … WHERE deviceName = ''` is 0 on the five vitals tables.
- [ ] Migration test green; measured migration duration recorded in §11.

---

### Phase 3 — Architecture and Dependency Injection

**Objective.** Collapse the two ingestion persistence paths into one, so ingestion invariants are
enforced once. Purely structural — **no behavior change**, verified by the existing tests passing
unmodified.

**Included findings.** `R2-ARCH-002` (High), `R2-HC-003` (Medium), `R2-DI-001` (Medium),
`R2-ARCH-004` (Low), `R2-ARCH-001` (High, the boundary half).

**Prerequisites.** Phases 1–2 (do not refactor code that is still wrong).

**Exact implementation steps**

1. **Extend the port.** Add to `HealthIngestionStore` (`core:model`):
   `affectedDatesForRecord(type, hcRecordId, zoneId)`, `deleteRecord(type, hcRecordId)`,
   `sessionSpansOverlapping(startMs, endMs): SessionSpans`. Implement them in
   `RoomHealthIngestionStore` by moving the bodies of
   `HealthChangeSynchronizerImpl.getAffectedDatesForDeletedRecord` and `deleteRecordLocal`
   verbatim.
2. **Migrate the changes path.** Replace all 13 DAO fields in `HealthChangeSynchronizerImpl`'s
   constructor with `healthIngestionStore: HealthIngestionStore`; rewrite `upsertRecord` to build
   the existing `*Input` types and call `persist(HealthIngestionBatch(...))` /
   `persistHeartRateSamples` / `persistHrvSamples`.
3. **Delete the duplicate mappers** at `HealthChangeSynchronizerImpl.kt:641-750`; use
   `core/database/.../data/local/HealthIngestionInputMappers.kt`. If the input→entity mappers are
   not reachable from `core:healthconnect` after the port migration, they should not need to be —
   that is the point.
4. **Fix the N+1s** (`R2-HC-003`): hoist `getOrCreateSourceRef` to once per record and
   `sessionSpansOverlapping` to once per changes page. Both fall out of the port's range-based API.
5. **Inject the client** (`R2-DI-001`): add `@Provides @Singleton fun provideHealthConnectClient`
   to `HealthConnectModule`; inject into `HealthConnectRepositoryImpl` and
   `HealthChangeSynchronizerImpl`; remove `@ApplicationContext Context` from the latter.
6. **Drop the module edge**: remove `implementation(project(":core:database-schema"))` from
   `core/healthconnect/build.gradle.kts` if nothing else needs it; otherwise add a comment saying
   what does.
7. **Standardize `UserPreferences` imports** (`R2-ARCH-004`) and add the konsist rule.
8. **Remove the UI's Health Connect access** (`R2-ARCH-001`, boundary half). Delete the
   `hcRepo.readHeartRateSamples` call from `WorkoutDetailViewModel`; the recovery-window samples
   now come from the tier-aware `HeartRateRepository` added in Phase 4 / WP-14 — **so sequence
   this step after WP-14 if Phase 4 is scheduled first**, or extend the workout ingest window by
   `hrrToleranceSeconds + 3 min` here so Room already holds them. Add the konsist rule banning
   `HealthConnectRepository` sample reads from `feature:*`.
9. **Add architecture tests** (konsist, module `:app` test source set where the existing konsist
   tests live): no `feature:*` → Health Connect sample reads; no `ZoneId.systemDefault()` in
   `core:scoring`/`core:database`; no DAO injection outside `core:database`.

**Expected file-level changes.**
`core/model/.../domain/sync/HealthIngestionStore.kt`,
`core/database/.../data/local/RoomHealthIngestionStore.kt`,
`core/healthconnect/.../data/healthconnect/HealthChangeSynchronizerImpl.kt` (750 → target ≤ 400),
`core/healthconnect/.../di/HealthConnectModule.kt`,
`core/healthconnect/build.gradle.kts`,
`feature/workouts/.../WorkoutDetailViewModel.kt`,
konsist test sources.

**Schema or API changes.** `HealthIngestionStore` gains three methods. No schema change.

**Migration strategy.** None — no persisted state changes.

**Rollback strategy.** Fully revertible; no schema or data change.

**Risks.** This refactors a 750-line file on the critical sync path. The mitigation is that every
existing test must pass **unmodified** — if a test needs changing, the refactor changed behavior
and must be reworked.

**Required validation.**
- `core/healthconnect/src/test/.../DailySyncUseCaseTest.kt`, `ResyncRangeUseCaseTest.kt` and the
  change-synchronizer tests pass with no edits beyond mechanical constructor updates.
- New konsist rules green.
- `verify(exactly = 1) { getOrCreateSourceRef(any(), any(), any()) }` for a 200-sample record.
- Full pre-commit chain.

**Completion criteria.**
- [ ] `HealthChangeSynchronizerImpl` injects no DAO and no `Context`.
- [ ] No `toEntity`/`toInput` mapper is declared in `core:healthconnect`.
- [ ] No `feature:*` module reads Health Connect sample data.
- [ ] All three konsist architecture rules exist and are green.
- [ ] `HealthChangeSynchronizerImpl.kt` ≤ 400 lines (repo target).

---

### Phase 4 — Incremental Recalculation and Performance

**Objective.** Remove the remaining allocation hot spots and finish the tier-awareness story on
the read side.

**Included findings.** `R2-PERF-001` (High), `R2-PERF-004` (Medium), `R2-UI-002` (Medium),
`R2-SCORE-002` + `R2-SCORE-003` (zone determinism), `R2-CACHE-002` (decision), `R2-PERF-005`
(if Phase 0 confirmed it).

**Prerequisites.** Phases 1–3.

**Exact implementation steps**

1. **Primitive-backed reconstruction** (`R2-PERF-001` level 1): `WarmTierReconstructor` returns
   `IntArray` / (`LongArray`, `IntArray`); update the three call sites. Golden snapshots must be
   bit-identical.
2. **Bucket-aware percentile** (`R2-PERF-001` level 2): give `BaselineComputer`'s percentile path
   a weighted `(value, weight)` walk so a warm night contributes `3 × bucketCount` elements
   instead of `sampleCount`. Requires Phase 1's 3-point summary.
3. **Single-pass mapper** (`R2-PERF-004`): rewrite `HeartRateMapper.mapToInputs` and
   `HrvMapper.mapToInputs` to one pre-sized materialization with no `Pair` boxing, preserving
   `SessionLinkSweep`'s non-decreasing input contract. Assert element-identical output against a
   golden fixture.
4. **Tier-aware UI reads** (`R2-UI-002`): add union reads to `HeartRateRepositoryImpl`; combine
   the two DAO `Flow`s for the observable timeline; return a `HeartRateSeries` carrying its
   resolution; add the "1-minute averages" string to `strings.xml` and surface it in the sleep,
   vitals and workout HR charts.
5. **Zone determinism** (`R2-SCORE-002`, `R2-SCORE-003`): make the scoring zone a required
   parameter in `BaselineComputer` / `HistoricalSleepDayAssembler`; replace every date-key
   `ZoneId.systemDefault()` in `feature:*` ViewModels with `prefs.scoringZone()`; rename the
   `core:ui` formatting helpers to name the device zone explicitly; enable the konsist rule added
   in Phase 3.
6. **Resolve `R2-CACHE-002`** per OD-5: either document the accepted trade in `DATA_FLOW.md` with
   the measured redo cost, or split the daily walk-forward per day with the resync's
   commit-then-advance discipline and measure the Flow-invalidation count.
7. **`R2-PERF-005`**, only if Phase 0's trace confirmed main-thread work: move `migrateIfNeeded`
   and the readiness check off the Hilt provider into the existing `DatabaseMigrationWorker` /
   startup path. **Read `internal-docs/plans/KNOWN_ISSUE_sqlcipher_multiprocess_key_race.md`
   (recoverable from git history) before touching this.**

**Expected file-level changes.**
`core/database/.../data/local/WarmTierReconstructor.kt`,
`core/database/.../data/repository/{ScoringHistoryRepositoryImpl,ScoringDayDataLoader,HeartRateRepositoryImpl}.kt`,
`core/scoring/.../domain/scoring/{BaselineComputer,HistoricalSleepDayAssembler}.kt`,
`core/model/.../domain/sync/mappers/{HeartRateMapper,HrvMapper}.kt`,
`core/ui/.../common/{DateFormatUtils,ChartUtils,TimeRange}.kt`,
~20 `feature:*` ViewModel/state-factory files, `app/src/main/res/values/strings.xml`,
possibly `core/database/.../di/DatabaseModule.kt`.

**Schema or API changes.** Internal signatures only. No schema change.

**Rollback strategy.** Fully revertible.

**Risks.** Step 5 is wide (≈ 20 files) but mechanical; step 7 touches a documented prior race and
should be skipped unless measurement demands it.

**Required validation.**
- Golden snapshots bit-identical for steps 1 and 3.
- Allocation benchmark: ≥ 80 % reduction on the 30-day warm baseline window; ≥ 50 % fewer
  allocations in the mapper on a 5,000-sample page.
- Cross-zone ViewModel tests (`Pacific/Auckland` device vs `America/Los_Angeles` scoring).
- Post-rollup chart rendering test for a 120-day-old session.
- Full pre-commit chain; `./gradlew lintRelease`.

**Completion criteria.**
- [ ] No boxed collection remains in the warm-tier read path.
- [ ] UI HR charts render 1-minute data for warm dates and label the resolution.
- [ ] Konsist zone rule green; no date-key math uses the device zone.
- [ ] `R2-CACHE-002` explicitly resolved (documented trade or implemented split).

---

### Phase 5 — Compose and Long-Term Maintainability

**Objective.** The structural UI work that remains once the data layer is correct.

**Included findings.** `R2-UI-001` (Medium), `R2-SEC-001` (Medium), `R2-HC-006` (Low),
`R2-SCORE-004` (documentation).

**Prerequisites.** Phase 4 (so the extraction lands on final data-layer APIs).

**Exact implementation steps**

1. **Extract `WorkoutDetailLoader`** (`R2-UI-001`): a `suspend` loader returning one immutable
   `WorkoutDetailData`; the ViewModel maps it into UI state. Follow the existing
   `WorkoutsStateFactory` / `VitalsStateFactory` pattern. Target `WorkoutDetailViewModel.kt`
   ≤ 400 lines.
2. **Cache hygiene for exported logs** (`R2-SEC-001`): fixed filename instead of
   `createTempFile`; delete after the share resolves; prune `diagnostic_logs`, `crash_reports`
   and `logcat_capture` on startup; verify all three are excluded in `data_extraction_rules.xml`
   and `full_backup_content.xml`; update `docs/privacy.md` and `docs/backup-and-data.md` if their
   description no longer matches.
3. **Narrow token-expiry detection** (`R2-HC-006`): keep `response.changesTokenExpired` primary;
   narrow the fallback to the concrete exception types `connect-client` 1.1.0 throws (verify
   against the sources on the build classpath); log WARN when the fallback fires.
4. **iTRIMP documentation** (`R2-SCORE-004`): state in `ScoringConstants`, `ABOUT.md` and
   `docs/about.md` that this is a Manzi-inspired fixed-exponent variant with a user-tunable `b`.
   No formula change.
5. **Sweep the remaining oversized files** against the ≤ 400 / ≤ 800 targets, boyscout-style,
   only where a phase in this plan already touched them.

**Expected file-level changes.** `feature/workouts/.../WorkoutDetailViewModel.kt` + new
`WorkoutDetailLoader.kt`, `app/.../crashreport/DiagnosticLogFileExport.kt`,
`app/.../MainActivity.kt`, `app/src/main/res/xml/{data_extraction_rules,full_backup_content}.xml`,
`core/healthconnect/.../data/healthconnect/HealthChangeSynchronizerImpl.kt`,
`core/model/.../domain/scoring/ScoringConstants.kt`, `ABOUT.md`, `docs/about.md`,
`docs/privacy.md`, `docs/backup-and-data.md`.

**Schema or API changes.** None.

**Rollback strategy.** Fully revertible.

**Risks.** Low throughout.

**Required validation.** `WorkoutDetailViewModelTest` (994 lines) passes with at most mechanical
constructor updates; documentation-drift tests green; full pre-commit chain plus
`./gradlew lintRelease`.

**Completion criteria.**
- [ ] `WorkoutDetailViewModel.kt` ≤ 400 lines and contains no data access.
- [ ] At most one exported diagnostic file exists after repeated exports.
- [ ] Token-expiry fallback is type-based and logged.
- [ ] `ABOUT.md` / `docs/about.md` describe the iTRIMP variant accurately.

---

## 10. Ordered Work Packages

18 commit-sized packages. Each is independently reviewable and leaves the build green. Every
package ends with the mandatory pre-commit chain:

```bash
./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest
```

`./gradlew lintRelease` runs once at the end of each phase, not per package.

---

### WP-01 · Inject `Clock`; make `RetentionBounds` time explicit

**Phase** 0 · **Findings** `R2-DI-002` · **Depends on** — · **Complexity** S

**Purpose.** Make every time source pinnable so the tier and retention tests in WP-02/WP-03 can
be deterministic.

**Files:** `core/model/.../domain/util/RetentionBounds.kt` ·
`core/healthconnect/.../domain/sync/{ResyncRangeUseCase,DailySyncUseCase}.kt` ·
`app/.../workers/{DataRollupWorker,DataCleanupWorker}.kt` · `app/.../di/UtilModule.kt`

- [ ] **Step 1: Write the failing test.** `core/model/src/test/.../RetentionBoundsTest.kt`

```kotlin
@Test
fun `hot tier cutoff is exactly 90 days before the supplied instant`() {
    val now = Instant.parse("2026-08-31T12:00:00Z")
    assertEquals(
        Instant.parse("2026-06-02T12:00:00Z").toEpochMilli(),
        RetentionBounds.resolveHotTierCutoffMs(now),   // no default — must be supplied
    )
}
```

- [ ] **Step 2: Run it, confirm it compiles today** (`now` currently has a default, so this
      passes). Then remove the defaults from `resolveHotTierCutoffMs`, `resolveHistoricalWindow`
      and `resolveRetentionCutoffMs` and confirm the **build breaks** at every call site.
      Run: `./gradlew :core:model:compileDebugKotlin`. Expected: compile errors listing every site.
- [ ] **Step 3: Fix the call sites.** Inject `private val clock: Clock` into `ResyncRangeUseCase`,
      `DataRollupWorker`, `DataCleanupWorker`; pass `clock.instant()`. Replace every
      `System.currentTimeMillis()` in `ResyncRangeUseCase` and `DailySyncUseCase` with
      `clock.millis()`.
- [ ] **Step 4: Verify the binding exists.** Confirm `app/.../di/UtilModule.kt` provides a
      `Clock`; if not, add `@Provides @Singleton fun provideClock(): Clock = Clock.systemDefaultZone()`.
- [ ] **Step 5: Run the guard.**
      `grep -rn "Instant.now()\|System.currentTimeMillis()" core/healthconnect/src/main core/model/src/main/kotlin/app/readylytics/health/core/model/domain/util/RetentionBounds.kt app/src/main/kotlin/app/readylytics/health/workers`
      Expected: no output.
- [ ] **Step 6: Pre-commit chain, then commit** — `refactor(time): inject Clock and require an explicit now in RetentionBounds (R2-DI-002)`

**Acceptance criteria.** Grep guard empty; every existing test green.

---

### WP-02 · Characterize the tier boundary and its drift

**Phase** 0 · **Findings** `R2-DB-001`, `R2-DB-004` (characterization) · **Depends on** WP-01 ·
**Complexity** M

**Purpose.** Lock today's behavior in tests so WP-03/WP-05 can be shown to change exactly what
they claim.

**Files:** new `core/database/src/test/.../data/local/TierBoundaryCharacterizationTest.kt`

- [ ] **Step 1: Build the fixture.** A sleep session `22:00 → 06:00` whose samples straddle a
      cutoff placed at `02:00`; roll up with `DataRollupManager.rollupExpiredHotTier(cutoff)`.
- [ ] **Step 2: Write the characterization assertions** — each marked as asserting the *current,
      incorrect* behavior:

```kotlin
// R2-DB-001: asserts CURRENT (incorrect) behavior; flipped in WP-03.
@Test
fun `straddling sleep session currently returns only its hot half`() {
    val all = repo.getSleepHrSamplesForSession(sessionId)
    assertEquals(hotSampleCount, all.size)          // NOT hotSampleCount + warmSampleCount
}
```

- [ ] **Step 3:** Same shape for `getSleepHrProjectionForSessions`, `getAvgSleepHrForSessions`
      and `ScoringDayDataLoader.loadWorkoutSamples`.
- [ ] **Step 4: Drift characterization** (`R2-DB-004`): compute the sleep-RHR percentile and the
      workout TRIMP for a fully-hot fixture day, roll it up, recompute, and assert the delta,
      recording the measured value in the assertion message.
- [ ] **Step 5: Run.** `./gradlew :core:database:testDebugUnitTest --tests "*TierBoundaryCharacterizationTest*"`
      Expected: PASS (they describe today).
- [ ] **Step 6: Pre-commit chain, commit** — `test(db): characterize hot/warm tier boundary loss and drift (R2-DB-001, R2-DB-004)`

**Acceptance criteria.** Five characterization tests, each naming the WP that flips it.

---

### WP-03 · Union hot and warm tiers in every scoring read

**Phase** 1 · **Findings** `R2-DB-001` · **Depends on** WP-02 · **Complexity** M

**Purpose.** Stop discarding half the samples of a boundary-straddling session or workout.

**Files:** `core/database/.../data/repository/ScoringHistoryRepositoryImpl.kt` ·
`core/database/.../data/repository/ScoringDayDataLoader.kt` ·
`core/database/src/test/.../TierBoundaryCharacterizationTest.kt`

- [ ] **Step 1: Flip the first characterization test** to the correct expectation:
      `assertEquals(hotSampleCount + warmSampleCount, all.size)`.
- [ ] **Step 2: Run it, confirm it fails.**
      `./gradlew :core:database:testDebugUnitTest --tests "*TierBoundaryCharacterizationTest*"`
      Expected: FAIL, actual = `hotSampleCount`.
- [ ] **Step 3: Implement the union** in `getSleepHrSamplesForSession`:

```kotlin
override suspend fun getSleepHrSamplesForSession(sessionId: String): List<Int> {
    val hot = heartRateDao.getSleepHrSamplesForSession(sessionId)
    val warm = minuteBucketDao.getBucketsForSession("SLEEP", sessionId)
    if (warm.isEmpty()) return hot
    return (hot + warm.reconstructSampleValues()).sorted()
}
```

- [ ] **Step 4: Run, confirm PASS.**
- [ ] **Step 5: Repeat steps 1–4** for `getSleepHrProjectionForSessions` (union per session id
      rather than partitioning by presence), `getAvgSleepHrForSessions` (weight the warm side by
      `sampleCount`, as that method already does for the warm-only case) and
      `ScoringDayDataLoader.loadWorkoutSamples`.
- [ ] **Step 6: Add the non-overlap invariant test** — after a rollup, no
      `(recordType, sessionId)` has both a raw row and a bucket covering the same minute. This is
      the precondition that makes the union safe.
- [ ] **Step 7: Review the golden snapshots.**
      `./gradlew :core:database:testDebugUnitTest --tests "*ScoringGoldenSnapshotTest*"` — expect
      diffs only on fixtures with straddling sessions; commit the new expected values
      deliberately.
- [ ] **Step 8: Pre-commit chain, commit** — `fix(db): union hot and warm tiers in scoring reads (R2-DB-001)`

**Acceptance criteria.** All five characterization tests flipped and green; non-overlap invariant
test green; golden diffs explained in the commit message.

---

### WP-04 · Unify the heart-rate plausibility predicate

**Phase** 1 · **Findings** `R2-DB-003` · **Depends on** WP-03 · **Complexity** S-M

**Files:** `core/database-schema/.../dao/HeartRateDao.kt` (+ `HrvDao.kt` if applicable) ·
new `core/database/src/test/.../dao/HeartRatePlausibilityTest.kt`

- [ ] **Step 1: Write the table-driven failing test** — insert a 250 bpm sample into a session,
      then assert every scoring-facing query excludes it:

```kotlin
@ParameterizedTest
@MethodSource("scoringQueries")
fun `implausible samples are excluded from every scoring query`(q: suspend () -> List<Int>) {
    assertFalse(250 in q())
}
```

- [ ] **Step 2: Run, confirm it fails** for `getAvgSleepHr`, `getAvgSleepHrPerSession`,
      `getSleepHrSamplesForSessions`, `getSleepHrSampleCount`, `getSleepHrSampleAtOffset`,
      `getMinHrTimestamp`, `getMinHrInRange`.
- [ ] **Step 3: Add `AND beatsPerMinute BETWEEN 30 AND 230`** to exactly those queries. Leave
      `observeAggregateByTimeRange` and `_observeSleepHrTimelineForSession` unfiltered (OD-3) and
      add a KDoc line on each saying why.
- [ ] **Step 4: Add the ordering-regression test** — `getSleepHrProjectionForSessions` must
      return `beatsPerMinute` non-decreasing within a session, because
      `BaselineComputer.rhrHistory` indexes into it positionally.
- [ ] **Step 5: Run, confirm PASS; review golden snapshots and commit new expectations.**
- [ ] **Step 6: Pre-commit chain, commit** — `fix(db): apply the plausibility predicate to every scoring HR query (R2-DB-003)`

**Acceptance criteria.** Parameterized test green; both display queries documented as exceptions;
ordering-regression test green.

---

### WP-05 · Three-point warm reconstruction and documented tier drift

**Phase** 1 · **Findings** `R2-DB-004` · **Depends on** WP-03, WP-04 · **Complexity** M ·
**Blocked on** OD-1

**Files:** `core/database/.../data/local/WarmTierReconstructor.kt` ·
new `core/database/src/test/.../WarmTierReconstructionPropertyTest.kt` ·
`internal-docs/DATA_FLOW.md` · `ABOUT.md` · `docs/about.md` · `AGENTS.md`

- [ ] **Step 1: Write the property test** — for random synthetic minutes, the 3-point
      reconstruction's p‑n percentile is closer to the raw percentile than the flat-mean
      reconstruction's, for `n ∈ {5, 10, 25, 50}`.
- [ ] **Step 2: Run, confirm it fails** (only the flat reconstruction exists).
- [ ] **Step 3: Implement.**

```kotlin
internal fun List<HrMinuteBucketEntity>.reconstructSampleValues(): List<Int> =
    flatMap { b ->
        if (b.sampleCount >= 3) {
            buildList(b.sampleCount) {
                add(b.minBpm)
                repeat(b.sampleCount - 2) { add(round(b.avgBpm).toInt()) }
                add(b.maxBpm)
            }
        } else {
            List(b.sampleCount) { round(b.avgBpm).toInt() }
        }
    }
```
      Apply the same shape to `reconstructTimestampedSamples` (min at the bucket start, max at
      the end, mean in between — preserving the ascending-timestamp contract).
- [ ] **Step 4: Run, confirm PASS.**
- [ ] **Step 5: Measure the residual drift** with WP-02's drift test and record the number.
- [ ] **Step 6: Document.** Add a "Determinism across tiers" subsection to `DATA_FLOW.md`'s
      3-tier lifecycle section stating the measured bound; add a sentence to `ABOUT.md` and
      `docs/about.md`; amend `AGENTS.md`'s idempotency bullet to read "idempotent *within a
      tier*".
- [ ] **Step 7: Pre-commit chain, commit** — `fix(db): preserve min/max in warm reconstruction and document tier drift (R2-DB-004)`

**Acceptance criteria.** Property test green; drift bound in `DATA_FLOW.md` matches the test's
asserted bound; documentation-drift tests green.

---

### WP-06 · `ScoreInvalidation` and worker-driven recompute

**Phase** 1 · **Findings** `R2-CACHE-001` · **Depends on** WP-01 · **Complexity** M

**Files:** new `core/model/.../domain/sync/ScoreInvalidation.kt` ·
`core/database/.../data/local/{DataRollupManager,RetentionCleanup}.kt` ·
`app/.../workers/{DataRollupWorker,DataCleanupWorker,WorkerSchedulerImpl}.kt` ·
new tests in `core/model/src/test/` and `app/src/test/`

- [ ] **Step 1: Write the failing pure test.**

```kotlin
@Test
fun `affected range extends 84 days past the changed range but never past today`() {
    val changed = LocalDate.of(2026, 1, 1)..LocalDate.of(2026, 1, 10)
    val today = LocalDate.of(2026, 2, 1)
    assertEquals(LocalDate.of(2026, 1, 1)..LocalDate.of(2026, 2, 1),
        ScoreInvalidation.affectedRange(changed, today))
}
```

- [ ] **Step 2: Run, confirm it fails** (class does not exist).
- [ ] **Step 3: Implement `ScoreInvalidation`** exactly as sketched in `R2-CACHE-001`.
- [ ] **Step 4: Add the depth-guard test** enumerating `ACUTE_DAYS`, `CHRONIC_DAYS`,
      `BASELINE_DAYS`, `HRV_SIGMA_WINDOW_DAYS`, `CIRCADIAN_CONSISTENCY_WINDOW_DAYS`,
      `MATURE_DATA_TENURE_DAYS` and the 84-day TRIMP fetch, asserting each
      `<= MAX_DEPENDENT_WINDOW_DAYS`.
- [ ] **Step 5: Return the touched range** from `DataRollupManager.rollupExpiredHotTier` and
      `RetentionCleanup.deleteBefore` (`ClosedRange<LocalDate>?`, `null` when nothing changed).
- [ ] **Step 6: Write the failing worker test** — a rollup that touched days 1–10 enqueues exactly
      one recompute-only resync for `[day1, min(day10+84, today)]`; a no-op rollup enqueues
      nothing.
- [ ] **Step 7: Wire the workers** through the existing
      `WorkerSchedulerImpl` → `HealthResyncWorker(KEY_RECOMPUTE_ONLY = true)`,
      `ExistingWorkPolicy.APPEND_OR_REPLACE`. Do not add a worker or a progress channel.
- [ ] **Step 8: Run, confirm PASS.**
- [ ] **Step 9: Update `DATA_FLOW.md`** — add `ScoreInvalidation` to the component table and
      describe the new worker → recompute edge.
- [ ] **Step 10: Pre-commit chain, commit** — `fix(cache): recompute scores after rollup and retention cleanup (R2-CACHE-001)`

**Acceptance criteria.** Depth-guard test green; both workers enqueue bounded recomputes; a no-op
run enqueues nothing.

---

### WP-07 · One shared step-attribution rule

**Phase** 1 · **Findings** `R2-HC-004` · **Depends on** — · **Complexity** S ·
**Blocked on** OD-2

**Files:** new `core/model/.../domain/sync/StepAttribution.kt` ·
`core/healthconnect/.../domain/sync/{DailySyncUseCase,ResyncRangeUseCase}.kt` ·
new `core/healthconnect/src/test/.../StepAttributionParityTest.kt`

- [ ] **Step 1: Write the failing parity test** — drive both flows over the same fixture (a day
      with no step data, step device selected) and assert identical `stepCount`.
- [ ] **Step 2: Run, confirm it fails** (daily preserves, resync writes 0).
- [ ] **Step 3: Implement the shared resolver.**

```kotlin
object StepAttribution {
    /** null ⇒ preserve the stored count; a value ⇒ overwrite it. */
    fun resolve(day: LocalDate, steps: Map<LocalDate, Long>,
                stepsDeviceSelected: Boolean, recomputeOnly: Boolean): Long? = when {
        recomputeOnly -> null
        stepsDeviceSelected -> steps[day] ?: 0L
        else -> steps[day]
    }
}
```

- [ ] **Step 4: Call it from both flows;** delete the inline `when` in `ResyncRangeUseCase` and
      the bare `stepsMap[dayToScore]` in `DailySyncUseCase`.
- [ ] **Step 5: Run, confirm PASS.**
- [ ] **Step 6: Pre-commit chain, commit** — `fix(sync): share one step-attribution rule between both sync flows (R2-HC-004)`

**Acceptance criteria.** Parity test green for the four combinations of
(device selected / not) × (data present / absent).

---

### WP-08 · `lastSyncTimestamp` only on a completed sync

**Phase** 1 · **Findings** `R2-HC-005` · **Depends on** WP-01 · **Complexity** S

**Files:** `core/healthconnect/.../domain/sync/DailySyncUseCase.kt` ·
`core/healthconnect/src/test/.../DailySyncUseCaseTest.kt`

- [ ] **Step 1: Write the failing test.**

```kotlin
@Test
fun `a sync that requires a historical resync does not advance lastSyncTimestamp`() = runTest {
    // changeSynchronizer returns an affected date older than MAX_INLINE_RECOMPUTE_DAYS
    val result = useCase.run(windowDays = 1, onProgress = null)
    assertEquals("REQUIRES_HISTORICAL_RESYNC", (result as Result.Failure).code)
    coVerify(exactly = 0) { settingsRepo.updateLastSyncTimestamp(any()) }
}
```

- [ ] **Step 2: Run, confirm it fails** (currently called unconditionally).
- [ ] **Step 3: Move the write** (and `commitTokens`) into the success branch; use `clock.millis()`.
- [ ] **Step 4: Run, confirm PASS;** confirm the existing success-path test still asserts the
      timestamp advances.
- [ ] **Step 5: Verify `ForegroundSyncController`** enqueues a resync on
      `REQUIRES_HISTORICAL_RESYNC`; add a test if it does not.
- [ ] **Step 6: Pre-commit chain, commit** — `fix(sync): do not mark a sync complete when it escalates to a historical resync (R2-HC-005)`

**Acceptance criteria.** Both tests green; escalation reliably enqueues the resync.

---

### WP-09 · Nullable math helpers and the TRIMP dead-zone constant

**Phase** 1 · **Findings** `R2-SCORE-005`, `R2-SCORE-001` · **Depends on** — · **Complexity** S ·
**Blocked on** OD-4 (dead-zone value only; the constant extraction is unconditional)

**Files:** `core/scoring/.../domain/util/MathUtils.kt` ·
`core/scoring/.../domain/scoring/{BaselineComputer,RasCalculator}.kt` ·
`core/model/.../domain/scoring/ScoringConstants.kt` · `ABOUT.md` · `docs/about.md` ·
`internal-docs/DATA_FLOW.md`

- [ ] **Step 1: Write the failing test.**

```kotlin
@Test
fun `rounded RHR baseline falls back to the default for an empty history`() {
    assertEquals(ScoringConstants.DEFAULT_RHR_BPM.roundToInt(),
        BaselineComputer.resolveBaselineRhrRounded(emptyList(), null))
}
```

- [ ] **Step 2: Run, confirm it fails** (returns 0).
- [ ] **Step 3: Add `meanOrNull`/`medianOrNull`/`stdevOrNull`;** fix both `resolveBaselineRhr*`
      methods to fall back to `DEFAULT_RHR_BPM`.
- [ ] **Step 4: Run, confirm PASS.**
- [ ] **Step 5: Extract the dead-zone constant** —
      `ScoringConstants.Trimp.MIN_HR_ABOVE_RHR_BPM = 5f` with a rationale comment; reference it in
      `RasCalculator`. **Value unchanged.**
- [ ] **Step 6: Document the rule** in `ABOUT.md`, `docs/about.md` and `DATA_FLOW.md`; extend the
      documentation-drift test to assert its presence.
- [ ] **Step 7: Confirm golden snapshots are bit-identical.**
      `./gradlew :core:database:testDebugUnitTest --tests "*ScoringGoldenSnapshotTest*"`
- [ ] **Step 8: Pre-commit chain, commit** — `fix(scoring): make empty-history math explicit and document the TRIMP dead zone (R2-SCORE-005, R2-SCORE-001)`

**Acceptance criteria.** Empty-history test green; golden snapshots unchanged;
documentation-drift test covers the new constant.

---

### WP-10 · Ship the Phase-1 recompute

**Phase** 1 · **Findings** all Phase-1 · **Depends on** WP-03…WP-09 · **Complexity** S

**Files:** `core/model/.../data/preferences/UserPreferences.kt` (`scoringVersion` bump) ·
release notes

- [ ] **Step 1:** Bump `scoringVersion` **once** for the whole phase.
- [ ] **Step 2:** Verify `HealthResyncWorker.persistPostRecomputeState()`'s staleness check
      triggers a single recompute-only pass on first launch after update.
- [ ] **Step 3:** Add a test asserting a stale `scoringVersion` enqueues exactly one
      recompute-only resync, not one per changed finding.
- [ ] **Step 4:** Draft release-note copy: historical scores may shift because sleep and workout
      data older than 90 days is now read completely.
- [ ] **Step 5: Pre-commit chain, commit** — `chore(scoring): bump scoringVersion for the Phase 1 correctness recompute`

**Acceptance criteria.** One recompute per upgrade; progress surfaced through the existing banner
and notification.

---

### WP-11 · Window the hot→warm rollup

**Phase** 2 · **Findings** `R2-PERF-002` · **Depends on** WP-06 · **Complexity** M

**Files:** `core/database-schema/.../dao/{MinuteBucketDao,HeartRateDao}.kt` ·
`core/database/.../data/local/DataRollupManager.kt` ·
new `core/database/src/test/.../DataRollupManagerTest.kt` ·
`database-benchmark/.../RollupBenchmark.kt`

- [ ] **Step 1: Write the failing test** with a counting `TransactionRunner` fake: rolling up
      3 days opens ≥ 3 transactions, none containing more than one day's rows.
- [ ] **Step 2: Run, confirm it fails** (one transaction today).
- [ ] **Step 3: Add the ranged DAO queries** — `rollupIntoBucketsBetween(fromMs, toMs)`,
      `deleteBetweenTimestamps(fromMs, toMs)`, `minTimestampBefore(cutoffMs)`.
- [ ] **Step 4: Rewrite `DataRollupManager`** as the day-windowed loop (see `R2-PERF-002`),
      aligning windows to UTC midnight to match the bucket key arithmetic. Keep the WP-06 return
      value.
- [ ] **Step 5: Run, confirm PASS.**
- [ ] **Step 6: Add the idempotency test** — kill mid-loop, re-run, identical final bucket set.
- [ ] **Step 7: Re-run `RollupBenchmark`;** record wall time and peak WAL in §11.
- [ ] **Step 8: Pre-commit chain, commit** — `perf(db): roll up the hot tier one day per transaction (R2-PERF-002)`

**Acceptance criteria.** Transaction-count test green; idempotency test green; WAL bounded per
§7.7.

---

### WP-12 · Page-token-resumable Health Connect ingest

**Phase** 2 · **Findings** `R2-HC-002` · **Depends on** — · **Complexity** M-L

**Files:** `core/model/.../domain/repository/HealthConnectRepository.kt` ·
`core/healthconnect/.../data/healthconnect/HealthConnectRepositoryImpl.kt` ·
`core/healthconnect/.../domain/sync/HealthIngestionCoordinator.kt` ·
`core/model/.../domain/sync/ResyncCheckpoint.kt` ·
`core/healthconnect/src/test/.../PagedIngestResumptionTest.kt`

- [ ] **Step 1: Write the failing test** with a fake HC source that fails once on page 3 of 10:
      assert exactly 10 page reads and 10 persists (today: 13 and 13).
- [ ] **Step 2: Run, confirm it fails.**
- [ ] **Step 3: Add `startPageToken`** to the two paged reads and surface `nextPageToken` to
      `onPage`; start `readAllPagesStreaming` from it.
- [ ] **Step 4: Move `retryWithBackoff`** to wrap only `client.readRecords(...)`; remove it from
      around the streamed call in `HealthIngestionCoordinator`.
- [ ] **Step 5: Run, confirm PASS.**
- [ ] **Step 6: Add the nullable checkpoint fields** `hrPageToken` / `hrvPageToken`; persist after
      each page; clear on chunk advance.
- [ ] **Step 7: Add the chunk-shrink test** — a window timeout that halves the chunk **must**
      clear the stored page token (a different window has different tokens).
- [ ] **Step 8: Add the old-checkpoint test** — a checkpoint serialized without the new fields
      deserializes with `null` and behaves as today.
- [ ] **Step 9: Pre-commit chain, commit** — `perf(sync): retry Health Connect reads per page and resume from the page token (R2-HC-002)`

**Acceptance criteria.** All four tests green; no benchmark regression on the happy path.

---

### WP-13 · Deletion convergence in the historical resync

**Phase** 2 · **Findings** `R2-HC-001` · **Depends on** WP-06, WP-12 · **Complexity** L ·
**Highest-risk package in this plan**

**Files:** `core/model/.../domain/sync/HealthIngestionStore.kt` ·
`core/database/.../data/local/RoomHealthIngestionStore.kt` ·
`core/healthconnect/.../domain/sync/{HealthIngestionCoordinator,ResyncRangeUseCase}.kt` ·
new `core/healthconnect/src/test/.../ResyncDeletionConvergenceTest.kt`

- [ ] **Step 1: Write the failing test.** Ingest 3 workouts + 2 sleep sessions; delete one of
      each from the fake HC source; run `resyncRange` over the same window; assert local counts
      converge and the affected days were recomputed.
- [ ] **Step 2: Run, confirm it fails** (resync is additive today).
- [ ] **Step 3: Add the port method.**

```kotlin
suspend fun reconcileWindow(
    type: HealthDataType, windowStartMs: Long, windowEndMs: Long, hcIds: Set<String>,
): ClosedRange<LocalDate>?   // dates of deleted rows, for ScoreInvalidation
```

- [ ] **Step 4: Collect the HC id set** per record type in `ingestWindow` and call
      `reconcileWindow` in the same transaction as the chunk's persist.
- [ ] **Step 5: Guard it.** Never call it when `skipIngestAndPrune = true`. Add
      `private const val RECONCILE_DELETIONS = true` so the behavior can be flipped without a
      revert.
- [ ] **Step 6: Feed the deleted dates** into the walk-forward range via
      `ScoreInvalidation.affectedRange`.
- [ ] **Step 7: Run, confirm PASS.**
- [ ] **Step 8: Add the negative tests** — recompute-only deletes nothing; a cancelled resync
      deletes nothing outside the committed chunk; an empty HC window (permission lost) deletes
      nothing.
- [ ] **Step 9: Add telemetry** — `ResyncTelemetry` logs
      `[INGESTION] reconciled deletes: hr=… sleep=… workout=…`.
- [ ] **Step 10: Update `DATA_FLOW.md`** — the resync now has delete semantics; the additive-only
      claim in `ResyncRangeUseCase`'s KDoc must be rewritten.
- [ ] **Step 11: Pre-commit chain, commit** — `fix(sync): converge on Health Connect deletions during a full resync (R2-HC-001)`

**Acceptance criteria.** Four convergence tests green; telemetry present; the "no blanket delete"
constraint still holds (deletes are id-set-scoped and window-scoped, never blanket).

---

### WP-14 · Warm-tier provenance (Room v14 → v15)

**Phase** 2 · **Findings** `R2-DB-002`, `R2-ARCH-003` · **Depends on** WP-11 · **Complexity** M ·
**Schema change — not rollback-safe**

**Files:** `core/database-schema/.../entity/HrMinuteBucketEntity.kt` ·
`core/database-schema/.../dao/MinuteBucketDao.kt` ·
`core/database/.../data/local/{HealthDatabase,DatabaseMigrations,SelectedSourcePrunerImpl}.kt` ·
new `core/database/.../data/local/migration/Migration14To15.kt` ·
`core/healthconnect/.../data/mapper/MapperHelpers.kt` ·
new `core/database/src/androidTest/.../Migration14To15Test.kt`

- [ ] **Step 1: Write the failing migration test** with `MigrationTestHelper`: create a v14
      database with 3 buckets, migrate to v15, assert row count and values preserved and
      `deviceName = ''`.
- [ ] **Step 2: Run, confirm it fails** (no v15).
- [ ] **Step 3: Change the entity** — `deviceName: String = ""`, PK
      `["bucketStartMs", "recordType", "sessionId", "deviceName"]`.
- [ ] **Step 4: Write `Migration14To15`** — create the new table, `INSERT … SELECT …, ''`, drop,
      rename, recreate both indices; plus
      `UPDATE <t> SET deviceName = NULL WHERE deviceName = ''` for the five vitals tables.
      Register it in `DatabaseMigrations.all`; bump `DATABASE_VERSION` to 15.
- [ ] **Step 5: Carry the device through the rollup** — `COALESCE(deviceName, '')` in the
      `SELECT` **and** the `GROUP BY` of `rollupIntoBucketsBetween`.
- [ ] **Step 6: Write the failing two-device test** — two devices in one minute produce two
      buckets.
- [ ] **Step 7: Run, confirm PASS.**
- [ ] **Step 8: Add `deleteBucketsNotMatchingDevice`** and call it from `SelectedSourcePrunerImpl`;
      test that warm buckets of a non-selected device are pruned.
- [ ] **Step 9: Union warm device names** into `getDistinctDeviceNames`; test that a
      warm-only device appears in the picker.
- [ ] **Step 10: Change `MapperHelpers.extractDeviceName`** to return `String?`; fix call sites.
- [ ] **Step 11: Measure the migration** on a seeded large warm tier; record in §11. If it is not
      near-instant, move it into `DatabaseMigrationWorker`.
- [ ] **Step 12: Update `DATA_FLOW.md`** — schema version, the new PK, the new prune path.
- [ ] **Step 13: Pre-commit chain, commit** — `feat(db)!: preserve device provenance in the warm tier (R2-DB-002, R2-ARCH-003)`

**Acceptance criteria.** Migration test green; two-device test green; prune and picker tests
green; no `deviceName = ''` remains in the five vitals tables; measured migration duration
recorded.

---

### WP-15 · Batched heart-rate writes (measure first)

**Phase** 2 · **Findings** `R2-PERF-003` · **Depends on** WP-01 (benchmark baseline) ·
**Complexity** M

**Files:** `core/database/.../data/local/RoomHealthIngestionStore.kt` ·
`database-benchmark/.../HrUpsertBenchmark.kt`

- [ ] **Step 1: Run the Phase-0 `HrUpsertBenchmark`** and record the baseline for 100 k rows.
- [ ] **Step 2: Implement a 500-row prepared-statement batch** inside `RoomHealthIngestionStore`,
      preserving the exact `ON CONFLICT … DO UPDATE … WHERE` semantics. The DAO's typed API is
      unchanged.
- [ ] **Step 3: Run the benchmark again.** If the improvement is not material, **revert step 2**
      and close the finding by recording the measurement in §11 and in this document.
- [ ] **Step 4: Add the idempotency test** — re-ingesting the same 100 k rows mutates zero rows
      (`changes() = 0` semantics preserved).
- [ ] **Step 5: Pre-commit chain, commit** — `perf(db): batch heart-rate upserts (R2-PERF-003)`
      *or* `docs(plan): close R2-PERF-003 — batching measured, no material gain`

**Acceptance criteria.** Either a recorded throughput improvement, or a recorded measurement
closing the finding. Idempotency preserved either way.

---

### WP-16 · Unify the ingestion persistence path

**Phase** 3 · **Findings** `R2-ARCH-002`, `R2-HC-003`, `R2-DI-001` · **Depends on** WP-13
(the port already gained `reconcileWindow`) · **Complexity** L

**Files:** `core/model/.../domain/sync/HealthIngestionStore.kt` ·
`core/database/.../data/local/RoomHealthIngestionStore.kt` ·
`core/healthconnect/.../data/healthconnect/HealthChangeSynchronizerImpl.kt` ·
`core/healthconnect/.../di/HealthConnectModule.kt` · `core/healthconnect/build.gradle.kts`

- [ ] **Step 1: Write the failing N+1 test.**

```kotlin
@Test
fun `a single heart rate record resolves its source ref exactly once`() = runTest {
    synchronizer.applyPendingChanges()          // one UpsertionChange, 200 samples
    coVerify(exactly = 1) { sourceRecordDao.getOrCreateSourceRef(any(), any(), any()) }
}
```

- [ ] **Step 2: Run, confirm it fails** (200 invocations).
- [ ] **Step 3: Extend the port** with `affectedDatesForRecord`, `deleteRecord` and
      `sessionSpansOverlapping`; move the bodies of
      `getAffectedDatesForDeletedRecord` / `deleteRecordLocal` into `RoomHealthIngestionStore`
      **verbatim**.
- [ ] **Step 4: Replace the 13 DAO fields** in `HealthChangeSynchronizerImpl` with
      `healthIngestionStore`; rewrite `upsertRecord` to build the existing `*Input` types and call
      the port. Hoist `getOrCreateSourceRef` to once per record and `sessionSpansOverlapping` to
      once per page.
- [ ] **Step 5: Delete the file-scope mappers** at lines 641-750.
- [ ] **Step 6: Add the `HealthConnectClient` binding;** inject it into both classes; remove
      `@ApplicationContext Context` from `HealthChangeSynchronizerImpl`.
- [ ] **Step 7: Run every existing sync test.** They must pass **unmodified** apart from
      constructor updates — if any assertion has to change, the refactor changed behavior.
      Run: `./gradlew :core:healthconnect:testDebugUnitTest`
- [ ] **Step 8: Drop `:core:database-schema`** from `core/healthconnect/build.gradle.kts` if
      unused; otherwise add a comment naming what still needs it.
- [ ] **Step 9: Update `DATA_FLOW.md`** — one persistence boundary.
- [ ] **Step 10: Pre-commit chain, commit** — `refactor(sync): route the Changes API path through HealthIngestionStore (R2-ARCH-002, R2-HC-003, R2-DI-001)`

**Acceptance criteria.** No DAO or `Context` in the synchronizer's constructor; no mapper declared
in `core:healthconnect`; N+1 test green; all pre-existing sync tests green unmodified;
`HealthChangeSynchronizerImpl.kt` ≤ 400 lines.

---

### WP-17 · Tier-aware UI reads, then remove the ViewModel's Health Connect access

**Phase** 3/4 · **Findings** `R2-UI-002`, `R2-ARCH-001`, `R2-ARCH-004` · **Depends on** WP-03,
WP-05 · **Complexity** M-L

**Files:** `core/database/.../data/repository/HeartRateRepositoryImpl.kt` ·
`core/model/.../domain/repository/HeartRateRepository.kt` ·
`feature/workouts/.../WorkoutDetailViewModel.kt` · `feature/sleep/.../SleepHrChart.kt` ·
`feature/vitals/.../heartrate/HrTimelineChart.kt` · `app/src/main/res/values/strings.xml` ·
konsist test sources

- [ ] **Step 1: Write the failing test** — after a rollup, the sleep HR series for a 120-day-old
      session is non-empty and carries `resolution = ONE_MINUTE`.
- [ ] **Step 2: Run, confirm it fails** (empty today).
- [ ] **Step 3: Add tier-aware reads** to `HeartRateRepositoryImpl` mirroring
      `ScoringHistoryRepositoryImpl`; return a `HeartRateSeries(points, resolution)`; combine the
      two DAO `Flow`s with `combine` + `distinctUntilChanged` for the observable timeline.
- [ ] **Step 4: Run, confirm PASS.**
- [ ] **Step 5: Surface the resolution** in the three charts using a new string in
      `strings.xml` (never a hardcoded literal).
- [ ] **Step 6: Write the failing device-filter test** — two devices in the recovery window,
      `HEART_RATE` selection = device A ⇒ chart and HRR metrics contain zero device-B samples.
- [ ] **Step 7: Delete the `hcRepo.readHeartRateSamples` call** from `WorkoutDetailViewModel`;
      source the recovery window from the tier-aware repository. If the samples are genuinely
      absent from Room, extend the workout ingest window by `hrrToleranceSeconds + 3 min` in
      `HealthIngestionCoordinator` rather than reintroducing the HC read.
- [ ] **Step 8: Run, confirm PASS.**
- [ ] **Step 9: Add the konsist rule** banning `HealthConnectRepository` sample reads from
      `feature:*`; add the `UserPreferences` import rule (`R2-ARCH-004`).
- [ ] **Step 10: Pre-commit chain, commit** — `fix(ui): read heart rate from Room across both tiers and drop the ViewModel's Health Connect access (R2-UI-002, R2-ARCH-001)`

**Acceptance criteria.** Post-rollup charts render; resolution labelled; device-filter test green;
konsist rules green.

---

### WP-18 · Allocation, zone determinism, and the Phase-5 cleanups

**Phase** 4/5 · **Findings** `R2-PERF-001`, `R2-PERF-004`, `R2-SCORE-002`, `R2-SCORE-003`,
`R2-UI-001`, `R2-SEC-001`, `R2-HC-006`, `R2-SCORE-004`, `R2-CACHE-002` ·
**Depends on** WP-17 · **Complexity** L (split into sub-commits)

This package is deliberately a container: each bullet is its own commit, but they share a
validation surface and are reviewed together.

- [ ] **18a — Primitive reconstruction** (`R2-PERF-001` level 1). `WarmTierReconstructor` returns
      `IntArray` / (`LongArray`, `IntArray`); update the three call sites. Golden snapshots must
      be **bit-identical**. Re-run `WarmReconstructionBenchmark`; expect ≥ 80 % fewer allocations.
      Commit: `perf(db): return primitive arrays from warm-tier reconstruction (R2-PERF-001)`
- [ ] **18b — Bucket-aware percentile** (`R2-PERF-001` level 2). Give `BaselineComputer`'s
      percentile path a weighted `(value, weight)` walk. Property test against the expanded
      version for equality.
      Commit: `perf(scoring): compute the RHR percentile from weighted buckets (R2-PERF-001)`
- [ ] **18c — Single-pass mappers** (`R2-PERF-004`). Rewrite `HeartRateMapper` / `HrvMapper` to one
      pre-sized materialization, no `Pair` boxing, preserving `SessionLinkSweep`'s
      non-decreasing contract. Golden fixture must be element-identical.
      Commit: `perf(sync): map heart-rate pages in a single pass (R2-PERF-004)`
- [ ] **18d — Zone determinism** (`R2-SCORE-002`, `R2-SCORE-003`). Make the scoring zone required
      in `BaselineComputer` / `HistoricalSleepDayAssembler`; replace every **date-key**
      `ZoneId.systemDefault()` in `feature:*` ViewModels with `prefs.scoringZone()`; rename the
      `core:ui` formatters to name the device zone explicitly; enable the konsist rule. Add the
      cross-zone test (`Pacific/Auckland` device, `America/Los_Angeles` scoring).
      Commit: `fix(scoring): resolve every date key in the stored scoring zone (R2-SCORE-002, R2-SCORE-003)`
- [ ] **18e — Extract `WorkoutDetailLoader`** (`R2-UI-001`). Follow the existing
      `WorkoutsStateFactory` pattern; target ≤ 400 lines;
      `WorkoutDetailViewModelTest` passes with at most constructor updates.
      Commit: `refactor(workouts): extract WorkoutDetailLoader from the ViewModel (R2-UI-001)`
- [ ] **18f — Export cache hygiene** (`R2-SEC-001`). Fixed filename; delete after share; prune the
      three cache directories on startup; verify the backup-rule exclusions; update
      `docs/privacy.md` / `docs/backup-and-data.md`.
      Commit: `fix(privacy): stop accumulating plaintext diagnostic exports in cache (R2-SEC-001)`
- [ ] **18g — Token-expiry detection** (`R2-HC-006`). Narrow the fallback to concrete exception
      types from `connect-client` 1.1.0; log WARN when it fires.
      Commit: `fix(sync): detect Health Connect token expiry by type, not message text (R2-HC-006)`
- [ ] **18h — iTRIMP documentation** (`R2-SCORE-004`) and the `R2-CACHE-002` resolution per OD-5
      (documented trade or per-day split, with the measurement).
      Commit: `docs(scoring): describe the iTRIMP variant accurately and record the daily-transaction trade`

**Validation for the whole package**

```bash
./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest
./gradlew lintRelease
./gradlew :database-benchmark:connectedCheck      # requires a device/emulator
```

**Acceptance criteria.** All Phase-4 and Phase-5 completion criteria in §9 met; every konsist rule
green; benchmarks re-recorded in §11.

---

## 11. Performance Validation Plan

All benchmarks live in the **existing** `:database-benchmark` module (and `:benchmark` for
macrobenchmarks). Do not create a new module. Fixtures are generated by extending
`app/src/profileSupport/kotlin/app/readylytics/health/benchmark/BenchmarkDataSeeder.kt`.

### 11.1 Fixture datasets

| Fixture | Contents | Used by |
|---|---|---|
| `F-SMALL` | 7 days, 1 sample/min HR, 7 sleep sessions, 3 workouts | correctness tests |
| `F-DENSE-30D` | **30 days at 1 Hz ≈ 2.59 M HR samples**, 30 sleep sessions, 20 workouts, 2 devices | the §7 scenario — ingest, upsert, rollup |
| `F-MULTIYEAR` | 3 years at 1 sample/min (≈ 1.58 M samples), 1,095 sleep sessions, 400 workouts | historical rebuild, migration |
| `F-STRADDLE` | one sleep session and one workout crossing a rollup cutoff | `R2-DB-001` correctness |
| `F-WARM` | `F-MULTIYEAR` with everything older than 90 days rolled up | warm-tier reconstruction, UI reads |

### 11.2 Benchmarks and targets

| # | Benchmark | Measures | Baseline (Phase 0) | Target after | Findings |
|---|---|---|---|---|---|
| B1 | `HcReadTransformBenchmark` | wall time + allocations for mapping one 5,000-sample HC page (`HeartRateMapper.mapToInputs`) | _record_ | ≥ 50 % fewer allocated objects; output element-identical | `R2-PERF-004` |
| B2 | `HrUpsertBenchmark` | throughput of `persistHeartRateSamples` for 100 k rows, cold and idempotent-re-ingest | _record_ | measurable improvement **or** finding closed with the measurement | `R2-PERF-003` |
| B3 | `RollupBenchmark` | wall time **and peak WAL** (`RoomWalDiagnostics.walFileSizeInfo()`) rolling up `F-DENSE-30D` | _record_ | WAL stays within 2× of one day's rollup; transaction count ≥ days rolled | `R2-PERF-002` |
| B4 | `WarmReconstructionBenchmark` | allocated bytes/objects building a 30-day warm baseline window from `F-WARM` | _record_ | ≥ 80 % reduction; zero boxed objects | `R2-PERF-001` |
| B5 | `HeartRateAggregationBenchmark` | `getMinuteBuckets` (hot), `getMinuteBuckets` (warm), `loadMergedMinuteBuckets` over one day of `F-DENSE-30D` | _record_ | no regression | — |
| B6 | `IncrementalRecomputeBenchmark` | `recomputeRange` over 8 days (the daily-sync widened window) on `F-MULTIYEAR` | _record_ | no regression; used to resolve OD-5 | `R2-CACHE-002` |
| B7 | `HistoricalRebuildBenchmark` | `resyncRange(skipIngestAndPrune = true)` over 365 days of `F-MULTIYEAR` | _record_ | peak heap does not scale with total history length | `R2-PERF-001`, `R2-DB-001` |
| B8 | `Migration14To15Benchmark` | migration wall time on `F-WARM`'s `hr_minute_buckets` | n/a | if not near-instant, move into `DatabaseMigrationWorker` | `R2-DB-002` |
| B9 | `QueryPlanTest` | `EXPLAIN QUERY PLAN` for every query in §7.5 | n/a | every plan uses an index; never `SCAN TABLE heart_rate_records` | §7.7 |
| B10 | `:benchmark` startup macrobenchmark | cold-start trace; presence of `migrateIfNeeded`/Keystore work on the main thread | _record_ | resolves `R2-PERF-005` either way | `R2-PERF-005` |
| B11 | `WorkerCompletionTest` | `HealthResyncWorker` over `F-MULTIYEAR` under `TestDriver`, killed and resumed at each phase | _record_ | converges; re-done work ≤ one unit | `R2-HC-002`, `R2-CACHE-001` |

### 11.3 Compose measurement (only where this plan touches it)

Only `WorkoutDetailScreen`, `SleepHrChart` and `HrTimelineChart` are affected (WP-17, WP-18e).
Use the existing Compose compiler metrics configuration (`compose_compiler_config.conf`) and the
existing `:benchmark` frame-timing setup from `PERFORMANCE_OPTIMIZATION_PLAN` items M1/M2:
assert no new unstable parameters are introduced and no frame-timing regression on those three
screens. **No broader Compose performance initiative is in scope.**

### 11.4 Recording discipline

Every benchmark number goes into the table above as it is measured, in the commit that measures
it. A target that is not met is recorded as not met — the plan is the record, not an aspiration.

---

## 12. Migration and Compatibility Risks

### 12.1 Room migrations

| Migration | Introduced by | Nature | Risk | Mitigation |
|---|---|---|---|---|
| **v14 → v15** | WP-14 | `hr_minute_buckets` table recreate (PK gains `deviceName`) + `deviceName = '' → NULL` normalization on 5 vitals tables | Table recreate on a table that can hold ~525 k rows/year. **Room has no downgrade path** — a v15 database cannot be opened by a v14 APK. | `MigrationTestHelper` test; B8 duration benchmark; move into `DatabaseMigrationWorker` if slow; ship Phase 2 as its own release with a staged rollout that can be halted |

No other schema change is proposed. All other API changes are compile-time.

### 12.2 Existing user data

- **Already-rolled warm buckets get `deviceName = ''`.** Their true provenance is unrecoverable —
  the raw rows are gone. This is accepted and must be documented in `DATA_FLOW.md`: device pruning
  is correct only for data rolled up **after** v15.
- **Historical scores change twice** across this plan: once at Phase 1 (tier union, plausibility
  unification, 3-point reconstruction, step semantics) and once at Phase 2 only for users whose
  Health Connect data contained records deleted upstream (`R2-HC-001`). Phase 1's change is
  delivered through a single `scoringVersion` bump (WP-10); Phase 2's is delivered by the resync
  the user already triggers.
- **`daily_summaries` is never dropped.** Every score change happens by recompute, so a failed
  recompute leaves the previous (stale but present) values rather than blanks.

### 12.3 Stale synchronization state

| State | Change | Compatibility |
|---|---|---|
| Health Connect change tokens | unchanged in format; WP-08 changes *when* `lastSyncTimestamp` is written | tokens already survive; no reset needed |
| `ResyncCheckpoint` | WP-12 adds `hrPageToken`/`hrvPageToken`, both nullable with `null` defaults | an old checkpoint deserializes to `null` and behaves exactly as today |
| `ResyncCheckpoint.selectionHash` | unchanged; `RECOMPUTE_ONLY_V2` namespace unchanged | a scoring-preference change still invalidates the checkpoint as designed |
| `lastSyncTimestamp` | users already stuck in the `R2-HC-005` state keep a non-zero value | recovery is the Settings resync; WP-08 prevents new occurrences. **Do not** reset the value on upgrade — that would re-trigger a 10-year catch-up for every user |
| `scoringVersion` | bumped once in WP-10 | existing staleness mechanism handles it |

### 12.4 Partial migrations and interrupted workers

Every long operation this plan touches becomes resumable at a bounded unit:
one HC page (WP-12), one ingest chunk (existing), one rollup day (WP-11), one 10 k delete batch
(existing), one 30-day recompute unit (existing). The one operation that is **not** resumable is
`Migration14To15` — SQLite runs it in a single implicit transaction, so a kill mid-migration
rolls back to v14 and the migration re-runs on next open. That is correct, but it means the
migration must be fast enough to complete within a normal app-open window; B8 exists to prove it.

### 12.5 Rollback feasibility

| Phase | Rollback |
|---|---|
| 0 | Trivial — tests and benchmarks only |
| 1 | Revert + ship previous APK. Scores move back on the next resync. No schema change, no stranded data |
| 2 | **Not rollback-safe below v15.** Deletion convergence is separately revertible via `RECONCILE_DELETIONS`. Page-token fields are additive and ignored by older code |
| 3 | Fully revertible — structural only |
| 4 | Fully revertible |
| 5 | Fully revertible |

### 12.6 Release sequencing

1. **Release A — Phase 0 + Phase 1.** One `scoringVersion` bump, one recompute. Release notes
   explain that historical scores may shift.
2. **Release B — Phase 2.** Contains the only schema change and the only data-deleting behavior.
   Ship alone, staged rollout, with `RECONCILE_DELETIONS` telemetry reviewed before widening.
3. **Release C — Phase 3 + Phase 4.** No user-visible change except restored charts for warm
   dates and corrected day attribution.
4. **Release D — Phase 5.** Cleanups.

Do not combine Releases A and B: a rollback from a combined release would require a downgrade
path that does not exist.

---

## 13. Documentation Updates

The repository treats documentation as load-bearing (`AGENTS.md` §Documentation Sync). Each item
below must land **in the same commit** as the code it describes.

| Document | Update required | Triggering work packages |
|---|---|---|
| `internal-docs/DATA_FLOW.md` | Tier union semantics (§3-tier lifecycle); the new **"Determinism across tiers"** subsection with the measured drift bound; `ScoreInvalidation` in the component table and the worker → recompute edge; the resync's new delete semantics (the additive-only claim must be rewritten); schema v15 and the new `hr_minute_buckets` PK; the new prune path; page-token checkpoint fields; the single ingestion persistence boundary; the device-zone/scoring-zone split for UI formatting | WP-03, 05, 06, 11, 12, 13, 14, 16, 17, 18d |
| `AGENTS.md` + `.claude/CLAUDE.md` | Idempotency bullet → "idempotent **within a tier**"; the resync now converges on deletions (id-set-scoped, never blanket) | WP-05, WP-13 |
| `ABOUT.md` | TRIMP dead zone (`MIN_HR_ABOVE_RHR_BPM`); iTRIMP described as a Manzi-inspired fixed-exponent variant; the note that scores for days older than 90 days derive from 1-minute aggregates | WP-05, WP-09, WP-18h |
| `docs/about.md` | Must match `ABOUT.md` verbatim in substance (Documentation Review Checklist item 2) | same |
| In-app strings (`about_*`, `tooltip_*` in `app/src/main/res/values/strings.xml`) | Same three items as `ABOUT.md`; plus the new "1-minute averages" chart-resolution string | WP-05, WP-09, WP-17, WP-18h |
| `docs/privacy.md` | Diagnostic-export retention behavior after WP-18f | WP-18f |
| `docs/backup-and-data.md` | Cache-directory exclusion from backup/D2D; retention vs rollup distinction | WP-18f |
| `docs/index.md` | Only if user-facing claims about data handling change (none currently expected) | — |
| `internal-docs/plans/CORE_SCORING_JVM_MIGRATION.md` | No change — remains DEFERRED on AGP 9.4.0 | — |
| **This plan** | Benchmark results in §11; OD resolutions in §14; findings closed by measurement (`R2-PERF-003`, `R2-PERF-005`) | WP-01, 11, 15, 18 |

**Verification.** The documentation-drift/presence tests (`domain/scoring/**DocumentationDriftTest*`)
must be extended to cover the new `ABOUT.md` content, and they must pass. Per the Documentation
Review Checklist, before approving any PR in this plan confirm: (1) implementation matches
`ABOUT.md`; (2) `docs/about.md` matches `ABOUT.md`; (3) the in-app About page, tooltips and
onboarding copy agree with `ABOUT.md`; (4) the drift tests pass.

---

## 14. Open Decisions

These cannot be resolved from repository evidence. Each names the work package it blocks.

---

**OD-1 · How lossy may the warm tier be?**
*Blocks* WP-05 (`R2-DB-004`).
*Why it matters.* Today a resync of a >90-day-old day produces different scores than the original
computation, and this is undocumented. Any answer is defensible; the current state (silent) is not.
*Options.*
 (a) **Accept and document** the loss; reduce it cheaply with the 3-point (min/avg/max)
     reconstruction and publish the measured drift bound.
 (b) **Bit-exact history** — keep raw samples forever, i.e. abandon the warm tier.
 (c) **Richer sketch** — store per-minute percentiles (p5/p25/p50/p75/p95) instead of min/avg/max.
*Recommended default.* **(a)** — it preserves the nadir that the RHR percentile actually needs, at
zero storage cost, and (b) reverses a deliberate decision landed in `01c944f7`.

---

**OD-2 · What does "no step data for a selected device" mean?**
*Blocks* WP-07 (`R2-HC-004`).
*Why it matters.* The two sync flows currently disagree, so the same day scores differently
depending on which flow ran. Whichever answer is chosen, some users' historical step counts change.
*Options.*
 (a) **`0`** — a selected device that reported nothing means the user genuinely took 0 steps.
     Matches today's resync.
 (b) **Preserve** the stored value — absence of data is not evidence of zero. Matches today's
     daily sync.
*Recommended default.* **(a)**, because the resync is the authority for historical values and
choosing (b) would make a full resync unable to correct a wrong stored step count.

---

**OD-3 · Should the raw HR timeline chart show implausible samples?**
*Blocks* WP-04 (`R2-DB-003`).
*Why it matters.* Determines whether `observeAggregateByTimeRange` and
`_observeSleepHrTimelineForSession` keep their unfiltered behavior.
*Options.*
 (a) **Leave display queries unfiltered** — the chart is a raw-data view; a 250 bpm spike is
     information about the sensor.
 (b) **Filter everywhere** — consistency; the user never sees a value the engine ignored.
*Recommended default.* **(a)**, with the exception documented in `DATA_FLOW.md` and a KDoc line on
each query. Scoring queries are unified regardless.

---

**OD-4 · Keep the 5 bpm TRIMP dead zone?**
*Blocks* nothing (WP-09 extracts the constant either way); gates any value change.
*Why it matters.* It is a real discontinuity applied per minute in the everyday-HR load, and it is
currently invisible to users and to the documentation.
*Options.*
 (a) **Keep the value, document it.** No score changes.
 (b) **Smooth ramp** over `[rhr, rhr+5]`. Changes every historical Load score.
*Recommended default.* **(a)** — per the audit brief, a heuristic is not a defect, and (b) would
move scores for a benefit no evidence in this repository supports.

---

**OD-5 · One transaction or per-day checkpoints for the daily walk-forward?**
*Blocks* WP-18h (`R2-CACHE-002`).
*Why it matters.* Trade between Room Flow-invalidation storms (the `F7` rationale) and losing up
to 8 days of recompute when the user backgrounds the app mid-sync.
*Options.*
 (a) **Keep one transaction**, document the accepted trade with B6's measured redo cost.
 (b) **Split per day** with commit-then-advance, accepting up to 8 invalidation rounds per sync.
*Recommended default.* **(a)** unless B6 shows the all-or-nothing redo is user-visible. The window
is ≤ 8 days and the resync already covers the durable case.

---

**OD-6 · Should `hrv_records` get a warm tier?**
*Blocks* nothing in this plan; affects long-term storage planning.
*Why it matters.* `heart_rate_records` rolls up at 90 days; `hrv_records` never does, so retention
is its only bound. At unlimited retention HRV grows without limit. HRV sample rates are far lower
than HR, so this may never matter — but it is an asymmetry nobody chose explicitly.
*Options.*
 (a) **Leave as-is**; measure `hrv_records` growth on `F-MULTIYEAR` and revisit if it is material.
 (b) **Mirror the HR rollup** with an `hrv_minute_buckets` table.
*Recommended default.* **(a)** — measure first (add the row count to B7's output). Do not build (b)
speculatively.

---

**OD-7 · Does SQLCipher initialization run on the main thread today?**
*Blocks* the Phase-4 step 7 decision (`R2-PERF-005`).
*Why it matters.* Determines whether a risky change near a documented multi-process key race is
justified.
*Resolution mechanism.* B10's startup trace, captured in Phase 0. **Record the answer here when
measured** — the finding is then either actioned or closed.
*Recommended default.* Do nothing unless the trace proves main-thread work; read
`KNOWN_ISSUE_sqlcipher_multiprocess_key_race.md` (recoverable via
`git show <commit>^:internal-docs/plans/KNOWN_ISSUE_sqlcipher_multiprocess_key_race.md`) first.

---

## 15. Definition of Done

### Architecture
- [ ] No `feature:*` module reads Health Connect sample data; konsist rule enforces it.
- [ ] Exactly one ingestion persistence boundary (`HealthIngestionStore`); no DAO is injected
      outside `core:database`; konsist rule enforces it.
- [ ] No entity/input mapper is duplicated across modules.
- [ ] `HealthConnectClient` is a Hilt binding; no `Context` is injected purely to reach a static
      factory.
- [ ] `HealthChangeSynchronizerImpl.kt` and `WorkoutDetailViewModel.kt` are each ≤ 400 lines.
- [ ] The module graph is unchanged and still acyclic; `core:healthconnect` no longer depends on
      `core:database-schema` (or the reason it still does is documented).

### Health Connect correctness
- [ ] A full resync converges on records deleted in Health Connect, scoped to the ingested window
      and the fetched id set — never a blanket delete.
- [ ] A recompute-only pass never deletes, never commits change tokens, never writes
      `lastSyncTimestamp`.
- [ ] A retry never re-persists a committed page; ingestion resumes from a stored page token.
- [ ] A chunk shrink clears the stored page token.
- [ ] A daily sync that escalates to `REQUIRES_HISTORICAL_RESYNC` does not advance
      `lastSyncTimestamp`, and does enqueue the resync.
- [ ] Both sync flows produce identical `stepCount` for identical input.
- [ ] Token-expiry detection is type-based; the message fallback is logged when it fires.

### Large-volume performance (the §7 scenario)
- [ ] No unbounded transaction remains: rollup is day-windowed, cleanup is 10 k-batched,
      recompute is 30-day-unit, ingest is page-streamed.
- [ ] No boxed collection remains in the warm-tier read path; B4 shows ≥ 80 % fewer allocations.
- [ ] B7 shows peak heap does not scale with total history length.
- [ ] B3 shows WAL stays bounded during rollup of `F-DENSE-30D`.
- [ ] B9 shows every scoring query uses an index; no `SCAN TABLE heart_rate_records`.
- [ ] B11 shows the resync worker converges after a kill at every phase, re-doing ≤ one unit.
- [ ] No ANR and no main-thread Room work in any macrobenchmark trace.

### Database behavior
- [ ] Room v15 migration test green; measured duration recorded; migration path decided
      (inline vs `DatabaseMigrationWorker`).
- [ ] Warm buckets carry device provenance; two devices in one minute produce two buckets.
- [ ] `SelectedSourcePruner` prunes warm data; the device picker sees warm-only devices.
- [ ] `deviceName = ''` does not exist in the five vitals tables.
- [ ] Hot and warm never cover the same minute for a `(recordType, sessionId)` — invariant test
      green.

### Scoring correctness
- [ ] Every tier read unions hot ∪ warm; no straddling session or workout loses samples.
- [ ] Every scoring-facing HR query applies the plausibility predicate; the two display queries
      are documented exceptions.
- [ ] Warm reconstruction preserves min/max; the drift bound is measured, asserted by a test, and
      published in `DATA_FLOW.md`.
- [ ] `resolveBaselineRhrRounded(emptyList(), null)` returns the default, not 0.
- [ ] The TRIMP dead zone is a named constant, documented in `ABOUT.md`/`docs/about.md`/`DATA_FLOW.md`.
- [ ] Golden scoring snapshots pass, with every intentional change committed deliberately and
      explained in its commit message.
- [ ] `ScoringSyncScopeOutputsDeterminismTest`, `ResidualFatigueScoringIntegrityTest`,
      `BackfillBaselinesUseCaseTest`, `ScoringRepositoryImplTest` green.

### Deterministic recomputation
- [ ] `ScoreInvalidation.MAX_DEPENDENT_WINDOW_DAYS` is the single statement of dependency depth,
      and the depth-guard test proves no lookback constant exceeds it.
- [ ] Rollup and retention cleanup each enqueue exactly one bounded recompute, and nothing on a
      no-op run.
- [ ] Every date key in scoring, the database layer and `feature:*` ViewModels resolves in
      `prefs.scoringZone()`; konsist rule enforces it; the cross-zone test passes.
- [ ] Two resyncs of the same range over unchanged data produce byte-identical `daily_summaries`
      within a tier.

### Security and privacy
- [ ] At most one plaintext diagnostic export exists in cache at a time; the three cache
      directories are pruned on startup.
- [ ] `diagnostic_logs`, `crash_reports` and `logcat_capture` are excluded from
      `data_extraction_rules.xml` and `full_backup_content.xml`.
- [ ] `docs/privacy.md` and `docs/backup-and-data.md` match the implemented behavior.
- [ ] No new logging of health values outside the sink-gated, lambda-evaluated `AppLog` helpers.

### Documentation
- [ ] Every row of §13 is landed in the same commit as its code.
- [ ] `internal-docs/DATA_FLOW.md` describes tier union, tier drift, `ScoreInvalidation`, the
      resync's delete semantics, schema v15, and the page-token checkpoint.
- [ ] `AGENTS.md` / `.claude/CLAUDE.md` idempotency wording says "within a tier"; the resync's
      delete semantics are described.
- [ ] `ABOUT.md` ≡ `docs/about.md` ≡ in-app About strings; documentation-drift tests green.
- [ ] This plan's §11 result table and §14 decisions are filled in.

### Validation and benchmarks
- [ ] `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest`
      green on every commit.
- [ ] `./gradlew lintRelease` green at each phase boundary.
- [ ] Zero new detekt issues; zero new `@Suppress`; zero baseline edits (any exception carries
      recorded human approval).
- [ ] All 11 benchmarks (B1–B11) have recorded numbers; every target is met or explicitly recorded
      as not met.
- [ ] `codegraph index` run after every new file; `codegraph sync` after every structural move.

---

## Appendix A — Findings index

| ID | Title | Sev | Conf | Status | Phase | WP |
|---|---|---|---|---|---|---|
| `R2-ARCH-001` | ViewModel reads Health Connect directly; merges unfiltered samples | High | High | confirmed | 3/4 | WP-17 |
| `R2-ARCH-002` | Two ingestion persistence paths; changes path bypasses the port | High | High | confirmed | 3 | WP-16 |
| `R2-ARCH-003` | `deviceName` unknown encoded as `""` and `null` | Medium | High | confirmed | 2 | WP-14 |
| `R2-ARCH-004` | `UserPreferences` domain typealias imported both ways | Low | High | confirmed | 3 | WP-17 |
| `R2-DI-001` | `HealthConnectClient` service-located | Medium | High | confirmed | 3 | WP-16 |
| `R2-DI-002` | Time injected in three places, static elsewhere | Medium | High | confirmed | 0 | WP-01 |
| `R2-HC-001` | Full resync cannot converge on deletions | High | High | confirmed | 2 | WP-13 |
| `R2-HC-002` | Retry restarts the whole paged window inside its timeout | High | High | confirmed | 2 | WP-12 |
| `R2-HC-003` | `getOrCreateSourceRef` called once per sample | Medium | High | confirmed | 3 | WP-16 |
| `R2-HC-004` | Step semantics differ between the two flows | Medium | High | confirmed | 1 | WP-07 |
| `R2-HC-005` | `lastSyncTimestamp` written on an incomplete sync | Medium | High | confirmed | 1 | WP-08 |
| `R2-HC-006` | Token-expiry detected by message substring | Low | Medium | suspected | 5 | WP-18g |
| `R2-PERF-001` | Warm reconstruction allocates boxed objects per sample | High | High | confirmed | 4 | WP-18a/b |
| `R2-PERF-002` | Rollup is one unbounded transaction | High | High | confirmed | 2 | WP-11 |
| `R2-PERF-003` | One upsert statement per HR row | Medium-High | High | confirmed | 2 | WP-15 |
| `R2-PERF-004` | Mapper makes three full-page passes with boxing | Medium | High | confirmed | 4 | WP-18c |
| `R2-PERF-005` | SQLCipher init inside the Hilt provider | Medium | Medium | suspected | 0/4 | WP-01, OD-7 |
| `R2-DB-001` | Tier selection all-or-nothing; straddling sessions truncated | High | High | confirmed | 1 | WP-03 |
| `R2-DB-002` | Rollup destroys device provenance | Medium-High | High | confirmed | 2 | WP-14 |
| `R2-DB-003` | Plausibility filter inconsistent across sibling queries | Medium | High | confirmed | 1 | WP-04 |
| `R2-DB-004` | Scores not reproducible across the tier transition | Medium-High | High | confirmed | 1 | WP-05 |
| `R2-SCORE-001` | Undocumented 5 bpm TRIMP dead zone | Medium | High | confirmed (doc) | 1 | WP-09 |
| `R2-SCORE-002` | `BaselineComputer` falls back to the device zone | Medium | Medium-High | confirmed | 4 | WP-18d |
| `R2-SCORE-003` | UI date keys use the device zone | Medium-High | High | confirmed | 4 | WP-18d |
| `R2-SCORE-004` | iTRIMP is a fixed-exponent variant, not Manzi's model | Low | High | confirmed (doc) | 5 | WP-18h |
| `R2-SCORE-005` | `MathUtils` returns `0f` for empty inputs | Low-Medium | High | confirmed | 1 | WP-09 |
| `R2-CACHE-001` | Rollup and cleanup invalidate nothing | High | High | confirmed | 1 | WP-06 |
| `R2-CACHE-002` | Daily walk-forward is one all-or-nothing transaction | Medium | High | confirmed | 4 | WP-18h |
| `R2-UI-001` | `loadWorkout` is an 80-line orchestration body | Medium | High | confirmed | 5 | WP-18e |
| `R2-UI-002` | UI HR reads have no warm-tier fallback | Medium | High | confirmed | 3/4 | WP-17 |
| `R2-SEC-001` | Plaintext diagnostic exports accumulate in cache | Medium | High | confirmed | 5 | WP-18f |
| `R2-SEC-002` | SQLCipher password array retained (accepted) | Low | High | confirmed | — | none |

**Coverage check.** Every Critical/High finding appears in the roadmap: `R2-ARCH-001` (WP-17),
`R2-ARCH-002` (WP-16), `R2-HC-001` (WP-13), `R2-HC-002` (WP-12), `R2-PERF-001` (WP-18a/b),
`R2-PERF-002` (WP-11), `R2-DB-001` (WP-03), `R2-CACHE-001` (WP-06). Every work package references
at least one finding ID. Every schema change (WP-14) carries a migration, a migration test, a
duration benchmark and a rollback assessment. Every scoring change carries a validation strategy
(golden snapshots + a targeted test). The 1,000,000-heart-rate-record scenario is addressed
explicitly in §7 and by fixtures `F-DENSE-30D` / `F-MULTIYEAR` / `F-WARM` in §11.
