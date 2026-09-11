# Authoritative Hot and Warm Coverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent partial-minute loss, duplicate tier coverage and stale warm-source contributions.

**Architecture:** Align rollup to complete minutes first. After OD-1, introduce explicit visible coverage plus immutable per-source minute contributions and staged full refresh; all readers and relinking select one committed generation.

**Tech Stack:** Existing Kotlin/Compose/Room/SQLCipher/Health Connect/WorkManager/DataStore modules; JUnit, MockK, existing Robolectric adapters, and instrumented Room/benchmark fixtures. No dependency upgrades or new Gradle modules.

**Spec:** `internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md`, WP-17; DB-002, CACHE-001, SCORE-005; §12 S2. Read it and [the execution index](2026-09-11-remediation-phase-0-1-index.md) before this plan. Finding IDs refer to that spec, not similarly named historical source comments.

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

### Task T1: Roll up only complete minutes (WP-17 independent fix)

**Files and responsibilities:**
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/DataRollupManager.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/MinuteCoverageBounds.kt`
- Create: `core/model/src/test/kotlin/app/readylytics/health/core/model/domain/sync/MinuteCoverageBoundsTest.kt` — new behavior test
- Create: `core/database/src/androidTest/kotlin/app/readylytics/health/core/database/data/local/CompleteMinuteRollupTest.kt` — new behavior test

**Interfaces:** Produces pure `completeMinuteCutoff(cutoffMs: Long): Long`, an exclusive lower-minute boundary. `DataRollupManager.rollupExpiredHotTier(cutoffMs: Long)` retains its signature until P2's captured zone/journal context is passed explicitly. No schema is required for this first fix.

- [ ] **Step 1: Add boundary tests and the real repeated-rollup fixture.**

```kotlin
@Test
fun `cutoff rounds down including pre epoch timestamps`() {
    assertEquals(60_000L, completeMinuteCutoff(90_000L))
    assertEquals(60_000L, completeMinuteCutoff(60_000L))
    assertEquals(0L, completeMinuteCutoff(59_999L))
    assertEquals(-60_000L, completeMinuteCutoff(-1L))
}
```

Seed two raw samples at 65,000/95,000ms in the same minute. Rollup cutoff 90,000 leaves both raw and no bucket. Rollup 120,000 writes one bucket with sampleCount=2 and removes both raw. Replay 120,000 and assert exact same bucket and no new dirty revision. Include sample exactly at cutoff and two devices sharing the minute.

- [ ] **Step 2: Run minute-boundary and instrumented rollup fixtures red.**

- [ ] **Step 3: Align cutoff and progress cursor.**

```kotlin
fun completeMinuteCutoff(cutoffMs: Long): Long = Math.floorDiv(cutoffMs, 60_000L) * 60_000L
```

Use `completeCutoff` for loop condition, dayEnd cap and termination fallback; use floorDiv for day bucket start as well. Do not process a remainder minute on repeated invocations. Keep read→aggregate→publish→delete atomic for the whole complete unit, with P2 dirty append in the same transaction and captured scoring zone rather than UTC. Do not rebuild a complete bucket from a raw fragment over an existing bucket; T2 owns that mixed-coverage case.

- [ ] **Step 4: Run real rollup tests, update DATA_FLOW and commit.**

Record the partial-minute bug fix, preserve coefficients and warm approximation description. Shared checks/indexing; message: `fix: roll up complete heart rate minutes`.

### Task T2: Add visible coverage and source contributions (WP-17, OD-1 gate)

**Files and responsibilities:**
- Modify: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/HrMinuteBucketEntity.kt`
- Modify: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/MinuteBucketDao.kt`
- Modify: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/MinuteBucketMaintenanceDao.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/HealthDatabase.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/DatabaseMigrations.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/DataRollupManager.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/SourcePayloadWriter.kt` — created by an earlier task in this set
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/BackupStreamWriter.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/RestoreBatchLoader.kt`
- Create: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/MinuteCoverageEntity.kt`
- Create: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/HrSourceMinuteContributionEntity.kt`
- Create: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/MinuteCoverageDao.kt`
- Create: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/MinuteCoveragePublisher.kt`
- Create: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/HeartRateRefreshStagingStore.kt`
- Create: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/StagedHeartRateEntity.kt`
- Create: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/StagedSourceMetadataEntity.kt`
- Create: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/HeartRateRefreshStagingDao.kt`
- Create: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/migration/Migration21To22.kt`
- Create: `core/database/schemas/app.readylytics.health.core.database.data.local.HealthDatabase/22.json`
- Create: `core/database/src/androidTest/kotlin/app/readylytics/health/core/database/data/local/MinuteCoverageMigrationTest.kt` — new behavior test
- Create: `core/database/src/androidTest/kotlin/app/readylytics/health/core/database/data/local/MinuteCoveragePublicationTest.kt` — new behavior test

