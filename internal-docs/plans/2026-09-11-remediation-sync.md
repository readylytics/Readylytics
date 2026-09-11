# Health Connect Sync Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make resumed scans, permissions, supported-type corrections and historical runs converge safely.

**Architecture:** Use complete source payloads from P3, typed read outcomes and immutable job identity. Persist local mutations through the journal; acquire remote enrichment before transactions and retain full-range session reconciliation.

**Tech Stack:** Existing Kotlin/Compose/Room/SQLCipher/Health Connect/WorkManager/DataStore modules; JUnit, MockK, existing Robolectric adapters, and instrumented Room/benchmark fixtures. No dependency upgrades or new Gradle modules.

**Spec:** `internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md`, WP-06–10; HC-002–006. Read it and [the execution index](2026-09-11-remediation-phase-0-1-index.md) before this plan. Finding IDs refer to that spec, not similarly named historical source comments.

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

Execute H2 before H1 because H1 consumes its typed completeness contract, then H3–H6. This is a dependency ordering adjustment, not additional scope.

### Task H1: Restart incomplete scans before deletion reconciliation (WP-06)

**Files and responsibilities:**
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HealthIngestionCoordinator.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/ResyncRangeUseCase.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/ResyncCheckpointStore.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/preferences/ResyncCheckpointStoreImpl.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/CompleteTypeScan.kt`
- Test: `core/healthconnect/src/test/kotlin/app/readylytics/health/core/healthconnect/domain/sync/ResyncCheckpointResumeTest.kt`
- Create: `core/healthconnect/src/test/kotlin/app/readylytics/health/core/healthconnect/domain/sync/CompleteTypeScanTest.kt` — new behavior test

**Interfaces:** Consumes H2 `ReadOutcome<Unit>` at paged-read completion. Produces `CompleteTypeScan(type: HealthDataType, windowStartMs: Long, windowEndExclusiveMs: Long, sourceSelectionId: String, ids: Set<String>)`; only an `Available(Unit)` scan can create it. Replace untyped `reconcileWindow` arguments with this completion object plus zone. Phase-1 seen IDs remain in memory for the restarted complete chunk; S3 persisted scan staging is Phase 2.

- [ ] **Step 1: Add page-interruption regressions.**

Use existing checkpoint fixture/fake provider. Seed A,B,C; delete B before capturing baseline token. Provider pages contain A then C. Interrupt after A's database commit but before/after its checkpoint write; restart new coordinator with saved checkpoint. Expected final IDs A,C, unchanged integer refs and no B. Repeat for HRV; interrupt during C upsert, reconciliation and chunk advancement. Denial/cancellation after A must leave B/C untouched until a later complete scan.

```kotlin
@Test
fun `only complete available scans authorize absence`() {
    assertTrue(ReadOutcome.Available(Unit).isComplete())
    assertFalse(ReadOutcome.Denied.isComplete())
    assertFalse(ReadOutcome.Unsupported.isComplete())
}
```

Define `fun ReadOutcome<Unit>.isComplete(): Boolean = this is ReadOutcome.Available` in `CompleteTypeScan.kt`; behavioral provider/Room assertions are required in addition to this pure guard test.

- [ ] **Step 2: Run `./gradlew :core:healthconnect:testDebugUnitTest --tests '*ResyncCheckpointResumeTest' --tests '*CompleteTypeScanTest'` red.**

- [ ] **Step 3: Replay incomplete HR/HRV chunk types from their beginning.**

Clear only the affected type's saved page token and in-memory seen set when reconstructing an interrupted scan. Re-fetch its full current chunk, preserving already committed complete source payloads. Collect IDs from parent containers including zero-sample parents. Reconcile only after that type returns Available completion. Mark completion only after reconciliation transaction succeeds, then checkpoint the next chunk. Never retain a "completed" marker without matching durable reconciliation evidence; if in doubt replay that type.

```kotlin
val replayStartToken: String? = null
// Use this for an interrupted type until Phase-2 durable seen-ID staging exists.
```

Remove the `collectReconcilableTypes` rule that silently excludes resumed HR/HRV yet lets the chunk succeed. Rejected provider page token means incomplete scan/replay, never empty success. No raw `deleteAll` or partial-set pruning.

- [ ] **Step 4: Gate token promotion and prove restart parity.**

Promote captured baseline tokens only for types with complete ingest/reconcile and durable dirty work acknowledged for this run. A denied type is suspended by H2; it does not perpetually block unrelated permitted types, and its old token is not promoted. Recompute-only paths cannot promote tokens. Inject stop after every checkpoint/commit boundary and compare rows/summaries with uninterrupted clean import.

- [ ] **Step 5: Update DATA_FLOW/checkpoint compatibility and commit.**

Old incomplete checkpoint formats replay conservatively; preserve committed raw data/journal. Run targeted suites, shared gates and indexing. Suggested message: `fix: reconcile complete scans after resume`.

### Task H2: Model permission and read outcomes explicitly (WP-07)

**Files and responsibilities:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/HealthConnectRepository.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/HealthChangeTokenStore.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthConnectRepositoryImpl.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/StepRecordReader.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/IntervalTotalsReader.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthChangeSynchronizerImpl.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/StepCountFetcher.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/preferences/HealthChangeTokenStoreImpl.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/ReadOutcome.kt`
- Create: `core/model/src/test/kotlin/app/readylytics/health/core/model/domain/repository/ReadOutcomeTest.kt` — new behavior test
- Test: `core/healthconnect/src/test/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthChangeSynchronizerImplTest.kt`
- Create: `core/healthconnect/src/test/kotlin/app/readylytics/health/core/healthconnect/domain/sync/StepCountFetcherTest.kt` — new behavior test

