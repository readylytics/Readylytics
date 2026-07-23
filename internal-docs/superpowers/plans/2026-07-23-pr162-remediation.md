# PR 162 Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix every finding from the `/review 162` code review (DB-001 migration crash risk, missing migration test coverage, broken docs, dropped index, `rowId` upsert-stability trap, incomplete SEC-001 log redaction, dead `DashboardViewModel` parameter) so PR 162 is safe to merge.

**Architecture:** Each finding is an independent, narrowly-scoped fix against the existing `feature/phase5` branch — no new subsystems. DB-001 fixes touch `core/database` (migration SQL, entity indices, generated schema JSON). SEC-001 fix touches `app/.../util/SecureFileLogSink.kt`. Docs and dashboard cleanup are single-file changes.

**Tech Stack:** Kotlin, Room 2.8.4 (KSP schema export via `room-conventions` plugin), JUnit4, MockK, Room `MigrationTestHelper` (instrumented tests), Robolectric (JVM unit tests).

## Global Constraints

- Pre-commit (mandatory, from repo `CLAUDE.md`): `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` before every commit in this plan.
- Run `./gradlew lintRelease` once at the very end, after the last task, per repo `CLAUDE.md`.
- `heart_rate_records` / `hrv_records` schema is version 7 (`core/database/schemas/app.readylytics.health.data.local.HealthDatabase/7.json`). Version 7 has not shipped in any released build yet (PR 162 is unmerged), so schema tweaks in this plan edit v7 in place — they do **not** require a v7→v8 migration.
- Do not touch scoring formulas, thresholds, or `internal-docs/DATA_FLOW.md` sections unrelated to the v6→v7 migration paragraph (repo rule: `DATA_FLOW.md` changes must stay scoped to what actually changed).
- Never widen sync windows, never remove the `syncMutex`, never touch `ScoringRepository.computeDailySummary` — out of scope for this plan and forbidden by repo `CLAUDE.md` regardless.

---

## Note on current repo state

`core/database/src/main/kotlin/app/readylytics/health/data/local/DatabaseMigrations.kt` currently has an **uncommitted** local change that already replaces the empty `MIGRATION_6_7` with a real table-rebuild. Task 1 below gives the complete final code for that file regardless — if you're executing this plan in a fresh worktree (where uncommitted changes from the original checkout do **not** carry over), Task 1's steps write the fix from scratch and behave identically. If the uncommitted change is already present, Task 1's diff step is a no-op and you proceed straight to the ktlint/whitespace cleanup and commit.

---

### Task 1: Real MIGRATION_6_7 table rebuild (fixes release-crash risk)

**Files:**
- Modify: `core/database/src/main/kotlin/app/readylytics/health/data/local/DatabaseMigrations.kt:172-230`

**Interfaces:**
- Produces: `DatabaseMigrations.MIGRATION_6_7` (private, registered in `DatabaseMigrations.all`) — a real `Migration(6, 7)` that rebuilds `heart_rate_records` and `hrv_records` to match the `rowId`/`sourceRecordId` schema in `7.json`.

- [ ] **Step 1: Replace the `MIGRATION_6_7` block with the full rebuild**

Replace the existing `private val MIGRATION_6_7 = ...` block (lines 172-230) with:

```kotlin
    private val MIGRATION_6_7 =
        object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // DB-001: rebuild heart_rate_records / hrv_records onto an autoincrement
                // rowId primary key (sourceRecordId is no longer unique on its own — HC
                // can emit multiple samples sharing an id across re-ingestion windows).

                // Rebuild heart_rate_records
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `heart_rate_records_new` (
                        `rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sourceRecordId` TEXT NOT NULL,
                        `timestampMs` INTEGER NOT NULL,
                        `beatsPerMinute` INTEGER NOT NULL,
                        `recordType` TEXT NOT NULL,
                        `sessionId` TEXT,
                        `deviceName` TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `heart_rate_records_new` (`sourceRecordId`, `timestampMs`, `beatsPerMinute`, `recordType`, `sessionId`, `deviceName`)
                    SELECT `id`, `timestampMs`, `beatsPerMinute`, `recordType`, `sessionId`, `deviceName` FROM `heart_rate_records`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `heart_rate_records`")
                db.execSQL("ALTER TABLE `heart_rate_records_new` RENAME TO `heart_rate_records`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_heart_rate_records_sourceRecordId_timestampMs` ON `heart_rate_records` (`sourceRecordId`, `timestampMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_heart_rate_records_timestampMs` ON `heart_rate_records` (`timestampMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_heart_rate_records_sessionId_recordType_beatsPerMinute` ON `heart_rate_records` (`sessionId`, `recordType`, `beatsPerMinute`)")

                // Rebuild hrv_records
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `hrv_records_new` (
                        `rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sourceRecordId` TEXT NOT NULL,
                        `timestampMs` INTEGER NOT NULL,
                        `rmssdMs` REAL NOT NULL,
                        `recordType` TEXT NOT NULL,
                        `sessionId` TEXT,
                        `deviceName` TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `hrv_records_new` (`sourceRecordId`, `timestampMs`, `rmssdMs`, `recordType`, `sessionId`, `deviceName`)
                    SELECT `id`, `timestampMs`, `rmssdMs`, `recordType`, `sessionId`, `deviceName` FROM `hrv_records`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `hrv_records`")
                db.execSQL("ALTER TABLE `hrv_records_new` RENAME TO `hrv_records`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_hrv_records_sourceRecordId_timestampMs` ON `hrv_records` (`sourceRecordId`, `timestampMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_hrv_records_timestampMs` ON `hrv_records` (`timestampMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_hrv_records_recordType_timestampMs` ON `hrv_records` (`recordType`, `timestampMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_hrv_records_sessionId` ON `hrv_records` (`sessionId`)")
            }
        }
```

Do not leave a blank line with trailing whitespace between the header comment and the first `db.execSQL(` call — ktlint's `no-trailing-spaces` rule will fail on it.

- [ ] **Step 2: Format and compile**

Run: `./gradlew ktlintFormat`
Expected: exits 0, no diff needed on `DatabaseMigrations.kt` beyond what Step 1 already wrote.

Run: `./gradlew :core:database:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run existing unit tests**

Run: `./gradlew :app:testDebugUnitTest --tests "app.readylytics.health.data.local.DatabaseMigrationTest"`
Expected: PASS (these tests only assert version constants and migration array ordering — they do not exercise the SQL itself; that's Task 2).

- [ ] **Step 4: Commit**

```bash
git add core/database/src/main/kotlin/app/readylytics/health/data/local/DatabaseMigrations.kt
git commit -m "fix: rebuild heart_rate_records/hrv_records tables in MIGRATION_6_7 (DB-001)"
```

---

### Task 2: Instrumented migration test proving MIGRATION_6_7 actually works

**Files:**
- Create: `core/database/src/androidTest/kotlin/app/readylytics/health/data/local/DatabaseMigration6To7Test.kt`

**Interfaces:**
- Consumes: `DatabaseMigrations.all` (public `Array<Migration>`, from Task 1), `HealthDatabase` (class, from `core/database/.../HealthDatabase.kt`).

`core/database/build.gradle.kts` already declares `androidTestImplementation(libs.room.testing)`, `libs.androidx.junit`, `libs.androidx.test.runner`, and `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` — no build file changes needed.

- [ ] **Step 1: Write the test against the CURRENT (broken) migration to prove it catches the bug**

First, temporarily revert Task 1's fix to confirm the test fails against the empty migration:

```bash
git stash push -- core/database/src/main/kotlin/app/readylytics/health/data/local/DatabaseMigrations.kt
```

Create `core/database/src/androidTest/kotlin/app/readylytics/health/data/local/DatabaseMigration6To7Test.kt`:

```kotlin
package app.readylytics.health.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigration6To7Test {
    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            HealthDatabase::class.java,
        )

    @Test
    fun migrate6To7_preservesRowsAndRebuildsPrimaryKey() {
        val dbName = "migration-6-7-test"

        helper.createDatabase(dbName, 6).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS `heart_rate_records` (`id` TEXT NOT NULL, `timestampMs` INTEGER NOT NULL, `beatsPerMinute` INTEGER NOT NULL, `recordType` TEXT NOT NULL, `sessionId` TEXT, `deviceName` TEXT, PRIMARY KEY(`id`))
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS `hrv_records` (`id` TEXT NOT NULL, `timestampMs` INTEGER NOT NULL, `rmssdMs` REAL NOT NULL, `recordType` TEXT NOT NULL, `sessionId` TEXT, `deviceName` TEXT, PRIMARY KEY(`id`))
                """.trimIndent(),
            )
            execSQL(
                "INSERT INTO heart_rate_records (id, timestampMs, beatsPerMinute, recordType, sessionId, deviceName) " +
                    "VALUES ('hc-hr-1', 1000, 62, 'SLEEP', 'session-1', 'Test Ring')",
            )
            execSQL(
                "INSERT INTO hrv_records (id, timestampMs, rmssdMs, recordType, sessionId, deviceName) " +
                    "VALUES ('hc-hrv-1', 1000, 45.2, 'SLEEP', 'session-1', 'Test Ring')",
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(dbName, 7, true, *DatabaseMigrations.all)

        migratedDb.query("SELECT sourceRecordId, timestampMs, beatsPerMinute, rowId FROM heart_rate_records").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("hc-hr-1", cursor.getString(cursor.getColumnIndexOrThrow("sourceRecordId")))
            assertEquals(1L, cursor.getLong(cursor.getColumnIndexOrThrow("rowId")))
        }

        migratedDb.query("SELECT sourceRecordId, rmssdMs FROM hrv_records").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("hc-hrv-1", cursor.getString(cursor.getColumnIndexOrThrow("sourceRecordId")))
        }
    }
}
```

- [ ] **Step 2: Run against the broken migration, confirm it fails**

Run: `./gradlew :core:database:connectedDebugAndroidTest --tests "app.readylytics.health.data.local.DatabaseMigration6To7Test"` (requires a connected emulator/device)
Expected: FAIL with `IllegalStateException: Migration didn't properly handle: heart_rate_records(...)` — proves the empty migration is broken and this test catches it.

- [ ] **Step 3: Restore Task 1's real migration**

```bash
git stash pop
```

- [ ] **Step 4: Run again, confirm it passes**

Run: `./gradlew :core:database:connectedDebugAndroidTest --tests "app.readylytics.health.data.local.DatabaseMigration6To7Test"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/database/src/androidTest/kotlin/app/readylytics/health/data/local/DatabaseMigration6To7Test.kt
git commit -m "test: add instrumented MigrationTestHelper coverage for MIGRATION_6_7 (DB-001)"
```

---

### Task 3: Restore the dropped `(recordType, timestampMs)` index on `heart_rate_records`

**Files:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/data/local/entity/HeartRateRecordEntity.kt:10-17`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/data/local/DatabaseMigrations.kt` (heart_rate_records index block, inside `MIGRATION_6_7`)
- Create: `core/database/src/androidTest/kotlin/app/readylytics/health/data/local/HealthDatabaseIndexTest.kt`
- Regenerate: `core/database/schemas/app.readylytics.health.data.local.HealthDatabase/7.json`

**Interfaces:**
- Consumes: `HealthDatabase` (class).
- Produces: `heart_rate_records` gains back index `index_heart_rate_records_recordType_timestampMs`, used by `HeartRateDao.getAvgSleepHrPerSession` (`WHERE recordType = 'SLEEP' AND timestampMs >= :fromMs`).

- [ ] **Step 1: Write the failing test**

Create `core/database/src/androidTest/kotlin/app/readylytics/health/data/local/HealthDatabaseIndexTest.kt`:

```kotlin
package app.readylytics.health.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthDatabaseIndexTest {
    private lateinit var db: HealthDatabase

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                HealthDatabase::class.java,
            ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun heartRateRecords_keepsRecordTypeTimestampIndexForSleepAggregation() {
        db.openHelper.readableDatabase
            .query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'heart_rate_records' " +
                    "AND name = 'index_heart_rate_records_recordType_timestampMs'",
            ).use { cursor ->
                assertEquals(1, cursor.count)
            }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:database:connectedDebugAndroidTest --tests "app.readylytics.health.data.local.HealthDatabaseIndexTest"`
Expected: FAIL — `cursor.count` is 0 (index doesn't exist on the current entity definition).

- [ ] **Step 3: Add the index back to the entity**

In `HeartRateRecordEntity.kt`, change the `@Entity` `indices` list from:

```kotlin
    indices = [
        Index(value = ["sourceRecordId", "timestampMs"], unique = true),
        Index(value = ["timestampMs"]),
        Index(value = ["sessionId", "recordType", "beatsPerMinute"]),
    ],
```

to:

```kotlin
    indices = [
        Index(value = ["sourceRecordId", "timestampMs"], unique = true),
        Index(value = ["timestampMs"]),
        Index(value = ["recordType", "timestampMs"]),
        Index(value = ["sessionId", "recordType", "beatsPerMinute"]),
    ],
```

- [ ] **Step 4: Add the matching `CREATE INDEX` to `MIGRATION_6_7`**

In `DatabaseMigrations.kt`, inside the "Rebuild heart_rate_records" section of `MIGRATION_6_7` (from Task 1), add one line after the `sessionId_recordType_beatsPerMinute` index line:

```kotlin
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_heart_rate_records_sessionId_recordType_beatsPerMinute` ON `heart_rate_records` (`sessionId`, `recordType`, `beatsPerMinute`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_heart_rate_records_recordType_timestampMs` ON `heart_rate_records` (`recordType`, `timestampMs`)")
```

- [ ] **Step 5: Regenerate the exported Room schema**

Run: `./gradlew :core:database:kspDebugKotlin`
Expected: BUILD SUCCESSFUL, and `core/database/schemas/app.readylytics.health.data.local.HealthDatabase/7.json` is rewritten in place — diff it to confirm the new index appears under `heart_rate_records.indices` and `database.identityHash` changed:

```bash
git diff --stat -- 'core/database/schemas/app.readylytics.health.data.local.HealthDatabase/7.json'
```

Expected: shows a non-empty diff for `7.json`.

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :core:database:connectedDebugAndroidTest --tests "app.readylytics.health.data.local.HealthDatabaseIndexTest"`
Expected: PASS.

- [ ] **Step 7: Re-run Task 2's migration test to confirm nothing regressed**

Run: `./gradlew :core:database:connectedDebugAndroidTest --tests "app.readylytics.health.data.local.DatabaseMigration6To7Test"`
Expected: PASS (the new index is created inside `MIGRATION_6_7` itself, so the migrated schema still matches `7.json`).

- [ ] **Step 8: Commit**

```bash
git add core/model/src/main/kotlin/app/readylytics/health/data/local/entity/HeartRateRecordEntity.kt \
        core/database/src/main/kotlin/app/readylytics/health/data/local/DatabaseMigrations.kt \
        core/database/src/androidTest/kotlin/app/readylytics/health/data/local/HealthDatabaseIndexTest.kt \
        "core/database/schemas/app.readylytics.health.data.local.HealthDatabase/7.json"
git commit -m "fix: restore recordType+timestampMs index on heart_rate_records (DB-001)"
```

---

### Task 4: Document `rowId`/`REPLACE` upsert semantics

**Files:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/data/local/entity/HeartRateRecordEntity.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/data/local/entity/HrvRecordEntity.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/data/local/dao/HeartRateDao.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/data/local/dao/HrvDao.kt`

No test — this is comment-only, no behavior change.

- [ ] **Step 1: Add a KDoc note above `rowId` in `HeartRateRecordEntity`**

In `HeartRateRecordEntity.kt`, change:

```kotlin
data class HeartRateRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0,
```

to:

```kotlin
data class HeartRateRecordEntity(
    /**
     * Not stable across re-ingestion: [HeartRateDao.upsertAll] uses
     * `@Insert(onConflict = REPLACE)`, keyed off the unique (sourceRecordId, timestampMs)
     * index, and SQLite REPLACE deletes the conflicting row before inserting the new one.
     * Never persist or compare rowId across sync passes.
     */
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0,
```

- [ ] **Step 2: Mirror the same note in `HrvRecordEntity`**

In `HrvRecordEntity.kt`, change:

```kotlin
data class HrvRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0,
```

to:

```kotlin
data class HrvRecordEntity(
    /**
     * Not stable across re-ingestion: [HrvDao.upsertAll] uses
     * `@Insert(onConflict = REPLACE)`, keyed off the unique (sourceRecordId, timestampMs)
     * index, and SQLite REPLACE deletes the conflicting row before inserting the new one.
     * Never persist or compare rowId across sync passes.
     */
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0,
```

- [ ] **Step 3: Add a matching one-line note above `upsertAll` in both DAOs**

In `HeartRateDao.kt`, change:

```kotlin
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<HeartRateRecordEntity>)
```

to:

```kotlin
    // REPLACE deletes+reinserts on (sourceRecordId, timestampMs) conflict — rowId changes
    // on every re-upsert of the same source record; see HeartRateRecordEntity.rowId.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<HeartRateRecordEntity>)
```

In `HrvDao.kt`, change:

```kotlin
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<HrvRecordEntity>)
```

to:

```kotlin
    // REPLACE deletes+reinserts on (sourceRecordId, timestampMs) conflict — rowId changes
    // on every re-upsert of the same source record; see HrvRecordEntity.rowId.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<HrvRecordEntity>)
```

- [ ] **Step 4: Format and compile**

Run: `./gradlew ktlintFormat`
Run: `./gradlew :core:model:compileDebugKotlin :core:database:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add core/model/src/main/kotlin/app/readylytics/health/data/local/entity/HeartRateRecordEntity.kt \
        core/model/src/main/kotlin/app/readylytics/health/data/local/entity/HrvRecordEntity.kt \
        core/model/src/main/kotlin/app/readylytics/health/data/local/dao/HeartRateDao.kt \
        core/model/src/main/kotlin/app/readylytics/health/data/local/dao/HrvDao.kt
git commit -m "docs: document rowId instability under REPLACE-based upsert (DB-001)"
```

---

### Task 5: Fix the broken `DATA_FLOW.md` migration paragraph

**Files:**
- Modify: `internal-docs/DATA_FLOW.md:252-264`

No test — documentation only.

- [ ] **Step 1: Add the v6→v7 migration to the registration sentence**

Change (line 252):

```markdown
`DatabaseMigrations` registers v1→v2, v2→v3, v3→v4, v4→v5, and v5→v6 migrations for existing
installs.
```

to:

```markdown
`DatabaseMigrations` registers v1→v2, v2→v3, v3→v4, v4→v5, v5→v6, and v6→v7 migrations for
existing installs.
```

- [ ] **Step 2: Rewrite the spliced-in v7 sentence as its own paragraph**

Change (lines 256-264):

```markdown
not change any other table or scoring formula. Version 6 (SCORE-001, HC-005, DB-002): adds a
few tables and indices. Version 7 (DB-001): updates primary keys for heart_rate_records and hrv_records.
nullable `workout_records.modelTrimp` column (the user-selected TRIMP model's value, lazily
backfilled by the next walk-forward recompute — see §2.3); adds the `step_records` table (13th
entity) holding raw per-record steps rows purely so a later Health Connect `DeletionChange` for
steps can resolve the deleted record's own date range (§1.2) — it is never read for scoring, daily
step totals still come from `StepCountFetcher`'s aggregate/device-filtered reads; and drops the
`daily_summaries` index on `dateMidnightMs`, redundant with that column already being the primary
key.
```

to:

```markdown
not change any other table or scoring formula. Version 6 (SCORE-001, HC-005, DB-002): adds a
nullable `workout_records.modelTrimp` column (the user-selected TRIMP model's value, lazily
backfilled by the next walk-forward recompute — see §2.3); adds the `step_records` table (13th
entity) holding raw per-record steps rows purely so a later Health Connect `DeletionChange` for
steps can resolve the deleted record's own date range (§1.2) — it is never read for scoring, daily
step totals still come from `StepCountFetcher`'s aggregate/device-filtered reads; and drops the
`daily_summaries` index on `dateMidnightMs`, redundant with that column already being the primary
key. Version 7 (DB-001) rebuilds `heart_rate_records` and `hrv_records` onto an autoincrement
`rowId` primary key: the previous `id` (the Health Connect record id) is renamed `sourceRecordId`
and is no longer unique on its own — a unique index on `(sourceRecordId, timestampMs)` replaces it,
because re-ingestion can otherwise see the same source id more than once within a resync window.
```

- [ ] **Step 3: Proofread the full paragraph**

Run: `sed -n '250,266p' internal-docs/DATA_FLOW.md`
Expected: reads as grammatically complete sentences describing v4 → v5 → v6 → v7 in order, no dangling fragments.

- [ ] **Step 4: Commit**

```bash
git add internal-docs/DATA_FLOW.md
git commit -m "docs: fix broken v6/v7 migration paragraph in DATA_FLOW.md (DB-001)"
```

---

### Task 6: Harden SEC-001 log redaction (stack traces + separator variants)

**Files:**
- Modify: `app/src/main/kotlin/app/readylytics/health/util/SecureFileLogSink.kt:75-104,305-326`
- Modify: `app/src/test/kotlin/app/readylytics/health/util/SecureFileLogSinkTest.kt`

**Interfaces:**
- Produces: `SecureFileLogSink.sanitizeLogMessage(String): String` (unchanged signature, broadened regex); `bufferLog` now also sanitizes the throwable's stack-trace text before appending it to the persisted log line.

- [ ] **Step 1: Write the failing tests**

Add to `SecureFileLogSinkTest.kt`, after the existing `testLogSanitization` test:

```kotlin
    @Test
    fun testLogSanitizationHandlesSeparatorVariants() {
        val original = "HR=120, HR:118, BPM 150"
        val sanitized = SecureFileLogSink.sanitizeLogMessage(original)

        assertFalse("Should redact HR=120", sanitized.contains("120"))
        assertFalse("Should redact HR:118", sanitized.contains("118"))
        assertFalse("Should redact BPM 150", sanitized.contains("150"))
    }

    @Test
    fun testStackTraceRedactsHealthMetrics() =
        runBlocking {
            val secureFileStore = FakeSecureFileStore()
            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    maxFileSize = 10000L,
                    maxBackups = 2,
                    encryptStreams = true,
                    coroutineContext = Dispatchers.Unconfined,
                    secureFileStore = secureFileStore,
                )

            val exception = RuntimeException("Invalid reading: HR is 245")
            sink.log(LogLevel.ERROR, "ErrorTag", "Validation failed", exception, LogContext("session-1"))

            val content = sink.readLogsDecrypted()
            assertFalse("Stack trace text should be redacted too", content.contains("245"))
        }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "app.readylytics.health.util.SecureFileLogSinkTest"`
Expected: `testLogSanitizationHandlesSeparatorVariants` FAILs (regex requires `is`/whitespace, not `=`/`:`), `testStackTraceRedactsHealthMetrics` FAILs (stack trace is never sanitized).

- [ ] **Step 3: Broaden the regex and sanitize the stack trace**

In `SecureFileLogSink.kt`, change `sanitizeLogMessage`:

```kotlin
            // Redact specific health metrics numbers
            sanitized =
                sanitized.replace(
                    Regex("(?i)\\b(HR|HRV|BP|BPM)\\s*(?:is\\s*)?\\d+(?:\\.\\d+)?(?:/\\d+)?"),
                    "$1 ***",
                )
```

to:

```kotlin
            // Redact specific health metrics numbers
            sanitized =
                sanitized.replace(
                    Regex("(?i)\\b(HR|HRV|BP|BPM)\\s*[:=]?\\s*(?:is\\s*)?\\d+(?:\\.\\d+)?(?:/\\d+)?"),
                    "$1 ***",
                )
```

Then change `bufferLog` to sanitize the stack-trace text:

```kotlin
    private fun bufferLog(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
        logContext: LogContext,
    ) {
        val timestamp = dateFormat.format(Date())
        val sessionId = logContext.sessionId ?: "none"
        val sanitizedMessage = sanitizeLogMessage(message)
        val sanitizedStackTrace = throwable?.let { sanitizeLogMessage(Log.getStackTraceString(it)) }
        val logLine =
            "$timestamp [$level] [$tag] [Session:$sessionId] $sanitizedMessage" +
                (sanitizedStackTrace?.let { "\n$it" } ?: "") + "\n"

        pendingLogs.add(logLine)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "app.readylytics.health.util.SecureFileLogSinkTest"`
Expected: PASS, all tests in the class including the pre-existing `testPlainTextLogsWrittenCorrectly` / `testPlainTextModeStillWorksWithoutSecureFileStore` (their exception message "Test Exception" has no digits, so it's unaffected by sanitization).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/app/readylytics/health/util/SecureFileLogSink.kt \
        app/src/test/kotlin/app/readylytics/health/util/SecureFileLogSinkTest.kt
git commit -m "fix: sanitize stack traces and broaden separator matching in log redaction (SEC-001)"
```

---

### Task 7: Drop the unused `summary` parameter from `resolveDashboardSleepSessionSummary`

**Files:**
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModel.kt:128-131,203-214`
- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModelTest.kt:162-185`

**Interfaces:**
- Produces: `resolveDashboardSleepSessionSummary(session: SleepSessionData?): SleepSessionSummary?` (drops the `summary: DailySummary?` parameter, which is dead since the PR 162 simplification removed the only branch that read it).

- [ ] **Step 1: Drop the parameter from the function**

In `DashboardViewModel.kt`, change:

```kotlin
        internal fun resolveDashboardSleepSessionSummary(
            summary: DailySummary?,
            session: SleepSessionData?,
        ): SleepSessionSummary? {
```

to:

```kotlin
        internal fun resolveDashboardSleepSessionSummary(
            session: SleepSessionData?,
        ): SleepSessionSummary? {
```

- [ ] **Step 2: Update the caller**

In the same file, change:

```kotlin
            val sessionSummary =
                resolveDashboardSleepSessionSummary(
                    summary = basicInputs.summary,
                    session = cardState.lastSleepSession,
                )
```

to:

```kotlin
            val sessionSummary =
                resolveDashboardSleepSessionSummary(
                    session = cardState.lastSleepSession,
                )
```

- [ ] **Step 3: Consolidate the two now-redundant tests and drop the unused import**

In `DashboardViewModelTest.kt`, remove the `import app.readylytics.health.domain.model.DailySummary` line (no longer used anywhere in the file), and replace both:

```kotlin
    @Test
    fun `dashboard session summary stays when aggregate matches single session actual sleep`() {
        val summary =
            viewModel.resolveDashboardSleepSessionSummary(
                summary = DailySummary(date = LocalDate.of(2026, 7, 9), sleepDurationMinutes = 480),
                session = sleepSession(durationMinutes = 510, awakeMinutes = 30),
            )

        assertEquals(0.9f, summary?.efficiency)
        assertEquals(0L, summary?.startTime)
        assertEquals(510 * 60_000L, summary?.endTime)
    }

    @Test
    fun `dashboard session summary falls back to session when aggregate duration no longer matches single session`() {
        val summary =
            viewModel.resolveDashboardSleepSessionSummary(
                summary = DailySummary(date = LocalDate.of(2026, 7, 9), sleepDurationMinutes = 540),
                session = sleepSession(durationMinutes = 510, awakeMinutes = 30),
            )

        assertEquals(0.9f, summary?.efficiency)
        assertNull(summary?.takeIf { it.endTime <= it.startTime })
    }
```

with a single test:

```kotlin
    @Test
    fun `dashboard session summary derives from session data`() {
        val summary =
            viewModel.resolveDashboardSleepSessionSummary(
                session = sleepSession(durationMinutes = 510, awakeMinutes = 30),
            )

        assertEquals(0.9f, summary?.efficiency)
        assertEquals(0L, summary?.startTime)
        assertEquals(510 * 60_000L, summary?.endTime)
    }
```

Check whether `assertNull` is still used elsewhere in the file (`grep -n assertNull feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModelTest.kt`); if that was its only use, remove the `import org.junit.Assert.assertNull` line too.

- [ ] **Step 4: Run tests**

Run: `./gradlew :feature:dashboard:testDebugUnitTest --tests "app.readylytics.health.feature.dashboard.DashboardViewModelTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModel.kt \
        feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModelTest.kt
git commit -m "refactor: drop unused summary param from resolveDashboardSleepSessionSummary"
```

---

### Task 8: Full repo verification

**Files:** none (verification only).

- [ ] **Step 1: Full pre-commit sequence**

Run: `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`
Expected: both succeed with no uncommitted diff from `ktlintFormat` and no test failures across all modules.

- [ ] **Step 2: Full instrumented suite (if an emulator/device is attached)**

Run: `./gradlew connectedDebugAndroidTest`
Expected: PASS, including the two new tests from Tasks 2 and 3. If no device is attached, note this explicitly rather than skipping silently — it must run in CI before merge.

- [ ] **Step 3: Release lint pass (repo-mandated final check)**

Run: `./gradlew lintRelease`
Expected: no new findings introduced by this plan's changes.

- [ ] **Step 4: Confirm PR 162 branch is ready**

Run: `git log --oneline -8` and `git status`
Expected: 7 new commits (Tasks 1-7) on top of the current `feature/phase5` HEAD, clean working tree.