**Gate:** OD-1 requires evidence before contribution schema/publication is enabled. Benchmark one-source-per-sample and dense-parent distributions from B2: bytes per source-minute, index/WAL/write amplification, deletion cost and peak staging disk. Record the selected policy in this plan/index. The following is the concrete contribution proposal; if rejected, revise T2/T3 around complete authorized refresh before implementation, never silently downgrade its deletion guarantee.

**Interfaces:** New coverage/contribution entities (Room defaults/Serializable required):

```kotlin
@Entity(tableName = "minute_coverage", primaryKeys = ["bucketStartMs"])
data class MinuteCoverageEntity(
    val bucketStartMs: Long,
    val visibleGeneration: Long,
    val tier: String,
    val quality: String,
    val sourceSelectionId: String?,
)
@Entity(tableName = "hr_source_minute_contributions",
    primaryKeys = ["sourceRecordRef", "bucketStartMs", "generation"],
    indices = [Index(value = ["bucketStartMs", "generation"])])
data class HrSourceMinuteContributionEntity(
    val sourceRecordRef: Long,
    val bucketStartMs: Long,
    val generation: Long,
    val firstSampleMs: Long,
    val lastSampleMs: Long,
    val deviceName: String,
    val bpmHistogram: String,
)
```

Tier values: `HOT`, `WARM`, `LEGACY_WARM`; quality values: `SOURCE_BACKED`, `LEGACY_UNKNOWN`. Add `ForeignKey(entity = HealthSourceRecordEntity::class, parentColumns = ["id"], childColumns = ["sourceRecordRef"], onDelete = ForeignKey.RESTRICT)` to the contribution entity and an index starting with sourceRecordRef (its primary key already provides that prefix). Add nullable/defaulted selection identity to the coverage schema. Add `generation: Long = 0` with matching `@ColumnInfo(defaultValue="0")` to `HrMinuteBucketEntity`; readers match it against visibleGeneration. Contributions reference stable source FK and keep original unlinked aggregate evidence; projected warm session/type buckets are derived views of it. `bpmHistogram` is a versioned integer-frequency encoding for the same plausible BPM domain as current rollup, not a merge of percentile sketches. Sum/count/min/max/percentiles derive from the histogram; timestamps remain an explicitly measured approximation.

- [ ] **Step 1: Add old-schema migration and single-coverage tests.**

Seed v19/S1 raw-only, warm-only and already-overlapping raw/warm minutes, then migrate. Assert old buckets preserved as LEGACY_UNKNOWN, source IDs stable and no reconstructed lineage invented. Existing overlap is unresolved: prefer the last complete published warm coverage while keeping raw quarantine evidence pending authorized refresh; do not concatenate both. A minute with no metadata keeps backward-compatible raw-only reading, never automatic deletion.

```sql
SELECT bucketStartMs, tier, quality FROM minute_coverage ORDER BY bucketStartMs;
PRAGMA foreign_key_check;
```

In publication fixture stage corrected values, throw before visibility switch, reopen and assert old visible data. Successful switch yields exactly one tier and expected count/weighted mean. An identical second switch is a no-op.

- [ ] **Step 2: Run migration/publication fixtures red and reserve the next schema version.**

Use reserved migration 21→22 after C5. Add `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/migration/Migration21To22.kt` with CREATE TABLE SQL matching these fields/defaults/FKs/indexes; export `core/database/schemas/app.readylytics.health.core.database.data.local.HealthDatabase/22.json`. If execution HEAD or an accepted OD-4 design changes this sequence, revise the ledger and every affected path before coding. Preserve legacy exported schemas.

- [ ] **Step 3: Publish new complete-minute rollup from immutable source contributions.**

Group complete raw minutes by explicit sourceRecordRef, count the existing plausible integer BPM values into histogram bins, store first/last observed timestamps and device. Derive linked aggregate buckets using existing SessionLinker ordering, then atomically insert contributions + buckets, set coverage WARM/SOURCE_BACKED, delete the consumed raw rows and append P2 dirty work. Check captured source generation before commit. Cancel/fail leaves all prior raw/buckets intact.

