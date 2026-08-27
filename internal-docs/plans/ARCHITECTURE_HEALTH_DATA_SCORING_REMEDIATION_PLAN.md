# Architecture, Health Data & Scoring Remediation Plan

**Date:** 2026-08-27
**Scope:** Full codebase audit — architecture, Health Connect ingestion, Room database, scoring engine, performance, security, UI
**Status:** Approved for implementation

---

## 1. Executive Summary

### Current Architectural Condition

Readylytics is a **well-engineered, production-hardened** Android health application. The codebase demonstrates deliberate architectural decisions across all layers:

- **Module structure** is clean: 7 core modules, 8 feature modules, zero feature-to-feature coupling, correct dependency direction throughout.
- **Health Connect pipeline** is production-grade with streaming HR/HRV paging, adaptive chunk shrink, four-phase resumable checkpoints, sweep-line session linking, conflict-targeted upserts, and serialized sync via `syncMutex`.
- **Scoring engine** is mathematically correct. All formulas match ABOUT.md documentation. No confirmed calculation bugs. Numerically stable with guarded division-by-zero, floored `ln()` inputs, and clamped outputs.
- **Room database** is well-designed with a 3-tier hot/warm/cold lifecycle, comprehensive indexing, keyset-paged backup/restore, and idempotent upserts.
- **Security posture** is strong: offline-only (no INTERNET permission), SQLCipher 4 encryption, Android Keystore-backed keys, no analytics/crash reporting, debug-gated logging.

### Risk Assessment

- **Correctness risks:** No critical or high-severity bugs found in scoring, ingestion, or data integrity. One suspected medium issue (HC-AUDIT-014: Changes-path modelTrimp preservation).
- **Scalability risks:** Architecture handles 1M+ HR records well via hot-tier rollup and streaming ingestion. A few SQL queries perform in-memory filtering instead of SQL-side filtering (DB-PERF-002).
- **Health Connect risks:** `discoverDevices` path uses non-streaming reads (HC-AUDIT-001). Per-workout transactions in reconcile phase could be batched (HC-AUDIT-007).
- **Scoring confidence:** High. All formulas verified against documentation, deterministic recomputation confirmed, frozen baselines prevent drift.

### Recommended Strategy

**Incremental hardening, not remediation.** This codebase does not need architectural remediation. The findings are medium and low severity — performance optimizations, maintainability improvements, and accessibility gaps. The plan proposes targeted improvements organized by risk/impact.

---

## 2. Repository Areas Reviewed

### Documentation
- `.claude/CLAUDE.md` — project rules and architecture constraints
- `AGENTS.md` — agent orchestration instructions
- `ABOUT.md` — user-facing scoring methodology (430 lines)
- `internal-docs/DATA_FLOW.md` — authoritative data pipeline map (1358 lines)
- `internal-docs/plans/CORE_SCORING_JVM_MIGRATION.md` — existing migration plan

### Source Code (804 Kotlin source files)
- **Scoring engine:** `core/scoring/src/main/kotlin/.../domain/scoring/**` — all strategies, calculators, baselines, use cases
- **Ingestion pipeline:** `core/healthconnect/src/main/kotlin/.../domain/sync/**` — sync use cases, mappers, coordinators, reconciler
- **Room layer:** `core/database-schema/src/main/kotlin/.../data/local/{entity,dao}/**` — 17 entities, all DAOs
- **Repository layer:** `core/database/src/main/kotlin/.../data/repository/**` — ScoringRepositoryImpl, ScoringDayDataLoader, coordinators
- **DI layer:** `app/src/main/kotlin/.../di/**` — all Hilt modules
- **Workers:** `app/src/main/kotlin/.../workers/**` — all WorkManager workers
- **ViewModels:** all feature ViewModels (`feature/*/src/main/kotlin/**`)
- **Security:** `SqlCipherKeyManager`, `DatabaseKeyRotator`, backup/restore, AndroidManifest.xml
- **UI:** all Screen/Card composables, navigation, state models

---

## 3. Current-State Architecture

### Module Dependency Graph

```
feature/{dashboard,sleep,workouts,vitals,settings,insights,about,onboarding}
    ↓
core:ui → core:designsystem → core:model (leaf)
core:healthconnect → core:database → core:database-schema → core:model
                                   → core:scoring → core:model
    ↓
app (wiring: DI modules, workers, preferences, migrations)
```

### End-to-End Data Flow

```
Health Connect API (ingestion-only)
    │ paginated readAllPages / readAllPagesStreaming (pageToken)
    │ permission checks, retry with backoff
    ▼
HealthIngestionCoordinator.ingestWindow()
    │ domain DTO → Input mappers (pure Kotlin, core:model)
    │ SessionLinkSweep for HR/HRV session attribution
    ▼
RoomHealthIngestionStore.persist(batch)
    │ ≤5000-row HR/HRV sub-batched transactions
    │ conflict-targeted INSERT ON CONFLICT DO UPDATE
    ▼
HealthDatabase (SQLite v12, SQLCipher 4, 17 entities)
    │ hot tier (0-90d): raw 1s heart_rate_records, hrv_records
    │ warm tier (90d→retention): hr_minute_buckets (1-min aggregates)
    │ cold tier: daily_summaries (permanent computed cache)
    ▼
ScoringRepositoryImpl.computeDailySummary(day)
    │ ScoringDayDataLoader → DailyTrimpComputer → RasTotalsComputer
    │ → ReadinessSummaryCoordinator → ComputeSleepMetricsUseCase
    │ all pure-Kotlin scoring in core:scoring (zero Android deps)
    ▼
DailySummaryEntity (frozen baselines + scores)
    │ DailyMetricsMapper → DailyMetrics (display projections)
    ▼
ViewModels (StateFlow via stateIn/combine)
    │ collectAsStateWithLifecycle
    ▼
Jetpack Compose UI (M3, Vico charts, Canvas visualizations)
```

---

## 4. Findings Register

### Health Connect Findings

