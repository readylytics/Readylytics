# Production Readiness Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every confirmed production-readiness finding in `internal-docs/PRODUCTION_READINESS_REVIEW.md` and produce a signed, deterministic, privacy-safe Android release candidate.

**Architecture:** Preserve Room as the single source of truth and Health Connect as ingestion-only. Fix correctness and privacy blockers before structural refactors. New sync behavior uses narrow domain ports, stable HC IDs, transactional persistence, per-type change tokens, and resumable WorkManager phases. UI changes retain UDF/MVVM, add adaptive M3 navigation and chart accessibility, and keep scoring formulas unchanged.

**Tech Stack:** Kotlin 2.3, Android/AGP 9.2, Compose Material 3, Hilt, Room 2.8, SQLCipher, Health Connect 1.1, WorkManager 2.11, DataStore/Proto, Vico 3, JUnit4, Robolectric, AndroidX Test, Macrobenchmark, JaCoCo, GitHub Actions.

## Program Rules

- Execute tasks in order. Tasks 1-5 close release blockers; Tasks 6-10 close high-risk behavior; Tasks 11-15 close architecture, UI, and quality debt.
- Use red-green-refactor for every code behavior. Run each listed failing test before production edits and confirm failure reason.
- Do not change scoring coefficients, thresholds, or formulas. Task 2 only corrects date bucketing.
- Never use `deleteAll()` for sync reconciliation. Deletes must be stable-ID, data-type, device-source, and retention/range scoped.
- Advance change tokens and resync checkpoints only after corresponding Room transaction succeeds.
- Preserve `HealthSyncUseCase.syncMutex`, cancellation propagation, full-range `SessionLinkReconciler`, 30-day fetch chunks, pagination, bounded retry, and WorkManager progress bridge.
- Update `internal-docs/DATA_FLOW.md` in same commit as any ingestion, worker, database, or scoring-use-case change.
- Run `codegraph index` after adding files. Run `codegraph sync` after structural moves/module creation.
- Never commit signing material. GitHub Actions secrets and Play Console state remain external prerequisites.

## Target File Structure

New focused units:

```text
app/src/main/kotlin/app/readylytics/health/domain/model/TimestampedTrimp.kt
app/src/main/kotlin/app/readylytics/health/domain/scoring/TrimpDateBucketer.kt
app/src/main/kotlin/app/readylytics/health/domain/sync/HealthChangeSynchronizer.kt
app/src/main/kotlin/app/readylytics/health/domain/sync/ResyncCheckpointStore.kt
app/src/main/kotlin/app/readylytics/health/domain/sync/SelectedSourcePruner.kt
app/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthChangeSynchronizerImpl.kt
app/src/main/kotlin/app/readylytics/health/data/local/RetentionCleanup.kt
app/src/main/kotlin/app/readylytics/health/data/preferences/HealthChangeTokenStoreImpl.kt
app/src/main/kotlin/app/readylytics/health/data/preferences/ResyncCheckpointStoreImpl.kt
app/src/main/kotlin/app/readylytics/health/ui/components/DayTimelineScale.kt
app/src/main/kotlin/app/readylytics/health/ui/onboarding/PrivacyRationaleViewModel.kt
app/src/main/proto/health_change_tokens.proto
app/src/main/proto/resync_checkpoint.proto
benchmark/build.gradle.kts
benchmark/src/main/AndroidManifest.xml
benchmark/src/main/kotlin/app/readylytics/health/benchmark/StartupBenchmark.kt
.github/workflows/release.yml
```

Do not create a new Room schema version unless implementation adds persisted Room columns/tables. Planned persistence uses DataStore/Proto and query projections, so Room version remains 1.

---

### Task 1: Moved to connected-test story

Task 1 moved to [2026-06-19-connected-test-and-benchmark-gates.md](docs/superpowers/plans/2026-06-19-connected-test-and-benchmark-gates.md).

Main remediation plan stays on non-`androidTest` correctness, privacy, architecture, and release-gate work.

---

### Task 2: Make ATL/CTL Date Bucketing DST-Safe

**Files:**
- Create: `app/src/main/kotlin/app/readylytics/health/domain/model/TimestampedTrimp.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/domain/scoring/TrimpDateBucketer.kt`
- Create: `app/src/test/kotlin/app/readylytics/health/domain/scoring/TrimpDateBucketerTest.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/local/dao/WorkoutDao.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/local/dao/DailySummaryDao.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/repository/ScoringRepositoryImpl.kt`
- Modify: `app/src/test/kotlin/app/readylytics/health/data/local/dao/RollingWindowTest.kt`
- Modify: `app/src/test/kotlin/app/readylytics/health/domain/scoring/ScoringPointInTimeRegressionTest.kt`
- Modify: `internal-docs/DATA_FLOW.md`

- [x] **Step 1: Write failing DST bucketing tests**

Define pure model and expected API in test first:

```kotlin
data class TimestampedTrimp(val timestampMs: Long, val trimp: Float)

object TrimpDateBucketer {
    fun bucket(points: List<TimestampedTrimp>, zoneId: ZoneId): Map<LocalDate, Float>
}
```

Test `Europe/Berlin` fall-back and `America/New_York` spring-forward. Include two points on same local date and one local midnight from opposite offset period. Expected keys must equal `Instant.ofEpochMilli(timestampMs).atZone(zoneId).toLocalDate()` and values must sum.

- [x] **Step 2: Verify RED**

Run:

```powershell
.\gradlew testDebugUnitTest --tests "app.readylytics.health.domain.scoring.TrimpDateBucketerTest"
```

Expected: FAIL because bucketer/model do not exist.

- [x] **Step 3: Implement pure bucketer**

