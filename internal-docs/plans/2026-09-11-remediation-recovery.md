# Recoverable Backup and Restore Operations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep backups and restored data recoverable across concurrent mutation, publication failures and process death.

**Architecture:** Use P2 shared maintenance ownership and a separate durable backup-operation journal. Capture a coherent data/settings generation, stage encrypted archives under unique identities, verify them, then switch the selected generation/credential.

**Tech Stack:** Existing Kotlin/Compose/Room/SQLCipher/Health Connect/WorkManager/DataStore modules; JUnit, MockK, existing Robolectric adapters, and instrumented Room/benchmark fixtures. No dependency upgrades or new Gradle modules.

**Spec:** `internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md`, WP-16; ARCH-001, SEC-002–005. Read it and [the execution index](2026-09-11-remediation-phase-0-1-index.md) before this plan. Finding IDs refer to that spec, not similarly named historical source comments.

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

### Task R1: Export a coherent generation with encrypted staging (WP-16 / SEC-003/005)

**Files and responsibilities:**
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/BackupStreamWriter.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/LocalBackupManager.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/BackupModels.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/LocalBackupServiceImpl.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/DatabaseReadyStartupInitializer.kt`
- Modify: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/SourceRecordDao.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/data/backup/BackupSnapshotExporter.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/data/backup/LocalBackupSerializationRegressionTest.kt`
- Create: `app/src/androidTest/kotlin/app/readylytics/health/data/backup/BackupSnapshotConsistencyTest.kt` — new behavior test

**Interfaces:** New `BackupSnapshotIdentity(sourceGeneration: Long, scoringSnapshotId: String, schemaVersion: Int, exportedAtEpochMs: Long)` in `BackupModels.kt`; `BackupSnapshotExporter.captureEncrypted(target: File, password: CharArray): BackupSnapshotIdentity`. `BackupStreamWriter.writeJsonStreaming(outputStream: OutputStream, preferences: UserPreferencesBackup, identity: BackupSnapshotIdentity): Unit` no longer loads live preferences independently.

- [ ] **Step 1: Add a forced interleaving snapshot test.**

Seed source+HR+warm+summary and settings A. Pause export after source-table page; launch sync correction, rollup and settings B. Resume export, restore archive into an isolated DB and assert source FKs/counts/tier/config all belong to one generation. The blocked mutations can complete only after snapshot capture. Use latches/deferreds, not timing sleeps.

```kotlin
val reachedPage = kotlinx.coroutines.CompletableDeferred<Unit>()
val releasePage = kotlinx.coroutines.CompletableDeferred<Unit>()
// The exporter page hook completes reachedPage, then awaits releasePage.
// Start competing mutations only after reachedPage.await(); releasePage.complete(Unit) resumes export.
```

Record a deliberate failure during page N, close/reopen app and assert no incomplete archive is advertised and last valid backup remains.

- [ ] **Step 2: Run `BackupSnapshotConsistencyTest` with the app connected-debug task red.**

- [ ] **Step 3: Freeze the logical snapshot through shared ownership.**

For Phase 1, hold the P2 maintenance owner while streaming the health tables to a private encrypted staging archive. Every health/scoring/config/layout mutation must already use that owner; audit direct DAO writers before declaring consistency. Capture preferences/layouts and generation once inside admission. No Room writer transaction spans serialization, ZIP or filesystem work; stable reads are guaranteed by excluding health mutations for the capture interval. Audit/log tables not in the archive need not block. Capture is cancellable and yields between pages.

This correctness-first approach pauses health mutations during capture; measure it and report the limitation. Do not claim a nonblocking WAL snapshot or Phase-2 scalability. Once encrypted capture is finished/verified, release the health maintenance gate before SAF publication, while the backup-operation owner remains held. If excluding all writers cannot be proven, use a dedicated read-snapshot adapter and measure WAL lifetime before enabling export; don't rely on unrelated per-DAO reads.

- [ ] **Step 4: Stream JSON directly into the existing ZIP encryption API.**

```kotlin
val parameters = net.lingala.zip4j.model.ZipParameters().apply {
    fileNameInZip = "backup.json"
    isEncryptFiles = true
    encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES
    aesKeyStrength = net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256
}
net.lingala.zip4j.io.outputstream.ZipOutputStream(target.outputStream(), password).use { zip ->
    zip.putNextEntry(parameters)
    writer.writeJsonStreaming(zip, capturedPreferences, identity)
    zip.closeEntry()
}
```

`writer`, `capturedPreferences`, `identity` are exporter members/locals captured at admission; never close the ZIP from inside `BackupStreamWriter` (flush only). Put private staging in an app-owned operation directory. S3 startup cleanup removes old plaintext leftovers; new creation leaves no plaintext JSON. On cancellation close/delete incomplete encrypted staging, preserving originals/journal recovery targets.

Add source keyset paging now as a snapshot safety prerequisite: `SELECT * FROM health_source_records WHERE id > :afterId ORDER BY id LIMIT :limit`. This prevents loading a million source rows during capture; broader export/rollup optimization remains Phase 2. Counts and rows refer to the same excluded-writer generation; re-verify observed counts/FKs on read-back using S2.

