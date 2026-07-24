package app.readylytics.health.data.migration

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.security.SqlCipherKeyManager
import app.readylytics.health.domain.migration.V7MigrationPhase
import app.readylytics.health.domain.migration.V7MigrationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.max

data class DatabaseBenchmarkResult(
    val schemaVersion: Int,
    val databaseBytes: Long,
    val ingestRowsPerSecond: Double,
    val migrationDurationMs: Long,
    val peakRequiredBytes: Long,
)

private data class ResumeBenchmarkResult(
    val copiedRowsBeforeCancellation: Long,
    val resumeDurationMs: Long,
    val result: V7MigrationResult,
)

/**
 * Release-like, on-device DB-001 acceptance gate.
 *
 * This intentionally lives in instrumentation only. Do not reduce [HEART_RATE_ROWS] or weaken the
 * gate to accommodate CI: results are release evidence only when produced by the benchmark variant
 * on a compatible device.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class V7DatabaseMigrationBenchmark {
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
    fun oneMillionRowV7Gate() =
        runBlocking {
            val v6 = createFixture(version = 6, suffix = "v6")
            val fullMigration = copyFixture(v6, "full-migration")
            val resumeMigration = copyFixture(v6, "resume-migration")
            val v7 = createFixture(version = 7, suffix = "v7")

            val fullMigrationMeasurement = measureFullMigration(fullMigration)
            val resumeMeasurement = measureCancellationAndResume(resumeMigration)
            val v6Result = measureSchema(v6, migrationDurationMs = 0L, peakRequiredBytes = 0L)
            val v7Result =
                measureSchema(
                    fixture = v7,
                    migrationDurationMs = fullMigrationMeasurement.first,
                    peakRequiredBytes = fullMigrationMeasurement.second,
                )

            val throughputGain =
                (v7Result.ingestRowsPerSecond - v6Result.ingestRowsPerSecond) /
                    v6Result.ingestRowsPerSecond
            val sizeReduction =
                (v6Result.databaseBytes - v7Result.databaseBytes).toDouble() /
                    v6Result.databaseBytes

            report(v6Result, v7Result, throughputGain, sizeReduction, resumeMeasurement)
            assertTrue(
                "DB-001 gate failed: throughput=$throughputGain size=$sizeReduction",
                throughputGain >= 0.30 || sizeReduction >= 0.25,
            )
        }

    private fun createFixture(
        version: Int,
        suffix: String,
    ): Fixture {
        val name = "v7-benchmark-$suffix.db"
        registerDatabase(name)
        helper.createDatabase(name, version).close()
        val fixture = Fixture(name, context.getDatabasePath(name), SqlCipherKeyManager(context))
        fixture.keyManager.migrateIfNeeded(fixture.file)
        fixture.keyManager.withWritableDatabase(fixture.file) { database ->
            database.version = version
            enableWal(database)
            insertHeartRateRows(database, version, 0, HEART_RATE_ROWS)
            insertHrvRows(database, version)
            checkpointWal(database)
        }
        return fixture
    }

    private fun copyFixture(
        source: Fixture,
        suffix: String,
    ): Fixture {
        val name = "v7-benchmark-$suffix.db"
        registerDatabase(name)
        val destination = context.getDatabasePath(name)
        source.file.copyTo(destination, overwrite = true)
        return Fixture(name, destination, source.keyManager)
    }

    private suspend fun measureFullMigration(fixture: Fixture): Pair<Long, Long> {
        val migrator = V7DatabaseMigrator(fixture.keyManager, fixture.file, availableBytes = { Long.MAX_VALUE })
        var peakBytes = databaseFootprintBytes(fixture.file)
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val result =
            migrator.migrate {
                peakBytes = max(peakBytes, databaseFootprintBytes(fixture.file))
            }
        val durationMs = nanosToMillis(SystemClock.elapsedRealtimeNanos() - startedAt)
        peakBytes = max(peakBytes, databaseFootprintBytes(fixture.file))
        assertEquals(V7MigrationResult.Complete, result)
        assertMigratedCounts(fixture)
        return durationMs to peakBytes
    }

    private suspend fun measureCancellationAndResume(fixture: Fixture): ResumeBenchmarkResult {
        val migrator = V7DatabaseMigrator(fixture.keyManager, fixture.file, availableBytes = { Long.MAX_VALUE })
        var cancelled = false
        try {
            migrator.migrate { progress ->
                if (progress.phase == V7MigrationPhase.COPY_HEART_RATE &&
                    progress.copiedRows == MIGRATION_COPY_BATCH_ROWS.toLong()
                ) {
                    throw CancellationException("DB-001 cancel after first copy batch")
                }
            }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue("Migration must be cancelled after a durable copy batch", cancelled)

        val copiedRows =
            fixture.keyManager.withWritableDatabase(fixture.file) { database ->
                queryLong(
                    database,
                    "SELECT copiedHeartRateRows FROM readylytics_schema_migration WHERE migrationId = 'v7'",
                )
            }
        assertEquals(MIGRATION_COPY_BATCH_ROWS.toLong(), copiedRows)

        val startedAt = SystemClock.elapsedRealtimeNanos()
        val result = migrator.migrate {}
        val resumeDurationMs = nanosToMillis(SystemClock.elapsedRealtimeNanos() - startedAt)
        assertEquals(V7MigrationResult.Complete, result)
        assertMigratedCounts(fixture)
        return ResumeBenchmarkResult(copiedRows, resumeDurationMs, result)
    }

    private fun measureSchema(
        fixture: Fixture,
        migrationDurationMs: Long,
        peakRequiredBytes: Long,
    ): DatabaseBenchmarkResult {
        val version =
            fixture.keyManager.withWritableDatabase(fixture.file) { database ->
                pragmaUserVersion(database)
            }
        var elapsedNanos = 0L
        fixture.keyManager.withWritableDatabase(fixture.file) { database ->
            val startedAt = SystemClock.elapsedRealtimeNanos()
            insertHeartRateRows(database, version, HEART_RATE_ROWS, INGEST_ROWS)
            elapsedNanos = SystemClock.elapsedRealtimeNanos() - startedAt
            checkpointWal(database)
        }
        val rowsPerSecond = INGEST_ROWS * NANOS_PER_SECOND.toDouble() / elapsedNanos
        return DatabaseBenchmarkResult(
            schemaVersion = version,
            databaseBytes = fixture.file.length(),
            ingestRowsPerSecond = rowsPerSecond,
            migrationDurationMs = migrationDurationMs,
            peakRequiredBytes = peakRequiredBytes,
        )
    }

    private fun insertHeartRateRows(
        database: SQLiteDatabase,
        version: Int,
        startIndex: Int,
        count: Int,
    ) {
        val sql =
            if (version == 6) {
                "INSERT INTO heart_rate_records " +
                    "(id, timestampMs, beatsPerMinute, recordType, sessionId, deviceName) VALUES (?, ?, ?, ?, ?, ?)"
            } else {
                "INSERT INTO heart_rate_records " +
                    "(sourceRecordId, timestampMs, beatsPerMinute, recordType, sessionId, deviceName) " +
                    "VALUES (?, ?, ?, ?, ?, ?)"
            }
        insertInBatches(database, sql, startIndex, count) { statement, recordIndex ->
            val timestampMs = START_TIMESTAMP_MS + recordIndex * HR_SAMPLE_INTERVAL_MS
            val sourceRecordId = "source-${recordIndex / SAMPLES_PER_RECORD}"
            statement.bindString(1, if (version == 6) "${sourceRecordId}_$timestampMs" else sourceRecordId)
            statement.bindLong(2, timestampMs)
            statement.bindLong(3, (55 + recordIndex % 121).toLong())
            statement.bindString(4, if (recordIndex % 3 == 0) "SLEEP" else "OTHER")
            statement.bindString(5, "session-${recordIndex / SESSION_SAMPLE_COUNT}")
            statement.bindString(6, "DB-001 fixture")
        }
    }

    private fun insertHrvRows(
        database: SQLiteDatabase,
        version: Int,
    ) {
        val sql =
            if (version == 6) {
                "INSERT INTO hrv_records " +
                    "(id, timestampMs, rmssdMs, recordType, sessionId, deviceName) VALUES (?, ?, ?, ?, ?, ?)"
            } else {
                "INSERT INTO hrv_records " +
                    "(sourceRecordId, timestampMs, rmssdMs, recordType, sessionId, deviceName) VALUES (?, ?, ?, ?, ?, ?)"
            }
        insertInBatches(database, sql, 0, HRV_ROWS) { statement, recordIndex ->
            val timestampMs = START_TIMESTAMP_MS + recordIndex * HRV_SAMPLE_INTERVAL_MS
            val sourceRecordId = "source-${recordIndex / HRV_SAMPLES_PER_RECORD}"
            statement.bindString(1, if (version == 6) "${sourceRecordId}_$timestampMs" else sourceRecordId)
            statement.bindLong(2, timestampMs)
            statement.bindDouble(3, 25.0 + recordIndex % 56)
            statement.bindString(4, "SLEEP")
            statement.bindString(5, "session-${recordIndex / HRV_SESSION_SAMPLE_COUNT}")
            statement.bindString(6, "DB-001 fixture")
        }
    }

    private inline fun insertInBatches(
        database: SQLiteDatabase,
        sql: String,
        startIndex: Int,
        count: Int,
        bind: (net.zetetic.database.sqlcipher.SQLiteStatement, Int) -> Unit,
    ) {
        val statement = database.compileStatement(sql)
        var batchStart = startIndex
        val endExclusive = startIndex + count
        while (batchStart < endExclusive) {
            val batchEnd = minOf(batchStart + INSERT_TRANSACTION_ROWS, endExclusive)
            database.beginTransaction()
            try {
                for (recordIndex in batchStart until batchEnd) {
                    statement.clearBindings()
                    bind(statement, recordIndex)
                    statement.executeInsert()
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            batchStart = batchEnd
        }
        statement.close()
    }

    private fun assertMigratedCounts(fixture: Fixture) {
        fixture.keyManager.withWritableDatabase(fixture.file) { database ->
            assertEquals(7, pragmaUserVersion(database))
            assertEquals(HEART_RATE_ROWS.toLong(), queryLong(database, "SELECT COUNT(*) FROM heart_rate_records"))
            assertEquals(HRV_ROWS.toLong(), queryLong(database, "SELECT COUNT(*) FROM hrv_records"))
        }
    }

    private fun report(
        v6: DatabaseBenchmarkResult,
        v7: DatabaseBenchmarkResult,
        throughputGain: Double,
        sizeReduction: Double,
        resume: ResumeBenchmarkResult,
    ) {
        val report =
            "DB-001 device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                "api=${android.os.Build.VERSION.SDK_INT}; v6=$v6; v7=$v7; " +
                "throughputGain=$throughputGain; sizeReduction=$sizeReduction; resume=$resume"
        InstrumentationRegistry.getInstrumentation().addResults(
            Bundle().apply { putString("DB-001 benchmark", report) },
        )
        println(report)
    }

    private fun registerDatabase(name: String) {
        createdDatabases += name
        context.deleteDatabase(name)
    }

    private fun enableWal(database: SQLiteDatabase) {
        database.rawQuery("PRAGMA journal_mode = WAL", emptyArray<String>()).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("wal", cursor.getString(0).lowercase())
        }
    }

    private fun checkpointWal(database: SQLiteDatabase) {
        database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray<String>()).use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
    }

    private fun pragmaUserVersion(database: SQLiteDatabase): Int =
        database.rawQuery("PRAGMA user_version", emptyArray<String>()).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun queryLong(
        database: SQLiteDatabase,
        sql: String,
    ): Long =
        database.rawQuery(sql, emptyArray<String>()).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun databaseFootprintBytes(file: File): Long =
        file.length() + File("${file.absolutePath}-wal").length() + File("${file.absolutePath}-shm").length()

    private fun nanosToMillis(nanos: Long): Long = nanos / 1_000_000L

    private data class Fixture(
        val name: String,
        val file: File,
        val keyManager: SqlCipherKeyManager,
    )

    private companion object {
        const val HEART_RATE_ROWS = 1_000_000
        const val INGEST_ROWS = 5_000
        const val INSERT_TRANSACTION_ROWS = 5_000
        const val MIGRATION_COPY_BATCH_ROWS = 10_000
        const val SAMPLES_PER_RECORD = 60
        const val SESSION_SAMPLE_COUNT = 28_800
        const val HRV_SAMPLES_PER_RECORD = 12
        const val HRV_SESSION_SAMPLE_COUNT = 48
        const val HR_SAMPLE_INTERVAL_MS = 1_000L
        const val HRV_SAMPLE_INTERVAL_MS = 300_000L
        const val HRV_ROWS = HEART_RATE_ROWS / (HRV_SAMPLE_INTERVAL_MS / HR_SAMPLE_INTERVAL_MS).toInt()
        const val START_TIMESTAMP_MS = 1_735_689_600_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