#### HC-001: `discoverDevices` loads all HR/HRV records into memory
- **ID:** HC-001
- **Category:** Health Connect / Memory
- **Severity:** Medium
- **Confidence:** High (confirmed)
- **Status:** Confirmed
- **Affected files:** `core/healthconnect/.../HealthConnectRepositoryImpl.kt:745-766`
- **Current behavior:** `discoverDevices` calls `readHeartRateSamples` and `readHrvSamples` — the non-paged variants that use `readAllPages` and accumulate every record into an in-memory list. A user with dense recording could trigger OOM.
- **Evidence:** `readHeartRateSamples` calls `readAllPages<HeartRateRecord>` which accumulates all pages into a `List`.
- **Root cause:** Discovery path was not updated when ingestion moved to streaming paged reads.
- **Impact:** OOM crash or ANR when opening device-selection settings with large history.
- **Recommended remediation:** Use streaming paged reads for device discovery. Only extract `deviceName` from each page, discard samples immediately.
- **Dependencies:** None
- **Implementation complexity:** Low
- **Migration risk:** None
- **Acceptance criteria:** `discoverDevices` peak memory stays bounded regardless of dataset size. Device name extraction verified in unit test.

#### HC-002: Per-workout transactions during reconcile phase
- **ID:** HC-002
- **Category:** Health Connect / Performance
- **Severity:** Medium
- **Confidence:** High (confirmed)
- **Status:** Confirmed
- **Affected files:** `core/database/.../SessionLinkReconcilerImpl.kt:131-168`
- **Current behavior:** Each workout gets its own `heartRateDao.getByTimeRange` + `transactionRunner.runInTransaction { workoutDao.upsertAll(...) }`. For 1000+ workouts over a multi-year history, this is 1000 DB reads + 1000 transactions + 1000 Room invalidation rounds.
- **Evidence:** Code inspection of `recomputeWorkouts` method.
- **Root cause:** Workout recomputation was not batched when session-link reconciler was implemented.
- **Impact:** Slow reconcile phase on large workout histories. Not a correctness issue.
- **Recommended remediation:** Batch workout recomputation into groups (e.g., 50 per transaction), similar to the 5000-row HR/HRV batch pattern. Single Room invalidation per batch instead of per workout.
- **Dependencies:** None
- **Implementation complexity:** Low
- **Migration risk:** None
- **Acceptance criteria:** Reconcile phase with 1000 workouts completes in ≤50 transactions. Same TRIMP/zone values produced.

#### HC-003: Changes-path exercise upsert may not preserve `modelTrimp`
- **ID:** HC-003
- **Category:** Health Connect / Data Integrity
- **Severity:** Medium
- **Confidence:** High (confirmed)
- **Status:** Confirmed
- **Affected files:** `core/healthconnect/.../HealthChangeSynchronizerImpl.kt:367-378`
- **Current behavior:** When an exercise session arrives via the Changes API, the entity is created from `workoutInput.toEntity().copy(...)` with computed metrics but without looking up the existing row's `modelTrimp`. The `@Upsert` overwrites the entire row, setting `modelTrimp = null`. The bulk ingestion path in `RoomHealthIngestionStore.persist()` (line 55-58) explicitly preserves `modelTrimp` via `existing?.modelTrimp`.
- **Evidence:** Direct code comparison: bulk path reads `workoutDao.getById(workout.id)` and copies `existing?.modelTrimp`; changes path does not.
- **Root cause:** Changes-path exercise handling was not updated when `modelTrimp` preservation was added to bulk ingestion.
- **Impact:** A changes-path exercise upsert could overwrite a previously-computed `modelTrimp` with null. The next walk-forward recompute would fix this, making it a transient inconsistency.
- **Recommended remediation:** Verify the changes-path exercise upsert preserves `modelTrimp` from the existing row, matching `RoomHealthIngestionStore.persist()` behavior. If not preserved, add the same existing-row merge pattern.
- **Dependencies:** None
- **Implementation complexity:** Low
- **Migration risk:** None
- **Acceptance criteria:** After a Changes-path exercise upsert, `modelTrimp` matches the previously stored value when no scoring input changed.

#### HC-004: Rate-limit detection relies on error message strings
- **ID:** HC-004
- **Category:** Health Connect / Resilience
- **Severity:** Low
- **Confidence:** Medium (suspected)
- **Status:** Suspected
- **Affected files:** `core/healthconnect/.../HealthConnectRetryPolicy.kt:26-31`
- **Current behavior:** `isTransientHealthConnectFailure` checks `message?.contains("rate limit"/"too many requests"/"quota")`. These strings are not part of the stable Health Connect API contract.
- **Impact:** If Google changes error message text, rate-limit retries stop working. `IOException` catch covers the most common transient failure, so this is defense-in-depth.
- **Recommended remediation:** Monitor Health Connect SDK releases for typed rate-limit exceptions. Add comment documenting the fragility.
- **Dependencies:** Health Connect SDK version updates
- **Implementation complexity:** Low (comment) / Medium (if typed exception becomes available)
- **Migration risk:** None
- **Acceptance criteria:** Documented. Monitored on SDK updates.

### Database Findings

#### DB-001: Exercise HR fetch does in-memory filtering
- **ID:** DB-001
- **Category:** Database / Performance
- **Severity:** Low
- **Confidence:** High (confirmed)
- **Status:** Confirmed
- **Affected files:** `core/database/.../ScoringDayDataLoader.kt:55-59`, `core/database-schema/.../dao/HeartRateDao.kt`
- **Current behavior:** `fetchExerciseHrInRange` fetches ALL record types in a range via `getByTimeRange`, then `.filter { it.recordType == RecordType.EXERCISE.name }` in Kotlin. The `index_hr_v10_type_timestamp` index exists but is unused.
- **Evidence:** Code inspection shows `getByTimeRange` has no `recordType` filter.
- **Root cause:** Query predates the `index_hr_v10_type_timestamp` index.
- **Impact:** Wastes memory on sleep/resting HR records. For a single day, sleep HR could be 28k unwanted rows.
- **Recommended remediation:** Add DAO method: `SELECT * FROM heart_rate_records WHERE recordType = :type AND timestampMs >= :startMs AND timestampMs <= :endMs ORDER BY timestampMs, sourceRecordRef`. Uses existing `index_hr_v10_type_timestamp`.
- **Dependencies:** None
- **Implementation complexity:** Low
- **Migration risk:** None
- **Acceptance criteria:** `fetchExerciseHrInRange` returns only exercise HR records. Same scoring results.