```kotlin
object TrimpDateBucketer {
    fun bucket(
        points: List<TimestampedTrimp>,
        zoneId: ZoneId,
    ): Map<LocalDate, Float> =
        points
            .groupBy { Instant.ofEpochMilli(it.timestampMs).atZone(zoneId).toLocalDate() }
            .mapValues { (_, values) -> values.sumOf { it.trimp.toDouble() }.toFloat() }
            .toSortedMap()
}
```

- [x] **Step 4: Replace fixed-offset DAO grouping with timestamp projections**

Replace both `get*TrimpByEpochDay(..., tzOffsetMs)` APIs with:

```kotlin
@Query(
    "SELECT startTime AS timestampMs, trimp AS trimp " +
        "FROM workout_records WHERE startTime >= :fromMs AND startTime < :toMs " +
        "AND trimp IS NOT NULL ORDER BY startTime ASC, id ASC",
)
suspend fun getTrimpPoints(fromMs: Long, toMs: Long): List<TimestampedTrimp>

@Query(
    "SELECT dateMidnightMs AS timestampMs, trimpEverydayHr AS trimp " +
        "FROM daily_summaries WHERE dateMidnightMs >= :fromMs AND dateMidnightMs < :toMs " +
        "AND trimpEverydayHr IS NOT NULL ORDER BY dateMidnightMs ASC",
)
suspend fun getEverydayTrimpPoints(fromMs: Long, toMs: Long): List<TimestampedTrimp>
```

Update `ScoringRepositoryImpl` to call `TrimpDateBucketer.bucket(points, zoneId)` for both series. Remove `tzOffsetMs` calculation.

- [x] **Step 5: Update DAO and scoring regressions**

Update mocks/query tests to return timestamp points. Add repository regression asserting identical ATL/CTL/readiness before and after repeated compute across fall-back transition.

- [x] **Step 6: Verify GREEN and determinism**

Run:

```powershell
.\gradlew testDebugUnitTest --tests "*TrimpDateBucketerTest" --tests "*ScoringPointInTimeRegressionTest" --tests "*ScoringSyncScopeOutputsDeterminismTest"
```

Expected: PASS.

- [x] **Step 7: Update data-flow documentation**

Document per-record zone conversion for ATL/CTL series in `internal-docs/DATA_FLOW.md`; do not duplicate formulas.

- [x] **Step 8: Commit**

```powershell
git add app/src/main app/src/test internal-docs/DATA_FLOW.md
git commit -m "fix: bucket load history by per-record local date"
```

---

### Task 3: Enforce Retention Across Every Sensitive Table

**Files:**
- Create: `app/src/main/kotlin/app/readylytics/health/data/local/RetentionCleanup.kt`
- Create: `app/src/test/kotlin/app/readylytics/health/data/local/RetentionCleanupTest.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/workers/DataCleanupWorker.kt`
- Modify: `app/src/test/kotlin/app/readylytics/health/data/local/dao/DeleteByTimestampTest.kt`
- Modify: `app/src/test/kotlin/app/readylytics/health/ProductionReadinessStaticTest.kt`
- Modify: `internal-docs/DATA_FLOW.md`
- Modify: `docs/privacy.md`
- Modify: `docs/backup-and-data.md`

- [x] **Step 1: Write failing all-table retention integration test**

Seed old, cutoff-equal, and new rows in sleep, stages, HR, HRV, workout, daily summary, weight, body fat, blood pressure, and oxygen saturation. Assert only rows strictly older than cutoff are removed; cutoff-equal rows remain.

- [x] **Step 2: Verify RED**

Run: `.\gradlew testDebugUnitTest --tests "*RetentionCleanupTest"`

Expected: FAIL because four tables retain old rows and cleanup is not transactional.

- [x] **Step 3: Add transactional cleanup unit**

```kotlin
@Singleton
class RetentionCleanup @Inject constructor(
    private val transactionRunner: TransactionRunner,
    private val sleepDao: SleepSessionDao,
    private val heartRateDao: HeartRateDao,
    private val hrvDao: HrvDao,
    private val workoutDao: WorkoutDao,
    private val dailySummaryDao: DailySummaryDao,
    private val weightDao: WeightRecordDao,
    private val bodyFatDao: BodyFatRecordDao,
    private val bloodPressureDao: BloodPressureRecordDao,
    private val oxygenSaturationDao: OxygenSaturationRecordDao,
) {
    suspend fun deleteBefore(cutoffMs: Long) = transactionRunner.runInTransaction {
        sleepDao.deleteBeforeTimestamp(cutoffMs)
        heartRateDao.deleteBeforeTimestamp(cutoffMs)
        hrvDao.deleteBeforeTimestamp(cutoffMs)
        workoutDao.deleteBeforeTimestamp(cutoffMs)
        dailySummaryDao.deleteBeforeTimestamp(cutoffMs)
        weightDao.deleteBeforeTimestamp(cutoffMs)
        bodyFatDao.deleteBeforeTimestamp(cutoffMs)
        bloodPressureDao.deleteBeforeTimestamp(cutoffMs)
        oxygenSaturationDao.deleteBeforeTimestamp(cutoffMs)
    }
}
```

Make worker resolve cutoff through `RetentionBounds`, delegate once, rethrow cancellation, and return failure only for genuine errors.

- [x] **Step 4: Add static coverage guard**

Extend `ProductionReadinessStaticTest` to assert all nine DAO deletions are owned by `RetentionCleanup`, preventing future entity omission.

- [x] **Step 5: Verify GREEN**

Run:

```powershell
.\gradlew testDebugUnitTest --tests "*RetentionCleanupTest" --tests "*DeleteByTimestampTest" --tests "*ProductionReadinessStaticTest"
```

Expected: PASS.

- [x] **Step 6: Update privacy/data-flow docs**

State retention applies to every imported health-record table and backups contain only records present at backup time.

- [x] **Step 7: Commit**

```powershell
git add app/src/main app/src/test internal-docs/DATA_FLOW.md docs/privacy.md docs/backup-and-data.md
git commit -m "fix: apply retention to all health data"
```

---

