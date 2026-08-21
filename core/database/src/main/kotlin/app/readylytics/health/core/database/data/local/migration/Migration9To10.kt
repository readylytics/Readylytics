package app.readylytics.health.core.database.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v9 -> v10: normalize per-row source UUIDs out of the hot tier.
 *
 * Introduces the [health_source_records] dimension table (base UUID -> integer id) and the
 * [hr_minute_buckets] warm-tier aggregate table, then rebuilds `heart_rate_records` and
 * `hrv_records` to reference the dimension id via an integer `sourceRecordRef` FK instead of
 * storing the full `sourceRecordId` TEXT on every row. Idempotency is preserved by the unique
 * `(sourceRecordRef, timestampMs)` index; a re-run of this migration on a fresh install is a no-op
 * because the new schema is created directly by Room.
 *
 * The base UUID is recovered from the legacy `sourceRecordId` (`<uuid>_<timestampMs>`) by taking
 * everything up to (not including) the first `_`; HC record ids are UUIDs and never contain `_`.
 */
val MIGRATION_9_10 =
    object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. Dimension table.
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS health_source_records (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sourceRecordId TEXT NOT NULL,
                    recordType TEXT NOT NULL,
                    createdAtMs INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_health_source_records_sourceRecordId " +
                    "ON health_source_records(sourceRecordId)",
            )

            // 2. Warm-tier minute buckets (composite identity: one row per minute/session/type).
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS hr_minute_buckets (
                    bucketStartMs INTEGER NOT NULL,
                    bucketEndMs INTEGER NOT NULL,
                    minBpm INTEGER NOT NULL,
                    maxBpm INTEGER NOT NULL,
                    avgBpm REAL NOT NULL,
                    sampleCount INTEGER NOT NULL,
                    recordType TEXT NOT NULL,
                    sessionId TEXT NOT NULL,
                    deviceName TEXT,
                    PRIMARY KEY(bucketStartMs, recordType, sessionId)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_hr_minute_buckets_sessionId_recordType " +
                    "ON hr_minute_buckets(sessionId, recordType)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_hr_minute_buckets_bucketStartMs_bucketEndMs " +
                    "ON hr_minute_buckets(bucketStartMs, bucketEndMs)",
            )

            // 3. Backfill dimension rows from both hot tables (base UUID -> recordType -> first-seen).
            db.execSQL(
                """
                INSERT OR IGNORE INTO health_source_records (sourceRecordId, recordType, createdAtMs)
                SELECT substr(sourceRecordId, 1, instr(sourceRecordId || '_', '_') - 1),
                       'HEART_RATE',
                       MIN(timestampMs)
                FROM heart_rate_records
                GROUP BY substr(sourceRecordId, 1, instr(sourceRecordId || '_', '_') - 1)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO health_source_records (sourceRecordId, recordType, createdAtMs)
                SELECT substr(sourceRecordId, 1, instr(sourceRecordId || '_', '_') - 1),
                       'HRV',
                       MIN(timestampMs)
                FROM hrv_records
                GROUP BY substr(sourceRecordId, 1, instr(sourceRecordId || '_', '_') - 1)
                """.trimIndent(),
            )

            rebuildHeartRateRecords(db)
            rebuildHrvRecords(db)
        }

        private fun rebuildHeartRateRecords(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS heart_rate_records_new (
                    rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sourceRecordRef INTEGER NOT NULL,
                    timestampMs INTEGER NOT NULL,
                    beatsPerMinute INTEGER NOT NULL,
                    recordType TEXT NOT NULL,
                    sessionId TEXT,
                    deviceName TEXT,
                    FOREIGN KEY(sourceRecordRef) REFERENCES health_source_records(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO heart_rate_records_new (rowId, sourceRecordRef, timestampMs, beatsPerMinute, recordType, sessionId, deviceName)
                SELECT hr.rowId, sr.id, hr.timestampMs, hr.beatsPerMinute, hr.recordType, hr.sessionId, hr.deviceName
                FROM heart_rate_records hr
                JOIN health_source_records sr
                  ON sr.sourceRecordId = substr(hr.sourceRecordId, 1, instr(hr.sourceRecordId || '_', '_') - 1)
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE heart_rate_records")
            db.execSQL("ALTER TABLE heart_rate_records_new RENAME TO heart_rate_records")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_hr_v10_source_time " +
                    "ON heart_rate_records(sourceRecordRef, timestampMs)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_hr_v10_timestamp ON heart_rate_records(timestampMs)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_hr_v10_session_type_bpm " +
                    "ON heart_rate_records(sessionId, recordType, beatsPerMinute)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_hr_v10_type_timestamp " +
                    "ON heart_rate_records(recordType, timestampMs)",
            )
        }

        private fun rebuildHrvRecords(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS hrv_records_new (
                    rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sourceRecordRef INTEGER NOT NULL,
                    timestampMs INTEGER NOT NULL,
                    rmssdMs REAL NOT NULL,
                    recordType TEXT NOT NULL,
                    sessionId TEXT,
                    deviceName TEXT,
                    FOREIGN KEY(sourceRecordRef) REFERENCES health_source_records(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO hrv_records_new (rowId, sourceRecordRef, timestampMs, rmssdMs, recordType, sessionId, deviceName)
                SELECT hrv.rowId, sr.id, hrv.timestampMs, hrv.rmssdMs, hrv.recordType, hrv.sessionId, hrv.deviceName
                FROM hrv_records hrv
                JOIN health_source_records sr
                  ON sr.sourceRecordId = substr(hrv.sourceRecordId, 1, instr(hrv.sourceRecordId || '_', '_') - 1)
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE hrv_records")
            db.execSQL("ALTER TABLE hrv_records_new RENAME TO hrv_records")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_hrv_v10_source_time " +
                    "ON hrv_records(sourceRecordRef, timestampMs)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_hrv_v10_timestamp ON hrv_records(timestampMs)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_hrv_v10_type_timestamp " +
                    "ON hrv_records(recordType, timestampMs)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_hrv_v10_session ON hrv_records(sessionId)",
            )
        }
    }