**Interfaces:** All repository list/aggregate reads wrap their existing return type in `ReadOutcome`; paged methods retain callbacks for complete parents and return `ReadOutcome<Unit>`. Transient exceptions propagate; CancellationException is never translated. Add `suspend fun suspendType(dataType: HealthDataType): Unit` and `suspend fun isSuspended(dataType: HealthDataType): Boolean` to token storage; suspension clears that type's token and persists denied state atomically in DataStore.

- [ ] **Step 1: Add unavailable versus authorized-empty tests.**

```kotlin
@Test
fun `unavailable total preserves value while authorized empty is zero`() {
    assertEquals(4200L, ReadOutcome.Denied.valueOrPrevious(4200L))
    assertEquals(4200L, ReadOutcome.Unsupported.valueOrPrevious(4200L))
    assertEquals(0L, ReadOutcome.Available(0L).valueOrPrevious(4200L))
}
```

Fake read sequence: Granted, first page Available, SecurityException before second page, regrant before reconciliation. Assert no deletion authority from the later grant. Grant→sync→revoke→repeat twice→regrant must suspend only revoked type, avoid full-resync loop, preserve existing step values, and bootstrap before that type resumes delta sync.

- [ ] **Step 2: Run read/steps/token fixtures red.**

```bash
./gradlew :core:model:testDebugUnitTest --tests '*ReadOutcomeTest'
./gradlew :core:healthconnect:testDebugUnitTest --tests '*StepCountFetcherTest' --tests '*HealthChangeSynchronizerImplTest'
```

- [ ] **Step 3: Implement the pure outcome and adapter conversion.**

```kotlin
sealed interface ReadOutcome<out T> {
    data class Available<T>(val data: T) : ReadOutcome<T>
    data object Denied : ReadOutcome<Nothing>
    data object Unsupported : ReadOutcome<Nothing>
}
fun <T> ReadOutcome<T>.valueOrPrevious(previous: T): T = when (this) {
    is ReadOutcome.Available -> data
    ReadOutcome.Denied, ReadOutcome.Unsupported -> previous
}
```

Precheck access per actual type and attach outcome to that read. `SecurityException` becomes Denied; unavailable provider/feature becomes Unsupported. Explicitly rethrow CancellationException before other catches. A midstream denial returns Denied after already committed complete parents; it provides no scan-complete marker. Do not recheck grants later to turn an empty list into authoritative absence.