### Task 4: Activate Backup and Device-Transfer Exclusions

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Keep/verify: `app/src/main/res/xml/data_extraction_rules.xml`
- Keep/verify: `app/src/main/res/xml/full_backup_content.xml`
- Delete: `app/src/main/res/xml/backup_rules.xml`
- Modify: `app/src/test/kotlin/app/readylytics/health/ProductionReadinessStaticTest.kt`
- Modify: `docs/privacy.md`
- Modify: `docs/backup-and-data.md`

- [x] **Step 1: Write failing manifest static test**

Assert application contains:

```xml
android:allowBackup="false"
android:dataExtractionRules="@xml/data_extraction_rules"
android:fullBackupContent="@xml/full_backup_content"
```

Assert `data_extraction_rules.xml` excludes `root`, `file`, `database`, `sharedpref`, `external`, `device_root`, `device_file`, `device_database`, and `device_sharedpref` for both cloud backup and device transfer. Assert `full_backup_content.xml` excludes `root`, `file`, `database`, `sharedpref`, and `external`. Assert unused `backup_rules.xml` is absent.

- [x] **Step 2: Verify RED**

Run: `.\gradlew testDebugUnitTest --tests "*ProductionReadinessStaticTest"`

Expected: FAIL on missing manifest attributes.

- [x] **Step 3: Wire rules and remove sample**

Add both manifest attributes, retain `allowBackup=false`, and delete unused sample file. Expand exclusion XML only where Android schema permits; validate with lint.

- [x] **Step 4: Verify GREEN**

Run: `.\gradlew testDebugUnitTest --tests "*ProductionReadinessStaticTest" lintRelease`

Expected: PASS; no `DataExtractionRules` or unused backup XML warning.

- [x] **Step 5: Perform device backup check**

Use `adb shell bmgr` on test device and inspect restore set. Confirm DB, DataStore, Tink keyset, SQLCipher key preference, and local backup ZIP are absent. Record OEM device-to-device transfer as manual release checklist item.

- [x] **Step 6: Commit**

```powershell
git add app/src/main app/src/test docs/privacy.md docs/backup-and-data.md
git commit -m "fix: exclude health data from backup and transfer"
```

---

### Task 5: Produce and Verify Signed Release Artifacts

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `.github/workflows/release.yml`
- Modify: `.gitignore`
- Modify: `README.md`
- Create: `internal-docs/RELEASE_SIGNING.md`

- [x] **Step 1: Add fail-closed signing input validation**

Read these environment variables only for release signing:

```text
READYLYTICS_UPLOAD_STORE_FILE
READYLYTICS_UPLOAD_STORE_PASSWORD
READYLYTICS_UPLOAD_KEY_ALIAS
READYLYTICS_UPLOAD_KEY_PASSWORD
READYLYTICS_UPLOAD_CERT_SHA256
```

Register `verifyReleaseSigningInputs`; it must fail when any value is missing for release workflow. Debug/local verification remains unaffected.

- [x] **Step 2: Configure release signing without secrets in source**

```kotlin
signingConfigs {
    create("releaseUpload") {
        storeFile = providers.environmentVariable("READYLYTICS_UPLOAD_STORE_FILE").map(::file).orNull
        storePassword = providers.environmentVariable("READYLYTICS_UPLOAD_STORE_PASSWORD").orNull
        keyAlias = providers.environmentVariable("READYLYTICS_UPLOAD_KEY_ALIAS").orNull
        keyPassword = providers.environmentVariable("READYLYTICS_UPLOAD_KEY_PASSWORD").orNull
    }
}
```

Assign to release only when validation passes. Never fall back to debug key.

- [x] **Step 3: Add protected release workflow**

Trigger manually and on version tags. Decode base64 keystore into runner temp directory, set environment variables, run unit/lint/release bundle, verify JAR signature, compare SHA-256 certificate fingerprint, upload signed AAB artifact, then delete temp key in `always()` step.

- [x] **Step 4: Document external setup**

Document GitHub environment protection, secret names, fingerprint rotation, Play App Signing enrollment, and internal-track dry run. Add keystore extensions to `.gitignore`.

- [x] **Step 5: Verify locally with disposable key only**

Run release workflow commands against temporary test keystore outside repository. Expected: `jarsigner -verify` reports signed; fingerprint matches supplied value. Remove disposable key afterward.

- [ ] **Step 6: Commit**

```powershell
git add app/build.gradle.kts .github/workflows/release.yml .gitignore README.md internal-docs/RELEASE_SIGNING.md
git commit -m "build: add fail-closed release signing pipeline"
```

---

### Task 6: Synchronize Health Connect Upsertions and Deletions

**Files:**
- Create: `app/src/main/kotlin/app/readylytics/health/domain/sync/HealthChangeSynchronizer.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthChangeSynchronizerImpl.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/domain/sync/HealthChangeTokenStore.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/data/preferences/HealthChangeTokenStoreImpl.kt`
- Create: `app/src/test/kotlin/app/readylytics/health/data/healthconnect/HealthChangeSynchronizerImplTest.kt`
- Create: `app/src/main/proto/health_change_tokens.proto`
- Modify: `app/src/main/kotlin/app/readylytics/health/domain/sync/HealthSyncUseCase.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/local/dao/*.kt`
- Modify: Hilt binding module under `app/src/main/kotlin/app/readylytics/health/di/`
- Modify: `internal-docs/DATA_FLOW.md`

- [x] **Step 1: Define domain port and outcome**

```kotlin
interface HealthChangeSynchronizer {
    suspend fun applyPendingChanges(): HealthChangeSyncOutcome
    suspend fun refreshTokensAfterFullResync()
}

data class HealthChangeSyncOutcome(
    val affectedDates: Set<LocalDate>,
    val requiresFullResync: Boolean,
)

interface HealthChangeTokenStore {
    suspend fun get(dataType: HealthDataType): String?
    suspend fun put(dataType: HealthDataType, token: String, syncedAtMs: Long)
    suspend fun clear(dataType: HealthDataType)
    suspend fun clearAll()
}
```

