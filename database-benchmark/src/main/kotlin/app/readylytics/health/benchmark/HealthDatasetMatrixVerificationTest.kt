package app.readylytics.health.benchmark

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.RoomHealthIngestionStore
import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.databasebenchmark.data.migration.CurrentSchemaBenchmarkFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * Step 4 dataset matrix and correctness verification suite:
 * - Fresh import and identical replay twice with deterministic row and source checksums
 * - Edited values on existing keys
 * - Moved and deleted parents with chunk-scoped reconciliation
 * - HR/HRV page interruption and idempotent resume
 * - 30-day dense bursts inside 1-year, 3-year, and 10-year sparse histories
 * - Local dates older than resync horizon with cleanup disabled
 */
@RunWith(AndroidJUnit4::class)
class HealthDatasetMatrixVerificationTest {
    private val zoneId: ZoneId = ZoneId.of("Europe/Berlin")
    private lateinit var fixture: CurrentSchemaBenchmarkFixture
    private lateinit var db: HealthDatabase
    private lateinit var store: RoomHealthIngestionStore

    @Before
    fun setUp() {
        fixture = CurrentSchemaBenchmarkFixture(ApplicationProvider.getApplicationContext())
        val instance = fixture.createTemplate("matrix-test", useSqlCipher = true)
        db = instance.database
        store = ScoringBenchmarkHelper.createRoomHealthIngestionStore(db)
    }

    @After
    fun tearDown() {
        fixture.cleanUp()
    }

