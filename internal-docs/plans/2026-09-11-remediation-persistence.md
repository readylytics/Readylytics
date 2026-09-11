# Transactional Health Mutations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make source corrections and their dependent scoring work durable and atomic.

**Architecture:** Add S1 metadata/journal without rewriting health rows, then serialize maintenance through one owner. Replace complete source payloads with stable integer FKs and journal old/new extents in the same Room transaction.

**Tech Stack:** Existing Kotlin/Compose/Room/SQLCipher/Health Connect/WorkManager/DataStore modules; JUnit, MockK, existing Robolectric adapters, and instrumented Room/benchmark fixtures. No dependency upgrades or new Gradle modules.

**Spec:** `internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md`, WP-04–05; CACHE-001/002, ARCH-001, HC-001, DB-001. Read it and [the execution index](2026-09-11-remediation-phase-0-1-index.md) before this plan. Finding IDs refer to that spec, not similarly named historical source comments.

## Global Constraints

- "Do not use restricted experimental deduplication internals or require an SDK upgrade for this plan."
- "Transient failures propagate as typed errors/exceptions and cancellation remains cancellation."
- "SDK read/retry/enrichment happens outside Room transactions."
- "Never uninstall `app.readylytics.health` to solve a migration or signing conflict."
- Repository floors: minSdk=26, compile/targetSdk=37; Health Connect 1.1.0; current Room schema 19 at inspected HEAD `93c468b2`.
- Room is the UI source of truth; calculations remain pure Kotlin. Preserve current-day refresh, `RetentionBounds`, full-range session reconciliation, WorkManager `KEEP`, and existing progress channels.
- Preserve formulas, coefficients, profiles and sleep/source-selection policies. Decision-gated proposals below are not permission to change product semantics.
- Follow the index's documentation matrix, mandatory pre-commit commands, migration ledger, file limits (target ≤400, hard ≤800), and indexing rules. No new suppressions or baseline edits.

---

### Task P1: Add S1 journal/provenance schema and migration (WP-04/05 foundation)

**Files and responsibilities:**
- Modify: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/HealthSourceRecordEntity.kt`
- Modify: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/SourceRecordDao.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/HealthDatabase.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/DatabaseMigrations.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/di/DatabaseModule.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/BackupModels.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/BackupStreamWriter.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/RestoreBatchLoader.kt`
- Create: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/DirtyRangeEntity.kt`
- Create: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/DirtyRangeDao.kt`
- Create: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/HealthMutationStateEntity.kt`
- Create: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/HealthMutationStateDao.kt`
- Create: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/migration/Migration19To20.kt`
- Create: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/SourceMetadataBackfill.kt`
- Test: `core/database/src/androidTest/kotlin/app/readylytics/health/core/database/data/local/DatabaseMigrationInstrumentedTest.kt`
- Create: `core/database/src/androidTest/kotlin/app/readylytics/health/core/database/data/local/DirtyRangeMigrationInstrumentedTest.kt` — new behavior test

**Interfaces:** Produces additive source fields `originPackage: String?`, `recordStartMs: Long?`, `recordEndExclusiveMs: Long?`, `lastModifiedMs: Long?`, `metadataState: String = "UNKNOWN"`, `sourceRevision: Long = 0`. `createdAtMs` remains for compatibility and stops serving as reconciliation membership. Source `id` and all existing FKs remain unchanged.

- [ ] **Step 1: Add a nonempty v19→20 migration fixture.**

Use `MigrationTestHelper` with the existing schema assets. Seed a source ref 42 with two HR timestamps, an HRV source, sessions/routes, a warm-only source and summary. After migration:

```sql
SELECT id, sourceRecordId, metadataState, sourceRevision FROM health_source_records ORDER BY id;
PRAGMA foreign_key_check;
SELECT COUNT(*) FROM dirty_ranges;
```

Assert ref 42 survives, numeric/sample rows are unchanged, metadata is UNKNOWN/revision 0, FK check is empty and no invented dirty dates exist. Run keyset backfill, interrupt after one batch, restart and compare with uninterrupted backfill. Warm-only origin/bounds remain unknown.

- [ ] **Step 2: Run `./gradlew :core:database:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.core.database.data.local.DirtyRangeMigrationInstrumentedTest` red.**

Expected: missing migration/table until implemented. Add a pre-migration fixture assertion proving baseline cannot preserve dirty state, not merely that a symbol is absent.

- [ ] **Step 3: Add the entities and transaction-safe journal DAO.**

