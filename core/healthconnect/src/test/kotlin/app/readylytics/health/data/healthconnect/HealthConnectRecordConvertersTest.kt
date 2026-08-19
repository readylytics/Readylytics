package app.readylytics.health.data.healthconnect

import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import app.readylytics.health.data.healthconnect.toDomain
import app.readylytics.health.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.domain.model.DomainSleepStageType
import app.readylytics.health.domain.model.RouteState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import kotlin.test.assertTrue

class HealthConnectRecordConvertersTest {
    @Test
    fun testExerciseSessionRecordToDomain() {
        val startTime = Instant.parse("2026-07-21T09:00:00Z")
        val endTime = Instant.parse("2026-07-21T10:00:00Z")
        val record = ExerciseSessionRecord(
            startTime = startTime,
            endTime = endTime,
            startZoneOffset = null,
            endZoneOffset = null,
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            title = "Morning Run",
            notes = null,
            metadata = Metadata.manualEntryWithId(id = "test-id")
        )
        val domain: DomainExerciseSessionRecord = record.toDomain()
        assertEquals("test-id", domain.id)
        assertEquals(startTime, domain.startTime)
        assertEquals(endTime, domain.endTime)
    }

    @Test
    fun `exercise route data converts to route points with IMPORTED state`() {
        val time = Instant.parse("2026-07-21T09:05:00Z")
        val record =
            ExerciseSessionRecord(
                startTime = Instant.parse("2026-07-21T09:00:00Z"),
                endTime = Instant.parse("2026-07-21T10:00:00Z"),
                startZoneOffset = null,
                endZoneOffset = null,
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
                title = "Morning Run",
                notes = null,
                metadata = Metadata.manualEntryWithId(id = "test-id"),
                exerciseRoute =
                    ExerciseRoute(
                        route =
                            listOf(
                                ExerciseRoute.Location(
                                    time = time,
                                    latitude = 48.8566,
                                    longitude = 2.3522,
                                    horizontalAccuracy = Length.meters(3.0),
                                    verticalAccuracy = Length.meters(5.0),
                                    altitude = Length.meters(125.0),
                                ),
                                ExerciseRoute.Location(
                                    time = time.plusSeconds(60),
                                    latitude = 48.8570,
                                    longitude = 2.3530,
                                ),
                            ),
                    ),
            )
        val domain = record.toDomain()
        assertEquals(RouteState.IMPORTED, domain.routeState)
        assertEquals(2, domain.routePoints.size)
        val first = domain.routePoints[0]
        assertEquals(48.8566, first.latitude, 0.00001)
        assertEquals(2.3522, first.longitude, 0.00001)
        assertEquals(125.0, first.altitudeMeters!!, 0.00001)
        assertEquals(3.0f, first.horizontalAccuracyMeters!!, 0.001f)
        assertEquals(5.0f, first.verticalAccuracyMeters!!, 0.001f)
        assertEquals(time, first.time)
        val second = domain.routePoints[1]
        assertEquals(null, second.altitudeMeters)
        assertEquals(null, second.horizontalAccuracyMeters)
        assertEquals(null, second.verticalAccuracyMeters)
    }

    @Test
    fun `consent required route converts to empty route points with PERMISSION_REQUIRED state`() {
        val result: ExerciseRouteResult = ExerciseRouteResult.ConsentRequired()
        assertEquals(RouteState.PERMISSION_REQUIRED, result.toRouteState())
        assertTrue(result.toDomainRoutePoints().isEmpty())
    }

    @Test
    fun `no data route converts to empty route points with NOT_AVAILABLE state`() {
        val result: ExerciseRouteResult = ExerciseRouteResult.NoData()
        assertEquals(RouteState.NOT_AVAILABLE, result.toRouteState())
        assertTrue(result.toDomainRoutePoints().isEmpty())
    }

    @Test
    fun `missing route result defaults to NOT_AVAILABLE state`() {
        val result: ExerciseRouteResult? = null
        assertEquals(RouteState.NOT_AVAILABLE, result.toRouteState())
        assertTrue(result.toDomainRoutePoints().isEmpty())
    }

    @Test
    fun `out of bed stage maps to awake`() {
        val start = Instant.parse("2026-01-01T23:00:00Z")
        val record =
            SleepSessionRecord(
                startTime = start,
                startZoneOffset = null,
                endTime = start.plusSeconds(3600),
                endZoneOffset = null,
                stages =
                    listOf(
                        SleepSessionRecord.Stage(
                            startTime = start,
                            endTime = start.plusSeconds(600),
                            stage = SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
                        ),
                    ),
                metadata = Metadata.manualEntry(),
            )

        val domain = record.toDomain()

        assertEquals(DomainSleepStageType.AWAKE, domain.stages.single().stageType)
    }
}
