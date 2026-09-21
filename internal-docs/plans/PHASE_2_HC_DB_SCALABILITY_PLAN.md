# Phase 2 — Health Connect and Database Scalability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Health Connect ingestion and the hot→warm rollup bounded in memory, batched in transactions, and durably resumable — so a million-parent-record history ingests, reconciles deletions and rolls up without heap growth, per-parent transactions, unsafe SQL binding lists or remote I/O inside a writer transaction.

**Architecture:** Three seams change. (1) Scan identity moves from heap `Set<String>` to an indexed Room staging table `scan_seen_ids` keyed `(runId, chunkId, recordType, sourceId)`; deletion reconciliation becomes a SQL anti-join against that table and refuses to prune from an incomplete scan. (2) Sample persistence groups a whole Health Connect page into one transaction with bulk source resolution, and the transform buffer is capped by sample count rather than by page cardinality. (3) The rollup reads raw samples through keyset pages *outside* the writer transaction, aggregates one complete-minute group at a time, and publishes each group through the existing generation-checked `MinuteCoveragePublisher`. Read retries collapse into one shared budget so nested `retryWithBackoff` layers stop multiplying attempts.

**Tech Stack:** Kotlin, Room 2.x + SQLCipher (`HealthDatabase`, currently v22 → v23 in this phase), Health Connect client 1.1.0, Hilt, kotlinx.coroutines, JUnit4 + Robolectric + `MigrationTestHelper` (unit tests in `core:database`), androidx.benchmark (`:database-benchmark`, `:benchmark`).

**Spec:** `internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md` — section 9 "Phase 2 — Health Connect and Database Scalability", work packages WP-18 and WP-19, findings PERF-001, PERF-003, HC-002, HC-005, HC-006, DB-001, DB-002, SEC-003, SEC-005, migration row "S3: scan staging and indexes" (section 12), and the "HC read/transform", "Room upsert/replay", "Query plans", "Rollup" and "Worker/platform" rows of section 11.

---

## Global Constraints

Copied verbatim from the spec and from `.claude/CLAUDE.md`; every task's requirements implicitly include this section.

- **Scoring math is OFF-LIMITS.** Both sync flows recompute exclusively via `ScoringRepository.computeDailySummary(day)`. This phase refactors data flow/batching/triggers only — never a formula, weight, threshold or unit.
- **Pull-to-refresh = current day only** (`ForegroundSyncController.triggerDailySync()` → `HealthSyncUseCase.sync(windowDays = 1)`). Never widen it to a catch-up window. Settings "Resync Health Connect data" stays the durable `HealthResyncWorker` path.
- **Idempotency is non-negotiable.** Ingestion stays upsert-keyed on the stable HC record `id`. A killed or failed worker leaves prior valid data intact; a retry re-runs the same range idempotently. No blanket `deleteAll()`.
- **Never prune from a partial or unavailable scan** (HC-002). Deletion reconciliation requires a scan explicitly marked complete for that `(runId, chunkId, recordType)`.
- **No Health Connect call inside a Room transaction** (HC-006). No CPU-heavy aggregation inside a long writer transaction (PERF-003).
- **No SQL statement may exceed the verified binding limit.** Assume `SQLITE_MAX_VARIABLE_NUMBER = 999` on the oldest supported API level (minSdk 26); bounded chunk constants stay ≤ 500 as already used in `MinuteCoveragePublisher.MINUTE_KEY_CHUNK` and `SourcePayloadWriter.BATCH_SIZE`/`DELETE_CHUNK_SIZE`.
- **Batch sizes start from what exists and change only from measurements:** 500-row sample writes, 250-row keyed deletes, 5_000-row reconciliation pages.
- **Retention is `RetentionBounds`' job.** Do not re-inline retention→date math.
- **Walk-forward and streaming loops stay cooperative:** `currentCoroutineContext().ensureActive()` + `yield()`; never swallow `CancellationException`.
- **Schema:** additive only, from v22 to v23. Staging starts empty. No physiological-data rewrite for indexes. Old scan checkpoints must replay safely; never promote a provider page token while discarding staging.
- **Strings:** any new user-facing string goes in `app/src/main/res/values/strings.xml` and is read with `stringResource(...)`. This phase should add none.
- **File size:** target ≤ 400 lines/file, hard limit 800. `HealthIngestionCoordinator.kt` is already 600 lines — extract rather than grow it.
- **Detekt discipline:** no new detekt issues; fix pre-existing issues in files you touch (boyscout). A `@Suppress` or baseline edit requires explicit human approval before merge.
- **Docs are load-bearing:** every task that touches ingestion, the Room schema, or the rollup updates `internal-docs/DATA_FLOW.md` in the same commit (§1.1–1.4 and §1.4.1). Benchmark numbers go in `benchmark/BASELINE.md` with device/build/dataset metadata and no health values.
- **Pre-commit gate (mandatory, per task):** `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest`; `./gradlew lintRelease` once at the end of the phase.
- **Never uninstall `app.readylytics.health`** to make a build, migration or benchmark run.
- **After creating or deleting files:** run `codegraph index`; after structural moves, `codegraph sync`.

### Validation shorthand (from spec §10)

| Token | Command |
|---|---|
| U-H | `./gradlew :core:healthconnect:testDebugUnitTest` |
| U-D | `./gradlew :core:database:testDebugUnitTest` |
| U-M | `./gradlew :core:model:testDebugUnitTest` |
| U-A | `./gradlew :app:testDebugUnitTest` |
| DB-device | `./gradlew :database-benchmark:connectedBenchmarkAndroidTest` (dedicated device, isolated fixture) |

Use `--tests "<FQCN>"` to select a single fixture while iterating.

---

## Current State (verified against HEAD, 2026-09-21)

Phase 1 landed; this plan assumes its seams and does not re-do them. What already exists:

- `HealthDatabase` is at **v22** with `Migration21To22` creating `minute_coverage`, `hr_source_minute_contributions`, `staged_hr_sources`, `staged_hr_samples`; DAOs registered in `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/HealthDatabase.kt`.
- `SourceMetadata`/`SourcePayload` carry opaque source id, origin package, actual bounds and `lastModifiedMs` (DB-001); `health_source_records` has index `(recordType, metadataState, recordStartMs)`.
- `RoomDirtyRangeStore`, `HealthMutationCoordinatorImpl`, `MinuteCoveragePublisher` (generation-checked), `WarmTierRelinker`, `AuthoritativeHeartRateReader`, `HeartRateRefreshStagingStore` exist (WP-04/17).
- `ResyncCheckpoint` carries `runIdentity: HistoricalRunIdentity?`, `completedTypes`, `hrPageToken`/`hrvPageToken` (WP-06/10).
- Backup: `BackupSnapshotExporter.captureEncrypted` streams straight into an encrypted ZIP under a dedicated cache staging dir, `LocalBackupManager` prunes **after** verified publication, `CachePrune.pruneBackupStaging` runs from `DatabaseReadyStartupInitializer`, and `BackupStreamWriter` already pages `healthSourceRecords` via `sourceRecordDao.pageAfter(afterId, 500)` (SEC-003/005 and PERF-003's backup half).

What remains, i.e. this plan's scope:

| Gap | Evidence at HEAD | Task |
|---|---|---|
| Heap seen-ID sets for a whole chunk | `HealthIngestionCoordinator.streamHeartRateSamples` builds `mutableSetOf<String>()`; `CompleteTypeScan.ids: Set<String>` | 1–4 |
| `NOT IN (:validIds)` binding lists | `SleepSessionDao.deleteSessionsNotIn`, `WorkoutDao.deleteWorkoutsNotIn`, `StepRecordDao.deleteNotIn`, 5× vitals `deleteNotIn` | 3 |
| Whole-window materialization to find deletions | `HealthRecordDeletionReconciler.reconcileSleep/reconcileExercise/reconcileSteps/reconcileCompositeMetric` call `getBetween(...)` then filter in Kotlin | 3 |
| One transaction per parent source | `SourcePayloadWriter.replaceHeartRateSources` loops `transactionRunner.runInTransaction { replaceSingleHeartRateSource(...) }` | 5 |
| Page-wide transform buffer | `HeartRateMapper.mapToInputs(page, …)` flattens/sorts every nested sample of a page | 6 |
| Retry amplification | `HistoricalIngestPhase.processChunk` wraps `ingestWindow` in `retryWithBackoff`, which already wraps each of the 9 reads in `fetchBulkRecords` | 7 |
| Page tokens never resumed | `processChunk` passes `hrStartPageToken = null` and clears tokens (WP-06 safe fallback) | 4 |
| Day-wide rollup read inside the writer transaction | `DataRollupManager.rollupDayChunk` → `heartRateDao.getPlausibleSamplesInRangeForRollup(fromMs, toMs)` inside `runInTransaction`, then `groupBy` | 8 |
| Orphan source metadata never collected | `RetentionCleanup` has no source-metadata GC | 9 |
| No proven index for the new predicates | no `EXPLAIN QUERY PLAN` coverage for anti-join/staging/GC | 10 |

---

## File Structure

New files:

| File | Responsibility |
|---|---|
| `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/ScanStaging.kt` | Pure contracts: `ScanIdentity`, `TypeScanState`, `ScanStagingStore` port. Zero Android. |
| `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/ScanSeenIdEntity.kt` | `scan_seen_ids` row. |
| `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/ScanTypeStateEntity.kt` | `scan_type_state` row (per-type scan completeness + staged count). |
| `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/ScanStagingDao.kt` | Staging inserts/state/counts/GC + `StagedDeletionBounds` projection. |
| `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/migration/Migration22To23.kt` | S3 additive migration. |
| `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RoomScanStagingStore.kt` | `ScanStagingStore` impl: bounded batch staging, run GC. |
| `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/StagedDeletionReconciler.kt` | Anti-join deletion per `HealthDataType`, extracted out of `RoomHealthIngestionStore.kt`. |
| `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/SourceRefResolver.kt` | Bulk `sourceId → sourceRef` lookup/insert for one page. |
| `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/MinuteRollupStreamer.kt` | Keyset paging of raw samples into complete-minute groups; carries only the unfinished bucket. |
| `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/SourceMetadataGc.kt` | Unreferenced-source-metadata collection. |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/ScanIdentities.kt` | `ScanIdentity` factories for the daily and historical flows. |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HeartSampleStreamer.kt` | HR/HRV page streaming + staging + bounded transform slices, extracted out of `HealthIngestionCoordinator.kt`. |
| `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/ReadRetryBudget.kt` | One shared bounded retry budget per window/run. |

Modified files: `CompleteTypeScan.kt`, `HealthIngestionStore.kt`, `HealthIngestionCoordinator.kt`, `DailySyncUseCase.kt`, `HistoricalIngestPhase.kt`, `RetryWithBackoff.kt`, `RoomHealthIngestionStore.kt`, `SourcePayloadWriter.kt`, `DataRollupManager.kt`, `RetentionCleanup.kt`, `HealthDatabase.kt`, `DatabaseMigrations.kt`, the per-type DAOs gaining anti-join queries, Hilt modules `DaoJournalProvidersModule.kt` / `DatabaseModule.kt` / `HealthConnectModule.kt`, `internal-docs/DATA_FLOW.md`, `benchmark/BASELINE.md`, `database-benchmark/.../HealthPipelineBaselineBenchmark.kt`.

---

## Task 1: S3 staging schema and DAO (migration v22 → v23)

**Files:**
- Create: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/ScanSeenIdEntity.kt`
- Create: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/ScanTypeStateEntity.kt`
- Create: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/ScanStagingDao.kt`
- Create: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/migration/Migration22To23.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/HealthDatabase.kt` (entities, `scanStagingDao()`, `DATABASE_VERSION = 23`)
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/DatabaseMigrations.kt` (`all` array)
- Test: `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/Migration22To23Test.kt`
- Test: `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/ScanStagingDaoTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `ScanSeenIdEntity(runId: String, chunkId: String, recordType: String, sourceId: String)`; `ScanTypeStateEntity(runId: String, chunkId: String, recordType: String, state: String, stagedCount: Int, updatedAtMs: Long)`; `ScanStagingDao` with `insertSeenIds(List<ScanSeenIdEntity>)`, `upsertState(ScanTypeStateEntity)`, `getState(runId, chunkId, recordType): ScanTypeStateEntity?`, `countSeen(runId, chunkId, recordType): Int`, `deleteSeenForType(runId, chunkId, recordType)`, `deleteStateForType(runId, chunkId, recordType)`, `deleteSeenForRun(runId)`, `deleteStateForRun(runId)`, `deleteSeenForOtherRuns(runId)`, `deleteStateForOtherRuns(runId)`, `deleteAllSeen()`, `deleteAllState()`; constants `ScanStagingDao.STATE_SCANNING = "SCANNING"`, `ScanStagingDao.STATE_COMPLETE = "COMPLETE"`; projection `StagedDeletionBounds(minMs: Long?, maxMs: Long?)`.

- [ ] **Step 1: Write the failing DAO test**

`core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/ScanStagingDaoTest.kt`:

```kotlin
package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.dao.ScanStagingDao
import app.readylytics.health.core.databaseschema.data.local.entity.ScanSeenIdEntity
import app.readylytics.health.core.databaseschema.data.local.entity.ScanTypeStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScanStagingDaoTest {
    private lateinit var database: HealthDatabase
    private lateinit var dao: ScanStagingDao

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        dao = database.scanStagingDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun stagingIsScopedByRunChunkAndType() =
        runBlocking {
            dao.insertSeenIds(
                listOf(
                    ScanSeenIdEntity("run-a", "19000", "HEART_RATE", "hc-1"),
                    ScanSeenIdEntity("run-a", "19000", "HEART_RATE", "hc-2"),
                    ScanSeenIdEntity("run-a", "19000", "HRV", "hc-3"),
                    ScanSeenIdEntity("run-a", "19030", "HEART_RATE", "hc-4"),
                    ScanSeenIdEntity("run-b", "19000", "HEART_RATE", "hc-5"),
                ),
            )

            assertEquals(2, dao.countSeen("run-a", "19000", "HEART_RATE"))
            assertEquals(1, dao.countSeen("run-a", "19000", "HRV"))
            assertEquals(1, dao.countSeen("run-b", "19000", "HEART_RATE"))
        }

    @Test
    fun repeatedStagingOfSameIdIsIdempotent() =
        runBlocking {
            val row = ScanSeenIdEntity("run-a", "19000", "HEART_RATE", "hc-1")
            dao.insertSeenIds(listOf(row))
            dao.insertSeenIds(listOf(row))

            assertEquals(1, dao.countSeen("run-a", "19000", "HEART_RATE"))
        }

    @Test
    fun clearingOtherRunsKeepsTheActiveRun() =
        runBlocking {
            dao.insertSeenIds(
                listOf(
                    ScanSeenIdEntity("run-a", "19000", "HEART_RATE", "hc-1"),
                    ScanSeenIdEntity("run-b", "19000", "HEART_RATE", "hc-2"),
                ),
            )
            dao.upsertState(ScanTypeStateEntity("run-a", "19000", "HEART_RATE", ScanStagingDao.STATE_SCANNING, 1, 10L))
            dao.upsertState(ScanTypeStateEntity("run-b", "19000", "HEART_RATE", ScanStagingDao.STATE_COMPLETE, 1, 10L))

            dao.deleteSeenForOtherRuns("run-a")
            dao.deleteStateForOtherRuns("run-a")

            assertEquals(1, dao.countSeen("run-a", "19000", "HEART_RATE"))
            assertEquals(0, dao.countSeen("run-b", "19000", "HEART_RATE"))
            assertNull(dao.getState("run-b", "19000", "HEART_RATE"))
            assertEquals(
                ScanStagingDao.STATE_SCANNING,
                dao.getState("run-a", "19000", "HEART_RATE")?.state,
            )
        }

    @Test
    fun stateUpsertReplacesPreviousStateForSameKey() =
        runBlocking {
            dao.upsertState(ScanTypeStateEntity("run-a", "19000", "SLEEP", ScanStagingDao.STATE_SCANNING, 3, 10L))
            dao.upsertState(ScanTypeStateEntity("run-a", "19000", "SLEEP", ScanStagingDao.STATE_COMPLETE, 7, 20L))

            val state = dao.getState("run-a", "19000", "SLEEP")
            assertEquals(ScanStagingDao.STATE_COMPLETE, state?.state)
            assertEquals(7, state?.stagedCount)
            assertEquals(20L, state?.updatedAtMs)
        }
}
```

- [ ] **Step 2: Run it and confirm it fails to compile**

```bash
./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.core.database.data.local.ScanStagingDaoTest"
```

Expected: compilation failure — `Unresolved reference: ScanStagingDao` / `scanStagingDao`.

- [ ] **Step 3: Add the entities**

`ScanSeenIdEntity.kt`:

```kotlin
package app.readylytics.health.core.databaseschema.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * WP-18/S3 scan staging: one Health Connect record identity observed by the scan of
 * `(runId, chunkId, recordType)`. Operational state only — never exported as health data, never
 * FK-joined to `health_source_records` (staging must not create live authoritative metadata just to
 * hold an id). Replaces the per-chunk heap `Set<String>` that grew with scanned cardinality
 * (PERF-001) and the `NOT IN (:validIds)` binding lists it fed (HC-002).
 */
@Entity(
    tableName = "scan_seen_ids",
    primaryKeys = ["runId", "chunkId", "recordType", "sourceId"],
    indices = [Index(value = ["runId", "chunkId", "recordType"])],
)
data class ScanSeenIdEntity(
    val runId: String,
    val chunkId: String,
    val recordType: String,
    val sourceId: String,
)
```

`ScanTypeStateEntity.kt`:

```kotlin
package app.readylytics.health.core.databaseschema.data.local.entity

import androidx.room.Entity

/**
 * WP-18/HC-002 scan completeness. Deletion reconciliation may only run for a key whose state is
 * `COMPLETE`: a resumed or cancelled scan leaves `SCANNING`, and unseen pages must never be
 * interpreted as deletions.
 */
@Entity(tableName = "scan_type_state", primaryKeys = ["runId", "chunkId", "recordType"])
data class ScanTypeStateEntity(
    val runId: String,
    val chunkId: String,
    val recordType: String,
    val state: String,
    val stagedCount: Int,
    val updatedAtMs: Long,
)
```

- [ ] **Step 4: Add the DAO**

`ScanStagingDao.kt`:

```kotlin
package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.ScanSeenIdEntity
import app.readylytics.health.core.databaseschema.data.local.entity.ScanTypeStateEntity

/** Min/max bounds of the rows an anti-join delete is about to remove, read before deleting them. */
data class StagedDeletionBounds(
    val minMs: Long?,
    val maxMs: Long?,
)

@Dao
interface ScanStagingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeenIds(rows: List<ScanSeenIdEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: ScanTypeStateEntity)

    @Query(
        "SELECT * FROM scan_type_state " +
            "WHERE runId = :runId AND chunkId = :chunkId AND recordType = :recordType",
    )
    suspend fun getState(
        runId: String,
        chunkId: String,
        recordType: String,
    ): ScanTypeStateEntity?

    @Query(
        "SELECT COUNT(*) FROM scan_seen_ids " +
            "WHERE runId = :runId AND chunkId = :chunkId AND recordType = :recordType",
    )
    suspend fun countSeen(
        runId: String,
        chunkId: String,
        recordType: String,
    ): Int

    @Query(
        "DELETE FROM scan_seen_ids " +
            "WHERE runId = :runId AND chunkId = :chunkId AND recordType = :recordType",
    )
    suspend fun deleteSeenForType(
        runId: String,
        chunkId: String,
        recordType: String,
    )

    @Query(
        "DELETE FROM scan_type_state " +
            "WHERE runId = :runId AND chunkId = :chunkId AND recordType = :recordType",
    )
    suspend fun deleteStateForType(
        runId: String,
        chunkId: String,
        recordType: String,
    )

    @Query("DELETE FROM scan_seen_ids WHERE runId = :runId")
    suspend fun deleteSeenForRun(runId: String)

    @Query("DELETE FROM scan_type_state WHERE runId = :runId")
    suspend fun deleteStateForRun(runId: String)

    @Query("DELETE FROM scan_seen_ids WHERE runId <> :runId")
    suspend fun deleteSeenForOtherRuns(runId: String)

    @Query("DELETE FROM scan_type_state WHERE runId <> :runId")
    suspend fun deleteStateForOtherRuns(runId: String)

    @Query("DELETE FROM scan_seen_ids")
    suspend fun deleteAllSeen()

    @Query("DELETE FROM scan_type_state")
    suspend fun deleteAllState()

    companion object {
        const val STATE_SCANNING: String = "SCANNING"
        const val STATE_COMPLETE: String = "COMPLETE"
    }
}
```

- [ ] **Step 5: Register entities, DAO accessor and version bump**

In `HealthDatabase.kt`: add `ScanSeenIdEntity::class,` and `ScanTypeStateEntity::class,` to `entities`, add the imports, add

```kotlin
    abstract fun scanStagingDao(): ScanStagingDao
```

and change the companion to `const val DATABASE_VERSION = 23`.

- [ ] **Step 6: Run the DAO test — now passing**

```bash
./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.core.database.data.local.ScanStagingDaoTest"
```

Expected: PASS. Room also regenerates `core/database/schemas/app.readylytics.health.core.database.data.local.HealthDatabase/23.json` — commit it.

- [ ] **Step 7: Write the failing migration test**

`Migration22To23Test.kt`:

```kotlin
package app.readylytics.health.core.database.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.core.database.data.local.migration.Migration22To23
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration22To23Test {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HealthDatabase::class.java)

    @Test
    fun migrationCreatesEmptyStagingTablesAndPreservesExistingRows() {
        helper.createDatabase(TEST_DATABASE, 22).apply {
            execSQL(
                "INSERT INTO health_source_records " +
                    "(sourceRecordId, recordType, createdAtMs, metadataState, sourceRevision) " +
                    "VALUES ('hc-1', 'HEART_RATE', 1000, 'AUTHORITATIVE', 1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DATABASE, 23, true, Migration22To23)

        db.query("SELECT COUNT(*) FROM health_source_records").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM scan_seen_ids").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM scan_type_state").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }

    @Test
    fun stagingIndexIsCreated() {
        helper.createDatabase(TEST_DATABASE, 22).close()

        val db = helper.runMigrationsAndValidate(TEST_DATABASE, 23, true, Migration22To23)

        val indexNames = mutableListOf<String>()
        db.query("PRAGMA index_list('scan_seen_ids')").use { cursor ->
            while (cursor.moveToNext()) {
                indexNames += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
        }
        assertTrue(
            "expected staging lookup index, got $indexNames",
            indexNames.any { it == "index_scan_seen_ids_runId_chunkId_recordType" },
        )
        db.close()
    }

    private companion object {
        const val TEST_DATABASE = "migration-22-23-test.db"
    }
}
```

- [ ] **Step 8: Run it and confirm it fails**

```bash
./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.core.database.data.local.Migration22To23Test"
```

Expected: FAIL — `Unresolved reference: Migration22To23`.

- [ ] **Step 9: Write the migration**

`Migration22To23.kt`:

```kotlin
package app.readylytics.health.core.database.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * WP-18/S3: additive scan-staging tables. Both start empty — staging is in-flight operational
 * state, so there is nothing to backfill and no physiological data is rewritten. Old scan
 * checkpoints stay replayable: a resumed run with no staged rows simply rescans its chunk, which is
 * the WP-06 complete-chunk replay fallback.
 */
object Migration22To23 : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `scan_seen_ids` (
                `runId` TEXT NOT NULL,
                `chunkId` TEXT NOT NULL,
                `recordType` TEXT NOT NULL,
                `sourceId` TEXT NOT NULL,
                PRIMARY KEY(`runId`, `chunkId`, `recordType`, `sourceId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_scan_seen_ids_runId_chunkId_recordType` " +
                "ON `scan_seen_ids` (`runId`, `chunkId`, `recordType`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `scan_type_state` (
                `runId` TEXT NOT NULL,
                `chunkId` TEXT NOT NULL,
                `recordType` TEXT NOT NULL,
                `state` TEXT NOT NULL,
                `stagedCount` INTEGER NOT NULL,
                `updatedAtMs` INTEGER NOT NULL,
                PRIMARY KEY(`runId`, `chunkId`, `recordType`)
            )
            """.trimIndent(),
        )
    }
}
```

Then add `Migration22To23,` to the end of `DatabaseMigrations.all` and import it.

- [ ] **Step 10: Run both tests plus the whole module**

```bash
./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.core.database.data.local.Migration22To23Test"
./gradlew :core:database:testDebugUnitTest
```

Expected: both PASS; no other `core:database` test regresses (the v23 schema JSON must exist or `runMigrationsAndValidate` fails).

- [ ] **Step 11: Update DATA_FLOW and commit**

In `internal-docs/DATA_FLOW.md`: change the `### 1.4 Room storage — HealthDatabase (@Database(version = 22))` heading to `version = 23` and add a short subsection under it describing `scan_seen_ids`/`scan_type_state` as operational scan staging excluded from backup and scoring, keyed `(runId, chunkId, recordType, sourceId)`, with the "never prune from a scan that is not COMPLETE" rule.

```bash
codegraph index
./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest
rtk git add -A && rtk git commit -m "feat(db): add S3 scan-staging tables and migration 22->23"
```

---

## Task 2: `ScanStagingStore` port and Room implementation

**Files:**
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/ScanStaging.kt`
- Create: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RoomScanStagingStore.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/di/DaoJournalProvidersModule.kt` (provide `ScanStagingDao`)
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/di/DatabaseRepositoryModule.kt` (bind `ScanStagingStore`)
- Test: `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/RoomScanStagingStoreTest.kt`

**Interfaces:**
- Consumes: `ScanStagingDao`, `ScanSeenIdEntity`, `ScanTypeStateEntity`, `ScanStagingDao.STATE_*` (Task 1).
- Produces:

```kotlin
data class ScanIdentity(val runId: String, val chunkId: String)
enum class TypeScanState { SCANNING, COMPLETE }

interface ScanStagingStore {
    suspend fun beginTypeScan(scan: ScanIdentity, type: HealthDataType, resume: Boolean)
    suspend fun stageIds(scan: ScanIdentity, type: HealthDataType, ids: Collection<String>)
    suspend fun markTypeScanComplete(scan: ScanIdentity, type: HealthDataType)
    suspend fun stateOf(scan: ScanIdentity, type: HealthDataType): TypeScanState?
    suspend fun stagedCount(scan: ScanIdentity, type: HealthDataType): Int
    suspend fun clearTypeScan(scan: ScanIdentity, type: HealthDataType)
    suspend fun clearRun(runId: String)
    suspend fun clearRunsOtherThan(runId: String)
}
```

`ScanStagingStore.STAGE_BATCH_SIZE = 500` is the bounded insert batch.

- [ ] **Step 1: Write the failing store test**

`RoomScanStagingStoreTest.kt`:

```kotlin
package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.ScanIdentity
import app.readylytics.health.core.model.domain.sync.ScanStagingStore
import app.readylytics.health.core.model.domain.sync.TypeScanState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class RoomScanStagingStoreTest {
    private lateinit var database: HealthDatabase
    private lateinit var store: ScanStagingStore
    private val scan = ScanIdentity(runId = "run-a", chunkId = "19000")

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        store =
            RoomScanStagingStore(
                scanStagingDao = database.scanStagingDao(),
                clock = Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC),
            )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun beginWithoutResumeClearsPreviousStagingForThatTypeOnly() =
        runBlocking {
            store.beginTypeScan(scan, HealthDataType.HEART_RATE, resume = false)
            store.stageIds(scan, HealthDataType.HEART_RATE, listOf("hc-1", "hc-2"))
            store.beginTypeScan(scan, HealthDataType.HRV, resume = false)
            store.stageIds(scan, HealthDataType.HRV, listOf("hc-3"))

            store.beginTypeScan(scan, HealthDataType.HEART_RATE, resume = false)

            assertEquals(0, store.stagedCount(scan, HealthDataType.HEART_RATE))
            assertEquals(1, store.stagedCount(scan, HealthDataType.HRV))
            assertEquals(TypeScanState.SCANNING, store.stateOf(scan, HealthDataType.HEART_RATE))
        }

    @Test
    fun beginWithResumeKeepsStagedIds() =
        runBlocking {
            store.beginTypeScan(scan, HealthDataType.HEART_RATE, resume = false)
            store.stageIds(scan, HealthDataType.HEART_RATE, listOf("hc-1", "hc-2"))

            store.beginTypeScan(scan, HealthDataType.HEART_RATE, resume = true)

            assertEquals(2, store.stagedCount(scan, HealthDataType.HEART_RATE))
            assertEquals(TypeScanState.SCANNING, store.stateOf(scan, HealthDataType.HEART_RATE))
        }

    @Test
    fun markCompleteRecordsStateAndCount() =
        runBlocking {
            store.beginTypeScan(scan, HealthDataType.SLEEP, resume = false)
            store.stageIds(scan, HealthDataType.SLEEP, listOf("s1", "s2", "s3"))

            store.markTypeScanComplete(scan, HealthDataType.SLEEP)

            assertEquals(TypeScanState.COMPLETE, store.stateOf(scan, HealthDataType.SLEEP))
            assertEquals(3, store.stagedCount(scan, HealthDataType.SLEEP))
        }

    @Test
    fun stagingMoreThanOneBatchStoresEveryId() =
        runBlocking {
            val ids = (1..1_250).map { "hc-$it" }
            store.beginTypeScan(scan, HealthDataType.HEART_RATE, resume = false)

            store.stageIds(scan, HealthDataType.HEART_RATE, ids)

            assertEquals(1_250, store.stagedCount(scan, HealthDataType.HEART_RATE))
        }

    @Test
    fun clearRunsOtherThanDropsAbandonedRunsOnly() =
        runBlocking {
            store.beginTypeScan(scan, HealthDataType.HEART_RATE, resume = false)
            store.stageIds(scan, HealthDataType.HEART_RATE, listOf("hc-1"))
            val abandoned = ScanIdentity(runId = "run-old", chunkId = "18000")
            store.beginTypeScan(abandoned, HealthDataType.HEART_RATE, resume = false)
            store.stageIds(abandoned, HealthDataType.HEART_RATE, listOf("hc-9"))

            store.clearRunsOtherThan("run-a")

            assertEquals(1, store.stagedCount(scan, HealthDataType.HEART_RATE))
            assertEquals(0, store.stagedCount(abandoned, HealthDataType.HEART_RATE))
            assertNull(store.stateOf(abandoned, HealthDataType.HEART_RATE))
        }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.core.database.data.local.RoomScanStagingStoreTest"
```

Expected: FAIL — `Unresolved reference: ScanIdentity` / `RoomScanStagingStore`.

- [ ] **Step 3: Add the pure port**

`core/model/.../domain/sync/ScanStaging.kt`:

```kotlin
package app.readylytics.health.core.model.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType

/**
 * WP-18: identity of one scan unit. `runId` is the immutable historical run id
 * ([HistoricalRunIdentity.runId]) or the fixed daily-sync id; `chunkId` identifies the window
 * inside that run (epoch day of the chunk start for a resync, window start millis for a daily
 * sync). Staged identities are scoped by this pair so two runs can never read each other's scan.
 */
data class ScanIdentity(
    val runId: String,
    val chunkId: String,
)

enum class TypeScanState {
    SCANNING,
    COMPLETE,
}

/**
 * Durable replacement for the per-chunk heap `Set<String>` of scanned Health Connect record ids
 * (PERF-001), and the record of whether that scan was complete (HC-002). Deletion reconciliation
 * anti-joins against this staging and refuses to run for a key that is not [TypeScanState.COMPLETE]
 * — unseen pages must never be interpreted as deletions.
 */
interface ScanStagingStore {
    /**
     * Opens (or reopens) the scan of [type] for [scan]. With `resume = false` any previously staged
     * identities for exactly that `(runId, chunkId, type)` are dropped first, so a restarted scan
     * cannot inherit a half-populated set. With `resume = true` they are kept and extended.
     */
    suspend fun beginTypeScan(
        scan: ScanIdentity,
        type: HealthDataType,
        resume: Boolean,
    )

    /** Stages [ids] in bounded batches. Idempotent: re-staging the same id changes nothing. */
    suspend fun stageIds(
        scan: ScanIdentity,
        type: HealthDataType,
        ids: Collection<String>,
    )

    suspend fun markTypeScanComplete(
        scan: ScanIdentity,
        type: HealthDataType,
    )

    suspend fun stateOf(
        scan: ScanIdentity,
        type: HealthDataType,
    ): TypeScanState?

    suspend fun stagedCount(
        scan: ScanIdentity,
        type: HealthDataType,
    ): Int

    suspend fun clearTypeScan(
        scan: ScanIdentity,
        type: HealthDataType,
    )

    suspend fun clearRun(runId: String)

    /** Purges abandoned generations: every staged row whose `runId` is not [runId]. */
    suspend fun clearRunsOtherThan(runId: String)

    companion object {
        /** Bounded insert batch; stays far below the 999-variable floor at 4 columns per row. */
        const val STAGE_BATCH_SIZE: Int = 500
    }
}
```

- [ ] **Step 4: Add the Room implementation**

`RoomScanStagingStore.kt`:

```kotlin
package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.ScanStagingDao
import app.readylytics.health.core.databaseschema.data.local.entity.ScanSeenIdEntity
import app.readylytics.health.core.databaseschema.data.local.entity.ScanTypeStateEntity
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.ScanIdentity
import app.readylytics.health.core.model.domain.sync.ScanStagingStore
import app.readylytics.health.core.model.domain.sync.TypeScanState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomScanStagingStore
    @Inject
    constructor(
        private val scanStagingDao: ScanStagingDao,
        private val clock: Clock = Clock.systemUTC(),
    ) : ScanStagingStore {
        override suspend fun beginTypeScan(
            scan: ScanIdentity,
            type: HealthDataType,
            resume: Boolean,
        ) {
            if (!resume) {
                scanStagingDao.deleteSeenForType(scan.runId, scan.chunkId, type.name)
            }
            writeState(scan, type, ScanStagingDao.STATE_SCANNING)
        }

        override suspend fun stageIds(
            scan: ScanIdentity,
            type: HealthDataType,
            ids: Collection<String>,
        ) {
            if (ids.isEmpty()) return
            ids.chunked(ScanStagingStore.STAGE_BATCH_SIZE).forEach { batch ->
                currentCoroutineContext().ensureActive()
                scanStagingDao.insertSeenIds(
                    batch.map { id ->
                        ScanSeenIdEntity(
                            runId = scan.runId,
                            chunkId = scan.chunkId,
                            recordType = type.name,
                            sourceId = id,
                        )
                    },
                )
            }
        }

        override suspend fun markTypeScanComplete(
            scan: ScanIdentity,
            type: HealthDataType,
        ) = writeState(scan, type, ScanStagingDao.STATE_COMPLETE)

        override suspend fun stateOf(
            scan: ScanIdentity,
            type: HealthDataType,
        ): TypeScanState? =
            when (scanStagingDao.getState(scan.runId, scan.chunkId, type.name)?.state) {
                ScanStagingDao.STATE_COMPLETE -> TypeScanState.COMPLETE
                ScanStagingDao.STATE_SCANNING -> TypeScanState.SCANNING
                else -> null
            }

        override suspend fun stagedCount(
            scan: ScanIdentity,
            type: HealthDataType,
        ): Int = scanStagingDao.countSeen(scan.runId, scan.chunkId, type.name)

        override suspend fun clearTypeScan(
            scan: ScanIdentity,
            type: HealthDataType,
        ) {
            scanStagingDao.deleteSeenForType(scan.runId, scan.chunkId, type.name)
            scanStagingDao.deleteStateForType(scan.runId, scan.chunkId, type.name)
        }

        override suspend fun clearRun(runId: String) {
            scanStagingDao.deleteSeenForRun(runId)
            scanStagingDao.deleteStateForRun(runId)
        }

        override suspend fun clearRunsOtherThan(runId: String) {
            scanStagingDao.deleteSeenForOtherRuns(runId)
            scanStagingDao.deleteStateForOtherRuns(runId)
        }

        private suspend fun writeState(
            scan: ScanIdentity,
            type: HealthDataType,
            state: String,
        ) {
            scanStagingDao.upsertState(
                ScanTypeStateEntity(
                    runId = scan.runId,
                    chunkId = scan.chunkId,
                    recordType = type.name,
                    state = state,
                    stagedCount = scanStagingDao.countSeen(scan.runId, scan.chunkId, type.name),
                    updatedAtMs = clock.millis(),
                ),
            )
        }
    }
```

- [ ] **Step 5: Run the store test**

```bash
./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.core.database.data.local.RoomScanStagingStoreTest"
```

Expected: PASS.

- [ ] **Step 6: Wire Hilt**

In `DaoJournalProvidersModule.kt`, following the existing `@Provides` pattern in that file:

```kotlin
    @Provides
    fun provideScanStagingDao(database: HealthDatabase): ScanStagingDao = database.scanStagingDao()
```

In `DatabaseRepositoryModule.kt`, following its existing `@Binds` pattern:

```kotlin
    @Binds
    @Singleton
    abstract fun bindScanStagingStore(impl: RoomScanStagingStore): ScanStagingStore
```

- [ ] **Step 7: Verify DI assembles, then commit**

```bash
./gradlew :core:model:testDebugUnitTest :core:database:testDebugUnitTest && ./gradlew assembleDebug
codegraph index
./gradlew ktlintFormat && ./gradlew detekt
rtk git add -A && rtk git commit -m "feat(sync): add durable scan-staging store for seen record ids"
```

---

## Task 3: Anti-join deletion reconciliation

**Files:**
- Modify: `core/model/.../domain/sync/CompleteTypeScan.kt` (replace `ids: Set<String>` with `scan: ScanIdentity`)
- Modify: `core/model/.../domain/sync/HealthIngestionStore.kt` (KDoc of `reconcileWindow`)
- Create: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/StagedDeletionReconciler.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RoomHealthIngestionStore.kt` (delete `HealthRecordDeletionReconciler`, delegate to the new reconciler, take `ScanStagingDao`)
- Modify DAOs (add anti-join delete + bounds queries): `SleepSessionDao.kt`, `WorkoutDao.kt`, `StepRecordDao.kt`, `WeightRecordDao.kt`, `BodyFatRecordDao.kt`, `BloodPressureRecordDao.kt`, `OxygenSaturationRecordDao.kt`, `BodyTemperatureRecordDao.kt`, `Vo2MaxRecordDao.kt`, `SourceRecordDao.kt`
- Test: `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/StagedDeletionReconcilerTest.kt`
- Modify tests (construction sites of `CompleteTypeScan`): `RoomHealthIngestionStoreReconcileTest.kt`, `RoomHealthIngestionStoreTest.kt`, `RoomHealthChangeIngestionStoreTest.kt`, `core/healthconnect/src/test/.../CompleteTypeScanTest.kt`

**Interfaces:**
- Consumes: `ScanIdentity`, `ScanStagingDao`, `StagedDeletionBounds`, `ScanStagingDao.STATE_COMPLETE` (Tasks 1–2).
- Produces:
  - `CompleteTypeScan(type: HealthDataType, windowStartMs: Long, windowEndExclusiveMs: Long, sourceSelectionId: String, scan: ScanIdentity)`
  - `StagedDeletionReconciler.reconcile(daos: HealthRecordDaos, vo2MaxRecordDao: Vo2MaxRecordDao, scanStagingDao: ScanStagingDao, scan: CompleteTypeScan, zoneId: ZoneId): ScoreInvalidation.AffectedRange?` — returns `null` when the scan is not `COMPLETE` or nothing was deleted.
  - New DAO queries, all binding-list free: `SleepSessionDao.boundsOfUnstagedSessions(...)`/`deleteSessionsNotStaged(...)`, `WorkoutDao.boundsOfUnstagedWorkouts(...)`/`deleteWorkoutsNotStaged(...)`, `StepRecordDao.boundsOfUnstagedRecords(...)`/`deleteRecordsNotStaged(...)`, per-vitals `boundsOfUnstagedRows(...)`/`deleteRowsNotStaged(...)`, `SourceRecordDao.pageUnstagedAuthoritativeSources(...)`.

**Design note (behaviour change to assert, not to hide):** vitals rows are keyed `"${hcRecordId}_${timestampMs}"` and today survive reconciliation through an id-prefix fallback (`getId(it).substringBefore('_') !in ctx.hcIds`), which keeps a stale row alive when a record's timestamp moves. Staging the *persisted row identity* for vitals/steps removes the ID parsing DB-001 asks us to stop doing and makes a moved timestamp converge (old row deleted, new row inserted, both inside the affected range). Task 4 stages these composite ids from the unfiltered mapped records, preserving today's "device filtering is `SelectedSourcePruner`'s job, not reconciliation's" split.

- [ ] **Step 1: Write the failing reconciler test**

`StagedDeletionReconcilerTest.kt`:

```kotlin
package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.dao.ScanStagingDao
import app.readylytics.health.core.databaseschema.data.local.entity.ScanSeenIdEntity
import app.readylytics.health.core.databaseschema.data.local.entity.ScanTypeStateEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.CompleteTypeScan
import app.readylytics.health.core.model.domain.sync.ScanIdentity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class StagedDeletionReconcilerTest {
    private lateinit var database: HealthDatabase
    private lateinit var scanStagingDao: ScanStagingDao
    private val scanId = ScanIdentity(runId = "run-a", chunkId = "0")
    private val windowStartMs = 0L
    private val windowEndMs = 10 * 86_400_000L

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        scanStagingDao = database.scanStagingDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun deletesOnlySessionsAbsentFromACompleteScan() =
        runBlocking {
            database.sleepSessionDao().upsertAll(
                listOf(session("s1", 1_000L, 2_000L), session("s2", 3_000L, 4_000L)),
            )
            stage(HealthDataType.SLEEP, listOf("s1"), complete = true)

            val affected = reconcile(HealthDataType.SLEEP)

            assertEquals(listOf("s1"), database.sleepSessionDao().getBetween(windowStartMs, windowEndMs).map { it.id })
            assertNotNull(affected)
            assertEquals(java.time.LocalDate.of(1970, 1, 1), affected?.start)
        }

    @Test
    fun refusesToDeleteFromAnIncompleteScan() =
        runBlocking {
            database.sleepSessionDao().upsertAll(
                listOf(session("s1", 1_000L, 2_000L), session("s2", 3_000L, 4_000L)),
            )
            stage(HealthDataType.SLEEP, listOf("s1"), complete = false)

            val affected = reconcile(HealthDataType.SLEEP)

            assertEquals(2, database.sleepSessionDao().getBetween(windowStartMs, windowEndMs).size)
            assertNull(affected)
        }

    @Test
    fun emptyCompleteScanDeletesEveryLocalRowInWindow() =
        runBlocking {
            database.sleepSessionDao().upsertAll(listOf(session("s1", 1_000L, 2_000L)))
            stage(HealthDataType.SLEEP, emptyList(), complete = true)

            val affected = reconcile(HealthDataType.SLEEP)

            assertEquals(0, database.sleepSessionDao().getBetween(windowStartMs, windowEndMs).size)
            assertNotNull(affected)
        }

    @Test
    fun deletionScopeIsIndependentOfStagedCardinality() =
        runBlocking {
            // 2_000 staged ids is far past SQLITE_MAX_VARIABLE_NUMBER; the old NOT IN (:ids) path
            // could not express this at all.
            val sessions = (1..2_000).map { session("s$it", it * 10_000L, it * 10_000L + 1_000L) }
            database.sleepSessionDao().upsertAll(sessions)
            database.sleepSessionDao().upsertAll(listOf(session("gone", 5L, 100L)))
            stage(HealthDataType.SLEEP, sessions.map { it.id }, complete = true)

            reconcile(HealthDataType.SLEEP)

            val remaining = database.sleepSessionDao().getBetween(windowStartMs, windowEndMs).map { it.id }
            assertEquals(2_000, remaining.size)
            assertEquals(false, remaining.contains("gone"))
        }

    private suspend fun reconcile(type: HealthDataType) =
        StagedDeletionReconciler.reconcile(
            daos = database.healthRecordDaos(),
            vo2MaxRecordDao = database.vo2MaxRecordDao(),
            scanStagingDao = scanStagingDao,
            scan =
                CompleteTypeScan(
                    type = type,
                    windowStartMs = windowStartMs,
                    windowEndExclusiveMs = windowEndMs,
                    sourceSelectionId = "",
                    scan = scanId,
                ),
            zoneId = ZoneOffset.UTC,
        )

    private suspend fun stage(
        type: HealthDataType,
        ids: List<String>,
        complete: Boolean,
    ) {
        scanStagingDao.insertSeenIds(ids.map { ScanSeenIdEntity(scanId.runId, scanId.chunkId, type.name, it) })
        scanStagingDao.upsertState(
            ScanTypeStateEntity(
                runId = scanId.runId,
                chunkId = scanId.chunkId,
                recordType = type.name,
                state = if (complete) ScanStagingDao.STATE_COMPLETE else ScanStagingDao.STATE_SCANNING,
                stagedCount = ids.size,
                updatedAtMs = 0L,
            ),
        )
    }

    private fun session(
        id: String,
        startTime: Long,
        endTime: Long,
    ) = SleepSessionEntity(
        id = id,
        startTime = startTime,
        endTime = endTime,
        durationMinutes = 1,
        efficiency = 1f,
        deepSleepMinutes = 0,
        remSleepMinutes = 0,
        lightSleepMinutes = 1,
        awakeMinutes = 0,
        sleepScore = null,
        startZoneOffsetSeconds = null,
        endZoneOffsetSeconds = null,
        deviceName = null,
    )
}
```

> Executor note: check `SleepSessionEntity`'s actual constructor at HEAD (`core/database-schema/.../entity/SleepSessionEntity.kt`) and the existing helper in `RoomHealthIngestionStoreReconcileTest.kt` — reuse that file's `session(...)` builder verbatim if it differs from the one above, and reuse however that test obtains a `HealthRecordDaos` instance rather than inventing `database.healthRecordDaos()` if no such accessor exists.

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.core.database.data.local.StagedDeletionReconcilerTest"
```

Expected: FAIL — `Unresolved reference: StagedDeletionReconciler`, and `CompleteTypeScan` has no `scan` parameter.

- [ ] **Step 3: Change `CompleteTypeScan` to carry the scan identity**

```kotlin
package app.readylytics.health.core.model.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.repository.ReadOutcome

/**
 * WP-18: one completed type scan of one window. The scanned record identities live in
 * [ScanStagingStore] under [scan], not on the heap — deletion reconciliation anti-joins against
 * `scan_seen_ids` and refuses to run unless that scan is [TypeScanState.COMPLETE] (HC-002).
 */
data class CompleteTypeScan(
    val type: HealthDataType,
    val windowStartMs: Long,
    val windowEndExclusiveMs: Long,
    val sourceSelectionId: String,
    val scan: ScanIdentity,
)

fun ReadOutcome<Unit>.isComplete(): Boolean = this is ReadOutcome.Available
```

- [ ] **Step 4: Add the anti-join DAO queries**

Pattern, once per type. `SleepSessionDao.kt` — replace `deleteSessionsNotIn` with:

```kotlin
    @Query(
        "SELECT MIN(startTime) AS minMs, MAX(endTime) AS maxMs FROM sleep_sessions " +
            "WHERE startTime >= :startMs AND endTime <= :endMs " +
            "AND id NOT IN (" +
            "  SELECT sourceId FROM scan_seen_ids " +
            "  WHERE runId = :runId AND chunkId = :chunkId AND recordType = :recordType)",
    )
    suspend fun boundsOfUnstagedSessions(
        startMs: Long,
        endMs: Long,
        runId: String,
        chunkId: String,
        recordType: String,
    ): StagedDeletionBounds

    @Query(
        "DELETE FROM sleep_stages WHERE sessionId IN (" +
            "  SELECT id FROM sleep_sessions " +
            "  WHERE startTime >= :startMs AND endTime <= :endMs " +
            "  AND id NOT IN (" +
            "    SELECT sourceId FROM scan_seen_ids " +
            "    WHERE runId = :runId AND chunkId = :chunkId AND recordType = :recordType))",
    )
    suspend fun deleteStagesOfUnstagedSessions(
        startMs: Long,
        endMs: Long,
        runId: String,
        chunkId: String,
        recordType: String,
    ): Int

    @Query(
        "DELETE FROM sleep_sessions " +
            "WHERE startTime >= :startMs AND endTime <= :endMs " +
            "AND id NOT IN (" +
            "  SELECT sourceId FROM scan_seen_ids " +
            "  WHERE runId = :runId AND chunkId = :chunkId AND recordType = :recordType)",
    )
    suspend fun deleteSessionsNotStaged(
        startMs: Long,
        endMs: Long,
        runId: String,
        chunkId: String,
        recordType: String,
    ): Int
```

`WorkoutDao.kt`: same three shapes, with `workout_records` / `startTime >= :startMs AND endTime <= :endMs`, plus `deleteRoutePointsOfUnstagedWorkouts` deleting from `workout_route_points WHERE workoutId IN (SELECT id FROM workout_records WHERE … NOT IN …)`. `StepRecordDao.kt`: `step_records`, bounds over `startTime`/`endTime`. Each vitals DAO (`WeightRecordDao`, `BodyFatRecordDao`, `BloodPressureRecordDao`, `OxygenSaturationRecordDao`, `BodyTemperatureRecordDao`, `Vo2MaxRecordDao`): bounds over `timestampMs` for both min and max, delete on `id NOT IN (…)`. Delete every now-unused `deleteNotIn`/`deleteSessionsNotIn`/`deleteWorkoutsNotIn` once no caller remains.

`SourceRecordDao.kt` gains a keyset page of unstaged authoritative parents, so HR/HRV deletion never materializes the whole overlap list:

```kotlin
    @Query(
        "SELECT * FROM health_source_records " +
            "WHERE recordType = :recordType AND metadataState = 'AUTHORITATIVE' " +
            "AND recordStartMs < :windowEndMs AND recordEndExclusiveMs > :windowStartMs " +
            "AND id > :afterRef " +
            "AND sourceRecordId NOT IN (" +
            "  SELECT sourceId FROM scan_seen_ids " +
            "  WHERE runId = :runId AND chunkId = :chunkId AND recordType = :scanType) " +
            "ORDER BY id ASC LIMIT :limit",
    )
    suspend fun pageUnstagedAuthoritativeSources(
        recordType: String,
        windowStartMs: Long,
        windowEndMs: Long,
        runId: String,
        chunkId: String,
        scanType: String,
        afterRef: Long,
        limit: Int,
    ): List<HealthSourceRecordEntity>
```

- [ ] **Step 5: Write `StagedDeletionReconciler`**

```kotlin
package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.ScanStagingDao
import app.readylytics.health.core.databaseschema.data.local.dao.StagedDeletionBounds
import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.CompleteTypeScan
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.time.Instant
import java.time.ZoneId

/**
 * WP-18 deletion reconciliation. Every predicate is a set-based anti-join against `scan_seen_ids`,
 * so no statement carries a bind-variable list and nothing proportional to the scanned cardinality
 * is materialized: for each type we read the min/max bounds of the rows about to be deleted (one
 * aggregate), then delete them in one statement — except HR/HRV parents, which are paged by
 * `health_source_records.id` because each one needs `deleteBySourceRecordId`'s
 * contribution-then-row sequence (its `ON DELETE RESTRICT` FK, see that DAO's KDoc).
 *
 * HC-002: returns `null` without touching anything unless the scan of this type is COMPLETE.
 */
internal object StagedDeletionReconciler {
    const val SOURCE_PAGE_SIZE = 500

    suspend fun reconcile(
        daos: HealthRecordDaos,
        vo2MaxRecordDao: Vo2MaxRecordDao,
        scanStagingDao: ScanStagingDao,
        scan: CompleteTypeScan,
        zoneId: ZoneId,
    ): ScoreInvalidation.AffectedRange? {
        val state = scanStagingDao.getState(scan.scan.runId, scan.scan.chunkId, scan.type.name)
        if (state?.state != ScanStagingDao.STATE_COMPLETE) return null

        val ctx = StagedScanContext(scan, zoneId)
        return when (scan.type) {
            HealthDataType.SLEEP -> reconcileSleep(daos, ctx)
            HealthDataType.EXERCISE -> reconcileExercise(daos, ctx)
            HealthDataType.HEART_RATE -> reconcileHeartSource(daos, "HEART_RATE", ctx)
            HealthDataType.HRV -> reconcileHeartSource(daos, "HRV", ctx)
            HealthDataType.STEPS -> reconcileSteps(daos, ctx)
            else -> reconcileVitals(daos, vo2MaxRecordDao, scan.type, ctx)
        }
    }

    private suspend fun reconcileSleep(
        daos: HealthRecordDaos,
        ctx: StagedScanContext,
    ): ScoreInvalidation.AffectedRange? {
        val bounds =
            daos.sleepSessionDao.boundsOfUnstagedSessions(
                ctx.startMs,
                ctx.endMs,
                ctx.runId,
                ctx.chunkId,
                ctx.recordType,
            )
        val range = ctx.rangeOf(bounds) ?: return null
        daos.sleepSessionDao.deleteStagesOfUnstagedSessions(
            ctx.startMs,
            ctx.endMs,
            ctx.runId,
            ctx.chunkId,
            ctx.recordType,
        )
        daos.sleepSessionDao.deleteSessionsNotStaged(
            ctx.startMs,
            ctx.endMs,
            ctx.runId,
            ctx.chunkId,
            ctx.recordType,
        )
        return range
    }

    private suspend fun reconcileHeartSource(
        daos: HealthRecordDaos,
        recordType: String,
        ctx: StagedScanContext,
    ): ScoreInvalidation.AffectedRange? {
        var afterRef = Long.MIN_VALUE
        var minMs: Long? = null
        var maxMs: Long? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val page =
                daos.sourceRecordDao.pageUnstagedAuthoritativeSources(
                    recordType = recordType,
                    windowStartMs = ctx.startMs,
                    windowEndMs = ctx.endMs,
                    runId = ctx.runId,
                    chunkId = ctx.chunkId,
                    scanType = ctx.recordType,
                    afterRef = afterRef,
                    limit = SOURCE_PAGE_SIZE,
                )
            if (page.isEmpty()) break
            for (source in page) {
                val startMs = source.recordStartMs ?: source.createdAtMs
                val endMs = source.recordEndExclusiveMs?.minus(1L) ?: source.createdAtMs
                minMs = minOf(minMs ?: startMs, startMs)
                maxMs = maxOf(maxMs ?: endMs, endMs)
                // Raw children cascade via the heart_rate_records/hrv_records FK; contributions are
                // removed first inside deleteBySourceRecordId (ON DELETE RESTRICT).
                daos.sourceRecordDao.deleteBySourceRecordId(source.sourceRecordId)
            }
            // Deletion removes the rows this predicate matched, so the keyset advances on the last
            // id seen rather than restarting from MIN_VALUE.
            afterRef = page.last().id
            yield()
        }
        return ctx.rangeOf(StagedDeletionBounds(minMs, maxMs))
    }

    // reconcileExercise / reconcileSteps / reconcileVitals follow reconcileSleep exactly: bounds
    // aggregate -> null if empty -> child delete (route points for workouts, none for steps and
    // vitals) -> parent anti-join delete -> return range.
}