- [ ] **Step 4: Implement token suspension/regrant and steps propagation.**

At every delta invocation inspect grants for existing tokens too. Suspend denied types, preserve local rows, and exclude them from baseline token capture. Regrant requires a fresh type baseline token captured before a complete recovery scan; don't resurrect expired old tokens. `putAll` no longer blindly leaves denied tokens in its merged map. Failure/retry keeps each type's last safe state.

Keep `StepCountFetcher.fetchWindow/fetchRange` returning maps where absent day means no update. Fill explicit zero entries only after an Available read for that day/chunk, in all-device and selected-device modes. Remove eager zero seeding before remote results. Null retains existing steps through the existing `withStepCount` path; available empty selected-device results explicitly clear prior totals.

- [ ] **Step 5: Feature-gate history/background access and validate.**

Through the existing HC provider seam, probe pinned 1.1.0 public history/background feature flags before presenting/requesting those capabilities. Preserve critical-permission recovery deep links. Add resource strings for unavailable/denied recovery actions and update privacy/data-collection docs and DATA_FLOW. Device matrix: API26/27 unavailable provider; supported APK provider; framework HC; denied history; denied background; optional revoke/regrant; TOCTOU denial. No SDK upgrade or new health permission.

Run shared gates and indexing. Suggested message: `fix: distinguish denied reads from empty health data`.

### Task H3: Complete VO2 registry and correction lifecycle (WP-08, independent portion)

**Files and responsibilities:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/model/HealthDataType.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthChangeSyncSupport.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthChangeSynchronizerImpl.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HealthIngestionCoordinator.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RoomHealthChangeIngestionStore.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RoomHealthIngestionStore.kt` — its nested `HealthRecordDeletionReconciler` object
- Modify: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/Vo2MaxRecordDao.kt`
- Modify: `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/data/DataSourceSettingsViewModel.kt`
- Modify: `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/data/DataSettings.kt` — exhaustive data type label mapping
- Modify: `app/src/main/res/values/strings.xml` — authoritative new user-facing label
- Modify: `feature/settings/src/main/res/values/strings.xml` — matching feature resource symbol required by current module R ownership
- Test: `core/model/src/test/kotlin/app/readylytics/health/core/model/domain/model/HealthDataTypeTest.kt`
- Test: `core/healthconnect/src/test/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HealthIngestionCoordinatorVo2MaxTest.kt`
- Test: `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/RoomHealthChangeIngestionStoreTest.kt`

**Interfaces:** Append stable `HealthDataType.VO2_MAX`. Add DAO `suspend fun getById(id: String): Vo2MaxRecordEntity?` and `suspend fun deleteById(id: String): Int`. VO2 uses its existing stable HC ID without a timestamp suffix; deletion writes P2 dirty work using pre-delete timestamp.

- [ ] **Step 1: Add historical insert/update/delete parity fixtures.**

Seed VO2 at D−45, delete it through Changes, and compare full/delta imports against a clean DB. Run a complete empty scan, denied scan, selected-device change and stable timestamp tie. Assert no stale carry-forward VO2 after deletion; unrelated types remain intact.

- [ ] **Step 2: Run `./gradlew :core:healthconnect:testDebugUnitTest --tests '*HealthIngestionCoordinatorVo2MaxTest'` and the change-store fixture red.**

- [ ] **Step 3: Register every VO2 lifecycle dispatch and DAO operation.**

```kotlin
@Query("SELECT * FROM vo2_max_records WHERE id = :id")
suspend fun getById(id: String): Vo2MaxRecordEntity?
@Query("DELETE FROM vo2_max_records WHERE id = :id")
suspend fun deleteById(id: String): Int
```

Add VO2 to existing permission/type mapping, baseline token capture, Changes upsertion/deletion, complete scan ID reconciliation, affected-date lookup, selected-source pruning and settings type mapping. Preserve all old enum names and old preferences/backup decoding. Device selection retains existing semantics. Derive local invalidation through retained suffix (VO2 can carry forward); do not reread all history for one deletion.

- [ ] **Step 4: Resource-map the added UI label and verify exhaustive switches.**

