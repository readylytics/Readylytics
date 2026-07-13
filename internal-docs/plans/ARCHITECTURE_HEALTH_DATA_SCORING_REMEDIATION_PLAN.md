# Architecture, Health Connect, Performance & Scoring Engine Remediation Plan

> **Status:** Proposed — planning document only. No production code is changed by this document.
> **Scope of audit:** full repository at commit `f9258ee` (branch baseline: `main`), July 2026.
> **Location note:** the task template suggested `docs/plans/`, but `docs/` is the *public*
> Jekyll site source for readylytics (it carries a `CNAME` and is published). Internal
> engineering documents live in `internal-docs/`, so this plan is committed as
> `internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md`.

---

## 1. Executive Summary

**Current architectural condition.** The codebase (≈53,600 lines of Kotlin across 506 main-source
files, 16 Gradle modules) is in substantially better shape than a typical app of this age. It has a
real modular structure (`core/model`, `core/scoring`, `core/database`, `core/healthconnect`,
`core/ui`, `core/designsystem`, 8 feature modules), a pure-Kotlin scoring engine with a large unit
test suite, a checkpointed/resumable historical resync with Changes-API tokens, keyset paging for
reconciliation, deterministic session-linking, a stored scoring timezone, SQLCipher-encrypted Room,
and Konsist architecture tests. Most of the classic disasters (blanket `deleteAll()`, formulas
duplicated in the UI, unkeyed ingestion) have already been engineered out.

**Most important correctness risks.**

1. **Two different TRIMP definitions feed user-visible load metrics** (SCORE-001). The persisted
   `workout_records.trimp` is a *zone-weighted (Edwards-style)* value computed by
   `WorkoutMapper.computeMetrics`, while the daily TRIMP persisted to `daily_summaries` and every
   per-workout display value is a *Banister exponential HR-reserve integration* from
   `ComputeWorkoutTrimpUseCase`. The workout-only ATL/CTL/Strain-Ratio series is built from the
   zone-weighted column; the everyday-HR ATL/CTL series is built from Banister values. `ABOUT.md`
   documents a single Banister pipeline ("TRIMP → ATL → CTL → Strain Ratio → Load Score").
2. **Timezone-inconsistent frozen-baseline clearing** (SCORE-002).
   `RoomHealthIngestionStore.clearFrozenBaselines` resolves day boundaries with
   `ZoneId.systemDefault()`, while every scoring write keys `dateMidnightMs` in the stored scoring
   zone — on devices whose zone differs from the scoring zone, the wrong day rows are cleared.
3. **Unsynchronized whole-history baseline rewrite at every app start** (SCORE-003).
   `BackfillHistoricalBaselinesUseCase` wipes *all* derived baselines and recomputes them outside
   `HealthSyncUseCase.syncMutex` and `ScoringRepositoryImpl.calculationMutex`, racing any
   concurrent sync/resync walk-forward.
4. **Switching the user-selectable Training Load Model recomputes only 8 days** (SCORE-007).
   The model (Banister / Cheng LT-TRIMP / iTRIMP) and its parameters are first-class settings,
   but a change only triggers `sync(windowDays = 8)`, so the persisted TRIMP history — and every
   ATL/CTL EMA built from it for the following ~76 days — silently mixes two models until a full
   resync happens to run.

**Most important scalability risks** (all become acute in the required 1,000,000-heart-rate-records
/ multi-year scenario):

1. `SessionLinker.resolve` performs a linear scan over *every* session span for *every* HR/HRV
   sample — the full-range reconcile phase is O(samples × sessions) (PERF-001).
2. `HealthIngestionCoordinator.ingestWindow` materializes an entire 30-day Health Connect window in
   memory, in up to four successive per-record object representations (HC-001, PERF-004).
3. The walk-forward recompute re-reads an 84-day TRIMP window, a 30-day baseline window and a full
   day of raw HR rows *per recomputed day* — O(days × window) queries over a 3,650-day resync
   (PERF-002).

**Most important Health Connect risks.** A fixed 3-minute `withTimeout` budget over eight
sequential reads per window can put a dense-data resync into a non-terminating retry loop (HC-002);
the "all devices" historical step fetch issues one aggregate call per calendar day — ~3,650
sequential HC calls on a 10-year resync (HC-003); and `ForegroundSyncController.evaluateAndSync`
computes an unbounded foreground catch-up window, violating the repository's own two-flow contract
(HC-007).

**Confidence in the scoring engine.** High for the pure math (`core/scoring` has extensive
boundary/regression tests: EMA, z-scores, readiness caps, phase model, biphasic aggregation,
point-in-time regression tests). Medium for the *data plumbing into* the math — the confirmed
findings above are all at the repository/orchestration boundary, not in the formulas.

**Recommended overall strategy.** Incremental remediation. No rewrite is justified: the module
boundaries, test base, and resync engine are sound. The plan proceeds in six phases —
characterization safety rails first, then correctness (TRIMP unification, timezone alignment,
baseline-freeze synchronization), then Health Connect / database scalability (streamed ingestion,
indexed session linking, batched step aggregation), then architecture/DI hygiene, then incremental
recomputation performance, then residual UI/maintainability work.

---

## 2. Repository Areas Reviewed

**Guidance and documentation**

- `.claude/CLAUDE.md`, `AGENTS.md`, `GEMINI.md` (project rules; two-flow sync contract)
- `internal-docs/DATA_FLOW.md` (585-line load-bearing pipeline map), `ABOUT.md`,
  `internal-docs/INSIGHTS.md`, `docs/about.md` / `docs/privacy.md` (public site)

**Ingestion / sync** (`core/healthconnect`, `core/model`, `app`)

- `HealthConnectRepositoryImpl`, `HealthSyncUseCase`, `DailySyncUseCase`, `ResyncRangeUseCase`,
  `HealthIngestionCoordinator`, `HealthIngestionStore` (+ `RoomHealthIngestionStore`),
  `StepCountFetcher`, `RetryWithBackoff`, `HealthConnectRetryPolicy` (via call sites),
  `HealthChangeSynchronizerImpl`, `HealthChangeTokenStore`, `ResyncCheckpointStore`,
  `SessionLinker`, `SessionLinkReconciler(-Impl)`, `SelectedSourcePruner(-Impl)`,
  `ForegroundSyncController`, `FullHistoricalResyncUseCase`, `WorkerScheduler`,
  `HealthResyncWorker`, `PeriodicHealthSyncWorker`, `DataCleanupWorker`, mappers
  (`SleepDataMapper`, `HeartRateMapper`, `HrvMapper`, `WorkoutMapper`, `StepsMapper`,
  `Weight/BodyFat/BloodPressure/OxygenSaturation DataMapper`)

**Database** (`core/database`, `core/model`)

- `HealthDatabase` (v5), `DatabaseMigrations` (via schema JSON), schema exports
  `core/database/schemas/.../{2,3,4,5}.json`, all 13 DAOs, `RoomTransactionRunner`,
  `RetentionCleanup`, `DatabaseModule` (SQLCipher, WAL, FK pragma)

**Scoring** (`core/scoring`, `core/database`)

- `ScoringRepositoryImpl`, `BaselineComputer`, `Backfill/ComputeHistoricalBaselinesUseCase`,
  `RasCalculator`, `RasScoringStrategy` (ATL/CTL EMA), `LoadScoringStrategy`,
  `SleepScoringStrategy` (via strategy tests), `ComputeSleepMetricsUseCase`,
  `ComputeWorkoutTrimpUseCase`, `ComputeWorkoutLoadMetricsUseCase`,
  `EverydayHeartRateLoadCalculator`, `SleepDayAggregator`, `SleepPercentileRhrCalculator`,
  `ScoringConstants`, `TrimpDateBucketer`, `LoadSourceSelector`, `DailyMetricsMapper`

**UI / DI / app shell**

- `HealthDashboardApplication`, `DashboardViewModel`, `GetDashboardDataUseCase`,
  `DashboardFlowIntermediate`, `ForegroundSyncGateway` consumers, DI modules under `app/.../di/`
  (`DatabaseModule`, `CoroutineDispatchersModule`, others by name), `CleanArchTest` (Konsist),
  `AndroidManifest.xml`, `benchmark/` (contains `StartupBenchmark` only)

**Tests** — `core/scoring/src/test` (50+ suites), `app/src/test` (incl.
`ScoringPointInTimeRegressionTest`, `BackfillBaselinesUseCaseTest`, `DocumentationDriftTest`,
`CleanArchTest`), `app/src/androidTest` (HC repository integration tests).

---

## 3. Current-State Architecture

The implemented architecture matches `internal-docs/DATA_FLOW.md` in outline (with the drift noted
in ARCH-003):

```
Health Connect (androidx.health.connect 1.1.0)
  │  readAllPages<T>() per record type; Changes API tokens per type
  ▼
core/healthconnect  domain/sync
  HealthSyncUseCase (facade, syncMutex)
    ├─ DailySyncUseCase        (recent window; changes-token diff; inline widen ≤7d)
    ├─ ResyncRangeUseCase      (4-phase checkpointed: INGEST → PRUNE → RECONCILE → RECOMPUTE)
    ├─ HealthIngestionCoordinator.ingestWindow  (read → map → device-filter → persist)
    └─ StepCountFetcher / DailyRecomputeSupport / retryWithBackoff
  │  HealthIngestionStore.persist(batch)  — parent txn + 5,000-row HR/HRV batch txns
  ▼
core/database  HealthDatabase v5 (SQLCipher, WAL) — 13 tables, @Upsert by stable HC id
  │  raw DAO reads only (no HC access below this line)
  ▼
core/database ScoringRepositoryImpl.computeDailySummary(day)
  │  per-day: workouts + HR + sleep aggregation → TRIMP/RAS → baselines (frozen snapshots)
  │  → sleep/load/readiness via pure-Kotlin core/scoring strategies → DailySummaryEntity
  ▼
feature/* ViewModels (Hilt) — repo Flows → combine() → stateIn(WhileSubscribed)
  ▼
Compose (M3) screens — collectAsStateWithLifecycle; Vico + Canvas charts
```

Cross-cutting: `ForegroundSyncController` bridges worker progress into StateFlows;
`BackfillHistoricalBaselinesUseCase` runs at every app start from
`HealthDashboardApplication.onCreate`; `DataCleanupWorker`/`RetentionCleanup` enforce retention;
`SelectedSourcePruner` deletes non-selected-device rows during resync.

Two deliberate deviations from "clean" layering exist and are load-bearing to understand:

- The `domain.sync` package in `core/healthconnect` calls `data.healthconnect` mappers and builds
  Room entity types, then converts them to `*Input` DTOs before handing them to
  `HealthIngestionStore` (see ARCH-001).
- `ScoringRepositoryImpl` (data layer) owns scoring orchestration and consumes the data-layer
  `UserPreferences` type; the Konsist rule allowlists specific `data.preferences` types for domain
  use.

---

## 4. Findings Register

Severity: Critical / High / Medium / Low. Confidence: High / Medium / Low.
"Confirmed" = observed directly in code; "Suspected" = plausible from code but needs a
reproduction or measurement.

---

### ARCH-001 — Domain→data dependencies hidden behind fully-qualified names defeat the Konsist guard

- **Category:** Architecture · **Severity:** Medium · **Confidence:** High · **Status:** Confirmed
- **Affected:**
  `core/healthconnect/.../domain/sync/HealthIngestionCoordinator.kt` (uses
  `app.readylytics.health.data.healthconnect.SleepDataMapper/WorkoutMapper/HeartRateMapper/HrvMapper`
  and `app.readylytics.health.data.local.entity.*` via inline fully-qualified references),
  `DailySyncUseCase.kt:118`, `ResyncRangeUseCase.kt:281` (both reference
  `app.readylytics.health.data.healthconnect.WorkoutMapper.zoneThresholds` inline);
  `app/src/test/.../CleanArchTest.kt` (`domain package does not import data package`).
- **Current behavior:** `CleanArchTest` enforces "domain must not import data" by scanning
  *import statements*. The sync use-cases avoid `import` lines and reference data-layer types with
  fully-qualified names inline, so the rule passes while the dependency exists.
- **Evidence:** No `import app.readylytics.health.data.healthconnect.*` in the three files, yet the
  FQNs appear in expression position; the Konsist test only inspects `file.imports`.
- **Root cause:** Zone-threshold construction and entity mapping live in the data layer but are
  needed by domain orchestration; the shortcut satisfied the linter instead of moving the seam.
- **Impact:** The architecture rule is decorative for this seam; future contributors will copy the
  FQN pattern; domain sync code cannot be tested/compiled without the data layer.
- **Remediation:**
  1. Move `WorkoutMapper.zoneThresholds(...)` into a pure-domain helper (e.g.
     `core/model .../domain/heartrate/ZoneThresholds.kt`) — it is already pure math.
  2. Move the DTO→`*Input` mapping out of `HealthIngestionCoordinator`: have the coordinator build
     `*Input` values directly from domain DTOs (they already exist in
     `core/model .../domain/sync/HealthIngestionStore.kt`), and let `RoomHealthIngestionStore` own
     Room-entity construction plus session tagging inputs. `HeartRateMapper`/`HrvMapper` logic that
     assigns `recordType`/`sessionId` is pure; relocate it next to `SessionLinker` in
     `core/model .../domain/sync/link/`.
  3. Strengthen `CleanArchTest` to also scan fully-qualified usages (Konsist
     `text`/`declarations` based check, or a simple regex over file text for
     `app.readylytics.health.data.` occurrences in `domain..` packages).
- **Dependencies:** none. · **Complexity:** Medium. · **Migration risk:** Low (pure moves; behavior
  preserved; existing mapper unit tests move with the code).
- **Acceptance criteria:** No references (imports *or* FQNs) from `domain..` packages to
  `data..` besides the documented allowlist; strengthened Konsist test fails if one is added.

---

### ARCH-002 — Health Connect record→domain mapping duplicated between repository and change synchronizer

- **Category:** Architecture / duplication · **Severity:** Medium · **Confidence:** High · **Status:** Confirmed
- **Affected:** `HealthConnectRepositoryImpl.kt:156–253` and
  `HealthChangeSynchronizerImpl.kt:383–473` — both define private `toDomain()` extensions for
  `SleepSessionRecord`, `HeartRateRecord`, `HeartRateVariabilityRmssdRecord`,
  `ExerciseSessionRecord`, `WeightRecord`, `BodyFatRecord`, `BloodPressureRecord`,
  `OxygenSaturationRecord`, including the sleep-stage-type mapping table.