#### DB-002: Retention cleanup single-transaction delete
- **ID:** DB-002
- **Category:** Database / Performance
- **Severity:** Medium
- **Confidence:** High (confirmed)
- **Status:** Confirmed
- **Affected files:** `core/database/.../RetentionCleanup.kt`
- **Current behavior:** Deletes from 12 tables in one transaction. For a user with 1M+ HR records enabling retention, this could delete hundreds of thousands of rows, causing large WAL growth.
- **Evidence:** All deletes wrapped in single `withTransaction`.
- **Root cause:** Designed for daily incremental cleanup (small batch), but also runs on retention setting change (potentially large batch).
- **Impact:** WAL growth, brief lock contention on large first-time cleanup. Daily cleanup is naturally small.
- **Recommended remediation:** Chunk HR/HRV deletes into 10k-row batches for large cleanups. Keep single transaction for low-volume tables.
- **Dependencies:** None
- **Implementation complexity:** Low
- **Migration risk:** None
- **Acceptance criteria:** WAL stays bounded during large retention cleanup. All rows correctly deleted.

### Architecture Findings

#### ARCH-001: `HealthConnectRepositoryImpl` exceeds 800-line hard limit
- **ID:** ARCH-001
- **Category:** Architecture / File Size
- **Severity:** Medium
- **Confidence:** High (confirmed)
- **Status:** Confirmed
- **Affected files:** `core/healthconnect/.../HealthConnectRepositoryImpl.kt` (824 lines)
- **Current behavior:** 30+ methods covering permissions, paginated reads, step aggregation, exercise route handling, interval totals.
- **Root cause:** Organic growth as new record types were added.
- **Impact:** Exceeds project's hard 800-line limit. Harder to navigate.
- **Recommended remediation:** Extract step-aggregation methods (`readSteps`, `readDailyStepTotals`, device-filtered step reads) into a `StepRecordReader` collaborator. Extract interval-totals methods (`readIntervalTotals`, `sessionTotalFor`) into an `IntervalTotalsReader`. Keep permissions and core paginated read infrastructure in the main class.
- **Dependencies:** None
- **Implementation complexity:** Low (pure relocation, no new data hop)
- **Migration risk:** None
- **Acceptance criteria:** `HealthConnectRepositoryImpl` below 600 lines. Extracted readers unit-testable independently. All existing tests pass.

#### ARCH-002: `SleepViewModel` 621 lines with 20 mutable state flows
- **ID:** ARCH-002
- **Category:** Architecture / ViewModel Size
- **Severity:** Medium
- **Confidence:** High (confirmed)
- **Status:** Confirmed
- **Affected files:** `feature/sleep/.../SleepViewModel.kt` (621 lines), plus `DashboardViewModel` (526), `WorkoutDetailViewModel` (442), `VitalsViewModel` (420), `WorkoutsViewModel` (412)
- **Current behavior:** SleepViewModel manages 20 mutable state holders, 14 event handlers, trend data, chart state, and card configuration.
- **Root cause:** Layout management (reordering, visibility toggling, display modes for 3 card groups) adds significant state management responsibility.
- **Impact:** Exceeds 400-line target. All within 800-line hard limit.
- **Recommended remediation:** Extract layout-management concerns into a `SleepLayoutDelegate` class (paralleling the existing `CardManagementDelegate` pattern used in Dashboard). Move trend chart state construction into a `SleepTrendStateFactory`.
- **Dependencies:** None
- **Implementation complexity:** Low
- **Migration risk:** None
- **Acceptance criteria:** `SleepViewModel` below 400 lines. Layout behavior unchanged. Same UI test outcomes.

#### ARCH-003: `ComputeSleepMetricsUseCase` 723 lines
- **ID:** ARCH-003
- **Category:** Architecture / File Size
- **Severity:** Low
- **Confidence:** High (confirmed)
- **Status:** Confirmed
- **Affected files:** `core/scoring/.../ComputeSleepMetricsUseCase.kt` (723 lines)
- **Current behavior:** Single use case with one ~490-line `invoke()` method. Cohesive responsibility — computes all sleep metrics end-to-end. Near the 800-line hard limit.
- **Impact:** Difficult to navigate but functionally cohesive.
- **Recommended remediation:** Extract restoration-computation block (~100 lines) into a `RestorationScoreAssembler`. Extract recovery-flag evaluation (already partially done via `RecoveryFlagEvaluator`) and HRV/RHR Z-score assembly into named helpers. Keep the orchestration in `invoke()`.
- **Dependencies:** None
- **Implementation complexity:** Low (pure extraction)
- **Migration risk:** None — scoring golden fixture tests validate equivalence
- **Acceptance criteria:** Same golden fixture outputs. `invoke()` under 300 lines.

### Dependency Injection Findings

#### DI-001: Ambient `LocalDate.now()` in scoring interface and utility classes
- **ID:** DI-001
- **Category:** DI / Testability
- **Severity:** Medium
- **Confidence:** High (confirmed)
- **Status:** Confirmed
- **Affected files:**
  - `core/model/.../repository/ScoringRepository.kt` — `computeDailySummary(targetDate: LocalDate = LocalDate.now())`
  - `core/model/.../SelectedDateRepository.kt` — 6 direct `LocalDate.now()` calls
  - `core/model/.../util/DateTransition.kt` — 2 `LocalDate.now()` calls
  - `core/scoring/.../UserUseCase.kt` — `calculateAge()` uses `LocalDate.now()`
- **Evidence:** Grep for `LocalDate.now()` in `core/` modules.
- **Root cause:** These classes predate the Clock injection pattern established later in the sync layer.
- **Impact:** Testability concern. Production correctness is fine since Clock and LocalDate.now() agree in production.
- **Recommended remediation:** Inject `Clock` into `SelectedDateRepository` and `UserUseCase`. Replace default parameters in `ScoringRepository` interface with explicit callers providing the date. Leave UI boundary (`BirthdayDatePickerField`) as-is.
- **Dependencies:** None
- **Implementation complexity:** Low
- **Migration risk:** None
- **Acceptance criteria:** Zero `LocalDate.now()` calls in `core/model` and `core/scoring` modules outside UI composables. All callers provide explicit dates.

### UI/Accessibility Findings

