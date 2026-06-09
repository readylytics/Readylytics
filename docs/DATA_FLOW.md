# Data Flow & Architecture Blueprint

End-to-end map of how data moves through the app: from **Android Health Connect**
ingestion, into **Room/SQLite** local storage, through the pure-Kotlin **scoring engine**,
and out to the **Jetpack Compose** UI.

> **Reference-level by design.** This document names the calculation models, their defaults,
> and their inputs, and points to the exact source file + function that owns each formula.
> It deliberately does **not** reproduce coefficients or derivations — the mathematical
> "source of truth" stays in `domain/scoring/**` (pure Kotlin, zero Android dependencies).
> When you change the pipeline, schema, use-cases, or scoring formulas, update this file in
> the same change (see the constraint in `.claude/CLAUDE.md`).

All paths are rooted at `app/src/main/java/com/gregor/lauritz/healthdashboard/`.

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
│  domain/sync/HealthSyncUseCase                                             │
│   • sync(windowDays)        — recent window (foreground; daily = 1 day);   │
│                               ingestion fetch reaches windowDays+1 back to  │
│                               capture cross-midnight sleep (recalc stays 1) │
│   • resyncRange(...)        — full historical, chunked (30-day windows)    │
│   • ingestWindow(...)       — read → map → device-filter → upsert (1 txn)  │
│   • retryWithBackoff(...)   — bounded exponential backoff on HC/IO faults  │
│   • syncMutex               — serializes daily sync vs. resync             │
└──────────────┬─────────────────────────────────────────────────────────────┘
               │ HC record → Entity (mappers; DeviceLabel; composite IDs)
               ▼
┌──────────────────────────────┐
│  RoomTransactionRunner       │   one atomic transaction per ingest window
└──────────────┬───────────────┘
               │ @Upsert by stable HC id (idempotent; overlap → replace)
               ▼
┌──────────────────────────────┐
│  HealthDatabase (SQLite v27) │   10 entities — single source of truth
└──────────────┬───────────────┘
               │ raw DAO reads (local; no further HC calls)
               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  data/repository/ScoringRepositoryImpl.computeDailySummary(day)           │