```kotlin
@Entity(tableName = "dirty_ranges", indices = [Index(value = ["sourceGeneration", "id"])])
data class DirtyRangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceGeneration: Long,
    val startEpochDay: Long,
    val endEpochDayInclusive: Long,
    val nextEpochDay: Long,
    val reason: String,
    val scoringSnapshotId: String,
)

@Entity(tableName = "health_mutation_state")
data class HealthMutationStateEntity(
    @PrimaryKey val id: Int = 1,
    val sourceGeneration: Long = 0,
    val maintenanceOperationId: String? = null,
    val maintenancePhase: String? = null,
    val backfillAfterSourceRef: Long = 0,
)

@Dao
interface DirtyRangeDao {
    @Insert suspend fun insert(row: DirtyRangeEntity): Long
    @Query("SELECT * FROM dirty_ranges ORDER BY id LIMIT :limit")
    suspend fun pending(limit: Int): List<DirtyRangeEntity>
    @Query("UPDATE dirty_ranges SET nextEpochDay = :nextDay WHERE id = :id AND sourceGeneration = :generation AND nextEpochDay = :expectedDay")
    suspend fun advance(id: Long, generation: Long, expectedDay: Long, nextDay: Long): Int
    @Query("DELETE FROM dirty_ranges WHERE id = :id AND sourceGeneration = :generation AND nextEpochDay > endEpochDayInclusive")
    suspend fun deleteCompleted(id: Long, generation: Long): Int
}
```

Imports are Room annotations in `core:database-schema`. Store dates as epoch days; reason/config identity are internal bounded values, not raw provider strings. Add CHECK-equivalent constructor/domain validation for start≤next≤end+1. Never delete a dirty entry because only its first day finished.

- [ ] **Step 4: Write additive migration SQL and register schema export.**

```sql
ALTER TABLE health_source_records ADD COLUMN originPackage TEXT;
ALTER TABLE health_source_records ADD COLUMN recordStartMs INTEGER;
ALTER TABLE health_source_records ADD COLUMN recordEndExclusiveMs INTEGER;
ALTER TABLE health_source_records ADD COLUMN lastModifiedMs INTEGER;
ALTER TABLE health_source_records ADD COLUMN metadataState TEXT NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE health_source_records ADD COLUMN sourceRevision INTEGER NOT NULL DEFAULT 0;
CREATE INDEX index_health_source_records_recordType_metadataState_recordStartMs
ON health_source_records(recordType, metadataState, recordStartMs);
CREATE TABLE dirty_ranges (
 id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceGeneration INTEGER NOT NULL,
 startEpochDay INTEGER NOT NULL, endEpochDayInclusive INTEGER NOT NULL,
 nextEpochDay INTEGER NOT NULL, reason TEXT NOT NULL, scoringSnapshotId TEXT NOT NULL);
CREATE INDEX index_dirty_ranges_sourceGeneration_id ON dirty_ranges(sourceGeneration, id);
CREATE TABLE health_mutation_state (
 id INTEGER NOT NULL PRIMARY KEY, sourceGeneration INTEGER NOT NULL,
 maintenanceOperationId TEXT, maintenancePhase TEXT, backfillAfterSourceRef INTEGER NOT NULL);
INSERT INTO health_mutation_state VALUES (1, 0, NULL, NULL, 0);
```

Match Room entity column defaults with `@ColumnInfo(defaultValue=...)` for the added non-null fields. Register `MIGRATION_19_20`, both entities/accessors, singleton DAO bindings and `DATABASE_VERSION=20`; generate `core/database/schemas/app.readylytics.health.core.database.data.local.HealthDatabase/20.json`. Do not hand-edit exported schema or alter migrations 7→19.

- [ ] **Step 5: Implement resumable metadata backfill and backup compatibility.**

Page source IDs by `id > afterRef ORDER BY id LIMIT 500`; derive each HR/HRV source's actual min/max children. Mark `CHILD_BOUNDS` (not authoritative HC bounds), leave missing origin null. Use max sample timestamp+1 as exclusive child bound with checked overflow. Save `backfillAfterSourceRef` in the same transaction as the batch; `ensureActive()`/`yield()` between batches. Only fresh complete provider payload sets `AUTHORITATIVE` metadata. A live write after backfill wins; update only rows still UNKNOWN.

S2 archive policy must accept new source defaults for old archives. Serialize journal/source generation in new backups or deliberately regenerate conservative dirty work on restore; choose regeneration here: local job IDs/tokens are not restored, all restored retained dates are journaled under the restored snapshot before work resumes. Include source fields and mutation generation in manifest; do not export a live maintenance lock. Update inventory policy/version tests in the same commit.