#### UI-001: 20 `contentDescription = null` across interactive/informational elements
- **ID:** UI-001
- **Category:** UI / Accessibility
- **Severity:** Medium
- **Confidence:** High (confirmed)
- **Status:** Confirmed
- **Affected files:** `DateSwitcher.kt:258`, `StatusLegend.kt:96`, `TrendCharts.kt:332`, `ReorderableSlot.kt:124`, `ReorderableList.kt:268`, `ReorderableGrid.kt:266`, `UniversalMetricCard.kt:296`, `ActivityVolumeSection.kt:119,187,265`, `WeeklyTrainingSection.kt:138`, `TrainingMixSection.kt:99,274`, `InsightCard.kt:57`, `AiRecommendationCard.kt:52`, `SyncErrorScreen.kt:48`
- **Current behavior:** Some are legitimately decorative icons (null is correct). Others are interactive (drag handles in reorderable lists, navigation arrows, status indicators) and need meaningful content descriptions.
- **Impact:** TalkBack users cannot identify these interactive elements.
- **Recommended remediation:** Case-by-case review. Add `stringResource(R.string.cd_*)` content descriptions for: drag handles ("Reorder"), navigation arrows ("Previous day"/"Next day"), status indicators ("Status: warning"), close buttons ("Dismiss insight"). Keep null for purely decorative icons.
- **Dependencies:** String resources must be added to `strings.xml`
- **Implementation complexity:** Low
- **Migration risk:** None
- **Acceptance criteria:** TalkBack audit passes for all interactive elements. No interactive Icon uses `contentDescription = null`.

### Security Findings

#### SEC-001: Debug-only `KeyRaceTestService` exported status
- **ID:** SEC-001
- **Category:** Security / Hardening
- **Severity:** Low
- **Confidence:** Medium (suspected)
- **Status:** Suspected — needs manifest verification
- **Affected files:** `app/src/debug/kotlin/.../racetest/KeyRaceTestService.kt`
- **Current behavior:** Bound service exists only in debug builds. If exported, other debug apps could bind to it.
- **Impact:** Debug-only. No production risk.
- **Recommended remediation:** Verify `exported="false"` in debug manifest. If missing, add it.
- **Dependencies:** None
- **Implementation complexity:** Trivial
- **Migration risk:** None
- **Acceptance criteria:** Service declared with `android:exported="false"`.

---

## 5. Scoring and Metric Verification Matrix

| Metric | Implementation | Source Inputs | Documented Formula | Implemented Behavior | Review Result | Finding IDs | Action |
|--------|---------------|---------------|-------------------|---------------------|---------------|-------------|--------|
| Sleep Score | `SleepScoringStrategy.kt` | duration, stages, HRV, RHR, fragmentation, regularity | 40% dur + 20% arch + 25% rest + 15% frag (Balanced) | Matches exactly. Logistic curves, age-adjusted architecture targets, hypersomnia dead zone. | **Pass** | — | None |
| Duration Sub-score | `SleepScoringStrategy.kt:24-37` | durationMinutes, goalSleepHours, efficiency | Logistic below goal, dead zone at onset, Gaussian decay above | Matches. Composite: 0.7×TST + 0.3×efficiency. | **Pass** | — | None |
| Architecture Sub-score | `SleepScoringStrategy.kt:46-67` | deep%, REM%, age | 0.5×min(deep/target,1) + 0.5×min(rem/target,1) | Matches. Age-continuous targets via `SleepArchitectureTargetFactory`. | **Pass** | — | None |
| Restoration Sub-score | `SleepScoringStrategy.kt:70-112` | lnRMSSD Z-score, RHR Z-score | 0.5×hrvScore + 0.5×rhrScore, saturation above Z=1.5 | Matches. Late-nadir ×0.95 applied in `ComputeSleepMetricsUseCase`. | **Pass** | — | None |
| Fragmentation Sub-score | `SleepFragmentationCalculator.kt` | WASO, awakening count | Exponential decay, 20min/2 awakening grace | Matches. | **Pass** | — | None |
| Regularity Multiplier | `SleepContinuityCurves.kt:58-63` | circadian score | floor=0.92, span=0.08, null→1.0 | Matches ABOUT.md (0.92–1.00). | **Pass** | — | None |
| Load Score | `LoadScoringStrategy.kt:25-29` | strain ratio (ATL/CTL) | sr≤1.3→100; sr>1.3→100×exp(-2.5×(sr-1.3)²) | Matches exactly. | **Pass** | — | None |
| Readiness | `LoadScoringStrategy.kt:143-158` | restoration, sleep, load | 0.4×rest + 0.3×sleep + 0.3×load, illness cap 50 | Matches. Two-consecutive-night illness check confirmed. | **Pass** | — | None |
| ATL/CTL EMA | `RasScoringStrategy.kt:31-50,78-99` | daily TRIMP series | 7-day/42-day EMA, seed=first 7 SMA, missing=0 | Matches ABOUT.md. Decay variant fills missing dates. | **Pass** | — | None |
| TRIMP (Banister) | `RasCalculator.kt:26-45` | HR samples, RHR, hrMax, sex | duration × hrR × a × exp(b×hrR) × multiplier | Matches. Profile multipliers: Ath=1.0, Act=1.35, Sed=1.75. | **Pass** | — | None |
| TRIMP (Cheng/LT) | `RasCalculator.kt:47-67` | HR, RHR, LT bound, sex | Piecewise around LT boundary | Implemented correctly. | **Pass** | — | None |
| TRIMP (iTRIMP) | `RasCalculator.kt:69-79` | HR, RHR, hrMax | duration × hrR × exp(b×hrR) | Implemented correctly. | **Pass** | — | None |
| RAS | `RasCalculator.kt:84-91` | daily TRIMP, scaling factor | TRIMP × scalingFactor, cap 75 | Matches. Athlete=0.15, Active=0.18, Sedentary=0.25. | **Pass** | — | None |
| Everyday HR Load | `EverydayHeartRateLoadCalculator.kt` | 1-min HR buckets, zones | Zone 1+ TRIMP per bucket, exclude sleep/workout | Matches. Confidence tiers match ABOUT.md. | **Pass** | — | None |
| RHR (Nocturnal Floor) | `SleepPercentileRhrCalculator.kt` | sleep HR samples | Configurable percentile (default 5th) | Matches. 30-day median of nightly nadirs. | **Pass** | — | None |
| HRV Baseline | `BaselineComputer.kt` | nightly lnRMSSD | 7-night mu window, 56-night sigma window, profile-prior blending | Matches. Geometric display: exp(mu). | **Pass** | — | None |
| Circadian Consistency | `CircadianConsistencyRepository.kt` | session bed/wake times | 14-day median baseline, 7-day rolling average, profile thresholds | Matches. Thresholds: Ath=20, Act=30, Sed=45 min. | **Pass** | — | None |
| Illness Onset | `RecoveryFlagEvaluator.kt:62-78` | HRV Z, RHR Z/delta | Two-consecutive-night HRV<-1.5 AND RHR elevated | Matches ABOUT.md. Caps readiness at 50. | **Pass** | — | None |
| Phase Transitions | `Phase.kt`, `PhaseCalculator.kt` | valid HRV night count | 0-6: Calibration, 7-20: Early, 21-59: Maturing, 60+: Mature | Exact match with ABOUT.md. | **Pass** | — | None |
| BMI Classification | `BodyCompositionAssessment.kt` | weight, height | WHO bands: <18.5, 18.5-24.9, 25-29.9, 30+ | Matches ABOUT.md. | **Pass** | — | None |
| Blood Pressure | `HealthMetricsService.kt` | systolic, diastolic | Inclusive ladder: ≤120/80, ≤129/89, ≤139/99, else | Matches ABOUT.md. | **Pass** | — | None |
| Body Temperature | `BodyTemperatureBaselineCalculator.kt` | nightly averages | 14-day trailing average, configurable deviation threshold | Matches. Non-scoring, display-only. | **Pass** | — | None |

