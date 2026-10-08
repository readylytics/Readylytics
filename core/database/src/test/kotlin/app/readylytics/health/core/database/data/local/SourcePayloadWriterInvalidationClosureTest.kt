package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.model.domain.sync.HeartRateInput
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
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Remediation Phase 4 step 2 / §7.3 criterion 6: one changed heart-rate source on day D journals
 * exactly one dirty ticket whose closure is `[D, today]` -- never one ticket per day or per
 * sample -- and an identical re-ingest journals nothing.
 */
@RunWith(RobolectricTestRunner::class)
class SourcePayloadWriterInvalidationClosureTest {
    private lateinit var database: HealthDatabase
    private lateinit var store: RoomDirtyRangeStore
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
        store = RoomDirtyRangeStore(database.dirtyRangeDao(), database.healthMutationStateDao())
        writer =
            SourcePayloadWriter(
                daos = daos,
                transactionRunner = RoomTransactionRunner(database),
                dirtyRangeStore = store,
                healthMutationStateDao = database.healthMutationStateDao(),
                clock = Clock.fixed(NOW, ZoneOffset.UTC),
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a changed heart rate source on day D journals exactly one ticket spanning D through today`() =
        runBlocking {
            writer.replaceHeartRateSources(listOf(hrPayload(dayOffsets = listOf(0L), bpm = 52)))
            database.dirtyRangeDao().deleteAll()

            writer.replaceHeartRateSources(listOf(hrPayload(dayOffsets = listOf(0L), bpm = 60)))

            val ticket = store.pending(LIMIT).single()
            assertEquals(DAY_D, ticket.nextDay)
            assertEquals(TODAY, ticket.endInclusive)
        }

    @Test
    fun `a changed source spanning three days still journals one ticket`() =
        runBlocking {
            writer.replaceHeartRateSources(listOf(hrPayload(dayOffsets = listOf(0L, 1L, 2L), bpm = 52)))
            database.dirtyRangeDao().deleteAll()

            writer.replaceHeartRateSources(listOf(hrPayload(dayOffsets = listOf(0L, 1L, 2L), bpm = 61)))

            val ticket = store.pending(LIMIT).single()
            assertEquals(DAY_D, ticket.nextDay)
            assertEquals(TODAY, ticket.endInclusive)
        }

    @Test
    fun `an identical re-ingest journals nothing`() =
        runBlocking {
            val payload = hrPayload(dayOffsets = listOf(0L), bpm = 52)
            writer.replaceHeartRateSources(listOf(payload))
            database.dirtyRangeDao().deleteAll()

            writer.replaceHeartRateSources(listOf(payload))

            assertEquals(0, database.dirtyRangeDao().count())
        }

    private fun hrPayload(
        dayOffsets: List<Long>,
        bpm: Int,
    ): SourcePayload<HeartRateInput> {
        val timestamps = dayOffsets.map { DAY_D_NOON_MS + it * DAY_MS }
        return SourcePayload(
            SourceMetadata(SOURCE_ID, "HEART_RATE", "origin", timestamps.first(), timestamps.last() + 1L, 1L),
            timestamps.map { ts ->
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
    }

    private companion object {
        const val SOURCE_ID = "hc-hr-source"
        const val LIMIT = 100
        const val DAY_MS = 86_400_000L
        val NOW: Instant = Instant.parse("2026-09-30T12:00:00Z")
        val TODAY: LocalDate = LocalDate.parse("2026-09-30")
        val DAY_D: LocalDate = LocalDate.parse("2026-09-20")
        val DAY_D_NOON_MS: Long = Instant.parse("2026-09-20T12:00:00Z").toEpochMilli()
    }
}
