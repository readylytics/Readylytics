package app.readylytics.health.data.migration

import android.content.Context
import android.os.SystemClock
import androidx.room.testing.MigrationTestHelper
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteStatement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

internal class DatabaseBenchmarkFixture(
    private val context: Context,
    private val helper: MigrationTestHelper,
) {
    private val createdNames = mutableSetOf<String>()

    fun createTemplate(
        version: Int,
        suffix: String,
    ): Fixture {
        val fixture = newFixture("v7-benchmark-$suffix.db")
        helper.createDatabase(fixture.name, version).close()
        fixture.driver.migrateIfNeeded()
        fixture.driver.withWritableDatabase { database ->
            database.version = version
            enableWal(database)
            insertHeartRateRows(database, version, 0, BenchmarkConstants.HEART_RATE_ROWS)
            insertHrvRows(database, version)
            checkpointWal(database)
        }
        return fixture
    }

    fun copyTemplate(
        source: Fixture,
        suffix: String,
    ): Fixture {
        val fixture = newFixture("v7-benchmark-$suffix.db")
        source.file.copyTo(fixture.file, overwrite = true)
        return fixture
    }

    fun measureFreshIngest(
        template: Fixture,
        suffix: String,
    ): Double {
        val fixture = copyTemplate(template, suffix)
        val elapsedNanos =
            fixture.driver.withWritableDatabase { database ->
                val startedAt = SystemClock.elapsedRealtimeNanos()
                insertHeartRateRows(
                    database,
                    fixture.version(),
                    BenchmarkConstants.HEART_RATE_ROWS,
                    BenchmarkConstants.INGEST_ROWS,
                )
                SystemClock.elapsedRealtimeNanos() - startedAt
            }
        delete(fixture)
        return BenchmarkConstants.INGEST_ROWS * BenchmarkConstants.NANOS_PER_SECOND.toDouble() / elapsedNanos
    }

    fun insertTimedBatch(database: SQLiteDatabase): Long {
        val version = pragmaUserVersion(database)
        val startedAt = SystemClock.elapsedRealtimeNanos()
        insertHeartRateRows(
            database,
            version,
            BenchmarkConstants.HEART_RATE_ROWS,
            BenchmarkConstants.INGEST_ROWS,
        )
        return SystemClock.elapsedRealtimeNanos() - startedAt
    }

    fun assertMigratedCounts(fixture: Fixture) {
        fixture.driver.withWritableDatabase { database ->
            assertEquals(7, pragmaUserVersion(database))
            assertEquals(
                BenchmarkConstants.HEART_RATE_ROWS.toLong(),
                queryLong(database, "SELECT COUNT(*) FROM heart_rate_records"),
            )
            assertEquals(
                BenchmarkConstants.HRV_ROWS.toLong(),
                queryLong(database, "SELECT COUNT(*) FROM hrv_records"),
            )
        }
    }

    fun checkpointHeartRateCopiedRows(fixture: Fixture): Long =
        fixture.driver.withWritableDatabase { database ->
            queryLong(
                database,
                "SELECT copiedHeartRateRows FROM readylytics_schema_migration WHERE migrationId = 'v7'",
            )
        }

    fun delete(fixture: Fixture) {
        context.deleteDatabase(fixture.name)
        createdNames -= fixture.name
    }

    fun cleanUp() {
        createdNames.forEach(context::deleteDatabase)
        createdNames.clear()
    }

    private fun newFixture(name: String): Fixture {
        context.deleteDatabase(name)
        createdNames += name
        val file = context.getDatabasePath(name)
        return Fixture(name, file, V7DatabaseBenchmarkDriver(context, file) { Long.MAX_VALUE })
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
            val timestampMs = BenchmarkConstants.START_TIMESTAMP_MS + recordIndex * BenchmarkConstants.HR_INTERVAL_MS
            val sourceRecordId = "source-${recordIndex / BenchmarkConstants.SAMPLES_PER_RECORD}"
            statement.bindString(1, if (version == 6) "${sourceRecordId}_$timestampMs" else sourceRecordId)
            statement.bindLong(2, timestampMs)
            statement.bindLong(3, (55 + recordIndex % 121).toLong())
            statement.bindString(4, if (recordIndex % 3 == 0) "SLEEP" else "OTHER")
            statement.bindString(5, "session-${recordIndex / BenchmarkConstants.SESSION_SAMPLE_COUNT}")
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
        insertInBatches(database, sql, 0, BenchmarkConstants.HRV_ROWS) { statement, recordIndex ->
            val timestampMs = BenchmarkConstants.START_TIMESTAMP_MS + recordIndex * BenchmarkConstants.HRV_INTERVAL_MS
            val sourceRecordId = "source-${recordIndex / BenchmarkConstants.HRV_SAMPLES_PER_RECORD}"
            statement.bindString(1, if (version == 6) "${sourceRecordId}_$timestampMs" else sourceRecordId)
            statement.bindLong(2, timestampMs)
            statement.bindDouble(3, 25.0 + recordIndex % 56)
            statement.bindString(4, "SLEEP")
            statement.bindString(5, "session-${recordIndex / BenchmarkConstants.HRV_SESSION_SAMPLE_COUNT}")
            statement.bindString(6, "DB-001 fixture")
        }
    }

    private inline fun insertInBatches(
        database: SQLiteDatabase,
        sql: String,
        startIndex: Int,
        count: Int,
        bind: (SQLiteStatement, Int) -> Unit,
    ) {
        database.compileStatement(sql).use { statement ->
            var batchStart = startIndex
            val endExclusive = startIndex + count
            while (batchStart < endExclusive) {
                val batchEnd = minOf(batchStart + BenchmarkConstants.INSERT_TRANSACTION_ROWS, endExclusive)
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
        }
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
}

internal data class Fixture(
    val name: String,
    val file: File,
    val driver: V7DatabaseBenchmarkDriver,
) {
    fun version(): Int = driver.withWritableDatabase(::pragmaUserVersion)
}

internal object BenchmarkConstants {
    const val HEART_RATE_ROWS = 1_000_000
    const val INGEST_ROWS = 5_000
    const val INSERT_TRANSACTION_ROWS = 5_000
    const val MIGRATION_COPY_BATCH_ROWS = 10_000
    const val SAMPLES_PER_RECORD = 60
    const val SESSION_SAMPLE_COUNT = 28_800
    const val HRV_SAMPLES_PER_RECORD = 12
    const val HRV_SESSION_SAMPLE_COUNT = 48
    const val HR_INTERVAL_MS = 1_000L
    const val HRV_INTERVAL_MS = 300_000L
    const val HRV_ROWS = HEART_RATE_ROWS / (HRV_INTERVAL_MS / HR_INTERVAL_MS).toInt()
    const val START_TIMESTAMP_MS = 1_735_689_600_000L
    const val NANOS_PER_SECOND = 1_000_000_000L
}

internal fun pragmaUserVersion(database: SQLiteDatabase): Int =
    database.rawQuery("PRAGMA user_version", emptyArray<String>()).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

internal fun queryLong(
    database: SQLiteDatabase,
    sql: String,
): Long =
    database.rawQuery(sql, emptyArray<String>()).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

internal fun databaseFootprintBytes(file: File): Long =
    file.length() + File("${file.absolutePath}-wal").length() + File("${file.absolutePath}-shm").length()