- [ ] **Step 6: Run migration/restore suites, update DATA_FLOW and commit.**

Run the supported installed upgrade chain, low-space/abort rollback fixture and current/legacy backup round trips. Verify `EXPLAIN QUERY PLAN` on the new type/state/start predicate. Shared gates, `codegraph index`, `codegraph sync`; suggested message: `feat: persist source provenance and dirty work`.

### Task P2: Own mutation/maintenance and atomic day publication (WP-04)

**Files and responsibilities:**
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RoomHealthChangeIngestionStore.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RetentionCleanup.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/DataRollupManager.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringRepositoryImpl.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringDayDataLoader.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/DailyTrimpComputer.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HealthSyncUseCase.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/DatabaseReadyStartupInitializer.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/workers/HealthResyncWorker.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/di/ScoringSyncBindingsModule.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/HealthMutationCoordinator.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/DirtyRangeStore.kt`
- Create: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RoomDirtyRangeStore.kt`
- Create: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/HealthMutationCoordinatorImpl.kt`
- Create: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/DirtySummaryPublisher.kt`
- Create: `core/model/src/test/kotlin/app/readylytics/health/core/model/domain/sync/DirtyRangeContractTest.kt` — new behavior test
- Create: `core/database/src/androidTest/kotlin/app/readylytics/health/core/database/data/local/DirtyMutationRecoveryInstrumentedTest.kt` — new behavior test
- Test: `app/src/test/kotlin/app/readylytics/health/DatabaseReadyStartupInitializerScoringVersionTest.kt`

**Interfaces:** Uses P1 tables. New contracts in `core:model/domain/sync`:

```kotlin
data class DirtyTicket(
    val id: Long, val sourceGeneration: Long, val nextDay: LocalDate,
    val endInclusive: LocalDate, val scoringSnapshotId: String,
)
interface DirtyRangeStore {
    suspend fun pending(limit: Int = 100): List<DirtyTicket>
}
interface HealthMutationCoordinator {
    suspend fun <T> withMutation(block: suspend () -> T): T
    suspend fun <T> withMaintenance(operationId: String, block: suspend () -> T): T
}
```

`HealthMutationStateDao` exposes `current(): HealthMutationStateEntity`, `incrementGeneration(): Unit` (`UPDATE ... SET sourceGeneration=sourceGeneration+1 WHERE id=1`), `setMaintenance(operationId: String?, phase: String?): Unit`, and `advanceBackfill(afterRef: Long): Unit`; all are suspend DAO methods. Updates require the caller's transaction.

`RoomDirtyRangeStore` also has database-internal `append(start: LocalDate, endInclusive: LocalDate, reason: String, snapshotId: String): Long`; it must run within the caller's mutation transaction. `DirtySummaryPublisher.publish(ticket: DirtyTicket, summary: DailySummary, zoneId: ZoneId, expectedSourceGeneration: Long): Boolean` commits the day and advances only that ticket. C3 later extends its input with staged canonical workout updates and explicit assembly status.

- [ ] **Step 1: Add real transaction interruption tests.**

Seed a historical sleep/HR source and summary. Delete the source and append dirty work in one transaction; close/reopen the file DB before enqueue. Assert source absent and journal present. Replay the same deletion and assert the original dirty entry survives even though `affectedDatesForRecord` now returns empty. Roll back a throwing mutation and assert both source and journal unchanged.

For publication, throw after summary insert but before `advance`; reopen and assert old summary plus pending ticket. Successful publication must update both. Inject a newer mutation after ticket capture: it must prevent stale acknowledgment, and newer dirty work must remain.

```kotlin
assertEquals(1, database.dirtyRangeDao().pending(100).size)
assertEquals(expectedOldSummary, database.dailySummaryDao().getByDate(dayMs))
```

Bind `dayMs` and `expectedOldSummary` to the seeded row in the instrumented fixture. Include pending range [D,D+100], complete only D and assert `nextDay=D+1` rather than deleting the range.

- [ ] **Step 2: Run `DirtyMutationRecoveryInstrumentedTest` red with the core database connected-debug task.**

- [ ] **Step 3: Implement ownership and document lock ordering.**