private class StagedScanContext(
    scan: CompleteTypeScan,
    private val zoneId: ZoneId,
) {
    val startMs = scan.windowStartMs
    val endMs = scan.windowEndExclusiveMs
    val runId = scan.scan.runId
    val chunkId = scan.scan.chunkId
    val recordType = scan.type.name

    fun rangeOf(bounds: StagedDeletionBounds): ScoreInvalidation.AffectedRange? {
        val minMs = bounds.minMs ?: return null
        val maxMs = bounds.maxMs ?: return null
        return ScoreInvalidation.AffectedRange(
            start = Instant.ofEpochMilli(minMs).atZone(zoneId).toLocalDate(),
            endInclusive = Instant.ofEpochMilli(maxMs).atZone(zoneId).toLocalDate(),
        )
    }
}
```

Fill in the three commented reconcilers concretely (no placeholders in the final code): `reconcileExercise` uses `daos.workoutDao.boundsOfUnstagedWorkouts` → `deleteRoutePointsOfUnstagedWorkouts` → `deleteWorkoutsNotStaged`; `reconcileSteps` uses `daos.stepRecordDao.boundsOfUnstagedRecords` → `deleteRecordsNotStaged`; `reconcileVitals` switches on the type to the matching DAO pair exactly as `HealthRecordDeletionReconciler.reconcileVitals` switches today.

- [ ] **Step 6: Delete the old reconciler and rewire the store**

In `RoomHealthIngestionStore.kt`: add `private val scanStagingDao: ScanStagingDao` to the constructor (after `vo2MaxRecordDao`, default-free — it is Hilt-provided), replace the body of `reconcileWindow` with

```kotlin
        override suspend fun reconcileWindow(
            scan: CompleteTypeScan,
            zoneId: ZoneId,
        ): ScoreInvalidation.AffectedRange? =
            transactionRunner.runInTransaction {
                StagedDeletionReconciler.reconcile(
                    daos = daos,
                    vo2MaxRecordDao = vo2MaxRecordDao,
                    scanStagingDao = scanStagingDao,
                    scan = scan,
                    zoneId = zoneId,
                )
            }
