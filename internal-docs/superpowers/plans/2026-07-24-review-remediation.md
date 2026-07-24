# Phase 5 Review Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve all six Phase 5 review findings while retaining the v7 schema and making its large-table upgrade recoverable, foregrounded, and compatible with v5/v6 backups.

**Architecture:** Land the three isolated data/scoring corrections first, then add a version-aware streaming backup adapter. Replace Room's monolithic v6→v7 callback with a SQLCipher pre-open state machine that first advances production v5 files through the shared additive v5→v6 SQL, copies large tables into shadow tables in committed batches, and gates every Room consumer until an atomic final swap marks the file v7.

**Tech Stack:** Kotlin, coroutines/Flow, Room 2.7, SQLCipher 4.16, WorkManager, Hilt, Compose Material 3, Robolectric, Android instrumented tests, AndroidX Benchmark.

## Global Constraints

- minSdk=26 and targetSdk=37.
- Room is the single source of truth; Health Connect is ingestion-only.
- Recompute-only work must perform zero Health Connect reads and preserve already-ingested steps.
- Scoring formulas remain unchanged; all recomputation continues through `ScoringRepository.computeDailySummary`.
- Never use destructive migration or `deleteAll()` for the v5/v6→v7 upgrade.
- Migration and walk-forward loops remain cooperative and never swallow `CancellationException`.
- User-facing strings live in Android string resources.
- Cards, banners, and blocking migration surfaces use Material 3 container roles and `MaterialTheme.shapes.large`.
- Any ingestion, schema, migration, backup, or scoring-explanation change synchronously updates `internal-docs/DATA_FLOW.md`.
- Stage-less scoring explanation changes synchronously update `ABOUT.md`, `docs/about.md`, in-app About/tooltip strings, and documentation drift tests.
- New files require `codegraph index`; structural moves require `codegraph sync`.
- Mandatory final verification is `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`, followed by `./gradlew lintRelease`.

## File Map

### Existing files to modify

- `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCase.kt` — omit step fetching/overrides in recompute-only runs.
- `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/StepCountFetcher.kt` — materialize zero totals for missing grouped days.
- `app/src/test/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCaseTest.kt` — offline recompute regression.
- `core/healthconnect/src/test/kotlin/app/readylytics/health/domain/sync/StepCountFetcherRangeTest.kt` — empty/sparse grouped-day regressions.
- `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/BaselineComputer.kt` — fixed-duration prefetch lower bound.
- `app/src/test/kotlin/app/readylytics/health/domain/scoring/BaselineComputerWalkForwardEquivalenceTest.kt` — DST equivalence fixture.
- `app/src/main/kotlin/app/readylytics/health/data/backup/LocalRestoreManager.kt` — version-aware validation and decode.
- `app/src/test/kotlin/app/readylytics/health/data/backup/LocalRestoreManagerTest.kt` — v5/v6/v7 restore fixtures.
- `app/src/main/kotlin/app/readylytics/health/data/security/SqlCipherKeyManager.kt` — scoped raw encrypted-database access.
- `core/database/src/main/kotlin/app/readylytics/health/data/local/DatabaseMigrations.kt` — remove the monolithic v6→v7 copy.
- `core/database/src/main/kotlin/app/readylytics/health/data/local/DatabaseUpgradeSql.kt` — shared v5→v6 additive SQL.
- `core/model/src/main/kotlin/app/readylytics/health/data/local/entity/HeartRateRecordEntity.kt` — explicit v7 index names.
- `core/model/src/main/kotlin/app/readylytics/health/data/local/entity/HrvRecordEntity.kt` — explicit v7 index names.
- `core/database/schemas/app.readylytics.health.data.local.HealthDatabase/7.json` — regenerated final v7 schema.
- `app/src/main/kotlin/app/readylytics/health/di/DatabaseModule.kt` — refuse Room opening until external migration completes.
- `app/src/main/kotlin/app/readylytics/health/HealthDashboardApplication.kt` — defer database-bound startup work.
- `app/src/main/kotlin/app/readylytics/health/MainActivity.kt` — readiness-first rendering.
- `app/src/main/kotlin/app/readylytics/health/workers/HealthResyncWorker.kt` — lazy DB dependency after readiness.
- `app/src/main/kotlin/app/readylytics/health/workers/PeriodicHealthSyncWorker.kt` — lazy DB dependency after readiness.
- `app/src/main/kotlin/app/readylytics/health/workers/LocalBackupWorker.kt` — lazy DB dependency after readiness.
- `app/src/main/kotlin/app/readylytics/health/workers/DataCleanupWorker.kt` — lazy DB dependency after readiness.
- `app/src/main/kotlin/app/readylytics/health/workers/WorkerSchedulerImpl.kt` — unique migration work.
- `core/model/src/main/kotlin/app/readylytics/health/workers/WorkerScheduler.kt` — migration scheduling contract.
- `app/src/main/kotlin/app/readylytics/health/workers/SyncNotifications.kt` — migration notification channel/builders.
- `app/src/main/res/values/strings.xml` — migration progress/error copy.
- `app/build.gradle.kts` and `gradle/libs.versions.toml` — benchmark test dependency if not already present.
- `ABOUT.md`, `docs/about.md`, `docs/backup-and-data.md`, `internal-docs/DATA_FLOW.md`, and `feature/about/src/main/res/values/strings.xml` — synchronized behavior documentation.

### New files to create

- `app/src/main/kotlin/app/readylytics/health/data/migration/DatabaseMigrationModels.kt` — readiness, phase, progress, and result value types.
- `app/src/main/kotlin/app/readylytics/health/data/migration/DatabaseReadinessGate.kt` — encrypted file-version inspection.
- `app/src/main/kotlin/app/readylytics/health/data/migration/V7DatabaseMigrator.kt` — resumable state machine and SQL.
- `app/src/main/kotlin/app/readylytics/health/domain/migration/DatabaseMigrationController.kt` — WorkManager scheduling/progress facade.
- `app/src/main/kotlin/app/readylytics/health/workers/DatabaseMigrationWorker.kt` — foreground migration execution.
- `app/src/main/kotlin/app/readylytics/health/ui/migration/DatabaseMigrationScreen.kt` — blocking M3 UI.
- `app/src/test/kotlin/app/readylytics/health/data/migration/DatabaseMigrationModelsTest.kt` — state/progress contracts.
- `app/src/test/kotlin/app/readylytics/health/domain/migration/DatabaseMigrationControllerTest.kt` — WorkInfo mapping.
- `app/src/test/kotlin/app/readylytics/health/workers/DatabaseMigrationWorkerTest.kt` — result/progress behavior.
- `app/src/androidTest/kotlin/app/readylytics/health/data/migration/V7DatabaseMigratorInstrumentedTest.kt` — SQLCipher, WAL, interruption, and resume.
- `app/src/androidTest/kotlin/app/readylytics/health/data/migration/V7DatabaseMigrationBenchmark.kt` — 1M-row gate evidence.
- `app/src/androidTest/kotlin/app/readylytics/health/ui/migration/DatabaseMigrationScreenTest.kt` — progress/error UI.

