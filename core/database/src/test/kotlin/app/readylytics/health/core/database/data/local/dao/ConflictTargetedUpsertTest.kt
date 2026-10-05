package app.readylytics.health.core.database.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for the conflict-targeted UPSERT that replaced `@Insert(onConflict = REPLACE)`
 * in [HeartRateDao] / [HrvDao] (HEAVY_DATA_SYNC_STABILITY_PLAN Step 8). Verifies on a real
 * in-memory Room DB that re-ingesting the same natural key (sourceRecordId, timestampMs) updates
 * mutable columns in place with a stable `rowId` instead of delete+reinsert rotating it.
 */
@RunWith(AndroidJUnit4::class)
class ConflictTargetedUpsertTest {
    private val inserts = java.util.concurrent.atomic.AtomicInteger()
    private lateinit var database: HealthDatabase
    private lateinit var heartRateDao: HeartRateDao
    private lateinit var hrvDao: HrvDao

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).setQueryCallback({ sql, _ ->
                    if (sql.startsWith("INSERT INTO heart_rate_records") || sql.startsWith("INSERT INTO hrv_records")) {
                        inserts.incrementAndGet()
                    }
                }, java.util.concurrent.Executor { it.run() }).allowMainThreadQueries()
                .build()
        heartRateDao = database.heartRateDao()
        hrvDao = database.hrvDao()
    }

    @After
    fun cleanup() {
        database.close()
    }

    private suspend fun seedSourceRecordParents(vararg refs: Long) {
        database.sourceRecordDao().insertAll(
            refs.map { ref ->
                HealthSourceRecordEntity(
                    id = ref,
                    sourceRecordId = "seed-$ref",
                    recordType = "HEART_RATE",
                    createdAtMs = 0L,
                )
            },
        )
    }

    @Test
    fun `identical heart rate re-ingest keeps rowId stable and does not duplicate`() =
        runTest {
            seedSourceRecordParents(1L)
            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(
                        sourceRecordRef = 1L,
                        timestampMs = 1000L,
                        beatsPerMinute = 60,
                        recordType = "SLEEP",
                    ),
                ),
            )

            val firstRowId = heartRateDao.getByTimeRange(0, Long.MAX_VALUE).single().rowId
            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(
                        sourceRecordRef = 1L,
                        timestampMs = 1000L,
                        beatsPerMinute = 60,
                        recordType = "SLEEP",
                    ),
                ),
            )

            val rows = heartRateDao.getByTimeRange(0, Long.MAX_VALUE)
            assertEquals(1, rows.size)
            assertEquals(firstRowId, rows.single().rowId)
        }

    @Test
    fun `re-tagged heart rate record updates session, record type, and device name in place`() =
        runTest {
            seedSourceRecordParents(1L)
            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(
                        sourceRecordRef = 1L,
                        timestampMs = 1000L,
                        beatsPerMinute = 60,
                        recordType = "RESTING",
                        deviceName = "Watch 1",
                    ),
                ),
            )
            val originalRowId = heartRateDao.getByTimeRange(0, Long.MAX_VALUE).single().rowId

            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(
                        sourceRecordRef = 1L,
                        timestampMs = 1000L,
                        beatsPerMinute = 60,
                        recordType = "SLEEP",
                        sessionId = "sleep-1",
                        deviceName = "Watch 2",
                    ),
                ),
            )

            val rows = heartRateDao.getByTimeRange(0, Long.MAX_VALUE)
            assertEquals(1, rows.size)
            assertEquals(originalRowId, rows.single().rowId)
            assertEquals("SLEEP", rows.single().recordType)
            assertEquals("sleep-1", rows.single().sessionId)
            assertEquals("Watch 2", rows.single().deviceName)
        }

    @Test
    fun `identical hrv re-ingest keeps rowId stable and does not duplicate`() =
        runTest {
            seedSourceRecordParents(1L)
            hrvDao.upsertAll(
                listOf(HrvRecordEntity(sourceRecordRef = 1L, timestampMs = 1000L, rmssdMs = 40f, recordType = "SLEEP")),
            )

            val firstRowId = hrvDao.getByTimeRange(0, Long.MAX_VALUE).single().rowId
            hrvDao.upsertAll(
                listOf(HrvRecordEntity(sourceRecordRef = 1L, timestampMs = 1000L, rmssdMs = 40f, recordType = "SLEEP")),
            )

            val rows = hrvDao.getByTimeRange(0, Long.MAX_VALUE)
            assertEquals(1, rows.size)
            assertEquals(firstRowId, rows.single().rowId)
        }

    @Test
    fun `re-tagged hrv record updates session, record type, and device name in place`() =
        runTest {
            seedSourceRecordParents(1L)
            hrvDao.upsertAll(
                listOf(
                    HrvRecordEntity(
                        sourceRecordRef = 1L,
                        timestampMs = 1000L,
                        rmssdMs = 40f,
                        recordType = "RESTING",
                        deviceName = "Watch 1",
                    ),
                ),
            )
            val originalRowId = hrvDao.getByTimeRange(0, Long.MAX_VALUE).single().rowId

            hrvDao.upsertAll(
                listOf(
                    HrvRecordEntity(
                        sourceRecordRef = 1L,
                        timestampMs = 1000L,
                        rmssdMs = 40f,
                        recordType = "SLEEP",
                        sessionId = "sleep-1",
                        deviceName = "Watch 2",
                    ),
                ),
            )

            val rows = hrvDao.getByTimeRange(0, Long.MAX_VALUE)
            assertEquals(1, rows.size)
            assertEquals(originalRowId, rows.single().rowId)
            assertEquals("SLEEP", rows.single().recordType)
            assertEquals("sleep-1", rows.single().sessionId)
            assertEquals("Watch 2", rows.single().deviceName)
        }

    @Test
    fun `rowId zero ingestion auto-assigns a real rowid`() =
        runTest {
            seedSourceRecordParents(1L)
            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(
                        sourceRecordRef = 1L,
                        timestampMs = 1000L,
                        beatsPerMinute = 60,
                        recordType = "SLEEP",
                    ),
                ),
            )

            val row = heartRateDao.getByTimeRange(0, Long.MAX_VALUE).single()
            assertNotEquals(0L, row.rowId)
            assertNotNull(row.rowId)
        }

    @Test
    fun `re-upsert after deletion-by-source-record reinserts fresh`() =
        runTest {
            seedSourceRecordParents(1L)
            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(
                        sourceRecordRef = 1L,
                        timestampMs = 1000L,
                        beatsPerMinute = 60,
                        recordType = "SLEEP",
                    ),
                ),
            )
            assertEquals(1, heartRateDao.deleteBySourceRecordRef(1L))

            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(
                        sourceRecordRef = 1L,
                        timestampMs = 1000L,
                        beatsPerMinute = 60,
                        recordType = "SLEEP",
                    ),
                ),
            )

            val rows = heartRateDao.getByTimeRange(0, Long.MAX_VALUE)
            assertEquals(1, rows.size)
            assertEquals(1L, rows.single().sourceRecordRef)
            assertEquals(60, rows.single().beatsPerMinute)
        }
    @Test
    fun bulkUpsertMatchesSingleRow() = runTest {
        seedSourceRecordParents(1L)
        val records = (1L..10_000L).map {
            HeartRateRecordEntity(sourceRecordRef = 1L, timestampMs = it, beatsPerMinute = 60, recordType = "RESTING")
        }
        heartRateDao.upsertAll(records.take(6666))
        val mixed = records.mapIndexed { index, row ->
            if (index % 3 == 0) row.copy(beatsPerMinute = 70, sessionId = "session", deviceName = "watch") else row
        }
        for (row in mixed) {
            heartRateDao.conflictTargetedUpsert(
                row.sourceRecordRef, row.timestampMs, row.beatsPerMinute, row.recordType, row.sessionId, row.deviceName,
            )
        }
        val referenceRows = heartRateDao.getByTimeRange(0, Long.MAX_VALUE)
        database.close()
        setup()
        seedSourceRecordParents(1L)
        heartRateDao.upsertAll(records.take(6666))
        heartRateDao.upsertAll(mixed)
        val bulkRows = heartRateDao.getByTimeRange(0, Long.MAX_VALUE)
        assertEquals(referenceRows, bulkRows)
    }

    @Test
    fun bulkUpsertSkipsUnchanged() = runTest {
        seedSourceRecordParents(1L)
        val records = (1L..10_000L).map {
            HeartRateRecordEntity(sourceRecordRef = 1L, timestampMs = it, beatsPerMinute = 60, recordType = "RESTING")
        }
        heartRateDao.upsertAll(records)
        val before = totalChanges()
        heartRateDao.upsertAll(records)
        val updatedRowCount = totalChanges() - before
        assertEquals(0L, updatedRowCount)
    }

    @Test
    fun bulkUpsertChunkBoundaryAndEmpty() = runTest {
        seedSourceRecordParents(1L)
        val records = (1L..101L).map {
            HeartRateRecordEntity(sourceRecordRef = 1L, timestampMs = it, beatsPerMinute = 60, recordType = "RESTING")
        }
        inserts.set(0)
        heartRateDao.upsertAll(records)
        assertEquals(2, inserts.get())
        inserts.set(0)
        heartRateDao.upsertAll(emptyList())
        assertEquals(0, inserts.get())
    }

    @Test
    fun bulkHrvUpsertMatchesSingleRow() = runTest {
        seedSourceRecordParents(1L)
        val records = (1L..10_000L).map {
            HrvRecordEntity(sourceRecordRef = 1L, timestampMs = it, rmssdMs = 40f, recordType = "RESTING")
        }
        hrvDao.upsertAll(records.take(6666))
        val mixed = records.mapIndexed { index, row ->
            if (index % 3 == 0) row.copy(rmssdMs = 50f, sessionId = "session", deviceName = "watch") else row
        }
        for (row in mixed) {
            hrvDao.conflictTargetedUpsert(
                row.sourceRecordRef, row.timestampMs, row.rmssdMs, row.recordType, row.sessionId, row.deviceName,
            )
        }
        val referenceRows = hrvDao.getByTimeRange(0, Long.MAX_VALUE)
        database.close()
        setup()
        seedSourceRecordParents(1L)
        hrvDao.upsertAll(records.take(6666))
        hrvDao.upsertAll(mixed)
        val bulkRows = hrvDao.getByTimeRange(0, Long.MAX_VALUE)
        assertEquals(referenceRows, bulkRows)
    }

    @Test
    fun bulkHrvUpsertSkipsUnchanged() = runTest {
        seedSourceRecordParents(1L)
        val records = (1L..10_000L).map {
            HrvRecordEntity(sourceRecordRef = 1L, timestampMs = it, rmssdMs = 40f, recordType = "RESTING")
        }
        hrvDao.upsertAll(records)
        val before = totalChanges()
        hrvDao.upsertAll(records)
        val updatedRowCount = totalChanges() - before
        assertEquals(0L, updatedRowCount)
    }

    @Test
    fun bulkHrvUpsertChunkBoundaryAndEmpty() = runTest {
        seedSourceRecordParents(1L)
        val records = (1L..101L).map {
            HrvRecordEntity(sourceRecordRef = 1L, timestampMs = it, rmssdMs = 40f, recordType = "RESTING")
        }
        inserts.set(0)
        hrvDao.upsertAll(records)
        assertEquals(2, inserts.get())
        inserts.set(0)
        hrvDao.upsertAll(emptyList())
        assertEquals(0, inserts.get())
    }

    @Test
    fun bulkUpsertInvalidatesObservers() = runBlocking {
        seedSourceRecordParents(1L)
        val record = HeartRateRecordEntity(
            sourceRecordRef = 1L, timestampMs = 1000, beatsPerMinute = 60, recordType = "RESTING",
        )
        heartRateDao.upsertAll(listOf(record))
        val subscribed = CompletableDeferred<Unit>()
        val observed = async(Dispatchers.Default) {
            withTimeout(5000) {
                heartRateDao.observeByTimeRange(0, 2000).onEach { subscribed.complete(Unit) }
                    .first { it.singleOrNull()?.beatsPerMinute == 70 }
            }
        }
        withTimeout(5000) { subscribed.await() }
        heartRateDao.upsertAll(listOf(record.copy(beatsPerMinute = 70)))
        assertEquals(70, observed.await().single().beatsPerMinute)
    }

    private fun totalChanges(): Long = database.openHelper.writableDatabase.query("SELECT total_changes()").use {
        it.moveToFirst()
        it.getLong(0)
    }

}