Keep Android HC record classes inside data implementation.

- [x] **Step 2: Write failing tests**

Cover one per-type token, paginated `getChanges`, `UpsertionChange`, `DeletionChange`, revoked permission isolation, token expiry, transaction failure, cancellation, and token persistence. Verify token advances only after DAO transaction succeeds.

- [x] **Step 3: Verify RED**

Run: `.\gradlew testDebugUnitTest --tests "*HealthChangeSynchronizerImplTest"`

Expected: FAIL because synchronizer/token fields do not exist.

- [x] **Step 4: Persist separate token per data type**

Create dedicated `health_change_tokens.proto` with a token map keyed by `HealthDataType.name` and a last-success timestamp map. Bind it through `HealthChangeTokenStore`; do not place operational tokens in user preferences or local backup export. Keep token write atomic with successful page application from caller perspective: apply page in Room transaction, then persist returned next token. If token write fails, page safely replays through stable-ID upsert/delete.

- [x] **Step 5: Implement official change loop**

Use `getChangesToken` and `getChanges` per record type. Follow <https://developer.android.com/health-and-fitness/guides/health-connect/develop/sync-data>. Map upsertions through existing mappers and selected-device filter. Apply deletions by stable HC record ID. For a non-selected upsertion, delete same local ID if present instead of retaining stale prior-source value.

- [x] **Step 6: Integrate with daily/full sync**

Daily sync applies pending changes before current-day ingest, unions affected dates with today, and recomputes through `ScoringRepository.computeDailySummary`. Successful full resync refreshes all per-type tokens. Expired token returns `requiresFullResync=true`; foreground controller enqueues existing unique resync worker, never runs historical resync inline.

- [x] **Step 7: Verify GREEN and idempotency**

Run:

```powershell
.\gradlew testDebugUnitTest --tests "*HealthChangeSynchronizerImplTest" --tests "*HealthSyncUseCaseTest" --tests "*SyncScopeDeterminismTest"
```

Expected: PASS; replayed page produces identical DB and summaries.

- [x] **Step 8: Update data-flow docs and commit**

```powershell
git add app/src/main app/src/test internal-docs/DATA_FLOW.md
git commit -m "feat: reconcile Health Connect changes and deletions"
```

---

### Task 7: Remove Stale Data After Device-Source Changes

**Files:**
- Create: `app/src/main/kotlin/app/readylytics/health/domain/sync/SelectedSourcePruner.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/data/local/SelectedSourcePrunerImpl.kt`
- Create: `app/src/test/kotlin/app/readylytics/health/data/local/SelectedSourcePrunerImplTest.kt`
- Modify: relevant DAOs for device-scoped deletes
- Modify: `app/src/main/kotlin/app/readylytics/health/domain/sync/HealthSyncUseCase.kt`
- Modify: `app/src/test/kotlin/app/readylytics/health/domain/sync/HealthSyncUseCaseTest.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/ui/settings/data/DataSourceSettingsViewModel.kt`
- Modify: `internal-docs/DATA_FLOW.md`

- [x] **Step 1: Write failing A-to-B device integration test**

Seed device A across every data type, set selection B, ingest B, and assert A remains until full historical ingest/reconcile succeeds. After final prune, assert only selected B contributes to summaries. For `null` selection, retain all devices.

- [x] **Step 2: Define finalization port**

```kotlin
interface SelectedSourcePruner {
    suspend fun prune(
        start: LocalDate,
        endInclusive: LocalDate,
        selections: Map<HealthDataType, String?>,
    )
}
```

- [x] **Step 3: Implement scoped transactional deletes**

Add DAO deletes bounded by retention range and selected device for each type. Do not delete before full ingest succeeds. Run prune once after all chunks and before full-range session-link reconciliation/recompute. `null`/blank selection performs no prune.

- [x] **Step 4: Keep settings on durable worker path**

Retain `DataSourceSettingsViewModel` behavior that schedules `WorkerScheduler.RESYNC_WORK_NAME`; add test proving apply persists selections then enqueues unique full resync. Never calculate historical data in ViewModel.

- [x] **Step 5: Verify**

Run:

```powershell
.\gradlew testDebugUnitTest --tests "*SelectedSourcePrunerImplTest" --tests "*HealthSyncUseCaseTest" --tests "*DataSourceSettingsViewModelTest"
```

Expected: PASS; killed ingest before finalization leaves prior valid rows intact.

- [x] **Step 6: Update docs and commit**

```powershell
git add app/src/main app/src/test internal-docs/DATA_FLOW.md
git commit -m "fix: finalize device-source changes after resync"
```

---

### Task 8: Add Durable Historical Resync Checkpoints

**Files:**
- Create: `app/src/main/proto/resync_checkpoint.proto`
- Create: `app/src/main/kotlin/app/readylytics/health/domain/sync/ResyncCheckpointStore.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/data/preferences/ResyncCheckpointStoreImpl.kt`
- Create: `app/src/test/kotlin/app/readylytics/health/domain/sync/ResyncCheckpointResumeTest.kt`
- Create: `app/src/test/kotlin/app/readylytics/health/workers/HealthResyncWorkerTest.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/domain/sync/HealthSyncUseCase.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/domain/sync/FullHistoricalResyncUseCase.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/workers/HealthResyncWorker.kt`
- Modify: DataStore Hilt module
- Modify: `internal-docs/DATA_FLOW.md`

- [x] **Step 1: Define checkpoint schema**

```proto
enum ResyncPhase {
  RESYNC_PHASE_UNSPECIFIED = 0;
  INGEST = 1;
  PRUNE = 2;
  RECONCILE = 3;
  RECOMPUTE = 4;
}

message ResyncCheckpointProto {
  int64 start_epoch_day = 1;
  int64 end_epoch_day = 2;
  ResyncPhase phase = 3;
  int64 next_epoch_day = 4;
  string selection_hash = 5;
}
```