- **Current behavior:** ~90 duplicated lines. Any change to stage mapping (e.g. how
  `STAGE_TYPE_SLEEPING` folds into LIGHT) must be made twice; the changes path and the bulk path can
  silently diverge, producing different Room rows for the same HC record depending on which path
  ingested it.
- **Root cause:** The change synchronizer was added later and copied the converters.
- **Remediation:** Extract a single `HealthConnectRecordConverters` (internal object in
  `core/healthconnect .../data/healthconnect/`) used by both classes. Pure refactor.
- **Dependencies:** none. · **Complexity:** Low. · **Migration risk:** Low.
- **Acceptance criteria:** one definition of each `toDomain()`; a unit test asserts stage-type
  mapping in one place; grep shows no duplicate mapping tables.

---

### ARCH-003 — Load-bearing documentation drift (DATA_FLOW.md / CLAUDE.md / AGENTS.md vs. code)

- **Category:** Documentation mismatch · **Severity:** Medium · **Confidence:** High · **Status:** Confirmed
- **Affected:** `internal-docs/DATA_FLOW.md`, `.claude/CLAUDE.md`, `AGENTS.md`.
- **Current behavior / evidence:**
  - `DATA_FLOW.md` §1.4 says `@Database(version = 4)` and "11 entities"; the code is
    `HealthDatabase.DATABASE_VERSION = 5` with 13 tables (schema export `5.json` exists). The
    header diagram still says "SQLite v2".
  - `CLAUDE.md`/`AGENTS.md` say "Settings paths map to `ui/settings/{...}`" — settings live in
    `feature/settings/...`; several referenced paths (`ui/sync/SyncViewModel.kt`,
    `ui/scaffold/MainScaffold.kt`) are `app/src/main/kotlin/app/readylytics/health/ui/...` (correct)
    but presented inconsistently with the module-prefixed convention used elsewhere.
  - `DATA_FLOW.md` §1.2 note says resync is "three-phase" in the CLAUDE/AGENTS copy while the
    implementation and the same document's later text describe four phases (INGEST → PRUNE →
    RECONCILE → RECOMPUTE).
- **Impact:** These files are declared load-bearing ("treat a stale DATA_FLOW.md as a broken
  build") and are consumed by agents; drift propagates wrong assumptions (e.g. writing a v4→v5
  migration that already exists).
- **Remediation:** Single documentation pass: DB version/table count, phase count, module-true
  paths in CLAUDE.md/AGENTS.md, and add the missing v4→v5 migration row to §1.4. Extend
  `DocumentationDriftTest` with a check asserting `DATABASE_VERSION` appears in `DATA_FLOW.md`
  (cheap tripwire).
- **Dependencies:** none, but should land *before* implementation phases so later diffs update
  accurate docs. · **Complexity:** Low. · **Migration risk:** None.
- **Acceptance criteria:** the three discrepancies above are gone; drift test guards DB version.

---

### ARCH-004 — Package-structure anomalies (domain packages inside feature modules; doubled path segments)

- **Category:** Architecture · **Severity:** Low · **Confidence:** High · **Status:** Confirmed
- **Affected:** `feature/dashboard/.../app/readylytics/health/domain/dashboard/dashboard/GetDashboardDataUseCase.kt`
  and `InsightDeriver.kt` (in `core/scoring` under `domain/dashboard/dashboard/`);
  `core/model/.../domain/circadian/circadian/CircadianThresholdValue.kt`.
- **Current behavior:** Feature module `feature/dashboard` hosts `domain.*` packages (blurring the
  Konsist boundary between "domain" and "feature" namespaces), and two accidental doubled directory
  segments (`dashboard/dashboard`, `circadian/circadian`) exist.
- **Impact:** Confusing navigation; the feature-package Konsist rules key off package names, so
  domain-named code inside feature modules gets domain-level restrictions applied to
  UI-adjacent code (currently benign but surprising).
- **Remediation:** Rename packages to match module homes (e.g.
  `app.readylytics.health.feature.dashboard.usecase`), collapse doubled segments. Mechanical
  IDE-assisted rename; run `codegraph sync` afterwards per repo rules.
- **Complexity:** Low. · **Migration risk:** Low (rename-only).
- **Acceptance criteria:** no doubled path segments; no `domain.` packages under `feature/*` src.

---

### DI-001 — Hardcoded dispatchers bypass the injected dispatcher qualifiers

- **Category:** DI / coroutine ownership · **Severity:** Low · **Confidence:** High · **Status:** Confirmed
- **Affected:** `ScoringRepositoryImpl.computeDailySummary` (`withContext(Dispatchers.Default)`,
  line 104); `DashboardViewModel.uiState` (`flowOn(Dispatchers.Default)`, line 110);
  `DatabaseModule` (`setQueryCoroutineContext(Dispatchers.IO)` — acceptable, but same theme).
- **Current relationship:** an `@IoDispatcher` qualifier exists (`core/model .../di/CoroutineDispatchers.kt`)
  and is injected in the sync engine, but scoring and dashboard hardcode `Dispatchers.Default`.
- **Why problematic:** tests cannot substitute a deterministic dispatcher for the scoring hot path;
  inconsistent ownership makes it unclear which layer decides threading.
- **Desired ownership/scope:** add a `@DefaultDispatcher` qualifier next to `@IoDispatcher`; inject
  it in `ScoringRepositoryImpl` and `DashboardViewModel`.
- **Safest migration:** add qualifier + binding, replace call sites one at a time; no behavior
  change.
- **Acceptance criteria:** no `Dispatchers.Default`/`Dispatchers.IO` literals outside DI modules
  (enforceable with a Konsist check scoped to `domain..`/`data..`).

---

### DI-002 — Clock/time-source injection is partial

- **Category:** DI / determinism · **Severity:** Low · **Confidence:** High · **Status:** Confirmed
- **Affected:** `DashboardViewModel` injects `java.time.Clock` but still calls `LocalDate.now()`
  (in `validateSelectedDate`, `nowMinutesOfDayFor`, and `DashboardUiState` defaults);
  `DailySyncUseCase`/`ResyncRangeUseCase` use `LocalDate.now(zoneId)` and
  `System.currentTimeMillis()` directly; `ForegroundSyncController.computeWindowDays` uses
  `ZoneId.systemDefault()` + `LocalDate.now(...)`.
- **Impact:** midnight-crossing behavior of a long resync and "today" semantics are untestable;
  the same class mixes injected and ambient time.
- **Remediation:** thread the injected `Clock` (or a small `TimeSource` in `core/model`) through
  the sync use-cases and viewmodel time reads. Keep the stored scoring zone as the zone source.
- **Complexity:** Low–Medium (mechanical, many call sites). · **Migration risk:** Low.
- **Acceptance criteria:** sync engine and dashboard obtain "now" from one injected source;
  a unit test can pin the clock across a simulated midnight rollover.

---

### DI-003 — `TransactionRunner` provided both via `@Provides` and via `@Singleton @Inject` constructor

- **Category:** DI · **Severity:** Low · **Confidence:** High · **Status:** Confirmed
- **Affected:** `DatabaseModule.provideTransactionRunner` manually constructs
  `RoomTransactionRunner(db)`; the class itself is annotated `@Singleton` with an `@Inject`
  constructor.
- **Impact:** two competing construction paths; the class-level `@Singleton` is dead metadata, and
  injecting `RoomTransactionRunner` directly (rather than the interface) would silently create a
  second instance.
- **Remediation:** replace with `@Binds abstract fun bindTransactionRunner(impl: RoomTransactionRunner): TransactionRunner`.
- **Complexity:** Trivial. · **Acceptance criteria:** single binding; no manual `RoomTransactionRunner(...)` construction.

---

### HC-001 — Whole-window in-memory materialization of Health Connect reads

- **Category:** Health Connect / memory · **Severity:** Critical (for the 1M-record scenario) ·
  **Confidence:** High · **Status:** Confirmed (pattern); OOM itself Suspected (needs measurement)
- **Affected:** `HealthConnectRepositoryImpl.readAllPages` (accumulates every page into one
  `MutableList`); `HealthIngestionCoordinator.ingestWindow` (holds all eight record-type lists at
  once, then maps HR: `DomainHeartRateRecord` → flattened `(record, sample)` pairs sorted →
  `HeartRateRecordEntity` list → `HeartRateInput` list); `HeartRateMapper.mapToEntities`
  (`flatMap` + `sortedBy` over all samples).
- **Current behavior:** for a resync chunk of 30 days, all HR samples of the chunk are alive in
  memory simultaneously in ≥3 representations (plus the pair-wrapper allocation per sample during
  sorting). With 1M samples/30 days: ~1M × (DTO + pair + entity + input) objects at peak — several
  hundred MB of transient allocation on a mobile heap, plus one 1M-element sort.
- **Root cause:** `readAllPages` erases HC's native paging; the coordinator API is list-in/list-out.
- **Impact:** OOM or GC-thrash/ANR risk on dense datasets (Samsung/Polar 1 Hz HR); the existing
  `windowBudgetMs` timeout makes long windows fail entirely instead of degrading.
- **Remediation (bounded streaming ingest):**
  1. Add a paged read API to `HealthConnectRepository`:
     `suspend fun readHeartRatePages(from, to, onPage: suspend (List<DomainHeartRateRecord>) -> Unit)`
     (or return `Flow<List<DomainHeartRateRecord>>`), preserving HC's `pageToken` boundaries.
  2. In `ingestWindow`, ingest sessions (sleep/exercise + low-volume types) first, then stream HR
     and HRV pages: map each page against the already-known session spans and
     `HealthIngestionStore.persist` it before fetching the next page. Per-page sorting is
     sufficient because the post-ingest `SessionLinkReconciler` (already run by both flows) is the
     canonical tagger — cross-page order no longer matters.
  3. Keep the 5,000-row Room transaction batching as-is (it already bounds the write side).
- **Dependencies:** PERF-001 (reconciler must be efficient, since streaming leans on it);
  HC-002 (budget policy changes together).
- **Complexity:** Medium–High. · **Migration risk:** Medium — ingestion determinism must be
  re-verified; mitigations: the reconcile pass already guarantees chunk-independence, and the
  duplicate-import/idempotency tests below must pass before/after.
- **Acceptance criteria:** peak resident set during a synthetic 1M-sample chunk bounded (no more
  than one HC page of samples alive at once, verified by benchmark + heap dump); resync of the
  synthetic dataset completes; per-day scores identical before/after the refactor on a fixture DB.

---

### HC-002 — Fixed 3-minute window budget turns dense windows into a permanent retry loop

- **Category:** Health Connect / termination · **Severity:** High · **Confidence:** High ·
  **Status:** Confirmed (mechanism); non-termination Suspected (device/data dependent)
- **Affected:** `HealthIngestionCoordinator.ingestWindow` (`windowBudgetMs = 3 * 60_000L`,
  `withTimeout` around all eight sequential reads); `ResyncRangeUseCase.run` (catches
  `CancellationException` → rethrows); `HealthResyncWorker.doWork` (catches
  `TimeoutCancellationException` → `Result.retry()`).
- **Current behavior:** the eight reads run sequentially inside one `withTimeout`. If a 30-day
  chunk cannot be read in 3 minutes (dense data, slow provider, HC throttling), the timeout throws;
  the checkpoint still points at the same chunk; WorkManager retries with backoff; the same chunk
  times out again — indefinitely. There is no chunk-size adaptation and no attempt counter for
  timeouts.
- **Secondary issue:** `TimeoutCancellationException` *is a* `CancellationException`; every
  intermediate layer must special-case it (the worker does; `ResyncRangeUseCase` logs it as
  "Resync cancelled", which is misleading telemetry; a future catch block that treats it as
  cooperative cancellation would swallow the failure).
- **Remediation:**
  1. Catch `TimeoutCancellationException` *at the `withTimeout` call site* and rethrow as a domain
     exception (`HealthConnectWindowTimeoutException`), so no layer above ever confuses it with
     cancellation.
  2. On timeout, halve the ingest chunk for the retry (persist an optional `chunkDaysOverride` in
     `ResyncCheckpointStore`, floor at 1 day) instead of replaying the identical window; reset to
     the default once a window succeeds.
  3. Optionally parallelize the low-volume reads (`weight/bodyFat/BP/SpO2`) with `async` to reclaim
     budget; keep HR/HRV sequential and streamed (HC-001).
- **Dependencies:** HC-001 (streaming shrinks the budget problem), schema change to the checkpoint
  proto (backward compatible: unknown field defaults to 0 = no override).
- **Complexity:** Medium. · **Migration risk:** Low–Medium (checkpoint proto field addition is
  additive; legacy checkpoints keep working).
- **Acceptance criteria:** a fake HC repository with injected latency that always exceeds the
  budget at 30-day chunks completes the resync via shrunken chunks; telemetry distinguishes
  timeout from cancellation.

---

### HC-003 — Historical step fetch performs one Health Connect aggregate call per calendar day

- **Category:** Health Connect / rate limits · **Severity:** High · **Confidence:** High · **Status:** Confirmed
- **Affected:** `StepCountFetcher.fetchRange` lines 99–110 ("all devices" path: per-day
  `hcRepo.readSteps(dayStart, dayEnd)` in a sequential loop); `fetchWindow` lines 50–65 (same
  pattern, semaphore-limited, window-sized so acceptable).
- **Current behavior:** with no step-source device selected (the default), a 10-year resync issues
  ~3,650 sequential `aggregate` IPC calls, each wrapped in `retryWithBackoff`. This dominates the
  recompute phase wall clock and invites HC rate limiting (which then adds backoff delays).
- **Remediation:** use `HealthConnectClient.aggregateGroupByPeriod` with
  `Period.ofDays(1)` per resync chunk (30 days → 1 call), exposed as
  `HealthConnectRepository.readDailyStepTotals(from, to): Map<LocalDate, Long>`. The API exists in
  the pinned `androidx.health.connect:connect-client:1.1.0`. Keep the per-day fallback only if a
  group-by call fails with an unsupported-operation error.
- **Dependencies:** none. · **Complexity:** Low–Medium. · **Migration risk:** Low —
  `aggregateGroupByPeriod` uses the same dedup semantics as `aggregate`; verify equivalence with an
  androidTest against the existing fixture data (extend `HealthConnectRepositoryImplTest`).
- **Acceptance criteria:** step fetch for a 3,650-day range performs ≤ (range/chunkDays)+ε HC
  calls; per-day totals equal the per-day aggregate results on fixture data.

---