Never infer a contribution's source by session ID or sketch. Legacy buckets remain separate; do not retrofit invented contributions. Source selection filters contributions by existing origin/device policy; a complete authorized refresh includes every source in the selected scope before visibility changes.

- [ ] **Step 4: Stage corrected warm intervals before changing visibility.**

`HeartRateRefreshStagingStore` persists incoming complete source payloads and metadata under `(runId, sourceId)` with staged child rows, separate from live raw tables. Add `StagedHeartRateEntity` and `StagedSourceMetadataEntity`/DAOs in the same S2 migration; columns mirror current HR child/source metadata plus run ID and complete-payload marker. Do not put unbounded nested samples in one JSON blob.

```kotlin
@Entity(tableName = "staged_hr_sources", primaryKeys = ["runId", "sourceId"])
data class StagedSourceMetadataEntity(
    val runId: String, val sourceId: String, val recordType: String,
    val originPackage: String?, val startMs: Long, val endExclusiveMs: Long,
    val lastModifiedMs: Long?, val payloadComplete: Boolean,
)
@Entity(tableName = "staged_hr_samples", primaryKeys = ["runId", "sourceId", "timestampMs"])
data class StagedHeartRateEntity(
    val runId: String, val sourceId: String, val timestampMs: Long,
    val beatsPerMinute: Int, val recordType: String, val sessionId: String?, val deviceName: String?,
)
```

Keep the staged source key textual until publication allocates/resolves its stable live source ref; staging must not mutate live authoritative metadata just to obtain an FK. Implement `HeartRateRefreshStagingStore.stage(runId: String, payload: SourcePayload<HeartRateInput>): Unit` as one transaction replacing that staged parent's rows and setting payloadComplete only after all children arrive. Empty complete parents retain their metadata row.

For a full resync overlapping warm history, collect complete authorized type scans using H1. Normalize refresh scope to complete minutes, include all overlapping source payloads and selection identity, and reconcile absent IDs before publication. A source straddling the scope edge must not publish only part of its payload: expand/stage its affected complete-minute coverage or leave it pending until all required coverage is present. Stage interrupted work durably; it grants no prune/visibility authority by itself.

Within a single publish transaction for the complete source/coverage unit, remove superseded buckets/contributions, replace stable source payloads/metadata, install either authoritative HOT rows or freshly rebuilt WARM contributions, switch coverage and append dirty work. Use set-based INSERT/DELETE from staging rather than materializing the unit in heap. Keep the entire parent old-or-new atomic contract. Large overlapping source units may hold the writer longer in Phase 1; measure this limit and don't claim Phase-2 bounded publication latency.

For a delta delete with SOURCE_BACKED contributions, remove only that source's contribution and regenerate affected visible minute projections atomically. For LEGACY_UNKNOWN data without provider access, retain the last valid approximate generation and pending unresolved repair; never subtract a guessed contribution or blanket-delete the minute. Authorized complete refresh can replace legacy coverage even when it is empty.

- [ ] **Step 5: Serialize coverage in backups and verify recovery.**

Archive current contributions/coverage with source FKs, generation and quality; S2 inventory policy requires these tables for the new format. Old archives initialize LEGACY_UNKNOWN and never mix tiers by concatenation. Staged unpublished work is local operational state: omit it from exported health data and regenerate dirty work on restore. Register every new entity, DAO, migration, serialization default and source-FK relation. Restore order is sources→contributions/coverage/projections, with FK/count checks before commit.

Run publication fault boundaries, source moves/deletes/selected device, old backup restoration and low-space migration/staging rollback. Update DATA_FLOW plus About/public backup/retention docs; shared gates/index/sync. Message: `feat: persist authoritative heart rate coverage`.

### Task T3: Select one generation in every reader and full-range relink (WP-17)

**Files and responsibilities:**
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringHistoryRepositoryImpl.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringDayDataLoader.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/HeartRateRepositoryImpl.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/SessionLinkReconcilerImpl.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/WarmTierReconstructor.kt`
- Modify: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/HeartRateDao.kt`
- Modify: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/MinuteBucketDao.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/ResyncRangeUseCase.kt`
- Create: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/AuthoritativeHeartRateReader.kt`
- Create: `core/database/src/androidTest/kotlin/app/readylytics/health/core/database/data/local/AuthoritativeHeartRateReaderTest.kt` — new behavior test
- Test: `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/WorkoutModelTrimpIngestionDeterminismTest.kt`