---

### Task 1: Make recompute-only scoring fully offline

**Files:**

- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCase.kt:390-430`
- Modify: `app/src/test/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCaseTest.kt:363`

**Interfaces:**

- Consumes: `skipIngestAndPrune: Boolean`.
- Produces: `stepsMap: Map<LocalDate, Long>` that is empty without calling `StepCountFetcher` in recompute-only mode; `stepsForDay: Long?` remains `null` so Room's stored summary value is preserved.

- [ ] **Step 1: Strengthen the existing failing contract test**

Add capture of the optional step override and assertions for every step API:

```kotlin
@Test
fun `skipIngestAndPrune recomputes from Room without Health Connect and preserves steps`() =
    runTest {
        val startDate = LocalDate.of(2024, 6, 1)
        val endDate = LocalDate.of(2024, 6, 2)
        val stepOverrides = mutableListOf<Long?>()
        coEvery {
            scoringRepository.computeAndPersistDailySummary(
                any(),
                captureNullable(stepOverrides),
                any(),
                any(),
                any(),
            )
        } returns Unit

        useCase.run(startDate, endDate, 30, null, skipIngestAndPrune = true)

        assertEquals(listOf(null, null), stepOverrides)
        coVerify(exactly = 0) { hcRepo.readDailyStepTotals(any(), any(), any()) }
        coVerify(exactly = 0) { hcRepo.readSteps(any(), any()) }
        coVerify(exactly = 0) { hcRepo.readStepsRecords(any(), any()) }
        coVerify(exactly = 0) { hcRepo.readSleepSessions(any(), any()) }
        coVerify(exactly = 0) { hcRepo.readHeartRateSamplesPaged(any(), any(), any()) }
        coVerify(exactly = 0) { hcRepo.readHrvSamplesPaged(any(), any(), any()) }
        coVerify(exactly = 2) {
            scoringRepository.computeAndPersistDailySummary(any(), null, any(), any(), any())
        }
    }
```

- [ ] **Step 2: Run the test and verify the current grouped-step call fails it**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*ResyncRangeUseCaseTest.skipIngestAndPrune recomputes from Room without Health Connect and preserves steps'
```

Expected: FAIL because `readDailyStepTotals` is invoked.

- [ ] **Step 3: Guard `fetchRange` and keep overrides null**

Replace the current `stepsMap` initialization with:

```kotlin
val stepsMap =
    if (!skipIngestAndPrune && !recomputeStartDate.isAfter(endDate)) {
        stepCountFetcher.fetchRange(
            startDate = recomputeStartDate,
            endDate = endDate,
            chunkDays = chunkDays,
            stepsDevice = stepsDevice,
            zoneId = zoneId,
        )
    } else {
        emptyMap()
    }
```

Keep the daily selection explicit:

```kotlin
val stepsForDay =
    when {
        skipIngestAndPrune -> null
        stepsDevice != null -> stepsMap[day] ?: 0L
        else -> stepsMap[day]
    }
```

- [ ] **Step 4: Run the focused sync tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*ResyncRangeUseCaseTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCase.kt app/src/test/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCaseTest.kt
git commit -m "fix: keep recompute-only scoring offline"
```

---

### Task 2: Write zeros for empty grouped-step days

**Files:**

- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/StepCountFetcher.kt:95-114`
- Modify: `core/healthconnect/src/test/kotlin/app/readylytics/health/domain/sync/StepCountFetcherRangeTest.kt`

**Interfaces:**

- Produces: `fetchRange(startDate, endDate, ..., stepsDevice = null)` returns an entry for every requested date.

- [ ] **Step 1: Add empty and sparse aggregate tests**

```kotlin
@Test
fun `fetchRange materializes zero for every day omitted by grouped aggregates`() =
    runTest {
        val start = LocalDate.of(2024, 1, 1)
        val end = LocalDate.of(2024, 1, 3)
        coEvery { hcRepo.readDailyStepTotals(any(), any(), any()) } returns emptyMap()

        val result = fetcher.fetchRange(start, end, 30, null, zoneId)

        assertEquals(
            mapOf(start to 0L, start.plusDays(1) to 0L, end to 0L),
            result,
        )
    }

@Test
fun `fetchRange overlays sparse grouped totals onto zero-filled days`() =
    runTest {
        val start = LocalDate.of(2024, 1, 1)
        val end = LocalDate.of(2024, 1, 3)
        coEvery { hcRepo.readDailyStepTotals(any(), any(), any()) } returns
            mapOf(start.plusDays(1) to 4_321L)

        val result = fetcher.fetchRange(start, end, 30, null, zoneId)

        assertEquals(0L, result[start])
        assertEquals(4_321L, result[start.plusDays(1)])
        assertEquals(0L, result[end])
    }
```

- [ ] **Step 2: Run the new tests and verify missing keys fail**

Run:

```bash
./gradlew :core:healthconnect:testDebugUnitTest --tests '*StepCountFetcherRangeTest'
```

Expected: FAIL because omitted dates are absent.

- [ ] **Step 3: Zero-fill each chunk before overlay**

Immediately before `readDailyStepTotals`:

```kotlin
var day = chunkStart
while (day.isBefore(chunkEndExclusive)) {
    stepsMap[day] = 0L
    day = day.plusDays(1)
}
stepsMap.putAll(
    retryWithBackoff {
        hcRepo.readDailyStepTotals(windowStart, windowEnd, zoneId)
    },
)
```