### HC-004 — Changes-API upserts bypass session linking and workout metric computation

- **Category:** Health Connect / correctness plumbing · **Severity:** Medium · **Confidence:** High ·
  **Status:** Confirmed (mechanism); user-visible impact bounded by the reconcile pass
- **Affected:** `HealthChangeSynchronizerImpl.upsertRecord` — HR:
  `HeartRateMapper.mapToEntities(listOf(domainHr), emptyList(), emptyList())` (all samples tagged
  RESTING/`sessionId=null`); HRV: `HrvMapper.mapToEntities(..., emptyList())`; exercise:
  `WorkoutMapper.mapExerciseSession(domainExercise, emptyList(), thresholds)` (trimp=0, avgHr=0,
  zone minutes=0 at upsert time).
- **Current behavior:** rows written by the changes path are temporarily wrong and are only
  corrected because `DailySyncUseCase` re-ingests and reconciles the affected window afterwards.
  Correctness therefore depends on the invariant "every change-affected date is inside the
  re-ingested/reconciled window". That holds today (`oldestTargetDay` derives from
  `outcome.affectedDates`), but nothing enforces it, and the intermediate zero-TRIMP workout row is
  visible to any Flow collector between the change application and the reconcile.
- **Remediation:** in `upsertRecord`, resolve links against the *local DB* session spans for the
  record's time range (one `getOverlapping` query) instead of empty lists, and for exercise
  sessions compute metrics from stored HR rows (`SessionLinkReconcilerImpl.recomputeWorkouts`
  already contains this logic — extract and reuse). This makes changes-path rows correct at write
  time rather than eventually.
- **Dependencies:** ARCH-002 (shared converters), PERF-001 (shared link resolution helper).
- **Complexity:** Medium. · **Migration risk:** Low. 
- **Acceptance criteria:** a unit test applying an `UpsertionChange` for an HR record overlapping a
  stored sleep session tags samples SLEEP immediately; a workout upsert has non-zero metrics
  without requiring the follow-up reconcile.

---

### HC-005 — Deleted Steps records never mark days as affected → stale step counts

- **Category:** Health Connect / deletion handling · **Severity:** Medium · **Confidence:** High · **Status:** Confirmed
- **Affected:** `HealthChangeSynchronizerImpl.getAffectedDatesForDeletedRecord` —
  `HealthDataType.STEPS -> emptySet()`; `deleteRecordLocal` STEPS → no-op (steps have no raw
  table; daily totals are persisted on `DailySummaryEntity.stepCount`).
- **Current behavior:** an upserted `StepsRecord` marks its dates affected via
  `getDatesForRecord`, but a `DeletionChange` for steps yields no affected dates, so the day's
  `stepCount` is never re-fetched; the deleted steps remain in the daily summary until some other
  change touches that day or a full resync runs.
- **Root cause:** deletion-date resolution requires the *local* record's time range, and steps have
  no local raw rows to look up.
- **Remediation options** (pick one — see Open Decisions OD-3):
  - (a) Persist a minimal `step_records` daily/interval table during sync (id, start, end, count,
    deviceName) purely to resolve deletion dates (schema addition, migration v5→v6); or
  - (b) On any STEPS `DeletionChange`, conservatively mark the *sync window* days affected (cheap,
    slightly over-recomputes, no schema change); recommended default.
- **Complexity:** Low (option b). · **Migration risk:** none (b) / Low (a).
- **Acceptance criteria:** test: apply steps upsert for day D, commit, then apply a
  `DeletionChange` for that record — the next sync outcome contains D in `affectedDates` and the
  recomputed summary reflects the reduced total.

---

### HC-006 — Sleep sessions without stage data ingest as zero-duration sleep

- **Category:** Health Connect / record-type semantics · **Severity:** Medium · **Confidence:** Medium ·
  **Status:** Suspected (mechanism confirmed; prevalence of stage-less sources needs verification)
- **Affected:** `SleepDataMapper.mapSleepSession` — `durationMinutes = deep+rem+light` (stage-derived
  only); `efficiency = totalSleepMinutes / timeInBed`.
- **Current behavior:** Health Connect permits `SleepSessionRecord` with an empty `stages` list
  (manual entries; several third-party writers). Such a session maps to
  `durationMinutes = 0`, `efficiency = 0`, which then fails `validateNight`
  (`durationValid = false`), is excluded from baselines, and produces no usable sleep for the day —
  even though start/end describe an 8-hour night.
- **Remediation:** when `stages` is empty, fall back to
  `durationMinutes = (endTime − startTime)` with `lightSleepMinutes = durationMinutes` explicitly
  flagged (e.g. `stagesUnavailable` marker via the existing suspicious-stage pathway) so Duration
  scoring works and Architecture is reweighted by the already-documented "suspicious sleep-stage
  reweight" (ABOUT.md) instead of zeroing the night. Confirm intended behavior first (Open
  Decisions OD-2).
- **Dependencies:** product confirmation; touches `ComputeSleepMetricsUseCase` weighting path.
- **Complexity:** Medium. · **Migration risk:** Medium — changes historical scores for affected
  users on next recompute; must ship with release notes + the score-change communication in §12.
- **Acceptance criteria:** fixture: stage-less 8h session scores a Duration component and marks
  architecture unavailable; existing staged-session fixtures unchanged.

---

### HC-007 — `evaluateAndSync` builds an unbounded foreground catch-up window

- **Category:** Health Connect / two-flow contract · **Severity:** Medium · **Confidence:** High · **Status:** Confirmed
- **Affected:** `ForegroundSyncController.evaluateAndSync` + `computeWindowDays` (lines 47–99,
  142–151).
- **Current behavior:** for a returning user (`lastSyncTimestamp > 0`), `windowDays =
  daysSince + 1` with **no upper bound** — after a 90-day absence the app-open sync performs a
  91-day foreground ingest (single `ingestWindow` call under the 3-minute budget, HC-002) plus a
  91-day walk-forward on the UI-blocking sync path. This contradicts the repository contract
  ("Pull-to-refresh = CURRENT DAY ONLY … `catchUpSync` reserved for genuine first-launch") — the
  contract governs `triggerDailySync`, but `evaluateAndSync` recreates the unbounded-window problem
  through the side door. `computeWindowDays` also uses `ZoneId.systemDefault()` rather than the
  scoring zone.
- **Remediation:** cap the foreground window at `MAX_INLINE_RECOMPUTE_DAYS` (7, reuse the existing
  constant from `DailySyncUseCase` by promoting it to `ScoringConstants` or a sync-config object);
  when `daysSince` exceeds the cap, run the capped sync *and* enqueue
  `WorkerScheduler.scheduleResyncWorker()` for the remainder (the change-token path will usually
  already trigger `REQUIRES_HISTORICAL_RESYNC` in this situation — align the two mechanisms).
  Resolve dates with `prefs.scoringZone()`.
- **Dependencies:** none. · **Complexity:** Low. · **Migration risk:** Low.
- **Acceptance criteria:** unit test with `lastSyncTimestamp` 90 days old: `sync` is invoked with
  `windowDays ≤ 7` and the resync worker is scheduled.

---

### HC-008 — Exception handling that loses failures

- **Category:** Kotlin / error modeling · **Severity:** Medium · **Confidence:** High · **Status:** Confirmed
- **Affected:**
  - `DailySyncUseCase.run` final `catch (e: Exception) { Result.failure("Sync failed", "SYNC_ERROR") }`
    — the exception is not logged and not attached; diagnosis of field sync failures is impossible.
    `HealthConnectPermissionRevokedException` is also flattened to `SYNC_ERROR` here (the resync
    path deliberately rethrows it; the daily path hides it, so the foreground flow never routes the
    user to the permission-recovery flow).
  - `HealthConnectRepositoryImpl.readWeightRecords` (and body-fat/BP/SpO2 twins): generic
    `catch (e: Exception) → emptyList()` — transient IO errors are indistinguishable from "user has
    no data"; the window then "succeeds" with silently missing optional metrics.
  - `GetDashboardDataUseCase.invoke` catch-all → `CARD_GENERATION_ERROR` without the cause.
  - `HealthChangeSynchronizerImpl.isTokenExpiredException` — string matching on
    `e.message` ("expired"/"invalid token") to classify errors.
- **Remediation:** log the throwable at every terminal catch (`logE` with the exception); rethrow
  `HealthConnectPermissionRevokedException` from `DailySyncUseCase` (or return a dedicated
  `PERMISSION_REVOKED` failure code the controller maps to the recovery flow); for optional-metric
  reads, swallow only `SecurityException`/permission exceptions and let transient IO propagate into
  `retryWithBackoff`; replace message sniffing with the HC client's typed remote exceptions where
  available (fall back to message match only as a last resort, kept in one function).
- **Complexity:** Low–Medium. · **Migration risk:** Low (failures become *visible*, behavior on
  success unchanged; periodic worker already routes `SecurityException` to `Result.failure()`).
- **Acceptance criteria:** revoking a permission mid-daily-sync surfaces the permission failure
  code to `ForegroundSyncController` (unit test with throwing fake); no terminal catch without
  logging.

---

### HC-009 — Implicit `windowDays = 8` default is the undocumented settings-change refresh window

- **Category:** Clean code / hidden contract · **Severity:** Low (as a code smell; its correctness
  consequence is SCORE-007) · **Confidence:** High · **Status:** Confirmed
- **Affected:** `HealthSyncUseCase.sync(windowDays: Int = 8, ...)`;
  `app/.../domain/sync/HealthDataRefreshAdapter.refreshAffectedWindow()` (calls `sync()` with no
  argument); callers of `HealthDataRefresh.refreshAffectedWindow()` in
  `UISettingsViewModel` (TRIMP model + Banister/Cheng/iTRIMP parameter changes),
  `PhysiologySettingsViewModel`, `HeartRateZonesViewModel`, `SyncSettingsViewModel`.
- **Current behavior:** every scoring-relevant settings change triggers a foreground
  `sync(windowDays = 8)` — the "8" lives silently as a parameter default on the facade rather
  than as a named settings-refresh policy, and is invisible at every call site. The
  `ForegroundSyncController.executeSync` `else` branch that could also hit the default is
  unreachable from current call sites.
- **Impact:** the settings-refresh horizon (8 days) is an undocumented magic constant; nobody
  reading `UISettingsViewModel` can tell how much history a model switch recomputes. Whether 8
  days is *sufficient* is a separate correctness question — see SCORE-007.
- **Remediation:** remove the parameter default; introduce a named constant/config
  (`SETTINGS_REFRESH_WINDOW_DAYS`) passed explicitly by `HealthDataRefreshAdapter`; delete the
  unreachable `else` branch in `executeSync`; document the settings-refresh flow in
  `DATA_FLOW.md` §1.2 (it is currently absent — the doc describes only pull-to-refresh, periodic,
  and historical resync).
- **Dependencies:** SCORE-007 decides what the window *should* be per setting.
- **Acceptance criteria:** no implicit window defaults on the facade; settings-refresh flow
  documented; every `sync(...)` call site names its window.

---

### HC-010 — `readStepsRange` is production-dead and uses the device timezone

- **Category:** Clean code / timezone hygiene · **Severity:** Low · **Confidence:** High · **Status:** Confirmed
- **Affected:** `HealthConnectRepositoryImpl.readStepsRange` (line 317:
  `ZoneId.systemDefault()` grouping); only referenced from tests/fakes.
- **Remediation:** delete the method (and its interface declaration + fake/test coverage), or —
  if kept for HC-003's group-by implementation — take an explicit `ZoneId` parameter. Prefer
  deletion; HC-003 introduces a properly-parameterized replacement.
- **Acceptance criteria:** no production API groups records by `ZoneId.systemDefault()`.

---

### PERF-001 — Session-link resolution is O(sessions) per sample; reconcile is O(samples × sessions)

- **Category:** Performance / algorithmic complexity · **Severity:** Critical (full-history resync) ·
  **Confidence:** High · **Status:** Confirmed
- **Affected:** `core/model .../domain/sync/link/SessionLinker.resolve` (`findContaining` does
  `sessions.asSequence().filter{...}.minWithOrNull(...)` over the *entire* span list);
  `SessionLinkReconcilerImpl.relinkHeartRate`/`relinkHrv` (call `resolve` once per row over the
  full-range span lists).
- **Current complexity:** reconcile loads all sleep + workout spans for the whole resync range
  (§1.2.1 runs it once over `[start−1d, end]`). For 5 years: ~1,800 sleep + ~1,000 workout spans;
  with 5–20M HR rows the reconcile performs 10^10-order comparisons plus a sequence/comparator
  allocation per row. This phase alone can take tens of minutes of pure CPU and is executed on
  every full resync and (small-range) on every daily sync.
- **Recommended target complexity:** O((samples + sessions) log sessions) or better.
- **Remediation:** exploit the fact that `getKeysetPage` already returns rows in ascending
  `(timestampMs, id)` order:
  1. Pre-sort span lists once by `startTime` (they are small).
  2. Implement a sweep: maintain a pointer + a small min-heap (or sorted active-set) of spans whose
     `startTime ≤ sampleMs`, evicting spans with `endTime < sampleMs`; the active set yields the
     earliest-`(startTime, id)` containing span in O(log k). Equivalent semantics to
     `SessionLinker.resolve` (sleep > workout precedence; earliest `(startTime,id)` tie-break) must
     be property-tested against the existing implementation with randomized overlapping spans.
  3. Keep `SessionLinker.resolve` as the reference implementation for single lookups (HC-004 uses
     it with tiny span lists) and for the property test oracle.
- **Dependencies:** none (pure Kotlin, `core/model`). · **Complexity:** Medium. ·
  **Migration risk:** Low — property test equivalence + existing reconcile tests gate it.
- **Acceptance criteria:** reconcile of the synthetic 1M-sample/1-month + 200-session fixture
  completes in seconds, not minutes (benchmark in §11); randomized property test proves link
  equality with the naive resolver on ≥10^6 generated cases.

---

### PERF-002 — Walk-forward recompute re-derives whole windows per day: O(days × window) queries

- **Category:** Performance / recomputation · **Severity:** High · **Confidence:** High · **Status:** Confirmed
- **Affected:** `ResyncRangeUseCase` recompute loop → `DailyRecomputeSupport.recomputeDay` →
  `ScoringRepositoryImpl.computeAndPersistDailySummary` (per day: `settingsRepo.userPreferences.first()`
  DataStore read + `calculationMutex` acquire; `workoutDao.getTrimpPoints` over an 84-day window;
  `dailySummaryDao.getEverydayTrimpPoints` over 84 days; `heartRateDao.getByTimeRange` for the full
  day (`SELECT *`, then per-sample object mapping for the everyday-HR calculator);
  `BaselineComputer.compute*Between` 30-day sleep-session + sleep-HR-projection reads;
  `sumRasLastSixDays`); plus `checkpointStore.save` (a proto-DataStore write) *per recomputed day*
  in `ResyncRangeUseCase` (lines 336–345).