One application-scoped coordinator uses a coroutine Mutex. All mutation entry points, cleanup, rollup, restore, backup snapshot capture and settings changes affecting scoring acquire this owner; it checks durable maintenance state before ordinary admission. Existing `syncMutex` remains the daily/resync serialization seam and acquires the coordinator next. Order: sync mutex (if applicable) → coordinator → calculation mutex → Room transaction. Maintenance never waits for a worker while holding a lock that worker needs. Internal store/score helpers do not reacquire the non-reentrant coordinator.

```kotlin
private val mutex = kotlinx.coroutines.sync.Mutex()

override suspend fun <T> withMutation(block: suspend () -> T): T = mutex.withLock {
    check(stateDao.current().maintenanceOperationId == null) { "MAINTENANCE_PENDING" }
    block()
}
```

`stateDao.current(): HealthMutationStateEntity` is a new `SELECT * ... WHERE id=1` DAO method in P1's state DAO. `withMaintenance` persists/validates operation ID under the same lock, invokes the block, and clears only after the durable operation reaches a verified completed state. Exceptions retain recovery state. R3 owns the detailed restore state machine. Coroutine cancellation must propagate and release in-memory locks without clearing incomplete durable state.

- [ ] **Step 4: Make mutations and dirty extents one transaction.**

Capture old extents before delete/upsert; derive new extents from complete payloads. Resolve overlapping sleep/workout score days with existing sleep-day policy, including a previous assigned sleep day. Convert using the captured scoring zone. Record the earliest affected retained score day through the fixed run's end/today; use conservative retained suffix, not `ScoreInvalidation`'s fixed 84-day widening. Include sleep/HRV/vital-only cleanup even when no HR exists. Keep local retained dates older than the HC resync horizon eligible for local recomputation.

Increment `health_mutation_state.sourceGeneration` and append each dirty entry in the same transaction. The generation is a data revision, not a timestamp. Identical replay does not increment it. Config-only invalidation uses a new snapshot ID with unchanged source rows. Finish affected writes before any WorkManager enqueue; startup drain closes the enqueue crash gap.

- [ ] **Step 5: Publish daily derived writes with acknowledgment.**

Read/compute under the current captured source generation and immutable settings, outside a writer transaction. The dirty row's `sourceGeneration` identifies its originating mutation; it is not the expected current generation. Pass `expectedSourceGeneration` separately so older pending tickets can drain after later mutations. Stage canonical workout updates instead of writing them midway in `DailyTrimpComputer`. Inside one bounded day transaction compare live generation with `expectedSourceGeneration`, recheck active snapshot/maintenance ownership, persist summary plus recommendation/canonical updates, advance `nextEpochDay` by exactly one using the ticket's original ID/generation/cursor and delete only completed tickets. If settings supersede a ticket's snapshot, transactionally replace/rebind its uncompleted suffix to the new run before computing; a separate config-wide ticket covers already-completed dates requiring repair. Never discard a suffix while rebinding. Return false on stale ticket, preserving dirty work. Update walk-forward in-memory contexts only after commit; rebuild them after failed publication.

Startup and scheduler query `pending(100)` and enqueue the existing durable resync path in recompute-only mode; they never create HC tokens or update lastSyncTimestamp. Progress stays on existing `RecalcProgress`/WorkInfo keys. Failed/canceled days remain pending and retain their last complete published generation.

- [ ] **Step 6: Verify maintenance races and suffix correctness, document and commit.**

Add concurrent daily sync/resync/cleanup/rollup/restore tests, no-HR sparse history, scoring-zone DST and old correction beyond 84 days; compare with a full ascending rebuild. Run shared gates and exact instrumented interruption suite. DATA_FLOW documents lock order and both transaction boundaries. `codegraph index/sync`; suggested message: `fix: couple health mutations to durable rescoring`.

### Task P3: Authoritatively replace complete source payloads (WP-05)