Add `data_type_vo2_max` = `VO₂ max` in app strings.xml and its feature/settings resource mirror, then add `HealthDataType.VO2_MAX -> R.string.data_type_vo2_max` to the existing mapping at `DataSettings.kt:394`. Keep app and feature copies identical; feature/settings cannot import the app module's R class. Do not introduce a user-visible hardcoded `displayName` string: replace label use with a resource mapping at the presentation boundary, keeping enum identifiers in pure domain. Audit exhaustive `when` and fake repositories using `rg 'HealthDataType'`. Update DATA_FLOW and public permission/score explanation copy only where behavior changes.

- [ ] **Step 5: Run module suites/shared checks and commit `fix: reconcile VO2 updates and deletions`.**

### Task H4: Track distance/elevation corrections independently (WP-08, OD-4 gate)

**Files and responsibilities:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/model/HealthConnectRecords.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/IntervalTotalsReader.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthChangeSyncSupport.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthChangeSynchronizerImpl.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RoomHealthChangeIngestionStore.kt`
- Modify: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/SourceRecordDao.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/IntervalChange.kt`
- Create: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/WorkoutEnrichmentRefresher.kt`
- Create: `core/healthconnect/src/test/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/WorkoutEnrichmentRefresherTest.kt` — new behavior test

**Interfaces:** Gate OD-4 first. Proposed exact policy uses `IntervalChange(sourceId: String, kind: IntervalKind, oldStartMs: Long?, oldEndExclusiveMs: Long?, newStartMs: Long?, newEndExclusiveMs: Long?)`, where `IntervalKind { DISTANCE, ELEVATION_GAINED }`. Preserve explicit source ID/origin/start/end on `DomainIntervalTotal`; never encode these as workout parent changes.

- [ ] **Step 1: Test overlap invalidation and actual enrichment refresh.**

```kotlin
@Test
fun `half open intervals overlap without touching neighbors`() {
    assertTrue(overlaps(10, 20, 19, 30))
    assertFalse(overlaps(10, 20, 20, 30))
}
```

Provider fixture: workout [10,30), distance source [15,20) changes alone; then delete it with only source ID; move source to [40,50). Assert old and new overlapping workouts refresh, boundary-only neighbors do not, and authorized empty clears totals while Denied retains them.

- [ ] **Step 2: Run refresher tests red.**

- [ ] **Step 3: Persist interval identity and bootstrap own-type tokens.**

```kotlin
fun overlaps(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean =
    aStart < bEnd && bStart < aEnd
```

Store DISTANCE/ELEVATION_GAINED metadata in P1's existing source table; no new quantity table is needed because provider totals remain authoritative. Add an ingestion-only token type registry separate from display `HealthDataType` if exposing these types would add unrequested controls. Persist per-type tokens/suspension by stable names; retain old HealthDataType adapters for compatibility. Initial capture→complete interval scan establishes metadata; after that an ID-only delete can resolve its old extent. Unknown legacy interval identity cannot justify clearing totals; queue an authorized bounded interval bootstrap.

- [ ] **Step 4: Refresh overlapping workouts outside transactions, then publish.**

For old∪new extents, query overlapping stored workout IDs in bounded batches. Fetch current distance/elevation by origin with existing `SessionTotalsResolver`; preserve attribution. Apply H5 prepared enrichment outcomes, record dirty extents and interval source metadata/deletions atomically. Capture actual old extent before source deletion so replay keeps invalidation. No full-history reread or new device deduplication policy.

- [ ] **Step 5: Run independent interval edit/delete/revoke fixtures, document and commit.**

With this proposed schema reuse, no 20→21 migration is needed; update the index migration ledger accordingly when OD-4 is accepted. Ensure source backup serialization includes these kinds and legacy decoders tolerate them. Shared checks/indexing; message: `fix: refresh workouts after interval corrections`.

### Task H5: Prepare exercise enrichment before local application (WP-09)

**Files and responsibilities:**
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthChangeSynchronizerImpl.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthConnectRepositoryImpl.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RoomHealthIngestionStore.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RoomHealthChangeIngestionStore.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/PreparedWorkout.kt`
- Create: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/WorkoutReadPreparer.kt`
- Test: `core/healthconnect/src/test/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthChangeSynchronizerRecordSyncTest.kt`
- Test: `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/WorkoutRouteIngestionPreservationTest.kt`

**Interfaces:** Produces `PreparedWorkout(workout: WorkoutInput, route: ReadOutcome<List<WorkoutRoutePoint>>, distanceMeters: ReadOutcome<Float?>, elevationMeters: ReadOutcome<Float?>)`. Available(empty/null) is authoritative removal; Denied/Unsupported preserves existing enrichment. A failed read propagates retry rather than becoming absence.

- [ ] **Step 1: Add transactional-provider and route preservation tests.**

Fake provider asserts `assertFalse(transactionActive)` in route/distance/elevation reads. Seed workout plus imported route; update parent while route is Denied and assert points preserved. Available(emptyList()) clears points. Delete workout explicitly and assert cascade. Throw after preparation but before application and assert previous workout byte-for-byte unchanged.

- [ ] **Step 2: Run record-sync/route-preservation tests red.**

- [ ] **Step 3: Split remote preparation from mutation.**

Prepare SDK exercise conversion, route consent outcome and both interval totals before invoking `TransactionRunner`. Move `sessionTotalFor` calls out of `processChangesPage`'s writer transaction. Commit prepared workouts through P3 store with update-in-place; remove `deleteRecord(EXERCISE,id)` from upsertion. Actual DeletionChange still deletes parent/children with P2 dirty journal.

```kotlin
fun <T> mergeEnrichment(old: T, read: ReadOutcome<T>): T = when (read) {
    is ReadOutcome.Available -> read.data
    ReadOutcome.Denied, ReadOutcome.Unsupported -> old
}
```

Use the same merger for bulk/delta. Derive avgSpeed from the selected available distance and duration; clear derived speed when distance is authoritatively absent. Preserve unreadable route independently from readable distance/elevation. An empty list caused by consent failure must map Denied, never Available(empty).

- [ ] **Step 4: Verify no provider read inside any Room transaction, update DATA_FLOW and commit.**

Run both targeted suites, real Room route FK fixture, shared checks and indexing. Message: `fix: prepare workout reads before mutation`.

### Task H6: Persist one immutable historical run (WP-10)

**Files and responsibilities:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/ResyncCheckpointStore.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/preferences/ResyncCheckpointStoreImpl.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/FullHistoricalResyncUseCase.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/ResyncRangeUseCase.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/workers/HealthResyncWorker.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/HistoricalRunIdentity.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/sync/ScoringRunSnapshot.kt`
- Create: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HistoricalRunResolver.kt`
- Create: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HistoricalIngestPhase.kt`
- Create: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HistoricalRecomputePhase.kt`
- Test: `core/healthconnect/src/test/kotlin/app/readylytics/health/core/healthconnect/domain/sync/ResyncCheckpointResumeTest.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/data/preferences/ResyncCheckpointStoreImplTest.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/workers/HealthResyncWorkerScoringVersionTest.kt`

**Interfaces:** Produces the following serializable record (epoch values avoid platform date serializers):

```kotlin
data class HistoricalRunIdentity(
    val protocolVersion: Int = 2,
    val runId: String,
    val mode: String,
    val startEpochDay: Long,
    val endEpochDayInclusive: Long,
    val zoneId: String,
    val startedAtEpochMs: Long,
    val sourceSelectionId: String,
    val algorithmRevision: Int,
    val scoringSnapshotJson: String,
    val scoringSnapshotId: String,
)
```

Modes are fixed `FULL_INGEST` and `RECOMPUTE_ONLY` constants. `ScoringRunSnapshot` is an explicit serializable value object containing all scoring preferences; its serialized content is persisted, not only a hash. Decode to the effective `UserPreferences` used by scoring. Never include backup password/location or unrelated UI state. `HistoricalRunResolver.resolve(existing: HistoricalRunIdentity?, requested: HistoricalRunIdentity): HistoricalRunIdentity` reuses an existing valid run rather than resolving today again.

- [ ] **Step 1: Add midnight/config tests using a fixed mutable test clock.**

Create a run ending 2026-03-28 in Europe/Berlin, checkpoint each INGEST/PRUNE/RECONCILE/RECOMPUTE phase, advance time through DST and midnight, retry and assert range/zone/snapshot unchanged. Enqueue today's follow-up separately. Change each scoring snapshot field one at a time; assert a coherent downstream restart and preservation of valid ingestion. Selection change requires new affected-type scans. Recompute-only must never write Changes tokens or lastSyncTimestamp.

```kotlin
assertEquals(savedRun, HistoricalRunResolver.resolve(savedRun, requestForTomorrow))
```

The fixture constructs both complete identities from declared fields; test incompatible protocol separately and ensure safe replay rather than reuse.

- [ ] **Step 2: Run checkpoint and worker tests red.**

- [ ] **Step 3: Capture explicit run data once and serialize it with checkpoints.**

At new work creation capture Clock instant, RetentionBounds range, stored scoring zone, sorted source selections, algorithm revision, baseline tokens and full canonical scoring snapshot. Include fields in current `scoringCheckpointIdentity` plus omitted material settings: sleepScoreWeightProfile, hypersomniaOnsetPercent, VO2 method/source, training-readiness residual/load-balance weights, profile/HRV priors, birth-date-derived age and resolved hrMax, source modes and zone thresholds. Derive values from one preference emission; no `LocalDate.now()` inside resume identity validation.

Use stable serialized fields and a deterministic SHA-256 identity rather than `hashCode()` or unsorted maps. Decode legacy checkpoints as incompatible and replay incomplete scans without deleting data or dirty work. Baseline tokens remain opaque and per type; never reuse them across mismatched scan coverage.



**Snapshot schema (H6 implementation content):** Put these serializable groups in `ScoringRunSnapshot.kt`; split them into `ScoringRunSnapshotParts.kt` if the file exceeds 400 lines after conversion functions. Part numbers are serialization keys, not mutable ordering. Enum fields below store stable enum names; nullable gender remains null when absent. The snapshot excludes backup credentials/location and UI preferences.

```kotlin
@kotlinx.serialization.Serializable
data class ScoringRunSnapshot(
    val part1: ScoringSnapshotPart1,
    val part2: ScoringSnapshotPart2,
    val part3: ScoringSnapshotPart3,
    val part4: ScoringSnapshotPart4,
    val part5: ScoringSnapshotPart5,
    val part6: ScoringSnapshotPart6,
    val part7: ScoringSnapshotPart7,
    val part8: ScoringSnapshotPart8,
    val resolvedHrMax: Float,
    val sourceSelection: Map<String, String>,
)

@kotlinx.serialization.Serializable
data class ScoringSnapshotPart1(
    val goalSleepHours: Float,
    val hrvBaselineOverride: Float?,
    val rhrBaselineOverride: Float?,
    val maxHeartRate: Int,
    val autoCalculateMaxHr: Boolean,
    val manualZoneEditing: Boolean,
    val zone1MinPercent: Float,
)

@kotlinx.serialization.Serializable
data class ScoringSnapshotPart2(
    val zone1MaxPercent: Float,
    val zone2MaxPercent: Float,
    val zone3MaxPercent: Float,
    val zone4MaxPercent: Float,
    val zone1MinBpm: Int,
    val zone1MaxBpm: Int,
    val zone2MaxBpm: Int,
)

@kotlinx.serialization.Serializable
data class ScoringSnapshotPart3(
    val zone3MaxBpm: Int,
    val zone4MaxBpm: Int,
    val age: Int,
    val birthDate: String?,
    val gender: String?,
    val heightCm: Float?,
    val hrvOptimalThreshold: Float,
)

@kotlinx.serialization.Serializable
data class ScoringSnapshotPart4(
    val hrvWarningThreshold: Float,
    val rhrOptimalThreshold: Float,
    val rhrWarningThreshold: Float,
    val restingHrPercentile: Int,
    val consistencyThresholdMinutes: Int,
    val consistencyEvaluationDays: Int,
    val consistencyBaselineDays: Int,
)

@kotlinx.serialization.Serializable
data class ScoringSnapshotPart5(
    val hrrToleranceSeconds: Int,
    val rasScalingFactor: Float,
    val stepGoal: Int,
    val physiologyProfile: String,
    val installDate: Long,
    val circadianThresholdOverride: String?,
    val trimpModel: String,
)

@kotlinx.serialization.Serializable
data class ScoringSnapshotPart6(
    val banisterMultiplier: Float,
    val chengBeta: Float,
    val itrimB: Float,
    val scoringZoneId: String,
    val strainLoadSourceMode: String,
    val rasSourceMode: String,
    val coreMergeGapMinutes: Int,
)

@kotlinx.serialization.Serializable
data class ScoringSnapshotPart7(
    val supplementalCutoffMinutesOfDay: Int,
    val minimumCountedSleepSegmentMinutes: Int,
    val supplementalArchitectureCoveragePercent: Int,
    val bodyTempElevatedThresholdCelsius: Float,
    val sleepScoreWeightProfile: String,
    val hypersomniaOnsetPercent: Int,
    val residualFatigueHalfLifeHours: Float,
)

@kotlinx.serialization.Serializable
data class ScoringSnapshotPart8(
    val residualFatigueGain: Float,
    val trainingReadinessResidualFatigueScale: Float,
    val trainingReadinessLoadBalanceWeight: Float,
    val lastAppliedTrainingReadinessResidualFatigueScale: Float?,
    val lastAppliedTrainingReadinessLoadBalanceWeight: Float?,
    val vo2MaxSourceMode: String,
    val vo2MaxEstimationMethod: String,
)
```

`ScoringRunSnapshot.capture(prefs: UserPreferences, resolvedHrMax: Float): ScoringRunSnapshot` assigns every listed property from one captured preference value, stores `prefs.scoringZone().id` for scoringZoneId, stable enum `.name` values, and `prefs.deviceByDataType.toSortedMap()` plus the primary-device fallback under a dedicated fixed key. `toPreferences(): UserPreferences` starts from `UserPreferences()` and explicitly copies every listed field back; enum fields use their existing type's `valueOf`, never localized names. Invalid decoded enums/protocol fail safe and trigger a fresh run. The encrypted circadian override remains encrypted; preserve its existing resolution seam and do not log it.

The pending versus applied training-readiness fields are both captured because `appliedTrainingReadinessConfig()` reads the latter. Add round-trip tests asserting every listed property, fixed scoring zone, birth-date/age behavior and resolved hrMax, then one-field-change identity tests. Hash stable JSON with sorted source keys; the persisted snapshot contents, not only the hash, reconstruct the run after death. Parameter holders group fields to avoid a giant constructor; if detekt still reports complexity, split by responsibility rather than suppressing.

- [ ] **Step 4: Extract phase runners so `ResyncRangeUseCase` stays below limits.**

Create `HistoricalIngestPhase.kt` and `HistoricalRecomputePhase.kt` alongside `HistoricalRunResolver.kt`; move the corresponding existing bodies with explicit run/context input objects, preserving `ensureActive`/`yield` and four-phase order. Settings changes invalidate only required phases: scoring-only → recompute; HR zones/source link policy → reconcile then recompute; source-selection change → affected-type ingest then later phases. Finish or supersede the saved run explicitly; don't overwrite its snapshot in place.

Worker requests reference saved run ID, retain unique `KEEP` and progress keys. Whole-pass transient failure returns WorkManager retry; canceled work preserves checkpoint. Probe provider token rejection on device; H1 complete-chunk replay remains fallback.

- [ ] **Step 5: Run retry parity, documentation and shared checks; commit.**

Compare resumed output with uninterrupted run under identical captured settings across every phase, including settings changes after some days published. Keep previous valid days until repaired. Update DATA_FLOW run protocol/lock ownership/migration notes; `codegraph index/sync`. Message: `fix: resume historical sync with immutable inputs`.
