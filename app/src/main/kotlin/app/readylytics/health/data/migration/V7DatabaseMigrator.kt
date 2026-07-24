package app.readylytics.health.data.migration

import android.content.Context
import android.os.StatFs
import app.readylytics.health.data.local.DatabaseUpgradeSql
import app.readylytics.health.data.security.SqlCipherKeyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class V7DatabaseMigrator
    internal constructor(
        private val sqlCipherKeyManager: SqlCipherKeyManager,
        private val dbFile: File,
        private val availableBytes: (File) -> Long = ::statAvailableBytes,
    ) {
        @Inject
        constructor(
            @ApplicationContext context: Context,
            sqlCipherKeyManager: SqlCipherKeyManager,
        ) : this(
            sqlCipherKeyManager = sqlCipherKeyManager,
            dbFile = context.getDatabasePath(DATABASE_NAME),
        )

        suspend fun migrate(onProgress: suspend (DatabaseMigrationProgress) -> Unit): V7MigrationResult {
            val space = calculateSpace()
            if (space.availableBytes < space.requiredBytes) {
                return V7MigrationResult.InsufficientSpace(
                    requiredBytes = space.requiredBytes,
                    availableBytes = space.availableBytes,
                )
            }
            onProgress(DatabaseMigrationProgress(V7MigrationPhase.PREFLIGHT, 0L, 0L))

            return try {
                when (val version = readUserVersion()) {
                    CURRENT_VERSION -> return V7MigrationResult.Complete
                    5 -> {
                        upgradeV5ToV6()
                        onProgress(DatabaseMigrationProgress(V7MigrationPhase.UPGRADE_5_TO_6, 0L, 0L))
                    }
                    6 -> Unit
                    else -> return V7MigrationResult.Failed("Unsupported database version: $version")
                }

                createOrResumeMigration()
                onProgress(DatabaseMigrationProgress(V7MigrationPhase.CREATE_SHADOW_TABLES, 0L, 0L))

                runStateMachine(onProgress)
                V7MigrationResult.Complete
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                V7MigrationResult.Failed(e.message ?: "Database migration failed")
            }
        }

        private suspend fun runStateMachine(onProgress: suspend (DatabaseMigrationProgress) -> Unit) {
            while (true) {
                currentCoroutineContext().ensureActive()
                val activePhase = readCheckpoint().phase
                when (activePhase) {
                    V7MigrationPhase.COPY_HEART_RATE ->
                        copyBatch(
                            table = HEART_RATE_TABLE,
                            targetTable = HEART_RATE_V7_TABLE,
                            lastIdColumn = "lastHeartRateId",
                            copiedColumn = "copiedHeartRateRows",
                            nextPhase = V7MigrationPhase.COPY_HRV,
                            insertSql = COPY_HEART_RATE_SQL,
                        )

                    V7MigrationPhase.COPY_HRV ->
                        copyBatch(
                            table = HRV_TABLE,
                            targetTable = HRV_V7_TABLE,
                            lastIdColumn = "lastHrvId",
                            copiedColumn = "copiedHrvRows",
                            nextPhase = V7MigrationPhase.INDEX_HEART_RATE_TIMESTAMP,
                            insertSql = COPY_HRV_SQL,
                        )

                    V7MigrationPhase.INDEX_HEART_RATE_TIMESTAMP ->
                        createIndex(
                            CREATE_HR_TIMESTAMP_INDEX,
                            V7MigrationPhase.INDEX_HEART_RATE_SESSION,
                        )

                    V7MigrationPhase.INDEX_HEART_RATE_SESSION ->
                        createIndex(
                            CREATE_HR_SESSION_INDEX,
                            V7MigrationPhase.INDEX_HEART_RATE_TYPE_TIME,
                        )

                    V7MigrationPhase.INDEX_HEART_RATE_TYPE_TIME ->
                        createIndex(
                            CREATE_HR_TYPE_TIME_INDEX,
                            V7MigrationPhase.INDEX_HRV_TIMESTAMP,
                        )

                    V7MigrationPhase.INDEX_HRV_TIMESTAMP ->
                        createIndex(
                            CREATE_HRV_TIMESTAMP_INDEX,
                            V7MigrationPhase.INDEX_HRV_TYPE_TIME,
                        )

                    V7MigrationPhase.INDEX_HRV_TYPE_TIME ->
                        createIndex(
                            CREATE_HRV_TYPE_TIME_INDEX,
                            V7MigrationPhase.INDEX_HRV_SESSION,
                        )

                    V7MigrationPhase.INDEX_HRV_SESSION ->
                        createIndex(
                            CREATE_HRV_SESSION_INDEX,
                            V7MigrationPhase.VALIDATE,
                        )

                    V7MigrationPhase.VALIDATE -> validateAndCheckpoint()
                    V7MigrationPhase.SWAP -> {
                        cutOver()
                        onProgress(DatabaseMigrationProgress(V7MigrationPhase.SWAP, 0L, 0L))
                        onProgress(DatabaseMigrationProgress(V7MigrationPhase.COMPLETE, 0L, 0L))
                        return
                    }

                    else -> error("Invalid durable v7 migration phase")
                }

                val checkpoint = readCheckpoint()
                onProgress(checkpoint.toProgress(activePhase))
                currentCoroutineContext().ensureActive()
                yield()
            }
        }

        private fun calculateSpace(): Space {
            val walFile = File("${dbFile.absolutePath}-wal")
            val sourceBytes = dbFile.length() + walFile.length().coerceAtLeast(0L)
            val requiredBytes = sourceBytes + sourceBytes / 4L + SPACE_RESERVE_BYTES
            return Space(requiredBytes, availableBytes(dbFile))
        }

        private fun readUserVersion(): Int =
            withDatabase { database ->
                database.rawQuery("PRAGMA user_version", emptyArray<String>()).use { cursor ->
                    check(cursor.moveToFirst()) { "Database has no user_version" }
                    cursor.getInt(0)
                }
            }

        private fun upgradeV5ToV6() {
            withDatabase { database ->
                database.transaction {
                    DatabaseUpgradeSql.V5_TO_V6.forEach(database::execSQL)
                    database.execSQL("PRAGMA user_version = 6")
                }
            }
        }

        private fun createOrResumeMigration() {
            withDatabase { database ->
                database.transaction {
                    database.execSQL(CREATE_METADATA_TABLE)
                    database.execSQL(CREATE_HEART_RATE_V7_TABLE)
                    database.execSQL(CREATE_HR_UNIQUE_INDEX)
                    database.execSQL(CREATE_HRV_V7_TABLE)
                    database.execSQL(CREATE_HRV_UNIQUE_INDEX)
                    database.execSQL(
                        """
                        INSERT OR IGNORE INTO $METADATA_TABLE (
                            migrationId, phase, lastHeartRateId, lastHrvId,
                            copiedHeartRateRows, copiedHrvRows, totalHeartRateRows, totalHrvRows
                        )
                        SELECT ?, ?, NULL, NULL, 0, 0,
                            (SELECT COUNT(*) FROM $HEART_RATE_TABLE),
                            (SELECT COUNT(*) FROM $HRV_TABLE)
                        """.trimIndent(),
                        arrayOf(MIGRATION_ID, V7MigrationPhase.COPY_HEART_RATE.name),
                    )
                }
                readCheckpoint(database)
            }
        }

        private fun copyBatch(
            table: String,
            targetTable: String,
            lastIdColumn: String,
            copiedColumn: String,
            nextPhase: V7MigrationPhase,
            insertSql: String,
        ) {
            withDatabase { database ->
                val before = readCheckpoint(database)
                val lastId = before.lastId(lastIdColumn).orEmpty()
                database.transaction {
                    database.execSQL(insertSql, arrayOf(lastId))
                    val insertedRows = database.queryLong("SELECT changes()")
                    val batchLastId =
                        database
                            .rawQuery(
                                """
                                SELECT MAX(id)
                                FROM (
                                    SELECT id
                                    FROM $table
                                    WHERE id > ?
                                    ORDER BY id
                                    LIMIT $BATCH_SIZE
                                )
                                """.trimIndent(),
                                arrayOf(lastId),
                            ).use { cursor ->
                                check(cursor.moveToFirst())
                                if (cursor.isNull(0)) null else cursor.getString(0)
                            }
                    if (batchLastId == null) {
                        database.execSQL(
                            "UPDATE $METADATA_TABLE SET phase = ? WHERE migrationId = ?",
                            arrayOf(nextPhase.name, MIGRATION_ID),
                        )
                    } else {
                        database.execSQL(
                            """
                            UPDATE $METADATA_TABLE
                            SET $lastIdColumn = ?, $copiedColumn = $copiedColumn + ?
                            WHERE migrationId = ?
                            """.trimIndent(),
                            arrayOf<Any>(batchLastId, insertedRows, MIGRATION_ID),
                        )
                    }
                }
                val after = readCheckpoint(database)
                check(database.queryLong("SELECT COUNT(*) FROM $targetTable") == after.copied(copiedColumn)) {
                    "Migration checkpoint count does not match $targetTable"
                }
            }
        }

        private fun createIndex(
            sql: String,
            nextPhase: V7MigrationPhase,
        ) {
            withDatabase { database ->
                database.transaction {
                    database.execSQL(sql)
                    database.execSQL(
                        "UPDATE $METADATA_TABLE SET phase = ? WHERE migrationId = ?",
                        arrayOf(nextPhase.name, MIGRATION_ID),
                    )
                }
                readCheckpoint(database)
            }
        }

        private fun validateAndCheckpoint() {
            withDatabase { database ->
                database.transaction {
                    val checkpoint = readCheckpoint(database)
                    val sourceHeartRateRows = database.queryLong("SELECT COUNT(*) FROM $HEART_RATE_TABLE")
                    val sourceHrvRows = database.queryLong("SELECT COUNT(*) FROM $HRV_TABLE")
                    val targetHeartRateRows = database.queryLong("SELECT COUNT(*) FROM $HEART_RATE_V7_TABLE")
                    val targetHrvRows = database.queryLong("SELECT COUNT(*) FROM $HRV_V7_TABLE")
                    check(sourceHeartRateRows == checkpoint.totalHeartRateRows)
                    check(sourceHrvRows == checkpoint.totalHrvRows)
                    check(sourceHeartRateRows == targetHeartRateRows) {
                        "Heart-rate migration count mismatch: source=$sourceHeartRateRows target=$targetHeartRateRows"
                    }
                    check(sourceHrvRows == targetHrvRows) {
                        "HRV migration count mismatch: source=$sourceHrvRows target=$targetHrvRows"
                    }
                    check(duplicateGroupCount(database, HEART_RATE_V7_TABLE) == 0L) {
                        "Duplicate heart-rate source/time groups"
                    }
                    check(duplicateGroupCount(database, HRV_V7_TABLE) == 0L) {
                        "Duplicate HRV source/time groups"
                    }
                    database.execSQL(
                        "UPDATE $METADATA_TABLE SET phase = ? WHERE migrationId = ?",
                        arrayOf(V7MigrationPhase.SWAP.name, MIGRATION_ID),
                    )
                }
                readCheckpoint(database)
            }
        }

        private fun cutOver() {
            withDatabase { database ->
                database.beginTransactionNonExclusive()
                try {
                    database.execSQL("DROP TABLE $HEART_RATE_TABLE")
                    database.execSQL("ALTER TABLE $HEART_RATE_V7_TABLE RENAME TO $HEART_RATE_TABLE")
                    database.execSQL("DROP TABLE $HRV_TABLE")
                    database.execSQL("ALTER TABLE $HRV_V7_TABLE RENAME TO $HRV_TABLE")
                    database.execSQL("DROP TABLE $METADATA_TABLE")
                    database.execSQL(
                        "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                        arrayOf(V7_IDENTITY_HASH),
                    )
                    database.execSQL("PRAGMA user_version = 7")
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        private fun readCheckpoint(): Checkpoint = withDatabase(::readCheckpoint)

        private fun readCheckpoint(database: SQLiteDatabase): Checkpoint =
            database
                .rawQuery(
                    """
                    SELECT phase, lastHeartRateId, lastHrvId,
                        copiedHeartRateRows, copiedHrvRows, totalHeartRateRows, totalHrvRows
                    FROM $METADATA_TABLE
                    WHERE migrationId = ?
                    """.trimIndent(),
                    arrayOf(MIGRATION_ID),
                ).use { cursor ->
                    check(cursor.moveToFirst()) { "Missing v7 migration checkpoint" }
                    Checkpoint(
                        phase = V7MigrationPhase.valueOf(cursor.getString(0)),
                        lastHeartRateId = cursor.nullableString(1),
                        lastHrvId = cursor.nullableString(2),
                        copiedHeartRateRows = cursor.getLong(3),
                        copiedHrvRows = cursor.getLong(4),
                        totalHeartRateRows = cursor.getLong(5),
                        totalHrvRows = cursor.getLong(6),
                    )
                }

        private fun duplicateGroupCount(
            database: SQLiteDatabase,
            table: String,
        ): Long =
            database.queryLong(
                """
                SELECT COUNT(*)
                FROM (
                    SELECT sourceRecordId, timestampMs
                    FROM $table
                    GROUP BY sourceRecordId, timestampMs
                    HAVING COUNT(*) > 1
                )
                """.trimIndent(),
            )

        private fun <T> withDatabase(block: (SQLiteDatabase) -> T): T =
            sqlCipherKeyManager.withWritableDatabase(dbFile, block)

        private data class Space(
            val requiredBytes: Long,
            val availableBytes: Long,
        )

        private data class Checkpoint(
            val phase: V7MigrationPhase,
            val lastHeartRateId: String?,
            val lastHrvId: String?,
            val copiedHeartRateRows: Long,
            val copiedHrvRows: Long,
            val totalHeartRateRows: Long,
            val totalHrvRows: Long,
        ) {
            fun lastId(column: String): String? =
                when (column) {
                    "lastHeartRateId" -> lastHeartRateId
                    "lastHrvId" -> lastHrvId
                    else -> error("Unknown checkpoint id column")
                }

            fun copied(column: String): Long =
                when (column) {
                    "copiedHeartRateRows" -> copiedHeartRateRows
                    "copiedHrvRows" -> copiedHrvRows
                    else -> error("Unknown checkpoint count column")
                }

            fun toProgress(activePhase: V7MigrationPhase): DatabaseMigrationProgress =
                when (activePhase) {
                    V7MigrationPhase.COPY_HEART_RATE ->
                        DatabaseMigrationProgress(activePhase, copiedHeartRateRows, totalHeartRateRows)
                    V7MigrationPhase.COPY_HRV ->
                        DatabaseMigrationProgress(activePhase, copiedHrvRows, totalHrvRows)
                    else -> DatabaseMigrationProgress(activePhase, 0L, 0L)
                }
        }

        private companion object {
            const val DATABASE_NAME = "health_dashboard.db"
            const val CURRENT_VERSION = 7
            const val MIGRATION_ID = "v7"
            const val METADATA_TABLE = "readylytics_schema_migration"
            const val HEART_RATE_TABLE = "heart_rate_records"
            const val HRV_TABLE = "hrv_records"
            const val HEART_RATE_V7_TABLE = "heart_rate_records_v7"
            const val HRV_V7_TABLE = "hrv_records_v7"
            const val BATCH_SIZE = 10_000
            const val SPACE_RESERVE_BYTES = 64L * 1024L * 1024L
            const val V7_IDENTITY_HASH = "54bca00d5cb026eb7ed7aa31e58c34f8"

            val CREATE_METADATA_TABLE =
                """
                CREATE TABLE IF NOT EXISTS $METADATA_TABLE (
                    migrationId TEXT NOT NULL PRIMARY KEY,
                    phase TEXT NOT NULL,
                    lastHeartRateId TEXT,
                    lastHrvId TEXT,
                    copiedHeartRateRows INTEGER NOT NULL,
                    copiedHrvRows INTEGER NOT NULL,
                    totalHeartRateRows INTEGER NOT NULL,
                    totalHrvRows INTEGER NOT NULL
                )
                """.trimIndent()

            val CREATE_HEART_RATE_V7_TABLE =
                """
                CREATE TABLE IF NOT EXISTS $HEART_RATE_V7_TABLE (
                    rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sourceRecordId TEXT NOT NULL,
                    timestampMs INTEGER NOT NULL,
                    beatsPerMinute INTEGER NOT NULL,
                    recordType TEXT NOT NULL,
                    sessionId TEXT,
                    deviceName TEXT
                )
                """.trimIndent()

            val CREATE_HRV_V7_TABLE =
                """
                CREATE TABLE IF NOT EXISTS $HRV_V7_TABLE (
                    rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sourceRecordId TEXT NOT NULL,
                    timestampMs INTEGER NOT NULL,
                    rmssdMs REAL NOT NULL,
                    recordType TEXT NOT NULL,
                    sessionId TEXT,
                    deviceName TEXT
                )
                """.trimIndent()

            const val CREATE_HR_UNIQUE_INDEX =
                "CREATE UNIQUE INDEX IF NOT EXISTS index_hr_v7_source_time " +
                    "ON $HEART_RATE_V7_TABLE(sourceRecordId, timestampMs)"
            const val CREATE_HRV_UNIQUE_INDEX =
                "CREATE UNIQUE INDEX IF NOT EXISTS index_hrv_v7_source_time " +
                    "ON $HRV_V7_TABLE(sourceRecordId, timestampMs)"
            const val CREATE_HR_TIMESTAMP_INDEX =
                "CREATE INDEX IF NOT EXISTS index_hr_v7_timestamp ON $HEART_RATE_V7_TABLE(timestampMs)"
            const val CREATE_HR_SESSION_INDEX =
                "CREATE INDEX IF NOT EXISTS index_hr_v7_session_type_bpm " +
                    "ON $HEART_RATE_V7_TABLE(sessionId, recordType, beatsPerMinute)"
            const val CREATE_HR_TYPE_TIME_INDEX =
                "CREATE INDEX IF NOT EXISTS index_hr_v7_type_timestamp " +
                    "ON $HEART_RATE_V7_TABLE(recordType, timestampMs)"
            const val CREATE_HRV_TIMESTAMP_INDEX =
                "CREATE INDEX IF NOT EXISTS index_hrv_v7_timestamp ON $HRV_V7_TABLE(timestampMs)"
            const val CREATE_HRV_TYPE_TIME_INDEX =
                "CREATE INDEX IF NOT EXISTS index_hrv_v7_type_timestamp ON $HRV_V7_TABLE(recordType, timestampMs)"
            const val CREATE_HRV_SESSION_INDEX =
                "CREATE INDEX IF NOT EXISTS index_hrv_v7_session ON $HRV_V7_TABLE(sessionId)"

            val COPY_HEART_RATE_SQL =
                """
                INSERT OR IGNORE INTO $HEART_RATE_V7_TABLE
                    (sourceRecordId, timestampMs, beatsPerMinute, recordType, sessionId, deviceName)
                SELECT
                    CASE
                        WHEN substr(id, -(length(CAST(timestampMs AS TEXT)) + 1)) =
                            '_' || CAST(timestampMs AS TEXT)
                        THEN substr(id, 1, length(id) - length(CAST(timestampMs AS TEXT)) - 1)
                        ELSE id
                    END,
                    timestampMs, beatsPerMinute, recordType, sessionId, deviceName
                FROM $HEART_RATE_TABLE
                WHERE id > ?
                ORDER BY id
                LIMIT $BATCH_SIZE
                """.trimIndent()

            val COPY_HRV_SQL =
                """
                INSERT OR IGNORE INTO $HRV_V7_TABLE
                    (sourceRecordId, timestampMs, rmssdMs, recordType, sessionId, deviceName)
                SELECT
                    CASE
                        WHEN substr(id, -(length(CAST(timestampMs AS TEXT)) + 1)) =
                            '_' || CAST(timestampMs AS TEXT)
                        THEN substr(id, 1, length(id) - length(CAST(timestampMs AS TEXT)) - 1)
                        ELSE id
                    END,
                    timestampMs, rmssdMs, recordType, sessionId, deviceName
                FROM $HRV_TABLE
                WHERE id > ?
                ORDER BY id
                LIMIT $BATCH_SIZE
                """.trimIndent()
        }
    }

private inline fun <T> SQLiteDatabase.transaction(block: () -> T): T {
    beginTransaction()
    return try {
        val result = block()
        setTransactionSuccessful()
        result
    } finally {
        endTransaction()
    }
}

private fun SQLiteDatabase.queryLong(sql: String): Long =
    rawQuery(sql, emptyArray<String>()).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

private fun android.database.Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)

private fun statAvailableBytes(dbFile: File): Long {
    val parent = requireNotNull(dbFile.parentFile) { "Database has no parent directory" }
    return StatFs(parent.absolutePath).availableBytes
}
