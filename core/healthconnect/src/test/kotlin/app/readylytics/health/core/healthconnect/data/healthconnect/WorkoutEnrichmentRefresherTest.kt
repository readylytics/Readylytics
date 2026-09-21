package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.response.ReadRecordResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.units.Length
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.sync.HealthChangeIngestionStore
import app.readylytics.health.core.model.domain.sync.IntervalChange
import app.readylytics.health.core.model.domain.sync.IntervalKind
import app.readylytics.health.core.model.domain.sync.IntervalSourceRecord
import app.readylytics.health.core.model.domain.sync.PreparedWorkout
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import app.readylytics.health.core.model.domain.sync.overlaps
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WorkoutEnrichmentRefresherTest {
    private val client = mockk<HealthConnectClient>()
    private val changeIngestionStore = mockk<HealthChangeIngestionStore>(relaxed = true)
    private lateinit var refresher: WorkoutEnrichmentRefresher

    private val zoneId = ZoneId.of("UTC")
    private val packageName = "com.strava"

    @Before
    fun setUp() {
        refresher = WorkoutEnrichmentRefresher(client, changeIngestionStore)

        // Default: readRecords return empty pages
        coEvery {
            client.readRecords<DistanceRecord>(match { it.recordType == DistanceRecord::class })
        } returns emptyPage()
        coEvery {
            client.readRecords<ElevationGainedRecord>(match { it.recordType == ElevationGainedRecord::class })
        } returns emptyPage()
    }

    @Test
    fun `half open intervals overlap without touching neighbors`() {
        assertTrue(overlaps(10, 20, 19, 30))
        assertFalse(overlaps(10, 20, 20, 30))
        assertFalse(overlaps(10, 20, 0, 10))
        assertTrue(overlaps(10, 20, 15, 20))
        assertTrue(overlaps(10, 20, 10, 15))
        assertFalse(overlaps(10, 20, 5, 10))
        assertTrue(overlaps(10, 20, 0, 100))
    }

    private fun setupStandardWorkouts() {
        val w1 = mockWorkout("w1", 10_000L, 30_000L)
        val wNeighbor = mockWorkout("w-neighbor", 20_000L, 35_000L)
        val w2 = mockWorkout("w2", 40_000L, 60_000L)
        val wNeighborMove = mockWorkout("w-neighbor-move", 30_000L, 40_000L)
        val storedWorkouts = listOf(w1, wNeighbor, w2, wNeighborMove)
        coEvery { changeIngestionStore.workoutsOverlapping(any(), any()) } returns storedWorkouts

        val t10 = Instant.ofEpochMilli(10_000L)
        val t20 = Instant.ofEpochMilli(20_000L)
        val t30 = Instant.ofEpochMilli(30_000L)
        val t35 = Instant.ofEpochMilli(35_000L)
        val t40 = Instant.ofEpochMilli(40_000L)
        val t60 = Instant.ofEpochMilli(60_000L)

        coEvery { client.readRecord(ExerciseSessionRecord::class, "w1") } returns
            ReadRecordResponse(mockExerciseSession("w1", t10, t30, packageName))
        coEvery { client.readRecord(ExerciseSessionRecord::class, "w-neighbor") } returns
            ReadRecordResponse(mockExerciseSession("w-neighbor", t20, t35, packageName))
        coEvery { client.readRecord(ExerciseSessionRecord::class, "w2") } returns
            ReadRecordResponse(mockExerciseSession("w2", t40, t60, packageName))
        coEvery { client.readRecord(ExerciseSessionRecord::class, "w-neighbor-move") } returns
            ReadRecordResponse(mockExerciseSession("w-neighbor-move", t30, t40, packageName))
    }

    @Test
    fun `provider fixture distance changes alone`() =
        runTest {
            setupStandardWorkouts()
            val distRecord1 = mockDistanceRecord(
                500.0,
                Instant.ofEpochMilli(15_000L),
                Instant.ofEpochMilli(20_000L),
                packageName,
            )
            coEvery {
                client.readRecords<DistanceRecord>(match {
                    it.recordType == DistanceRecord::class &&
                        it.timeRangeFilter == androidx.health.connect.client.time.TimeRangeFilter.between(
                            Instant.ofEpochMilli(10_000L),
                            Instant.ofEpochMilli(30_000L),
                        )
                })
            } returns ReadRecordsResponse(listOf(distRecord1), pageToken = null)

            val change1 = IntervalChange(
                sourceId = "dist-source-1",
                kind = IntervalKind.DISTANCE,
                oldStartMs = null,
                oldEndExclusiveMs = null,
                newStartMs = 15_000L,
                newEndExclusiveMs = 20_000L,
            )

            val preparedSlot1 = slot<List<PreparedWorkout>>()
            val upsertSlot1 = slot<List<IntervalSourceRecord>>()
            coEvery {
                changeIngestionStore.persistIntervalEnrichment(
                    capture(preparedSlot1),
                    capture(upsertSlot1),
                    any(),
                    any(),
                )
            } returns Unit

            refresher.refreshForIntervalChanges(listOf(change1), zoneId)

            assertEquals(1, preparedSlot1.captured.size)
            assertEquals("w1", preparedSlot1.captured.first().workout.id)
            val refreshedW1 = preparedSlot1.captured.first()
            assertTrue(refreshedW1.distanceMeters is ReadOutcome.Available)
            assertEquals(500.0f, (refreshedW1.distanceMeters as ReadOutcome.Available).data)

            assertEquals(1, upsertSlot1.captured.size)
            assertEquals("dist-source-1", upsertSlot1.captured.first().sourceId)
            assertEquals(15_000L, upsertSlot1.captured.first().startMs)
            assertEquals(20_000L, upsertSlot1.captured.first().endExclusiveMs)
        }

    @Test
    fun `provider fixture delete by ID clears total when authorized empty`() =
        runTest {
            setupStandardWorkouts()
            coEvery { changeIngestionStore.getIntervalSource("dist-source-1") } returns
                IntervalSourceRecord("dist-source-1", IntervalKind.DISTANCE, 15_000L, 20_000L, packageName)

            coEvery {
                client.readRecords<DistanceRecord>(match {
                    it.recordType == DistanceRecord::class &&
                        it.timeRangeFilter == androidx.health.connect.client.time.TimeRangeFilter.between(
                            Instant.ofEpochMilli(10_000L),
                            Instant.ofEpochMilli(30_000L),
                        )
                })
            } returns emptyPage()

            val deleteChange = IntervalChange(
                sourceId = "dist-source-1",
                kind = IntervalKind.DISTANCE,
                oldStartMs = null,
                oldEndExclusiveMs = null,
                newStartMs = null,
                newEndExclusiveMs = null,
            )

            val preparedSlot = slot<List<PreparedWorkout>>()
            val deleteSlot = slot<List<String>>()
            coEvery {
                changeIngestionStore.persistIntervalEnrichment(
                    capture(preparedSlot),
                    any(),
                    capture(deleteSlot),
                    any(),
                )
            } returns Unit

            refresher.refreshForIntervalChanges(listOf(deleteChange), zoneId)

            assertEquals(1, preparedSlot.captured.size)
            assertEquals("w1", preparedSlot.captured.first().workout.id)
            val clearedW1 = preparedSlot.captured.first()
            assertTrue(clearedW1.distanceMeters is ReadOutcome.Available)
            assertNull((clearedW1.distanceMeters as ReadOutcome.Available).data)
            assertEquals(listOf("dist-source-1"), deleteSlot.captured)
        }

    @Test
    fun `provider fixture delete by ID retains total when denied`() =
        runTest {
            setupStandardWorkouts()
            coEvery { changeIngestionStore.getIntervalSource("dist-source-1") } returns
                IntervalSourceRecord("dist-source-1", IntervalKind.DISTANCE, 15_000L, 20_000L, packageName)

            coEvery {
                client.readRecords<DistanceRecord>(match {
                    it.recordType == DistanceRecord::class &&
                        it.timeRangeFilter == androidx.health.connect.client.time.TimeRangeFilter.between(
                            Instant.ofEpochMilli(10_000L),
                            Instant.ofEpochMilli(30_000L),
                        )
                })
            } throws SecurityException("Permission revoked")

            val deleteChange = IntervalChange(
                sourceId = "dist-source-1",
                kind = IntervalKind.DISTANCE,
                oldStartMs = null,
                oldEndExclusiveMs = null,
                newStartMs = null,
                newEndExclusiveMs = null,
            )

            val preparedSlot = slot<List<PreparedWorkout>>()
            coEvery {
                changeIngestionStore.persistIntervalEnrichment(
                    capture(preparedSlot),
                    any(),
                    any(),
                    any(),
                )
            } returns Unit

            refresher.refreshForIntervalChanges(listOf(deleteChange), zoneId)

            assertEquals(1, preparedSlot.captured.size)
            assertEquals("w1", preparedSlot.captured.first().workout.id)
            assertEquals(ReadOutcome.Denied, preparedSlot.captured.first().distanceMeters)
        }

    @Test
    fun `provider fixture move source from one workout to another`() =
        runTest {
            setupStandardWorkouts()
            val moveChange = IntervalChange(
                sourceId = "dist-source-1",
                kind = IntervalKind.DISTANCE,
                oldStartMs = 15_000L,
                oldEndExclusiveMs = 20_000L,
                newStartMs = 40_000L,
                newEndExclusiveMs = 50_000L,
            )

            coEvery {
                client.readRecords<DistanceRecord>(match {
                    it.recordType == DistanceRecord::class &&
                        it.timeRangeFilter == androidx.health.connect.client.time.TimeRangeFilter.between(
                            Instant.ofEpochMilli(10_000L),
                            Instant.ofEpochMilli(30_000L),
                        )
                })
            } returns emptyPage()

            val distRecordMoved = mockDistanceRecord(
                800.0,
                Instant.ofEpochMilli(40_000L),
                Instant.ofEpochMilli(50_000L),
                packageName,
            )
            coEvery {
                client.readRecords<DistanceRecord>(match {
                    it.recordType == DistanceRecord::class &&
                        it.timeRangeFilter == androidx.health.connect.client.time.TimeRangeFilter.between(
                            Instant.ofEpochMilli(40_000L),
                            Instant.ofEpochMilli(60_000L),
                        )
                })
            } returns ReadRecordsResponse(listOf(distRecordMoved), pageToken = null)

            val preparedSlot = slot<List<PreparedWorkout>>()
            coEvery {
                changeIngestionStore.persistIntervalEnrichment(
                    capture(preparedSlot),
                    any(),
                    any(),
                    any(),
                )
            } returns Unit

            refresher.refreshForIntervalChanges(listOf(moveChange), zoneId)

            val refreshedIds = preparedSlot.captured.map { it.workout.id }.toSet()
            assertEquals(setOf("w1", "w2"), refreshedIds)
            assertFalse(refreshedIds.contains("w-neighbor"))
            assertFalse(refreshedIds.contains("w-neighbor-move"))

            val refreshedW2 = preparedSlot.captured.first { it.workout.id == "w2" }
            assertTrue(refreshedW2.distanceMeters is ReadOutcome.Available)
            assertEquals(800.0f, (refreshedW2.distanceMeters as ReadOutcome.Available).data)

            val reRefreshedW1 = preparedSlot.captured.first { it.workout.id == "w1" }
            assertTrue(reRefreshedW1.distanceMeters is ReadOutcome.Available)
            assertNull((reRefreshedW1.distanceMeters as ReadOutcome.Available).data)
        }

    @Test
    fun `elevation changes alone refreshes overlapping workout elevation`() =
        runTest {
            val w1 = mockWorkout("w1", 10_000L, 30_000L)
            coEvery { changeIngestionStore.workoutsOverlapping(any(), any()) } returns listOf(w1)
            val t10 = Instant.ofEpochMilli(10_000L)
            val t30 = Instant.ofEpochMilli(30_000L)
            val t15 = Instant.ofEpochMilli(15_000L)
            val t25 = Instant.ofEpochMilli(25_000L)
            coEvery { client.readRecord(ExerciseSessionRecord::class, "w1") } returns
                ReadRecordResponse(mockExerciseSession("w1", t10, t30, packageName))

            val elevRecord = mockElevationRecord(150.0, t15, t25, packageName)
            coEvery {
                client.readRecords<ElevationGainedRecord>(match { it.recordType == ElevationGainedRecord::class })
            } returns ReadRecordsResponse(listOf(elevRecord), pageToken = null)

            val change = IntervalChange(
                sourceId = "elev-1",
                kind = IntervalKind.ELEVATION_GAINED,
                newStartMs = 15_000L,
                newEndExclusiveMs = 25_000L,
            )

            val preparedSlot = slot<List<PreparedWorkout>>()
            coEvery {
                changeIngestionStore.persistIntervalEnrichment(
                    capture(preparedSlot),
                    any(),
                    any(),
                    any(),
                )
            } returns Unit

            refresher.refreshForIntervalChanges(listOf(change), zoneId)

            assertEquals(1, preparedSlot.captured.size)
            val refreshed = preparedSlot.captured.first()
            assertTrue(refreshed.elevationMeters is ReadOutcome.Available)
            assertEquals(150.0f, (refreshed.elevationMeters as ReadOutcome.Available).data)
        }

    @Test
    fun `unknown legacy interval deletion does not clear workout totals`() =
        runTest {
            // Source not found in store
            coEvery { changeIngestionStore.getIntervalSource("legacy-unknown") } returns null

            val change = IntervalChange(
                sourceId = "legacy-unknown",
                kind = IntervalKind.DISTANCE,
                oldStartMs = null,
                oldEndExclusiveMs = null,
                newStartMs = null,
                newEndExclusiveMs = null,
            )

            refresher.refreshForIntervalChanges(listOf(change), zoneId)

            // No workouts queried or persisted
            coVerify(exactly = 0) { changeIngestionStore.persistIntervalEnrichment(any(), any(), any(), any()) }
        }

    @Test
    fun `interval total written by different package is not attributed to session`() =
        runTest {
            val w1 = mockWorkout("w1", 10_000L, 30_000L)
            coEvery { changeIngestionStore.workoutsOverlapping(any(), any()) } returns listOf(w1)
            val t10 = Instant.ofEpochMilli(10_000L)
            val t30 = Instant.ofEpochMilli(30_000L)
            val t15 = Instant.ofEpochMilli(15_000L)
            val t20 = Instant.ofEpochMilli(20_000L)
            coEvery { client.readRecord(ExerciseSessionRecord::class, "w1") } returns
                ReadRecordResponse(mockExerciseSession("w1", t10, t30, "com.strava"))

            // Distance written by another app (e.g. Samsung Health)
            val distRecordOther = mockDistanceRecord(1000.0, t15, t20, "com.other.app")
            coEvery {
                client.readRecords<DistanceRecord>(match { it.recordType == DistanceRecord::class })
            } returns ReadRecordsResponse(listOf(distRecordOther), pageToken = null)

            val change = IntervalChange(
                sourceId = "dist-other",
                kind = IntervalKind.DISTANCE,
                newStartMs = 15_000L,
                newEndExclusiveMs = 20_000L,
            )

            val preparedSlot = slot<List<PreparedWorkout>>()
            coEvery {
                changeIngestionStore.persistIntervalEnrichment(
                    capture(preparedSlot),
                    any(),
                    any(),
                    any(),
                )
            } returns Unit

            refresher.refreshForIntervalChanges(listOf(change), zoneId)

            assertEquals(1, preparedSlot.captured.size)
            // Other package total is not attributed -> resolves to null (Available(null))
            val refreshed = preparedSlot.captured.first()
            assertTrue(refreshed.distanceMeters is ReadOutcome.Available)
            assertNull((refreshed.distanceMeters as ReadOutcome.Available).data)
        }

    private fun mockWorkout(id: String, startTime: Long, endTime: Long): WorkoutInput =
        WorkoutInput(
            id = id,
            startTime = startTime,
            endTime = endTime,
            exerciseType = "RUNNING",
            durationMinutes = ((endTime - startTime) / 60_000L).toInt(),
            zone1Minutes = 0f,
            zone2Minutes = 0f,
            zone3Minutes = 0f,
            zone4Minutes = 0f,
            zone5Minutes = 0f,
            trimp = 10f,
            avgHr = 140f,
            deviceName = "com.strava",
        )

    private fun mockExerciseSession(
        id: String,
        start: Instant,
        end: Instant,
        pkg: String,
    ): ExerciseSessionRecord {
        val meta = mockk<Metadata>(relaxed = true) {
            every { this@mockk.id } returns id
            val origin = mockk<DataOrigin>(relaxed = true) {
                every { packageName } returns pkg
            }
            every { dataOrigin } returns origin
        }
        return mockk(relaxed = true) {
            every { metadata } returns meta
            every { startTime } returns start
            every { endTime } returns end
        }
    }

    private fun mockDistanceRecord(
        meters: Double,
        start: Instant,
        end: Instant,
        pkg: String,
    ): DistanceRecord =
        mockk(relaxed = true) {
            every { metadata.dataOrigin.packageName } returns pkg
            every { distance } returns Length.meters(meters)
            every { startTime } returns start
            every { endTime } returns end
        }

    private fun mockElevationRecord(
        meters: Double,
        start: Instant,
        end: Instant,
        pkg: String,
    ): ElevationGainedRecord =
        mockk(relaxed = true) {
            every { metadata.dataOrigin.packageName } returns pkg
            every { elevation } returns Length.meters(meters)
            every { startTime } returns start
            every { endTime } returns end
        }

    private fun <T : Record> emptyPage(): ReadRecordsResponse<T> =
        mockk {
            every { records } returns emptyList()
            every { pageToken } returns null
        }
}
