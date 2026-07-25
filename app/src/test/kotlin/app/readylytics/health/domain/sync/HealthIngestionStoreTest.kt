package app.readylytics.health.domain.sync

import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class HealthIngestionStoreTest {
    @Test
    fun testSleepSessionInput() {
        val input =
            SleepSessionInput(
                id = "1",
                startTime = 100,
                endTime = 200,
                durationMinutes = 10,
                efficiency = 0.9f,
                deepSleepMinutes = 2,
                remSleepMinutes = 2,
                lightSleepMinutes = 5,
                awakeMinutes = 1,
                sleepScore = 80f,
                startZoneOffsetSeconds = 3600,
                endZoneOffsetSeconds = 3600,
                deviceName = "Phone",
            )
        assertEquals("1", input.id)
        assertEquals(100, input.startTime)
        assertEquals(200, input.endTime)
        assertEquals(10, input.durationMinutes)
        assertEquals(0.9f, input.efficiency)
        assertEquals(2, input.deepSleepMinutes)
        assertEquals(2, input.remSleepMinutes)
        assertEquals(5, input.lightSleepMinutes)
        assertEquals(1, input.awakeMinutes)
        assertEquals(80f, input.sleepScore)
        assertEquals(3600, input.startZoneOffsetSeconds)
        assertEquals(3600, input.endZoneOffsetSeconds)
        assertEquals("Phone", input.deviceName)

        val copy = input.copy(id = "2")
        assertEquals("2", copy.id)
        assertEquals(input, input)
        assertNotEquals(input, copy)
        assertNotNull(input.toString())
        assertNotNull(input.hashCode())
    }

    @Test
    fun testSleepStageInput() {
        val input =
            SleepStageInput(sessionId = "1", stageType = "deep", startTime = 100, endTime = 200, durationMinutes = 10)
        assertEquals("1", input.sessionId)
        assertEquals("deep", input.stageType)
        assertEquals(100, input.startTime)
        assertEquals(200, input.endTime)
        assertEquals(10, input.durationMinutes)

        val copy = input.copy(sessionId = "2")
        assertNotEquals(input, copy)
        assertNotNull(input.toString())
        assertNotNull(input.hashCode())
    }

    @Test
    fun testHeartRateInput() {
        val input =
            HeartRateInput(
                id = "1",
                timestampMs = 100,
                beatsPerMinute = 70,
                recordType = "resting",
                sessionId = "s1",
                deviceName = "Watch",
            )
        assertEquals("1", input.id)
        assertEquals(100, input.timestampMs)
        assertEquals(70, input.beatsPerMinute)
        assertEquals("resting", input.recordType)
        assertEquals("s1", input.sessionId)
        assertEquals("Watch", input.deviceName)

        val copy = input.copy(id = "2")
        assertNotEquals(input, copy)
        assertNotNull(input.toString())
        assertNotNull(input.hashCode())
    }

    @Test
    fun testBloodPressureInput() {
        val input =
            BloodPressureInput(
                id = "1",
                timestampMs = 100,
                systolicMmHg = 120,
                diastolicMmHg = 80,
                deviceName = "Monitor",
            )
        assertEquals("1", input.id)
        assertEquals(100, input.timestampMs)
        assertEquals(120, input.systolicMmHg)
        assertEquals(80, input.diastolicMmHg)
        assertEquals("Monitor", input.deviceName)

        val copy = input.copy(id = "2")
        assertNotEquals(input, copy)
        assertNotNull(input.toString())
        assertNotNull(input.hashCode())
    }

    @Test
    fun testHrvInput() {
        val input =
            HrvInput(
                id = "1",
                timestampMs = 100,
                rmssdMs = 45f,
                recordType = "r",
                sessionId = "s1",
                deviceName = "Watch",
            )
        assertEquals("1", input.id)
        assertEquals(100, input.timestampMs)
        assertEquals(45f, input.rmssdMs)
        assertEquals("r", input.recordType)
        assertEquals("s1", input.sessionId)
        assertEquals("Watch", input.deviceName)

        val copy = input.copy(id = "2")
        assertNotEquals(input, copy)
        assertNotNull(input.toString())
        assertNotNull(input.hashCode())
    }

    @Test
    fun testWorkoutInput() {
        val input =
            WorkoutInput(
                id = "1",
                startTime = 100,
                endTime = 200,
                exerciseType = "run",
                durationMinutes = 10,
                zone1Minutes = 1f,
                zone2Minutes = 2f,
                zone3Minutes = 3f,
                zone4Minutes = 4f,
                zone5Minutes = 0f,
                trimp = 15f,
                avgHr = 140f,
                deviceName = "Watch",
            )
        assertEquals("1", input.id)
        assertEquals(100, input.startTime)
        assertEquals(200, input.endTime)
        assertEquals("run", input.exerciseType)
        assertEquals(10, input.durationMinutes)
        assertEquals(1f, input.zone1Minutes)
        assertEquals(2f, input.zone2Minutes)
        assertEquals(3f, input.zone3Minutes)
        assertEquals(4f, input.zone4Minutes)
        assertEquals(0f, input.zone5Minutes)
        assertEquals(15f, input.trimp)
        assertEquals(140f, input.avgHr)
        assertEquals("Watch", input.deviceName)

        val copy = input.copy(id = "2")
        assertNotEquals(input, copy)
        assertNotNull(input.toString())
        assertNotNull(input.hashCode())
    }

    @Test
    fun testWeightInput() {
        val input = WeightInput(id = "1", timestampMs = 100, weightKg = 75f, deviceName = "Scale")
        assertEquals("1", input.id)
        assertEquals(100, input.timestampMs)
        assertEquals(75f, input.weightKg)
        assertEquals("Scale", input.deviceName)

        val copy = input.copy(id = "2")
        assertNotEquals(input, copy)
        assertNotNull(input.toString())
        assertNotNull(input.hashCode())
    }

    @Test
    fun testBodyFatInput() {
        val input = BodyFatInput(id = "1", timestampMs = 100, bodyFatPercent = 15f, deviceName = "Scale")
        assertEquals("1", input.id)
        assertEquals(100, input.timestampMs)
        assertEquals(15f, input.bodyFatPercent)
        assertEquals("Scale", input.deviceName)

        val copy = input.copy(id = "2")
        assertNotEquals(input, copy)
        assertNotNull(input.toString())
        assertNotNull(input.hashCode())
    }

    @Test
    fun testOxygenSaturationInput() {
        val input = OxygenSaturationInput(id = "1", timestampMs = 100, percentage = 98f, deviceName = "Oximeter")
        assertEquals("1", input.id)
        assertEquals(100, input.timestampMs)
        assertEquals(98f, input.percentage)
        assertEquals("Oximeter", input.deviceName)

        val copy = input.copy(id = "2")
        assertNotEquals(input, copy)
        assertNotNull(input.toString())
        assertNotNull(input.hashCode())
    }

    @Test
    fun testHealthIngestionBatch() {
        val batch =
            HealthIngestionBatch(
                sleepSessions = emptyList(),
                sleepStages = emptyList(),
                heartRateSamples = emptyList(),
                hrvSamples = emptyList(),
                workouts = emptyList(),
                weights = emptyList(),
                bodyFatSamples = emptyList(),
                bloodPressureSamples = emptyList(),
                oxygenSaturationSamples = emptyList(),
                stepRecords = emptyList(),
            )
        assertEquals(0, batch.sleepSessions.size)
        assertEquals(0, batch.sleepStages.size)
        assertEquals(0, batch.heartRateSamples.size)
        assertEquals(0, batch.hrvSamples.size)
        assertEquals(0, batch.workouts.size)
        assertEquals(0, batch.weights.size)
        assertEquals(0, batch.bodyFatSamples.size)
        assertEquals(0, batch.bloodPressureSamples.size)
        assertEquals(0, batch.oxygenSaturationSamples.size)

        val copy = batch.copy(sleepSessions = listOf(mockk()))
        assertNotEquals(batch, copy)
        assertNotNull(batch.toString())
        assertNotNull(batch.hashCode())
    }
}