- [ ] **Step 5: Verify consistent restore and measure capture cost; commit.**

Test millions of source rows, forced concurrent page boundaries, disk full, canceled write, read-back corruption, source/default fields and warm legacy metadata. Report peak heap, capture duration and blocked-writer time in benchmark/BASELINE; no invented budgets. Update DATA_FLOW, all public backup/privacy docs and strings. Shared gates/indexing; message: `fix: capture coherent encrypted backup snapshots`.

### Task R2: Own password rotation and verified archive publication (WP-16 / SEC-002)

**Files and responsibilities:**
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/LocalBackupManager.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/LocalBackupServiceImpl.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/BackupStore.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/FileBackupStore.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/SafBackupStore.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/backup/BackupService.kt`
- Modify: `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/LocalBackupViewModel.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/preferences/BackupPreferences.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/backup/BackupOperationState.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/data/backup/BackupOperationJournal.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/data/backup/BackupRotationService.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/data/backup/VerifiedArchivePublisher.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/data/backup/LocalBackupManagerReencryptTest.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/data/backup/FileBackupStoreTest.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/data/backup/SafBackupStoreTest.kt`
- Create: `feature/settings/src/test/kotlin/app/readylytics/health/feature/settings/BackupRotationStateTest.kt` — new behavior test

**Interfaces:** `BackupService.rotatePassword(newPassword: String): Result<Unit>` is service-owned; UI no longer commits password or loops over archives. `val operationState: StateFlow<BackupOperationState>` exposes current status. New pure phases:

```kotlin
enum class BackupOperationPhase {
    PREPARING, STAGED, PUBLISHING, VERIFIED, CREDENTIAL_COMMITTED, CLEANUP, COMPLETE,
}
data class ArchiveRotationEntry(
    val originalLocation: String, val stagedLocation: String?,
    val publishedLocation: String?, val verified: Boolean,
)
data class BackupOperationState(
    val operationId: String?, val phase: BackupOperationPhase?,
    val completedArchives: Int, val totalArchives: Int,
)
```

Journal includes protocol version, immutable directory identity, archive list, encrypted old/new credential recovery material and selected generation. Persist it encrypted using existing `SecureFileStore`/`EncryptionManager`, outside replaceable health tables. Never write plaintext passwords, source URIs or archive contents to logs.

- [ ] **Step 1: Add fault-matrix tests before service extraction.**

For two old-password archives, inject failure/kill before staging, after each stage, before/after each published file, after all verification, before/after credential commit and during cleanup. Recreate service from disk at each point; every original must still decrypt with old credentials or have a verified new copy and recoverable credential. Retry completes without creating visible duplicates or deleting last good data.

```kotlin
assertTrue(oldArchiveStillReadable || verifiedNewArchiveReadable)
assertEquals(expectedSelectedGeneration, persistedSelectedGeneration)
```

These assertions follow actual ZIP decryption/read-back in file and fake-SAF fixtures. A test that only checks `Result.failure` is insufficient. On navigation/cancellation, ViewModel stops collecting but cannot orphan service recovery.

- [ ] **Step 2: Run reencrypt/file/SAF/ViewModel tests red.**

```bash
./gradlew :app:testDebugUnitTest --tests '*LocalBackupManagerReencryptTest' --tests '*FileBackupStoreTest' --tests '*SafBackupStoreTest'
./gradlew :feature:settings:testDebugUnitTest --tests '*BackupRotationStateTest'
```

- [ ] **Step 3: Serialize all backup actions and stage every replacement.**

Application-scoped backup-operation Mutex owns scheduled/manual creation, deletion, rotation and restore archive access. Its durable journal resumes before new conflicting operations. Capture directory identity and original archive set once. Copy/read each original with old password, write new encrypted archive under an operation-specific name, fully decrypt/validate inventory with new password, then persist STAGED. Never overwrite original paths during this phase. On an already mixed-password archive, return recovery-required without deleting it or committing a new selected credential; request the needed password through existing user flow.

- [ ] **Step 4: Publish unique files and switch credentials only after all verify.**

Change `BackupStore.publish`'s misleading atomic-replace promise. Add `suspend fun publishNew(source: File, name: String): BackupLocation` returning the actual location and failing on collision; retain old `publish` only for callers that still need compatible behavior until migrated. File store can use atomic rename to a unique path; SAF writes a fresh document, closes it, then reads back and validates. Never assume SAF rename or display-name identity is atomic.

Persist each returned location/verified state before proceeding. Journal permits retrying a copy after process death without replacing the original. Only after every new archive is verified does one DataStore edit commit the new encrypted credential and selected catalogue/generation ID together. Listing consults the selected catalogue so old/new copies are not both actionable. Before this edit, selected old copies remain usable; after it, all selected copies use the new credential.

The private protected journal remains until preference commit is verified. Crash after preference commit but before journal transition is detected by selected-generation ID. Cleanup removes obsolete originals only after the verified new generation is selected, and retains at least one usable archive. A failed cleanup remains retryable; it must not roll credentials backward. Purge protected recovery secrets only after all selected archives verify and cleanup settles. Keep recovery-referenced staging out of orphan cleanup.

- [ ] **Step 5: Replace the ViewModel password branch and validate lifecycle.**

`UpdateBackupPassword` validates through existing SettingsValidators then calls service `rotatePassword`; remove unconditional preference writes after failure. ViewModel exposes service StateFlow/status and resource errors, collected with lifecycle in UI; passwords remain transient inputs, never stored in UI state or logs. This is service status work only; directory listing races from WP-23 remain separate except catalogue identity required for safe rotation.

Run restart/failure matrix on file and SAF, full backup/restore round-trip after rotation and old ZIP compatibility. Update DATA_FLOW, public backup/privacy docs and in-app strings; shared gates/index/sync. Message: `fix: make backup password rotation recoverable`.

### Task R3: Coordinate restore database, preferences and startup recovery (WP-16 / ARCH-001)

**Files and responsibilities:**
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/LocalRestoreManager.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/LocalRestoreServiceImpl.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/RestorePreferencesApplier.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/RestoreRecommendationCoverageChecker.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/DatabaseReadyStartupInitializer.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/workers/HealthResyncWorker.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/data/backup/RestoreOperationJournal.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/data/backup/RestoreMaintenanceCoordinator.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/data/backup/LocalRestoreApplicationTest.kt`
- Create: `app/src/androidTest/kotlin/app/readylytics/health/data/backup/RestoreMaintenanceRecoveryTest.kt` — new behavior test