**Result: All 22 metrics pass verification. Zero scoring bugs found.**

---

## 6. Health Connect Ingestion Matrix

| Record Type | Read Strategy | Paging/Batching | Dedup Key | Update Behavior | Deletion Behavior | Persistence Target | Recalc Trigger | Performance Risks | Finding IDs |
|-------------|--------------|-----------------|-----------|-----------------|-------------------|-------------------|----------------|-------------------|-------------|
| Sleep Sessions | `readAllPages` (accumulating) | Platform page token | HC record `id` | `@Upsert` on `id` | Changes API `DeletionChange` → DAO delete | `sleep_sessions` + `sleep_stages` | Walk-forward recompute of affected day | Low volume, bounded | — |
| Heart Rate | `readAllPagesStreaming` (per-page callback) | HC page token → ≤5000-row Room transactions | `(sourceRecordRef, timestampMs)` unique | Conflict-targeted `INSERT ON CONFLICT DO UPDATE` | Changes API → `deleteBySourceRecordId` range predicate | `heart_rate_records` | Walk-forward recompute | HC-001 (discoverDevices path) | HC-001 |
| HRV (RMSSD) | `readAllPagesStreaming` (per-page callback) | HC page token → ≤5000-row Room transactions | `(sourceRecordRef, timestampMs)` unique | Conflict-targeted `INSERT ON CONFLICT DO UPDATE` | Changes API → `deleteBySourceRecordId` range predicate | `hrv_records` | Walk-forward recompute | Same as HR | HC-001 |
| Exercise Sessions | `readAllPages` (accumulating) | Platform page token | HC record `id` | Bulk merge preserving `modelTrimp` | Changes API `DeletionChange` → DAO delete | `workout_records` + `workout_route_points` | Walk-forward recompute + reconcile | HC-002 (per-workout txn), HC-003 (modelTrimp) | HC-002, HC-003 |
| Steps | `aggregateGroupByPeriod` (all-devices) or raw `readRecords` (device-selected) | Per-chunk or per-day | `(id)` composite | `@Upsert` on `id` | Changes API → `step_records` lookup for date resolution | `step_records` (deletion lookup only) | Step count re-fetched during recompute | Low volume | — |
| Weight | `readAllPages` (accumulating) | Platform page token | Composite `id` | `@Upsert` | Changes API | `weight_records` | Summary body-metric snapshot | Low volume | — |
| Body Fat | `readAllPages` (accumulating) | Platform page token | Composite `id` | `@Upsert` | Changes API | `body_fat_records` | Summary body-metric snapshot | Low volume | — |
| Blood Pressure | `readAllPages` (accumulating) | Platform page token | Composite `id` | `@Upsert` | Changes API | `blood_pressure_records` | Summary body-metric snapshot | Low volume | — |
| SpO2 | `readAllPages` (accumulating) | Platform page token | Composite `id` | `@Upsert` | Changes API | `oxygen_saturation_records` | Summary body-metric snapshot | Low volume | — |
| Body Temperature | `readAllPages` (accumulating) | Platform page token | Composite `id` | `@Upsert` | Changes API | `body_temperature_records` | Nightly avg cache in `daily_summaries` | Low volume | — |

---

## 7. Large-Dataset Analysis

### Scenario: 1,000,000+ HR Records in 30-Day Period

**Storage estimate:**
- Hot tier (v10 schema): ~30 bytes/row × 1M = ~30 MB raw + ~60 MB indexes ≈ **~90 MB**
- After 90-day rollup to warm tier: 1M ÷ 60 = ~16,667 minute buckets ≈ **negligible**
- Cold tier: 30 daily summaries ≈ **negligible**

**Ingestion bottlenecks:**
- HC page reads: ~200 pages × IPC round-trip. Rate-limited by Health Connect.
- Room writes: ~200 × 5000-row transactions. Each transaction is fast (conflict-targeted upsert with index).
- Per-page `getOrCreateSourceRef`: ~1-3 calls per page (HC groups samples by record). Negligible.
- **Peak memory:** One HC page + one 5000-row batch ≈ bounded, well under 10 MB.

**Recomputation bottlenecks:**
- Walk-forward: 30 days × `computeDailySummary()`. Each day reads ≤1440 minute buckets (SQL-bucketed). Fast.
- Session-link reconcile: Keyset-paged 5000-row batches. 200 batches for 1M rows. Each batch: read → sweep → update in transaction. Serial but bounded.

