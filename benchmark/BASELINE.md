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

**STATUS: PARTIALLY RECORDED** — `vitalsFling`, `vitalsChartPanAndZoom`, `coldStart`, and `warmStart` frame-timing & startup numbers extracted from physical device benchmark run (Samsung SM-A576B, Android API 36). `dashboardVitalsTabSwitch` and `hotStart` remain pending due to SQLCipher key race on tab navigation during clean install runs.

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
| dashboardVitalsTabSwitch | Pending (blocked by SQLCipher race, see above) | Pending (blocked by SQLCipher race, see above) | Pending (blocked by SQLCipher race, see above) |

## Startup (StartupBenchmark, same run)

| Mode | timeToInitialDisplayMs P50 |
|---|---|
| coldStart | 520.41 ms |
| warmStart | 184.68 ms |
| hotStart | Pending (failed intermittently during benchmark run, see note above) |

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
DB-002 | ba89eb9c | `./gradlew :core:database:testDebugUnitTest --tests '*DataRollupManagerTest*'` | Hot/warm tier maintains single authoritative coverage per minute without overlap, dropped samples on sub-minute cutoffs, or double-counting on historical reimport | not run: planned fixture to be implemented in Task T1-T3; code inspection confirms resolveHotTierCutoffMs is not minute-aligned and MinuteBucketDao.upsertBuckets overwrites entire minute without source attribution | T1–T3
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
PERF-001 | ba89eb9c | `./gradlew :core:database:testDebugUnitTest --tests '*DatabaseBenchmarkFixture*'` | Paged ingestion memory does not scale with total scanned parent records; source lookups batch within sample transactions | not run: planned fixture to be implemented in Task B2; code inspection confirms getOrCreateSourceRef executed once per parent record outside sample transaction batching | B2
PERF-002 | ba89eb9c | `./gradlew :core:database:testDebugUnitTest --tests '*ScoringWalkForwardBenchmark*'` | Historical walk-forward recompute caches nightly aggregations without re-expanding warm samples or re-scanning prior workout histories per day | not run: planned fixture to be implemented in Task B2; code inspection confirms ScoringHistoryRepositoryImpl reconstructs individual samples from minute buckets on every night query | B2
PERF-003 | ba89eb9c | `./gradlew :core:database:testDebugUnitTest --tests '*DataRollupBenchmark*'` | DataRollupManager and BackupStreamWriter process samples and sources in bounded pages rather than loading full day/table into memory | not run: planned fixture to be implemented in Task B2; code inspection confirms DataRollupManager.rollupDayChunk loads all plausible samples for a day at once into memory | B2