```

and delete `internal object HealthRecordDeletionReconciler` plus its `ReconcileContext` and `toAffectedRange` helpers from that file (this also brings the file back under the 400-line target).

- [ ] **Step 7: Migrate the existing test construction sites**

In each of the four test files, replace `CompleteTypeScan(type, start, end, "", setOf("s2"))` with a `CompleteTypeScan(type, start, end, "", scanId)` plus a `stage(type, listOf("s2"), complete = true)` helper call, reusing the `stage(...)` helper shape from Step 1. Keep every existing assertion unchanged — the point is that behaviour is preserved, not the call shape.

- [ ] **Step 8: Run the module suites**

```bash
./gradlew :core:model:testDebugUnitTest
./gradlew :core:database:testDebugUnitTest
./gradlew :core:healthconnect:testDebugUnitTest
```

Expected: PASS. `core:healthconnect` will not compile until Task 4 changes `collectCompleteTypeScans`; if so, do Step 8's `core:healthconnect` run at the end of Task 4 and note it in the commit body.

- [ ] **Step 9: Update DATA_FLOW and commit**

`internal-docs/DATA_FLOW.md` §1.2 ("Sync engine — orchestration, chunking, idempotency"): replace the description of in-memory seen-ID reconciliation with the staging + anti-join contract, and state explicitly that a scan which is not `COMPLETE` prunes nothing.

```bash
codegraph index
./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest
rtk git add -A && rtk git commit -m "refactor(sync): reconcile deletions by staged-id anti-join instead of heap sets"
```

---

## Task 4: Stage scan identities from the coordinator; stop holding them on the heap

**Files:**
- Create: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/ScanIdentities.kt`
- Create: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HeartSampleStreamer.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HealthIngestionCoordinator.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/DailySyncUseCase.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HistoricalIngestPhase.kt`
- Test: `core/healthconnect/src/test/kotlin/app/readylytics/health/core/healthconnect/domain/sync/ScanStagingIngestionTest.kt`
- Test (extend): `core/healthconnect/src/test/kotlin/app/readylytics/health/core/healthconnect/domain/sync/PagedIngestResumptionTest.kt`

**Interfaces:**
- Consumes: `ScanStagingStore`, `ScanIdentity`, `TypeScanState` (Task 2); `CompleteTypeScan(… , scan)` (Task 3).
- Produces:
  - `ScanIdentities.daily(windowStart: Instant): ScanIdentity` → `ScanIdentity(runId = "DAILY_SYNC", chunkId = windowStart.toEpochMilli().toString())`
  - `ScanIdentities.historical(runId: String, chunkStart: LocalDate): ScanIdentity` → `ScanIdentity(runId, chunkStart.toEpochDay().toString())`
  - `HealthIngestionCoordinator.ingestWindow(…, scanIdentity: ScanIdentity, resumeStagedScan: Boolean = false, …): IngestionWindowResult` — new required parameter, `hrStartPageToken`/`hrvStartPageToken` now actually forwarded.
  - `HeartSampleStreamer.streamHeartRate(...)`/`streamHrv(...)`: `suspend fun (params, sessionContext, device, onPageDone) -> ReadOutcome<Unit>` — page ids go straight to staging; nothing per-page is retained.

- [ ] **Step 1: Write the failing staging test**

`ScanStagingIngestionTest.kt` (use the existing fakes in `core/healthconnect/src/test` — `FakeHealthConnectRepository`-style doubles already exist for `DailySyncUseCaseTest`/`PagedIngestResumptionTest`; reuse them rather than writing new ones):

```kotlin
package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.ScanIdentity
import app.readylytics.health.core.model.domain.sync.TypeScanState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ScanStagingIngestionTest {
    private val staging = FakeScanStagingStore()

    @Test
    fun everyHeartRatePageIdIsStagedAndTheTypeIsMarkedComplete() =
        runTest {
            val repo = FakeHealthConnectRepository(hrPages = listOf(hrPage("a", 3), hrPage("b", 3)))
            val coordinator = coordinator(repo)
            val scanId = ScanIdentity("run-a", "0")

            coordinator.ingestWindow(
                windowStart = Instant.ofEpochMilli(0),
                windowEnd = Instant.ofEpochMilli(86_400_000),
                prefs = prefs(),
                scanIdentity = scanId,
            )

            assertEquals(setOf("a", "b"), staging.stagedIds(scanId, HealthDataType.HEART_RATE))
            assertEquals(TypeScanState.COMPLETE, staging.stateOf(scanId, HealthDataType.HEART_RATE))
        }

    @Test
    fun deniedTypeIsNeverMarkedCompleteAndStagesNothing() =
        runTest {
            val repo = FakeHealthConnectRepository(hrOutcome = ReadOutcomeKind.DENIED)
            val coordinator = coordinator(repo)
            val scanId = ScanIdentity("run-a", "0")

            coordinator.ingestWindow(
                windowStart = Instant.ofEpochMilli(0),
                windowEnd = Instant.ofEpochMilli(86_400_000),
                prefs = prefs(),
                scanIdentity = scanId,
            )

            assertTrue(staging.stagedIds(scanId, HealthDataType.HEART_RATE).isEmpty())
            assertEquals(TypeScanState.SCANNING, staging.stateOf(scanId, HealthDataType.HEART_RATE))
        }

    @Test
    fun cancelledPageStreamLeavesScanIncompleteSoNothingIsPruned() =
        runTest {
            val repo = FakeHealthConnectRepository(hrPages = listOf(hrPage("a", 3)), failOnPage = 1)
            val coordinator = coordinator(repo)
            val scanId = ScanIdentity("run-a", "0")

            runCatching {
                coordinator.ingestWindow(
                    windowStart = Instant.ofEpochMilli(0),
                    windowEnd = Instant.ofEpochMilli(86_400_000),
                    prefs = prefs(),
                    scanIdentity = scanId,
                )
            }

            assertEquals(TypeScanState.SCANNING, staging.stateOf(scanId, HealthDataType.HEART_RATE))
        }

    @Test
    fun resumingKeepsPreviouslyStagedIdsAndForwardsTheStartToken() =
        runTest {
            val repo = FakeHealthConnectRepository(hrPages = listOf(hrPage("b", 3)))
            val coordinator = coordinator(repo)
            val scanId = ScanIdentity("run-a", "0")
            staging.beginTypeScan(scanId, HealthDataType.HEART_RATE, resume = false)
            staging.stageIds(scanId, HealthDataType.HEART_RATE, listOf("a"))

            coordinator.ingestWindow(
                windowStart = Instant.ofEpochMilli(0),
                windowEnd = Instant.ofEpochMilli(86_400_000),
                prefs = prefs(),
                scanIdentity = scanId,
                resumeStagedScan = true,
                hrStartPageToken = "token-1",
            )

            assertEquals(setOf("a", "b"), staging.stagedIds(scanId, HealthDataType.HEART_RATE))
            assertEquals("token-1", repo.observedHrStartToken)
        }
}
```

`FakeScanStagingStore` is a new in-memory `ScanStagingStore` in the same test source set (a `MutableMap<Triple<String, String, String>, MutableSet<String>>` plus a state map); it exposes `stagedIds(scan, type): Set<String>` for assertions. Extend the existing HC repository fake with `observedHrStartToken` and `failOnPage`.

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew :core:healthconnect:testDebugUnitTest --tests "app.readylytics.health.core.healthconnect.domain.sync.ScanStagingIngestionTest"
```

Expected: FAIL — `ingestWindow` has no `scanIdentity`/`resumeStagedScan` parameter.

- [ ] **Step 3: Add `ScanIdentities`**

```kotlin
package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.sync.ScanIdentity
import java.time.Instant
import java.time.LocalDate

/**
 * WP-18 scan identity factories. The daily flow is a single logical run whose chunk is the refreshed
 * window; a historical resync uses its immutable [HistoricalRunIdentity.runId] and the chunk's start
 * epoch day, so a resumed chunk reopens exactly the staging its interrupted predecessor wrote and a
 * new run can never inherit an old one's staged identities.
 */
object ScanIdentities {
    const val DAILY_RUN_ID: String = "DAILY_SYNC"

    fun daily(windowStart: Instant): ScanIdentity =
        ScanIdentity(runId = DAILY_RUN_ID, chunkId = windowStart.toEpochMilli().toString())

    fun historical(
        runId: String,
        chunkStart: LocalDate,
    ): ScanIdentity = ScanIdentity(runId = runId, chunkId = chunkStart.toEpochDay().toString())
}
```

- [ ] **Step 4: Extract `HeartSampleStreamer` and stage per page**

Move the two file-private `streamHeartRateSamples`/`streamHrvSamples` functions out of `HealthIngestionCoordinator.kt` into `HeartSampleStreamer.kt`, changing three things: (1) the `mutableSetOf<String>()` accumulators are gone — each page calls `staging.stageIds(scan, type, page.map { it.id })` before persisting; (2) the return type is `ReadOutcome<Unit>` (the outcome of the read, not a set); (3) `startPageToken` is taken from `params` instead of being discarded.

```kotlin
internal class HeartSampleStreamer(
    private val hcRepo: HealthConnectRepository,
    private val healthIngestionStore: HealthIngestionStore,
    private val staging: ScanStagingStore,
) {
    suspend fun streamHeartRate(
        params: IngestWindowParams,
        sessionContext: IngestionSessionContext,
        device: String?,
        onPageDone: () -> Unit,
    ): ReadOutcome<Unit> {
        staging.beginTypeScan(params.scanIdentity, HealthDataType.HEART_RATE, params.resumeStagedScan)
        var sampleCount = 0
        val outcome =
            hcRepo.readHeartRateSamplesPaged(
                from = params.windowStart,
                to = params.windowEnd,
                startPageToken = params.hrStartPageToken,
            ) { page, nextToken ->
                // Staged before the write: a crash between staging and persisting re-reads the page
                // and re-stages the same ids idempotently, whereas the reverse order could mark a
                // persisted id unseen and delete it on the next reconcile.
                staging.stageIds(params.scanIdentity, HealthDataType.HEART_RATE, page.map { it.id })
                sampleCount += persistPage(page, sessionContext, device)
                onPageDone()
                params.onTokenUpdated?.invoke(nextToken, null)
            }
        if (outcome is ReadOutcome.Available) {
            staging.markTypeScanComplete(params.scanIdentity, HealthDataType.HEART_RATE)
        }
        logD("HealthSync.Ingest") { "HR samples persisted: $sampleCount" }
        return outcome
    }
    // streamHrv mirrors this with HrvMapper, HealthDataType.HRV and params.hrvStartPageToken.
}
```

`persistPage` is where Task 6 will insert the bounded transform slice; for now it is the existing map → device-filter → `replaceHeartRateSources` body, returning the persisted row count.