**Database bottlenecks:**
- `getSleepHrSamplesForSession`: For 8h sleep at 1 Hz = ~28k rows sorted. Indexed, single-session scoped. Acceptable.
- `getMinuteBuckets(dayStart, dayEnd)`: Returns ≤1440 rows. SQL GROUP BY. Fast.
- `observeAggregateByTimeRange`: Single-row SQL aggregate. O(n) scan, O(1) result.

**Target success criteria:**
- [x] Bounded memory during ingestion (one page at a time)
- [x] No full-history materialization
- [x] No ANR (cooperative cancellation with `ensureActive()` + `yield()`)
- [x] Resumable synchronization (4-phase checkpoint)
- [x] Deterministic results (frozen baselines + scoring timezone)
- [x] Date-scoped recomputation (walk-forward)
- [x] All critical queries supported by indexes

**Assessment: The current architecture handles the 1M+ scenario well.** The 90-day hot-tier boundary is the key design decision. No architectural changes needed for this scenario.

---

## 8. Target Architecture

The current architecture is the target architecture with minor refinements:

### Changes from Current State

1. **`HealthConnectRepositoryImpl` decomposition** (ARCH-001): Extract `StepRecordReader` and `IntervalTotalsReader` collaborators.
2. **ViewModel delegation** (ARCH-002): Extract `SleepLayoutDelegate` from `SleepViewModel`.
3. **Clock injection** (DI-001): Remove ambient `LocalDate.now()` from core modules.
4. **discoverDevices streaming** (HC-001): Use streaming paged reads, extract device names per page.
5. **Reconcile batching** (HC-002): Batch workout recomputation into groups.
6. **SQL exercise filter** (DB-001): Add `recordType` predicate to exercise HR query.

All other component responsibilities, dependency direction, ingestion boundaries, transaction ownership, scoring-engine boundaries, invalidation ownership, dispatcher ownership, DI scopes, and UI-state boundaries remain unchanged.

---

## 9. Phased Implementation Roadmap

### Phase 0 — Baseline and Safety Rails

**Objective:** Establish characterization baselines before making changes.

**Included finding IDs:** Prerequisite for all phases.

**Steps:**
1. Verify golden fixture tests pass: `./gradlew testDebugUnitTest --tests "*.golden.*"`
2. Run `./gradlew detekt` — capture current baseline count
3. Verify `HealthConnectRepositoryImpl` line count (should report 824)
4. Verify HC-003 (modelTrimp preservation on changes path) by reading the code path and writing a characterization test if the bug is confirmed

**Completion criteria:** All golden tests pass. Detekt baseline documented. HC-003 confirmed or dismissed.

---

### Phase 1 — Correctness and Data Integrity

**Objective:** Fix the one suspected data integrity issue.

**Included finding IDs:** HC-003

**Steps:**
1. Read `HealthChangeSynchronizerImpl` exercise upsert path (~line 326-379)
2. Compare with `RoomHealthIngestionStore.persist()` workout merge logic
3. If `modelTrimp` is not preserved:
   a. Add existing-row lookup in the changes-path exercise upsert
   b. Merge `modelTrimp` from existing row (matching `RoomHealthIngestionStore` pattern)
   c. Add unit test: upsert via changes path preserves previously-computed `modelTrimp`
4. If `modelTrimp` IS preserved: close finding as "no issue"

**Expected file changes:**
- `core/healthconnect/.../HealthChangeSynchronizerImpl.kt`
- `core/healthconnect/src/test/.../HealthChangeSynchronizerImplTest.kt`

**Prerequisites:** Phase 0 complete
**Schema changes:** None
**Migration strategy:** N/A
**Rollback strategy:** Revert commit
**Risks:** None — the walk-forward already fixes transient inconsistency
**Validation:** Golden fixture tests + new characterization test
**Completion criteria:** Changes-path exercise upsert preserves `modelTrimp`. Test proves it.

---

### Phase 2 — Health Connect and Database Performance

**Objective:** Fix memory and performance issues in ingestion and database layers.

**Included finding IDs:** HC-001, HC-002, DB-001, DB-002

#### WP-2A: discoverDevices streaming (HC-001)

**Steps:**
1. Add `readHeartRateSamplesPaged` variant that extracts only `deviceName` per page (or reuse existing streaming method with a device-name-only callback)
2. Replace `readHeartRateSamples`/`readHrvSamples` calls in `discoverDevices` with streaming equivalent
3. Collect distinct device names per page, discard sample data immediately
4. Unit test: mock HC client returning 100+ pages, verify bounded memory (no OOM)

**Expected file changes:**
- `core/healthconnect/.../HealthConnectRepositoryImpl.kt`
- `core/healthconnect/src/test/.../HealthConnectRepositoryImplTest.kt`

#### WP-2B: Reconcile workout batching (HC-002)

**Steps:**
1. In `SessionLinkReconcilerImpl.recomputeWorkouts`, batch workouts into groups of 20
2. Each batch: read all HR samples for all workouts in the batch (single date range query), compute metrics, upsert all in one transaction
3. Verify: same TRIMP/zone values as per-workout path

**Expected file changes:**
- `core/database/.../SessionLinkReconcilerImpl.kt`

#### WP-2C: SQL exercise HR filter (DB-001)

**Steps:**
1. Add `HeartRateDao.getExerciseByTimeRange(startMs, endMs)` with `WHERE recordType = 'EXERCISE'`
2. Update `ScoringDayDataLoader.fetchExerciseHrInRange` to call new method
3. Remove in-memory filter

**Expected file changes:**
- `core/database-schema/.../dao/HeartRateDao.kt`
- `core/database/.../ScoringDayDataLoader.kt`

#### WP-2D: Chunked retention cleanup (DB-002)

**Steps:**
1. In `RetentionCleanup.deleteBefore`, chunk HR and HRV deletes into 10k-row batches (using keyset pagination on `timestampMs`)
2. Keep single transaction for low-volume tables (sleep, workouts, vitals, summaries)
3. Each HR/HRV batch in its own transaction

**Expected file changes:**
- `core/database/.../RetentionCleanup.kt`

**Prerequisites:** Phase 0 complete
**Schema changes:** None (new DAO query only)
**Migration strategy:** N/A
**Rollback strategy:** Revert commits
**Risks:** Low. All changes are performance optimizations with identical outputs.
**Validation:** Existing tests + benchmark with synthetic large datasets
**Completion criteria:** All four work packages pass tests. `discoverDevices` bounded memory. Reconcile batch count ≤ `ceil(workouts/50)`. Exercise HR query returns only exercise records. Retention cleanup WAL stays bounded.

