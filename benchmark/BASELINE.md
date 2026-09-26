# Baseline frame-timing numbers

## F14 cold-start compilation comparison — 2026-07-29

Measured on physical Samsung SM-A576B (Android API 36), using three iterations
per cold-start mode. Results JSON:
`benchmark/build/outputs/connected_android_test_additional_output/benchmarkBenchmark/connected/SM-A576B - 16/app.readylytics.health.benchmark-benchmarkData.json`.

| Compilation mode | `timeToInitialDisplayMs` median |
|---|---:|
| `CompilationMode.None()` | 563.629961 ms |
| `CompilationMode.Partial(BaselineProfileMode.Require)` | 467.200859 ms |

The required Baseline Profile median is lower in this run. F14 enforces no
performance threshold; record and review measured results when regenerating.

**STATUS: PARTIALLY RECORDED** — `vitalsFling`, `vitalsChartPanAndZoom`, `coldStart`, and `warmStart` frame-timing & startup numbers extracted from physical device benchmark run (Samsung SM-A576B, Android API 36). `dashboardVitalsTabSwitch` and `hotStart` remain unmeasured. **The SQLCipher key race originally blamed here was fixed and verified in July 2026** — `SqlCipherKeyManager` holds a cross-process `FileLock` plus an in-process `ReentrantLock` around the key critical section, and `SqlCipherKeyManagerTest` / `SqlCipherKeyManagerCrossProcessRaceTest` were re-run green on 2026-09-26. The remaining blocker is environmental: `:benchmark`'s `benchmarkBenchmark` variant inherits release signing, so `:app:verifyReleaseSigningInputs` fails without the `READYLYTICS_UPLOAD_*` secrets (see `internal-docs/RELEASE_SIGNING.md`). Re-run on a machine that has them.

Once connected to a device/emulator after resolving the tab navigation blocker:

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

These numbers will be the "before" reference for the F1/F3/F4/F5/F9/F11/F15/F19 UI
items in `internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md`. After running
the command and extracting the real numbers from the JSON output, update the
table cells below and re-commit with the populated data. Do not overwrite this
entry — instead, append a new dated section after each relevant F-series item
lands.

## M2 Initial Baseline — before any F-series item

| Journey | P50 (ms) | P90 (ms) | P99 (ms) |
|---|---|---|---|
| vitalsFling | 18.05 ms | 20.20 ms | 24.79 ms |
| vitalsChartPanAndZoom | 21.68 ms | 25.98 ms | 35.70 ms |
| dashboardVitalsTabSwitch | Unmeasured (needs release signing env, see above) | Unmeasured (needs release signing env, see above) | Unmeasured (needs release signing env, see above) |

## Startup (StartupBenchmark, same run)

| Mode | timeToInitialDisplayMs P50 |
|---|---|
| coldStart | 520.41 ms |
| warmStart | 184.68 ms |
| hotStart | Unmeasured (needs release signing env, see note above) |

## How to record the baseline

1. Ensure an Android device or emulator is connected and available to `adb`.
2. Run: `./gradlew :benchmark:connectedBenchmarkAndroidTest`
3. Locate the JSON output file (typically at `benchmark/build/outputs/androidTest-results/connected/.../app.readylytics.health.benchmark-benchmarkData.json`).
4. Extract the `frameDurationCpuMs` P50/P90/P99 percentiles for each `ScrollBenchmark` journey and the `timeToInitialDisplayMs` P50 for each `StartupBenchmark` mode.
5. Replace each "Pending (no device available)" placeholder above with the actual number.
6. Commit with: `git commit -am "perf(M2): record baseline frame-timing numbers from real device run"`

---

# Health Data Remediation Baseline Correctness Evidence Inventory

## Baseline Environment & Clean Suite Verification (Task B1 Step 1)

- **Inspected Plan Baseline HEAD:** `93c468b2` (Room Schema 19)
- **Current Verified HEAD:** `ba89eb9cea4d04dba45412b5197f680c672822d4` (`ba89eb9c`)
- **Working Tree:** Clean (`git status --short` returned 0 modified files)
- **Execution Timestamp:** 2026-09-11 07:54 UTC+2
- **Command:**
  ```bash
  git status --short
  git rev-parse HEAD
  ./gradlew :core:model:testDebugUnitTest :core:scoring:testDebugUnitTest :core:healthconnect:testDebugUnitTest :core:database:testDebugUnitTest :app:testDebugUnitTest :feature:settings:testDebugUnitTest
  ```
- **Build Status:** SUCCESS (394 actionable tasks, 0 errors)
- **Clean Suite Results by Module:**
  - `:core:model`: 856 tests, 0 failures, 0 errors, 0 skipped (PASSED)
  - `:core:scoring`: 847 tests, 0 failures, 0 errors, 0 skipped (PASSED)
  - `:core:healthconnect`: 193 tests, 0 failures, 0 errors, 0 skipped (PASSED)
  - `:core:database`: 468 tests, 0 failures, 0 errors, 0 skipped (PASSED)
  - `:app`: 605 tests, 0 failures, 0 errors, 0 skipped (PASSED)
  - `:feature:settings`: 100 tests, 0 failures, 0 errors, 0 skipped (PASSED)
  - **Total:** 3,069 tests, 0 failures, 0 errors, 0 skipped across all 6 modules.

### Targeted Fixture Baseline Status (Pre-existing Clean State)

| Fixture | Module | Tests | Failures | Status | Execution Time |
|---|---|---|---|---|---|
| `RoomHealthIngestionStoreTest` | `:core:database` | 4 | 0 | PASSED | 7.862s |
| `BaselineComputerBackfillEquivalenceTest` | `:core:database` | 2 | 0 | PASSED | 1.791s |
| `ResyncCheckpointResumeTest` | `:core:healthconnect` | 11 | 0 | PASSED | 4.089s |
| `LocalRestoreValidationTest` | `:app` | 18 | 0 | PASSED | 12.027s |

---

## HC-001 Concrete Characterization Run (Task B1 Steps 2 & 3)

- **Target Fixture:** `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/RoomHealthIngestionStoreTest.kt`
- **Injected Regression Test:**
  ```kotlin
  @Test
  fun `same source and timestamp replaces bpm`() = runTest {
      val sample = HeartRateInput("source_1000", 1000L, 60, "RESTING", null, "watch")
      store.persistHeartRateSamples(listOf(sample))
      store.persistHeartRateSamples(listOf(sample.copy(beatsPerMinute = 81)))
      assertEquals(81, database.heartRateDao().getByTimeRange(1000L, 1001L).single().beatsPerMinute)
  }
  ```
- **Execution Command:**
  ```bash
  ./gradlew :core:database:testDebugUnitTest --tests '*RoomHealthIngestionStoreTest'
  ```
- **Observed Result:** FAILED (5 tests completed, 1 failed)
  ```text
  RoomHealthIngestionStoreTest > same source and timestamp replaces bpm FAILED
      java.lang.AssertionError: Expected <81>, actual <60>.
          at kotlin.test.DefaultAsserter.fail(DefaultAsserter.kt:16)
          at kotlin.test.Asserter.assertTrue(Assertions.kt:767)
          at kotlin.test.DefaultAsserter.assertTrue(DefaultAsserter.kt:11)
          at kotlin.test.Asserter.assertEquals(Assertions.kt:786)
          at kotlin.test.DefaultAsserter.assertEquals(DefaultAsserter.kt:11)
          at kotlin.test.AssertionsKt__AssertionsKt.assertEquals(Assertions.kt:63)
          at kotlin.test.AssertionsKt.assertEquals(Unknown Source)
          at app.readylytics.health.core.database.data.local.RoomHealthIngestionStoreTest$same source and timestamp replaces bpm$1.invokeSuspend(RoomHealthIngestionStoreTest.kt:116)
  ```