- **Current behavior / estimate:** for a 3,650-day resync, ~11–14 queries/day → ~45k queries; the
  30-day sleep-HR projection alone re-reads each night's samples ~30×. The per-day DataStore
  checkpoint write adds ~3,650 small file commits. (Contrast: the app-start baseline backfill
  already uses the *batched* `BaselineComputer.computeBackfillBaselines` with "a fixed, small
  number of DB reads for the whole range" — the resync loop never got the same treatment.)
- **Recommended target:** O(days + total rows) — each raw row read a constant number of times.
- **Remediation (incremental, in order of payoff):**
  1. Snapshot `UserPreferences` **once** per walk-forward and pass it down (also fixes SCORE-004).
  2. Checkpoint every N days (e.g. 30) instead of every day; resume granularity of a month is
     acceptable because recompute is idempotent.
  3. Fetch `getTrimpPoints`/`getEverydayTrimpPoints` once for the whole recompute range into a
     date-bucketed map, maintain the EMA *incrementally* per day (the EMA recurrence needs only the
     previous day's value — `RasScoringStrategy.computeEmaSeries` already exists for this).
  4. Extend the batched baseline computation (`computeBackfillBaselines`) to drive the resync
     recompute's baseline needs, or maintain rolling 30-day window state (deque of nightly nadir /
     lnRMSSD values) across the walk-forward.
  5. Replace the per-day full-row everyday-HR fetch with a SQL minute-bucket aggregation
     (see PERF-006).
- **Dependencies:** SCORE-001 should be decided first (it changes what the TRIMP series is);
  SCORE-004 is subsumed by step 1.
- **Complexity:** High (step 4), Low–Medium (steps 1–3). · **Migration risk:** Medium — determinism
  must be regression-locked with a full-DB fixture: identical `DailySummaryEntity` rows
  before/after (this is exactly what the frozen-baseline stable-order contract in DATA_FLOW.md
  §1.4 demands).
- **Acceptance criteria:** synthetic 2-year dataset resync recompute phase ≥5× faster and query
  count O(days); byte-identical summary rows vs. the per-day implementation on the fixture.

---

### PERF-003 — Source-record lookups use `substr()` predicates → full table scans in the changes path

- **Category:** Performance / query plan · **Severity:** Medium (High with 1M+ rows and frequent
  changes) · **Confidence:** High · **Status:** Confirmed
- **Affected:** `HeartRateDao.getBySourceRecordId` / `deleteBySourceRecordId`
  (`WHERE id = :src OR substr(id, 1, length(:src) + 1) = :src || '_'`); same pattern in `HrvDao`,
  `WeightRecordDao`, `BodyFatRecordDao`, `BloodPressureRecordDao`, `OxygenSaturationRecordDao`
  (composite-ID tables). Called per changed/deleted record by
  `HealthChangeSynchronizerImpl.deleteRecordLocal`/`getAffectedDatesForDeletedRecord`.
- **Current behavior:** SQLite cannot use the PK index for the `substr(...)` disjunct, so each
  changed HR record triggers a full scan of `heart_rate_records`. A nightly changes batch touching
  50 records scans a 5M-row table 100 times (dates + delete).
- **Remediation:** composite IDs are `${hcId}_${timestampMs}` — a pure prefix. Replace with an
  index-sargable range predicate:
  `WHERE id = :src OR (id > :src || '_' AND id < :src || '_' || X'FF')` — or simpler, since `_` is
  the fixed separator: `WHERE id >= :src || '_' AND id < :src || '`'` (next char after `_`), plus
  the equality arm. Verify with `EXPLAIN QUERY PLAN` in an androidTest that the PK index is used.
- **Complexity:** Low. · **Migration risk:** Low — semantics identical for the ID alphabet in use
  (HC UUIDs + `_` + digits); add a unit test with adversarial IDs (`abc` vs `abc_1` vs `abcd_1`).
- **Acceptance criteria:** `EXPLAIN QUERY PLAN` shows `SEARCH ... USING INDEX` (not `SCAN`) for
  both queries; behavior-equality test passes.

---

### PERF-004 — Per-record representation churn in the ingestion funnel

- **Category:** Performance / allocation · **Severity:** Medium · **Confidence:** High · **Status:** Confirmed
- **Affected:** `HealthIngestionCoordinator` (entity lists → `.map { it.toInput() }` full copies for
  all nine record types; `initialWorkouts` mapped once with empty samples and then all workouts
  mapped a second time with samples); `RoomHealthIngestionStore` (inputs → entities again).
- **Current behavior:** every HR sample is allocated ≥3 times before write (DTO→entity→input→entity)
  purely to launder types across the domain/data seam introduced by ARCH-001's constraint.
- **Remediation:** once ARCH-001 moves tagging into the domain layer, the coordinator should build
  `HeartRateInput` etc. *directly* from DTOs (one allocation), and `RoomHealthIngestionStore` maps
  input→entity once (second allocation, unavoidable with Room). Compute workout metrics once by
  grouping samples per session *before* constructing workout inputs, removing the double
  `mapExerciseSession`.
- **Dependencies:** ARCH-001, HC-001 (streaming makes this per-page). · **Complexity:** Medium
  (bundled with those refactors). · **Migration risk:** Low.
- **Acceptance criteria:** allocation profile of a synthetic ingest shows ≤2 objects per persisted
  sample; workout metrics computed exactly once per workout per window.

---

### PERF-005 — UI observes raw heart-rate rows; 5,000-row ingest batches re-trigger day-sized queries

- **Category:** Performance / Flow invalidation · **Severity:** Medium · **Confidence:** High
  (re-execution) / Medium (user-visible jank) · **Status:** Confirmed (mechanism)
- **Affected:** `HeartRateDao._observeByTimeRange` consumed via `HeartRateRepositoryImpl` by
  `DashboardFlowIntermediate.kt:210` (dashboard HR day summary) and
  `HeartRateDetailViewModel.kt:54`; `HeartRateDao.observeSleepHrSince` (exposed, currently
  repository-internal).
- **Current behavior:** every committed 5,000-row HR transaction during sync/resync invalidates
  `heart_rate_records`; each active observer re-runs a full-day `SELECT *` (20–35k rows at 1 Hz)
  and re-materializes entities, then `distinctUntilChanged` usually discards the result (for
  historical days) after paying the query + mapping cost. During a long resync with the dashboard
  open this is a steady background churn on the query executor.
- **Remediation:**
  1. Replace the dashboard's day-summary consumption with a SQL aggregate observable
     (min/max/avg/count + per-zone minute counts via `GROUP BY` bucket), so invalidations re-run a
     cheap aggregation rather than a full materialization.
  2. For `HeartRateDetailViewModel` (which genuinely needs the series), debounce/`sample` the flow
     (e.g. 500 ms) and keep `distinctUntilChanged`.
- **Complexity:** Medium. · **Migration risk:** Low (display-only path; verify chart parity).
- **Acceptance criteria:** during a synthetic resync with dashboard subscribed, total rows
  materialized by observers drops by >90% (measurable via a test Room query callback / query log).

---

### PERF-006 — Everyday-HR load computes minute buckets in Kotlin from full-day `SELECT *`

- **Category:** Performance / missing DB aggregation · **Severity:** Medium · **Confidence:** High ·
  **Status:** Confirmed
- **Affected:** `ScoringRepositoryImpl.computeDailySummary` lines 207–233 (fetch all day rows, map
  each to `ComputeWorkoutTrimpUseCase.HeartRateSample`), `EverydayHeartRateLoadCalculator`
  (per-sample bucketing).
- **Current behavior:** per scored day, all raw HR rows are loaded and re-bucketed in memory. In
  the walk-forward this multiplies by days (part of PERF-002); at 1 Hz it is 86k rows/day.
- **Remediation:** add a DAO aggregation
  `SELECT (timestampMs - :dayStart)/60000 AS bucket, AVG(beatsPerMinute) AS avgBpm, COUNT(*) AS n FROM heart_rate_records WHERE timestampMs >= :dayStart AND timestampMs < :dayEnd GROUP BY bucket`
  and feed `EverydayHeartRateLoadCalculator` bucket rows instead of raw samples (the calculator's
  plausibility filter 30–230 bpm moves into the WHERE clause; sleep/workout interval exclusion
  stays in Kotlin — it operates on ~1,440 buckets, not 86k samples). The calculator remains pure
  Kotlin; only its input shape changes.
- **Dependencies:** PERF-002 (same code path); keep formula identical (Banister per-minute TRIMP on
  the bucket average — unchanged semantics because the current code already averages within the
  bucket before classifying).
- **Complexity:** Medium. · **Migration risk:** Low–Medium — floating-point equality: SQL `AVG` is
  double vs. Kotlin float accumulation; lock with a tolerance-based regression test and the §11
  fixture.
- **Acceptance criteria:** identical (±1e-3 TRIMP) results on fixture days; per-day scored query
  returns ≤1,440 rows.

---

### DB-001 — `heart_rate_records` uses a ~45-byte TEXT PK duplicated into three secondary indices

- **Category:** Database / storage & write amplification · **Severity:** Medium · **Confidence:**
  High (structure) / Medium (impact) · **Status:** Confirmed structure; impact needs measurement
- **Affected:** `HeartRateRecordEntity` (`id = "${hcUuid}_${timestampMs}"` TEXT PK), indices
  `timestampMs`, `(recordType, timestampMs)`, `(sessionId, recordType, beatsPerMinute)` (each
  secondary index stores the full PK per row); `hrv_records` analogous.
- **Current behavior:** at 5M rows, each row's ~45-byte key is stored 4× (table B-tree + 3
  indices) ≈ 900 MB of pure key bytes worst-case before page overhead — plus slower inserts (three
  index updates with long string comparisons per row) during 5,000-row ingest batches.
- **Remediation (optional, measure first):** introduce an `INTEGER PRIMARY KEY` rowid surrogate
  with a `UNIQUE` index on the current composite string (needed for upsert idempotency), or split
  the composite into `(sourceRecordId TEXT, timestampMs INTEGER)` with a composite UNIQUE — the
  latter also eliminates PERF-003's prefix hack entirely (`WHERE sourceRecordId = ?`).
  Requires a v5→v6 copy migration of the largest table — schedule behind a benchmark that
  quantifies the win (§11), not before.
- **Dependencies:** PERF-003 (alternative solutions overlap); backup/restore compatibility
  (`LocalBackupManager` restores DB files — a schema bump must keep migrations linear).
- **Complexity:** High (large-table migration on-device). · **Migration risk:** High — copy
  migration of a multi-GB table must be chunked/resumable or gated to run under a foreground
  progress UI. This is why it is *optional* and last.
- **Acceptance criteria:** decided by benchmark: proceed only if ingest throughput improves ≥30%
  or DB size shrinks ≥25% on the 1M fixture; migration tested with WAL + SQLCipher on a seeded
  multi-GB DB, interruptible and resumable.

---

### DB-002 — Redundant index on `daily_summaries.dateMidnightMs`

- **Category:** Database · **Severity:** Low · **Confidence:** High · **Status:** Confirmed
- **Affected:** schema v5: `daily_summaries` PK `dateMidnightMs` **and**
  `index_daily_summaries_dateMidnightMs` on the same single column.
- **Impact:** wasted writes/space (small table; cosmetic).
- **Remediation:** drop the index in the next schema bump (piggyback on whichever migration lands
  first; do not bump the version solely for this).
- **Acceptance criteria:** schema export no longer contains the redundant index.

---

### SCORE-001 — Two TRIMP definitions: zone-weighted stored workout TRIMP vs. Banister everywhere else

- **Category:** Scoring — likely implementation bug + documentation mismatch (needs product
  confirmation) · **Severity:** High · **Confidence:** High (inconsistency is real; intent is the
  open question) · **Status:** Confirmed inconsistency