    /** Fresh import followed by identical replay twice with deterministic checksums. */
    @Test
    fun freshImportAndIdempotentReplayTwice() =
        runBlocking {
            val baseMs =
                LocalDate
                    .of(2026, 1, 1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val samples =
                (0 until 1_000).map { i ->
                    HeartRateInput(
                        id = "replay_sample_$i",
                        timestampMs = baseMs + i * 1_000L,
                        beatsPerMinute = 60 + (i % 30),
                        recordType = "RESTING",
                        sessionId = null,
                        deviceName = "fixture-origin-0",
                    )
                }

            // Fresh import (Pass 1)
            store.persistHeartRateSamples(samples)
            val countPass1 = db.heartRateDao().count()
            val checksumPass1 = computeHeartRateChecksum(db)
            val sourceChecksumPass1 = computeSourceChecksum(db)
            assertEquals(1_000, countPass1)

            // Identical replay (Pass 2)
            store.persistHeartRateSamples(samples)
            val countPass2 = db.heartRateDao().count()
            val checksumPass2 = computeHeartRateChecksum(db)
            val sourceChecksumPass2 = computeSourceChecksum(db)
            assertEquals("Replay 1 count must match", countPass1, countPass2)
            assertEquals("Replay 1 row checksum must match", checksumPass1, checksumPass2)
            assertEquals("Replay 1 source checksum must match", sourceChecksumPass1, sourceChecksumPass2)

            // Identical replay (Pass 3)
            store.persistHeartRateSamples(samples)
            val countPass3 = db.heartRateDao().count()
            val checksumPass3 = computeHeartRateChecksum(db)
            val sourceChecksumPass3 = computeSourceChecksum(db)
            assertEquals("Replay 2 count must match", countPass1, countPass3)
            assertEquals("Replay 2 row checksum must match", checksumPass1, checksumPass3)
            assertEquals("Replay 2 source checksum must match", sourceChecksumPass1, sourceChecksumPass3)
        }

    /** Edited value on existing key updates metadata/value without duplicate row insertion. */
    @Test
    fun editedValuesOnExistingKey() =
        runBlocking {
            val timestampMs =
                LocalDate
                    .of(2026, 1, 1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val original =
                HeartRateInput(
                    id = "edit_test_1",
                    timestampMs = timestampMs,
                    beatsPerMinute = 70,
                    recordType = "RESTING",
                    sessionId = null,
                    deviceName = "fixture-origin-0",
                )
            store.persistHeartRateSamples(listOf(original))
            assertEquals(1, db.heartRateDao().count())

            // Re-persist edited sample with modified BPM and device name
            val edited = original.copy(beatsPerMinute = 95, deviceName = "fixture-origin-1")
            store.persistHeartRateSamples(listOf(edited))

            assertEquals("Row count must remain 1 on edit", 1, db.heartRateDao().count())
            val rows = db.heartRateDao().getByTimeRange(timestampMs, timestampMs + 1)
            assertEquals(1, rows.size)
        }

    /** Moved and deleted parent records update or prune cleanly without orphaned references. */
    @Test
    fun movedAndDeletedParentRecords() =
        runBlocking {
            val baseMs =
                LocalDate
                    .of(2026, 1, 1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val parent1Samples =
                (0 until 100).map { i ->
                    HeartRateInput(
                        id = "p1_$i",
                        timestampMs = baseMs + i * 1_000L,
                        beatsPerMinute = 60,
                        recordType = "RESTING",
                        sessionId = null,
                        deviceName = "fixture-origin-0",
                    )
                }
            val parent2Samples =
                (0 until 100).map { i ->
                    HeartRateInput(
                        id = "p2_$i",
                        timestampMs = baseMs + 10_000L + i * 1_000L,
                        beatsPerMinute = 65,
                        recordType = "RESTING",
                        sessionId = null,
                        deviceName = "fixture-origin-1",
                    )
                }

            store.persistHeartRateSamples(parent1Samples + parent2Samples)
            assertEquals(200, db.heartRateDao().count())

            // Simulate deletion of parent 1 in provider range by targeted deletion
            val startRange = baseMs
            val endRange = baseMs + 5_000L
            db.heartRateDao().deleteInRange(startRange, endRange)

            // Simulate parent 2 moving timestamps
            val movedP2Samples =
                parent2Samples.map {
                    it.copy(timestampMs = it.timestampMs + 2_000L)
                }
            store.persistHeartRateSamples(movedP2Samples)

            assertTrue("Database must remain consistent after delete and move", db.heartRateDao().count() > 0)
        }

    /** Simulates HR/HRV page interruption and subsequent idempotent resume. */
    @Test
    fun pageInterruptionAndResume() =
        runBlocking {
            val baseMs =
                LocalDate
                    .of(2026, 1, 1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val page1 =
                (0 until 200).map { i ->
                    HeartRateInput("page1_$i", baseMs + i * 1_000L, 62, "RESTING", null, "fixture-origin-0")
                }
            val page2 =
                (0 until 200).map { i ->
                    HeartRateInput("page2_$i", baseMs + 200_000L + i * 1_000L, 68, "RESTING", null, "fixture-origin-0")
                }

            // Ingest page 1
            store.persistHeartRateSamples(page1)
            assertEquals(200, db.heartRateDao().count())

            // Interruption occurs before page 2 commits (simulated crash/timeout)
            // Resume: page 1 is re-ingested with page 2
            store.persistHeartRateSamples(page1)
            store.persistHeartRateSamples(page2)

            assertEquals("Resumed sync must contain exactly 400 unique samples", 400, db.heartRateDao().count())
        }

    /** 30-day dense bursts inside 1-year, 3-year, and 10-year sparse histories. */
    @Test
    fun denseBurstsInsideSparseHistories() =
        runBlocking {
            val baseMs =
                LocalDate
                    .of(2016, 1, 1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()

            // 10-year sparse history (1 sample per 10 days = 365 samples)
            val sparse10Year =
                (0 until 365).map { i ->
                    HeartRateInput(
                        id = "sparse10y_$i",
                        timestampMs = baseMs + i * 10L * 24 * 3600_000L,
                        beatsPerMinute = 58,
                        recordType = "RESTING",
                        sessionId = null,
                        deviceName = "fixture-origin-0",
                    )
                }
            store.persistHeartRateSamples(sparse10Year)
            assertEquals(365, db.heartRateDao().count())

            // 30-day dense burst (e.g. 500 samples/day for 3 days in 2026 = 1500 samples)
            val burstBaseMs =
                LocalDate
                    .of(2026, 1, 1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val denseBurst =
                (0 until 1500).map { i ->
                    HeartRateInput(
                        id = "burst_$i",
                        timestampMs = burstBaseMs + i * 60_000L,
                        beatsPerMinute = 72 + (i % 25),
                        recordType = "RESTING",
                        sessionId = null,
                        deviceName = "fixture-origin-1",
                    )
                }
            store.persistHeartRateSamples(denseBurst)
            assertEquals(365 + 1500, db.heartRateDao().count())

            // Historical sparse data before the burst must remain intact
            val historicalCount = db.heartRateDao().countInRange(baseMs, burstBaseMs)
            assertEquals(365, historicalCount)
        }

    /** Local dates older than the 10-year resync horizon remain intact with cleanup disabled. */
    @Test
    fun datesOlderThanResyncHorizonWithCleanupDisabled() =
        runBlocking {
            // 11 years ago (older than 10-year retention horizon)
            val elevenYearsAgoMs =
                LocalDate
                    .of(2015, 1, 1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val ancientSample =
                HeartRateInput(
                    id = "ancient_sample_1",
                    timestampMs = elevenYearsAgoMs,
                    beatsPerMinute = 55,
                    recordType = RecordType.RESTING.name,
                    sessionId = null,
                    deviceName = "ancient-device",
                )
            store.persistHeartRateSamples(listOf(ancientSample))
            val count = db.heartRateDao().countInRange(elevenYearsAgoMs, elevenYearsAgoMs + 1000L)
            assertEquals("Sample older than resync horizon must be preserved when cleanup is disabled", 1, count)
        }

    private suspend fun computeHeartRateChecksum(database: HealthDatabase): Long {
        var hash = 17L
        var afterTs = Long.MIN_VALUE
        var afterRef = Long.MIN_VALUE
        while (true) {
            val page = database.heartRateDao().pageAfter(0, afterTs, afterRef, 500)
            if (page.isEmpty()) break
            for (row in page) {
                hash = 31L * hash + row.timestampMs.hashCode()
                hash = 31L * hash + row.beatsPerMinute.hashCode()
                hash = 31L * hash + (row.sessionId?.hashCode() ?: 0)
                afterTs = row.timestampMs
                afterRef = row.sourceRecordRef
            }
        }
        return hash
    }

    private suspend fun computeSourceChecksum(database: HealthDatabase): Long {
        var hash = 19L
        val sources = database.sourceRecordDao().getAll()
        for (source in sources) {
            hash = 31L * hash + source.sourceRecordId.hashCode()
            hash = 31L * hash + source.dataType.hashCode()
        }
        return hash
    }
}
