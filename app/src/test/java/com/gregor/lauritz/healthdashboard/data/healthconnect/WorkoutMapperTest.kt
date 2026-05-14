package com.gregor.lauritz.healthdashboard.data.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class WorkoutMapperTest {
    @Test
    fun `mapExerciseSession correctly calculates TRIMP and avg HR from samples`() {
        val startTime = Instant.parse("2026-05-09T10:00:00Z")
        val endTime = Instant.parse("2026-05-09T11:00:00Z")

        val session =
            mockk<ExerciseSessionRecord> {
                every { metadata.id } returns "test_session"
                every { this@mockk.startTime } returns startTime
                every { this@mockk.endTime } returns endTime
                every { exerciseType } returns ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
            }

        val thresholds =
            WorkoutMapper.zoneThresholds(
                z1Min = 100,
                z1Max = 120,
                z2Max = 140,
                z3Max = 160,
                z4Max = 180,
            )

        val hrSamples =
            listOf(
                // 30 minutes in Zone 2 (130 bpm)
                HeartRateRecordEntity(
                    id = "s1",
                    timestampMs = startTime.toEpochMilli(),
                    beatsPerMinute = 130,
                    recordType = "EXERCISE",
                    sessionId = "test_session",
                ),
                // 30 minutes in Zone 4 (170 bpm)
                HeartRateRecordEntity(
                    id = "s2",
                    timestampMs = startTime.plusSeconds(1800).toEpochMilli(),
                    beatsPerMinute = 170,
                    recordType = "EXERCISE",
                    sessionId = "test_session",
                ),
            )

        val result = WorkoutMapper.mapExerciseSession(session, hrSamples, thresholds)

        assertEquals("test_session", result.id)
        assertEquals(60, result.durationMinutes)
        assertEquals(150f, result.avgHr, 0.001f)

        // Zone 2 weight = 2.0. Duration = 30 min. TRIMP = 60
        // Zone 4 weight = 4.0. Duration = 30 min. TRIMP = 120
        // Total TRIMP = 180
        assertEquals(180f, result.trimp, 0.001f)
        assertEquals(30f, result.zone2Minutes, 0.001f)
        assertEquals(30f, result.zone4Minutes, 0.001f)
    }

    @Test
    fun `mapExerciseSession handles overlapping or duplicate samples gracefully`() {
        val startTime = Instant.parse("2026-05-09T10:00:00Z")
        val endTime = Instant.parse("2026-05-09T10:10:00Z")

        val session =
            mockk<ExerciseSessionRecord> {
                every { metadata.id } returns "test_session"
                every { this@mockk.startTime } returns startTime
                every { this@mockk.endTime } returns endTime
                every { exerciseType } returns ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
            }

        val thresholds = WorkoutMapper.zoneThresholds()

        val hrSamples =
            listOf(
                // Sample from source A
                HeartRateRecordEntity(
                    id = "sourceA_1",
                    timestampMs = startTime.toEpochMilli(),
                    beatsPerMinute = 140,
                    recordType = "EXERCISE",
                    sessionId = "test_session",
                ),
                // Duplicate sample from source B for the same timestamp
                HeartRateRecordEntity(
                    id = "sourceB_1",
                    timestampMs = startTime.toEpochMilli(),
                    beatsPerMinute = 142,
                    recordType = "EXERCISE",
                    sessionId = "test_session",
                ),
                // Another sample later
                HeartRateRecordEntity(
                    id = "sourceA_2",
                    timestampMs = startTime.plusSeconds(300).toEpochMilli(),
                    beatsPerMinute = 150,
                    recordType = "EXERCISE",
                    sessionId = "test_session",
                ),
            )

        val result = WorkoutMapper.mapExerciseSession(session, hrSamples, thresholds)

        // Average HR: (140 + 142 + 150) / 3 = 144
        assertEquals(144f, result.avgHr, 0.001f)
        assert(result.trimp > 0)
    }
}