Domain representation is fixed as:

```kotlin
enum class ResyncPhase { INGEST, PRUNE, RECONCILE, RECOMPUTE }

data class ResyncCheckpoint(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val phase: ResyncPhase,
    val nextDate: LocalDate,
    val selectionHash: String,
)
```

- [x] **Step 2: Write failing resume tests**

Inject failure after chunk N, during prune, during reconcile, and after recompute day N. Assert retry resumes correct phase, does not skip work, does not duplicate rows, and produces same final summaries as uninterrupted run. Selection/range hash mismatch must discard checkpoint and restart.

- [x] **Step 3: Verify RED**

Run: `.\gradlew testDebugUnitTest --tests "*ResyncCheckpointResumeTest"`

Expected: FAIL because every retry begins at range start.

- [x] **Step 4: Implement checkpoint contract**

```kotlin
interface ResyncCheckpointStore {
    val checkpoint: Flow<ResyncCheckpoint?>
    suspend fun save(checkpoint: ResyncCheckpoint)
    suspend fun clear()
}
```

Advance ingest checkpoint after each successful 30-day transaction. `PRUNE` and `RECONCILE` are idempotent whole-phase operations. Advance recompute checkpoint after each successfully persisted day. Keep `ensureActive()` and `yield()`.

- [x] **Step 5: Preserve progress and failure semantics**

Worker progress derives from original total days and checkpoint position. Transient failures still return `Result.retry()`. Cancellation rethrows. Clear checkpoint only after recompute and token refresh complete.

- [x] **Step 6: Verify GREEN and timeout behavior**

Run:

```powershell
.\gradlew testDebugUnitTest --tests "*ResyncCheckpointResumeTest" --tests "*HealthSyncUseCaseTest" --tests "*HealthResyncWorkerTest"
```

Expected: PASS; interrupted and uninterrupted database snapshots are equal.

- [x] **Step 7: Update docs and commit**

```powershell
git add app/src/main app/src/test internal-docs/DATA_FLOW.md
git commit -m "feat: resume historical resync from durable checkpoints"
```

---

### Task 9: Remove Runtime Network Capability and Sanitize Errors

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: all direct `Log.*` production call sites listed in review
- Modify: `app/src/main/kotlin/app/readylytics/health/util/SecureLogger.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/domain/util/AppLog.kt`
- Modify: affected ViewModels returning `e.message`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/test/kotlin/app/readylytics/health/ProductionReadinessStaticTest.kt`
- Modify: `docs/privacy.md`

- [x] **Step 1: Write failing static privacy tests**

Assert merged/source manifest lacks `INTERNET`; Gradle lacks Retrofit/OkHttp dependencies; production Kotlin has no direct `android.util.Log`/`Log.*`; no ViewModel maps `Throwable.message` to `UiText.RawString`.

- [x] **Step 2: Verify RED**

Run: `.\gradlew testDebugUnitTest --tests "*ProductionReadinessStaticTest"`

Expected: FAIL on current permission, dependencies, logs, and raw exception strings.

- [x] **Step 3: Remove unused network surface**

Delete manifest permission, three app dependencies, and now-unused catalog aliases/versions. Keep external privacy-policy `ACTION_VIEW`; it does not require app network permission.

- [x] **Step 4: Centralize debug-only sanitized logging**

Use existing `DomainLogger`/`logD`/`logE` path everywhere. Android sink remains guarded by `BuildConfig.DEBUG`. Production call sites pass stable messages and never interpolate health values, paths, decrypted content, or raw preference values.

- [x] **Step 5: Replace raw error UI**

Map known errors to localized `UiText.StringRes`; unknown errors use generic `R.string.error_generic`. Keep raw strings only for formatted non-sensitive values.

- [x] **Step 6: Verify GREEN**

Run:

```powershell
.\gradlew testDebugUnitTest --tests "*ProductionReadinessStaticTest" lintRelease assembleRelease
```

Expected: PASS; merged manifest has no `INTERNET`; dependency report has no Retrofit/OkHttp.

- [x] **Step 7: Update privacy docs and commit**

```powershell
git add app/src/main app/src/test app/build.gradle.kts gradle/libs.versions.toml docs/privacy.md
git commit -m "security: remove network capability and sanitize errors"
```

---

### Task 10: Correct Chart Time, Selection, and Scroll Behavior

**Files:**
- Create: `app/src/main/kotlin/app/readylytics/health/ui/components/DayTimelineScale.kt`
- Create: `app/src/test/kotlin/app/readylytics/health/ui/components/DayTimelineScaleTest.kt`
- Create: `app/src/test/kotlin/app/readylytics/health/ui/components/HrTimelineChartStateTest.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/ui/components/HrTimelineChart.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/ui/components/SleepStagesChart.kt`
- Modify: `app/src/test/kotlin/app/readylytics/health/ui/components/SleepStagesChartTest.kt`
- Modify: `DashboardScreen.kt` and `HeartRateDetailScreen.kt`

- [x] **Step 1: Write failing DST timeline tests**
Define:

```kotlin
data class DayTimelineScale(
    val startMs: Long,
    val endExclusiveMs: Long,
) {
    val durationMs: Long get() = endExclusiveMs - startMs
    fun fraction(timestampMs: Long): Float
}
```

Test 23-, 24-, and 25-hour days from `LocalDate.atStartOfDay(zone)` to next local day. No clamping to 1439 minutes.

- [x] **Step 2: Verify RED and implement scale**

Run: `.\gradlew testDebugUnitTest --tests "*DayTimelineScaleTest"`

Expected: FAIL, then implement fraction from actual duration and rerun to PASS.

- [x] **Step 3: Replace fixed 1440-minute HR mapping**

Pass day end or `LocalDate` plus zone into chart. Convert taps/samples using actual epoch duration. Add deterministic axis labels for repeated/missing DST hour.

- [x] **Step 4: Reset stale chart state**

Use `LaunchedEffect(dayStartMs, samples)` to clear selected sample when date changes or selected ID disappears. Use `LaunchedEffect(stages)` to clear selected sleep segment when session changes. Reset zoom/pan on date change.

- [x] **Step 5: Move scroll-state reads out of composition**

Remove `scrollState.value` from `remember` keys. Position tooltip with layout-phase offset:

```kotlin
Modifier.offset {
    IntOffset(selectedCenterPx - scrollState.value, 0)
}
```

Compute selected value text only from selected stage. Clip/hide through layout bounds, not compositional scroll reads.

- [x] **Step 6: Replace frozen remembered current date**

Use existing date-transition/timezone flow or injected clock-backed UI state; do not `remember { LocalDate.now() }` in screens.

- [x] **Step 7: Verify**

Run:

```powershell
.\gradlew testDebugUnitTest --tests "*DayTimelineScaleTest" --tests "*SleepStagesChartTest" --tests "*HrTimelineChartStateTest"
.\gradlew lintRelease
```

Expected: PASS; no `FrequentlyChangingValue` warning.

- [x] **Step 8: Commit**

```powershell
git add app/src/main app/src/test
git commit -m "fix: make chart time and selection state resilient"
```

---

### Task 11: Add Chart Accessibility and Localize Remaining Labels

**Files:**
- Modify: `HrTimelineChart.kt`, `SleepStagesChart.kt`, `CanvasChartTooltip.kt`, `TrimpBreakdownChart.kt`, `RasWeeklyBar.kt`, `SleepArchitectureBar.kt`, `StepsBar.kt`
- Modify: `VitalsScreen.kt`
- Modify: `DeviceLabel.kt` and its callers
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/androidTest/kotlin/app/readylytics/health/ui/components/ChartAccessibilityTest.kt`

