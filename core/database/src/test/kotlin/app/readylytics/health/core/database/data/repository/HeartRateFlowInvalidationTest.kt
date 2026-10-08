package app.readylytics.health.core.database.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.HealthRecordDaos
import app.readylytics.health.core.database.data.local.RoomTransactionRunner
import app.readylytics.health.core.database.data.local.SourcePayloadWriter
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.SourceMetadata
import app.readylytics.health.core.model.domain.sync.SourcePayload
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Remediation Phase 4 step 3: a heart-rate write outside an observed window triggers Room's
 * table-level invalidation but must not re-emit the chart Flow; a write inside the window must.
 */
@RunWith(RobolectricTestRunner::class)
class HeartRateFlowInvalidationTest {
    private lateinit var database: HealthDatabase
    private lateinit var writer: SourcePayloadWriter
    private lateinit var repository: HeartRateRepositoryImpl

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        val daos =
            HealthRecordDaos(
                database.sleepSessionDao(),
                database.sleepStageDao(),
                database.heartRateDao(),
                database.hrvDao(),
                database.workoutDao(),
                database.workoutRoutePointDao(),
                database.weightRecordDao(),
                database.bodyFatRecordDao(),
                database.bloodPressureRecordDao(),
                database.oxygenSaturationRecordDao(),
                database.bodyTemperatureRecordDao(),
                database.stepRecordDao(),
                database.sourceRecordDao(),
                database.minuteBucketMaintenanceDao(),
            )
        writer = SourcePayloadWriter(daos, RoomTransactionRunner(database))
        repository = HeartRateRepositoryImpl(database.heartRateDao(), database.hrvDao(), database.minuteBucketDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `timeline ignores a write outside its window and refreshes for one inside`() =
        assertOnlyInWindowWritesEmit(repository.observeTimelineWithResolution(WINDOW_START, WINDOW_END)) {
            it.points.size
        }

    @Test
    fun `range observer ignores a write outside its window and refreshes for one inside`() =
        assertOnlyInWindowWritesEmit(repository.observeByTimeRange(WINDOW_START, WINDOW_END)) { it.size }

    private fun <T> assertOnlyInWindowWritesEmit(
        flow: Flow<T>,
        sampleCount: (T) -> Int,
    ) = runBlocking {
        write("in-1", WINDOW_START + 1_000L)
        val emissions = Channel<T>(Channel.UNLIMITED)
        val collection = launch { flow.collect { emissions.send(it) } }
        try {
            assertEquals(1, sampleCount(withTimeout(TIMEOUT_MS) { emissions.receive() }))

            write("outside", WINDOW_END + 3_600_000L)
            assertNull(withTimeoutOrNull(QUIET_MS) { emissions.receive() })

            // Positive control: without it, the null above would pass even if invalidation were dead.
            write("in-2", WINDOW_START + 2_000L)
            assertEquals(2, sampleCount(withTimeout(TIMEOUT_MS) { emissions.receive() }))
        } finally {
            collection.cancel()
            emissions.close()
        }
    }

    private suspend fun write(
        sourceId: String,
        timestampMs: Long,
    ) {
        writer.replaceHeartRateSources(
            listOf(
                SourcePayload(
                    SourceMetadata(sourceId, "HEART_RATE", "origin", timestampMs, timestampMs + 1L, 1L),
                    listOf(
                        HeartRateInput(
                            id = "${sourceId}_$timestampMs",
                            timestampMs = timestampMs,
                            beatsPerMinute = 62,
                            recordType = "RESTING",
                            sessionId = null,
                            deviceName = "Watch",
                        ),
                    ),
                ),
            ),
        )
    }

    private companion object {
        const val WINDOW_START = 1_790_000_000_000L
        const val WINDOW_END = WINDOW_START + 3_600_000L
        const val TIMEOUT_MS = 5_000L
        const val QUIET_MS = 1_000L
    }
}
