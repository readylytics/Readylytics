package app.readylytics.health.core.model.domain.sync.mappers

import app.readylytics.health.core.model.domain.heartrate.ZoneThresholds
import app.readylytics.health.core.model.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import app.readylytics.health.core.model.domain.model.DomainRouteLocation
import app.readylytics.health.core.model.domain.model.RouteState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import kotlin.test.assertTrue

class WorkoutMapperTest {
    @Test
    fun `mapExerciseSession correctly maps basic session fields`() {
        val startTime = Instant.parse("2026-05-09T10:00:00Z")
        val endTime = Instant.parse("2026-05-09T11:00:00Z")

        val session =
            DomainExerciseSessionRecord(
                id = "test_session",
                startTime = startTime,
                endTime = endTime,
                exerciseType = "RUNNING",
                deviceName = "Watch",
            )

        val result = WorkoutMapper.mapExerciseSession(session)

        assertEquals("test_session", result.id)
        assertEquals(startTime.toEpochMilli(), result.startTime)
        assertEquals(endTime.toEpochMilli(), result.endTime)
        assertEquals("RUNNING", result.exerciseType)
        assertEquals("Watch", result.deviceName)
        // durationMinutes is derived at ingestion time; zone/TRIMP/avgHr wait for the reconcile pass
        assertEquals(60, result.durationMinutes)
        assertEquals(0f, result.trimp)
        assertEquals(0f, result.avgHr)
    }

    @Test
    fun `mapExerciseSession preserves route points and computes fallback metrics`() {
        val startTime = Instant.parse("2026-05-09T10:00:00Z")
        val endTime = Instant.parse("2026-05-09T11:00:00Z")
        val session =
            DomainExerciseSessionRecord(
                id = "test_session",
                startTime = startTime,
                endTime = endTime,
                exerciseType = "56",
                deviceName = "Watch",
                routePoints =
                    listOf(
                        DomainRouteLocation(48.8566, 2.3522, 100.0, startTime, 3.0f, 5.0f),
                        DomainRouteLocation(48.8580, 2.3540, 110.0, startTime.plusSeconds(600), 3.0f, 5.0f),
                        DomainRouteLocation(48.8590, 2.3550, 104.0, startTime.plusSeconds(1200), 3.0f, 5.0f),
                    ),
                routeState = RouteState.IMPORTED,
            )

        val result = WorkoutMapper.mapExerciseSession(session)

        assertEquals(RouteState.IMPORTED, result.routeState)
        assertEquals(3, result.routePoints.size)
        val first = result.routePoints[0]
        assertEquals("test_session", first.workoutId)
        assertEquals(48.8566, first.latitude, 0.00001)
        assertEquals(2.3522, first.longitude, 0.00001)
        assertEquals(100.0, first.altitude!!, 0.00001)
        assertEquals(startTime.toEpochMilli(), first.timestampMs)
        assertEquals(3.0f, first.horizontalAccuracy!!, 0.001f)
        assertEquals(5.0f, first.verticalAccuracy!!, 0.001f)
        assertTrue(result.totalDistanceMeters!! > 0f, "expected fallback distance")
        assertTrue(result.avgSpeedKmh!! > 0f, "expected fallback avg speed")
        assertTrue(result.elevationGainMeters!! > 0f, "expected fallback elevation gain")
    }

    @Test
    fun `mapExerciseSession leaves metrics null when no route data exists`() {
        val startTime = Instant.parse("2026-05-09T10:00:00Z")
        val endTime = Instant.parse("2026-05-09T11:00:00Z")
        val session =
            DomainExerciseSessionRecord(
                id = "test_session",
                startTime = startTime,
                endTime = endTime,
                exerciseType = "56",
                deviceName = "Watch",
            )

        val result = WorkoutMapper.mapExerciseSession(session)

        assertTrue(result.routePoints.isEmpty())
        assertNull(result.totalDistanceMeters)
        assertNull(result.avgSpeedKmh)
        assertNull(result.elevationGainMeters)
        assertEquals(RouteState.NOT_AVAILABLE, result.routeState)
    }

    @Test
    fun `computeMetrics correctly calculates TRIMP and avg HR from samples`() {
        val startTime = Instant.parse("2026-05-09T10:00:00Z")
        val endTime = Instant.parse("2026-05-09T11:00:00Z")

        val thresholds =
            ZoneThresholds.zoneThresholds(
                z1Min = 100,
                z1Max = 120,
                z2Max = 140,
                z3Max = 160,
                z4Max = 180,
            )

        val hrSamples =
            listOf(
                // 30 minutes in Zone 2 (130 bpm)
                DomainHeartRateSample(
                    time = startTime,
                    beatsPerMinute = 130,
                ),
                // 30 minutes in Zone 4 (170 bpm)
                DomainHeartRateSample(
                    time = startTime.plusSeconds(1800),
                    beatsPerMinute = 170,
                ),
            )

        val result =
            ZoneThresholds.computeMetrics(
                startTime.toEpochMilli(),
                endTime.toEpochMilli(),
                hrSamples,
                thresholds,
            )

        assertEquals(60, result.durationMinutes)
        assertEquals(150f, result.avgHr, 0.001f)

        // Zone 2 weight = 2.0. Duration = 30 min. TRIMP = 60
        // Zone 4 weight = 4.0. Duration = 30 min. TRIMP = 120
        // Total TRIMP = 180
        assertEquals(180f, result.trimp, 0.001f)
        assertEquals(30f, result.zoneMinutes[1], 0.001f)
        assertEquals(30f, result.zoneMinutes[3], 0.001f)
    }

    @Test
    fun `computeMetrics handles overlapping or duplicate samples gracefully`() {
        val startTime = Instant.parse("2026-05-09T10:00:00Z")
        val endTime = Instant.parse("2026-05-09T10:10:00Z")

        val thresholds = ZoneThresholds.zoneThresholds()

        val hrSamples =
            listOf(
                // Sample from source A
                DomainHeartRateSample(
                    time = startTime,
                    beatsPerMinute = 140,
                ),
                // Duplicate sample from source B for the same timestamp
                DomainHeartRateSample(
                    time = startTime,
                    beatsPerMinute = 142,
                ),
                // Another sample later
                DomainHeartRateSample(
                    time = startTime.plusSeconds(300),
                    beatsPerMinute = 150,
                ),
            )

        val result =
            ZoneThresholds.computeMetrics(
                startTime.toEpochMilli(),
                endTime.toEpochMilli(),
                hrSamples,
                thresholds,
            )

        // Average HR: (140 + 142 + 150) / 3 = 144
        assertEquals(144f, result.avgHr, 0.001f)
        assert(result.trimp > 0)
    }
}