- [x] **Step 1: Write failing semantics tests**

For HR, sleep stages, and TRIMP charts assert a chart summary description, collection/value semantics, next/previous accessible actions, selected-value state description, and dismiss action.

- [x] **Step 2: Verify RED**

Run focused connected semantics test. Expected: nodes/actions not found.

- [x] **Step 3: Add accessible chart contract**

Every interactive chart exposes:

```kotlin
Modifier.semantics {
    contentDescription = chartSummary
    stateDescription = selectedValueDescription
    customActions = listOf(previousAction, nextAction, clearSelectionAction)
}
```

Provide a visible or screen-reader-accessible textual value list for charts where point traversal would be excessive. Announce selection changes through state description, not transient toast.

- [x] **Step 4: Localize labels**

Move `RHR`, `HRV`, `Steps`, `This Phone`, and provider display labels to resources. Keep package names and enum identifiers nonlocalized internally. Add plurals for candidate counts/durations flagged by lint.

- [x] **Step 5: Verify GREEN and manual accessibility**

Run connected tests. Manually verify TalkBack, Switch Access, keyboard/D-pad, 200% font, dark mode, and high contrast on compact and expanded devices.

- [x] **Step 6: Commit**

```powershell
git add app/src/main app/src/androidTest
git commit -m "feat: make health charts accessible and localizable"
```

---

### Task 12: Add Android 17 Adaptive Navigation and Rationale ViewModel

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/kotlin/app/readylytics/health/ui/scaffold/MainScaffold.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/ui/onboarding/PrivacyRationaleViewModel.kt`
- Modify: `PrivacyRationaleActivity.kt`
- Modify: `app/src/androidTest/kotlin/app/readylytics/health/ui/scaffold/MainScaffoldTest.kt`
- Create: `app/src/test/kotlin/app/readylytics/health/ui/onboarding/PrivacyRationaleViewModelTest.kt`

- [x] **Step 1: Write failing adaptive navigation tests**

Assert compact width renders bottom navigation, medium renders navigation rail, and expanded renders rail/drawer per chosen M3 suite defaults. Assert all destinations and selected state remain identical.

- [x] **Step 2: Add adaptive navigation dependency and implementation**

Use Material3 adaptive navigation suite and `currentWindowAdaptiveInfo()` as documented at <https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation>. Replace custom bottom-only scaffold with `NavigationSuiteScaffold`; keep content/insets edge-to-edge safe.

- [x] **Step 3: Write failing rationale ViewModel test**

Assert ViewModel exposes theme state from settings and Activity/Composable only consumes UI state/callbacks.

- [x] **Step 4: Move repository access out of Activity composition**

```kotlin
@HiltViewModel
class PrivacyRationaleViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val appTheme = settingsRepository.userPreferences
        .map(UserPreferences::appTheme)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppTheme.SYSTEM)
}
```

Activity collects with lifecycle and passes `appTheme` to stateless content.

- [x] **Step 5: Verify**

Run unit/connected scaffold tests and manual API 37 tablet/foldable portrait/landscape smoke tests.

- [x] **Step 6: Commit**

```powershell
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main app/src/test app/src/androidTest
git commit -m "feat: add adaptive navigation and rationale state"
```

---

### Task 13: Enforce Domain/Data Boundaries Without Formula Changes

**Files:**
- Modify: `app/src/test/kotlin/app/readylytics/health/CleanArchTest.kt`
- Create domain summary/preferences/ingestion port models under `domain/model`, `domain/preferences`, and `domain/sync`
- Modify: `ScoringRepository.kt`, `HealthSyncUseCase.kt`, `SessionLinkReconciler.kt`, scoring use cases importing `data.*`
- Create data adapters under `data/repository` and `data/local`
- Modify Hilt binding modules
- Modify: `internal-docs/DATA_FLOW.md`

- [x] **Step 1: Add failing architecture rule**

```kotlin
@Test
fun `domain package does not import data package`() {
    // Konsist assertion: app.readylytics.health.domain.. imports must not start with app.readylytics.health.data.
}
```

Run `CleanArchTest`; expected FAIL with complete import list.

- [x] **Step 2: Remove Room entity from scoring contract**

Introduce domain `DailySummary` and map at data boundary. `ScoringRepository` returns domain model; `DailySummaryRepositoryImpl` owns entity mapping. Preserve all values and rounding exactly.

- [x] **Step 3: Hide DAO/mappers behind ingestion port**

Define narrow domain contract:

```kotlin
interface HealthIngestionStore {
    suspend fun persist(batch: HealthIngestionBatch)
    suspend fun clearFrozenBaselines(start: LocalDate, endExclusive: LocalDate)
}