---

### Phase 3 — Architecture and File Size

**Objective:** Bring files within size limits and improve testability.

**Included finding IDs:** ARCH-001, ARCH-002, ARCH-003, DI-001

#### WP-3A: Decompose `HealthConnectRepositoryImpl` (ARCH-001)

**Steps:**
1. Extract `StepRecordReader` — `readSteps`, `readDailyStepTotals`, device-filtered step methods
2. Extract `IntervalTotalsReader` — `readIntervalTotals`, `sessionTotalFor`
3. Inject both as collaborators. Keep permissions, core paginated reads, and session reads in main class.
4. Target: main class under 600 lines

**Expected file changes:**
- `core/healthconnect/.../HealthConnectRepositoryImpl.kt` (shrink)
- `core/healthconnect/.../StepRecordReader.kt` (new)
- `core/healthconnect/.../IntervalTotalsReader.kt` (new)

#### WP-3B: Extract `SleepLayoutDelegate` (ARCH-002)

**Steps:**
1. Extract layout management (top cards, trends, metric cards — reorder, toggle, display mode, reset) into `SleepLayoutDelegate`
2. `SleepViewModel` holds the delegate and forwards events
3. Target: `SleepViewModel` under 400 lines

**Expected file changes:**
- `feature/sleep/.../SleepViewModel.kt` (shrink)
- `feature/sleep/.../SleepLayoutDelegate.kt` (new)

#### WP-3C: Extract `ComputeSleepMetricsUseCase` helpers (ARCH-003)

**Steps:**
1. Extract restoration-score assembly (~100 lines) into `RestorationScoreAssembler`
2. Extract HRV/RHR Z-score assembly into `BaselineZScoreComputer`
3. Keep orchestration in `invoke()` under 300 lines
4. Validate with golden fixture tests

**Expected file changes:**
- `core/scoring/.../ComputeSleepMetricsUseCase.kt` (shrink)
- `core/scoring/.../sleep/RestorationScoreAssembler.kt` (new)
- `core/scoring/.../sleep/BaselineZScoreComputer.kt` (new)

#### WP-3D: Remove ambient `LocalDate.now()` from core modules (DI-001)

**Steps:**
1. Inject `Clock` into `SelectedDateRepository` — replace 6 `LocalDate.now()` calls with `LocalDate.now(clock)`
2. Inject `Clock` into `UserUseCase` — replace `calculateAge()` usage
3. Remove default parameter `LocalDate.now()` from `ScoringRepository.computeDailySummary` — require callers to pass explicit date
4. Update `DateTransition` to accept `Clock` parameter

**Expected file changes:**
- `core/model/.../SelectedDateRepository.kt`
- `core/scoring/.../UserUseCase.kt`
- `core/model/.../repository/ScoringRepository.kt`
- `core/model/.../util/DateTransition.kt`
- All callers of `computeDailySummary()` that relied on default

**Prerequisites:** Phase 0 complete
**Schema changes:** None
**Migration strategy:** N/A
**Rollback strategy:** Revert commits
**Risks:** WP-3D requires updating all callers of `computeDailySummary()` — verify none missed via compile error.
**Validation:** All tests pass. `wc -l` on affected files within limits. `grep -r "LocalDate.now()" core/` returns zero hits outside UI composables.
**Completion criteria:** All files within limits. Clock injected consistently. Golden fixtures pass.

---

### Phase 4 — Accessibility and Long-Term Maintainability

**Objective:** Address accessibility gaps and remaining maintainability items.

**Included finding IDs:** UI-001, SEC-001, HC-004

#### WP-4A: Accessibility content descriptions (UI-001)

**Steps:**
1. Audit all 20 `contentDescription = null` occurrences
2. For interactive elements (drag handles, nav arrows, close buttons, status indicators): add `stringResource(R.string.cd_*)` content descriptions
3. Keep null for purely decorative icons
4. Add string resources to `app/src/main/res/values/strings.xml`

**Expected file changes:**
- `app/src/main/res/values/strings.xml`
- ~10-15 composable files

#### WP-4B: Debug service export verification (SEC-001)

**Steps:**
1. Check `KeyRaceTestService` declaration in debug manifest
2. Add `android:exported="false"` if missing

**Expected file changes:**
- `app/src/debug/AndroidManifest.xml` (if applicable)

#### WP-4C: Document HC retry fragility (HC-004)

**Steps:**
1. Add comment to `HealthConnectRetryPolicy.kt` documenting the message-string fragility
2. Add TODO to check for typed rate-limit exception on next HC SDK update

**Expected file changes:**
- `core/healthconnect/.../HealthConnectRetryPolicy.kt`

**Prerequisites:** None
**Risks:** None
**Completion criteria:** TalkBack audit passes for interactive elements. Debug service verified. Comment added.

---

## 10. Ordered Work Packages

| # | Title | Finding IDs | Dependencies | Acceptance Criteria |
|---|-------|-------------|--------------|-------------------|
| WP-0 | Baseline verification | — | None | Golden tests pass, detekt baseline captured |
| WP-1 | modelTrimp changes-path fix | HC-003 | WP-0 | Changes-path preserves modelTrimp; test proves it |
| WP-2A | discoverDevices streaming | HC-001 | WP-0 | Bounded memory during discovery |
| WP-2B | Reconcile workout batching | HC-002 | WP-0 | ≤ ceil(workouts/20) transactions during reconcile |
| WP-2C | SQL exercise HR filter | DB-001 | WP-0 | Only exercise records returned; same scoring |
| WP-2D | Chunked retention cleanup | DB-002 | WP-0 | WAL bounded during large cleanup |
| WP-3A | HealthConnectRepositoryImpl decomposition | ARCH-001 | WP-2A | Main class < 600 lines |
| WP-3B | SleepViewModel delegate extraction | ARCH-002 | None | VM < 400 lines |
| WP-3C | ComputeSleepMetricsUseCase extraction | ARCH-003 | WP-0 | invoke() < 300 lines; golden tests pass |
| WP-3D | Clock injection cleanup | DI-001 | None | Zero LocalDate.now() in core modules |
| WP-4A | Accessibility content descriptions | UI-001 | None | TalkBack audit passes |
| WP-4B | Debug service export verification | SEC-001 | None | exported="false" verified |
| WP-4C | HC retry documentation | HC-004 | None | Comment added |