│   raw metrics → TRIMP/PAI → baselines → sleep/load/readiness → persist    │
│   delegates to pure-Kotlin domain/scoring/** (BaselineComputer,           │
│   PaiCalculator, strategies/*, sleep/*)                                    │
└──────────────┬─────────────────────────────────────────────────────────────┘
               │ DailySummaryEntity persisted back to Room
               ▼
┌──────────────────────────────┐
│  ViewModels (StateFlow)      │   repo flows → combine() → stateIn()
└──────────────┬───────────────┘
               │ collectAsStateWithLifecycle()
               ▼
┌──────────────────────────────┐
│  Jetpack Compose UI          │   M3ScoreDial · Vico TrendChart · Canvas charts
└──────────────────────────────┘
```

---

## 1. Ingestion Layer (Health Connect → SQLite)

### 1.1 Health Connect access — authentication, permissions, paginated fetch

| Component | Path | Responsibility |
| :--- | :--- | :--- |
| `HealthConnectRepository` (interface) | `domain/repository/HealthConnectRepository.kt` | Declares permission sets (critical / required / optional), `checkPermissions() → PermissionStatus` (Granted / Unavailable / Missing), and per-type read methods. Throws `HealthConnectPermissionRevokedException` when access is revoked mid-flight. |
| `HealthConnectRepositoryImpl` | `data/healthconnect/HealthConnectRepositoryImpl.kt` | Concrete HC client wrapper. Generic `readAllPages<T>()` loops on HC `pageToken` until exhausted; catches `SecurityException` → `HealthConnectPermissionRevokedException`. Optional records (weight/body-fat/BP/SpO2) fall back to empty lists when their optional permission is missing. Steps use `AggregateRequest(StepsRecord.COUNT_TOTAL)` only when "All devices" is selected; selected-device paths read raw `StepsRecord`s and aggregate locally. |

**Permission model** (declared in the interface):
- **Critical:** `READ_SLEEP`, `READ_HEART_RATE`, `READ_HEART_RATE_VARIABILITY`, `READ_EXERCISE`, `READ_STEPS`
- **Required:** critical + `READ_HEALTH_DATA_HISTORY`
- **Optional:** `READ_WEIGHT`, `READ_BODY_FAT`, `READ_BLOOD_PRESSURE`, `READ_OXYGEN_SATURATION`

**Read methods:** `readSleepSessions`, `readHeartRateSamples`, `readHrvSamples`,
`readExerciseSessions`, `readSteps` / `readStepsRange`, `readWeightRecords`,
`readBodyFatRecords`, `readBloodPressureRecords`, `readOxygenSaturationRecords`,
`discoverDevices`.

### 1.2 Sync engine — orchestration, chunking, idempotency

| Component | Path | Responsibility |
| :--- | :--- | :--- |
| `HealthSyncUseCase` | `domain/sync/HealthSyncUseCase.kt` | Core engine. `sync(windowDays, onProgress)` recent-window sync — note the **ingestion fetch starts one day earlier than the scored window** (`today − windowDays`), because overnight sleep sessions begin the previous evening; clipping at the scored window's midnight would drop a night's pre-midnight HR/HRV samples (lower HRV mean, higher RHR percentile). The recalc loop still covers only `windowDays` (current-day-only refresh unchanged); `resyncRange(start, end, chunkDays = 30, onProgress)` full historical (two-phase: chunked re-fetch then walk-forward recompute); **every ingestion chunk of resyncRange starts one day early and fetches sleep/exercise sessions one day past the chunk end** so HR/HRV samples at either side of a 30-day boundary can still be assigned to cross-midnight sessions. Metric sample reads remain capped to the chunk. Selected-step-device resync reads raw `StepsRecord`s for each chunk, filters by device, and aggregates locally; all-devices resync keeps the HC aggregate path. Historical resync progress reports recomputed calendar days, not internal ingest+recompute work units. `ingestWindow(start, end, prefs)` the single read→map→filter→upsert funnel; `retryWithBackoff(maxAttempts = 4, initialDelayMs = 1000)` for transient HC/IO faults (never swallows `CancellationException`); `syncMutex` serializes daily vs. resync. |
| `ForegroundSyncController` | `domain/sync/ForegroundSyncController.kt` | Foreground state + progress bridge. `triggerDailySync()` = pull-to-refresh (current day only, `windowDays = 1`); `triggerImmediateSync()` = first-launch catch-up; `onBackgroundRecalc{Started,Progress,Finished}()` publish WorkManager job progress into `isSyncing` / `recalcProgress` StateFlows + `syncCompletedEvent`. |
| `FullHistoricalResyncUseCase` | `domain/sync/FullHistoricalResyncUseCase.kt` | Resolves the retention-bounded start date via `RetentionBounds.resolveResyncStartDate()` and delegates to `HealthSyncUseCase.resyncRange(start, today)`. No math. |
| `HealthResyncWorker` | `workers/HealthResyncWorker.kt` | `@HiltWorker` durable foreground service (`FOREGROUND_SERVICE_TYPE_DATA_SYNC`). Runs the resync use case, emits `WorkInfo` progress (`setProgressAsync`), posts a determinate "day X of Y" notification, bridges progress to `ForegroundSyncController`; `Result.retry()` on transient failure. |
| `WorkerScheduler` | `workers/WorkerScheduler.kt` | Enqueues unique work. `scheduleResyncWorker()` (`RESYNC_WORK_NAME`, `ExistingWorkPolicy.KEEP`, expedited, exponential backoff) + `cancelResyncWorker()`; also backup / birthday / data-cleanup workers. |
| `DataCleanupWorker` | `workers/DataCleanupWorker.kt` | Daily retention enforcement; cutoff resolved via `RetentionBounds.resolveRetentionCutoffMs()` (shared with resync). No-op when retention disabled. |
| `RetentionBounds` | `domain/util/RetentionBounds.kt` | Single source of truth for retention→date math: enabled → `today − retentionDays`; disabled → `today − ABSOLUTE_MAX_DAYS` (3650 / 10y). |
| `RoomTransactionRunner` | `data/local/RoomTransactionRunner.kt` | Wraps `HealthDatabase.withTransaction { … }` so an entire ingest window upserts atomically. |

### 1.3 Mappers — HC record → Room entity

All mappers attach a stable device label via `DeviceLabel.from(device, dataOrigin)` and use
deterministic composite IDs (`${hcRecordId}_${timestampMs}`) so re-ingestion is idempotent.

| Mapper | Path | HC → Entity |
| :--- | :--- | :--- |
| `SleepDataMapper` | `data/healthconnect/SleepDataMapper.kt` | `SleepSessionRecord` → `SleepSessionEntity` + `List<SleepStageEntity>` (sums deep/REM/light/awake, computes efficiency). |
| `HeartRateMapper` | `data/healthconnect/HeartRateMapper.kt` | `List<HeartRateRecord>` → `List<HeartRateRecordEntity>`; assigns `recordType` (SLEEP / EXERCISE / RESTING) and `sessionId` by time-range matching. |
| `HrvMapper` | `data/healthconnect/HrvMapper.kt` | RMSSD records → `HrvRecordEntity`; links to sleep session or marks RESTING. |
| `WorkoutMapper` | `data/healthconnect/WorkoutMapper.kt` | `ExerciseSessionRecord` (+ HR samples) → `WorkoutRecordEntity`; derives zone minutes, avg HR, TRIMP. |
| `WeightDataMapper` | `data/mapper/WeightDataMapper.kt` | `WeightRecord` → `WeightRecordEntity` (kg). |
| `BodyFatDataMapper` | `data/mapper/BodyFatDataMapper.kt` | `BodyFatRecord` → `BodyFatRecordEntity` (%). |
| `BloodPressureDataMapper` | `data/mapper/BloodPressureDataMapper.kt` | `BloodPressureRecord` → `BloodPressureRecordEntity` (systolic/diastolic mmHg). |
| `OxygenSaturationDataMapper` | `data/mapper/OxygenSaturationDataMapper.kt` | `OxygenSaturationRecord` → `OxygenSaturationRecordEntity` (%). |

### 1.4 Room storage — `HealthDatabase` (`@Database(version = 27)`)

Defined in `data/local/HealthDatabase.kt`; entities in `data/local/entity/`, DAOs in
`data/local/dao/`. **The database is the single source of truth; the UI never reads Health
Connect directly.**

| Entity | Table | Primary key | Notable columns |
| :--- | :--- | :--- | :--- |
| `SleepSessionEntity` | `sleep_sessions` | `id: String` (HC id) | start/end time, deep/REM/light/awake min, efficiency, `deviceName` |
| `SleepStageEntity` | `sleep_stages` | `id: Long` (auto) | `sessionId` (FK), `(sessionId, startTime)` unique — cleared per-session before re-upsert |
| `HeartRateRecordEntity` | `heart_rate_records` | `id: String` (`${hcId}_${ms}`) | `timestampMs`, `recordType`, `sessionId`, `deviceName` |
| `HrvRecordEntity` | `hrv_records` | `id: String` (composite) | RMSSD ms, `timestampMs`, `recordType`, `sessionId` |
| `WorkoutRecordEntity` | `workout_records` | `id: String` (HC id) | zone1–5 min, TRIMP, avg HR, `startTime`, `deviceName` |
| `WeightRecordEntity` | `weight_records` | `id: String` (composite) | kg, `timestampMs`, `deviceName` |
| `BodyFatRecordEntity` | `body_fat_records` | `id: String` (composite) | %, `timestampMs`, `deviceName` |
| `BloodPressureRecordEntity` | `blood_pressure_records` | `id: String` (composite) | systolic/diastolic, `timestampMs`, `deviceName` |
| `OxygenSaturationRecordEntity` | `oxygen_saturation_records` | `id: String` (composite) | %, `timestampMs`, `deviceName` |
| `DailySummaryEntity` | `daily_summaries` | `dateMidnightMs: Long` | computed scores (sleep/load/readiness), frozen baselines (`hrv_mu_mssd`, `hrv_sigma_mssd`, `rhr_bpm`, `rhr_sigma`, `hr_max`, …), weight/BP/SpO2 snapshots |

**Idempotency contract:** every DAO uses `@Upsert` keyed on the stable primary key, so
re-fetching a record **replaces** rather than duplicates. There is no blanket `deleteAll()`
in the sync path — a worker that dies mid-resync leaves prior valid data intact, and a retry
re-runs the same range cleanly. `DailySummaryDao` additionally exposes `updateBaselines()`
and `clearFrozenBaselines()` (the only up-front mutation during a resync, recomputed in the
same walk-forward pass).

---

## 2. Processing & Scoring Engine Pipeline (SQLite → Calculations)

**All calculation logic lives in `domain/scoring/**` and is pure Kotlin (zero Android
dependencies).** The repository layer fetches Room entities and feeds them in; the engine
returns computed values; the repository persists them. This separation is an invariant —
keep Android types out of `domain/scoring/**`.

### 2.1 Coordinator

| Component | Path | Responsibility |
| :--- | :--- | :--- |
| `ScoringRepository` (interface) | `domain/repository/ScoringRepository.kt` | Contract for daily computation. |
| `ScoringRepositoryImpl` | `data/repository/ScoringRepositoryImpl.kt` | `computeDailySummary(day)` (mutex-locked) orchestrates: raw DAO fetch → daily TRIMP/PAI → baseline resolve/freeze → sleep + load + readiness → persist `DailySummaryEntity`. Hosts the "Calibrating" gate (< 7 sleep sessions in the last 42 days → tentative scores, no load score). **Both sync flows recompute exclusively through this method — formulas are never duplicated in the sync engine.** |

### 2.2 Resting Heart Rate (RHR) — percentile "nocturnal floor"

| Component | Path | Model / inputs |
| :--- | :--- | :--- |
| `SleepPercentileRhrCalculator` | `domain/scoring/sleep/SleepPercentileRhrCalculator.kt` | `collect(session, dayMidnight, percentile)` — sorts overnight sleep HR samples and takes the configured **percentile** as the nightly resting nadir; baseline = median of historical nightly percentile values over a 30-day sleep-session window. Default percentile = `SettingsDefaults.RESTING_HR_PERCENTILE` (**5th**; user-configurable, validator range **1–15** in `domain/validation/SettingsValidators.kt`). |
| `BaselineComputer.computeAdaptiveBaselineRhrBpm()` | `domain/scoring/BaselineComputer.kt` | Live recompute variant of the same 30-day / percentile logic, filtering invalid sessions (insufficient samples / failed sleep validation). |

### 2.3 Training Impulse (TRIMP) — multi-model engine

| Component | Path | Model / default |
| :--- | :--- | :--- |
| `TrimpModel` (enum) | `domain/scoring/TrimpModel.kt` | `BANISTER`, `I_TRIMP`, `CHENG`. |
| `PaiCalculator` | `domain/scoring/PaiCalculator.kt` | `calculateDailyTrimp(..., trimpModel = TrimpModel.BANISTER)` switches per model — **BANISTER is the operational default** (default parameter value). `calculateDailyPai()` converts TRIMP → PAI via a profile scaling factor (capped at 75). |
| `ComputeWorkoutTrimpUseCase` | `domain/scoring/ComputeWorkoutTrimpUseCase.kt` | Per-workout integration over HR samples; reads the user-selected model from `prefs.trimpModel`. |
| `ScoringConfigFactory` | `domain/scoring/ScoringConfigFactory.kt` | Threads `userPreferences.trimpModel` into the scoring config. |

**Variants (reference only — see `PaiCalculator.calculateDailyTrimp` for the implementation):**
- **BANISTER** *(default)* — classic exponential HR-reserve TRIMP (Banister / Morton), sex-specific weighting.
- **CHENG** — LT-TRIMP, piecewise around the lactate-threshold zone (requires a zone-3 / LT bound).
- **I_TRIMP** — individualized exponential TRIMP (Manzi et al.).

### 2.4 Baselines & calibration

`domain/scoring/BaselineComputer.kt` computes and snapshots per-day frozen baselines:
`hrMax`, `rhrBpm`, HRV `mu`/`sigma` (with profile-prior blending for new users),
`paiScalingFactor`, and physiology profile. Baselines freeze once
calibrated (≥ 7 valid sessions); before that, `ScoringRepositoryImpl` reports
**"Calibrating"** and emits tentative metrics only.

The historical backfill (`domain/scoring/BackfillHistoricalBaselinesUseCase` →
`ComputeHistoricalBaselinesUseCase`) wipes derived baselines and recomputes the entire
history at app start. It resolves all per-day HRV/RHR windows via the **batched**
`BaselineComputer.computeBackfillBaselines()` — a fixed, small number of DB reads for the
whole range instead of ~11 queries per day — which reproduces the per-day
`compute*Between` window/validity/nadir math exactly (guarded by an equivalence test). The
per-day UPDATEs are collapsed into a single transaction by the backfill use-case.

### 2.5 Sleep & Load scoring strategies

| Component | Path | Output |
| :--- | :--- | :--- |
| `SleepScoringStrategy` | `domain/scoring/strategies/SleepScoringStrategy.kt` | Sleep score = **Duration 50% + Architecture 25% + Restoration 25%** (Restoration from HRV & RHR z-scores). |
| `LoadScoringStrategy` | `domain/scoring/strategies/LoadScoringStrategy.kt` | Load score from the **Strain Ratio** (ATL/CTL); readiness composite (restoration + sleep + load), capped by recovery flags. |
| `PaiScoringStrategy` | `domain/scoring/strategies/PaiScoringStrategy.kt` | **CTL (42-day)** and **ATL (7-day)** exponential moving averages of daily TRIMP. |
| `ComputeSleepMetricsUseCase` | `domain/scoring/ComputeSleepMetricsUseCase.kt` | Assembles sleep/readiness metrics for the day from the strategies + baselines. |

Supporting helpers live in `domain/scoring/components/` and `domain/scoring/sleep/`
(architecture targets, restoration weights, nadir analysis, HR coverage validation).

---

## 3. Presentation Layer (Calculated States → UI)

### 3.1 ViewModels → `StateFlow`

ViewModels collect repository flows, fuse them with `combine()`, and expose immutable
`*UiState` via `stateIn()`. Screens collect with `collectAsStateWithLifecycle()`.

| ViewModel | Path | Exposes |
| :--- | :--- | :--- |
| `DashboardViewModel` | `ui/dashboard/DashboardViewModel.kt` | `uiState: StateFlow<DashboardUiState>` (summary, card map, circadian, PAI, `recalcProgress`); `onRefresh()` → `foregroundSyncController.triggerDailySync()`. |
| `SyncViewModel` | `ui/sync/SyncViewModel.kt` | `uiState` (sealed sync state machine), `isSyncing`, `recalcProgress` (forwarded from `ForegroundSyncController`). |
| `VitalsViewModel` | `ui/vitals/VitalsViewModel.kt` | HRV / RHR / SpO2 daily trends + baseline bands. |
| `SleepViewModel` | `ui/sleep/SleepViewModel.kt` | Sleep summary, stage timeline, circadian consistency. |
| `WorkoutsViewModel` / `WorkoutDetailViewModel` | `ui/workouts/` | Daily TRIMP/strain trends, PAI breakdown; per-workout TRIMP/strain/HRR. |
| `HeartRateDetailViewModel` | `ui/heartrate/HeartRateDetailViewModel.kt` | Intra-day HR samples + zone totals. |
| `StepDetailViewModel` / `WeightDetailViewModel` / `BloodPressureDetailViewModel` / `BodyFatDetailViewModel` | `ui/steps/`, `ui/weight/`, `ui/bloodpressure/`, `ui/bodyfat/` | Per-metric trends, statuses, formatted display. |

### 3.2 UI state wrappers

`@Immutable` data classes: `DashboardUiState` (+ intermediates in
`ui/dashboard/DashboardFlowIntermediate.kt`, `DashboardLoadingState.kt`), per-screen
`*UiState` (`HeartRateDetailUiState`, `VitalsUiState`, `SleepUiState`, `WorkoutsUiState`, …),
and `ui/settings/SettingsState.kt` (incl. `SyncSettingsState` with resync current/total
progress).

### 3.3 Compose render & visualization components

| Component | Path | Role |
| :--- | :--- | :--- |
| `MainScaffold` / `RecalcProgressBanner` | `ui/scaffold/MainScaffold.kt` | Root scaffold, bottom nav, pull-to-refresh; renders the determinate "Recalculating day X of Y" banner (`R.string.recalculating_progress`). |
| `M3ScoreDial` | `ui/components/M3ScoreDial.kt` | Animated radial score dial (status-colored). |
| `MetricCard` / `MetricTooltip` | `ui/components/MetricCard.kt`, `MetricTooltip.kt` | Status-colored metric cards with tooltips. |
| `TrendCharts` | `ui/components/TrendCharts.kt` | Vico line charts (`TrendChart`, `MultiSeriesTrendChart`) — Bezier curves, gradient fills, M3 tonal mapping. |
| `SingleBloodPressureChart` / `BloodPressureSplitChart` | `ui/components/` | Vico dual-series synchronized BP charts. |
| `HrTimelineChart` / `SleepStagesChart` / `SleepArchitectureBar` / `PaiWeeklyBar` / `StepsBar` | `ui/components/` | Custom `Canvas` visualizations. |
| `VicoChartTooltipOverlay` / `DataPointTooltip` | `ui/components/` | Touch interception + floating tooltip overlay for Vico charts. |
| `ReorderableCardGrid` (+ `reorder/DragController`) | `ui/components/` | Drag-and-drop dashboard card grid. |

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
resync dialog (via `WorkInfo` observed through `getWorkInfosForUniqueWorkFlow`).

---

## 4. Component & File Registry

| Source File Path | Layer / Responsibility | Associated Metric / Formula |
| :--- | :--- | :--- |
| `domain/repository/HealthConnectRepository.kt` | Ingestion — HC contract, permissions | — |
| `data/healthconnect/HealthConnectRepositoryImpl.kt` | Ingestion — paginated HC reads | `readAllPages<T>()` (pageToken) |
| `domain/sync/HealthSyncUseCase.kt` | Ingestion — sync engine | `sync` / `resyncRange` / `ingestWindow` / `retryWithBackoff` |
| `domain/sync/ForegroundSyncController.kt` | Ingestion — foreground trigger + progress | daily sync (1 day); recalc progress publish |
| `domain/sync/FullHistoricalResyncUseCase.kt` | Ingestion — resync orchestration | retention-bounded range |
| `domain/util/RetentionBounds.kt` | Ingestion — retention math | `today − retentionDays` / `ABSOLUTE_MAX_DAYS` |
| `workers/HealthResyncWorker.kt` | Ingestion — durable background resync | `WorkInfo` progress, foreground notification |
| `workers/WorkerScheduler.kt` | Ingestion — work scheduling | unique resync work (KEEP) |
| `workers/DataCleanupWorker.kt` | Ingestion — retention enforcement | retention cutoff (shared) |
| `data/local/RoomTransactionRunner.kt` | Ingestion — atomic transaction | per-window upsert |
| `data/healthconnect/SleepDataMapper.kt` | Ingestion — mapper | sleep session + stages |
| `data/healthconnect/HeartRateMapper.kt` | Ingestion — mapper | HR samples → SLEEP/EXERCISE/RESTING |
| `data/healthconnect/HrvMapper.kt` | Ingestion — mapper | RMSSD samples |
| `data/healthconnect/WorkoutMapper.kt` | Ingestion — mapper | zone minutes + workout TRIMP |
| `data/mapper/{Weight,BodyFat,BloodPressure,OxygenSaturation}DataMapper.kt` | Ingestion — mappers | weight / body fat / BP / SpO2 |
| `data/local/HealthDatabase.kt` | Storage — Room DB (v26) | 10 entities, migrations |
| `data/local/entity/DailySummaryEntity.kt` | Storage — computed-day snapshot | scores + frozen baselines |
| `data/local/entity/*.kt` (sleep, HR, HRV, workout, weight, …) | Storage — raw metric entities | upsert by stable HC id |
| `data/local/dao/*.kt` | Storage — DAOs | `@Upsert`, `clearFrozenBaselines`, `deleteBeforeTimestamp` |
| `domain/repository/ScoringRepository.kt` | Processing — coordinator contract | `computeDailySummary(day)` |
| `data/repository/ScoringRepositoryImpl.kt` | Processing — scoring orchestrator | TRIMP/PAI → baselines → scores; "Calibrating" gate |
| `domain/scoring/sleep/SleepPercentileRhrCalculator.kt` | Processing — RHR | **RHR nocturnal-floor percentile** (default 5th, 30-day window) |
| `domain/scoring/TrimpModel.kt` | Processing — TRIMP model enum | BANISTER / I_TRIMP / CHENG |
| `domain/scoring/PaiCalculator.kt` | Processing — TRIMP/PAI | **TRIMP (default BANISTER)**; PAI = TRIMP × scaling (cap 75) |
| `domain/scoring/ComputeWorkoutTrimpUseCase.kt` | Processing — per-workout TRIMP | model from `prefs.trimpModel` |
| `domain/scoring/BaselineComputer.kt` | Processing — baselines | hrMax / RHR / HRV mu·sigma / PAI factor; freeze + calibration |
| `domain/scoring/strategies/SleepScoringStrategy.kt` | Processing — sleep score | Duration 50% / Architecture 25% / Restoration 25% |
| `domain/scoring/strategies/LoadScoringStrategy.kt` | Processing — load/readiness | Strain Ratio (ATL/CTL); readiness composite |
| `domain/scoring/strategies/PaiScoringStrategy.kt` | Processing — training load | CTL (42-day) / ATL (7-day) EMA |
| `domain/scoring/ComputeSleepMetricsUseCase.kt` | Processing — sleep metrics assembly | sleep + restoration |
| `ui/dashboard/DashboardViewModel.kt` | UI — dashboard state | summary, cards, PAI, recalc progress |
| `ui/sync/SyncViewModel.kt` | UI — sync state | `recalcProgress` forward |
| `ui/vitals/VitalsViewModel.kt` | UI — vitals state | HRV / RHR / SpO2 trends + bands |
| `ui/sleep/SleepViewModel.kt` | UI — sleep state | sleep score, stage timeline |
| `ui/workouts/WorkoutsViewModel.kt` | UI — workouts state | TRIMP / strain / PAI |
| `ui/settings/SettingsState.kt` | UI — settings state | `SyncSettingsState` resync progress |
| `ui/scaffold/MainScaffold.kt` | UI — scaffold + banner | "Recalculating day X of Y" |
| `ui/components/M3ScoreDial.kt` | UI — visualization | radial score dial |
| `ui/components/TrendCharts.kt` | UI — Vico charts | line trends (Bezier, gradient) |
| `ui/components/HrTimelineChart.kt` | UI — Canvas chart | intra-day HR + zones |
| `ui/components/SleepStagesChart.kt` | UI — Canvas chart | sleep stage timeline |
| `ui/components/PaiWeeklyBar.kt` | UI — Canvas chart | 7-day PAI breakdown |

---

*Keep this document synchronized with the source. See the **Documentation Sync** rule in
`.claude/CLAUDE.md`.*