data class HealthIngestionBatch(
    val sleepSessions: List<SleepSessionInput>,
    val sleepStages: List<SleepStageInput>,
    val heartRateSamples: List<HeartRateInput>,
    val hrvSamples: List<HrvInput>,
    val workouts: List<WorkoutInput>,
    val weights: List<WeightInput>,
    val bodyFatSamples: List<BodyFatInput>,
    val bloodPressureSamples: List<BloodPressureInput>,
    val oxygenSaturationSamples: List<OxygenSaturationInput>,
)

data class SleepSessionInput(
    val id: String, val startTime: Long, val endTime: Long,
    val durationMinutes: Int, val efficiency: Float,
    val deepSleepMinutes: Int, val remSleepMinutes: Int,
    val lightSleepMinutes: Int, val awakeMinutes: Int,
    val sleepScore: Float?, val startZoneOffsetSeconds: Int?,
    val endZoneOffsetSeconds: Int?, val deviceName: String?,
)

data class SleepStageInput(
    val sessionId: String, val stageType: String,
    val startTime: Long, val endTime: Long, val durationMinutes: Int,
)

data class HeartRateInput(
    val id: String, val timestampMs: Long, val beatsPerMinute: Int,
    val recordType: String, val sessionId: String?, val deviceName: String?,
)

data class HrvInput(
    val id: String, val timestampMs: Long, val rmssdMs: Float,
    val recordType: String, val sessionId: String?, val deviceName: String?,
)

data class WorkoutInput(
    val id: String, val startTime: Long, val endTime: Long,
    val exerciseType: String, val durationMinutes: Int,
    val zone1Minutes: Float, val zone2Minutes: Float, val zone3Minutes: Float,
    val zone4Minutes: Float, val zone5Minutes: Float,
    val trimp: Float, val avgHr: Float, val deviceName: String?,
)

data class WeightInput(
    val id: String, val timestampMs: Long, val weightKg: Float, val deviceName: String?,
)

data class BodyFatInput(
    val id: String, val timestampMs: Long, val bodyFatPercent: Float, val deviceName: String?,
)

data class BloodPressureInput(
    val id: String, val timestampMs: Long,
    val systolicMmHg: Int, val diastolicMmHg: Int, val deviceName: String?,
)

