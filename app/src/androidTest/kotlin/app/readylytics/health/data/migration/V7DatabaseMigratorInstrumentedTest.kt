package app.readylytics.health.data.migration

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.core.database.data.security.AndroidKeystoreKeyProvider
import app.readylytics.health.core.database.data.security.SqlCipherKeyManager
import app.readylytics.health.core.model.domain.migration.V7MigrationPhase
import app.readylytics.health.core.model.domain.migration.V7MigrationResult
import app.readylytics.health.data.local.HealthDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class V7DatabaseMigratorInstrumentedTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            HealthDatabase::class.java,
        )

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val createdDatabases = mutableListOf<String>()

    @After
    fun cleanUp() {
        createdDatabases.forEach(context::deleteDatabase)
    }

    @Test
    fun v6MigrationResumesAfterFirstFullHeartRateBatch() =
        runBlocking {
            val fixture =
                createEncryptedFixture(
                    version = 6,
                    heartRateCount = BATCH_SIZE + 7,
                    hrvCount = 13,
                )
            val migrator = V7DatabaseMigrator(fixture.keyManager, fixture.file)

            expectCancellation {
                migrator.migrate { progress ->
                    if (progress.phase == V7MigrationPhase.COPY_HEART_RATE &&
                        progress.copiedRows == BATCH_SIZE.toLong()
                    ) {
                        throw CancellationException("interrupt first HR batch")
                    }
                }
            }

            fixture.keyManager.withWritableDatabase(fixture.file) { database ->
                assertEquals(6, pragmaUserVersion(database))
                assertEquals(BATCH_SIZE.toLong(), checkpointLong(database, "copiedHeartRateRows"))
                assertEquals(BATCH_SIZE.toLong(), queryLong(database, "SELECT COUNT(*) FROM heart_rate_records_v7"))
                assertEquals((BATCH_SIZE + 7).toLong(), queryLong(database, "SELECT COUNT(*) FROM heart_rate_records"))
            }

            assertEquals(V7MigrationResult.Complete, migrator.migrate {})
            assertSuccessfulV7(fixture, BATCH_SIZE + 7, 13)
        }

    @Test
    fun v5MigrationUsesSharedUpgradeAndPreservesWorkout() =
        runBlocking {
            val fixture = createEncryptedFixture(version = 5, heartRateCount = 3, hrvCount = 2)

            assertEquals(
                V7MigrationResult.Complete,
                V7DatabaseMigrator(fixture.keyManager, fixture.file).migrate {},
            )

            fixture.keyManager.withWritableDatabase(fixture.file) { database ->
                assertEquals(7, pragmaUserVersion(database))
                database
                    .rawQuery(
                        "SELECT trimp, modelTrimp FROM workout_records WHERE id = ?",
                        arrayOf("workout-v5"),
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(45.0, cursor.getDouble(0), 0.001)
                        assertTrue(cursor.isNull(1))
                    }
                assertEquals(
                    1L,
                    queryLong(
                        database,
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'step_records'",
                    ),
                )
            }
            assertSuccessfulV7(fixture, 3, 2)
        }

    @Test
    fun cancellationAtEachDurablePhaseKeepsLegacyTablesAndResumes() =
        runBlocking {
            INTERRUPTIBLE_PHASES.forEachIndexed { index, interruptedPhase ->
                val fixture =
                    createEncryptedFixture(
                        version = 6,
                        heartRateCount = 3,
                        hrvCount = 2,
                        suffix = "phase-$index",
                    )
                val migrator = V7DatabaseMigrator(fixture.keyManager, fixture.file)

                expectCancellation {
                    migrator.migrate { progress ->
                        if (progress.phase == interruptedPhase) {
                            throw CancellationException("interrupt $interruptedPhase")
                        }
                    }
                }

                fixture.keyManager.withWritableDatabase(fixture.file) { database ->
                    assertEquals(6, pragmaUserVersion(database))
                    assertTrue(tableExists(database, "heart_rate_records"))
                    assertTrue(tableExists(database, "hrv_records"))
                }

                assertEquals(V7MigrationResult.Complete, migrator.migrate {})
                assertSuccessfulV7(fixture, 3, 2)
            }
        }

    @Test
    fun normalizationRemovesOnlyExactTimestampSuffix() =
        runBlocking {
            val fixture = createEncryptedFixture(version = 6, heartRateCount = 0, hrvCount = 0)
            fixture.keyManager.withWritableDatabase(fixture.file) { database ->
                insertHeartRate(database, "plain-source", 1_000L)
                insertHeartRate(database, "source_2000", 2_000L)
                insertHeartRate(database, "source_003000", 3_000L)
                insertHeartRate(database, "source_4000_extra", 4_000L)
                insertHrv(database, "hrv-source_5000", 5_000L)
            }

            assertEquals(
                V7MigrationResult.Complete,
                V7DatabaseMigrator(fixture.keyManager, fixture.file).migrate {},
            )

            fixture.keyManager.withWritableDatabase(fixture.file) { database ->
                assertEquals(
                    listOf("plain-source", "source", "source_003000", "source_4000_extra"),
                    queryStrings(database, "SELECT sourceRecordId FROM heart_rate_records ORDER BY timestampMs"),
                )
                assertEquals(
                    listOf("hrv-source"),
                    queryStrings(database, "SELECT sourceRecordId FROM hrv_records ORDER BY timestampMs"),
                )
            }
        }

    @Test
    fun insufficientSpaceReturnsBeforeCreatingMigrationTables() =
        runBlocking {
            val fixture = createEncryptedFixture(version = 6, heartRateCount = 1, hrvCount = 1)
            val result =
                V7DatabaseMigrator(
                    sqlCipherKeyManager = fixture.keyManager,
                    dbFile = fixture.file,
                    availableBytes = { 0L },
                ).migrate {}

            assertTrue(result is V7MigrationResult.InsufficientSpace)
            fixture.keyManager.withWritableDatabase(fixture.file) { database ->
                assertEquals(6, pragmaUserVersion(database))
                assertFalse(tableExists(database, "readylytics_schema_migration"))
                assertFalse(tableExists(database, "heart_rate_records_v7"))
                assertFalse(tableExists(database, "hrv_records_v7"))
            }
        }

    @Test
    fun readinessFailureReturnsFailedInsteadOfEscaping() =
        runBlocking {
            val fixture =
                createEncryptedFixture(
                    version = 6,
                    heartRateCount = 1,
                    hrvCount = 1,
                    suffix = "unreadable-readiness",
                )
            File("${fixture.file.absolutePath}-wal").delete()
            File("${fixture.file.absolutePath}-shm").delete()
            fixture.file.writeBytes("not-a-sqlcipher-database".encodeToByteArray())

            val result =
                V7DatabaseMigrator(
                    sqlCipherKeyManager = fixture.keyManager,
                    dbFile = fixture.file,
                    availableBytes = { Long.MAX_VALUE },
                ).migrate {}

            assertTrue(result is V7MigrationResult.Failed)
        }

    @Test
    fun resumedMigrationDoesNotRepeatDiskPreflightAfterCheckpointExists() =
        runBlocking {
            val fixture =
                createEncryptedFixture(
                    version = 6,
                    heartRateCount = BATCH_SIZE + 1,
                    hrvCount = 1,
                    suffix = "low-space-resume",
                )

            expectCancellation {
                V7DatabaseMigrator(
                    sqlCipherKeyManager = fixture.keyManager,
                    dbFile = fixture.file,
                    availableBytes = { Long.MAX_VALUE },
                ).migrate { progress ->
                    if (progress.phase == V7MigrationPhase.COPY_HEART_RATE &&
                        progress.copiedRows == BATCH_SIZE.toLong()
                    ) {
                        throw CancellationException("interrupt after accepted preflight")
                    }
                }
            }

            val result =
                V7DatabaseMigrator(
                    sqlCipherKeyManager = fixture.keyManager,
                    dbFile = fixture.file,
                    availableBytes = { 0L },
                ).migrate {}

            assertEquals(V7MigrationResult.Complete, result)
            assertSuccessfulV7(fixture, BATCH_SIZE + 1, 1)
        }

    @Test
    fun validationFailureLeavesAuthoritativeV6TablesIntact() =
        runBlocking {
            val fixture = createEncryptedFixture(version = 6, heartRateCount = 0, hrvCount = 0)
            fixture.keyManager.withWritableDatabase(fixture.file) { database ->
                insertHeartRate(database, "duplicate-source_1000", 1_000L)
                insertHeartRate(database, "duplicate-source", 1_000L)
            }

            val result = V7DatabaseMigrator(fixture.keyManager, fixture.file).migrate {}

            assertTrue(result is V7MigrationResult.Failed)
            fixture.keyManager.withWritableDatabase(fixture.file) { database ->
                assertEquals(6, pragmaUserVersion(database))
                assertEquals(2L, queryLong(database, "SELECT COUNT(*) FROM heart_rate_records"))
                assertTrue(tableExists(database, "heart_rate_records"))
                assertTrue(tableExists(database, "hrv_records"))
            }
        }

    @Test
    fun resumeRevalidatesSourceMutationInsideAtomicCutover() =
        runBlocking {
            val fixture =
                createEncryptedFixture(
                    version = 6,
                    heartRateCount = 1,
                    hrvCount = 1,
                    suffix = "source-mutated-after-validation",
                )
            val migrator = V7DatabaseMigrator(fixture.keyManager, fixture.file)

            expectCancellation {
                migrator.migrate { progress ->
                    if (progress.phase == V7MigrationPhase.VALIDATE) {
                        throw CancellationException("interrupt after validation checkpoint")
                    }
                }
            }
            fixture.keyManager.withWritableDatabase(fixture.file) { database ->
                assertEquals(
                    V7MigrationPhase.SWAP.name,
                    queryStrings(
                        database,
                        "SELECT phase FROM readylytics_schema_migration WHERE migrationId = 'v7'",
                    ).single(),
                )
                insertHeartRate(database, "late-source_3000000", 3_000_000L)
            }

            val result = migrator.migrate {}

            assertTrue(result is V7MigrationResult.Failed)
            fixture.keyManager.withWritableDatabase(fixture.file) { database ->
                assertEquals(6, pragmaUserVersion(database))
                assertEquals(2L, queryLong(database, "SELECT COUNT(*) FROM heart_rate_records"))
                assertEquals(1L, queryLong(database, "SELECT COUNT(*) FROM heart_rate_records_v7"))
                assertTrue(tableExists(database, "readylytics_schema_migration"))
            }
        }

    private fun createEncryptedFixture(
        version: Int,
        heartRateCount: Int,
        hrvCount: Int,
        suffix: String = "fixture",
    ): Fixture {
        val name = "v7-migrator-$version-$suffix.db"
        createdDatabases += name
        context.deleteDatabase(name)
        helper.createDatabase(name, version).close()

        val file = context.getDatabasePath(name)
        val keyManager = SqlCipherKeyManager(context, AndroidKeystoreKeyProvider())
        keyManager.migrateIfNeeded(file)
        keyManager.withWritableDatabase(file) { database ->
            database.version = version
            database.rawQuery("PRAGMA journal_mode = WAL", emptyArray<String>()).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("wal", cursor.getString(0).lowercase())
            }
            database.beginTransaction()
            try {
                repeat(heartRateCount) { index ->
                    val timestamp = 1_000_000L + index
                    insertHeartRate(database, "hr-source-${index}_$timestamp", timestamp)
                }
                repeat(hrvCount) { index ->
                    val timestamp = 2_000_000L + index
                    insertHrv(database, "hrv-source-${index}_$timestamp", timestamp)
                }
                if (version == 5) {
                    database.execSQL(
                        "INSERT INTO workout_records " +
                            "(id, startTime, endTime, exerciseType, durationMinutes, zone1Minutes, zone2Minutes, " +
                            "zone3Minutes, zone4Minutes, zone5Minutes, trimp, avgHr) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf<Any>("workout-v5", 1_000L, 2_000L, "RUNNING", 30, 0f, 5f, 10f, 0f, 0f, 45f, 140f),
                    )
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
        return Fixture(name, file, keyManager)
    }

    private fun assertSuccessfulV7(
        fixture: Fixture,
        sourceHeartRateCount: Int,
        sourceHrvCount: Int,
    ) {
        fixture.keyManager.withWritableDatabase(fixture.file) { database ->
            assertEquals(7, pragmaUserVersion(database))
            assertEquals(sourceHeartRateCount.toLong(), queryLong(database, "SELECT COUNT(*) FROM heart_rate_records"))
            assertEquals(sourceHrvCount.toLong(), queryLong(database, "SELECT COUNT(*) FROM hrv_records"))
            assertEquals(
                0L,
                queryLong(
                    database,
                    "SELECT COUNT(*) FROM heart_rate_records WHERE sourceRecordId LIKE '%_' || timestampMs",
                ),
            )
            assertFalse(tableExists(database, "heart_rate_records_v7"))
            assertFalse(tableExists(database, "hrv_records_v7"))
            assertFalse(tableExists(database, "readylytics_schema_migration"))
            database.rawQuery("PRAGMA journal_mode", emptyArray<String>()).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("wal", cursor.getString(0).lowercase())
            }
            assertEquals(
                V7_DATABASE_IDENTITY_HASH,
                queryStrings(
                    database,
                    "SELECT identity_hash FROM room_master_table WHERE id = 42",
                ).single(),
            )
        }

        validateRoomSchema(fixture)
    }

    private fun validateRoomSchema(fixture: Fixture) {
        val plaintextName = "plaintext-${fixture.name}"
        createdDatabases += plaintextName
        val plaintextFile = context.getDatabasePath(plaintextName)
        fixture.keyManager.exportPlaintext(fixture.file, plaintextFile)
        android.database.sqlite.SQLiteDatabase
            .openDatabase(plaintextFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            .use { database ->
                database.execSQL("DROP TABLE room_master_table")
                database.version = 7
            }
        helper.runMigrationsAndValidate(plaintextName, 7, true).close()
    }

    private suspend fun expectCancellation(block: suspend () -> Unit) {
        var cancellation: CancellationException? = null
        try {
            block()
        } catch (e: CancellationException) {
            cancellation = e
        }
        assertEquals("migration callback must cancel", true, cancellation != null)
    }

    private fun insertHeartRate(
        database: SQLiteDatabase,
        id: String,
        timestampMs: Long,
    ) {
        database.execSQL(
            "INSERT INTO heart_rate_records " +
                "(id, timestampMs, beatsPerMinute, recordType, sessionId, deviceName) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, timestampMs, 62, "SLEEP", "session-1", "Test Ring"),
        )
    }

    private fun insertHrv(
        database: SQLiteDatabase,
        id: String,
        timestampMs: Long,
    ) {
        database.execSQL(
            "INSERT INTO hrv_records " +
                "(id, timestampMs, rmssdMs, recordType, sessionId, deviceName) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, timestampMs, 45.2f, "SLEEP", "session-1", "Test Ring"),
        )
    }

    private fun pragmaUserVersion(database: SQLiteDatabase): Int =
        database.rawQuery("PRAGMA user_version", emptyArray<String>()).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun checkpointLong(
        database: SQLiteDatabase,
        column: String,
    ): Long =
        database
            .rawQuery(
                "SELECT $column FROM readylytics_schema_migration WHERE migrationId = 'v7'",
                emptyArray<String>(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getLong(0)
            }

    private fun queryLong(
        database: SQLiteDatabase,
        sql: String,
    ): Long =
        database.rawQuery(sql, emptyArray<String>()).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun queryStrings(
        database: SQLiteDatabase,
        sql: String,
    ): List<String> =
        database.rawQuery(sql, emptyArray<String>()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private fun tableExists(
        database: SQLiteDatabase,
        table: String,
    ): Boolean =
        database
            .rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(table),
            ).use { cursor -> cursor.moveToFirst() }

    private data class Fixture(
        val name: String,
        val file: File,
        val keyManager: SqlCipherKeyManager,
    )

    private companion object {
        const val BATCH_SIZE = 10_000

        val INTERRUPTIBLE_PHASES =
            listOf(
                V7MigrationPhase.COPY_HEART_RATE,
                V7MigrationPhase.COPY_HRV,
                V7MigrationPhase.INDEX_HEART_RATE_TIMESTAMP,
                V7MigrationPhase.INDEX_HEART_RATE_SESSION,
                V7MigrationPhase.INDEX_HEART_RATE_TYPE_TIME,
                V7MigrationPhase.INDEX_HRV_TIMESTAMP,
                V7MigrationPhase.INDEX_HRV_TYPE_TIME,
                V7MigrationPhase.INDEX_HRV_SESSION,
                V7MigrationPhase.VALIDATE,
            )
    }
}