---

## 11. Performance Validation Plan

### Benchmarks Required

| Benchmark | Target | Synthetic Dataset | Location |
|-----------|--------|-------------------|----------|
| HC ingestion throughput | Complete without OOM | 1M HR records across 30 days (200 HC pages) | `database-benchmark/` |
| discoverDevices memory | Peak < 10 MB | Same dataset | `database-benchmark/` |
| Session-link reconcile | < 60s for 1M records | 1M HR + 100 sleep + 500 workout sessions | `database-benchmark/` |
| Walk-forward recompute | < 30s for 365 days | 365 daily summaries with TRIMP series | `core/database/src/test/` |
| Retention cleanup WAL | WAL < 50 MB | 500k HR records to delete | `database-benchmark/` |
| SQL minute-bucket query | < 100ms per day | 86k raw rows per day | `database-benchmark/` |
| Backup streaming | < 5 min for 1M HR | Full database backup | `database-benchmark/` |

### Existing Benchmarks

The `database-benchmark/` module already exists. The golden fixture tests in `core/database/src/test/.../golden/` provide scoring regression coverage. `ScoringEquivalenceGoldenTest` verifies hot/warm tier equivalence.

---

## 12. Migration and Compatibility Risks

### Room Migrations
No schema changes proposed. All findings are code-level optimizations and decompositions.

### Existing User Data
No data migration required. All changes preserve existing stored data.

### Score Changes After Bug Fixes
- **HC-003 (modelTrimp):** If confirmed, the fix prevents transient inconsistency but does not change final scores (the walk-forward already corrects on the next recompute). No user-visible score change expected.

### Backward Compatibility
All changes are internal. No API surface changes. No preference schema changes. No backup format changes.

### Rollback Feasibility
All work packages are independently revertible via git revert. No database migrations to roll back.

---

## 13. Documentation Updates

| Change | Documentation to Update |
|--------|------------------------|
| WP-3A (HealthConnectRepositoryImpl decomposition) | `internal-docs/DATA_FLOW.md` §1.1 — update component table with new collaborators |
| WP-3C (ComputeSleepMetricsUseCase extraction) | `internal-docs/DATA_FLOW.md` §2.5 — add extracted helpers to component table |
| WP-3D (Clock injection) | `internal-docs/DATA_FLOW.md` §2.1 — note Clock injection requirement |

No changes required to:
- `ABOUT.md` (no scoring formula changes)
- `docs/about.md` (no user-facing methodology changes)
- `docs/privacy.md` (no data collection changes)
- In-app About strings (no score explanation changes)

---

## 14. Open Decisions

### OD-001: HC-003 Confirmation — RESOLVED (Confirmed Bug)
- **Question:** Does the Changes-path exercise upsert actually fail to preserve `modelTrimp`?
- **Answer:** **Yes — confirmed bug.** The bulk ingestion path (`RoomHealthIngestionStore.kt:55-58`) reads the existing row via `workoutDao.getById(workout.id)` and copies `existing?.modelTrimp` onto the fresh entity. The changes path (`HealthChangeSynchronizerImpl.kt:367-378`) creates the entity from `workoutInput.toEntity().copy(...)` without looking up the existing row — `modelTrimp` defaults to null, and `@Upsert` overwrites the entire row.
- **Impact:** Transient — next walk-forward recompute fixes it. But until then, `COALESCE(modelTrimp, trimp)` falls back to zone-TRIMP instead of model-TRIMP, potentially producing incorrect ATL/CTL/strain/load/readiness values for the affected day.
- **Resolution:** WP-1 adds the same existing-row lookup to the changes path.
- **Affected roadmap items:** WP-1 (confirmed necessary)

### OD-002: Workout Batch Size for Reconcile — RESOLVED (20)
- **Question:** What batch size should WP-2B use for workout recomputation?
- **Answer:** **20 workouts per batch.** Keeps per-batch memory low — 20 workouts × ~1h avg = ~20h of HR data worst case.
- **Affected roadmap items:** WP-2B

---

## 15. Definition of Done

### Architecture
- [ ] All production Kotlin files ≤ 800 lines (hard limit)
- [ ] All targeted files ≤ 400 lines (soft limit)
- [ ] Zero feature-to-feature coupling (maintained)
- [ ] Clean dependency direction (maintained)

### Health Connect Correctness
- [ ] `discoverDevices` bounded memory regardless of dataset size
- [ ] Changes-path exercise upsert preserves `modelTrimp` (or confirmed not needed)
- [ ] Reconcile phase uses batched transactions
- [ ] Idempotent upserts verified for all record types (maintained)

### Large-Volume Performance
- [ ] 1M+ HR record ingestion completes without OOM
- [ ] Walk-forward recompute bounded to O(days × 1440 buckets) per day
- [ ] Retention cleanup WAL stays bounded
- [ ] All critical queries use appropriate indexes

### Database Behavior
- [ ] Exercise HR query uses SQL-side `recordType` filter
- [ ] Retention cleanup chunked for HR/HRV tables
- [ ] Hot/warm tier equivalence maintained (golden test)

### Scoring Correctness
- [ ] All golden fixture tests pass after every change
- [ ] All scoring formulas match ABOUT.md (maintained — no formula changes proposed)
- [ ] Deterministic recomputation preserved (maintained)

### Security and Privacy
- [ ] Debug service export verified
- [ ] No new logging of health data values
- [ ] SQLCipher encryption maintained

### Accessibility
- [ ] All interactive elements have meaningful content descriptions
- [ ] TalkBack audit passes for affected screens

### Documentation
- [ ] `internal-docs/DATA_FLOW.md` updated for architectural decompositions
- [ ] No stale component references in documentation

### Validation
- [ ] `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest` passes
- [ ] `./gradlew lintRelease` passes
- [ ] No new detekt issues introduced

---

*This plan was produced by a full-codebase audit on 2026-08-27. It reflects the repository state at commit `3a4dddad` (main). No production code was modified.*