- **Root Cause Confirmed:** `HeartRateDao.kt:187` `conflictTargetedUpsert` updates only `recordType = excluded.recordType, sessionId = excluded.sessionId, deviceName = excluded.deviceName` and omits `beatsPerMinute = excluded.beatsPerMinute`. When sample is updated with new BPM (81), old BPM (60) is retained.
- **Protocol Action:** Reverted the test edit immediately following characterization. In accordance with Task B1 Step 3, baseline evidence records the assertion and result; failing test is deferred to the P3 repair branch so main branch remains green.

---

## Reproduction Inventory by Task Domain (Task B1 Step 4)

| Findings | Owning task and assertion / failure injection |
|---|---|
| HC-001, DB-001 | P3: same ID numeric edit, remove one/all samples, timestamp move, opaque underscore ID; compare clean import and source FK |
| HC-002, HC-005 | H1/H6: delete before baseline token; interrupt HR and HRV pages independently; retry across midnight/DST/config changes |
| HC-003 | H2: grant→revoke→repeat→regrant, denied after first page; retain totals and suppress pruning |
| HC-004, HC-006 | H3–H5: historical VO2 delete, independent interval edits, unreadable route, assertion that provider is outside transaction |
| DB-002 | T1–T3: cutoff inside a minute, reimport warm history, rolled-source delete/relink, interrupted publication |
| CACHE-001/002, ARCH-001 | P2/C3/R3: kill after raw deletion and before scoring/token/enqueue; retained suffix on HR-absent history; restore concurrent with scoring |
| SCORE-001/006 | C1: future nights and wider batch do not alter D; live versus backfill and fixed-zone DST |
| SCORE-002/003 | C2/C3: 6/7, 29/30, 59/60 eligible observations; frozen replay, deleted only sleep, assembler failure |
| SCORE-004/005/007 | C4–C6: supplemental nap, split core/stages, all TRIMP models/frozen hrMax, next-midnight VO2 and steps intervals |
| SEC-001 | S1: synthetic private payload absent at every release sink including nested exception messages |
| SEC-002/003/004/005 | S2/S3/R1–R3: incomplete inventory, count/FK mismatch, interrupted rotation/export/restore, failure with one old backup |
| PERF-001/002/003 | B2: parent distribution distinct from sample count, real store/scoring, rollup and export stage costs |

---

## Correctness Evidence Register (Task B1 Step 5)

