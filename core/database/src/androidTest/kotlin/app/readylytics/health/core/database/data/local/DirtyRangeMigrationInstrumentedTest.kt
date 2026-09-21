package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.core.databaseschema.data.local.dao.SourceRecordDao
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirtyRangeMigrationInstrumentedTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            HealthDatabase::class.java,
        )

    @Test
    fun preMigrationBaselineCannotPreserveDirtyState() {
        val db = helper.createDatabase(TEST_DATABASE, 19)
        try {
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('dirty_ranges', 'health_mutation_state')",
            ).use { cursor ->
                assertFalse("v19 baseline must not contain dirty_ranges or health_mutation_state", cursor.moveToFirst())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate19To20PreservesDataAndInitializesJournalAndMutationState() {
        helper.createDatabase(TEST_DATABASE, 19).apply {
            seedFixture(this)
            close()
        }

        val database =
            helper.runMigrationsAndValidate(
                TEST_DATABASE,
                20,
                true,
                *DatabaseMigrations.all,
            )

        // 1. Ref 42 and other sources survive with expected defaults
        database.query(
            "SELECT id, sourceRecordId, metadataState, sourceRevision, originPackage, recordStartMs, recordEndExclusiveMs, lastModifiedMs " +
                "FROM health_source_records ORDER BY id",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            // Ref 42
            assertEquals(42L, cursor.getLong(0))
            assertEquals("source-hr-42", cursor.getString(1))
            assertEquals("UNKNOWN", cursor.getString(2))
            assertEquals(0L, cursor.getLong(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
            assertTrue(cursor.isNull(6))
            assertTrue(cursor.isNull(7))

            // Ref 43
            assertTrue(cursor.moveToNext())
            assertEquals(43L, cursor.getLong(0))
            assertEquals("source-hrv-43", cursor.getString(1))
            assertEquals("UNKNOWN", cursor.getString(2))
            assertEquals(0L, cursor.getLong(3))

            // Ref 44
            assertTrue(cursor.moveToNext())
            assertEquals(44L, cursor.getLong(0))
            assertEquals("source-warm-44", cursor.getString(1))
            assertEquals("UNKNOWN", cursor.getString(2))
            assertEquals(0L, cursor.getLong(3))

            assertFalse(cursor.moveToNext())
        }

        // 2. PRAGMA foreign_key_check is clean
        database.query("PRAGMA foreign_key_check").use { cursor ->
            assertFalse("Foreign key check must be empty after migration", cursor.moveToFirst())
        }

        // 3. No invented dirty dates
        database.query("SELECT COUNT(*) FROM dirty_ranges").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
        }

        // 4. Initial mutation state row exists
        database.query(
            "SELECT id, sourceGeneration, maintenanceOperationId, maintenancePhase, backfillAfterSourceRef " +
                "FROM health_mutation_state",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(0L, cursor.getLong(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertEquals(0L, cursor.getLong(4))
        }

        // 5. Existing numerical / sample rows remain untouched
        database.query("SELECT COUNT(*) FROM heart_rate_records WHERE sourceRecordRef = 42").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2L, cursor.getLong(0))
        }
        database.query("SELECT rmssdMs FROM hrv_records WHERE sourceRecordRef = 43").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(45.0, cursor.getFloat(0).toDouble(), 0.001)
        }
        database.query("SELECT COUNT(*) FROM hr_minute_buckets").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
        }
        database.query("SELECT COUNT(*) FROM workout_records WHERE id = 'w1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
        }
        database.query("SELECT COUNT(*) FROM workout_route_points WHERE workoutId = 'w1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
        }
        database.query("SELECT COUNT(*) FROM daily_summaries WHERE dateMidnightMs = 1000000").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
        }

        database.close()

        // 6. Keyset backfill test: interrupt after 1 batch, restart, and compare with uninterrupted backfill
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roomDb =
            Room.databaseBuilder(context, HealthDatabase::class.java, TEST_DATABASE)
                .addMigrations(*DatabaseMigrations.all)
                .build()
        try {
            val backfill =
                SourceMetadataBackfill(
                    sourceRecordDao = roomDb.sourceRecordDao(),
                    heartRateDao = roomDb.heartRateDao(),
                    hrvDao = roomDb.hrvDao(),
                    healthMutationStateDao = roomDb.healthMutationStateDao(),
                    transactionRunner = RoomTransactionRunner(roomDb),
                )

            runBlocking {
                // Batch size 2, maxBatches 1 -> processes refs 42 and 43, then stops
                val r1 = backfill.backfill(batchSize = 2, maxBatches = 1)
                assertEquals(1, r1.batchesProcessed)
                assertEquals(2, r1.sourcesExamined)
                assertEquals(2, r1.sourcesUpdated)
                assertFalse(r1.isComplete)
                assertEquals(43L, roomDb.healthMutationStateDao().get()?.backfillAfterSourceRef)

                // Ref 42 is updated to CHILD_BOUNDS
                val ref42 = roomDb.sourceRecordDao().getById(42)!!
                assertEquals("CHILD_BOUNDS", ref42.metadataState)
                assertEquals(1000000L, ref42.recordStartMs)
                assertEquals(1005001L, ref42.recordEndExclusiveMs)
                assertNull(ref42.originPackage)

                // Ref 43 is updated to CHILD_BOUNDS
                val ref43 = roomDb.sourceRecordDao().getById(43)!!
                assertEquals("CHILD_BOUNDS", ref43.metadataState)
                assertEquals(2000000L, ref43.recordStartMs)
                assertEquals(2000001L, ref43.recordEndExclusiveMs)
                assertNull(ref43.originPackage)

                // Restart and complete remaining
                val r2 = backfill.backfill(batchSize = 2, maxBatches = 10)
                assertTrue(r2.isComplete)
                assertEquals(44L, roomDb.healthMutationStateDao().get()?.backfillAfterSourceRef)

                // Ref 44 (warm-only) remains unknown
                val ref44 = roomDb.sourceRecordDao().getById(44)!!
                assertEquals("UNKNOWN", ref44.metadataState)
                assertNull(ref44.recordStartMs)
                assertNull(ref44.recordEndExclusiveMs)
                assertNull(ref44.originPackage)
            }
        } finally {
            roomDb.close()
        }

        // Compare with uninterrupted backfill on a second DB
        helper.createDatabase(TEST_DATABASE_2, 19).apply {
            seedFixture(this)
            close()
        }
        val db2 = helper.runMigrationsAndValidate(TEST_DATABASE_2, 20, true, *DatabaseMigrations.all)
        db2.close()

        val roomDb2 =
            Room.databaseBuilder(context, HealthDatabase::class.java, TEST_DATABASE_2)
                .addMigrations(*DatabaseMigrations.all)
                .build()
        try {
            val backfill2 =
                SourceMetadataBackfill(
                    sourceRecordDao = roomDb2.sourceRecordDao(),
                    heartRateDao = roomDb2.heartRateDao(),
                    hrvDao = roomDb2.hrvDao(),
                    healthMutationStateDao = roomDb2.healthMutationStateDao(),
                    transactionRunner = RoomTransactionRunner(roomDb2),
                )
            runBlocking {
                val r = backfill2.backfill(batchSize = 10)
                assertTrue(r.isComplete)

                val roomDb1Reopened = Room.databaseBuilder(context, HealthDatabase::class.java, TEST_DATABASE).build()
                try {
                    val sources1 = roomDb1Reopened.sourceRecordDao().getAll()
                    val sources2 = roomDb2.sourceRecordDao().getAll()
                    assertEquals(sources2, sources1)

                    val state1 = roomDb1Reopened.healthMutationStateDao().get()
                    val state2 = roomDb2.healthMutationStateDao().get()
                    assertEquals(state2, state1)
                } finally {
                    roomDb1Reopened.close()
                }
            }
        } finally {
            roomDb2.close()
        }
    }

    private fun seedFixture(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO health_source_records (id, sourceRecordId, recordType, createdAtMs) " +
                "VALUES (42, 'source-hr-42', 'HEART_RATE', 1000000)",
        )
        db.execSQL(
            "INSERT INTO health_source_records (id, sourceRecordId, recordType, createdAtMs) " +
                "VALUES (43, 'source-hrv-43', 'HRV', 1000000)",
        )
        db.execSQL(
            "INSERT INTO health_source_records (id, sourceRecordId, recordType, createdAtMs) " +
                "VALUES (44, 'source-warm-44', 'HEART_RATE', 1000000)",
        )
        db.execSQL(
            "INSERT INTO heart_rate_records (sourceRecordRef, timestampMs, beatsPerMinute, recordType, sessionId, deviceName) " +
                "VALUES (42, 1000000, 72, 'RESTING', NULL, 'Watch')",
        )
        db.execSQL(
            "INSERT INTO heart_rate_records (sourceRecordRef, timestampMs, beatsPerMinute, recordType, sessionId, deviceName) " +
                "VALUES (42, 1005000, 75, 'RESTING', NULL, 'Watch')",
        )
        db.execSQL(
            "INSERT INTO hrv_records (sourceRecordRef, timestampMs, rmssdMs, recordType, sessionId, deviceName) " +
                "VALUES (43, 2000000, 45.0, 'SLEEP', NULL, 'Watch')",
        )
        db.execSQL(
            "INSERT INTO hr_minute_buckets (bucketStartMs, bucketEndMs, minBpm, maxBpm, avgBpm, sampleCount, recordType, sessionId, deviceName) " +
                "VALUES (3000000, 3060000, 60, 70, 65.0, 10, 'RESTING', '', 'Watch')",
        )
        db.execSQL(
            "INSERT INTO workout_records (id, startTime, endTime, exerciseType, durationMinutes, " +
                "zone1Minutes, zone2Minutes, zone3Minutes, zone4Minutes, zone5Minutes, trimp, avgHr, modelTrimp, routeState) " +
                "VALUES ('w1', 1000, 2000, 'RUNNING', 16, 0, 0, 0, 0, 0, 10, 140, 10, 'COMPLETED')",
        )
        db.execSQL(
            "INSERT INTO workout_route_points (workoutId, latitude, longitude, altitude, timestampMs, horizontalAccuracy, verticalAccuracy) " +
                "VALUES ('w1', 45.0, 9.0, 100.0, 1500, 5.0, 5.0)",
        )
        db.execSQL(
            "INSERT INTO daily_summaries (dateMidnightMs, diag_isCalibrating, diag_stagesSuspicious, diag_lateNadir, diag_hrvMissing, diag_timezoneJump) " +
                "VALUES (1000000, 0, 0, 0, 0, 0)",
        )
    }

    private companion object {
        const val TEST_DATABASE = "dirty-range-migration-test"
        const val TEST_DATABASE_2 = "dirty-range-migration-test-2"
    }

    private suspend fun SourceRecordDao.getById(id: Long): HealthSourceRecordEntity? =
        pageAfter(id - 1, 1).firstOrNull { it.id == id }
}