**Interfaces:** New durable `RestorePhase { VALIDATED, DATABASE_COMMITTED, PREFERENCES_COMMITTED, CACHES_RESET, COMPLETE }`; `RestoreMaintenanceCoordinator.restore(operationId: String, applyDatabase: suspend () -> Unit, applyPreferences: suspend () -> Unit): RestoreResult`. Journal stores verified archive identity, encrypted preference/layout snapshot, restored generation and phase outside the replaceable DB. Public success/restart result variants remain.

- [ ] **Step 1: Add failure-at-every-phase and concurrent scoring tests.**

Start with DB/config A, restore DB/config B. Inject preferences failure after DB commit; attempt daily scoring, historical worker, cleanup, rollup and backup. All must wait/report maintenance pending; none may score B using A. Recreate the app and recover preferences B before work admission. Validation/application rollback before DB commit leaves A available.

```kotlin
assertEquals(RestorePhase.DATABASE_COMMITTED, journal.phase)
assertFalse(workerWasAdmitted)
assertEquals(oldPublishedSummary, visibleSummaryDuringRecovery)
```

Maintain UI's last complete projection/loading state until recovery completes; don't expose mixed-generation data as healthy. Test process death before journal update but after DB commit by checking a DB-side restore generation marker.

- [ ] **Step 2: Run real Room restore-maintenance fixture red.**

- [ ] **Step 3: Implement a durable two-resource recovery state machine.**

Acquire backup-operation owner then P2 maintenance owner; persist VALIDATED and the selected archive/preferences snapshot. Cancel/suspend pending jobs without awaiting a running lock-holder from inside its needed lock. Use S2 transactional replacement, writing restored generation and DATABASE_COMMITTED marker in the same Room commit. Outside Room, apply preferences/layouts and commit PREFERENCES_COMMITTED to the external journal; the health maintenance state stays pending throughout.

```text
VALIDATED -> DB replacement + generation marker (Room transaction)
DATABASE_COMMITTED -> preferences/layouts apply (idempotent)
PREFERENCES_COMMITTED -> tokens/checkpoints/caches reset
CACHES_RESET -> restored-snapshot dirty range append + work scheduling
COMPLETE -> release durable maintenance block
```

Because Room and DataStore are separate atomic resources, startup reconciles DB marker and journal before choosing a phase. Never infer rollback from a stale external phase after DB commit. Retain verified input archive/credential until preferences recovery finishes; transient failure returns existing partial-restart result plus durable recovery state. Don't schedule recommendation backfill in the preference-failure catch before the correct settings are restored.

- [ ] **Step 4: Reset source job state and schedule one coherent repair.**

After restored preferences are active, clear/suspend old HC tokens/checkpoints, discard run-scoped caches, and append retained-history dirty work using restored scoring zone/config. Keep restored source/summary data; don't rely on frozen metadata from a different snapshot. Queue recompute-only through existing worker/progress path, then safely bootstrap future HC sync. Scheduler startup drains pending work even if process death happened before enqueue; it does not compute while maintenance is pending.

- [ ] **Step 5: Verify recovery/restore compatibility and commit.**

Exercise file/SAF archive reads, legacy missing preferences/layout defaults, preference failure, repeated recovery and each concurrent writer. Confirm mutation lock order and no deadlock, no premature worker admission, and eventual DB/config B parity. Update DATA_FLOW operation diagram, backup/privacy docs and resource recovery status; shared gates/index/sync. Message: `fix: recover restore across database and settings`.