**Interfaces:** `AuthoritativeHeartRateReader` owns SQL coverage predicates shared by chart, scoring, baseline and workout loaders. It returns the existing domain sample/projection types plus existing/new quality metadata, never both raw and warm for a visible minute. Public consumers keep Room-backed access. No chart downsampling work is added here.

- [ ] **Step 1: Add cross-reader and relinking equivalence fixtures.**

For raw-only/warm-only/mixed boundary/legacy overlap, assert sampleCount and weighted mean agree across readers and include exactly one generation. Reimport identical history, edit/delete a rolled source and relink a sleep/workout crossing both a minute and chunk boundary. Compare uninterrupted versus killed/retried and two different chunk alignments under identical tier/config.

```kotlin
assertEquals(referenceCount, scoringProjection.size)
assertEquals(referenceMean, workoutAverage, 0.0001f)
assertEquals(referenceCanonicalTrimp, replayCanonicalTrimp, 0.0001f)
```

Reference derives from immutable stored contribution histograms and deterministic reconstruction for that tier, independently of production SQL selection. Compare integer identities/counts exactly; document 1e-4 numerical accumulation tolerance for Float results and separately report hot-versus-warm approximation error. A repeated pass cannot drift by progressively reconstructing its own output.

- [ ] **Step 2: Run reader/relink/canonical-workout tests red.**

- [ ] **Step 3: Apply one visibility predicate to every tier consumer.**

Raw query excludes any minute whose committed coverage tier is WARM/LEGACY_WARM. Warm query includes only that minute's visible generation/tier. HOT coverage excludes all older warm projections. Missing metadata permits legacy raw-only or warm-only compatibility; overlap is explicitly unresolved under T2 policy, never sum/concatenate. Use the same predicate for means, percentile projections, charts, workout HR, baseline coverage and count checks.

```sql
SELECT h.* FROM heart_rate_records h
LEFT JOIN minute_coverage c ON c.bucketStartMs =
  (h.timestampMs / 60000 - CASE WHEN h.timestampMs % 60000 < 0 THEN 1 ELSE 0 END) * 60000
WHERE h.timestampMs >= :startMs AND h.timestampMs < :endMs
  AND (c.bucketStartMs IS NULL OR c.tier = 'HOT');
```

The CASE correction implements epoch floor for negative timestamps, matching T1. Add matching warm selection and indexes based on `EXPLAIN QUERY PLAN`; do not add unmeasured performance indexes indiscriminately.

- [ ] **Step 4: Relink warm projections from stable evidence, once over the full range.**

Keep `SessionLinkReconciler.reconcile(start,end,zoneThresholds)` after all ingest/prune, over the complete session list. For SOURCE_BACKED minutes, reconstruct timestamp/sample evidence deterministically from each original contribution's histogram and first/last timestamps, then apply unchanged sleep>workout>resting ties `(startTime,id)`. Derive session/type buckets from this original evidence every time, never from last pass's linked buckets. Warm temporal distribution remains approximate and is labelled/measured. A complete authorized refresh replaces it with fresh source evidence.

Legacy buckets lacking source/time evidence cannot promise exact relinking. Preserve them as approximate/unresolved and request complete authorized interval refresh; do not publish a newly "corrected" canonical score on guessed lineage. Recompute affected workout zones/avgHr/TRIMP through existing `WorkoutMapper.computeMetrics` and C5 canonical resolver after link projection commit. Dirty work remains until every dependent day is safely published.

- [ ] **Step 5: Run full within-tier repair parity and release gates.**

Test stable session ties, straddling sessions, selected-device changes, killed refresh, partial permission loss and repeated rollup. Run C5 canonical surface matrix after reader changes, schema upgrade/restore tests and source-removal fixtures. Update DATA_FLOW determinism-across-tiers note and all scoring/public docs with legacy limitations. Run shared gates and final `./gradlew lintRelease`; `codegraph index/sync`. Message: `fix: read and reconcile one heart rate generation`.

Phase 1 is complete only after T2/T3 decision/evidence gates pass or the affected feature is explicitly withheld from release. Preserving unknown legacy data is a recovery policy, not proof that inaccessible old history has been corrected. Phase-2 scan staging, streaming rollup optimization and throughput budgets remain separately scoped.