- [ ] **Step 4: Run the health-connect test class**

Run:

```bash
./gradlew :core:healthconnect:testDebugUnitTest --tests '*StepCountFetcherRangeTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/StepCountFetcher.kt core/healthconnect/src/test/kotlin/app/readylytics/health/domain/sync/StepCountFetcherRangeTest.kt
git commit -m "fix: clear empty grouped step days"
```

---

### Task 3: Make walk-forward baseline prefetch DST-safe

**Files:**

- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/BaselineComputer.kt:505-521`
- Modify: `app/src/test/kotlin/app/readylytics/health/domain/scoring/BaselineComputerWalkForwardEquivalenceTest.kt`

**Interfaces:**

- Produces: prefetch lower bound identical to `fromMs - 56 * 24h` used by per-day HRV methods.

- [ ] **Step 1: Add a spring-forward regression**

Use a dedicated `Europe/Berlin` fixture whose extra session starts in the omitted hour:

```kotlin
@Test
fun `walk-forward prefetch is a fixed-duration superset across spring forward`() =
    runTest {
        val berlin = ZoneId.of("Europe/Berlin")
        val scoreDay = LocalDate.of(2025, 4, 1)
        val fixedFrom =
            scoreDay.atStartOfDay(berlin).toInstant()
                .minus(56, java.time.temporal.ChronoUnit.DAYS)
                .toEpochMilli()
        val boundarySession =
            SleepSessionEntity(
                id = "dst-boundary",
                startTime = fixedFrom + 30 * 60 * 1000L,
                endTime = fixedFrom + 6 * 60 * 60 * 1000L,
                durationMinutes = 330,
                efficiency = 92f,
                deepSleepMinutes = 70,
                remSleepMinutes = 80,
                lightSleepMinutes = 170,
                awakeMinutes = 10,
            )
        sessions += boundarySession
        rmssdById[boundarySession.id] = listOf(42f, 44f)
        avgHrById[boundarySession.id] = 54
        hrProjectionById[boundarySession.id] = (48..60).toList()

        val prefetched =
            baselineComputer.prefetchWalkForwardSessions(scoreDay, scoreDay, berlin)

        assertEquals(true, prefetched.any { it.id == boundarySession.id })
    }
```

- [ ] **Step 2: Verify the test fails under the calendar-date bound**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*BaselineComputerWalkForwardEquivalenceTest.walk-forward prefetch is a fixed-duration superset across spring forward'
```

Expected: FAIL because `dst-boundary` is absent.

- [ ] **Step 3: Use the same instant-duration calculation as the consumers**

```kotlin
val fromMs =
    startDate
        .atStartOfDay(zoneId)
        .toInstant()
        .minus(
            ScoringConstants.HRV_SIGMA_WINDOW_DAYS.toLong(),
            ChronoUnit.DAYS,
        ).toEpochMilli()
```

Update the KDoc to say the lower bound is a fixed-duration superset, not a calendar-date subtraction.

- [ ] **Step 4: Run all baseline equivalence tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*BaselineComputer*EquivalenceTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/BaselineComputer.kt app/src/test/kotlin/app/readylytics/health/domain/scoring/BaselineComputerWalkForwardEquivalenceTest.kt
git commit -m "fix: make baseline prefetch DST-safe"
```

---

### Task 4: Restore v5/v6 backups into v7

**Files:**

- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/BackupModels.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/LocalRestoreManager.kt`
- Modify: `app/src/test/kotlin/app/readylytics/health/data/backup/LocalRestoreManagerTest.kt`

**Interfaces:**

- Produces: `BackupSchemaPolicy.requireSupported(version: Int)`.
- Produces: `LegacyHeartRateRecordBackup.toCurrent()` and `LegacyHrvRecordBackup.toCurrent()`.
- Changes: `performStreamingRestore(reader, schemaVersion, onPreferencesParsed)`.

- [ ] **Step 1: Add version policy and legacy DTO tests**

```kotlin
@Test
fun `validate accepts backup versions five through seven`() = runTest {
    for (version in 5..HealthDatabase.DATABASE_VERSION) {
        val json = createValidBackupJson().put("schemaVersion", version)
        val zip = createBackupZipFile("backup-v$version.zip", json)
        assertTrue(manager.validate(Uri.fromFile(zip)).isSuccess)
        zip.delete()
    }
}

@Test
fun `legacy heart and HRV ids migrate to source record ids`() = runTest {
    val timestamp = 1_700_000_000_000L
    val json =
        createValidBackupJson()
            .put("schemaVersion", 5)
            .put(
                "heartRateRecords",
                JSONArray().put(
                    JSONObject()
                        .put("id", "hc-heart_$timestamp")
                        .put("timestampMs", timestamp)
                        .put("beatsPerMinute", 61)
                        .put("recordType", "SLEEP"),
                ),
            ).put(
                "hrvRecords",
                JSONArray().put(
                    JSONObject()
                        .put("id", "hc-hrv_$timestamp")
                        .put("timestampMs", timestamp)
                        .put("rmssdMs", 48.5)
                        .put("recordType", "SLEEP"),
                ),
            )
    val zip = createBackupZipFile("legacy-v5.zip", json)

    assertTrue(manager.applyRestore(Uri.fromFile(zip)) is RestoreResult.SuccessRequiresRestart)
    assertEquals("hc-heart", db.heartRateDao().getBetween(0, Long.MAX_VALUE).single().sourceRecordId)
    assertEquals("hc-hrv", db.hrvDao().getBetween(0, Long.MAX_VALUE).single().sourceRecordId)
}
```

Also retain a future-version rejection assertion and add a malformed suffix case that preserves the
full `id`.

- [ ] **Step 2: Verify current strict equality/decoder failures**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*LocalRestoreManagerTest'
```

Expected: FAIL for v5/v6 validation and required `sourceRecordId`.

- [ ] **Step 3: Add explicit supported-version and conversion models**

Add to `BackupModels.kt`:

```kotlin
internal object BackupSchemaPolicy {
    const val MIN_SUPPORTED_VERSION = 5

    fun requireSupported(version: Int) {
        require(version in MIN_SUPPORTED_VERSION..HealthDatabase.DATABASE_VERSION) {
            "Unsupported backup schema version $version; supported range is " +
                "$MIN_SUPPORTED_VERSION..${HealthDatabase.DATABASE_VERSION}"
        }
    }
}