- **Affected files/symbols:**
  - `core/model .../data/healthconnect/WorkoutMapper.computeMetrics` — stored
    `workout_records.trimp = Σ zoneMinutes[i] × weight(1..5)` (Edwards-style zone TRIMP).
  - `SessionLinkReconcilerImpl.recomputeWorkouts` — rewrites the same zone-weighted value.
  - `WorkoutDao.getTrimpPoints` — reads that stored column.
  - `ScoringRepositoryImpl.computeDailySummary` lines 424–434 — workout-only ATL/CTL/Strain
    Ratio/Load Score are EMAs **of the zone-weighted column**, while lines 151–196 compute the
    persisted daily `trimpWorkoutOnly` via `ComputeWorkoutTrimpUseCase` (**Banister/Cheng/iTRIMP**
    per-sample integration), and lines 439–450 build the everyday-HR ATL/CTL from persisted
    Banister-based `trimpEverydayHr` values (with today's fresh value injected).
  - `ComputeWorkoutLoadMetricsUseCase` / `GetWorkoutDisplayMetricsUseCase` — per-workout displayed
    TRIMP is Banister (fresh integration; `storedTrimp` only as fallback).
  - `ABOUT.md` ("Per-profile training-load multiplier … Banister training-load model", "Readiness/
    load continue to use TRIMP → ATL → CTL → Strain Ratio → Load Score"; implementation pointers
    cite `RasCalculator.kt`, `ComputeWorkoutTrimpUseCase.kt`).
- **Current behavior:** with the default "Workout only" load source, the Strain Ratio and Load
  Score the user sees are EMAs of a *different metric* (zone-minutes) than the TRIMP number shown
  on the same screen (Banister). The two models diverge systematically: zone TRIMP scales linearly
  with zone weight while Banister scales exponentially with HR-reserve, so high-intensity days are
  under-weighted in the workout-only ATL/CTL relative to the displayed TRIMP, and switching the
  strain source preference silently switches load *models*, not just data coverage. The per-workout
  "TRIMP ≥ 90 / TRIMP/min ≥ 1.75" classification thresholds (ABOUT.md) are applied to Banister
  values, while `trimpByDate` passed into `ComputeWorkoutLoadMetricsUseCase` (from
  `getTrimpPoints`) is zone-based — the with/without-workout ATL deltas there subtract a Banister
  workout TRIMP from a zone-based day total (`ComputeWorkoutLoadMetricsUseCase` lines 29–54).
- **Root cause:** `workout_records.trimp` predates the multi-model TRIMP engine; the reconcile
  pass cannot compute Banister TRIMP (it lacks RHR baseline / hrMax / gender inputs), so the stored
  column stayed zone-based while everything display-facing moved to `ComputeWorkoutTrimpUseCase`.
- **Impact:** Load Score / Strain Ratio / Readiness (workout-only mode) are internally consistent
  (same units in ATL and CTL) but inconsistent with displayed TRIMP, with the everyday-HR variant,
  with the with/without-workout strain attribution, and with `ABOUT.md`.
- **Recommended remediation** (subject to Open Decision OD-1):
  1. **Unify on the user-selected TRIMP model** (Settings → Advanced exposes Banister /
     Cheng LT-TRIMP / iTRIMP with tunable parameters; Banister is only the default selection).
     Compute per-workout model TRIMP during the daily/resync recompute (the code already does this
     per day) and persist it to a *new* column `workout_records.modelTrimp` (nullable REAL;
     migration v5→v6, backfilled lazily by the next recompute — the walk-forward already touches
     every workout in range). Because the persisted value then depends on `prefs.trimpModel` and
     its parameters, a model/parameter switch must invalidate and rebuild it across the full
     retention-bounded history — that trigger is SCORE-007's recompute-only path, which is
     therefore a hard prerequisite for flipping the ATL/CTL read to `modelTrimp`.
  2. Point `getTrimpPoints` (and `ComputeWorkoutLoadMetricsUseCase`'s history fetch) at
     `COALESCE(modelTrimp, trimp)` during the transition, then at `modelTrimp` once backfilled.
  3. Keep the zone-weighted value (rename the concept internally to `zoneTrimp`) for the zone
     minutes UI only; stop calling it TRIMP in code.
  4. Update `ABOUT.md`/`docs/about.md`/in-app About strings + `DATA_FLOW.md` §2.3 in the same PR
     (repo Documentation Synchronization Rule).
- **Dependencies:** must precede PERF-002 step 3 (defines which series the EMA consumes); OD-1;
  SCORE-007 (model-switch invalidation of the new column).
- **Complexity:** Medium–High. · **Migration risk:** Medium-High — **user-visible score changes**:
  workout-only ATL/CTL/Strain Ratio/Load Score/Readiness shift after the fix. Requires the score-
  change communication strategy in §12 and a before/after fixture report.
- **Acceptance criteria:** one TRIMP definition feeds daily TRIMP, per-workout display, both ATL/CTL
  variants, and strain attribution; `DocumentationDriftTest`-style check that ABOUT.md names the
  operative model; fixture report quantifying score deltas attached to the PR.

---

### SCORE-002 — `clearFrozenBaselines` uses the device timezone; scoring uses the stored scoring zone

- **Category:** Scoring — confirmed implementation bug · **Severity:** High · **Confidence:** High ·
  **Status:** Confirmed
- **Affected:** `core/database .../data/local/RoomHealthIngestionStore.clearFrozenBaselines`
  (lines 105–114: `val zoneId = ZoneId.systemDefault()`); callers `DailySyncUseCase.run` (line 138)
  and `ResyncRangeUseCase.run` (line 323) pass `LocalDate`s that the scoring engine will interpret
  in `prefs.scoringZone()`; `DailySummaryDao.clearFrozenBaselinesBetween` compares against
  `dateMidnightMs` values that were *written* with the scoring zone
  (`DailySummaryMapper.toEntity(summary, zoneId)`).
- **Current behavior:** when the device zone ≠ stored scoring zone (travel, or the documented
  cross-device restore scenario in DATA_FLOW.md's determinism section), the cleared
  `[fromMs, toMs)` range is shifted by the zone offset difference relative to the summary rows'
  midnights: edge days keep *stale frozen baselines* (so the walk-forward recompute reuses old
  `hrvMuMssd/rhrBpm` instead of recomputing) or an extra out-of-range day gets its baseline wiped
  without being recomputed (leaving `baselineCalculatedAtDate` null until some later recompute).
- **Failure scenario (concrete):** scoring zone `Europe/Berlin`, device in `America/Los_Angeles`
  (−9 h): clearing "2026-07-01 .. 2026-07-08" clears epoch range `[Jul 1 00:00 PDT, …)` = `[Jul 1
  09:00 Berlin, …)`, missing the Berlin-midnight row for Jul 1 → Jul 1 recomputes against its old
  frozen baseline, violating the "identical SQLite + prefs ⇒ identical scores" invariant.
- **Remediation:** pass the scoring `ZoneId` into `HealthIngestionStore.clearFrozenBaselines`
  (both callers already hold it), or change the port to accept epoch millis computed by the caller.
  One-line semantics fix + signature change.
- **Complexity:** Trivial. · **Migration risk:** None (bug fix; affected users get correct
  recompute on next sync).
- **Acceptance criteria:** unit test with scoring zone ≠ system zone: the cleared range matches
  exactly the `dateMidnightMs` keys the recompute will write.

---

### SCORE-003 — App-start baseline backfill wipes/rewrites all frozen baselines with no synchronization

- **Category:** Scoring / concurrency + freeze semantics · **Severity:** High · **Confidence:**
  High (unsynchronized), Medium (frequency of harmful interleaving) · **Status:** Confirmed
  structure; race Suspected in practice
- **Affected:** `HealthDashboardApplication.onCreate` → `appScope.launch { backfillHistoricalBaselines.execute() }`;
  `BackfillHistoricalBaselinesUseCase.execute` (`dailySummaryDao.wipeDerivedBaselines()` then
  recompute + batched `updateBaselines`); concurrent actors: `MainActivity`/`SyncViewModel` →
  `ForegroundSyncController.evaluateAndSync` (runs at app open too), `HealthResyncWorker`,
  `PeriodicHealthSyncWorker` — all serialized among themselves by `HealthSyncUseCase.syncMutex`,
  which the backfill does **not** take. `ScoringRepositoryImpl.calculationMutex` protects only
  `computeAndPersistDailySummary`, not the backfill's direct DAO writes.
- **Current behavior:** on every launch, *all* rows' `hrvMuMssd/hrvSigmaMssd/rhrBpm/rhrSigma/
  hrMax/…/baselineCalculatedAtDate` are nulled and recomputed while a daily sync may be
  mid-walk-forward. Interleavings: (a) sync reads a day's summary after wipe but before rewrite →
  computes with `baselineCalculatedAtDate == null` → recomputes/refreezes that day from current raw
  data (silent baseline drift); (b) backfill's rewrite lands after sync froze a fresh baseline →
  overwrites it with values computed from a snapshot taken before the sync's new rows.
  Additionally, the wipe-recompute design makes the documented freeze semantics ("scoring for that
  day uses the frozen values instead of recomputing from whatever rows happen to be present")
  hold only *within* a process lifetime: after `DataCleanupWorker` deletes raw rows past retention,
  the next launch recomputes different baselines for days whose 30-day window crossed the cutoff.
- **Remediation:**
  1. Serialize: expose `HealthSyncUseCase.withSyncLock { }` (or inject the mutex) and run the
     backfill under it.
  2. Make the backfill *incremental*: track a `baselineSchemaVersion`/`lastBackfillInputsHash`
     (prefs) and skip the wipe when nothing relevant changed (profile, overrides, raw-data
     watermark). Recompute only days whose windows are actually affected.
  3. Decide the retention interaction explicitly (Open Decision OD-4): either freeze means frozen
     (backfill must *not* wipe days with `baselineCalculatedAtDate` older than the raw-data
     watermark), or recompute-from-current-data is the contract (then DATA_FLOW.md §2.4's freeze
     description must be rewritten).
- **Dependencies:** OD-4; SCORE-002 (same subsystem, land together).
- **Complexity:** Medium. · **Migration risk:** Medium — changing wipe behavior changes scores for
  users whose retention already truncated history (document in §12).
- **Acceptance criteria:** stress test: launch-time backfill + concurrent `sync(windowDays=7)` on a
  seeded DB produce the same summaries as running them serially; backfill is a no-op (0 writes) on
  a second consecutive launch with unchanged inputs.

---

### SCORE-004 — Preferences re-read per day inside the walk-forward recompute

- **Category:** Scoring / determinism · **Severity:** Medium · **Confidence:** High · **Status:** Confirmed
- **Affected:** `ScoringRepositoryImpl.computeAndPersistDailySummary` (reads
  `settingsRepo.userPreferences.first()` per call) invoked per day by
  `DailyRecomputeSupport.recomputeDay` from both sync flows.
- **Current behavior:** a preference change mid-resync (user edits zones/profile while the worker
  runs) makes the recomputed history a mix of old- and new-preference days, violating "identical
  prefs ⇒ identical scores" for the resync as a unit. (`ResyncRangeUseCase` snapshots prefs for
  *ingestion* but not for recompute.)
- **Remediation:** add `computeAndPersistDailySummary(day, steps, prefs)` overload; both sync flows
  snapshot once (they already hold a snapshot) and pass it through `DailyRecomputeSupport`.
  Fold into PERF-002 step 1.
- **Complexity:** Low. · **Migration risk:** Low.
- **Acceptance criteria:** test: mutate prefs between recomputed days via a fake settings repo —
  all days score with the snapshot taken at walk-forward start.

---

### SCORE-005 — Workout-only ATL/CTL series omits the target day's freshly computed TRIMP

- **Category:** Scoring / consistency · **Severity:** Medium · **Confidence:** High · **Status:** Confirmed
- **Affected:** `ScoringRepositoryImpl.computeDailySummary` — everyday variant injects today's
  fresh value (`everydayTrimpByDate … .apply { put(targetDate, trimpEverydayHr) }`, lines 439–445)
  but the workout-only series relies on whatever `workout_records.trimp` holds for today (lines
  424–428) while persisting a *differently computed* `trimpWorkoutOnly` (Banister) for the same day.
- **Current behavior:** today's ATL contribution ≠ today's displayed TRIMP (this is the same root
  cause as SCORE-001 viewed from the daily path; kept as a separate finding because fixing
  SCORE-001 must include the injection symmetry, and a test should pin it).
- **Remediation:** after SCORE-001 unification, inject the freshly computed model TRIMP for
  `targetDate` into the workout-only series exactly as the everyday path does.
- **Dependencies:** SCORE-001. · **Complexity:** Trivial once SCORE-001 lands.
- **Acceptance criteria:** regression test mirroring `ScoringPointInTimeRegressionTest`'s series
  assertions: both variants' series contain the same freshly computed value for `targetDate`.

---

### SCORE-006 — Install date converted with UTC epoch-day division

- **Category:** Scoring / timezone hygiene · **Severity:** Low · **Confidence:** High · **Status:** Confirmed
- **Affected:** `ScoringRepositoryImpl.computeDailySummary` line 141:
  `LocalDate.ofEpochDay(prefs.installDate / 86400000)`.
- **Current behavior:** `installDate` (epoch ms captured at first launch, local time) is floored to
  a *UTC* day; for users east of UTC the derived install date can be one day early/late. Feeds only
  `ScoringConfigFactory`'s diagnostic phase math, not scores.
- **Remediation:** `Instant.ofEpochMilli(prefs.installDate).atZone(zoneId).toLocalDate()`.
- **Complexity:** Trivial. · **Acceptance criteria:** unit test at zone `Pacific/Auckland` with an
  evening install timestamp resolves the local date.

---

### SCORE-007 — Switching the TRIMP model recomputes only 8 days; older TRIMP history stays on the previous model

- **Category:** Scoring — likely implementation bug (mixed-model load series) · **Severity:** High ·
  **Confidence:** High · **Status:** Confirmed
- **Affected:** `UISettingsViewModel` (`SettingsEvent.TrimpModelChanged` /
  `BanisterMultiplierChanged` / `ChengBetaChanged` / `ItrimBChanged` /
  `ResetTrimpToProfileDefaults` → `healthDataRefresh.refreshAffectedWindow()`);
  `HealthDataRefreshAdapter.refreshAffectedWindow()` → `HealthSyncUseCase.sync()` →
  `windowDays = 8` default (HC-009); persisted model-dependent columns on `daily_summaries`
  (`trimpWorkoutOnly`, `trimpEverydayHr`, `rasWorkoutOnly`, `rasEverydayHr`, `totalRas*`);
  `ScoringRepositoryImpl.computeDailySummary` (everyday ATL/CTL reads
  `dailySummaryDao.getEverydayTrimpPoints` over an 84-day window).
- **Current behavior:** the app exposes the Training Load Model as a first-class user setting
  (Settings → Advanced: Banister / Cheng LT-TRIMP / iTRIMP, each with tunable parameters). On any
  change, only the last 8 days are recomputed. All older `daily_summaries` rows keep TRIMP/RAS
  values computed under the *previous* model and parameters. Because the everyday-HR ATL/CTL EMAs
  are built from the persisted `trimpEverydayHr` series over an 84-day fetch window, every
  ATL/CTL/Strain-Ratio/Load-Score/Readiness computation for the next ~76 days mixes old-model and
  new-model TRIMP values in a single EMA. The 7-day `totalRas*` sums mix models for 6 days.
  (The workout-only ATL/CTL series is currently insulated only because it reads the zone-weighted
  `workout_records.trimp` — i.e. SCORE-001 masks SCORE-007 for that variant; fixing SCORE-001
  without fixing SCORE-007 would extend the mixed-model window problem to the workout-only series
  as well.)
- **Failure scenario (concrete):** user on Banister for months switches to iTRIMP. Days −8..0 are
  recomputed with iTRIMP; days −84..−9 retain Banister `trimpEverydayHr`. Tomorrow's CTL
  (42-day EMA) blends the two models' different scales; the Strain Ratio shifts for reasons that
  are neither training nor the model's steady-state difference, then drifts for weeks as old-model
  days age out. A subsequent full historical resync recomputes everything under the new model —
  so the same data yields different scores before vs. after a resync, violating the determinism
  contract ("identical SQLite + preferences reproduce identical scores").
- **Root cause:** the settings-refresh path reuses the recent-window sync as a generic "refresh"
  without modeling *which dates a preference change invalidates*. TRIMP model/parameters (and
  hrMax, RHR override, HR zones — same class) invalidate the entire retention-bounded history,
  not a recent window.
- **Remediation:**
  1. Classify settings by invalidation scope: display-only (no recompute), recent-window
     (e.g. RHR percentile forward-only policy decisions), and **historical** (TRIMP model +
     parameters, HR zones, hrMax source, RHR/HRV overrides, physiology profile).
  2. For historical-scope changes, escalate to the durable recompute path instead of
     `sync(8)`: enqueue `WorkerScheduler.scheduleResyncWorker()` — ideally a *recompute-only*
     variant that skips the INGEST/PRUNE phases (raw HC data is unaffected by a model switch) and
     runs RECONCILE(zone thresholds changed)/RECOMPUTE over `RetentionBounds.resolveResyncStartDate`.
     The 4-phase checkpoint machinery already supports starting at a later phase; expose that as
     a `recomputeRange(start, end)` entry on `HealthSyncUseCase`.
  3. Under WP-10 (SCORE-001), the same trigger must invalidate/rebuild the persisted
     `workout_records.modelTrimp` column (the walk-forward recompute already rewrites it per day,
     so step 2's recompute-only pass covers it).
  4. Surface the recompute to the user with the existing determinate progress banner (the model
     switch is an explicit user action; a "Recalculating history day X of Y" is expected, and the
     infrastructure exists).
- **Dependencies:** HC-009 (same entry point), SCORE-001/WP-10 (modelTrimp invalidation),
  PERF-002 (makes the full-history recompute cheap enough to run on a settings change).
- **Complexity:** Medium. · **Migration risk:** Low–Medium — behavior change is strictly toward
  the documented determinism contract; the recompute is the same one a manual "Resync Health
  Connect data" already performs, minus re-ingestion.
- **Acceptance criteria:** switching TRIMP model (or editing its parameters / HR zones) schedules
  a full-range recompute; after it completes, `daily_summaries` TRIMP/RAS/ATL/CTL columns are
  byte-identical to a fresh full resync under the new model (fixture test); no EMA window ever
  mixes values computed under different model settings; the recompute-only path never re-reads
  Health Connect.

---

### CACHE-001 — Insight dismissals keyed by device-zone midnight; summaries keyed by scoring-zone midnight

- **Category:** Cache/state correctness · **Severity:** Low–Medium · **Confidence:** High · **Status:** Confirmed
- **Affected:** `DashboardViewModel.onEvent` (`DashboardEvent.DismissInsight` / `RestoreInsights`
  compute `dateMs` with `ZoneId.systemDefault()`, lines 296–319); `InsightDismissalEntity` PK is
  `(dateMidnightMs, type)`; the dismissal *observation* joins against the selected date through the
  same repository — verify observation side uses the same key derivation.
- **Current behavior:** when device zone ≠ scoring zone, a dismissal writes a `dateMidnightMs` that
  matches no summary-day key; depending on the observation path the dismissal either silently fails
  to hide the insight or hides the wrong day's.
- **Remediation:** centralize "LocalDate → dateMidnightMs" in one injected helper (scoring zone),
  use it for both dismissal writes and reads; a Konsist or lint check that
  `atStartOfDay(ZoneId.systemDefault())` does not appear outside display-format code would prevent
  the whole class of bug (this is the third finding in that class: SCORE-002, HC-007, CACHE-001).
- **Complexity:** Low. · **Acceptance criteria:** dismissal round-trip test with scoring zone ≠
  system zone.

---

### UI-001 — Dead conditional in `resolveDashboardSleepSessionSummary`

- **Category:** Clean code · **Severity:** Low · **Confidence:** High · **Status:** Confirmed
- **Affected:** `DashboardViewModel.resolveDashboardSleepSessionSummary` (lines 200–221): both the
  biphasic branch and the fall-through return an identical `SleepSessionSummary`; `sessionMinutes`
  is computed and unused except in the comparison guarding identical outcomes.
- **Remediation:** collapse to the single construction (keep the explanatory comment about
  biphasic days); or, if the branch was *meant* to differ (e.g. suppress the fallback), restore the
  intended behavior — check `git log` for PR #146 (biphasic sleep) intent during implementation.
- **Complexity:** Trivial. · **Acceptance criteria:** one return path; no unused locals.

---

### UI-002 — Files exceeding the repository's own size limits

- **Category:** Maintainability · **Severity:** Low · **Confidence:** High · **Status:** Confirmed
- **Affected (worst offenders, main source):** `SleepStagesChart.kt` 756, `BaselineComputer.kt`
  694, `ScoringRepositoryImpl.kt` 673, `SettingsScreen.kt` 664, `ComputeSleepMetricsUseCase.kt`
  603, `GetDashboardDataUseCase.kt` 601 — all above the repo's 400-line target (none above the
  800-line hard limit).
- **Remediation:** opportunistic decomposition **only where other findings already touch the
  file** (ScoringRepositoryImpl will shrink naturally when PERF-002/SCORE-001 extract the
  TRIMP-series and everyday-HR blocks into use-cases). Do not refactor charts for line count alone.
- **Complexity:** absorbed into other work packages. · **Acceptance criteria:** touched files do
  not grow; ScoringRepositoryImpl ≤ ~450 lines after Phase 4.

---

### SEC-001 — Security posture review: no confirmed vulnerabilities; targeted hardening items

- **Category:** Security/privacy · **Severity:** Low · **Confidence:** Medium · **Status:** Review summary
- **Reviewed:** `AndroidManifest.xml` (`allowBackup=false`, `dataExtractionRules` +
  `fullBackupContent` present; exported components limited to launcher, HC rationale activity
  required by Health Connect, and a permission-guarded `VIEW_PERMISSION_USAGE` alias);
  `DatabaseModule` (SQLCipher via `SqlCipherKeyManager`, WAL, FK pragma, destructive migration
  gated to `BuildConfig.DEBUG`); logging (`logD` payloads are counts/dates, not sample values;
  release sink is `SecureFileLogSink` with documented sanitization).
- **Hardening opportunities (not vulnerabilities):**
  1. `SecureFileLogSink` (304 lines) is the single choke point for release logs — add a unit test
     asserting that strings matching HR/HRV/BP numeric patterns with identifying context are
     redacted, so future log calls can't silently leak health values into the shareable bug-report
     log.
  2. `HealthConnectRepositoryImpl.checkPermissions` logs the full granted-permission set on every
     check; harmless but chatty — gate to debug.
  3. Confirm `data_extraction_rules`/`full_backup_content` exclude the DataStore protos
     (change tokens, checkpoints) — a restored stale token set on a new device is handled
     (missing/expired token → full resync) but excluding them avoids the odd state entirely.
- **Complexity:** Low. · **Acceptance criteria:** redaction test exists; extraction rules reviewed
  and documented in `docs/privacy.md` if changed.

---

## 5. Scoring and Metric Verification Matrix

| Metric / score | Implementation | Source inputs | Documented rule | Implemented behavior | Review result | Findings | Action |
|---|---|---|---|---|---|---|---|
| TRIMP (Banister) | `RasCalculator.calculateDailyTrimp` | duration, avg HR, RHR baseline, hrMax, sex | Banister exponential HRR (`ABOUT.md`), male 0.64/1.92 | matches; sex-specific constants in `ScoringConstants.Trimp`; profile multiplier via `banisterMultiplier` | ✅ correct | — | none |
| TRIMP (Cheng / iTRIMP) | same | + `ltBpm` / `itrimB` | LT-piecewise / Manzi exponential | continuous at LT (both branches = 0.5); iTRIMP no calibration factor | ✅ correct | — | none |
| Per-workout TRIMP (display) | `ComputeWorkoutTrimpUseCase` | stored HR samples in workout bounds | sample-integrated model TRIMP | trapezoid-free forward integration incl. leading gap; avg-HR fallback when no samples | ✅ correct | — | none |
| **Stored workout TRIMP** | `WorkoutMapper.computeMetrics` | zone minutes × 1..5 | not documented as a separate model | **zone-weighted (Edwards), feeds workout-only ATL/CTL** | ❌ inconsistent with docs + rest of pipeline | **SCORE-001, SCORE-005** | unify on model TRIMP (OD-1) |
| Daily TRIMP (workout-only) | `ScoringRepositoryImpl` lines 151–196 | fresh per-workout Banister sum | "TRIMP → ATL → CTL" | persisted value ≠ series value for same day | ❌ | SCORE-001/005 | fix with SCORE-001 |
| Everyday-HR load | `EverydayHeartRateLoadCalculator` | 1-min buckets, non-sleep/non-workout | ABOUT.md §everyday load (zone 0 excluded from TRIMP, counted in coverage) | matches doc incl. confidence tiers (0/≤179/≤479/480+) | ✅ correct | PERF-006 (perf only) | SQL bucketing |
| ATL / CTL EMA | `RasScoringStrategy.computeEmaWithDecay` | date-bucketed TRIMP map | EMA, α=2/(N+1), zero-fill missing days, single-point seed rule | formula matches; single-point rule is documented in ABOUT.md ("used directly as the starting average") — product decision, not a bug. **However**, the input series mixes TRIMP models for up to 84 days after the user switches the Training Load Model (only 8 days recomputed) | ⚠️ input series | **SCORE-007** | full-range recompute on model switch |
| Strain Ratio | `RasScoringStrategy.computeStrainRatio` | ATL/CTL | ATL/CTL, 0 when CTL=0 | matches | ✅ | — | none |
| Load Score | `LoadScoringStrategy.computeLoadScore` | SR | sr≤1.3→100; else 100·exp(−2.5·(sr−1.3)²) | matches constants `SR_SWEET_SPOT_MAX=1.3`, `K=2.5` | ✅ | — | none |
| RAS | `RasCalculator.calculateDailyRas` + `sumRasLastSixDays` | TRIMP × scaling, cap 75; 7-day rolling | ABOUT.md PAI-style | matches; total = round(today + Σ round(prev 6)) consistent with UI sum | ✅ | — | none |
| Sleep Score | `SleepScoringStrategy` (+ tests) | duration/architecture/restoration | 50/25/25; suspicious-stage reweight 75/0/25 | verified via `ScoringStrategyTest`/`SleepScoringE2eTest` (not re-derived line-by-line here) | ✅ (test-verified) | — | none |
| Restoration (HRV z) | `LoadScoringStrategy.computeHrvZScore/hrvSigma/computeHrvScore` | lnRMSSD, frozen μ/σ, σ-prior blend | lnRMSSD z-scores; saturation z=1.5 slope 0.25 | matches; blend weight over `HRV_SIGMA_BLEND_MIN/MAX_N`; σ floor `MIN_LN_SIGMA` | ✅ | — | none |
| RHR nocturnal floor | `SleepPercentileRhrCalculator`, `BaselineComputer` | sorted sleep HR, percentile (default 5, range 1–15) | ABOUT.md low-percentile floor, 30-day median baseline | matches; batched projections (documented N+1 fix) | ✅ | — | none |
| HRV displayed baseline | `HrvBaselineProvider` | frozen `exp(hrvMuMssd)` → override → arithmetic median | geometric mean per ABOUT.md | matches priority chain (test-covered) | ✅ | — | none |
| Baseline freeze | `BaselineComputer` + `ScoringRepositoryImpl` frozen-snapshot reads | `baselineCalculatedAtDate` gate | freeze once calibrated; identical resync ⇒ identical scores | freeze respected in scoring path, **but app-start backfill wipes/rewrites all baselines unsynchronized**, and clearing is zone-buggy | ⚠️ | **SCORE-002, SCORE-003** | fix zone; serialize + make incremental |
| Readiness | `LoadScoringStrategy.computeReadinessScore` | 0.4·rest + 0.3·sleep + 0.3·load; ILLNESS cap 50, 2 consecutive nights | matches DATA_FLOW §2.5 | matches (`ReadinessCapTest`) | ✅ | — | none |
| Recovery flags | `computeRecoveryFlags` | z-scores, ΔRHR, TRIMP thresholds 120/10 | INSIGHTS docs | matches tests; thresholds hardcoded (120f/10f/2−x) — move to `EmergencyFlagThresholds`-style constants opportunistically | ✅ (minor magic numbers) | — | fold into touched-file cleanup |
| Phase / confidence | `PhaseCalculator` | totalValidHrvNights | 0-6/7-20/21-59/60+ | matches (`PhaseTest`) | ✅ | — | none |
| Calibration gate | `ScoringRepositoryImpl.isCalibrated` | ≥7 valid sessions (42-day window via HRV windows) | "< 7 days ⇒ Calibrating" | matches | ✅ | — | none |
| Biphasic aggregation | `SleepDayAggregator` | policy: merge gap, cutoff, min segment | DATA_FLOW §2.4.1 contract | deterministic tie-breaks implemented; test-covered | ✅ | HC-006 (stage-less input) | fix upstream mapper |
| Day/date attribution | `TrimpDateBucketer`, `DailySummaryMapper` | stored scoring zone | all boundaries via scoring zone | true in scoring; **false** in `clearFrozenBaselines`, insight dismissals, `computeWindowDays`, `readStepsRange` | ⚠️ | SCORE-002, CACHE-001, HC-007, HC-010 | centralize day-key helper |

---

## 6. Health Connect Ingestion Matrix

Read strategy for all types: `readAllPages<T>()` (pageToken loop) within
`[windowStart, windowEnd]`, wrapped in `retryWithBackoff` + shared 3-min `withTimeout`
(HC-001/HC-002). Dedup/idempotency: Room `@Upsert` on stable IDs; no blanket deletes.
Recalc trigger: walk-forward recompute of the sync window (daily) or full range (resync);
changes-path affected-dates widen the daily window (≤7d inline).

| Record type | Dedup key | Update handling (Changes API) | Deletion handling | Persistence target | Notable risks / findings |
|---|---|---|---|---|---|
| SleepSession | HC `metadata.id` | delete-then-upsert; stages replaced via `deleteForSessions` + upsert | `deleteById` (stages CASCADE) | `sleep_sessions` + `sleep_stages` | stage-less sessions → 0-min sleep (**HC-006**) |
| Sleep stages | `(sessionId, startTime)` unique, autogen PK | replaced with parent | CASCADE | `sleep_stages` | ✅ |
| HeartRate (series) | `${hcId}_${sampleMs}` | `deleteBySourceRecordId` + re-upsert; **tagged RESTING at change time** (HC-004) | `deleteBySourceRecordId` (**substr scan, PERF-003**) | `heart_rate_records` | in-memory window materialization (**HC-001**); reconcile cost (**PERF-001**) |
| HRV RMSSD | `${hcId}_${ms}` | as HR | as HR | `hrv_records` | same as HR |
| ExerciseSession | HC id | re-upserted with **empty samples → zero metrics** until reconcile (HC-004) | `deleteById` | `workout_records` | stored `trimp` is zone-weighted (**SCORE-001**) |
| Steps | none (no raw table) | affected-dates only; daily total re-fetched per day via aggregate | **no affected dates → stale totals (HC-005)** | `DailySummaryEntity.stepCount` | per-day aggregate calls (**HC-003**); device-filtered path reads raw records and sums with scoring zone ✅ |
| Weight / BodyFat / BP / SpO2 | `${hcId}_${ms}` composite | delete + upsert, device-filtered | `deleteBySourceRecordId` (substr, PERF-003) | respective tables | optional-permission reads swallow transient IO errors (**HC-008**) |
| Provenance | `deviceName` = `DeviceLabel.from(device, dataOrigin)` on every row | — | — | all raw tables | selected-source pruning is zone-correct (`SelectedSourcePrunerImpl` uses scoring zone) ✅ |

Token/checkpoint machinery (`HealthChangeTokenStore`, `ResyncCheckpointStore`): tokens committed
only after the (possibly widened) window scores successfully; resync captures baseline tokens
pre-ingest and promotes post-recompute — design is sound; missing/expired token → full resync.
Permission revocation: `SecurityException → HealthConnectPermissionRevokedException` propagates and
stops the resync worker with `Result.failure()` ✅, but is flattened to `SYNC_ERROR` on the daily
path (**HC-008**).

---

## 7. Large-Dataset Analysis (1,000,000+ HR records / 30 days; multi-year history)

Assume 1 Hz HR (Samsung/Polar class): ~86,400 samples/day → a 30-day resync chunk ≈ 2.6M samples;
"1M in 30 days" is comfortably within real-world range at ~0.4 Hz.

**Ingestion bottlenecks.** `readAllPages` accumulates the full chunk (HC-001): ≥3 object
representations per sample plus a full-chunk sort in `HeartRateMapper` → hundreds of MB transient;
the 3-minute budget (HC-002) will likely trip first, producing the retry loop instead of an OOM.
**Fix:** streamed page-wise ingest (HC-001) + adaptive chunk shrink (HC-002); Room write side is
already bounded (5,000-row transactions).

**Peak-memory risks.** Chunk materialization (above); reconcile loads only spans (fine) and pages
rows (fine); walk-forward loads one day of raw HR per day (86k rows — tolerable but wasteful,
PERF-006); `stepsMap` for 10 years is ~3,650 entries (fine).

**Database bottlenecks.** Upsert of 2.6M rows through 3 secondary indices with long TEXT keys
(DB-001 — measure); `substr()` scans in the changes path (PERF-003); observers re-running day
queries during ingest (PERF-005).

**Recomputation bottlenecks.** Reconcile O(samples × sessions) (PERF-001) — the dominant CPU cost
at multi-year scale; walk-forward O(days × window) queries + per-day DataStore reads/writes
(PERF-002); everyday-HR per-day full materialization (PERF-006).

**Query complexity.** Range queries are index-backed (`timestampMs`, `(recordType,timestampMs)`,
`(sessionId,recordType,beatsPerMinute)` cover the scoring reads); the only non-sargable production
predicates found are the `substr()` pair (PERF-003).

**Recommended bounded-processing strategy.** Keep 30-day default chunks but stream pages within a
chunk (HC page size ≈ 1,000–5,000 records; memory bound = one page + one 5,000-row write batch);
shrink chunk on timeout; reconcile via ordered sweep (PERF-001); recompute with rolling
window state (PERF-002).

**Benchmark requirements & success criteria** (details §11): with the 1M-sample fixture —
no OOM with a 512 MB heap cap; resync completes and is resumable after process kill at any phase;
peak RSS bounded and independent of chunk record count; reconcile phase ≤ tens of seconds on a
mid-range device profile; recompute phase issues O(days) queries; identical `daily_summaries`
across repeated resyncs and across chunk sizes {7, 30, 60}; no ANR (foreground path never ingests
more than the capped window, HC-007).

---

## 8. Target Architecture

Deltas only — the module layout and two-flow sync contract stay.

- **Ingestion boundary:** `HealthConnectRepository` exposes *paged/streamed* reads for HR/HRV and a
  grouped daily-step aggregate; `HealthIngestionCoordinator` becomes a pure-domain orchestrator
  consuming domain DTOs and producing `*Input` batches per page (no Room entity types, no
  data-layer mappers — ARCH-001/PERF-004). One shared `HealthConnectRecordConverters` serves the
  bulk and changes paths (ARCH-002).
- **Session linking ownership:** one pure link module in `core/model .../domain/sync/link/`:
  `SessionLinker` (reference single-lookup), `OrderedSessionLinkSweep` (bulk), used by ingestion
  tagging, the reconciler, and the changes path (PERF-001, HC-004).
- **Transaction ownership:** unchanged — `RoomHealthIngestionStore` + `TransactionRunner` own all
  write batching (bound via `@Binds`, DI-003).
- **Scoring-engine boundary:** unchanged (pure Kotlin `core/scoring`). `ScoringRepositoryImpl`
  slims to orchestration: TRIMP-series assembly and everyday-HR aggregation move behind small
  use-cases with a single injected preferences snapshot per walk-forward (PERF-002, SCORE-004).
  One TRIMP definition (`modelTrimp`) flows through daily, per-workout, and both ATL/CTL variants
  (SCORE-001).
- **Invalidation ownership:** `HealthIngestionStore.clearFrozenBaselines(range, zone)` is the only
  baseline invalidation during sync (zone-correct, SCORE-002); the app-start backfill runs under
  the sync lock and is incremental (SCORE-003). A single injected `ScoringDayKey` helper owns
  `LocalDate ⇄ dateMidnightMs` (SCORE-002/CACHE-001/HC-007 class of bugs), with a lint/Konsist ban
  on `atStartOfDay(ZoneId.systemDefault())` outside display code.
- **Dispatcher/clock ownership:** `@IoDispatcher` + `@DefaultDispatcher` + injected `Clock` are the
  only time/threading sources below the UI layer (DI-001/DI-002).
- **UI-state boundaries:** unchanged (the dashboard's split of core-state vs realtime-state is
  good); raw-row observation replaced by aggregate observation for summary surfaces (PERF-005).

---

## 9. Phased Implementation Roadmap

### Phase 0 — Baseline & Safety Rails

- **Objective:** make the later phases provably behavior-preserving.
- **Findings:** prerequisites for SCORE-001/002/003, HC-001/002, PERF-001/002.
- **Steps:**
  1. **Deterministic scoring fixture:** a seeded Room DB builder (pure JVM with Room's SQLite
     driver, as existing DAO tests do) generating ~2 years of realistic data (sleep w/ stages,
     1 Hz HR for a subset, workouts, HRV, steps) + a golden-file test that snapshots every
     `DailySummaryEntity` produced by a full walk-forward. This is the regression lock for
     Phases 1–4.
  2. **1M-sample synthetic dataset generator** (shared by unit benchmarks and the `benchmark`
     module) + Macrobenchmark scaffolding for: ingest window, reconcile, recompute (extends the
     existing `benchmark` module beyond `StartupBenchmark`).
  3. **Property-test harness** for session-link equivalence (oracle = current
     `SessionLinker.resolve`).
  4. Documentation truth pass (ARCH-003) so subsequent PRs amend correct docs.
- **Rollback:** N/A (additive). · **Completion criteria:** golden test green on `main`; benchmarks
  produce baseline numbers committed to the plan's tracking issue.

### Phase 1 — Correctness & Data Integrity

- **Objective:** fix confirmed correctness defects; decide and land the TRIMP unification.
- **Findings:** SCORE-002, SCORE-003, SCORE-004, SCORE-006, SCORE-007, CACHE-001, HC-005, HC-008,
  HC-004, HC-006 (after OD-2), SCORE-001 + SCORE-005 (after OD-1).
- **Steps (order):**
  1. SCORE-002 zone fix (+ `ScoringDayKey` helper + zone-misuse guard test) → CACHE-001 → HC-007's
     zone half → SCORE-006.
  2. SCORE-004 prefs snapshot threading.
  3. SCORE-003 backfill serialization + incrementality (behind OD-4 decision).
  4. HC-008 error-modeling fixes; HC-005 steps-deletion affected dates (option b).
  5. HC-004 changes-path link/metrics correctness.
  6. SCORE-007 settings-invalidation scopes + recompute-only resync entry point (with HC-009's
     explicit-window cleanup) — lands *before* the ATL/CTL read flips to `modelTrimp`.
  7. SCORE-001/005 TRIMP unification (schema v5→v6: add `workout_records.modelTrimp`), including
     ABOUT.md/docs/about.md/strings/DATA_FLOW.md sync per repo rule.
  8. HC-006 stage-less sleep fallback (behind OD-2).
- **Schema/API changes:** v5→v6 additive column (nullable REAL) + drop DB-002's redundant index in
  the same migration.
- **Migration strategy:** additive column, lazily backfilled by the next recompute; `COALESCE`
  read path until then. **Rollback:** revert reads to `trimp` column (data remains).
- **Risks:** user-visible score changes (SCORE-001, HC-006, SCORE-003/OD-4) — see §12.
- **Validation:** golden fixture updated in a *separate reviewed commit* that quantifies deltas;
  all new unit tests from the findings' acceptance criteria.
- **Completion:** all Phase-1 finding acceptance criteria met; drift tests green.

### Phase 2 — Health Connect & Database Scalability

- **Objective:** survive the 1M-record scenario.
- **Findings:** HC-001, HC-002, HC-003, PERF-001, PERF-003, HC-009, HC-010.
- **Steps:** PERF-001 sweep linker (property-tested) → HC-001 streamed ingest (uses it) → HC-002
  domain timeout + adaptive chunk (checkpoint proto field) → HC-003 grouped step aggregate →
  PERF-003 sargable predicates → HC-009/HC-010 API cleanup.
- **Schema/API changes:** checkpoint proto additive field; DAO query text changes (no schema bump).
- **Rollback:** each step independently revertable; streaming keeps list-based path behind the
  repository interface for one release if needed (feature flag not required — determinism is
  reconcile-guaranteed).
- **Validation:** §11 benchmarks vs Phase-0 baselines; chunk-size invariance test
  ({7,30,60} → identical summaries); kill-and-resume test at each phase boundary.
- **Completion:** §7 success criteria met on the 1M fixture.

### Phase 3 — Architecture & Dependency Injection

- **Objective:** make the enforced boundaries real and the graph honest.
- **Findings:** ARCH-001, ARCH-002, ARCH-004, DI-001, DI-002, DI-003, PERF-004.
- **Steps:** ARCH-002 shared converters → ARCH-001 seam relocation + strengthened Konsist check →
  PERF-004 single-pass mapping (bundled) → DI-001/002/003 → ARCH-004 renames (+ `codegraph sync`).
- **Rollback:** pure refactors; revert per commit. **Risks:** wide mechanical diffs — keep each
  work package review-sized.
- **Validation:** golden fixture unchanged; strengthened `CleanArchTest` green; allocation
  benchmark (PERF-004) improved.
- **Completion:** no domain→data FQN references; single mapper definitions; injected
  dispatchers/clock.

### Phase 4 — Incremental Recalculation & Performance

- **Objective:** make walk-forward and UI observation costs proportional to change size.
- **Findings:** PERF-002, PERF-006, PERF-005, UI-002 (opportunistic).
- **Steps:** PERF-002 steps 1–3 (prefs snapshot done in P1; batched TRIMP series + incremental
  EMA + checkpoint every N days) → PERF-006 SQL bucketing → PERF-002 step 4 (rolling baseline
  windows or batched-backfill reuse) → PERF-005 aggregate observation + debounce.
- **Rollback:** per-step; golden fixture gates each.
- **Validation:** recompute benchmark ≥5× on 2-year fixture; observer-row-count metric (PERF-005).
- **Completion:** O(days) query count during recompute; summaries byte-identical to Phase-1 golden.

### Phase 5 — Compose & Long-Term Maintainability

- **Objective:** residual structural cleanups.
- **Findings:** UI-001, UI-002 remainder, SEC-001 hardening, DB-001 (only if the Phase-2 benchmark
  justifies it).
- **Steps:** UI-001 dead branch (with git-history check); SEC-001 redaction test + debug-gated
  permission logging + extraction-rules review; DB-001 decision + (if go) chunked resumable v6→v7
  key migration with progress UI.
- **Completion:** hardening tests green; DB-001 explicitly closed as done or wont-fix with
  benchmark evidence.

---

## 10. Ordered Work Packages

Each package ≈ one reviewable PR. Validation always includes
`./gradlew ktlintFormat && ./gradlew testDebugUnitTest`, plus `./gradlew lintRelease` at phase
ends (repo pre-commit rule), plus the package-specific checks below.

| # | Title | Findings | Key files | Depends on |
|---|---|---|---|---|
| WP-01 | Golden scoring fixture + walk-forward snapshot test | Phase-0 | new `app/src/test/.../fixtures/`, golden JSON | — |
| WP-02 | Synthetic 1M dataset generator + ingest/reconcile/recompute benchmarks | Phase-0 | `benchmark/`, shared test-fixtures module | WP-01 |
| WP-03 | Documentation truth pass (DB v5, 4 phases, module paths, drift-test tripwire) | ARCH-003 | `internal-docs/DATA_FLOW.md`, `.claude/CLAUDE.md`, `AGENTS.md`, `DocumentationDriftTest` | — |
| WP-04 | Scoring day-key helper + zone fixes | SCORE-002, CACHE-001, SCORE-006, HC-007(zone) | `RoomHealthIngestionStore`, `HealthIngestionStore` port, `DashboardViewModel`, new `ScoringDayKey` | WP-01 |
| WP-05 | Prefs snapshot through walk-forward | SCORE-004 | `ScoringRepository(+Impl)`, `DailyRecomputeSupport`, both sync use-cases | WP-01 |
| WP-06 | Backfill under sync lock + incremental skip | SCORE-003 (OD-4) | `BackfillHistoricalBaselinesUseCase`, `HealthSyncUseCase`, `HealthDashboardApplication` | WP-04, OD-4 |
| WP-07 | Error-modeling: permission propagation, logged catches, typed token-expiry, optional-read semantics | HC-008 | `DailySyncUseCase`, `HealthConnectRepositoryImpl`, `HealthChangeSynchronizerImpl`, `GetDashboardDataUseCase` | — |
| WP-08 | Changes-path correctness: local-span linking, workout metrics at write, steps-deletion affected dates | HC-004, HC-005 | `HealthChangeSynchronizerImpl`, extracted workout-metrics helper | WP-07 |
| WP-09 | Foreground window cap + resync escalation | HC-007, HC-009 | `ForegroundSyncController`, `HealthSyncUseCase` | — |
| WP-10 | **TRIMP unification** (v5→v6 `modelTrimp`, COALESCE reads, today-injection symmetry, docs sync, score-delta report) | SCORE-001, SCORE-005, DB-002 | `WorkoutRecordEntity`, `DatabaseMigrations`, `WorkoutDao`, `ScoringRepositoryImpl`, `ComputeWorkoutLoadMetricsUseCase`, `ABOUT.md`, `docs/about.md`, strings, `DATA_FLOW.md` | WP-01, WP-26, OD-1 |
| WP-11 | Stage-less sleep fallback | HC-006 | `SleepDataMapper`, `ComputeSleepMetricsUseCase`, docs | WP-01, OD-2 |
| WP-12 | Ordered session-link sweep + property tests | PERF-001 | `core/model .../link/`, `SessionLinkReconcilerImpl` | WP-02 |
| WP-13 | Streamed page-wise ingestion | HC-001, part PERF-004 | `HealthConnectRepository(+Impl)`, `HealthIngestionCoordinator`, `RoomHealthIngestionStore` | WP-12 |
| WP-14 | Domain timeout + adaptive chunk shrink | HC-002 | `HealthIngestionCoordinator`, `ResyncCheckpointStore(+proto)`, `ResyncRangeUseCase`, `HealthResyncWorker` | WP-13 |
| WP-15 | Grouped daily step aggregation | HC-003, HC-010 | `HealthConnectRepository(+Impl)`, `StepCountFetcher` | — |
| WP-16 | Sargable source-record queries | PERF-003 | six DAOs + `EXPLAIN QUERY PLAN` androidTest | — |
| WP-17 | Shared HC converters + ingestion seam relocation + Konsist FQN check | ARCH-001, ARCH-002, PERF-004 | converters object, coordinator, mappers, `CleanArchTest` | WP-13 |
| WP-18 | Dispatcher/clock/binding hygiene | DI-001/002/003 | DI modules, `ScoringRepositoryImpl`, `DashboardViewModel`, sync use-cases | — |
| WP-19 | Package renames + codegraph sync | ARCH-004 | feature/dashboard, core/scoring, core/model packages | WP-17 |
| WP-20 | Batched TRIMP series + incremental EMA + N-day checkpoints | PERF-002(1–3) | `ScoringRepositoryImpl`, `RasScoringStrategy` callers, `ResyncRangeUseCase` | WP-10 |
| WP-21 | SQL minute-bucket everyday-HR aggregation | PERF-006 | `HeartRateDao`, `ScoringRepositoryImpl`, `EverydayHeartRateLoadCalculator` input shape | WP-20 |
| WP-22 | Rolling baseline windows in walk-forward | PERF-002(4) | `BaselineComputer`, `ScoringRepositoryImpl` | WP-20 |
| WP-23 | Aggregate HR observation + debounced detail flow | PERF-005 | `HeartRateDao`, `HeartRateRepository`, `DashboardFlowIntermediate`, `HeartRateDetailViewModel` | — |
| WP-24 | Security hardening: log-redaction test, debug-gated permission logging, extraction-rules review | SEC-001 | `SecureFileLogSink` tests, `HealthConnectRepositoryImpl`, `res/xml/*` | — |
| WP-25 | (Conditional) `heart_rate_records` key migration | DB-001 | entity, v6→v7 chunked migration, backup compat | WP-02 benchmark gate |
| WP-26 | Settings-invalidation scopes + recompute-only resync entry (`recomputeRange`), explicit refresh windows, progress surfacing | SCORE-007, HC-009 | `UISettingsViewModel`, `HealthDataRefreshAdapter`, `HealthSyncUseCase`, `ResyncRangeUseCase`, `WorkerScheduler`, `HealthResyncWorker`, `DATA_FLOW.md` | WP-01, WP-05 |

Acceptance criteria per package = the corresponding findings' acceptance criteria plus golden-
fixture equality (WP-04…WP-23) or the documented intentional delta (WP-10, WP-11).

---

## 11. Performance Validation Plan

**Fixtures.** (a) Golden 2-year realistic dataset (WP-01); (b) synthetic stress dataset: 1M HR
samples in 30 days (0.4 Hz) + a 1 Hz variant (2.6M/30d), 3,650-day history skeleton, ~1,800 sleep
sessions with stages, ~1,000 workouts, HRV nightly, multi-device provenance on 20% of rows.

**Benchmarks** (JVM micro where pure, Macrobenchmark where Android-bound; baselines recorded in
Phase 0, re-run per phase):

| Area | Measurement | Target after remediation |
|---|---|---|
| HC read+transform | time & peak allocation for one 30-day window (fake HC client, 1M samples) | memory bounded to O(page); no timeout at default chunk |
| Room upsert | rows/sec for 5,000-row HR batches into a 5M-row table | ≥ baseline; if DB-001 pursued: ≥ +30% |
| HR aggregation | day-summary + everyday-HR bucket query at 86k rows/day | ≤ 1,440 rows returned; ≥5× vs full materialization |
| Reconcile | full-range relink on stress fixture | O((n+m)log m); wall-clock seconds, not minutes |
| Incremental recompute | queries + time per recomputed day (2-year fixture) | O(1) queries amortized/day; ≥5× total |
| Full historical rebuild | end-to-end resync on stress fixture, incl. kill/resume at each phase | completes; resumes; identical summaries across runs & chunk sizes |
| Memory | heap watermark during ingest+reconcile (512 MB cap) | no OOM; watermark independent of chunk record count |
| Worker completion | `HealthResyncWorker` on device profile w/ Doze-like interruptions | terminates without manual intervention (HC-002 test) |
| Compose | dashboard frame metrics while a resync runs (Macrobenchmark) | no jank regression; observer row-count −90% (PERF-005) |

**Determinism checks** (not timing): chunk-size invariance; repeat-resync idempotency; zone-split
test (scoring zone ≠ device zone) across sync/resync/backfill; DST-boundary walk-forward
(reuse the existing 2025-03-31 / 2025-10-27 dates from `ScoringPointInTimeRegressionTest`).

---

## 12. Migration and Compatibility Risks

- **Room migrations:** one planned bump v5→v6 (add `workout_records.modelTrimp`, drop redundant
  `daily_summaries` index; plus optional checkpoint-proto field which is DataStore, not Room).
  Additive → low risk; keep schema export committed; migration test from a seeded v5 DB (SQLCipher
  + WAL) required. Conditional v6→v7 (DB-001) is high-risk copy migration — only with chunked,
  resumable execution and a foreground progress UI, gated on benchmark evidence.
- **Existing user data:** raw tables untouched by Phases 1–4 except lazily backfilled
  `modelTrimp`. `COALESCE` read path guarantees no data loss if the backfill hasn't reached a row.
- **Stale sync tokens / checkpoints:** unchanged semantics (missing/expired → full resync). The
  adaptive-chunk field is additive; legacy checkpoints without it behave as today. A checkpoint
  saved by an *older* app version remains valid because range/phase/selection-hash checks already
  gate reuse.
- **Partial migrations / interrupted workers:** resync remains idempotent-by-upsert; the new
  N-day checkpoint granularity (WP-20) only coarsens resume position — safe because recompute is
  idempotent. Verify with the kill/resume benchmark.
- **Score changes after bug fixes:** three sources of intentional drift — SCORE-001 (workout-only
  ATL/CTL now Banister-based), HC-006 (stage-less nights now score duration), SCORE-003/OD-4
  (freeze-vs-retention decision). Mitigations: (1) each ships with a fixture-based delta report in
  the PR; (2) user-facing release note + ABOUT.md "what changed" entry (the repo already keeps
  score-methodology docs in sync by rule); (3) land SCORE-001 in its own release so field feedback
  is attributable.
- **Backward compatibility / rollback:** every read-path change keeps the old column/behavior
  available for one release (`COALESCE`, retained list-based repository methods); rollback = revert
  the PR; no destructive migration until DB-001 (which is explicitly conditional).
- **Release sequencing:** Phase 1 (correctness) → release; Phase 2 (scalability) → release;
  Phases 3–4 can ride normal releases. Do not combine SCORE-001 with HC-006 in one release.

---

## 13. Documentation Updates

Required in the same PR as the code they describe (repo Documentation Synchronization Rule):

- `internal-docs/DATA_FLOW.md` — §1.1 (paged reads, grouped steps), §1.2 (adaptive chunking,
  timeout semantics, capped foreground window, the settings-change refresh flow and the new
  recompute-only `recomputeRange` entry — currently undocumented), §1.2.1 (sweep linker), §1.4
  (v6 schema, index drop), §2.1/§2.3 (TRIMP unification, prefs snapshot, model-switch
  invalidation), §2.4 (backfill locking/incrementality, freeze-vs-retention outcome), plus the
  DB-version tripwire (WP-03).
- `ABOUT.md` + `docs/about.md` + in-app `about_*`/`tooltip_*` strings — TRIMP model unification
  (SCORE-001), stage-less-sleep handling (HC-006), any OD-4 semantic change; run
  `DocumentationDriftTest`.
- `.claude/CLAUDE.md` / `AGENTS.md` — path corrections (WP-03); update the two-flow contract text
  if HC-007's cap changes the documented behavior; four-phase resync wording.
- `docs/privacy.md` — only if SEC-001's extraction-rules review changes backup behavior.
- `.github/ISSUE_TEMPLATE` ↔ email templates — untouched by this plan (no changes required).

---

## 14. Open Decisions

**OD-1 — Which TRIMP feeds workout-only ATL/CTL?** (blocks WP-10 / SCORE-001)
*Why it matters:* defines the user's Load Score/Readiness semantics and the size of the score jump.
The Training Load Model is a first-class user setting (Settings → Advanced: Banister /
Cheng LT-TRIMP / iTRIMP with tunable parameters), so "unify" must mean "the selected model",
never a hardcoded one.
*Options:* (a) unify on the user-selected model TRIMP — consistent with ABOUT.md, the everyday
variant, the per-workout display, and the settings UI; requires SCORE-007's full-history
recompute on model switch; (b) declare zone-TRIMP the intended "stored" load metric and document
it — no score jump and no model-switch invalidation needed for the workout-only series, but it
perpetuates two models, contradicts current docs, and makes the model setting affect only *part*
of the load pipeline; (c) unify on zone-TRIMP everywhere — effectively deletes the multi-model
feature; contradicts the settings UI.
*Recommended default:* (a). *Affected roadmap:* WP-10, WP-20, WP-26.

**OD-2 — Stage-less sleep sessions.** (blocks WP-11 / HC-006)
*Why:* changes historical sleep scores for users with stage-less writers; may be deliberate
("stages required for a scored night").
*Options:* (a) duration fallback + architecture-unavailable reweight (recommended — matches the
documented suspicious-stage reweight philosophy); (b) keep excluding, but *document* it in ABOUT.md
and surface an insight/caveat instead of a silent 0-minute night.
*Affected:* WP-11.

**OD-3 — Steps deletion tracking.** (shapes WP-08 / HC-005)
*Options:* (a) minimal raw steps table (schema change, exact dates); (b) conservative
window-invalidations on any steps deletion (no schema change; recommended default).
*Affected:* WP-08; if (a), merges into the WP-10 migration.

**OD-4 — Freeze semantics vs. retention & app-start backfill.** (blocks WP-06 / SCORE-003)
*Why:* today the "frozen" baselines are rewritten every launch from current raw data; after
retention cleanup this silently changes history. Either behavior can be correct — the docs
currently promise freezing.
*Options:* (a) true freeze: backfill never wipes days whose baselines were frozen with
since-deleted inputs (preserve snapshots; recommended — matches DATA_FLOW.md §2.4 and the
determinism contract); (b) recompute-from-current-data: keep the wipe, rewrite the docs, and accept
retention-dependent drift.
*Affected:* WP-06; DATA_FLOW.md §2.4.

**OD-5 — Pursue DB-001 key migration?** (gates WP-25)
Decide from WP-02 benchmark data per the ≥30%-ingest / ≥25%-size thresholds in DB-001.

---

## 15. Definition of Done

- **Architecture:** no domain→data references (imports or FQNs) outside the allowlist, enforced by
  the strengthened Konsist test; single HC record-converter implementation; no doubled package
  segments; dispatchers and clock injected below the UI layer.
- **Health Connect correctness:** changes-path rows are link- and metric-correct at write time;
  steps deletions invalidate their days; permission revocation surfaces distinctly on all three
  sync flows; stage-less sleep behavior matches the OD-2 decision and its documentation; foreground
  sync window is capped with resync escalation.
- **Large-volume performance:** 1M-sample fixture ingests with bounded memory (no full-window
  materialization), reconcile is sweep-based, resync terminates under injected slowness via
  adaptive chunking, historical steps fetched via grouped aggregation; all §11 targets met and
  recorded against the Phase-0 baselines.
- **Database:** v5→v6 migration tested (SQLCipher+WAL, seeded data); source-record queries use
  indexes (`EXPLAIN QUERY PLAN` asserted); redundant index removed; DB-001 explicitly resolved
  (done or wont-fix with data).
- **Scoring correctness:** one TRIMP definition across daily, per-workout, ATL/CTL, and strain
  attribution (per OD-1); switching the Training Load Model or its parameters triggers a
  full-range recompute so no EMA window mixes models; frozen-baseline clearing and insight
  dismissals use the scoring zone; walk-forward uses a single preferences snapshot; backfill is
  serialized with sync and incremental (per OD-4); score deltas from each intentional change
  quantified in fixture reports.
- **Deterministic recomputation:** golden-fixture summaries byte-identical across repeated resyncs,
  chunk sizes {7,30,60}, device-zone ≠ scoring-zone splits, and kill/resume at every phase
  boundary; DST-boundary regression dates stay green.
- **Security & privacy:** log-redaction test in place; permission-set logging debug-gated;
  extraction rules reviewed; no new exported surface.
- **Documentation:** DATA_FLOW.md, ABOUT.md, docs/about.md, in-app About strings, CLAUDE.md/
  AGENTS.md updated per §13; drift/presence tests green; DB-version tripwire active.
- **Validation:** `ktlintFormat`, `testDebugUnitTest`, `lintRelease` green; §11 benchmark suite
  runs in CI or a documented manual cadence with recorded results.