- [ ] **Step 5: Rework the coordinator's scan collection**

`IngestWindowParams` gains `val scanIdentity: ScanIdentity` and `val resumeStagedScan: Boolean`. `streamAndPersistHeartSamples` returns nothing (the outcomes go to staging state). `collectCompleteTypeScans` becomes a staging writer: for every bulk type, stage the ids and mark complete when the read was `Available`, then emit a `CompleteTypeScan` referencing the identity:

```kotlin
        private suspend fun stageBulkScans(
            params: IngestWindowParams,
            raw: RawBulkRecords,
        ): List<CompleteTypeScan> {
            val startMs = params.windowStart.toEpochMilli()
            val endExclusiveMs = params.windowEnd.toEpochMilli()
            fun deviceFor(type: HealthDataType) = params.prefs.deviceByDataType[type.name].orEmpty()

            suspend fun MutableList<CompleteTypeScan>.stage(
                outcome: ReadOutcome<List<String>>,
                type: HealthDataType,
            ) {
                staging.beginTypeScan(params.scanIdentity, type, resume = false)
                if (outcome !is ReadOutcome.Available) return
                staging.stageIds(params.scanIdentity, type, outcome.data)
                staging.markTypeScanComplete(params.scanIdentity, type)
                add(
                    CompleteTypeScan(
                        type = type,
                        windowStartMs = startMs,
                        windowEndExclusiveMs = endExclusiveMs,
                        sourceSelectionId = deviceFor(type),
                        scan = params.scanIdentity,
                    ),
                )
            }

            return buildList {
                stage(raw.sleepSessions.toIds { it.id }, HealthDataType.SLEEP)
                stage(raw.exerciseRecords.toIds { it.id }, HealthDataType.EXERCISE)
                stage(raw.stepsRecords.toIds { it.id }, HealthDataType.STEPS)
                // DB-001: vitals rows are keyed "<hcId>_<timestampMs>", so the staged identity is
                // that persisted row id -- no substringBefore('_') parsing anywhere, and a record
                // whose timestamp moved converges instead of leaving a stale duplicate behind.
                stage(raw.weightRecords.toIds { "${it.id}_${it.time.toEpochMilli()}" }, HealthDataType.WEIGHT)
                stage(raw.bodyFatRecords.toIds { "${it.id}_${it.time.toEpochMilli()}" }, HealthDataType.BODY_FAT)
                stage(
                    raw.bloodPressureRecords.toIds { "${it.id}_${it.time.toEpochMilli()}" },
                    HealthDataType.BLOOD_PRESSURE,
                )
                stage(raw.spo2Records.toIds { "${it.id}_${it.time.toEpochMilli()}" }, HealthDataType.OXYGEN_SATURATION)
                stage(
                    raw.bodyTemperatureRecords.toIds { "${it.id}_${it.time.toEpochMilli()}" },
                    HealthDataType.BODY_TEMPERATURE,
                )
                stage(raw.vo2MaxRecords.toIds { it.id }, HealthDataType.VO2_MAX)
                addHeartScans(params, startMs, endExclusiveMs, ::deviceFor)
            }
        }
```

`addHeartScans` emits `CompleteTypeScan`s for `HEART_RATE`/`HRV` only when `staging.stateOf(params.scanIdentity, type) == TypeScanState.COMPLETE`. `toIds` changes from `ReadOutcome<List<T>> -> ReadOutcome<Set<String>>` to `ReadOutcome<List<T>> -> ReadOutcome<List<String>>` (no set, nothing deduplicated in memory — the staging PK deduplicates). `IngestionWindowResult.completedTypes` is now `scans.mapTo(HashSet()) { it.type }` as before.

- [ ] **Step 6: Thread the identity through both flows**

`DailySyncUseCase.ingestSegment`: `scanIdentity = ScanIdentities.daily(startMs)`, `resumeStagedScan = false` (a daily refresh always rescans its own single window). Both `ingestWindow` calls in that method pass it.

`HistoricalIngestPhase.processChunk`:

```kotlin
            val scanIdentity = ScanIdentities.historical(context.runIdentity.runId, chunkStart)
            val resumingThisChunk =
                context.checkpoint?.phase == ResyncPhase.INGEST &&
                    context.checkpoint.nextDate == chunkStart &&
                    context.checkpoint.runIdentity?.runId == context.runIdentity.runId
            val ingestResult =
                ingestion.ingestionCoordinator.ingestWindow(
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    prefs = context.prefs,
                    scanIdentity = scanIdentity,
                    resumeStagedScan = resumingThisChunk,
                    hrStartPageToken = if (resumingThisChunk) context.checkpoint?.hrPageToken else null,
                    hrvStartPageToken = if (resumingThisChunk) context.checkpoint?.hrvPageToken else null,
                    onTokenUpdated = { hrToken, hrvToken -> saveChunkProgress(...) },
                    reconcileDeletions = !context.skipIngestAndPrune,
                )
```