@Serializable
internal data class LegacyHeartRateRecordBackup(
    val id: String,
    val timestampMs: Long,
    val beatsPerMinute: Int,
    val recordType: String,
    val sessionId: String? = null,
    val deviceName: String? = null,
) {
    fun toCurrent() =
        HeartRateRecordEntity(
            sourceRecordId = legacySourceRecordId(id, timestampMs),
            timestampMs = timestampMs,
            beatsPerMinute = beatsPerMinute,
            recordType = recordType,
            sessionId = sessionId,
            deviceName = deviceName,
        )
}

@Serializable
internal data class LegacyHrvRecordBackup(
    val id: String,
    val timestampMs: Long,
    val rmssdMs: Float,
    val recordType: String,
    val sessionId: String? = null,
    val deviceName: String? = null,
) {
    fun toCurrent() =
        HrvRecordEntity(
            sourceRecordId = legacySourceRecordId(id, timestampMs),
            timestampMs = timestampMs,
            rmssdMs = rmssdMs,
            recordType = recordType,
            sessionId = sessionId,
            deviceName = deviceName,
        )
}

internal fun legacySourceRecordId(id: String, timestampMs: Long): String {
    val suffix = "_$timestampMs"
    return if (id.endsWith(suffix) && id.length > suffix.length) id.dropLast(suffix.length) else id
}
```

- [ ] **Step 4: Read the manifest before entering the restore transaction**

Extract one `readManifest(zipFile: ZipFile): BackupManifest` helper and call
`BackupSchemaPolicy.requireSupported(schemaVersion)` from both `validate` and `applyRestore`.
Pass `manifest.schemaVersion` into `performStreamingRestore`. Decode HR/HRV rows as:

```kotlin
val entity =
    if (schemaVersion >= 7) {
        json.decodeFromString<HeartRateRecordEntity>(row)
    } else {
        json.decodeFromString<LegacyHeartRateRecordBackup>(row).toCurrent()
    }
```

Use the analogous HRV branch. Do not clear any DAO until manifest and password validation finish.

- [ ] **Step 5: Run restore tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*LocalRestoreManagerTest'
```

Expected: PASS for v5, v6, v7, unsupported version, rollback, and cancellation cases.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/app/readylytics/health/data/backup/BackupModels.kt app/src/main/kotlin/app/readylytics/health/data/backup/LocalRestoreManager.kt app/src/test/kotlin/app/readylytics/health/data/backup/LocalRestoreManagerTest.kt
git commit -m "fix: migrate legacy backups during restore"
```

---

### Task 5: Define the external v7 migration contract and Room guard

**Files:**

- Create: `app/src/main/kotlin/app/readylytics/health/data/migration/DatabaseMigrationModels.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/data/migration/DatabaseReadinessGate.kt`
- Create: `core/database/src/main/kotlin/app/readylytics/health/data/local/DatabaseUpgradeSql.kt`
- Create: `app/src/test/kotlin/app/readylytics/health/data/migration/DatabaseMigrationModelsTest.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/security/SqlCipherKeyManager.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/data/local/DatabaseMigrations.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/di/DatabaseModule.kt`
- Modify: `app/src/test/kotlin/app/readylytics/health/data/local/DatabaseMigrationTest.kt`

**Interfaces:**

- Produces: `DatabaseReadinessGate.inspect(): DatabaseReadiness`.
- Produces: `SqlCipherKeyManager.withWritableDatabase(dbFile, block)`.
- Room migration chain ends at 6; v7 is completed only by `V7DatabaseMigrator`.

- [ ] **Step 1: Write the migration/readiness models**

```kotlin
enum class V7MigrationPhase {
    PREFLIGHT,
    UPGRADE_5_TO_6,
    CREATE_SHADOW_TABLES,
    COPY_HEART_RATE,
    COPY_HRV,
    INDEX_HEART_RATE_TIMESTAMP,
    INDEX_HEART_RATE_SESSION,
    INDEX_HEART_RATE_TYPE_TIME,
    INDEX_HRV_TIMESTAMP,
    INDEX_HRV_TYPE_TIME,
    INDEX_HRV_SESSION,
    VALIDATE,
    SWAP,
    COMPLETE,
}

sealed interface DatabaseReadiness {
    data object Ready : DatabaseReadiness
    data class MigrationRequired(val fromVersion: Int) : DatabaseReadiness
    data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long) : DatabaseReadiness
    data class Failed(val message: String) : DatabaseReadiness
}

data class DatabaseMigrationProgress(
    val phase: V7MigrationPhase,
    val copiedRows: Long,
    val totalRows: Long,
)