Finding | HEAD | command/test | intended invariant | observed outcome | repair task
---|---|---|---|---|---
HC-001 | ba89eb9c | `./gradlew :core:database:testDebugUnitTest --tests '*RoomHealthIngestionStoreTest'` (`same source and timestamp replaces bpm`) | replacement BPM=81 on re-ingestion of same sample key | `java.lang.AssertionError: Expected <81>, actual <60>.` (conflict clause in `HeartRateDao.kt:187` updates metadata only, omitting `beatsPerMinute = excluded.beatsPerMinute`) | P3
DB-001 | ba89eb9c | `./gradlew :core:database:testDebugUnitTest --tests '*RoomHealthIngestionStore*'` (`SourceReplacementInstrumentedTest`) | Source metadata carries opaque source ID with underscores, true sample start/end bounds, origin package, and stable source FK across re-ingestion | not run: planned fixture to be implemented in Task P3; code inspection confirms substringBefore('_') id truncation and createdAtMs pinned to samples.first().timestampMs in RoomHealthIngestionStore.kt:105-110 | P3
HC-002 | ba89eb9c | `./gradlew :core:healthconnect:testDebugUnitTest --tests '*ResyncCheckpointResumeTest*'` | Resumed chunk scan across multiple pages captures and reconciles pre-baseline deletions without losing deletion completeness | not run: planned failure injection fixture to be implemented in Task H1/H6; code inspection confirms collectReconcilableTypes excludes HR/HRV when resuming page tokens (HealthIngestionCoordinator.kt:386-390) | H1
HC-003 | ba89eb9c | `./gradlew :core:healthconnect:testDebugUnitTest --tests '*PermissionLifecycleTest*'` | Revoked optional permission returns typed `Denied` without zeroing summary totals or pruning unread types; regrant bootstraps cleanly | not run: planned fixture to be implemented in Task H2; code inspection confirms StepRecordReader returns zero on SecurityException and merges tokens without clearing denied types | H2
HC-004 | ba89eb9c | `./gradlew :core:healthconnect:testDebugUnitTest --tests '*HealthChangeSynchronizerTest*'` | VO2 Max changes and independent interval (distance/elevation) edits trigger delta refresh and update historical totals | not run: planned fixture to be implemented in Task H3/H4; code inspection confirms HealthDataType enum and HealthChangeSynchronizerImpl omit VO2 Max and interval change dispatch | H3/H4
HC-005 | ba89eb9c | `./gradlew :core:healthconnect:testDebugUnitTest --tests '*HistoricalRunIdentityTest*'` | Historical resync retry preserves immutable run identity (range, scoring zone, algorithm version) across midnight/DST and setting changes | not run: planned fixture to be implemented in Task H6; code inspection confirms FullHistoricalResyncUseCase resolves today/retention anew on retry, discarding prior run progress | H6
HC-006 | ba89eb9c | `./gradlew :core:healthconnect:testDebugUnitTest --tests '*ExerciseDeltaReplacementTest*'` | Exercise delta update with unreadable route preserves previously imported GPS route points; provider enrichment executes outside Room transaction | not run: planned fixture to be implemented in Task H5; code inspection confirms HealthChangeSynchronizerImpl deletes existing workout before checking route availability and performs sessionTotalFor IPC inside transaction | H5
DB-002 | 1974f52e (+T2 fix round) | `./gradlew :core:database:testDebugUnitTest --tests '*DataRollupCoverageTest*' --tests '*MinuteCoveragePublicationTest*' --tests '*MinuteCoverageMigrationTest*'` (behaviour) and `--tests '*OdOneContributionCostMeasurementTest*'` (OD-1 cost evidence) | Hot/warm tier maintains single authoritative coverage per minute without overlap, dropped samples on sub-minute cutoffs, or double-counting on historical reimport | PASS. `completeMinuteCutoff` aligns the cutoff (WP-17/T1); `minute_coverage` now carries exactly one visible generation and quality per minute, an ordinary rollup quarantines `LEGACY_UNKNOWN` minutes instead of concatenating them, and superseded contributions/bucket slices are deleted in the publish transaction. **OD-1 cost evidence** (720 complete minutes × 12 samples/min = 8,640 samples; file-backed SQLite, WAL, auto-vacuum, Robolectric host JVM, NO SQLCipher → absolute bytes are a floor, ratios hold): dense-parent (1 source) — 720 contributions = 98,304 B (**136.5 B/source-minute**), coverage 24,576 B (34.1 B/minute), **170.7 B/published minute**, source table sub-page (0 B), WAL at commit 524,288 B, rollup 190 ms in 1 transaction, net page delta −729,088 B (rollup still reclaims 76% of the 954,368 B raw footprint), single-source delta-delete 4.54 ms removing all 720 contributions and freeing 98,304 B, staging peak 589,824 B for 720 staged samples. one-source-per-sample (8,640 sources) — 8,640 contributions = 679,936 B (**78.7 B/source-minute**, better page packing), coverage 24,576 B, **978.5 B/published minute (5.7× dense-parent)**, plus 954,368 B of `health_source_records` (~110 B/source) which is the dominant multiplier, WAL at commit 947,632 B, rollup 98 ms in 1 transaction, net page delta only −184,320 B (fan-out erases ~75% of the rollup's space saving), single-source delta-delete 1.07 ms removing 1 contribution, staging peak 589,824 B. Deletion is bounded and index/WAL growth stays sub-MB per day-chunk in both distributions. | T1–T3
CACHE-001 | ba89eb9c | `./gradlew :core:database:testDebugUnitTest --tests '*DirtyRangeJournalTest*'` | Raw mutations atomically write a dirty range journal in the same Room transaction before token promotion, surviving process death | not run: planned fixture to be implemented in Task P1/P2; code inspection confirms HealthChangeSynchronizerImpl commits Room mutations before token promotion and affected dates are held in volatile memory | P1/P2
CACHE-002 | ba89eb9c | `./gradlew :core:scoring:testDebugUnitTest --tests '*ScoreInvalidationTest*'` | Score invalidation recomputes transitive dependencies (sleep baseline → workout TRIMP → acute/chronic load) without being truncated by a rigid 84-day fan-out constant | not run: planned fixture to be implemented in Task P2/C3; code inspection confirms ScoreInvalidation.kt enforces a hard 84-day lookback cutoff regardless of residual fatigue tail | P2/C3
ARCH-001 | ba89eb9c | `./gradlew :core:database:testDebugUnitTest --tests '*HealthMutationCoordinatorTest*'` | Sync, rollup, cleanup, restore, and scoring coordinate through a shared domain mutation coordinator preventing mixed-generation publication | not run: planned fixture to be implemented in Task P2/R3; code inspection confirms separate synchronization locks across HealthSyncUseCase, DataRollupWorker, DataCleanupWorker, and LocalRestoreServiceImpl | P2/R3
SCORE-001 | ba89eb9c | `./gradlew :core:database:testDebugUnitTest --tests '*BaselineComputerBackfillEquivalenceTest*'` | Historical RHR backfill on day D excludes future nights (scoreDay > D), preserving walk-forward determinism | not run: planned fixture to be implemented in Task C1; code inspection confirms computeDayBackfillBaseline bounds HRV with scoreDay < target but filters RHR nadirs only by lower date (BaselineComputer.kt:485-519) | C1
SCORE-002 | ba89eb9c | `./gradlew :core:scoring:testDebugUnitTest --tests '*CalibrationStateTest*'` | Calibration state and maturity phase transitions are consistent across live scoring, frozen replay, and mature histories at 7, 30, and 60 days | not run: planned fixture to be implemented in Task C2; code inspection confirms ComputeSleepMetricsUseCase conflates window count with lifetime maturity, setting frozen count to 0 and preventing MATURE phase | C2
SCORE-003 | ba89eb9c | `./gradlew :core:database:testDebugUnitTest --tests '*FinalSummaryAssemblerTest*'` | Authoritative deletion of sleep data clears dependent sleep, restoration, and recommendation fields rather than preserving stale prior summaries | not run: planned fixture to be implemented in Task C3; code inspection confirms BaseSummaryAssembler copies old summary and FinalSummaryAssembler leaves previous sleep fields intact on absence | C3
SCORE-004 | ba89eb9c | `./gradlew :core:scoring:testDebugUnitTest --tests '*SleepDayAggregatorTest*'` | Whole-sleep duration and core recovery use distinct session scopes; adding a supplemental nap does not alter core nadir position or fragmentation | not run: planned fixture to be implemented in Task C4; code inspection confirms ReadinessSummaryCoordinator synthesizes a session with total duration but core interval, causing SleepNadirAnalyzer to normalize core nadir by total sleep duration | C4
SCORE-005 | ba89eb9c | `./gradlew :core:scoring:testDebugUnitTest --tests '*WorkoutLoadMetricsTest*'` | Workout display metrics and persisted daily TRIMP share identical input provenance, frozen hrMax, and canonical model calculations | not run: planned fixture to be implemented in Task C5; code inspection confirms ComputeWorkoutLoadMetricsUseCase uses current preferences without summary frozen hrMax, while ComputeDailyTrimpUseCase uses loaded sample average and frozen hrMax | C5
SCORE-006 | ba89eb9c | `./gradlew :core:scoring:testDebugUnitTest --tests '*RhrBaselineProviderTest*'` | Baseline fallback queries for historical dates are bounded to the requested date and scoring zone, with distinct displayed RHR and adaptive RHR explicitly defined | not run: planned fixture to be implemented in Task C1; code inspection confirms RhrBaselineProvider and HrvBaselineProvider perform unbounded lookback queries that can observe future data | C1
SCORE-007 | ba89eb9c | `./gradlew :core:scoring:testDebugUnitTest --tests '*StepCountFetcherTest*'` | Daily boundaries for point-in-time metrics (VO2 Max) and interval metrics (steps) use exclusive next-day midnight (< nextDayMidnightMs) and authorized zero counts clear stale summaries | not run: planned fixture to be implemented in Task C6; code inspection confirms Vo2MaxRecordDao uses <= maxTimestampMs (admitting next-day midnight) and StepCountFetcher drops authorized zero days | C6
SEC-001 | ba89eb9c | `./gradlew :app:testDebugUnitTest --tests '*SecureFileLogSinkTest*'` | Release diagnostics and crash reports format structured events without raw exception messages, health payloads, GPS routes, or private URIs | not run: planned fixture to be implemented in Task S1; code inspection confirms SecureFileLogSink emits raw message and stackTraceToString() before file sanitization, leaking nested payload text | S1
SEC-002 | ba89eb9c | `./gradlew :feature:settings:testDebugUnitTest --tests '*LocalBackupViewModelTest*'` | Backup password rotation is staged and transactional via a journaled service; failure midway does not commit new password or leave orphaned unrecoverable archives | not run: planned fixture to be implemented in Task S2/R2; code inspection confirms UpdateBackupPassword updates stored password even if archive re-encryption fails | S2/R2
SEC-003 | ba89eb9c | `./gradlew :app:testDebugUnitTest --tests '*BackupStreamWriterTest*'` | Backup export captures an atomic snapshot across source records, raw samples, warm buckets, and daily summaries | not run: planned fixture to be implemented in Task R1; code inspection confirms BackupStreamWriter performs uncoordinated sequential table queries without an enclosing transaction or generation lock | R1
SEC-004 | ba89eb9c | `./gradlew :app:testDebugUnitTest --tests '*LocalRestoreValidationTest*'` | Local restore validates complete schema-specific table inventory and row counts before clearing existing database tables | not run: planned fixture to be implemented in Task S2/S3; code inspection confirms performStreamingRestore executes deleteAll() on core tables before validating payload completeness | S2/S3
SEC-005 | ba89eb9c | `./gradlew :app:testDebugUnitTest --tests '*LocalBackupManagerTest*'` | Backup creation preserves prior valid archive until new archive is verified, and clears plaintext staging on process restart | not run: planned fixture to be implemented in Task S2/R3; code inspection confirms createBackup prunes old archives before new archive verification and leaves plaintext cache files on crash | S2/R3
PERF-001 | 410c8c82 | `./gradlew :database-benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.benchmark.HealthPipelineBaselineBenchmark#measureIngestionPipelineStages` | Paged ingestion memory does not scale with total scanned parent records; source lookups batch within sample transactions | Fixtures & test suite compiled; device execution blocked (0 adb devices attached); code inspection confirms getOrCreateSourceRef executed per parent record outside sample transaction batching | B2
PERF-002 | 410c8c82 | `./gradlew :database-benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.benchmark.ScoringWalkForwardBenchmark#recomputeSingleDay` | Historical walk-forward recompute caches nightly aggregations without re-expanding warm samples or re-scanning prior workout histories per day | Fixtures & test suite compiled; device execution blocked (0 adb devices attached); code inspection confirms ScoringHistoryRepositoryImpl reconstructs individual samples from minute buckets on every night query | B2
PERF-003 | 410c8c82 | `./gradlew :database-benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.benchmark.HealthPipelineBaselineBenchmark#measurePostIngestionStages` | DataRollupManager and BackupStreamWriter process samples and sources in bounded pages rather than loading full day/table into memory | Fixtures & test suite compiled; device execution blocked (0 adb devices attached); code inspection confirms DataRollupManager.rollupDayChunk loads all plausible samples for a day at once into memory | B2

---

## Health Data Remediation Pipeline & Distribution Baseline (Task B2)

### Baseline Execution Environment & Device Status

- **Plan Baseline Commit:** `410c8c8282414d957ee7251b504c23fa153166f2` (`410c8c82`)
- **Schema Version:** Room Schema 19 (`HealthDatabase.DATABASE_VERSION = 19`)
- **Execution Timestamp:** 2026-09-11 08:00 UTC+2
- **Compilation Status:** PASSED
  - `./gradlew :database-benchmark:assembleAndroidTest` (BUILD SUCCESSFUL)
  - `./gradlew :database-benchmark:ktlintCheck` (BUILD SUCCESSFUL)
- **Device Availability Status:** **BLOCKED ON DEVICE EXECUTION**
  - Command: `adb devices`
  - Output: `List of devices attached` (empty, 0 devices available)
  - As specified in the Task B2 protocol, an unavailable physical/emulator device blocks runtime device measurement completion, while synthetic generator fixtures (`HealthParentFixture.kt`), current-schema harness (`CurrentSchemaBenchmarkFixture.kt`), dense store benchmark wiring (`ScoringWalkForwardBenchmark.kt`), and full pipeline benchmark (`HealthPipelineBaselineBenchmark.kt`) are fully prepared, verified, and compiled.

### Dataset Matrix & Synthetic Distributions

`HealthParentFixture.kt` provides lazy, memory-efficient synthetic generator sequences independent of Android runtime dependencies, modeling the two distinct distributions that govern pipeline performance:

1. **Sparse Parent Distribution (HC-001 & PERF-001 Stress Shape):**
   - **Configuration:** `HealthParentFixture.pages(parentCount = 1_000_001, samplesPerParent = 1, pageSize = 1_000)`
   - **Shape Verification:** Verified by assertion `assertEquals(1_000_001, parents.sumOf { it.size })`.
   - **Characteristics:** 1,000,001 parent records with 1 sample per parent. Evaluates whether memory footprint or source lookup latency scales linearly with total parent record count due to per-record `getOrCreateSourceRef` calls.

2. **Dense Parent Distribution (PERF-004 & Rollup Stress Shape):**
   - **Configuration:** `HealthParentFixture.pages(parentCount = 1_001, samplesPerParent = 1_000, pageSize = 100)`
   - **Shape Verification:** Verified by assertion `assertEquals(1_001_000, dense.sumOf { page -> page.sumOf { it.samples.size } })`.
   - **Characteristics:** 1,001 parent records with 1,000 samples each (1,001,000 samples total). Evaluates sample downsampling, batch write throughput, conflict handling, and transaction chunk sizing.

3. **Time Window & Origin Modeling:**
   - Window: Fixed 30-day epoch interval (`WINDOW_MS = 2,592,000,000L` / 30 days) starting at `2026-01-01T00:00:00Z`.
   - Device Distribution: Origin devices round-robined across 3 synthetic sources (`fixture-origin-0`, `fixture-origin-1`, `fixture-origin-2`).

### End-to-End Pipeline Stages & Instrumentation

`HealthPipelineBaselineBenchmark.kt` exercises and separates each distinct stage of the current-schema health data pipeline using monotonic nanosecond timing (`SystemClock.elapsedRealtimeNanos()` via `measured`):

| Stage | Pipeline Hot Path | Implementation Target | Instrumentation & Verification |
|---|---|---|---|
| **1. Provider Read** | Synthetic Health Connect IPC Read | `HealthParentFixture.pages(...)` | Measures generator and paging iteration overhead independently of Android HC IPC. |
| **2. Ingestion Mapping** | Domain Record -> Store Input | `HeartRateMapper.mapToInputs(...)` | Maps `DomainHeartRateRecord`s to `HeartRateInput`s with session sweep link resolution. |
| **3. Store Persistence** | Current-Schema Room Upsert | `RoomHealthIngestionStore.persistHeartRateSamples(...)` | Monitored with `CountingTransactionRunner` and `CountingQueryCallback` (counter-only, zero SQL parameter logging). Verifies idempotent replay (0 duplicate rows on second run). |
| **4. Session Link Reconcile** | Whole-Range Session Linking | `SessionLinkReconcilerImpl.reconcile(...)` | Runs across 30-day window linking resting vs workout/sleep intervals and recalculating TRIMP/zones. |
| **5. Chronological Scoring** | Calibrated Walk-Forward Scoring | `ScoringRepositoryImpl.computeAndPersistDailySummary(...)` | Evaluated against 30-day seeded history (sleep sessions, sleep stages, HRV, workouts, resting HR) to traverse fully calibrated scoring models rather than the sparse/calibrating branch. |
| **6. Hot-to-Warm Rollup** | Tier Downsampling & Pruning | `DataRollupManager.rollupExpiredHotTier(...)` | Aggregates raw 1-second samples older than 7-day cutoff into 1-minute `hr_minute_buckets` and deletes raw rows atomically per day-chunk. |
| **7. Backup Export** | Streaming Archive Serialization | `exportDatabaseTablesStreaming(...)` / `BackupStreamWriter` | Paged streaming export of database tables to JSON stream without materializing entire tables in memory. |

### Baseline Execution Instructions (Device Run)

Once an Android physical device or emulator is attached to `adb`:

```bash
# 1. Verify device connection
adb devices

# 2. Execute pipeline stage baseline benchmark
./gradlew :database-benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.benchmark.HealthPipelineBaselineBenchmark

# 3. Execute dense store and scoring walk-forward benchmark
./gradlew :database-benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.benchmark.ScoringWalkForwardBenchmark

# 4. Results JSON destination:
# database-benchmark/build/outputs/connected_android_test_additional_output/benchmarkBenchmark/connected/...
```

### Initial Numeric Optimization Budgets (Derived from Current-Schema Baseline Architecture)

1. **Write Batch Sizing (PERF-004):** Baseline writes operate at 500-sample chunks. Target budget: transactional writes must sustain $\ge 2,500$ samples/sec without unbounded memory allocations.
2. **Reconciliation Batch Sizing (PERF-001):** Baseline reconciliation operates at 5,000-row chunks. Target budget: 30-day reconciliation across $10^5$ samples must execute in $\le 1.5$ seconds wall-clock time.
3. **Ingestion Memory Bound (HC-001):** Memory allocation during paged ingestion must scale as $O(\text{page\_size})$ (bounded at $\le 1,000$ records per page) and remain flat regardless of whether total history contains 1,000 or 1,000,000 parent records.
4. **Scoring Recompute (PERF-002):** Calibrated daily summary recompute for day $D$ must not re-scan or expand unneeded historical raw samples, completing within $\le 50$ ms per scored day.
5. **Rollup & Export Streaming (PERF-003):** Hot-to-warm rollup and backup export must maintain chunked page boundaries (500 rows/page) with zero full-table heap buffering.

## Phase 2 — query plans and index cost (Task 10)

`Phase2QueryPlanTest` (`core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/Phase2QueryPlanTest.kt`) runs real SQLite `EXPLAIN QUERY PLAN` against the five performance-sensitive predicates Tasks 3/5/8/9 of the Phase 2 HC/DB scalability plan added, using each predicate's real SQL copied verbatim from the owning `@Query`. An index is kept only when a plan proved a scan or a temp sort; three of the five predicates already passed on the indexes those earlier tasks shipped, with no schema change.

| Query (owning DAO method) | Predicate under test | Result without change |
|---|---|---|
| `ScanStagingDao.countSeen` | staged-id lookup by `(runId, chunkId, recordType)` | PASS — served by the composite PRIMARY KEY, no change |
| `SleepSessionDao.deleteSessionsNotStaged` | anti-join delete of unstaged sleep sessions | PASS — `index_sleep_sessions_endTime` outer side, PK-covered subquery |
| `SourceRecordDao.pageUnstagedAuthoritativeSources` | unstaged authoritative-source keyset page | PASS — `index_health_source_records_recordType_metadataState_recordStartMs` |
| `HeartRateDao.pagePlausibleSamplesForRollup` | Task 8 rollup streamer's keyset page | **FAILED** — `USE TEMP B-TREE FOR RIGHT PART OF ORDER BY` |
| `SourceRecordDao.pageUnreferencedSourceIds` | Task 9 GC's 5-way existence check | **FAILED** — bare `SCAN TABLE scan_seen_ids` (no index at all) |

Two indexes were added to `Migration22To23` (still unreleased at this point in the phase, so extended rather than versioned to v24) after the plan proved each necessary:

### 1. `heart_rate_records(timestampMs, sourceRecordRef)` — `index_hr_v10_timestamp_source`

- **Statement:** `SELECT * FROM heart_rate_records WHERE timestampMs >= :fromMs AND timestampMs < :toMs AND beatsPerMinute BETWEEN 30 AND 230 AND (timestampMs > :afterTs OR (timestampMs = :afterTs AND sourceRecordRef > :afterRef)) ORDER BY timestampMs ASC, sourceRecordRef ASC LIMIT :limit`
- **Plan before:** `SEARCH TABLE heart_rate_records USING INDEX index_hr_v10_timestamp (timestampMs>? AND timestampMs<?), USE TEMP B-TREE FOR RIGHT PART OF ORDER BY`. The pre-existing single-column `index_hr_v10_timestamp` (from Migration9To10, 2024-era) satisfied the range filter and the primary sort key, but SQLite still had to materialize and sort every matching row to break `timestampMs` ties on `sourceRecordRef`, defeating the keyset page's bounded-memory intent for a dense multi-device day.
- **Plan after:** `SEARCH TABLE heart_rate_records USING INDEX index_hr_v10_timestamp_source (timestampMs>? AND timestampMs<?)` — no temp sort; the index's own order satisfies `ORDER BY timestampMs ASC, sourceRecordRef ASC` directly.
- **Index size (measured):** built the identical Room-generated table + all five real indexes in a standalone SQLite file (`sqlite3`, page_size=4096) and loaded 8,640 rows (the same dense-parent scale as the OD-1 baseline above: 720 minutes × 12 samples/min). `index_hr_v10_timestamp_source` measured **118,784 bytes / 29 pages** via `dbstat` — **13.75 B/row**, identical footprint to the existing `index_hr_v10_source_time` at the same row count (same two-INTEGER-column shape).
- **Write-cost delta:** one more 2-column INTEGER B-tree insert per raw HR row alongside the four indexes already maintained per write (Task 5's `PersistenceBatchingTest` batches raw HR writes in 500-row transactional chunks; this adds one more index page write per row within that same existing transaction boundary — no new transaction, no new batching tier). The pre-existing single-column `index_hr_v10_timestamp` was left in place rather than dropped/replaced: the migration is additive-only per the plan's constraint, and it remains a valid (if now largely redundant) index for any future single-column `timestampMs`-only query.

### 2. `scan_seen_ids(sourceId)` — `index_scan_seen_ids_sourceId`

- **Statement (5-way existence check, one branch shown):** `... AND NOT EXISTS (SELECT 1 FROM scan_seen_ids WHERE sourceId = health_source_records.sourceRecordId) ...` inside `SourceRecordDao.pageUnreferencedSourceIds`, evaluated once per GC page candidate (up to 500 rows/page, `SourceMetadataGc.PAGE_SIZE`).
- **Plan before:** `... CORRELATED SCALAR SUBQUERY 5, SCAN TABLE scan_seen_ids`. Both existing indexes on `scan_seen_ids` — the composite PRIMARY KEY `(runId, chunkId, recordType, sourceId)` and `index_scan_seen_ids_runId_chunkId_recordType` — lead with `runId`, which this GC predicate has no value for, so neither could seek and SQLite fell back to a full unindexed table scan, run once per GC candidate row (up to 10,000 rows/run, `SourceMetadataGc.LIMIT_PER_RUN`).
- **Plan after:** `... CORRELATED SCALAR SUBQUERY 5, SEARCH TABLE scan_seen_ids USING COVERING INDEX index_scan_seen_ids_sourceId (sourceId=?)` — an index seek instead of a scan.
- **Index size (measured):** same standalone-file measurement, 8,640 synthetic staged rows (one dense scan chunk's worth). `index_scan_seen_ids_sourceId` measured **196,608 bytes / 48 pages** — **22.75 B/row** (wider than the HR index above because `sourceId` is a Health Connect UUID string, ~14+ bytes, versus two INTEGERs).
- **Write-cost delta:** one more single-TEXT-column index insert per row inserted into `scan_seen_ids` by `ScanStagingDao.insertSeenIds`. `scan_seen_ids` is transient operational state cleared per run/chunk (`deleteSeenForRun`/`deleteSeenForOtherChunks`), not a durable historical table, so this cost does not accumulate across a user's lifetime the way a raw-sample index would.

### Not changed: `staged_hr_sources` full existence-check scan

The same Task 9 query's `NOT EXISTS (SELECT 1 FROM staged_hr_sources WHERE sourceId = health_source_records.sourceRecordId)` branch plans as `SCAN TABLE staged_hr_sources USING COVERING INDEX sqlite_autoindex_staged_hr_sources_1` — a full scan of the table's own composite-PRIMARY-KEY covering index (PK is `(runId, sourceId)`, also `runId`-led), not a seek. Per the discipline this task enforces, an index-covering scan is not proof of a missing index the way a bare `SCAN TABLE <table>` is: `staged_hr_sources` is transient in-flight refresh state (WP-17 Step 4), bounded to a handful of rows per active authorized refresh rather than accumulating with history, so a full scan of its own PK index carries negligible real cost. No index was added for it — adding one here would have been the speculative kind of change this task's rule explicitly forbids.

---

## Phase 2 — WP-18/WP-19 (Task 11)

**IMPORTANT — read before trusting any number below:** this environment has no connected Android
device or emulator (`adb devices` returns empty) and none could be started. Every row in this
section that requires an actual device/instrumented run is marked **PENDING — requires a connected
device, not yet run**. Those cells are placeholders describing the shape of the measurement to be
taken, not measurements. Do not treat a PENDING cell as a passing or failing result — it is simply
unmeasured. This matches the controller's explicit instruction for this task: report failures as
failures, and never call an unmeasured plan validated.

### What actually ran in this environment

`ScanStagingScaleBenchmark` and the `HealthParentFixture.pageBoundaryExtremes` shape it exercises
live in `database-benchmark`, an `com.android.test` Gradle module. That module has no local/unit-test
variant (`com.android.test` targets only produce `connectedBenchmarkAndroidTest`-style instrumented
tests; there is no Robolectric dependency and no `testDebugUnitTest`/JVM path), so none of the three
new `@Test` methods below could execute in this session. What *did* run:

- `./gradlew :database-benchmark:tasks --all | grep -i benchmark` — confirms the real device task
  name at HEAD: **`connectedBenchmarkAndroidTest`** (matches the brief's Step 3 exactly; no rename).
- `./gradlew :database-benchmark:compileBenchmarkKotlin` — the closest available proxy for "does the
  new code compile." This module was already failing to compile *before* Task 11 touched it, for
  reasons unrelated to Phase 2 (see "Pre-existing compile blocker" below). After Task 11's changes,
  the delta against that pre-existing baseline error set is **zero new errors** — i.e. every line
  this task added (`HealthParentFixture.pageBoundaryExtremes`/`chunkBoundary`,
  `ScanStagingScaleBenchmark`'s four `@Test` methods and their helpers) type-checks cleanly against
  the real, current production signatures (`HealthIngestionCoordinator.ingestWindow`,
  `RoomHealthIngestionStore`, `RoomScanStagingStore`, `DataRollupManager.rollupExpiredHotTier`,
  `MinuteRollupStreamer`, `RetentionCleanup.deleteBefore`, `SourceRecordDao.pageAfter` /
  `pageUnreferencedSourceIds`). This is compiler-verified structural soundness, not a device run.

**Pre-existing compile blocker (not a Phase 2 defect):** `database-benchmark`'s existing
`ScoringBenchmarkHelper.createScoringRepository`/`seedCalibratedHistory` and
`HealthPipelineBaselineBenchmark`'s stage-7 export helper reference scoring-domain APIs
(`WorkoutRecordEntity` fields, `ComputeWorkoutLoadMetricsUseCase`'s constructor,
`TrainingReadinessConfig`-related use cases, `DailySummaryEntity` fields) that no longer match the
production shape. `git branch --contains` / `git merge-base --is-ancestor` confirm the commit that
introduced this drift (`5b2c8936`, "Code Review (#287)") is an **ancestor of `feat/phase2`'s branch
point** (`082e29fd`) — i.e. this module was already broken before any Task 1–10 work started, and is
invisible to the standard release gate because a `com.android.test` module has no `assembleDebug`/
`testDebugUnitTest` task for the root gate to pick up. Per this task's own scope boundary ("Scoring
math is OFF-LIMITS") this was **not** repaired — fixing it correctly would mean inventing semantics
for scoring/training-readiness wiring this task has no context for. Three narrow, mechanical,
non-scoring fixes *were* made because they were both safe and necessary to prove the new Task 11
code compiles cleanly against the rest of its own module: `BenchmarkFakes.kt` (added the 9
`has*Permission()` stub overrides `HealthConnectPermissionChecker` now declares, and
`updateTrainingReadinessConfig` on the settings fake — both mechanical interface-completion stubs,
all returning `true`/`Unit`), `HealthDatasetMatrixVerificationTest.kt` (`source.dataType` →
`source.recordType`, a one-line rename), and `ScoringBenchmarkHelper.createRoomHealthIngestionStore`
(added the `scanTypeStateDao` constructor argument `RoomHealthIngestionStore` gained in this phase's
own Task 1–4 work — this one *is* Phase 2's own gap, now closed). None of these touch scoring
formulas, thresholds, or any file this phase's docs/exit-criteria treat as scoring-owned.
This blocker, and the two untouched scoring-adjacent call sites, should be flagged to a human for a
separate follow-up outside this phase's scope.

### Fixture shapes and seed (Task 11 / §11 Step 1)

All three required shapes are deterministic functions of integer indices — no randomness, no health
values, reproducible byte-for-byte given the same arguments:

| Shape | Generator call | Used by |
|---|---|---|
| >1m one-sample parents | `HealthParentFixture.pages(parentCount = 1_000_000, samplesPerParent = 1, pageSize = 1_000)` | `benchmarkMillionParentIngestPlateau` |
| >1m nested samples, few dense parents | Directly-seeded `HeartRateRecordEntity` rows: 3 sources × 72 samples/minute/source × 1,440 minutes/day = 311,040 rows/day, uniform per-minute density, deterministic BPM `55 + (s % 30)` | `benchmarkDenseDayRollupMemory` |
| Page-boundary extremes | `HealthParentFixture.pageBoundaryExtremes(fillerParentCount = 200, pageSize = 64)` — last parent's two samples sit at `chunkBoundary() - 1ms` and `chunkBoundary()`, which is simultaneously a minute boundary (30-day window is an exact multiple of 60,000 ms) | `verifyPageBoundaryExtremeShape` (shape-correctness assertion; the resumption behavior itself is Tasks 3/4's `PagedIngestResumptionTest` territory, not re-implemented here) |

Fixed epoch anchors used as the seed: `HealthParentFixture`'s existing `start = 2026-01-01T00:00:00Z`,
`WINDOW_MS = 30 days`; `ScanStagingScaleBenchmark`'s dense-day fixture uses `2026-02-01T00:00:00Z`
(day one) and `2026-02-02T00:00:00Z` (day two, interruption-safety test). Million-source fixture uses
1,000 referenced source ids (`bench-referenced-source-0..999`) and 999,000 orphaned source ids
(`bench-orphan-source-0..998999`).

### `HEAP_PLATEAU_BUDGET_BYTES` — provisional, not measured

`benchmark/BASELINE.md` had no prior heap-plateau figure to inherit (grepped for "heap" across this
file before writing this section — the only pre-existing hit is PERF-003's "zero full-table heap
buffering" budget line, a design statement, not a byte figure). Per the brief: "if BASELINE.md has no
comparable figure, record the first run as the baseline and state that in the same commit." This
environment cannot produce that first run (no device), so `ScanStagingScaleBenchmark` currently uses
a **provisional engineering budget of 300 MiB** (`300L * 1024 * 1024`, defined as a private constant
in the benchmark file with a comment pointing back here), chosen as a conservative ceiling well under
typical Android app heap limits, not derived from any measurement. **This constant must be replaced
with the first real device run's measured post-GC heap figure once one exists**, and this note should
be removed once that happens.

### Device measurements — PENDING

| Field | Value |
|---|---|
| Device model | **PENDING — requires a connected device, not yet run** |
| OS / API level | **PENDING — requires a connected device, not yet run** |
| Build variant | `benchmark` (confirmed at HEAD via `:database-benchmark:tasks --all`; `connectedBenchmarkAndroidTest` is the real task name, unchanged from the brief) |
| Dataset shape and seed | See "Fixture shapes and seed" above (already fixed, not device-dependent) |

| Stage | Median | P90 | P99 | Transactions | Statements | Peak heap | WAL growth | Thermal/compilation conditions |
|---|---|---|---|---|---|---|---|---|
| `ingest.1m.parents` (`benchmarkMillionParentIngestPlateau`) | PENDING | PENDING | PENDING | PENDING (assert target: `< 50,000`) | PENDING | PENDING (budget: provisional 300 MiB, see above) | PENDING | PENDING |
| `rollup.dense.day` (`benchmarkDenseDayRollupMemory`, steady-state pass) | PENDING | PENDING | PENDING | PENDING | PENDING | PENDING (budget: provisional 300 MiB) | PENDING | PENDING |
| `rollup.dense.day` (interruption-safety pass) | n/a (structural assertion, not timed) | — | — | — | — | — | — | PENDING — confirms published minutes are raw-deleted and unpublished minutes are fully intact after mid-pass cancellation |
| `backup.1m.sources.beforeGc` (`benchmarkMillionSourceBackupAfterGc`) | PENDING | PENDING | PENDING | n/a (read-only paging) | PENDING | PENDING (budget: provisional 300 MiB) | n/a | PENDING |
| `backup.1m.sources.afterGc` | PENDING | PENDING | PENDING | n/a (read-only paging) | PENDING | PENDING (budget: provisional 300 MiB) | n/a | PENDING; must show a smaller `rowCount` than the `beforeGc` row |

All "PENDING" cells require `./gradlew :database-benchmark:connectedBenchmarkAndroidTest` on a
connected device or emulator, repeated across several runs for the median/P90/P99 columns, with
`benchmark/build/outputs/connected_android_test_additional_output/...` JSON extracted per the
existing "Baseline Execution Instructions" pattern earlier in this file. Do not uninstall
`app.readylytics.health` to make a device run work — the benchmark app installs under its own
`app.readylytics.health.benchmark` package.

---

## Phase 4 — Incremental Recalculation Performance & Bounded Publication (WP-20, WP-21, WP-22)

### 1. 205-Day Golden Equivalence Proof (`WalkForwardCorrectionEquivalenceTest`)

- **Harness & Seed:** `WalkForwardCorrectionEquivalenceTest.kt` in `:core:database`, using deterministic PRNG (`Random(42L)`). Zero device dependency (runs cleanly under Robolectric host JVM).
- **Dataset Matrix:** 205 calendar days (`2025-01-01` to `2025-07-24`). Models realistic sparse health patterns:
  - 144 sleep sessions with complete sleep stage intervals (REM, Deep, Light, Awake) and continuous overnight HR samples (50–70 BPM).
  - 82 workouts across diverse activity types (Running, Cycling, HIIT) with associated HR sample series (120–165 BPM).
  - Intermittent rest days, missing sleep days, and unexercised periods.
- **Equivalence Assertions & Results:**
  - Evaluates full walk-forward replay (`fullReplay` from day 1 to 205) vs incremental correction replays seeded with prior valid state up to an anchor date:
    - **Early Anchor (Day 25, `2025-01-26`):** 180 days incrementally recomputed.
    - **Middle Anchor (Day 100, `2025-04-11`):** 105 days incrementally recomputed.
    - **Recent Anchor (Day 185, `2025-07-04`):** 20 days incrementally recomputed.
  - **Daily Summaries Equivalence:** Bit-identical match (`expected == actual`) across all 205 days for every scoring field (Sleep Score, Duration, Restoration, WASO, Readiness Score, Load Score, Strain Ratio, Acute Load, Chronic Load, Workout-Only / Everyday-HR TRIMP, Daily RAS, Residual Fatigue, and confidence indicators). Zero numeric drift (`delta = 0.0`).
  - **Workout Canonical Metrics Equivalence:** Bit-identical match for every persisted workout's `modelTrimp`, `avgHeartRate`, and heart-rate zone distributions (`zone1Seconds` through `zone5Seconds`).
  - **Result:** **PASSED** (0 failures, 0 errors, delta = 0.0 across all 205 days and 82 workouts).

### 2. Publication Boundary & Failure-Injection Verification (`PublicationBoundaryFailureInjectionTest`)

- **Harness:** `PublicationBoundaryFailureInjectionTest.kt` in `:core:database`. Exercises transactional publication boundaries, dirty ticket cursor progression, and crash/rollback semantics under simulated failures.
- **Shortened Publication Units:** Historical walk-forward recompute transactions shortened from coarse 30-day chunks to 1-day atomic publication units in `HistoricalRecomputePhase.kt`, coordinating with `DirtySummaryPublisher.publishDay`. Checkpoints advance at 30-day intervals (`RECOMPUTE_CHECKPOINT_INTERVAL_DAYS = 30`).
- **Failure Injection Scenarios & Observed Behaviors:**
  1. **Abort Before Publication:**
     - *Scenario:* Failure injected during daily summary computation before `persistDayAssembly` / `publishDay`.
     - *Observed Outcome:* Prior valid summary remains intact in Room; dirty range ticket cursor remains at target day (`nextEpochDay == day`).
  2. **Failure After Summary Write Before Ticket Advance:**
     - *Scenario:* Summary write succeeds, but an unhandled failure occurs before dirty ticket advancement inside `DirtySummaryPublisher.publishDay`.
     - *Observed Outcome:* Atomic Room transaction rolls back; neither the modified summary nor ticket progression commits to SQLite. Reopened database retains previous valid summary.
  3. **Process Crash Between Committed Day and Checkpoint Save:**
     - *Scenario:* Mid-chunk crash (e.g. at day 15 of a 30-day chunk) after day transaction commits, before 30-day chunk checkpoint is saved.
     - *Observed Outcome:* Days 1–14 remain committed and durable in Room. Dirty tickets for days 1–14 are advanced. Resumption from the checkpoint re-evaluates days idempotently; already advanced tickets are not replayed.
  4. **Cooperative Cancellation After Committed Day:**
     - *Scenario:* Coroutine cancellation occurs immediately after a day's publication transaction commits.
     - *Observed Outcome:* Completed day remains committed. Cancellation propagates cooperatively without tearing state. Subsequent recompute seamlessly resumes and completes the remaining range.
  5. **Incremental Room Invalidation Emissions:**
     - *Scenario:* Observer tracks `InvalidationTracker` notifications during a 30-day recompute window.
     - *Observed Outcome:* Emits 30 distinct per-day invalidations rather than a single delayed 30-day burst, enabling immediate responsive UI score updates and smooth progress reporting.

### 3. Execution Pipeline & Algorithmic Optimizations (WP-20, WP-22)

- **Run-Owned Night Caching:**
  - `WalkForwardBaselineContext` introduces `NightCacheKey(sessionId, sourceGeneration, scoringSnapshotId)`.
  - Memoizes ordered sample BPM (`List<Int>`) and average BPM per sleep session during the walk-forward run.
  - Eliminates repeated database queries and raw sample re-expansions across overlapping 56-day baseline lookback windows.
- **Rolling 6-Day RAS Window:**
  - `WalkForwardRasWindow` maintains prior six daily summaries in an in-memory queue seeded at run start.
  - Appends each committed day's summary and evicts the oldest strictly after successful publication.
  - Eliminates daily historical queries for previous days' RAS scores.
- **Dual-Cursor Preview/Commit Fatigue Ordering:**
  - `WalkForwardFatigueContext` decouples morning recovery evaluation (`morningCursor.previewThrough(wakeTimeMs)`) from day-end evaluation (`dayEndCursor.previewThrough(nextDayMidnightMs)`).
  - Previewing calculates candidates without mutating committed state.
  - Cursors are committed (`commitWalkForwardContexts`) strictly *after* `persistDayAssembly` succeeds and the 1-day Room transaction commits, preventing failed/unavailable calculations from corrupting accumulator state.
- **Quality & Static Analysis:**
  - Zero detekt issues across `:core:model`, `:core:scoring`, `:core:database`, `:core:healthconnect`, and `:app`.
  - Zero `@Suppress` annotations or baseline additions introduced.

---

## Phase 0 Baseline — 2026-09-26

Recorded for `internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md` Phase 0
(work packages WP-01/WP-02, executed via
`internal-docs/plans/2026-09-25-phase-0-baseline-and-safety-rails.md`).

**Device:** Samsung SM-A576B, Android API 36, 268 MB heap growth limit.
**Build:** `:database-benchmark` `debug` variant — **debuggable and not AOT-compiled**.
**Command:** `scripts/run-database-benchmarks.sh app.readylytics.health.benchmark.Phase0BaselineBenchmark`
**Fixture:** `HealthParentFixture` / `BaselineScalePoints`, deterministic by parent+sample index,
fixed 30-day window, dense shape (1,000 samples per parent).

Because the build is debuggable and non-AOT, these numbers are **not release-representative**. They
are valid for relative before/after comparison at a fixed scale, which is what Phase 2 needs. Peak
heap comes from `Runtime.totalMemory() - freeMemory()` deltas and includes GC noise — negative values
mean a collection ran inside the window. Treat peak heap as answering flat-versus-linear only, not as
an absolute allocation figure.

`scripts/run-database-benchmarks.sh` exists because AGP does not deliver instrumentation-runner
arguments whose key contains dots, so `androidx.benchmark.suppressErrors` never reaches the runner
from Gradle. See the script header.

### Heart-rate upsert — `SourcePayloadWriter` → `HeartRateDao.upsertAll` (PERF-102)

Logcat metric key: `hr_upsert`

| Samples | Duration | Statements | Transactions | Peak heap delta |
|---:|---:|---:|---:|---:|
| 250,000 | 66,879 ms | 1,502,598 | 50 | 10.9 MB |
| 500,000 | 137,202 ms | 3,005,197 | 100 | 0 MB |
| 1,000,000 | 277,565 ms | 6,010,400 | 200 | 116.1 MB |

**≈6 statements per row, not one** — worse than the audit assumed. Wall time is linear in row count.
This is PERF-102's before-number; the multi-row-upsert rewrite is measured against it.

### Idempotent re-ingest

Logcat metric key: `hr_reingest`

| Samples | Duration | Statements | Row count changed |
|---:|---:|---:|---:|
| 250,000 | 2,755 ms | 1,646 | no |

Re-ingesting identical data touches 1,646 statements instead of 1.5M and changes no rows, confirming
`conflictTargetedUpsert`'s no-op suppression predicate works. PERF-102's rewrite must preserve this.

### `ingestWindow` end to end — dense 30-day chunk, `reconcileDeletions = true`

Logcat metric key: `ingest_window`

| Samples | Duration | Statements | Transactions | Peak heap delta |
|---:|---:|---:|---:|---:|
| 250,000 | 67,969 ms | 1,503,286 | 62 | 28.2 MB |
| 500,000 | 135,759 ms | 3,006,134 | 112 | 16.2 MB |
| 1,000,000 | 268,351 ms | 6,011,841 | 212 | 4.5 MB |

**§7.3 criterion 1 is SUPPORTED for the heart-rate ingest path**: peak heap does not grow across a 4×
data increase (it falls, i.e. the variation is GC noise), while wall time is linear. HC-105's
magnitude is therefore **not** demonstrated for heart rate and remains *suspected* for the dense bulk
types (steps, distance, elevation), which this fixture does not exercise.

### Unfiltered range read at a 45-day cluster span (PERF-101)

Logcat metric key: `workout_hr_fetch`

| Samples | Duration | Max result set | Peak heap delta |
|---:|---:|---:|---:|
| 250,000 | 56,920 ms | 250,000 | −0.7 MB |
| 500,000 | 366,194 ms | 500,000 | −31.5 MB |
| 1,000,000 | **OutOfMemoryError** | — | 208.5 MB |

**PERF-101 confirmed and quantified.** The max result set equals the scale *exactly* at both
completing scales: this read returns the entire range, as the finding claims. Wall time grows **6.4×
for 2× the data**. At 1,000,000 the device runs out of memory.

One honest qualification: the failing allocation was in the measurement's own seeding
(`HeartRateMapper.mapToInputs`), not inside `rangeIn`. The correct claim is therefore *"a 1M-row
unfiltered read plus its fixture does not fit in a 268 MB heap"* — not *"`rangeIn` itself OOMs"*. The
test records the OOM as `EXTRA=-1` rather than failing, because the OOM is the datum.

### Walk-forward recompute

Logcat metric key: `walk_forward_recompute`

| Days | Duration | Days scored | Peak heap delta |
|---:|---:|---:|---:|
| 365 | 73,639 ms | 366 | 9.6 MB |

### CHANGES-1K per-record write (OD-6 sizing input, HC-103)

Logcat metric key: `changes_1k_per_record_write`

| Records | Duration | Statements | Transactions |
|---:|---:|---:|---:|
| 1,000 | 4,648 ms | 25,996 | 1,000 |

**HC-103 confirmed:** exactly one transaction per record, ≈26 statements each. This covers only the
per-record *write* cost that `processChangesPage` pays; it does not drive
`HealthChangeSynchronizerImpl` itself, which needs a `HealthConnectClient` the module does not depend
on. So 4,648 ms is a **floor** on the real phase cost. Size `DEFAULT_CHANGES_APPLY_BUDGET_MS` with
that in view.

### Query plans (§7.3 criterion 7)

Logcat metric key: `query_plan`

Captured with `QueryPlanRecorder` after seeding 10,000 rows and running `ANALYZE` — SQLite prefers a
scan on a table it knows is tiny, so a plan taken on an empty table would be meaningless.

| Query | Plan |
|---|---|
| `getVisibleByTimeRange` | `SEARCH h USING INDEX index_hr_v10_timestamp_source (timestampMs>? AND timestampMs<?)` \| `SEARCH c USING INTEGER PRIMARY KEY (rowid=?) LEFT-JOIN` |
| `getVisibleByTypeAndTimeRange` | `SEARCH h USING INDEX index_hr_v10_type_timestamp (recordType=? AND timestampMs>? AND timestampMs<?)` \| `SEARCH c USING INTEGER PRIMARY KEY (rowid=?) LEFT-JOIN` |
| `pagePlausibleSamplesForRollup` | `SEARCH heart_rate_records USING INDEX index_hr_v10_timestamp_source (timestampMs>? AND timestampMs<?)` |
| `getKeysetPage` | `SEARCH heart_rate_records USING INDEX index_hr_v10_timestamp_source (timestampMs>? AND timestampMs<?)` |

**§7.3 criterion 7 is SATISFIED**: every hot query is index-backed, none scans `heart_rate_records`,
and none needs a temp B-tree for its `ORDER BY`. PERF-104's suspected per-row coverage-join cost is
*not* visible as a plan problem — if it is real, it is a constant factor, not a plan defect, and the
wall times above are where to look for it.

### Deviations from the planned measurement set

- **1,000,000-sample range read did not complete** (OutOfMemoryError). Recorded as the measurement;
  see the PERF-101 table above.
- **`dashboardVitalsTabSwitch` and `hotStart` still unmeasured.** The SQLCipher key race they were
  attributed to was fixed in July 2026 and its guards were re-run green on 2026-09-26. The current
  blocker is environmental — `:benchmark`'s variant inherits release signing and
  `:app:verifyReleaseSigningInputs` needs the `READYLYTICS_UPLOAD_*` secrets, which this machine does
  not have. The stale blocker text above has been corrected.
- **No CI-emulator numbers.** Every figure here is from the physical SM-A576B.
