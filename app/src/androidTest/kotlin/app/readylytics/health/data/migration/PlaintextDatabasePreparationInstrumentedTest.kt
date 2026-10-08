package app.readylytics.health.data.migration

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.core.database.data.local.DatabaseMigrations
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.migration.DatabaseReadinessGate
import app.readylytics.health.core.database.data.migration.ExistingDatabaseState
import app.readylytics.health.core.database.data.security.AndroidKeystoreKeyProvider
import app.readylytics.health.core.database.data.security.SqlCipherKeyManager
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.migration.V7MigrationPhase
import app.readylytics.health.core.model.domain.migration.V7MigrationResult
import dagger.Lazy
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlaintextDatabasePreparationInstrumentedTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HealthDatabase::class.java)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val token = UUID.randomUUID().toString()
    private val names = mutableListOf<String>()
    private val writers = mutableMapOf<File, SQLiteDatabase>()
    private val keyContext by lazy {
        object : ContextWrapper(context) {
            override fun getSharedPreferences(
                name: String,
                mode: Int,
            ) = super.getSharedPreferences("preparation-$token-$name", mode)
        }
    }
    private val keyManager by lazy { spyk(SqlCipherKeyManager(keyContext, AndroidKeystoreKeyProvider())) }

    @After
    fun cleanup() {
        writers.values.forEach(SQLiteDatabase::close)
        names.forEach { name ->
            context.deleteDatabase(name)
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                File("${context.getDatabasePath(name)}.cipher_tmp$suffix").delete()
            }
        }
        keyContext
            .getSharedPreferences(
                SqlCipherKeyManager.PREF_FILE_NAME,
                Context.MODE_PRIVATE,
            ).edit()
            .clear()
            .commit()
    }

    @Test
    fun plaintextVersionsPreserveRowsAndReachRoom23OffMain() =
        runBlocking {
            every { keyManager.migrateIfNeeded(any(), any()) } answers {
                assertTrue(Looper.myLooper() != Looper.getMainLooper())
                callOriginal()
            }
            for (version in listOf(5, 6, 7, 23)) {
                val source = createPlaintext(version, pendingWalRow = false)
                val preparation = runner(source)
                assertEquals(DatabaseReadiness.Checking, preparation.second.readiness.value)
                assertEquals(DatabaseReadiness.EncryptionRequired, preparation.second.inspect())
                assertEquals(V7MigrationResult.Complete, preparation.first.run {})
                assertFalse(hasPlaintextHeader(source))
                assertEquals(DatabaseReadiness.Ready, preparation.second.readiness.value)
                assertFixtureRows(source, encrypted = true)
                assertEquals(23, readUserVersionAfterRoomOpen(source))
                assertFixtureRows(source, encrypted = true)
            }
        }

    @Test
    fun cancellationBeforeCutoverKeepsCommittedWalAndFreshRunnerRetries() =
        runBlocking {
            val source = createPlaintext(7, pendingWalRow = true)
            assertTrue(File("$source-wal").length() > 0)
            expectCancellation {
                withContext(Dispatchers.IO) {
                    keyManager.migrateIfNeeded(source) {
                        assertTrue(Looper.myLooper() != Looper.getMainLooper())
                        assertNoExportHandle(source)
                        assertExportHasNoWriterLock(source)
                        throw CancellationException("before replacement")
                    }
                }
            }
            assertTrue(hasPlaintextHeader(source))
            assertFixtureRows(source, encrypted = false)
            // An original writer still works, proving cancellation did not replace its inode.
            writers.getValue(source).execSQL("UPDATE workout_records SET trimp = 45 WHERE id = 'workout'")
            writers.remove(source)!!.close()
            assertEquals(V7MigrationResult.Complete, runner(source).first.run {})
            assertFalse(hasPlaintextHeader(source))
            assertFixtureRows(source, encrypted = true)
            assertEquals(23, readUserVersionAfterRoomOpen(source))
        }

    @Test
    fun failedCutoverClosesTargetAndPreservesSource() =
        runBlocking {
            val source = createPlaintext(23, pendingWalRow = false)
            var failed = false
            try {
                withContext(Dispatchers.IO) {
                    keyManager.migrateIfNeeded(source) {
                        assertNoExportHandle(source)
                        assertExportHasNoWriterLock(source)
                        throw IllegalStateException("cutover failure")
                    }
                }
            } catch (_: SqlCipherKeyManager.MigrationException) {
                failed = true
            }
            assertTrue(failed)
            assertTrue(hasPlaintextHeader(source))
            assertFixtureRows(source, encrypted = false)
            assertFalse(File("$source.cipher_tmp").exists())
            assertEquals(V7MigrationResult.Complete, runner(source).first.run {})
            assertFixtureRows(source, encrypted = true)
        }

    @Test
    fun freshRunnerDiscardsProcessDeathTargetAndSidecars() =
        runBlocking {
            val source = createPlaintext(23, pendingWalRow = false)
            val bytesBefore = source.readBytes()
            // No throwing exporter/cleanup path runs: these are artifacts left by a dead process.
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                File("$source.cipher_tmp$suffix").writeText("incomplete native export")
            }
            assertTrue(bytesBefore.contentEquals(source.readBytes()))
            assertEquals(V7MigrationResult.Complete, runner(source).first.run {})
            assertFixtureRows(source, encrypted = true)
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                assertFalse(File("$source.cipher_tmp$suffix").exists())
            }
        }

    @Test
    fun encryptedLegacyCheckpointsSurviveAndResume() =
        runBlocking {
            for (version in listOf(5, 6)) {
                val source = createPlaintext(version, pendingWalRow = false)
                withContext(Dispatchers.IO) { keyManager.migrateIfNeeded(source) }
                expectCancellation {
                    runner(source).first.run { progress ->
                        if (progress.phase ==
                            V7MigrationPhase.COPY_HEART_RATE
                        ) {
                            throw CancellationException("checkpoint")
                        }
                    }
                }
                val checkpoint = checkpoint(source)
                assertEquals("COPY_HEART_RATE", checkpoint)
                assertEquals(DatabaseReadiness.MigrationRequired(6), runner(source).second.inspect())
                assertEquals(checkpoint, checkpoint(source))
                assertEquals(V7MigrationResult.Complete, runner(source).first.run {})
                assertFixtureRows(source, encrypted = true)
                assertEquals(23, readUserVersionAfterRoomOpen(source))
            }
        }

    @Test
    fun encryptedRoomManagedVersionsBypassExport() =
        runBlocking {
            for (version in listOf(7, 23)) {
                val source = createPlaintext(version, pendingWalRow = false)
                withContext(Dispatchers.IO) { keyManager.migrateIfNeeded(source) }
                every { keyManager.migrateIfNeeded(source, any()) } answers { error("must bypass export") }
                assertEquals(V7MigrationResult.Complete, runner(source).first.run {})
                assertFixtureRows(source, encrypted = true)
                assertEquals(23, readUserVersionAfterRoomOpen(source))
            }
        }

    @Test
    fun missingFileDoesNotCreateAFile() =
        runBlocking {
            val file = context.getDatabasePath("preparation-$token-missing.db")
            names += file.name
            assertEquals(V7MigrationResult.Complete, runner(file).first.run {})
            assertFalse(file.exists())
        }

    @Test
    fun insufficientSpaceDoesNotOpenTargetOrChangeOriginal() =
        runBlocking {
            val source = createPlaintext(23, pendingWalRow = false)
            val original = source.readBytes()
            assertTrue(runner(source, available = 0L).first.run {} is V7MigrationResult.InsufficientSpace)
            assertTrue(original.contentEquals(source.readBytes()))
            assertFalse(File("$source.cipher_tmp").exists())
        }

    @Test
    fun futurePlaintextVersionIsRejectedWithoutChangingOriginal() =
        runBlocking {
            val source = createPlaintext(23, pendingWalRow = false)
            SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 24 }
            val original = source.readBytes()
            assertTrue(runner(source).first.run {} is V7MigrationResult.Failed)
            assertTrue(original.contentEquals(source.readBytes()))
            assertFalse(File("$source.cipher_tmp").exists())
        }

    @Test
    fun corruptKeyBlocksEncryptedDatabaseWithoutReplacingIt() =
        runBlocking {
            val source = createPlaintext(23, pendingWalRow = false)
            withContext(Dispatchers.IO) { keyManager.migrateIfNeeded(source) }
            keyContext
                .getSharedPreferences(SqlCipherKeyManager.PREF_FILE_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(SqlCipherKeyManager.PREF_ENCRYPTED_KEY, "invalid")
                .commit()
            val original = source.readBytes()
            val preparation = runner(source)
            assertEquals(DatabaseReadiness.KeyCorrupted, preparation.second.inspect())
            assertTrue(preparation.first.run {} is V7MigrationResult.Failed)
            assertTrue(original.contentEquals(source.readBytes()))
        }

    private fun createPlaintext(
        version: Int,
        pendingWalRow: Boolean,
    ): File {
        val name = "preparation-$token-$version-${names.size}.db"
        names += name
        helper.createDatabase(name, version).close()
        val file = context.getDatabasePath(name)
        val database = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        database.enableWriteAheadLogging()
        if (version == 23) {
            database.execSQL(
                "INSERT INTO health_source_records (id, sourceRecordId, recordType, createdAtMs) " +
                    "VALUES (1, 'hr-source', 'HEART_RATE', 0), (2, 'wal-source', 'HEART_RATE', 0), " +
                    "(3, 'hrv-source', 'HRV', 0)",
            )
        }
        val id =
            when {
                version < 7 -> "id"
                version < 23 -> "sourceRecordId"
                else -> "sourceRecordRef"
            }
        val first: Any = if (version == 23) 1 else "hr-source"
        val wal: Any = if (version == 23) 2 else "wal-source"
        val hrv: Any = if (version == 23) 3 else "hrv-source"
        database.execSQL(
            "INSERT INTO heart_rate_records ($id, timestampMs, beatsPerMinute, recordType) VALUES (?, 1000, 62, 'SLEEP')",
            arrayOf(first),
        )
        database.execSQL(
            "INSERT INTO hrv_records ($id, timestampMs, rmssdMs, recordType) VALUES (?, 3000, 45.2, 'SLEEP')",
            arrayOf(hrv),
        )
        database.execSQL(
            "INSERT INTO workout_records (id, startTime, endTime, exerciseType, durationMinutes, " +
                "zone1Minutes, zone2Minutes, zone3Minutes, zone4Minutes, zone5Minutes, trimp, avgHr" +
                (if (version == 23) ", routeState" else "") + ") VALUES ('workout', 1000, 2000, 'RUNNING', " +
                "30, 0, 5, 10, 0, 0, 45, 140" + (if (version == 23) ", 'NOT_AVAILABLE'" else "") + ")",
        )
        database.execSQL(
            "INSERT INTO daily_summaries (dateMidnightMs, sleepScore, diag_isCalibrating, diag_stagesSuspicious, " +
                "diag_lateNadir, diag_hrvMissing, diag_timezoneJump) VALUES (0, 81.25, 0, 0, 0, 0, 0)",
        )
        database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { assertTrue(it.moveToFirst()) }
        database.execSQL(
            "INSERT INTO heart_rate_records ($id, timestampMs, beatsPerMinute, recordType) VALUES (?, 2000, 64, 'SLEEP')",
            arrayOf(wal),
        )
        if (pendingWalRow) {
            writers[file] = database
        } else {
            database.close()
        }
        return file
    }

    private fun assertFixtureRows(
        file: File,
        encrypted: Boolean,
    ) {
        if (encrypted) {
            keyManager.withWritableDatabase(file) { database ->
                assertRows(database.version) { database.rawQuery(it, emptyArray<String>()) }
            }
        } else {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                assertRows(database.version) { database.rawQuery(it, null) }
            }
        }
    }

    private fun assertRows(
        version: Int,
        query: (String) -> android.database.Cursor,
    ) {
        query("SELECT timestampMs, beatsPerMinute FROM heart_rate_records ORDER BY timestampMs").use(::assertHeartRows)
        query("SELECT rmssdMs FROM hrv_records").use { assertValue(it, 45.2) }
        query("SELECT trimp FROM workout_records WHERE id = 'workout'").use { assertValue(it, 45.0) }
        query("SELECT sleepScore FROM daily_summaries WHERE dateMidnightMs = 0").use { assertValue(it, 81.25) }
        val identities =
            when {
                version < 7 -> "SELECT id FROM heart_rate_records ORDER BY timestampMs"
                version < 21 -> "SELECT sourceRecordId FROM heart_rate_records ORDER BY timestampMs"
                else ->
                    "SELECT s.sourceRecordId FROM heart_rate_records h " +
                        "JOIN health_source_records s ON s.id = h.sourceRecordRef ORDER BY h.timestampMs"
            }
        query(identities).use { cursor ->
            assertEquals(2, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("hr-source", cursor.getString(0))
            assertTrue(cursor.moveToNext())
            assertEquals("wal-source", cursor.getString(0))
        }
    }

    private fun assertHeartRows(cursor: android.database.Cursor) {
        assertEquals(2, cursor.count)
        assertTrue(cursor.moveToFirst())
        assertEquals(1000L, cursor.getLong(0))
        assertEquals(62, cursor.getInt(1))
        assertTrue(cursor.moveToNext())
        assertEquals(2000L, cursor.getLong(0))
        assertEquals(64, cursor.getInt(1))
    }

    private fun assertValue(
        cursor: android.database.Cursor,
        expected: Double,
    ) {
        assertEquals(1, cursor.count)
        assertTrue(cursor.moveToFirst())
        assertEquals(expected, cursor.getDouble(0), 0.001)
    }

    private fun runner(
        file: File,
        available: Long = Long.MAX_VALUE,
    ): Pair<DatabasePreparationRunner, DatabaseReadinessGate> {
        val gate =
            DatabaseReadinessGate(file) { source ->
                keyManager.withWritableDatabase(source) { ExistingDatabaseState(it.version, false) }
            }
        return DatabasePreparationRunner(
            file,
            gate,
            keyManager,
            Lazy { V7DatabaseMigrator(keyManager, file) },
            Dispatchers.IO,
            {
                available
            },
            { source ->
                SQLiteDatabase
                    .openDatabase(
                        source.path,
                        null,
                        SQLiteDatabase.OPEN_READONLY,
                    ).use { it.version }
            },
        ) to
            gate
    }

    private fun hasPlaintextHeader(file: File): Boolean =
        file.inputStream().use {
            val header = ByteArray(16)
            it.read(header) == 16 && header.contentEquals("SQLite format 3\u0000".encodeToByteArray())
        }

    private fun readUserVersionAfterRoomOpen(file: File): Int {
        val database =
            Room
                .databaseBuilder(context, HealthDatabase::class.java, file.name)
                .openHelperFactory(keyManager.getOrCreateFactory())
                .addMigrations(*DatabaseMigrations.all)
                .build()
        return try {
            database.openHelper.writableDatabase.version
        } finally {
            database.close()
        }
    }

    private fun assertNoExportHandle(source: File) {
        val field = net.zetetic.database.sqlcipher.SQLiteDatabase::class.java.getDeclaredField("sActiveDatabases")
        field.isAccessible = true
        val databases = field.get(null) as Map<*, *>
        synchronized(databases) {
            val handles = databases.keys.filterIsInstance<net.zetetic.database.sqlcipher.SQLiteDatabase>()
            assertFalse(handles.any { it.path == "$source.cipher_tmp" && it.isOpen })
        }
    }

    private fun assertExportHasNoWriterLock(source: File) {
        keyManager.withWritableDatabase(File("$source.cipher_tmp")) { database ->
            database.rawExecSQL("BEGIN EXCLUSIVE")
            database.rawExecSQL("ROLLBACK")
        }
    }

    private fun checkpoint(source: File): String =
        keyManager.withWritableDatabase(source) { database ->
            database
                .rawQuery(
                    "SELECT phase FROM readylytics_schema_migration WHERE migrationId = 'v7'",
                    emptyArray<String>(),
                ).use {
                    assertTrue(it.moveToFirst())
                    it.getString(0)
                }
        }

    private suspend fun expectCancellation(block: suspend () -> Unit) {
        var cancelled = false
        try {
            block()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue("cancellation must escape", cancelled)
    }
}