data class OxygenSaturationInput(
    val id: String, val timestampMs: Long, val percentage: Float, val deviceName: String?,
)
```

Create these contracts in `domain/sync/HealthIngestionStore.kt`. The data implementation maps them to the current entity fields without changing precision, nullability, or identifiers, and owns DAOs plus the Room transaction. `HealthSyncUseCase` coordinates repository ports only.

- [x] **Step 4: Move preference models/contracts to domain**

Move domain-relevant enums/config snapshots from `data.preferences` to `domain.preferences`; keep Proto conversion in data. Update imports mechanically with no behavior change.

- [x] **Step 5: Split orchestration by responsibility**

Extract from `HealthSyncUseCase` only when tests are green:

```text
HealthWindowIngestor       fetch/filter/persist one window
HistoricalResyncCoordinator  checkpointed phase order
WalkForwardRecalculator   baseline clear + daily scoring/progress
```

Keep shared mutex at outer coordinator and full-range session reconciliation between ingest/prune and recompute.

- [x] **Step 6: Verify architecture and determinism**

Run:

```powershell
.\gradlew testDebugUnitTest --tests "*CleanArchTest" --tests "*Scoring*Determinism*" --tests "*HealthSyncUseCaseTest"
```

Expected: PASS; zero domain imports from data; serialized summary outputs unchanged for fixed fixtures.

- [x] **Step 7: Update docs, sync index, commit**

```powershell
codegraph sync
git add app/src/main app/src/test internal-docs/DATA_FLOW.md
git commit -m "refactor: enforce domain and persistence boundaries"
```

---

### Task 14: Make Coverage, Lint, and R8 Release Gates Meaningful

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `.github/workflows/ci.yml`
- Modify: `app/proguard-rules.pro`
- Modify: lint findings across `app/src/main`
- Modify/add tests for uncovered critical packages

- [x] **Step 1: Correct JaCoCo inputs**

Include Kotlin sources/classes and print included class count. Add verification assertion that report contains `ScoringRepositoryImpl`, `HealthSyncUseCase`, `DataCleanupWorker`, and chart ViewModels. Remove generated-only outputs and keep only justified exclusions.

- [x] **Step 2: Replace 4% custom gate with standard package gates**

Use `JacocoCoverageVerification` rules:

```text
overall instruction coverage >= 25%
domain/scoring line coverage >= 80%
domain/sync line coverage >= 70%
workers line coverage >= 60%
```

Add tests until thresholds pass; never lower thresholds to fit current output. Make CI label match code.

- [x] **Step 3: Resolve release lint backlog**

Remove 42 unused resources, correct modifier parameter ordering, use plurals, fix typography/KTX/locale findings, and re-run after each category. Set `warningsAsErrors=true` only after report reaches zero warnings/hints or establish a reviewed baseline that excludes no privacy, correctness, performance, accessibility, or API compatibility rule.

- [x] **Step 4: Narrow R8 rules incrementally**

Delete blanket keeps for Room, Hilt ViewModels, Health Connect records, Vico, SQLCipher, and whole repositories unless release smoke proves reflection requires them. After each group removal run `assembleRelease`, install minified APK generated from bundle, and exercise startup, Room, Hilt, WorkManager, backup/restore, and charts.

- [x] **Step 5: Verify final gates**

Run:

```powershell
.\gradlew ktlintCheck testDebugUnitTest jacocoCoverageVerification lint lintRelease assembleRelease bundleRelease
```

Expected: all PASS; release lint has no unreviewed warning; R8 mapping generated; app startup smoke passes.

- [x] **Step 6: Commit**

```powershell
git add app/build.gradle.kts app/proguard-rules.pro .github/workflows/ci.yml app/src/main app/src/test
git commit -m "build: enforce production quality gates"
```

---

### Task 15: Split Oversized Files Along Established Responsibilities

**Files:**
- Split: `TrendCharts.kt`, `SettingsScreen.kt`, `WorkoutStatsSection.kt`, `Theme.kt`, `BaselineComputer.kt`, `LocalBackupManager.kt`, `LocalRestoreManager.kt`
- Modify tests only for moved symbols/imports

- [x] **Step 1: Record behavior baseline**

Run relevant unit/UI tests and capture public/internal symbol callers with CodeGraph before each split.

- [x] **Step 2: Split one file per commit**

Use these boundaries:

```text
TrendCharts.kt -> one chart family per file plus shared Vico helpers
SettingsScreen.kt -> route/effects, top-level sections, About/privacy section
WorkoutStatsSection.kt -> summary, zones, load charts
Theme.kt -> scheme construction, dynamic palette, CompositionLocals
BaselineComputer.kt -> HRV baseline, RHR baseline, snapshot assembly
LocalBackupManager.kt -> JSON writer, ZIP/encryption, storage target
LocalRestoreManager.kt -> archive validation, JSON reader, transactional apply
```

Do not change public behavior, formulas, serialization keys, backup format, or resource IDs.

- [x] **Step 3: Keep file limits**

Target <=400 lines per production file; no new file may exceed 800 lines. Add no abstraction unless it removes a real responsibility or duplication.

- [x] **Step 4: Verify after each split**

Run focused tests plus `ktlintCheck`. Run backup round-trip and documentation drift tests after backup/scoring splits.

- [x] **Step 5: Sync index and commit final split batch**

```powershell
codegraph sync
git add app/src/main app/src/test
git commit -m "refactor: split oversized production components"
```

---

### Task 16: Final Release Acceptance and Review Closure

**Files:**
- Modify: `internal-docs/PRODUCTION_READINESS_REVIEW.md`
- Modify: `internal-docs/DATA_FLOW.md`
- Modify: `ABOUT.md`, `docs/about.md`, and `strings.xml` only if implementation changed public scoring explanation
- Create: `internal-docs/RELEASE_ACCEPTANCE.md`

- [ ] **Step 1: Run mandatory formatting and unit gate**

```powershell
.\gradlew ktlintFormat
.\gradlew testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 2: Run complete static/release gate**

```powershell
.\gradlew ktlintCheck jacocoCoverageVerification lint lintRelease assembleRelease bundleRelease
```

Expected: PASS.

- [ ] **Step 3: Run device and benchmark matrix**

Run functional connected tests on minimum API 26, Health Connect-supported API, API 36 physical device, and API 37 emulator/device. Run dedicated Macrobenchmark suite separately. Expected: 0 failures.

- [ ] **Step 4: Verify signed artifact**

Build through protected release workflow. Verify AAB certificate fingerprint and upload to Play internal testing track. Confirm install/upgrade from prior internal build retains encrypted database and preferences.

- [ ] **Step 5: Execute privacy and data QA**

Verify permission denial/revocation, HC deletion, device-source switch, retention, local encrypted backup/restore, cloud backup exclusion, device transfer exclusion, no app network permission, and sanitized release logs.

Confirm Play/store copy does not advertise widgets because no widget implementation exists. If widgets become release scope, stop and create a separate widget design/spec/implementation plan rather than adding them inside this remediation program.

- [ ] **Step 6: Execute UI/accessibility QA**

Verify compact/medium/expanded windows, portrait/landscape/fold, dynamic light/dark, 200% font, TalkBack, Switch Access, keyboard/D-pad, chart selection reset, DST days, empty/loading/error/permission states.

- [ ] **Step 7: Close review findings with evidence**

For each P0/P1/P2 item in `PRODUCTION_READINESS_REVIEW.md`, append status, fixing commit, test command, and artifact/report link. Do not mark external Play/OEM blindspots verified without evidence.

- [ ] **Step 8: Create release acceptance record**

Record commit SHA, version code/name, AAB SHA-256, signing certificate SHA-256, Gradle command results, device matrix, Play internal-track result, remaining accepted P3 debt, and approver.

- [ ] **Step 9: Index/sync and final commit**

```powershell
codegraph index
codegraph sync
git add internal-docs ABOUT.md docs app/src/main/res/values/strings.xml
git commit -m "docs: record production release acceptance"
```

## Release Exit Criteria

- All five P0 findings closed with automated regression evidence.
- HC deletion and selected-device behavior deterministic and idempotent.
- Historical resync resumes after process death/timeout without restarting completed phases.
- Functional connected tests and Macrobenchmark run separately and pass.
- Release AAB is signed, fingerprint-verified, R8-minified, and accepted by Play internal track.
- Retention covers every sensitive table; backup/device transfer excludes all sensitive domains.
- Merged manifest contains no `INTERNET` permission.
- Release logs contain no raw throwable/health values; UI contains no raw exception messages.
- Scoring, sync, worker, architecture, lint, coverage, accessibility, and documentation drift gates pass.
- `internal-docs/PRODUCTION_READINESS_REVIEW.md` contains no open P0/P1 finding.
