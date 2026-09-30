package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.HrvInput
import app.readylytics.health.core.model.domain.sync.SourceMetadata
import app.readylytics.health.core.model.domain.sync.SourcePayload
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * #295/#302: the reconcile pass re-derives `recordType`/`sessionId` after every ingest, so an
 * unchanged Health Connect payload whose provisional links differ from the reconciled ones must not
 * count as a source replacement (no revision bump, no generation bump, no dirty ticket).
 */
@RunWith(RobolectricTestRunner::class)
class SourcePayloadWriterSessionLinkIdentityTest {
    private lateinit var database: HealthDatabase
    private lateinit var writer: SourcePayloadWriter

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        runBlocking { database.healthMutationStateDao().getOrCreate() }
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
        writer =
            SourcePayloadWriter(
                daos = daos,
                transactionRunner = RoomTransactionRunner(database),
                dirtyRangeStore = RoomDirtyRangeStore(database.dirtyRangeDao(), database.healthMutationStateDao()),
                healthMutationStateDao = database.healthMutationStateDao(),
                clock = Clock.fixed(NOW, ZoneOffset.UTC),
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `unchanged heart rate payload after a reconcile relink journals no replacement`() =
        runBlocking {
            val payload = hrPayload(bpms = listOf(52, 54))
            writer.replaceHeartRateSources(listOf(payload))
            val state = snapshot()

            // Simulate SessionLinkReconciler re-tagging the stored rows to the overnight sleep.
            val sourceRef = sourceRef()
            val relinked =
                database.heartRateDao().getBySourceRecordRef(sourceRef).map {
                    it.copy(recordType = "SLEEP", sessionId = "sleep-1")
                }
            database.heartRateDao().upsertAll(relinked)

            writer.replaceHeartRateSources(listOf(payload))

            assertEquals(state, snapshot())
            assertEquals(
                listOf("SLEEP" to "sleep-1", "SLEEP" to "sleep-1"),
                database.heartRateDao().getBySourceRecordRef(sourceRef).map { it.recordType to it.sessionId },
            )
        }

    @Test
    fun `unchanged hrv payload after a reconcile relink journals no replacement`() =
        runBlocking {
            val payload = hrvPayload(rmssd = 41.5f)
            writer.replaceHrvSources(listOf(payload))
            val state = snapshot()

            val sourceRef = sourceRef()
            val relinked =
                database.hrvDao().getBySourceRecordRef(sourceRef).map {
                    it.copy(recordType = "SLEEP", sessionId = "sleep-1")
                }
            database.hrvDao().upsertAll(relinked)

            writer.replaceHrvSources(listOf(payload))

            assertEquals(state, snapshot())
            assertEquals(
                "sleep-1",
                database.hrvDao().getBySourceRecordRef(sourceRef).single().sessionId,
            )
        }

    @Test
    fun `a real heart rate value change still replaces the source and journals one ticket`() =
        runBlocking {
            writer.replaceHeartRateSources(listOf(hrPayload(bpms = listOf(52, 54))))
            val before = snapshot()

            writer.replaceHeartRateSources(listOf(hrPayload(bpms = listOf(52, 60))))

            val after = snapshot()
            assertEquals(before.revision + 1, after.revision)
            assertEquals(before.generation + 1, after.generation)
            assertEquals(before.tickets + 1, after.tickets)
        }

    private data class WriterState(
        val revision: Long,
        val generation: Long,
        val tickets: Int,
    )

    private suspend fun snapshot() =
        WriterState(
            revision = requireNotNull(database.sourceRecordDao().getBySourceRecordId(SOURCE_ID)).sourceRevision,
            generation = database.healthMutationStateDao().current().sourceGeneration,
            tickets = database.dirtyRangeDao().count(),
        )

    private suspend fun sourceRef(): Long = requireNotNull(database.sourceRecordDao().getBySourceRecordId(SOURCE_ID)).id

    private fun hrPayload(bpms: List<Int>) =
        SourcePayload(
            SourceMetadata(SOURCE_ID, "HEART_RATE", "origin", SAMPLE_MS, SAMPLE_MS + 60_000L, 1L),
            bpms.mapIndexed { index, bpm ->
                val ts = SAMPLE_MS + index * 10_000L
                HeartRateInput(
                    id = "${SOURCE_ID}_$ts",
                    timestampMs = ts,
                    beatsPerMinute = bpm,
                    recordType = "RESTING",
                    sessionId = null,
                    deviceName = "Watch",
                )
            },
        )

    private fun hrvPayload(rmssd: Float) =
        SourcePayload(
            SourceMetadata(SOURCE_ID, "HRV", "origin", SAMPLE_MS, SAMPLE_MS + 1L, 1L),
            listOf(
                HrvInput(
                    id = "${SOURCE_ID}_$SAMPLE_MS",
                    timestampMs = SAMPLE_MS,
                    rmssdMs = rmssd,
                    recordType = "RESTING",
                    sessionId = null,
                    deviceName = "Watch",
                ),
            ),
        )

    private companion object {
        const val SOURCE_ID = "hc-source"
        val NOW: Instant = Instant.parse("2026-09-30T12:00:00Z")
        val SAMPLE_MS: Long = Instant.parse("2026-09-29T23:30:00Z").toEpochMilli()
    }
}