sealed interface V7MigrationResult {
    data object Complete : V7MigrationResult
    data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long) : V7MigrationResult
    data class Failed(val reason: String) : V7MigrationResult
}
```

Test that progress fractions are monotonic and clamp to `[0f, 1f]`.

- [ ] **Step 2: Add scoped raw SQLCipher access**

Add a method that owns and zeroes the key:

```kotlin
fun <T> withWritableDatabase(
    dbFile: File,
    block: (net.zetetic.database.sqlcipher.SQLiteDatabase) -> T,
): T {
    val rawKey = getOrCreateDbKey(dbFile)
    return try {
        val keyHex = rawKey.toHex()
        net.zetetic.database.sqlcipher.SQLiteDatabase
            .openOrCreateDatabase(dbFile, "x'$keyHex'", null, null, null)
            .use(block)
    } finally {
        rawKey.fill(0)
    }
}
```

Do not expose the raw key, hex string, or open database outside the callback.

- [ ] **Step 3: Implement readiness inspection**

`DatabaseReadinessGate.inspect()` returns `Ready` for a missing file or `user_version == 7`,
`MigrationRequired(5)`/`MigrationRequired(6)` for the two supported database upgrade origins (or a
file with migration metadata), and `Failed` for all other versions. It queries only
`PRAGMA user_version` and `sqlite_master`; it never creates Room.

- [ ] **Step 4: Remove the unsafe Room callback**

Extract the existing v5→v6 statements into:

```kotlin
object DatabaseUpgradeSql {
    val V5_TO_V6 =
        listOf(
            "ALTER TABLE `workout_records` ADD COLUMN `modelTrimp` REAL DEFAULT NULL",
            """
            CREATE TABLE IF NOT EXISTS `step_records` (
                `id` TEXT NOT NULL,
                `startTime` INTEGER NOT NULL,
                `endTime` INTEGER NOT NULL,
                `count` INTEGER NOT NULL,
                `deviceName` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
            "DROP INDEX IF EXISTS `index_daily_summaries_dateMidnightMs`",
        )
}
```

Make `MIGRATION_5_6` execute this list. Delete `MIGRATION_6_7` from
`DatabaseMigrations.all`. Remove debug
`fallbackToDestructiveMigration(dropAllTables = true)`. Before `Room.databaseBuilder`, require:

```kotlin
check(databaseReadinessGate.inspect() == DatabaseReadiness.Ready) {
    "HealthDatabase cannot open before the external v7 migration is complete"
}
```

Change the migration registration test to assert `DatabaseMigrations.all.last().endVersion == 6`
and `HealthDatabase.DATABASE_VERSION == 7`.

- [ ] **Step 5: Run unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*DatabaseMigrationModelsTest' --tests '*DatabaseMigrationTest'
```

Expected: PASS.

- [ ] **Step 6: Index and commit**

```bash
codegraph index
git add app/src/main/kotlin/app/readylytics/health/data/migration app/src/test/kotlin/app/readylytics/health/data/migration app/src/main/kotlin/app/readylytics/health/data/security/SqlCipherKeyManager.kt core/database/src/main/kotlin/app/readylytics/health/data/local/DatabaseMigrations.kt core/database/src/main/kotlin/app/readylytics/health/data/local/DatabaseUpgradeSql.kt app/src/main/kotlin/app/readylytics/health/di/DatabaseModule.kt app/src/test/kotlin/app/readylytics/health/data/local/DatabaseMigrationTest.kt
git commit -m "refactor: gate Room behind external v7 migration"
```

---

### Task 6: Implement the resumable SQLCipher v7 copy engine

**Files:**

- Create: `app/src/main/kotlin/app/readylytics/health/data/migration/V7DatabaseMigrator.kt`
- Create: `app/src/androidTest/kotlin/app/readylytics/health/data/migration/V7DatabaseMigratorInstrumentedTest.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/data/local/entity/HeartRateRecordEntity.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/data/local/entity/HrvRecordEntity.kt`
- Modify: `core/database/schemas/app.readylytics.health.data.local.HealthDatabase/7.json`

**Interfaces:**

- Produces:

```kotlin
suspend fun migrate(
    onProgress: suspend (DatabaseMigrationProgress) -> Unit,
): V7MigrationResult
```

- Batch size: 10,000 rows.
- Checkpoint row: single id `v7`, phase name, last HR id, last HRV id, copied counts, total counts.

- [ ] **Step 1: Add an instrumented v6 fixture and first failing resume test**

Seed both v5 and v6 databases with HR and HRV rows whose legacy IDs are
`"source_$timestampMs"`. For the v5 fixture also seed a workout and assert `modelTrimp` plus
`step_records` after migration. Interrupt after the first 10,000-row callback, reopen, resume, and
assert:

```kotlin
assertEquals(7, pragmaUserVersion(db))
assertEquals(sourceHeartRateCount, queryLong(db, "SELECT COUNT(*) FROM heart_rate_records"))
assertEquals(sourceHrvCount, queryLong(db, "SELECT COUNT(*) FROM hrv_records"))
assertEquals(
    0L,
    queryLong(
        db,
        "SELECT COUNT(*) FROM heart_rate_records " +
            "WHERE sourceRecordId LIKE '%_' || timestampMs",
    ),
)
```

Also assert no `_v7` tables or migration metadata remain after success.

- [ ] **Step 2: Create metadata and shadow tables**

Use these durable tables and explicit v7 index names:

```sql
CREATE TABLE IF NOT EXISTS readylytics_schema_migration (
    migrationId TEXT NOT NULL PRIMARY KEY,
    phase TEXT NOT NULL,
    lastHeartRateId TEXT,
    lastHrvId TEXT,
    copiedHeartRateRows INTEGER NOT NULL,
    copiedHrvRows INTEGER NOT NULL,
    totalHeartRateRows INTEGER NOT NULL,
    totalHrvRows INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS heart_rate_records_v7 (
    rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    sourceRecordId TEXT NOT NULL,
    timestampMs INTEGER NOT NULL,
    beatsPerMinute INTEGER NOT NULL,
    recordType TEXT NOT NULL,
    sessionId TEXT,
    deviceName TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS index_hr_v7_source_time
ON heart_rate_records_v7(sourceRecordId, timestampMs);
```

Create the analogous HRV table and `index_hrv_v7_source_time`. Give every remaining final index a
distinct `*_v7_*` name and use those exact names in the entity `@Index(name = ...)` declarations.

- [ ] **Step 3: Add preflight space calculation**

Compute:

```kotlin
val walFile = File("${dbFile.absolutePath}-wal")
val sourceBytes = dbFile.length() + walFile.length().coerceAtLeast(0L)
val requiredBytes = sourceBytes + sourceBytes / 4L + 64L * 1024L * 1024L
val availableBytes = StatFs(dbFile.parentFile!!.absolutePath).availableBytes
```

If `availableBytes < requiredBytes`, return `InsufficientSpace` before creating metadata or shadow
tables.

- [ ] **Step 4: Advance v5 databases through the shared additive migration**

When `PRAGMA user_version` is 5, run every statement in `DatabaseUpgradeSql.V5_TO_V6` and
`PRAGMA user_version = 6` in one transaction. Emit `UPGRADE_5_TO_6` progress. A process death either
rolls this transaction back to v5 or leaves a complete v6 file; the next run handles both.

- [ ] **Step 5: Copy HR in atomic keyset batches**

Normalize the old composite ID during SQL copy:

```sql
INSERT OR IGNORE INTO heart_rate_records_v7
    (sourceRecordId, timestampMs, beatsPerMinute, recordType, sessionId, deviceName)
SELECT
    CASE
        WHEN substr(id, -(length(CAST(timestampMs AS TEXT)) + 1)) =
            '_' || CAST(timestampMs AS TEXT)
        THEN substr(id, 1, length(id) - length(CAST(timestampMs AS TEXT)) - 1)
        ELSE id
    END,
    timestampMs, beatsPerMinute, recordType, sessionId, deviceName
FROM heart_rate_records
WHERE id > ?
ORDER BY id
LIMIT 10000;
```

Within the same transaction, obtain the batch's last source key with:

```sql
SELECT MAX(id)
FROM (
    SELECT id
    FROM heart_rate_records
    WHERE id > ?
    ORDER BY id
    LIMIT 10000
);
```

Persist that key and the incremented copied count in the metadata row before committing. Re-read the
checkpoint after every transaction and call `ensureActive()` plus `yield()` between batches. Apply
the identical algorithm to HRV.

- [ ] **Step 6: Build and checkpoint secondary indexes**

Create one index per transaction and advance the phase only after success:

```sql
CREATE INDEX IF NOT EXISTS index_hr_v7_timestamp
ON heart_rate_records_v7(timestampMs);
CREATE INDEX IF NOT EXISTS index_hr_v7_session_type_bpm
ON heart_rate_records_v7(sessionId, recordType, beatsPerMinute);
CREATE INDEX IF NOT EXISTS index_hr_v7_type_timestamp
ON heart_rate_records_v7(recordType, timestampMs);
CREATE INDEX IF NOT EXISTS index_hrv_v7_timestamp
ON hrv_records_v7(timestampMs);
CREATE INDEX IF NOT EXISTS index_hrv_v7_type_timestamp
ON hrv_records_v7(recordType, timestampMs);
CREATE INDEX IF NOT EXISTS index_hrv_v7_session
ON hrv_records_v7(sessionId);
```

- [ ] **Step 7: Validate and perform one atomic cutover**

Before cutover, require equal source/target counts and zero duplicate source/time groups. Then:

```sql
BEGIN IMMEDIATE;
DROP TABLE heart_rate_records;
ALTER TABLE heart_rate_records_v7 RENAME TO heart_rate_records;
DROP TABLE hrv_records;
ALTER TABLE hrv_records_v7 RENAME TO hrv_records;
DROP TABLE readylytics_schema_migration;
PRAGMA user_version = 7;
COMMIT;
```

On any error, roll back and return `Failed`; never delete the v6 tables outside this final
transaction.

- [ ] **Step 8: Add interruption coverage for every phase**

Parameterize the instrumented test over every copy/index/validate phase. After forced cancellation,
assert `user_version == 6` and legacy tables still exist; after resume, run
`MigrationTestHelper.runMigrationsAndValidate(..., 7, true)` against the exported v7 schema without
invoking a Room v6→v7 migration.

- [ ] **Step 9: Run instrumented migration tests**

Run on an API 26+ emulator:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.data.migration.V7DatabaseMigratorInstrumentedTest
```

Expected: PASS with SQLCipher and WAL enabled.

- [ ] **Step 10: Regenerate schema, index, and commit**

```bash
./gradlew :app:kspDebugKotlin
codegraph index
git add app/src/main/kotlin/app/readylytics/health/data/migration/V7DatabaseMigrator.kt app/src/androidTest/kotlin/app/readylytics/health/data/migration/V7DatabaseMigratorInstrumentedTest.kt core/model/src/main/kotlin/app/readylytics/health/data/local/entity/HeartRateRecordEntity.kt core/model/src/main/kotlin/app/readylytics/health/data/local/entity/HrvRecordEntity.kt core/database/schemas/app.readylytics.health.data.local.HealthDatabase/7.json
git commit -m "feat: add resumable v7 database migration"
```

---

### Task 7: Run migration as foreground work and gate all Room consumers

**Files:**

- Create: `app/src/main/kotlin/app/readylytics/health/domain/migration/DatabaseMigrationController.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/workers/DatabaseMigrationWorker.kt`
- Create: `app/src/test/kotlin/app/readylytics/health/domain/migration/DatabaseMigrationControllerTest.kt`
- Create: `app/src/test/kotlin/app/readylytics/health/workers/DatabaseMigrationWorkerTest.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/workers/WorkerScheduler.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/workers/WorkerSchedulerImpl.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/workers/SyncNotifications.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/workers/HealthResyncWorker.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/workers/PeriodicHealthSyncWorker.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/workers/LocalBackupWorker.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/workers/DataCleanupWorker.kt`

**Interfaces:**

- Unique name: `DATABASE_MIGRATION_WORK_NAME = "database_v7_migration"`.
- Work progress keys: `phase`, `copiedRows`, `totalRows`, `requiredBytes`, `availableBytes`.
- Controller API:

```kotlin
data class DatabaseMigrationUiState(
    val readiness: DatabaseReadiness,
    val progress: DatabaseMigrationProgress? = null,
)

interface DatabaseMigrationController {
    val state: StateFlow<DatabaseMigrationUiState>
    fun startOrResume()
}
```

- Non-migration DB workers return `Result.retry()` without resolving their lazy Room dependency
  while readiness is not `Ready`.

- [ ] **Step 1: Write scheduler/controller tests**

Assert `scheduleDatabaseMigration()` enqueues `DatabaseMigrationWorker` with
`ExistingWorkPolicy.KEEP`, and map `WorkInfo` into `DatabaseReadiness`/`DatabaseMigrationProgress`.

- [ ] **Step 2: Add scheduler contract**

```kotlin
fun scheduleDatabaseMigration()

const val DATABASE_MIGRATION_WORK_NAME = "database_v7_migration"
```

Use a non-expedited one-time request with exponential backoff; the worker itself calls
`setForeground` immediately.

- [ ] **Step 3: Implement the foreground worker**

```kotlin
override suspend fun doWork(): Result {
    setForeground(buildForegroundInfo(V7MigrationPhase.PREFLIGHT, 0, 0))
    return when (
        val result =
            migrator.migrate { progress ->
                setProgress(
                    workDataOf(
                        KEY_PHASE to progress.phase.name,
                        KEY_COPIED_ROWS to progress.copiedRows,
                        KEY_TOTAL_ROWS to progress.totalRows,
                    ),
                )
                setForeground(
                    buildForegroundInfo(
                        progress.phase,
                        progress.copiedRows,
                        progress.totalRows,
                    ),
                )
            }
    ) {
        V7MigrationResult.Complete -> Result.success()
        is V7MigrationResult.InsufficientSpace ->
            Result.failure(
                workDataOf(
                    KEY_REQUIRED_BYTES to result.requiredBytes,
                    KEY_AVAILABLE_BYTES to result.availableBytes,
                ),
            )
        is V7MigrationResult.Failed -> Result.retry()
    }
}
```

Rethrow `CancellationException`. Use `FOREGROUND_SERVICE_TYPE_DATA_SYNC`.

- [ ] **Step 4: Gate existing database workers before lazy resolution**

Change database-bound constructor parameters to `dagger.Lazy<T>`. At the first line of `doWork()`:

```kotlin
if (databaseReadinessGate.inspect() != DatabaseReadiness.Ready) {
    return Result.retry()
}
```

Only then call `.get()` on `HealthSyncUseCase`, `FullHistoricalResyncUseCase`,
`LocalBackupManager`, or `RetentionCleanup`. `BirthdayCheckWorker` needs no change because it uses
DataStore only.

- [ ] **Step 5: Run worker and scheduler unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*DatabaseMigration*Test' --tests '*WorkerSchedulerTest' --tests '*HealthResyncWorkerTest' --tests '*PeriodicHealthSyncWorkerTest' --tests '*LocalBackupWorkerTest' --tests '*DataCleanupWorkerTest'
```

Expected: PASS; tests explicitly verify lazy DB dependencies are untouched while migration is
required.

- [ ] **Step 6: Index and commit**

```bash
codegraph index
git add app/src/main/kotlin/app/readylytics/health/domain/migration app/src/main/kotlin/app/readylytics/health/workers app/src/test/kotlin/app/readylytics/health/domain/migration app/src/test/kotlin/app/readylytics/health/workers core/model/src/main/kotlin/app/readylytics/health/workers/WorkerScheduler.kt
git commit -m "feat: run v7 migration as foreground work"
```

---

### Task 8: Add readiness-first startup and blocking Material 3 UI

**Files:**

- Create: `app/src/main/kotlin/app/readylytics/health/ui/migration/DatabaseMigrationScreen.kt`
- Create: `app/src/androidTest/kotlin/app/readylytics/health/ui/migration/DatabaseMigrationScreenTest.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/MainActivity.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/HealthDashboardApplication.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**

- `DatabaseMigrationScreen(readiness, progress, onRetry)`.
- Normal `SyncViewModel` and `LocalRestoreManager` are resolved only after `Ready`.
- Application backfill/scheduling begins only after `DatabaseReadiness.Ready`.

- [ ] **Step 1: Write Compose UI tests**

Cover:

- preparing/indeterminate state for both v5 and v6 origins;
- determinate `"42,000 of 100,000 records"`;
- insufficient-space text and retry button;
- validation failure without reset/delete action.

- [ ] **Step 2: Add resource strings**

Define:

```xml
<string name="database_migration_title">Updating your health database</string>
<string name="database_migration_description">Readylytics is optimizing locally stored heart-rate history. Keep the app open or let the foreground notification finish.</string>
<string name="database_migration_progress">%1$d of %2$d records</string>
<string name="database_migration_space_error">More device storage is required. Free at least %1$s, then retry.</string>
<string name="database_migration_retry">Retry</string>
<string name="database_migration_failed">Your existing data is unchanged. Retry the update or export diagnostics before continuing.</string>
<string name="database_migration_channel_name">Database updates</string>
<string name="database_migration_channel_description">Progress for required local database updates</string>
```

- [ ] **Step 3: Implement the blocking screen**

Use `Scaffold`, a centered `Card(colors = surfaceContainerHigh)`, `LinearProgressIndicator`,
`MaterialTheme.shapes.large`, and `Button(onRetry)`. Do not expose reset/destructive actions.

- [ ] **Step 4: Gate `MainActivity` before `hiltViewModel()`**

Inject `dagger.Lazy<LocalRestoreManager>` and the lightweight migration controller. Render:

```kotlin
when (val readiness = migrationState.readiness) {
    DatabaseReadiness.Ready -> ReadylyticsContent()
    is DatabaseReadiness.MigrationRequired,
    is DatabaseReadiness.InsufficientSpace,
    is DatabaseReadiness.Failed,
    -> DatabaseMigrationScreen(
        readiness = readiness,
        progress = migrationState.progress,
        onRetry = migrationController::startOrResume,
    )
}
```

Call `startOrResume()` once from `LaunchedEffect` for `MigrationRequired`. Only
`ReadylyticsContent()` may call `hiltViewModel<SyncViewModel>()`.

- [ ] **Step 5: Defer application startup database work**

Inject database-bound `HealthSyncUseCase` and `BackfillHistoricalBaselinesUseCase` as
`dagger.Lazy`. Observe readiness; only after `Ready` run baseline backfill and schedule backup,
birthday, cleanup, and periodic work. Ensure the block is guarded with an `AtomicBoolean` so
activity recreation does not duplicate initialization.

- [ ] **Step 6: Run UI/startup tests**

Run:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.ui.migration.DatabaseMigrationScreenTest
```

Expected: PASS and no Room open in migration-required startup tests.

- [ ] **Step 7: Index and commit**

```bash
codegraph index
git add app/src/main/kotlin/app/readylytics/health/ui/migration app/src/androidTest/kotlin/app/readylytics/health/ui/migration app/src/main/kotlin/app/readylytics/health/MainActivity.kt app/src/main/kotlin/app/readylytics/health/HealthDashboardApplication.kt app/src/main/res/values/strings.xml
git commit -m "feat: gate startup on v7 migration"
```

---

### Task 9: Add the 1M-row benchmark gate

**Files:**

- Create: `app/src/androidTest/kotlin/app/readylytics/health/data/migration/V7DatabaseMigrationBenchmark.kt`
- Modify: `app/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md`

**Interfaces:**

- Fixture: 1,000,000 HR rows plus representative HRV history.
- Gate passes when v7 improves 5,000-row ingest throughput by at least 30% or reduces final DB size
  by at least 25%.

- [ ] **Step 1: Add AndroidX Benchmark JUnit dependency**

Add `androidx.benchmark:benchmark-junit4` at the repository's AndroidX Benchmark version and wire it
to `androidTestImplementation`.

- [ ] **Step 2: Build deterministic v6/v7 fixtures**

Generate IDs as `"source-${recordIndex / samplesPerRecord}_${timestampMs}"`, insert in 5,000-row
transactions, checkpoint WAL, and record:

```kotlin
data class DatabaseBenchmarkResult(
    val schemaVersion: Int,
    val databaseBytes: Long,
    val ingestRowsPerSecond: Double,
    val migrationDurationMs: Long,
    val peakRequiredBytes: Long,
)
```

- [ ] **Step 3: Benchmark and assert the retained-v7 gate**

```kotlin
val throughputGain =
    (v7.ingestRowsPerSecond - v6.ingestRowsPerSecond) / v6.ingestRowsPerSecond
val sizeReduction =
    (v6.databaseBytes - v7.databaseBytes).toDouble() / v6.databaseBytes
assertTrue(
    "DB-001 gate failed: throughput=$throughputGain size=$sizeReduction",
    throughputGain >= 0.30 || sizeReduction >= 0.25,
)
```

Also cancel after a copy batch and include resume duration in the benchmark report.

- [ ] **Step 4: Run on the release-like benchmark device**

Run:

```bash
./gradlew :app:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.data.migration.V7DatabaseMigrationBenchmark
```

Expected: PASS one of the two DB-001 thresholds and record migration duration/space results.

- [ ] **Step 5: Record evidence and commit**

Write the measured device/API, v6/v7 sizes, rows/sec, percentage changes, migration duration, and
resume result into DB-001's decision section.

```bash
codegraph index
git add app/src/androidTest/kotlin/app/readylytics/health/data/migration/V7DatabaseMigrationBenchmark.kt app/build.gradle.kts gradle/libs.versions.toml internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md
git commit -m "test: benchmark v7 database migration"
```

---

### Task 10: Synchronize methodology, backup, and migration documentation

**Files:**

- Modify: `ABOUT.md`
- Modify: `docs/about.md`
- Modify: `docs/backup-and-data.md`
- Modify: `internal-docs/DATA_FLOW.md`
- Modify: `feature/about/src/main/res/values/strings.xml`
- Modify: `app/src/test/kotlin/app/readylytics/health/docs/DocumentationDriftTest.kt`

**Interfaces:**

- One canonical stage-less statement on every scoring-explanation surface.
- Backup docs explicitly support v5/v6/v7 restore into v7.
- Data-flow docs describe the pre-open migration state machine, not Room's old `INSERT SELECT`.

- [ ] **Step 1: Add a documentation drift assertion**

Require these exact concepts on all three public/in-app surfaces:

```kotlin
val requiredStageLessPhrases =
    listOf(
        "raw session span",
        "Duration 75%",
        "Architecture 0%",
        "Restoration 25%",
    )
```

Read `ABOUT.md`, `docs/about.md`, and `feature/about/src/main/res/values/strings.xml`; assert every
phrase exists in each.

- [ ] **Step 2: Verify the drift test currently fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*DocumentationDriftTest'
```

Expected: FAIL because only `DATA_FLOW.md` fully describes stage-less behavior.

- [ ] **Step 3: Add synchronized stage-less copy**

Add this meaning verbatim or with platform-appropriate punctuation:

> If a source provides a sleep session with no stage records, Readylytics uses the raw session span
> as total sleep duration. Because Architecture is unavailable, the Sleep Score reweights to
> Duration 75%, Architecture 0%, and Restoration 25%. This differs from suspicious but non-empty
> stage data: the source supplied stages, but their distribution failed plausibility checks.

Place it beside Sleep Score methodology in both Markdown files and in a new
`about_stage_less_reweight` string rendered by the About screen. Update relevant tooltip copy if the
About UI exposes Architecture details there.

- [ ] **Step 4: Correct database/backup documentation**

Document:

- v5→v6 additive setup and v6→v7 large-table work occur before Room opens;
- copies are keyset-paginated and checkpointed;
- v6 tables remain authoritative until the atomic swap;
- insufficient disk never mutates legacy data;
- v5/v6/v7 backup manifests restore into current v7 entities;
- legacy composite HR/HRV IDs are normalized to `(sourceRecordId, timestampMs)`.

Remove claims that `DatabaseMigrations.MIGRATION_6_7` performs a one-shot table rebuild.

- [ ] **Step 5: Run drift tests and commit**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*DocumentationDriftTest'
```

Expected: PASS.

```bash
git add ABOUT.md docs/about.md docs/backup-and-data.md internal-docs/DATA_FLOW.md feature/about/src/main/res/values/strings.xml app/src/test/kotlin/app/readylytics/health/docs/DocumentationDriftTest.kt
git commit -m "docs: synchronize stage-less and v7 behavior"
```

---

### Task 11: Complete repository verification

**Files:**

- Inspect: all files changed in Tasks 1–10.

- [ ] **Step 1: Confirm no accidental unrelated changes**

Run:

```bash
git status --short
git diff --check
git diff --stat main...HEAD
```

Expected: only remediation, tests, schema export, benchmark evidence, and synchronized docs.

- [ ] **Step 2: Format**

Run:

```bash
./gradlew ktlintFormat
```

Expected: PASS; inspect and commit any formatter-only changes with their owning task.

- [ ] **Step 3: Run all unit tests**

Run:

```bash
./gradlew testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 4: Run release lint**

Run:

```bash
./gradlew lintRelease
```

Expected: PASS with zero errors.

- [ ] **Step 5: Run required instrumented suites**

Run on API 26 and a current target API emulator:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.data.migration.V7DatabaseMigratorInstrumentedTest,app.readylytics.health.ui.migration.DatabaseMigrationScreenTest,app.readylytics.health.data.local.DatabaseMigrationInstrumentedTest
```

Expected: PASS on both API levels.

- [ ] **Step 6: Re-run benchmark gate**

Run:

```bash
./gradlew :app:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.data.migration.V7DatabaseMigrationBenchmark
```

Expected: same gate outcome recorded in Task 9 within normal device variance.

- [ ] **Step 7: Refresh codegraph and verify clean state**

```bash
codegraph index
git status --short
git log --oneline main..HEAD
```

Expected: index succeeds; worktree is clean; each task has its own reviewable commit.