and `clearInterruptedTokensIfNeeded` is deleted: with staging, a resumed page token is safe to use (HC-002's remediation), so the WP-06 "clear tokens and replay the whole chunk" fallback is no longer the default. Keep the fallback reachable: if the provider rejects a stored token, the paged read throws, `retryWithBackoff` gives up, and the chunk is retried from `startPageToken = null` with `resume = false` — add that catch explicitly in `processChunk` (HC-005: "treat rejected provider page tokens as a reason to replay the incomplete scan, not as proof of completion").

After a chunk succeeds, purge its staging: `staging.clearTypeScan(scanIdentity, type)` for every type of that chunk, driven from `HistoricalIngestPhase` after `saveChunkCompleted`. At run start, `staging.clearRunsOtherThan(context.runIdentity.runId)` drops abandoned generations.

- [ ] **Step 7: Run the HC suites**

```bash
./gradlew :core:healthconnect:testDebugUnitTest --tests "app.readylytics.health.core.healthconnect.domain.sync.ScanStagingIngestionTest"
./gradlew :core:healthconnect:testDebugUnitTest
./gradlew :core:database:testDebugUnitTest
```

Expected: PASS, including the pre-existing `PagedIngestResumptionTest`, `ResyncDeletionConvergenceTest`, `ResyncCheckpointResumeTest`. Extend `PagedIngestResumptionTest` with one case: a kill after page 1 of HR followed by a resume that supplies the stored token must end with the same rows and the same deletions as an uninterrupted run, and with a pre-baseline deletion still removed.

- [ ] **Step 8: Update DATA_FLOW and commit**

`internal-docs/DATA_FLOW.md` §1.1 (paginated fetch) and §1.2: document that page tokens are now resumed, that staging is what makes that safe, and that a rejected token replays the chunk from scratch.

```bash
codegraph index
./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest
rtk git add -A && rtk git commit -m "feat(sync): stage scanned record ids per page and resume page tokens safely"
```

---

## Task 5: Bulk source resolution, one transaction per page

**Files:**
- Create: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/SourceRefResolver.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/SourcePayloadWriter.kt`
- Modify: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/SourceRecordDao.kt` (bulk lookup + bulk insert-ignore)
- Test: `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/SourcePayloadWriterBatchingTest.kt`

**Interfaces:**
- Consumes: `SourcePayload`/`SourceMetadata` (existing), `TransactionRunner` (existing).
- Produces:
  - `SourceRecordDao.getSourcesByRecordIds(ids: List<String>): List<HealthSourceRecordEntity>` (chunked by the caller at 250 ids)
  - `SourceRecordDao.insertIgnoreAll(entities: List<HealthSourceRecordEntity>)`
  - `SourceRefResolver.resolveAll(dao: SourceRecordDao, sources: List<SourceMetadata>): Map<String, ResolvedSource>` where `data class ResolvedSource(val ref: Long, val existing: HealthSourceRecordEntity?)`
  - `SourcePayloadWriter.SOURCE_LOOKUP_CHUNK = 250`, `SourcePayloadWriter.PAGE_TRANSACTION_MAX_ROWS = 5_000`

- [ ] **Step 1: Write the failing batching test**

```kotlin
package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.SourceMetadata
import app.readylytics.health.core.model.domain.sync.SourcePayload
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SourcePayloadWriterBatchingTest {
    private lateinit var database: HealthDatabase
    private lateinit var runner: RecordingTransactionRunner
    private lateinit var writer: SourcePayloadWriter

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        runner = RecordingTransactionRunner(RoomTransactionRunner(database))
        writer = SourcePayloadWriter(daos = database.healthRecordDaos(), transactionRunner = runner)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun onePageOfManyParentsUsesOneTransaction() =
        runBlocking {
            val payloads = (1..600).map { payload("hc-$it", it.toLong()) }

            writer.replaceHeartRateSources(payloads)

            assertEquals(1, runner.transactionCount)
            assertEquals(600, database.sourceRecordDao().count())
            assertEquals(600, database.heartRateDao().count())
        }

    @Test
    fun aPageLargerThanTheRowBudgetSplitsIntoBoundedTransactions() =
        runBlocking {
            // 20 parents x 400 samples = 8_000 rows, over PAGE_TRANSACTION_MAX_ROWS (5_000).
            val payloads = (1..20).map { parent -> payload("hc-$parent", parent.toLong(), sampleCount = 400) }

            writer.replaceHeartRateSources(payloads)

            assertEquals(2, runner.transactionCount)
            assertEquals(8_000, database.heartRateDao().count())
        }

    @Test
    fun identicalReimportChangesNoRowsAndOpensNoWriteForUnchangedSources() =
        runBlocking {
            val payloads = (1..50).map { payload("hc-$it", it.toLong()) }
            writer.replaceHeartRateSources(payloads)
            val revisionsBefore =
                database.sourceRecordDao().pageAfter(0L, 100).associate { it.sourceRecordId to it.sourceRevision }
            runner.reset()

            writer.replaceHeartRateSources(payloads)

            val revisionsAfter =
                database.sourceRecordDao().pageAfter(0L, 100).associate { it.sourceRecordId to it.sourceRevision }
            assertEquals(revisionsBefore, revisionsAfter)
            assertTrue("identical replay must not open a per-parent transaction", runner.transactionCount <= 1)
        }

    private fun payload(
        sourceId: String,
        index: Long,
        sampleCount: Int = 3,
    ): SourcePayload<HeartRateInput> {
        val startMs = index * 600_000L
        val rows =
            (0 until sampleCount).map { i ->
                HeartRateInput(
                    id = "${sourceId}_${startMs + i * 1_000L}",
                    timestampMs = startMs + i * 1_000L,
                    beatsPerMinute = 60 + (i % 20),
                    recordType = "RESTING",
                    sessionId = null,
                    deviceName = "watch",
                )
            }
        return SourcePayload(
            source =
                SourceMetadata(
                    sourceId = sourceId,
                    recordType = "HEART_RATE",
                    originPackage = "com.example.provider",
                    startMs = startMs,
                    endExclusiveMs = startMs + sampleCount * 1_000L,
                    lastModifiedMs = null,
                ),
            rows = rows,
        )
    }
}
```

`RecordingTransactionRunner` already exists in `core/healthconnect/src/test`; add an equivalent to `core/database/src/test` (or move the existing one into a shared test fixture) exposing `transactionCount` and `reset()`.

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.core.database.data.local.SourcePayloadWriterBatchingTest"
```

Expected: FAIL — `onePageOfManyParentsUsesOneTransaction` reports 600 transactions (today's per-source loop).

- [ ] **Step 3: Add the bulk DAO queries**

```kotlin
    @Query("SELECT * FROM health_source_records WHERE sourceRecordId IN (:sourceRecordIds)")
    suspend fun getSourcesByRecordIds(sourceRecordIds: List<String>): List<HealthSourceRecordEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(entities: List<HealthSourceRecordEntity>)
```

- [ ] **Step 4: Add `SourceRefResolver`**

```kotlin
package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.SourceRecordDao
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.model.domain.sync.SourceMetadata

internal data class ResolvedSource(
    val ref: Long,
    val existing: HealthSourceRecordEntity?,
)

/**
 * PERF-001: resolves every parent identity of one Health Connect page in two statements per 250-id
 * chunk (one `IN` lookup, one `INSERT OR IGNORE` of the missing ones) instead of the previous
 * `getOrCreateSourceRef` round trip per parent — which cost roughly one million transactions for a
 * million-parent history. Chunk width keeps the bind-variable count an order of magnitude under the
 * 999 floor.
 */
internal object SourceRefResolver {
    const val LOOKUP_CHUNK = 250

    suspend fun resolveAll(
        dao: SourceRecordDao,
        sources: List<SourceMetadata>,
    ): Map<String, ResolvedSource> {
        if (sources.isEmpty()) return emptyMap()
        val bySourceId = sources.associateBy { it.sourceId }
        val existing = LinkedHashMap<String, HealthSourceRecordEntity>(bySourceId.size)
        bySourceId.keys.chunked(LOOKUP_CHUNK).forEach { chunk ->
            dao.getSourcesByRecordIds(chunk).forEach { existing[it.sourceRecordId] = it }
        }

        val missing = bySourceId.values.filter { it.sourceId !in existing }
        if (missing.isNotEmpty()) {
            missing.chunked(LOOKUP_CHUNK).forEach { chunk ->
                dao.insertIgnoreAll(chunk.map { it.toNewEntity() })
            }
            missing.map { it.sourceId }.chunked(LOOKUP_CHUNK).forEach { chunk ->
                dao.getSourcesByRecordIds(chunk).forEach { inserted ->
                    // Newly created rows have no prior revision/bounds, so `existing` stays null for
                    // them: the caller must treat them as changed and write authoritative metadata.
                    existing[inserted.sourceRecordId] = inserted
                }
            }
        }

        return bySourceId.mapValues { (sourceId, _) ->
            val row = existing[sourceId] ?: error("Failed to resolve source ref for $sourceId")
            ResolvedSource(ref = row.id, existing = if (missing.any { it.sourceId == sourceId }) null else row)
        }
    }

    private fun SourceMetadata.toNewEntity() =
        HealthSourceRecordEntity(
            sourceRecordId = sourceId,
            recordType = recordType,
            createdAtMs = startMs,
            originPackage = originPackage,
            recordStartMs = startMs,
            recordEndExclusiveMs = endExclusiveMs,
            lastModifiedMs = lastModifiedMs,
            metadataState = "AUTHORITATIVE",
            sourceRevision = 0L,
        )
}
```

- [ ] **Step 5: Rewrite the writer's transaction boundary**

`SourcePayloadWriter.replaceHeartRateSources`/`replaceHrvSources` change from "one transaction per source" to "one transaction per bounded row group":

```kotlin
        suspend fun replaceHeartRateSources(sources: List<SourcePayload<HeartRateInput>>) {
            if (sources.isEmpty()) return
            sources.groupedByRowBudget(PAGE_TRANSACTION_MAX_ROWS).forEach { group ->
                currentCoroutineContext().ensureActive()
                transactionRunner.runInTransaction {
                    val resolved = SourceRefResolver.resolveAll(daos.sourceRecordDao, group.map { it.source })
                    group.forEach { payload ->
                        replaceSingleHeartRateSource(payload, resolved.getValue(payload.source.sourceId))
                    }
                }
                yield()
            }
        }
```

`replaceSingleHeartRateSource(payload, resolved)` loses its own `resolveOrCreateSource` call and uses `resolved.ref`/`resolved.existing`; everything else in it — the identical-payload short circuit, `deleteMissingRows`, `updateSourceMetadata`, `recordDirtyRange`, `warmRefresh.publish` — stays byte-for-byte as today. `groupedByRowBudget` is a private extension that accumulates payloads until `rows.size` crosses the budget, always emitting at least one payload per group (a single source larger than the budget stays whole — the SDK-owned single-record payload is the irreducible bound the spec names).

- [ ] **Step 6: Run the test and the module**

```bash
./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.core.database.data.local.SourcePayloadWriterBatchingTest"
./gradlew :core:database:testDebugUnitTest
```

Expected: PASS, with `PersistenceBatchingTest`, `ConflictTargetedUpsertTest`, `DeleteBySourceRecordIdTest` unchanged.

- [ ] **Step 7: Update DATA_FLOW and commit**

§1.4: state that source refs for a page resolve in bulk inside the same transaction as that page's sample writes, and that transaction count scales with row batches, never with parent count.

```bash
codegraph index
./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest
rtk git add -A && rtk git commit -m "perf(db): resolve page source refs in bulk under one sample transaction"
```

---

## Task 6: Cap the transform buffer by sample count

**Files:**
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HeartSampleStreamer.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/SyncConstants.kt` (add the budget constant)
- Test: `core/healthconnect/src/test/kotlin/app/readylytics/health/core/healthconnect/domain/sync/TransformBufferBoundTest.kt`

**Interfaces:**
- Consumes: `HeartSampleStreamer` (Task 4), `HeartRateMapper.mapToInputs`, `HrvMapper.mapToInputs` (unchanged — the mappers stay pure and page-agnostic).
- Produces: `SyncConstants.TRANSFORM_SAMPLE_BUDGET = 5_000`; `HeartSampleStreamer.persistPage(...)` slices a page into sub-lists whose *nested sample* total stays at or under that budget before calling the mapper.

- [ ] **Step 1: Write the failing bound test**

```kotlin
package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.sync.ScanIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TransformBufferBoundTest {
    @Test
    fun aDenseNestedPageIsMappedInBoundedSlices() =
        runTest {
            // One page, 40 parents x 500 nested samples = 20_000 samples, budget 5_000.
            val repo = FakeHealthConnectRepository(hrPages = listOf(densePage(parents = 40, samplesEach = 500)))
            val store = RecordingIngestionStore()
            val coordinator = coordinator(repo, store)

            coordinator.ingestWindow(
                windowStart = Instant.ofEpochMilli(0),
                windowEnd = Instant.ofEpochMilli(86_400_000),
                prefs = prefs(),
                scanIdentity = ScanIdentity("run-a", "0"),
            )

            assertEquals(20_000, store.persistedRowCount)
            assertTrue(
                "largest mapped slice ${store.maxSliceSampleCount} must stay within the budget",
                store.maxSliceSampleCount <= SyncConstants.TRANSFORM_SAMPLE_BUDGET,
            )
        }

    @Test
    fun oneParentLargerThanTheBudgetIsStillPersistedWhole() =
        runTest {
            val repo = FakeHealthConnectRepository(hrPages = listOf(densePage(parents = 1, samplesEach = 12_000)))
            val store = RecordingIngestionStore()
            val coordinator = coordinator(repo, store)

            coordinator.ingestWindow(
                windowStart = Instant.ofEpochMilli(0),
                windowEnd = Instant.ofEpochMilli(86_400_000),
                prefs = prefs(),
                scanIdentity = ScanIdentity("run-a", "0"),
            )

            assertEquals(12_000, store.persistedRowCount)
            assertEquals(1, store.sliceCount)
        }
}
```

`RecordingIngestionStore` is a `HealthIngestionStore` test double recording, per `replaceHeartRateSources` call, the total row count of that call (`maxSliceSampleCount`, `sliceCount`, `persistedRowCount`).

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew :core:healthconnect:testDebugUnitTest --tests "app.readylytics.health.core.healthconnect.domain.sync.TransformBufferBoundTest"
```

Expected: FAIL — one slice of 20_000, because the whole page is mapped at once.

- [ ] **Step 3: Slice the page before mapping**

```kotlin
    private suspend fun persistPage(
        page: List<DomainHeartRateRecord>,
        sessionContext: IngestionSessionContext,
        device: String?,
    ): Int {
        var persisted = 0
        // PERF-001: a Health Connect page bounds parent cardinality, not nested sample count. The
        // transform buffer is therefore capped on samples: each slice's mapped payloads, the
        // distinct-timestamp link table HeartRateMapper builds, and the Room write it feeds all stay
        // proportional to TRANSFORM_SAMPLE_BUDGET instead of to the page's density. A single parent
        // whose payload already exceeds the budget forms its own slice -- the SDK owns that record
        // and there is no smaller unit to read.
        page.sliceBySampleBudget(SyncConstants.TRANSFORM_SAMPLE_BUDGET) { slice ->
            val mapped =
                HeartRateMapper.mapToInputs(slice, sessionContext.sleepInputs, sessionContext.workoutInputs)
            val filtered =
                mapped.map { source ->
                    source.copy(rows = DeviceSourceFilter.filterToDevice(source.rows, device) { it.deviceName })
                }
            healthIngestionStore.replaceHeartRateSources(filtered)
            persisted += filtered.sumOf { it.rows.size }
        }
        return persisted
    }
```

```kotlin
internal suspend fun <T> List<T>.sliceBySampleBudget(
    budget: Int,
    sampleCountOf: (T) -> Int,
    action: suspend (List<T>) -> Unit,
) {
    require(budget > 0) { "budget must be positive" }
    var start = 0
    while (start < size) {
        currentCoroutineContext().ensureActive()
        var end = start
        var samples = 0
        while (end < size && (end == start || samples + sampleCountOf(this[end]) <= budget)) {
            samples += sampleCountOf(this[end])
            end++
        }
        action(subList(start, end))
        start = end
        yield()
    }
}
```

The HR call passes `sampleCountOf = { it.samples.size }`; the HRV path passes `{ 1 }` (one RMSSD value per record), so HRV slicing is parent-count bounded by the same constant.

- [ ] **Step 4: Run it, then the module**

```bash
./gradlew :core:healthconnect:testDebugUnitTest --tests "app.readylytics.health.core.healthconnect.domain.sync.TransformBufferBoundTest"
./gradlew :core:healthconnect:testDebugUnitTest
```

Expected: PASS. `HealthIngestionCoordinatorVo2MaxTest` and `HealthIngestionCoordinatorTimeoutTest` must stay green — slicing must not change what gets persisted, only in how many calls.

- [ ] **Step 5: Update DATA_FLOW and commit**

§1.3 (Mappers): note that mappers are invoked per bounded sample slice and remain pure, and that the mapper's distinct-timestamp link table is therefore slice-bounded.

```bash
./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest
rtk git add -A && rtk git commit -m "perf(sync): cap HR/HRV transform buffers by sample count per page"
```

---

## Task 7: One bounded read-retry budget

**Files:**
- Create: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/ReadRetryBudget.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/RetryWithBackoff.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HealthIngestionCoordinator.kt` (bulk reads take the budget)
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HistoricalIngestPhase.kt` (stop wrapping `ingestWindow`)
- Test: `core/healthconnect/src/test/kotlin/app/readylytics/health/core/healthconnect/domain/sync/ReadRetryBudgetTest.kt`
- Test (extend): `core/healthconnect/src/test/kotlin/app/readylytics/health/core/healthconnect/domain/sync/RetryWithBackoffTest.kt`

**Interfaces:**
- Consumes: `HealthConnectRetryPolicy` (existing, unchanged).
- Produces:

```kotlin
internal class ReadRetryBudget(
    private val policy: HealthConnectRetryPolicy = HealthConnectRetryPolicy(),
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {
    val attemptsUsed: Int
    suspend fun <T> execute(label: String, block: suspend () -> T): T
}
```

`retryWithBackoff(budget: ReadRetryBudget? = null, …)` keeps its current signature for the callers that own an independent retry scope (the changes-token path in `HealthChangeSynchronizerImpl`), but delegates to `budget` when one is supplied.

**Ownership rule to encode (spec: "keeping Changes/page/window retry ownership explicit"):** one budget per ingest *window*, shared by that window's nine bulk reads and its HR/HRV page reads. The window itself is not retried by `HistoricalIngestPhase` any more; a window that exhausts its budget fails the chunk, and WorkManager's own EXPONENTIAL backoff owns the outer retry. The Changes-token flow keeps its own separate budget — it is a different read with a different failure meaning.

- [ ] **Step 1: Write the failing budget test**

```kotlin
package app.readylytics.health.core.healthconnect.domain.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ReadRetryBudgetTest {
    @Test
    fun attemptsAreSharedAcrossEveryReadInOneWindow() =
        runTest {
            val budget = ReadRetryBudget(delayFn = {})
            var firstReadCalls = 0
            var secondReadCalls = 0

            runCatching {
                budget.execute("read-1") {
                    firstReadCalls++
                    throw IOException("boom")
                }
            }
            assertThrows(IOException::class.java) {
                kotlinx.coroutines.runBlocking {
                    budget.execute("read-2") {
                        secondReadCalls++
                        throw IOException("boom")
                    }
                }
            }

            // 5 attempts total for the window, not 5 per read.
            assertEquals(5, firstReadCalls + secondReadCalls)
        }

    @Test
    fun aSuccessfulReadConsumesOnlyItsOwnFailedAttempts() =
        runTest {
            val budget = ReadRetryBudget(delayFn = {})
            var calls = 0

            val result =
                budget.execute("read-1") {
                    calls++
                    if (calls < 3) throw IOException("boom") else "ok"
                }

            assertEquals("ok", result)
            assertEquals(3, calls)
            assertEquals(2, budget.attemptsUsed)
        }

    @Test
    fun nonTransientFailuresAreNotRetried() =
        runTest {
            val budget = ReadRetryBudget(delayFn = {})
            var calls = 0

            assertThrows(IllegalStateException::class.java) {
                kotlinx.coroutines.runBlocking {
                    budget.execute("read-1") {
                        calls++
                        throw IllegalStateException("not transient")
                    }
                }
            }
            assertEquals(1, calls)
        }
}
```

Add one case to `RetryWithBackoffTest`: `retryWithBackoff` with a supplied budget must not exceed that budget across two nested invocations (the amplification regression).

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew :core:healthconnect:testDebugUnitTest --tests "app.readylytics.health.core.healthconnect.domain.sync.ReadRetryBudgetTest"
```

Expected: FAIL — `Unresolved reference: ReadRetryBudget`.

- [ ] **Step 3: Implement the budget**

```kotlin
package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.util.logD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * PERF-001/HC-005: one bounded retry budget for every Health Connect read of one ingest window.
 * Before this, `HistoricalIngestPhase` wrapped `ingestWindow` in `retryWithBackoff` while
 * `fetchBulkRecords` wrapped each of its nine reads in another, so a provider under quota pressure
 * could be hit up to `maxAttempts^2` times for one window. Attempts are counted per window and
 * shared by every read inside it; the outer retry belongs to WorkManager's EXPONENTIAL backoff,
 * which is durable, observable and already configured.
 */
internal class ReadRetryBudget(
    private val policy: HealthConnectRetryPolicy = HealthConnectRetryPolicy(),
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {
    var attemptsUsed: Int = 0
        private set

    suspend fun <T> execute(
        label: String,
        block: suspend () -> T,
    ): T {
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val attempt = attemptsUsed + 1
                if (!policy.shouldRetry(e, attempt)) throw e
                attemptsUsed = attempt
                val delayMs = policy.delayForAttempt(attempt)
                logD("ReadRetryBudget") { "$label failed (window attempt $attempt); backing off ${delayMs}ms" }
                delayFn(delayMs)
            }
        }
    }
}
```

`HealthConnectRetryPolicy.shouldRetry(throwable, attempt)` already stops at `attempt < maxAttempts`, so the shared counter caps the window at five transient attempts in total.

- [ ] **Step 4: Route the window's reads through one budget**

`IngestWindowParams` gains `val retryBudget: ReadRetryBudget`. `HealthIngestionCoordinator.ingestWindow` creates it (`ReadRetryBudget()`) unless a caller supplies one. In `fetchBulkRecords`, every `retryWithBackoff { … }` becomes `params.retryBudget.execute("sleepSessions") { … }` (one label per read). `HeartSampleStreamer` wraps its `readHeartRateSamplesPaged` call in `params.retryBudget.execute("hrPages") { … }`.

In `HistoricalIngestPhase.processChunk`, drop the `retryWithBackoff { … }` wrapper around `ingestWindow` entirely; the chunk-shrink-on-timeout branch and the rejected-token replay from Task 4 remain the only in-process recovery, and a genuine exhaustion propagates to `HealthResyncWorker` as `Result.retry()`.

- [ ] **Step 5: Run the tests**

```bash
./gradlew :core:healthconnect:testDebugUnitTest --tests "app.readylytics.health.core.healthconnect.domain.sync.ReadRetryBudgetTest"
./gradlew :core:healthconnect:testDebugUnitTest
./gradlew :app:testDebugUnitTest
```

Expected: PASS. `HealthConnectRetryPolicyTest` and `RetryWithBackoffTest` stay green; the resync worker tests in `:app` must still observe `Result.retry()` on a persistently failing window.

- [ ] **Step 6: Update DATA_FLOW and commit**

§1.1/§1.2: document the single window budget, that WorkManager owns the outer retry, and that the Changes-token path keeps an independent budget.

```bash
codegraph index
./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest
rtk git add -A && rtk git commit -m "fix(sync): share one bounded read-retry budget per ingest window"
```

---

## Task 8: Stream the rollup — keyset pages, bounded groups, no aggregation inside the writer

**Files:**
- Create: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/MinuteRollupStreamer.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/DataRollupManager.kt`
- Modify: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/HeartRateDao.kt` (keyset variant of the plausibility read)
- Test: `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/MinuteRollupStreamerTest.kt`
- Test (extend): `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/DataRollupManagerTest.kt`, `CompleteMinuteRollupTest.kt`, `DataRollupCoverageTest.kt`

**Interfaces:**
- Consumes: `MinuteCoveragePublisher.publish(MinutePublicationRequest)`, `MinutePublicationOutcome`, `SourceGenerationConflictException`, `aggregateIntoMinuteBuckets()`, `BpmHistogram`, `completeMinuteCutoff` (all existing).
- Produces:
  - `HeartRateDao.pagePlausibleSamplesForRollup(fromMs: Long, toMs: Long, afterTs: Long, afterRef: Long, limit: Int): List<HeartRateRecordEntity>`
  - `MinuteRollupStreamer.streamGroups(fromMs: Long, toMs: Long, pageSize: Int = SAMPLE_PAGE_SIZE, groupMinuteBudget: Int = GROUP_MINUTE_BUDGET, onGroup: suspend (RollupGroup) -> Unit)`
  - `data class RollupGroup(val samples: List<HeartRateRecordEntity>, val minuteStartMs: Long, val minuteEndExclusiveMs: Long)` — every minute in a group is *complete* (all of its samples are present), so bucket output is identical to a single-pass run.
  - `MinuteRollupStreamer.SAMPLE_PAGE_SIZE = 5_000`, `MinuteRollupStreamer.GROUP_MINUTE_BUDGET = 60`

**Invariant to preserve:** grouping is by `(bucketStartMs, recordType, sessionId, deviceName)` inside `aggregateIntoMinuteBuckets`, and coverage/contributions are grouped by `completeMinuteCutoff`. A group boundary must therefore fall on a minute boundary, never inside a minute — otherwise a bucket would be published from a partial sample set, which is exactly the DB-002 defect Phase 1 fixed. The streamer holds back the trailing minute of each page until it sees a sample from a later minute (or the range ends).

- [ ] **Step 1: Write the failing streamer test**

```kotlin
package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.dao.getOrCreateSourceRef
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MinuteRollupStreamerTest {
    private lateinit var database: HealthDatabase
    private lateinit var streamer: MinuteRollupStreamer

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        streamer = MinuteRollupStreamer(database.heartRateDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun noGroupEverSplitsAMinute() =
        runBlocking {
            val ref = database.sourceRecordDao().getOrCreateSourceRef("hc-1", "HEART_RATE", 0L)
            // 10 minutes x 60 samples = 600 samples; a 25-sample page deliberately lands mid-minute.
            val samples =
                (0 until 10).flatMap { minute ->
                    (0 until 60).map { second ->
                        hr(ref, minute * 60_000L + second * 1_000L, 60 + second % 20)
                    }
                }
            database.heartRateDao().upsertAll(samples)

            val seenMinutes = mutableListOf<Set<Long>>()
            var totalSamples = 0
            streamer.streamGroups(fromMs = 0L, toMs = 10 * 60_000L, pageSize = 25, groupMinuteBudget = 3) { group ->
                seenMinutes += group.samples.map { it.timestampMs / 60_000L * 60_000L }.toSet()
                totalSamples += group.samples.size
                group.samples.groupBy { it.timestampMs / 60_000L }.forEach { (_, minuteSamples) ->
                    assertEquals("every minute in a group must be complete", 60, minuteSamples.size)
                }
            }

            assertEquals(600, totalSamples)
            val flattened = seenMinutes.flatten()
            assertEquals("no minute may appear in two groups", flattened.size, flattened.toSet().size)
        }

    @Test
    fun groupsRespectTheMinuteBudget() =
        runBlocking {
            val ref = database.sourceRecordDao().getOrCreateSourceRef("hc-1", "HEART_RATE", 0L)
            database.heartRateDao().upsertAll((0 until 10).map { hr(ref, it * 60_000L, 70) })

            val groupSizes = mutableListOf<Int>()
            streamer.streamGroups(fromMs = 0L, toMs = 10 * 60_000L, pageSize = 100, groupMinuteBudget = 4) { group ->
                groupSizes += group.samples.map { it.timestampMs / 60_000L }.distinct().size
            }

            assertTrue("minute budget exceeded: $groupSizes", groupSizes.all { it <= 4 })
            assertEquals(10, groupSizes.sum())
        }

    @Test
    fun implausibleSamplesAreExcludedJustLikeTheSinglePassQuery() =
        runBlocking {
            val ref = database.sourceRecordDao().getOrCreateSourceRef("hc-1", "HEART_RATE", 0L)
            database.heartRateDao().upsertAll(
                listOf(hr(ref, 0L, 70), hr(ref, 1_000L, 250), hr(ref, 2_000L, 20)),
            )

            var streamed = 0
            streamer.streamGroups(fromMs = 0L, toMs = 60_000L, pageSize = 10, groupMinuteBudget = 1) { group ->
                streamed += group.samples.size
            }

            assertEquals(1, streamed)
        }

    private fun hr(
        ref: Long,
        timestampMs: Long,
        bpm: Int,
    ) = HeartRateRecordEntity(
        sourceRecordRef = ref,
        timestampMs = timestampMs,
        beatsPerMinute = bpm,
        recordType = "RESTING",
        sessionId = null,
        deviceName = "watch",
    )
}
```

Add one case to `DataRollupManagerTest`: a day with 3 dense minutes must produce byte-identical buckets (`avgBpm`, `sampleCount`, `p5Bpm..p95Bpm`) whether `SAMPLE_PAGE_SIZE` is larger than the day or small enough to force several pages — the "chunked run equals single-pass run" invariant the current KDoc claims, now actually asserted across pages.

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.core.database.data.local.MinuteRollupStreamerTest"
```

Expected: FAIL — `Unresolved reference: MinuteRollupStreamer`.

- [ ] **Step 3: Add the keyset DAO query**

```kotlin
    // PERF-003: keyset page of the same plausibility predicate as
    // getPlausibleSamplesInRangeForRollup, ordered on the (timestampMs, sourceRecordRef) keyset so a
    // dense multi-device day streams in bounded pages instead of materializing every sample of that
    // day inside the rollup's writer transaction. Ordered by time first (not recordType) because the
    // streamer must be able to close a minute: grouping by (bucketStartMs, recordType, sessionId,
    // deviceName) then happens inside each bounded group.
    @Query(
        "SELECT * FROM heart_rate_records " +
            "WHERE timestampMs >= :fromMs AND timestampMs < :toMs " +
            "AND beatsPerMinute BETWEEN 30 AND 230 " +
            "AND (timestampMs > :afterTs OR (timestampMs = :afterTs AND sourceRecordRef > :afterRef)) " +
            "ORDER BY timestampMs ASC, sourceRecordRef ASC " +
            "LIMIT :limit",
    )
    suspend fun pagePlausibleSamplesForRollup(
        fromMs: Long,
        toMs: Long,
        afterTs: Long,
        afterRef: Long,
        limit: Int,
    ): List<HeartRateRecordEntity>
```

- [ ] **Step 4: Write the streamer**

```kotlin
package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.model.domain.sync.completeMinuteCutoff
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton

/** One publication unit: complete minutes only, bounded by [MinuteRollupStreamer.GROUP_MINUTE_BUDGET]. */
data class RollupGroup(
    val samples: List<HeartRateRecordEntity>,
    val minuteStartMs: Long,
    val minuteEndExclusiveMs: Long,
)

/**
 * PERF-003: streams a rollup range as bounded groups of *complete* minutes.
 *
 * Day-bounding never bounded sample count — a dense multi-device day can hold arbitrarily many
 * samples, and the old single read plus `groupBy` retained all of them (plus a sorted copy per
 * bucket) inside the writer transaction. This reads keyset pages of at most [SAMPLE_PAGE_SIZE] rows
 * *outside* any transaction and hands the caller at most [GROUP_MINUTE_BUDGET] minutes at a time.
 *
 * The trailing minute of a page is never emitted: it is carried into the next page until a sample
 * from a later minute proves it complete (or the range ends). A group boundary therefore always
 * falls on a minute boundary, so `aggregateIntoMinuteBuckets` and the coverage/contribution grouping
 * see exactly the sample set a single-pass run would see — no partial-minute bucket can be published
 * (DB-002), and paging cannot change bucket values.
 */
@Singleton
class MinuteRollupStreamer
    @Inject
    constructor(
        private val heartRateDao: HeartRateDao,
    ) {
        suspend fun streamGroups(
            fromMs: Long,
            toMs: Long,
            pageSize: Int = SAMPLE_PAGE_SIZE,
            groupMinuteBudget: Int = GROUP_MINUTE_BUDGET,
            onGroup: suspend (RollupGroup) -> Unit,
        ) {
            require(pageSize > 0) { "pageSize must be positive" }
            require(groupMinuteBudget > 0) { "groupMinuteBudget must be positive" }

            var afterTs = Long.MIN_VALUE
            var afterRef = Long.MIN_VALUE
            val carried = ArrayList<HeartRateRecordEntity>()
            var exhausted = false

            while (!exhausted) {
                currentCoroutineContext().ensureActive()
                val page = heartRateDao.pagePlausibleSamplesForRollup(fromMs, toMs, afterTs, afterRef, pageSize)
                exhausted = page.size < pageSize
                if (page.isNotEmpty()) {
                    afterTs = page.last().timestampMs
                    afterRef = page.last().sourceRecordRef
                    carried += page
                }

                val closedThrough =
                    if (exhausted) Long.MAX_VALUE else completeMinuteCutoff(carried.last().timestampMs)
                emitGroups(carried, closedThrough, groupMinuteBudget, onGroup)
                yield()
            }
        }

        /**
         * Emits every buffered minute strictly before [closedThroughExclusive] in groups of at most
         * [groupMinuteBudget] minutes, leaving the still-open minute(s) in [buffer] for the next page.
         */
        private suspend fun emitGroups(
            buffer: ArrayList<HeartRateRecordEntity>,
            closedThroughExclusive: Long,
            groupMinuteBudget: Int,
            onGroup: suspend (RollupGroup) -> Unit,
        ) {
            while (true) {
                val closed = buffer.filter { completeMinuteCutoff(it.timestampMs) < closedThroughExclusive }
                if (closed.isEmpty()) return
                val minutes = closed.map { completeMinuteCutoff(it.timestampMs) }.distinct().sorted()
                val take = minutes.take(groupMinuteBudget).toSet()
                val groupSamples = closed.filter { completeMinuteCutoff(it.timestampMs) in take }
                buffer.removeAll(groupSamples.toSet())
                onGroup(
                    RollupGroup(
                        samples = groupSamples,
                        minuteStartMs = take.min(),
                        minuteEndExclusiveMs = take.max() + MINUTE_MS,
                    ),
                )
                if (minutes.size <= groupMinuteBudget) return
            }
        }

        companion object {
            const val SAMPLE_PAGE_SIZE: Int = 5_000
            const val GROUP_MINUTE_BUDGET: Int = 60
            private const val MINUTE_MS = 60_000L
        }
    }
```

- [ ] **Step 5: Rewire `DataRollupManager` to publish per group**

`rollupDayChunk` stops being one transaction around read + aggregate + publish + delete. It becomes: stream groups (no transaction), and for each group capture the generation, aggregate in Kotlin, then open a *short* transaction that publishes that group and deletes exactly that group's consumed minutes.

```kotlin
        private suspend fun rollupDayChunk(
            fromMs: Long,
            toMs: Long,
        ): ScoreInvalidation.AffectedRange? {
            var chunkRange: ScoreInvalidation.AffectedRange? = null
            streamer.streamGroups(fromMs, toMs) { group ->
                chunkRange = mergeRanges(chunkRange, publishGroup(group))
            }
            return chunkRange
        }

        private suspend fun publishGroup(group: RollupGroup): ScoreInvalidation.AffectedRange? {
            val quarantinedMinutes =
                minuteCoverageDao
                    .getLegacyMinutesInRange(group.minuteStartMs, group.minuteEndExclusiveMs)
                    .toSet()
            val samplesByMinute =
                group.samples
                    .groupBy { completeMinuteCutoff(it.timestampMs) }
                    .filterKeys { it !in quarantinedMinutes }
            if (samplesByMinute.isEmpty()) {
                // Fully quarantined group: raw evidence stays in place (OD-1), no generation burned.
                return null
            }

            val generation = nextGeneration()
            val publishableSamples = samplesByMinute.values.flatten()
            val minMs = publishableSamples.minOf { it.timestampMs }
            val maxMs = publishableSamples.maxOf { it.timestampMs }
            // PERF-003: percentile/histogram work happens here, outside any writer transaction. The
            // publisher re-checks `capturedGeneration` inside the transaction below, so a concurrent
            // source mutation aborts this group instead of committing a mixed-generation projection.
            val request =
                MinutePublicationRequest(
                    rangeStartMs = group.minuteStartMs,
                    rangeEndExclusiveMs = group.minuteEndExclusiveMs,
                    capturedGeneration = generation,
                    coverage = samplesByMinute.keys.map { coverageFor(it, generation) },
                    contributions = contributionsFor(samplesByMinute, generation),
                    buckets = publishableSamples.aggregateIntoMinuteBuckets().map { it.copy(generation = generation) },
                    dirtyRange = dirtyRangeFor(minMs, maxMs, generation),
                )

            val outcome =
                transactionRunner.runInTransaction {
                    val published = publisher.publish(request)
                    if (published.publishedMinutes.isNotEmpty()) {
                        heartRateDao.deleteConsumedSamplesInRange(group.minuteStartMs, group.minuteEndExclusiveMs)
                    }
                    published
                }

            return if (outcome.publishedMinutes.isEmpty()) {
                null
            } else {
                ScoreInvalidation.AffectedRange(start = utcDateOf(minMs), endInclusive = utcDateOf(maxMs))
            }
        }
```

`doRollupExpiredHotTier`'s day loop is unchanged (`getEarliestTimestampMs` still moves the cursor past quarantined days). Wrap the per-group publish in a `try`/`catch (e: SourceGenerationConflictException)` that logs and **stops the pass** returning what has already published — the next scheduled rollup retries the remaining groups idempotently. Constructor gains `private val streamer: MinuteRollupStreamer`; update `DataRollupManagerTest`'s construction and the Hilt provider.

- [ ] **Step 6: Run the rollup suites**

```bash
./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.core.database.data.local.MinuteRollupStreamerTest"
./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.core.database.data.local.DataRollupManagerTest"
./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.core.database.data.local.CompleteMinuteRollupTest"
./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.core.database.data.local.DataRollupCoverageTest"
./gradlew :core:database:testDebugUnitTest
```

Expected: PASS, with legacy quarantine, multi-device same-minute, partial-cutoff and reimport-overlap behaviour unchanged.

- [ ] **Step 7: Update DATA_FLOW and commit**

§1.4/§1.4.1: replace the "reads every plausible sample of a day inside its transaction" description with the streamed complete-minute-group contract, and state explicitly that aggregation happens outside the writer transaction with generation validation at publish time.

```bash
codegraph index
./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest
rtk git add -A && rtk git commit -m "perf(db): stream hot-tier rollup in bounded complete-minute groups"
```

---

## Task 9: Collect unreferenced source metadata

**Files:**
- Create: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/SourceMetadataGc.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RetentionCleanup.kt`
- Modify: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/SourceRecordDao.kt` (page of unreferenced ids)
- Test: `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/SourceMetadataGcTest.kt`

**Interfaces:**
- Consumes: `SourceRecordDao`, `RetentionCleanup`'s existing batching pattern.
- Produces:
  - `SourceRecordDao.pageUnreferencedSourceIds(afterRef: Long, limit: Int): List<Long>`
  - `SourceMetadataGc.collect(dao: SourceRecordDao, pageSize: Int = PAGE_SIZE, limitPerRun: Int = LIMIT_PER_RUN): Int` returning the number of rows deleted
  - `SourceMetadataGc.PAGE_SIZE = 500`, `SourceMetadataGc.LIMIT_PER_RUN = 10_000`

**Referential rule (spec: "clean only metadata unreferenced by raw/warm/pending work"):** a source row may be deleted only when it has no `heart_rate_records`, no `hrv_records`, no `hr_source_minute_contributions`, and is not named by in-flight staging (`staged_hr_sources`, `scan_seen_ids`). Backup writes every referenced source, so GC must never break an FK a backup or restore could still need.

- [ ] **Step 1: Write the failing GC test**

```kotlin
package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.dao.getOrCreateSourceRef
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrSourceMinuteContributionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.ScanSeenIdEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SourceMetadataGcTest {
    private lateinit var database: HealthDatabase

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun deletesOnlySourcesWithNoRawNoWarmAndNoPendingReference() =
        runBlocking {
            val dao = database.sourceRecordDao()
            val withRaw = dao.getOrCreateSourceRef("hc-raw", "HEART_RATE", 0L)
            val withWarm = dao.getOrCreateSourceRef("hc-warm", "HEART_RATE", 0L)
            val staged = dao.getOrCreateSourceRef("hc-staged", "HEART_RATE", 0L)
            dao.getOrCreateSourceRef("hc-orphan", "HEART_RATE", 0L)

            database.heartRateDao().upsertAll(
                listOf(
                    HeartRateRecordEntity(withRaw, 1_000L, 70, "RESTING", null, "watch"),
                ),
            )
            database.minuteCoverageDao().upsertContributions(
                listOf(
                    HrSourceMinuteContributionEntity(
                        sourceRecordRef = withWarm,
                        bucketStartMs = 0L,
                        generation = 1L,
                        firstSampleMs = 0L,
                        lastSampleMs = 1_000L,
                        deviceName = "watch",
                        bpmHistogram = "v1:70=1",
                    ),
                ),
            )
            database.scanStagingDao().insertSeenIds(
                listOf(ScanSeenIdEntity("run-a", "0", "HEART_RATE", "hc-staged")),
            )

            val deleted = SourceMetadataGc.collect(dao)

            assertEquals(1, deleted)
            val remaining = dao.pageAfter(0L, 100).map { it.sourceRecordId }.toSet()
            assertEquals(setOf("hc-raw", "hc-warm", "hc-staged"), remaining)
            assertEquals(staged, dao.getSourceRef("hc-staged"))
        }

    @Test
    fun collectionIsBoundedPerRun() =
        runBlocking {
            val dao = database.sourceRecordDao()
            repeat(120) { dao.getOrCreateSourceRef("hc-$it", "HEART_RATE", 0L) }

            val deleted = SourceMetadataGc.collect(dao, pageSize = 25, limitPerRun = 50)

            assertEquals(50, deleted)
            assertEquals(70, dao.count())
        }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.core.database.data.local.SourceMetadataGcTest"
```

Expected: FAIL — `Unresolved reference: SourceMetadataGc`.

- [ ] **Step 3: Add the DAO page and the GC**

```kotlin
    // PERF-003: candidate orphan metadata. A row qualifies only when nothing references it any more:
    // no raw HR/HRV children, no warm contribution evidence (OD-1 lineage), and no in-flight staging
    // naming it. Backup pages every remaining source, so this predicate is also what keeps an export
    // FK-complete.
    @Query(
        "SELECT id FROM health_source_records " +
            "WHERE id > :afterRef " +
            "AND NOT EXISTS (SELECT 1 FROM heart_rate_records WHERE sourceRecordRef = health_source_records.id) " +
            "AND NOT EXISTS (SELECT 1 FROM hrv_records WHERE sourceRecordRef = health_source_records.id) " +
            "AND NOT EXISTS (" +
            "  SELECT 1 FROM hr_source_minute_contributions " +
            "  WHERE sourceRecordRef = health_source_records.id) " +
            "AND NOT EXISTS (" +
            "  SELECT 1 FROM staged_hr_sources WHERE sourceId = health_source_records.sourceRecordId) " +
            "AND NOT EXISTS (" +
            "  SELECT 1 FROM scan_seen_ids WHERE sourceId = health_source_records.sourceRecordId) " +
            "ORDER BY id ASC LIMIT :limit",
    )
    suspend fun pageUnreferencedSourceIds(afterRef: Long, limit: Int): List<Long>

    @Query("DELETE FROM health_source_records WHERE id IN (:ids)")
    suspend fun deleteSourcesByRefs(ids: List<Long>): Int
```

```kotlin
package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.SourceRecordDao
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/**
 * PERF-003: bounded collection of source metadata that nothing references any more. Retention
 * deletes raw rows but previously left one metadata row per historical parent behind, so a
 * million-parent history kept accumulating identities that every backup then had to page through.
 *
 * Deliberately conservative: a row survives if it still has raw children, warm contribution
 * evidence, or any in-flight staged/scan reference. Deleting a still-referenced row would break a
 * backup FK or destroy OD-1 warm lineage, so "unsure" always means "keep".
 */
internal object SourceMetadataGc {
    const val PAGE_SIZE = 500
    const val LIMIT_PER_RUN = 10_000

    suspend fun collect(
        dao: SourceRecordDao,
        pageSize: Int = PAGE_SIZE,
        limitPerRun: Int = LIMIT_PER_RUN,
    ): Int {
        var deleted = 0
        var afterRef = 0L
        while (deleted < limitPerRun) {
            currentCoroutineContext().ensureActive()
            val remaining = (limitPerRun - deleted).coerceAtMost(pageSize)
            val page = dao.pageUnreferencedSourceIds(afterRef, remaining)
            if (page.isEmpty()) break
            deleted += dao.deleteSourcesByRefs(page)
            afterRef = page.last()
            yield()
        }
        return deleted
    }
}
```

- [ ] **Step 4: Call it from retention cleanup**

In `RetentionCleanup`, after the existing raw-row deletion batches complete for a run, call `SourceMetadataGc.collect(sourceRecordDao)` and include the count in that class's existing telemetry log (counts only — no identifiers). Keep it after deletion, never before: a row whose children are being deleted in this same run must be evaluated against the post-deletion state.

- [ ] **Step 5: Run the tests**

```bash
./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.core.database.data.local.SourceMetadataGcTest"
./gradlew :core:database:testDebugUnitTest
./gradlew :app:testDebugUnitTest
```

Expected: PASS, including the backup round-trip tests in `:app` (an export after GC must still resolve every `sourceRecordRef`).

- [ ] **Step 6: Update DATA_FLOW and commit**

§1.4: document the GC predicate and that it runs after retention deletion, bounded per run.

```bash
codegraph index
./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest
rtk git add -A && rtk git commit -m "perf(db): collect unreferenced source metadata after retention cleanup"
```

---

## Task 10: Prove the query plans

**Files:**
- Test: `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/Phase2QueryPlanTest.kt`
- Modify (only if a plan shows a scan): the relevant entity's `indices` plus a follow-up additive migration statement inside `Migration22To23` (still unreleased at this point in the phase — extend it rather than adding v24)

**Interfaces:**
- Consumes: every query added in Tasks 3, 5, 8, 9.
- Produces: no production API; an executable record of which index each new predicate uses.

**Rule:** an index is kept only if a plan needs it. Before adding one, record the plan without it; after adding it, record the plan and the storage/write cost in `benchmark/BASELINE.md` (spec §11 "compare index storage/write cost before keeping additions").

- [ ] **Step 1: Write the plan test**

```kotlin
package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Phase2QueryPlanTest {
    private lateinit var database: HealthDatabase

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun stagedIdLookupUsesTheStagingIndex() {
        val plan =
            explain(
                "SELECT sourceId FROM scan_seen_ids " +
                    "WHERE runId = 'run-a' AND chunkId = '0' AND recordType = 'SLEEP'",
            )
        assertTrue(plan, plan.contains("USING INDEX") || plan.contains("USING COVERING INDEX"))
        assertTrue("staging lookup must not scan: $plan", !plan.contains("SCAN scan_seen_ids"))
    }

    @Test
    fun sleepAntiJoinDeleteUsesAnIndexOnBothSides() {
        val plan =
            explain(
                "SELECT id FROM sleep_sessions WHERE startTime >= 0 AND endTime <= 100 " +
                    "AND id NOT IN (SELECT sourceId FROM scan_seen_ids " +
                    "WHERE runId = 'run-a' AND chunkId = '0' AND recordType = 'SLEEP')",
            )
        assertTrue("subquery side must use the staging index: $plan", plan.contains("scan_seen_ids"))
        assertTrue("outer side must not table-scan: $plan", !plan.contains("SCAN sleep_sessions"))
    }

    @Test
    fun unstagedSourcePageUsesTheSourceRangeIndex() {
        val plan =
            explain(
                "SELECT * FROM health_source_records WHERE recordType = 'HEART_RATE' " +
                    "AND metadataState = 'AUTHORITATIVE' AND recordStartMs < 100 " +
                    "AND recordEndExclusiveMs > 0 AND id > 0 " +
                    "AND sourceRecordId NOT IN (SELECT sourceId FROM scan_seen_ids " +
                    "WHERE runId = 'run-a' AND chunkId = '0' AND recordType = 'HEART_RATE') " +
                    "ORDER BY id ASC LIMIT 500",
            )
        assertTrue(
            "expected index_health_source_records_recordType_metadataState_recordStartMs: $plan",
            plan.contains("index_health_source_records_recordType_metadataState_recordStartMs"),
        )
    }

    @Test
    fun rollupKeysetPageUsesTheTimestampIndexWithoutTempSort() {
        val plan =
            explain(
                "SELECT * FROM heart_rate_records WHERE timestampMs >= 0 AND timestampMs < 60000 " +
                    "AND beatsPerMinute BETWEEN 30 AND 230 " +
                    "AND (timestampMs > 0 OR (timestampMs = 0 AND sourceRecordRef > 0)) " +
                    "ORDER BY timestampMs ASC, sourceRecordRef ASC LIMIT 5000",
            )
        assertTrue("high-volume cursor must not temp-sort: $plan", !plan.contains("USE TEMP B-TREE"))
        assertTrue(plan, plan.contains("index_hr_v10_timestamp") || plan.contains("index_hr_v10_source_time"))
    }

    @Test
    fun sourceGcCandidatePageDoesNotScanChildTables() {
        val plan =
            explain(
                "SELECT id FROM health_source_records WHERE id > 0 " +
                    "AND NOT EXISTS (SELECT 1 FROM heart_rate_records " +
                    "WHERE sourceRecordRef = health_source_records.id) " +
                    "AND NOT EXISTS (SELECT 1 FROM hrv_records " +
                    "WHERE sourceRecordRef = health_source_records.id) " +
                    "AND NOT EXISTS (SELECT 1 FROM hr_source_minute_contributions " +
                    "WHERE sourceRecordRef = health_source_records.id) " +
                    "ORDER BY id ASC LIMIT 500",
            )
        assertTrue("HR existence check must use an index: $plan", !plan.contains("SCAN heart_rate_records"))
        assertTrue("HRV existence check must use an index: $plan", !plan.contains("SCAN hrv_records"))
        assertTrue(
            "contribution existence check must use an index: $plan",
            !plan.contains("SCAN hr_source_minute_contributions"),
        )
    }

    private fun explain(sql: String): String =
        runBlocking {
            val rows = mutableListOf<String>()
            database.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
                while (cursor.moveToNext()) {
                    rows += cursor.getString(cursor.columnCount - 1)
                }
            }
            rows.joinToString("\n")
        }
}
```

- [ ] **Step 2: Run it and read the plans**

```bash
./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.core.database.data.local.Phase2QueryPlanTest"
```

Expected on first run: some assertions fail. Likely candidates and their fixes:
- `hr_source_minute_contributions` has index `(bucketStartMs, generation)` but its PK begins with `sourceRecordRef`, so the existence check should already be covered — confirm from the plan output rather than assuming.
- `hrv_records` — check its declared indices; if it has no `sourceRecordRef`-leading index, add `Index(value = ["sourceRecordRef", "timestampMs"], unique = true)` to match `heart_rate_records`' `index_hr_v10_source_time`, plus the matching `CREATE INDEX IF NOT EXISTS` in `Migration22To23`.

- [ ] **Step 3: Add only the indexes the plans demand**

For each failing assertion, add the index to the entity's `indices`, add the matching `CREATE INDEX IF NOT EXISTS` to `Migration22To23.migrate`, regenerate `23.json`, and record before/after in `benchmark/BASELINE.md` under a new "Phase 2 — query plans and index cost" section (statement, plan before, plan after, index size from `dbstat` or measured DB growth, and the write-cost delta from the Task 5 batching test's timings).

- [ ] **Step 4: Re-run and commit**

```bash
./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.core.database.data.local.Phase2QueryPlanTest"
./gradlew :core:database:testDebugUnitTest
codegraph index
./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest
rtk git add -A && rtk git commit -m "test(db): prove Phase 2 query plans use intended indexes"
```

---

## Task 11: Measure it — benchmarks, docs, release gates

**Files:**
- Modify: `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/HealthPipelineBaselineBenchmark.kt`
- Modify: `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/HealthParentFixture.kt` (nested-sample and page-boundary shapes)
- Modify: `benchmark/BASELINE.md`
- Modify: `internal-docs/DATA_FLOW.md` (final consistency pass)
- Test: `database-benchmark/src/main/kotlin/app/readylytics/health/benchmark/ScanStagingScaleBenchmark.kt` (new)

**Interfaces:**
- Consumes: everything from Tasks 1–10; `CountingTransactionRunner`, `CountingQueryCallback`, `measured {}` (already in `HealthPipelineBaselineBenchmark.kt`).
- Produces: `ScanStagingScaleBenchmark` with `benchmarkMillionParentIngestPlateau()`, `benchmarkDenseDayRollupMemory()`, `benchmarkMillionSourceBackupAfterGc()`; new BASELINE.md rows.

- [ ] **Step 1: Extend the fixture shapes**

`HealthParentFixture.pages(...)` gains the three shapes §11 requires: `>1m one-sample parents`, `>1m nested samples` (few parents, dense children), and `page boundary extremes` (a page whose last parent's samples straddle a minute and a chunk boundary). Keep it deterministic — record the seed in BASELINE.md, never health values.

- [ ] **Step 2: Add the scale benchmark**

Three measurements, each asserting the WP-18/19 acceptance criteria rather than a wall-clock target:

```kotlin
    @Test
    fun benchmarkMillionParentIngestPlateau() =
        runBlocking {
            val runner = CountingTransactionRunner(RoomTransactionRunner(database))
            // 1_000_000 one-sample parents through the real coordinator + store.
            val (_, elapsedMs) = measured { ingestFixture(parents = 1_000_000, samplesEach = 1) }

            // Acceptance: transactions scale with row batches, never with parent count.
            assertTrue(
                "transactions ${runner.transactionCount} must stay far below parent count",
                runner.transactionCount < 50_000,
            )
            // Acceptance: live heap plateaus with the buffer settings, not with scanned ids.
            logStageMetric("ingest.1m.parents", elapsedMs, peakHeapBytes())
            assertTrue("heap grew with scanned ids", peakHeapBytes() < HEAP_PLATEAU_BUDGET_BYTES)
        }
```

`benchmarkDenseDayRollupMemory()` runs one day holding several hundred thousand samples from three devices through `DataRollupManager.rollupExpiredHotTier`, asserting peak heap stays within the same budget and that interrupting the pass after N groups leaves every published minute complete and every unpublished minute still raw. `benchmarkMillionSourceBackupAfterGc()` exports with a million source rows, then runs `SourceMetadataGc` and exports again, asserting both exports are FK-complete and bounded in heap and that the second is smaller.

Set `HEAP_PLATEAU_BUDGET_BYTES` from the Phase-0 measurement already in BASELINE.md, not from a guess; if BASELINE.md has no comparable figure, record the first run as the baseline and state that in the same commit.

- [ ] **Step 3: Run the device benchmarks**

```bash
./gradlew :database-benchmark:tasks --all | grep -i benchmark   # confirm the task name at HEAD
./gradlew :database-benchmark:connectedBenchmarkAndroidTest
```

Report failures as failures; do not call an unmeasured plan validated. Do not uninstall `app.readylytics.health` to make a device run work — use the benchmark app's own package.

- [ ] **Step 4: Record results**

In `benchmark/BASELINE.md`, add a "Phase 2 — WP-18/WP-19" section: device model, OS/API level, build variant, dataset shape and seed, per-stage medians and tails across repeated runs, transaction/statement counts, peak heap, WAL growth, thermal/compilation conditions. Counts, durations, sizes and revisions only — no health values, no source identifiers.

- [ ] **Step 5: Final documentation consistency pass**

Re-read `internal-docs/DATA_FLOW.md` §1.1–1.4.1 end to end and confirm: `@Database(version = 23)`, staging tables described, page-token resume described, single retry budget described, bulk source resolution described, streamed rollup described, source GC described, and nothing still claims the removed heap-set or per-source-transaction behaviour. No scoring section changes in this phase — if a scoring section needed editing, something in the phase went out of scope.

`ABOUT.md`, `docs/about.md`, `docs/privacy.md` and the in-app `about_*`/`tooltip_*` strings need **no** change: no formula, threshold, profile, phase/confidence model, retention behaviour, collection behaviour or sharing behaviour changed. State that explicitly in the PR description so a reviewer can check it rather than assume it.

- [ ] **Step 6: Run the full release gate**

```bash
./gradlew ktlintFormat
./gradlew detekt
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintRelease
codegraph index
```

Expected: all green, zero new detekt issues, zero new suppressions, zero baseline edits. If a touched file had pre-existing detekt issues, they are fixed structurally in this phase (boyscout rule).

- [ ] **Step 7: Commit**

```bash
rtk git add -A && rtk git commit -m "test(benchmark): measure Phase 2 ingest/rollup/backup bounds and sync docs"
```

---

## Phase Exit Criteria

Straight from WP-18/WP-19 acceptance and §11; each must be evidenced, not asserted.

- [ ] Million-parent live heap plateaus by buffer settings (Task 11 measurement, not inference).
- [ ] No per-parent transaction: transaction count scales with row batches (Task 5 test + Task 11 counter).
- [ ] No SQL statement carries an unbounded binding list; every new predicate is set-based or chunked ≤ 500 (Task 3, grep for `NOT IN (:` returns nothing in the reconciliation path).
- [ ] No Health Connect call inside a Room transaction; no percentile/histogram work inside a writer transaction (Tasks 5, 8).
- [ ] Suspended jobs resume: a kill in HR and in HRV independently, with a pre-baseline deletion and multi-page updates, ends with the same rows and scores as an uninterrupted run, and cancellation never treats unseen pages as deletions (Tasks 3, 4 tests + `PagedIngestResumptionTest`).
- [ ] Repeated identical import changes no rows and burns no source revisions (Task 5 test).
- [ ] Dense single-day rollup has bounded heap; interruption preserves complete buckets; repeated rollup within the same minute, mixed-tier boundary and legacy quarantine behave exactly as before (Task 8 tests + Task 11).
- [ ] Million-source backup stays bounded and includes every referenced source exactly once, after GC as well as before (Task 9, Task 11).
- [ ] Intended indexes are proven by `EXPLAIN QUERY PLAN`, with storage/write cost recorded (Task 10).
- [ ] Rollback path intact: WP-06 complete-chunk replay still works (drop the stored token, `resume = false`) and staging can be purged without touching physiological data.
- [ ] `internal-docs/DATA_FLOW.md` matches the implementation; the v23 schema JSON is committed; `codegraph index` has run after the new files.

## Rollback and Risk Notes

- **Schema:** v23 is additive (two empty tables, plus any index Task 10 proves necessary). Rolling back means shipping a forward release that stops writing staging, or restoring a verified backup — never a destructive downgrade. An older binary may refuse to open a v23 DB; that is the documented S3 compatibility position.
- **Temporary disk/WAL:** staging grows with staged identities (4 short columns per scanned record, purged per completed chunk and per abandoned run). Measure peak disk and WAL in Task 11; if a device shows pressure, lower `GROUP_MINUTE_BUDGET`/`SAMPLE_PAGE_SIZE` and purge per chunk more eagerly — do not widen the transaction.
- **Provider page-token rejection:** treated as a reason to replay the chunk with `resume = false` (Task 4), never as proof of completion. Token longevity stays an explicit device validation, not an assumed API guarantee.
- **Retry amplification:** removed by construction in Task 7; the outer retry is WorkManager's, which is durable and observable.
- **Behaviour change to call out in the PR:** vitals/steps reconciliation now compares the persisted row identity instead of an id prefix, so a record whose timestamp moved converges (old row deleted, new row inserted) instead of leaving a stale duplicate. This corrects DB-001's ID-parsing risk; it can change a historical vitals series that previously carried a duplicate, so it belongs in release notes as a bug fix.
- **Out of scope here (do not drift into it):** CACHE-002 dependency closure, PERF-002 scoring history costs, ARCH-001/DI-001 ownership migration, chart materialization — Phases 3–5, WP-20–24.

## Self-Review

- **Spec coverage.** WP-18: staging + anti-join (Tasks 1–4), unused ID sets removed (Task 4), bulk source lookup/insert with sample writes (Task 5), capped transform buffers and SQL bindings (Tasks 3, 5, 6), unified bounded read-retry budget with explicit Changes/page/window ownership (Task 7), full-range session sweep and stable ties untouched (no task modifies `SessionLinkReconciler`/`SessionLinker` — called out here so an executor does not "helpfully" touch it). WP-19: streamed complete-bucket rollup with generation-checked publication (Task 8), source export paging (already landed — verified, re-asserted in Task 11), metadata GC limited to unreferenced rows (Task 9), pruning after verified archive creation and startup staging cleanup (already landed via WP-16 — verified in Current State, not re-implemented). §11 measurement rows for HC read/transform, Room upsert/replay, query plans and rollup map to Tasks 5, 8, 10, 11. S3 migration row maps to Tasks 1 and 10.
- **Placeholders.** Two intentional "fill in the remaining cases exactly like this one" instructions remain, both with the shape fully written out and the existing function they mirror named: the three sibling reconcilers in Task 3 Step 5 and the per-type DAO query set in Task 3 Step 4. They are mechanical repetitions of code given in full; every other step carries its actual content.
- **Type consistency.** `ScanIdentity(runId, chunkId)` is used identically in Tasks 2, 3, 4, 9, 10. `CompleteTypeScan`'s fifth parameter is `scan: ScanIdentity` everywhere after Task 3. `ScanStagingDao.STATE_COMPLETE` is the single completeness literal (Tasks 1, 2, 3). `StagedDeletionBounds(minMs, maxMs)` is produced by every bounds query and consumed only by `StagedScanContext.rangeOf`. `ResolvedSource(ref, existing)` is produced by `SourceRefResolver.resolveAll` and consumed by `replaceSingleHeartRateSource`/`replaceSingleHrvSource`. `RollupGroup(samples, minuteStartMs, minuteEndExclusiveMs)` is produced by `MinuteRollupStreamer.streamGroups` and consumed by `DataRollupManager.publishGroup`. `SourceMetadataGc.collect` is the only GC entry point.
- **Known executor checks flagged inline** (verify at HEAD rather than trusting this document): `SleepSessionEntity`'s constructor and the existing `session(...)`/`HealthRecordDaos` test helpers (Task 3 Step 1), the HC repository test fake's surface (Task 4 Step 1), `hrv_records`' declared indices (Task 10 Step 2), and the `:database-benchmark` variant task name (Task 11 Step 3).