**Files and responsibilities:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/model/HealthConnectRecords.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/HealthIngestionStore.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/mappers/HeartRateMapper.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/mappers/HrvMapper.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthConnectRecordConverters.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RoomHealthIngestionStore.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/HealthIngestionInputMappers.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RoomHealthIngestionStore.kt` — its nested `HealthRecordDeletionReconciler` object
- Modify: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/HeartRateDao.kt`
- Modify: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/HrvDao.kt`
- Modify: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/SourceRecordDao.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/SourcePayload.kt`
- Create: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/SourcePayloadWriter.kt`
- Test: `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/RoomHealthIngestionStoreTest.kt`
- Test: `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/RoomHealthIngestionStoreReconcileTest.kt`
- Create: `core/database/src/androidTest/kotlin/app/readylytics/health/core/database/data/local/SourceReplacementInstrumentedTest.kt` — new behavior test

**Interfaces:** Produces explicit metadata and complete-parent containers; no parsing new IDs:

```kotlin
data class SourceMetadata(
    val sourceId: String, val recordType: String, val originPackage: String?,
    val startMs: Long, val endExclusiveMs: Long, val lastModifiedMs: Long?,
)
data class SourcePayload<T>(val source: SourceMetadata, val rows: List<T>)
```

Replace streamed sample methods with `replaceHeartRateSources(sources: List<SourcePayload<HeartRateInput>>)` and `replaceHrvSources(sources: List<SourcePayload<HrvInput>>)`. Retain per-source containers even for zero children. Add `sourceId: String` explicitly to timestamp-suffixed vital inputs; metadata travels from SDK converter through domain records. Existing legacy backup ID decoding remains version-scoped, not an ingestion fallback.

- [ ] **Step 1: Extend B1's numeric test to full payload parity.**

In the real Room replacement fixture, use source `opaque_id_with_underscores`, timestamps [1000,2000], values [60,70]. Correct it to only [2000→81], then empty, then [3000→90]. After each replacement compare all columns with a separately clean-imported DB; source ref stays unchanged. Replay each twice and assert unchanged generation on identical payload. Repeat HRV 40f→64f and moved weight/BP/temperature/SpO2/body-fat timestamps. Include same-millisecond duplicate characterization without claiming nanosecond precision.

```kotlin
val meta = SourceMetadata("opaque_id_with_underscores", "HEART_RATE", "fixture.origin", 0, 4000, null)
store.replaceHeartRateSources(listOf(SourcePayload(meta,
    listOf(HeartRateInput("display-only", 2000, 81, "RESTING", null, "watch")))))
assertEquals(listOf(81), database.heartRateDao().getByTimeRange(0, 4000).map { it.beatsPerMinute })
```

- [ ] **Step 2: Run the store suites red, including B1 numeric assertions.**

```bash
./gradlew :core:database:testDebugUnitTest --tests '*RoomHealthIngestionStore*'
```

- [ ] **Step 3: Fix numeric UPSERT columns and changed-row predicates.**

Add `beatsPerMinute = excluded.beatsPerMinute` and `rmssdMs = excluded.rmssdMs` to respective conflict clauses. Include numeric differences in the null-safe no-op guard alongside metadata differences. Do not use source-parent `INSERT OR REPLACE`, which can change FKs/cascade children.

- [ ] **Step 4: Implement complete-source atomic replacement and provenance.**

`SourcePayloadWriter` opens one transaction per complete parent (batching several small parents is allowed). Resolve/insert stable source ref, read old child keys/extents, compare payload, upsert new rows in chunks of 500, then delete only old timestamps absent from the complete new parent. Keyset-read old keys and delete chunks small enough for the verified driver binding limit; avoid unbounded `NOT IN`. Empty payload deletes all children for that source, not its identity. Update authoritative source bounds/origin/revision and append old/new dirty work through P2 before commit.

A fetched parent already includes all nested HR samples; do not split its publication across transactions. Large parent processing may take longer until Phase-2 staging, but readers must see old or complete new payload. No provider call or retry inside this transaction. A failure before it begins leaves prior data untouched; cancellation midway rolls the whole parent back.

For vital timestamp-keyed rows, target explicit source ID ownership and remove old family rows atomically; don't accept a base ID merely because a new timestamp exists. Keep supported enum string keys stable. New range reconciliation uses authoritative interval overlap or point membership under half-open bounds, not `createdAtMs`; CHILD_BOUNDS/UNKNOWN are non-destructive until refreshed. The safe conservative exclusion must be visible as unreconciled metadata.

- [ ] **Step 5: Route snapshot and Changes upserts through the same writer.**

Mappers emit one container per HC parent, including empty sample arrays. Remove delete-before-upsert from delta paths once the shared replacement method is wired. H5 handles workout enrichment separately. Preserve source identity on every overlap/retry and full-range session reconciliation after chunks. Audit all constructors and fakes with `rg 'persistHeartRateSamples|persistHrvSamples|substringBefore'` so no stale adapter bypasses the new contract.

- [ ] **Step 6: Validate failure atomicity and documentation, then commit.**

Real Room fault injection throws after the first 500-row write of a >500-sample parent: all old children and generation must survive. Compare full/delta/clean snapshots and summaries; vary page order and size to prove range membership independence. Run DATA_FLOW/backup metadata updates, shared gates and `codegraph index/sync`. Suggested message: `fix: replace complete health source payloads`.
