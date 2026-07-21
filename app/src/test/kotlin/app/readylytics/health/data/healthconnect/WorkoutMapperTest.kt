package app.readylytics.health.data.healthconnect

import app.readylytics.health.domain.heartrate.ZoneThresholds
import app.readylytics.health.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.domain.model.DomainHeartRateSample
import app.readylytics.health.domain.sync.mappers.WorkoutMapper
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

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
        // All metrics initialized to 0 during ingestion
        assertEquals(60, result.durationMinutes)
        assertEquals(0f, result.trimp)
        assertEquals(0f, result.avgHr)
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
