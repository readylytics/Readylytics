package app.readylytics.health.core.model.domain.sync.mappers

import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord
import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import app.readylytics.health.core.model.domain.sync.SleepSessionInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class HeartRateMapperTest {
    private val sleepStartMs = Instant.parse("2026-05-09T22:00:00Z").toEpochMilli()
    private val sleepEndMs = Instant.parse("2026-05-10T06:00:00Z").toEpochMilli()
    private val sleepSession =
        SleepSessionInput(
            id = "sleep_1",
            startTime = sleepStartMs,
            endTime = sleepEndMs,
            durationMinutes = 480,
            efficiency = 90.0f,
            deepSleepMinutes = 90,
            remSleepMinutes = 120,
            lightSleepMinutes = 240,
            awakeMinutes = 30,
            sleepScore = null,
            startZoneOffsetSeconds = null,
            endZoneOffsetSeconds = null,
            deviceName = "Watch",
        )

    // --- mapToInputs ---

    @Test
    fun `mapToInputs handles empty samples list gracefully`() {
        val record =
            DomainHeartRateRecord(
                id = "rec_empty",
                deviceName = "Watch",
                samples = emptyList(),
            )

        val result = HeartRateMapper.mapToInputs(listOf(record), emptyList(), emptyList())

        assertEquals(1, result.size)
        assertEquals("rec_empty", result[0].source.sourceId)
        assertEquals(0, result[0].rows.size)
    }

    @Test
    fun `mapToInputs handles out-of-order records so both samples classified as SLEEP`() {
        // Samsung delivers records out of chronological order.
        // sample2Time is earlier than sample1Time, but record with sample1 arrives first.
        val sample1Time = Instant.parse("2026-05-10T03:00:00Z")
        val sample2Time = Instant.parse("2026-05-10T01:00:00Z")

        val sample1 = DomainHeartRateSample(time = sample1Time, beatsPerMinute = 60)
        val sample2 = DomainHeartRateSample(time = sample2Time, beatsPerMinute = 65)
        val recordA =
            DomainHeartRateRecord(
                id = "recA",
                deviceName = "Watch",
                samples = listOf(sample1),
            )
        val recordB =
            DomainHeartRateRecord(
                id = "recB",
                deviceName = "Watch",
                samples = listOf(sample2),
            )

        val result = HeartRateMapper.mapToInputs(listOf(recordA, recordB), listOf(sleepSession), emptyList())

        assertEquals(2, result.size)
        val rows = result.flatMap { it.rows }
        assertEquals(2, rows.size)
        val byTs = rows.associateBy { it.timestampMs }
        assertEquals("SLEEP", byTs[sample1Time.toEpochMilli()]?.recordType)
        assertEquals("SLEEP", byTs[sample2Time.toEpochMilli()]?.recordType)
    }

    @Test
    fun `mapToInputs handles out-of-order records across distinct sessions`() {
        val s1Start = Instant.parse("2026-05-10T00:00:00Z").toEpochMilli()
        val s1End = Instant.parse("2026-05-10T02:00:00Z").toEpochMilli()
        val s2Start = Instant.parse("2026-05-10T02:30:00Z").toEpochMilli()
        val s2End = Instant.parse("2026-05-10T04:00:00Z").toEpochMilli()

        val session1 = sleepSession.copy(id = "sleep_early", startTime = s1Start, endTime = s1End)
        val session2 = sleepSession.copy(id = "sleep_late", startTime = s2Start, endTime = s2End)

        val tLater = Instant.parse("2026-05-10T03:00:00Z")
        val tEarlier = Instant.parse("2026-05-10T01:00:00Z")

        val recordLater =
            DomainHeartRateRecord(
                id = "rec_later",
                deviceName = "Watch",
                samples = listOf(DomainHeartRateSample(time = tLater, beatsPerMinute = 60)),
            )
        val recordEarlier =
            DomainHeartRateRecord(
                id = "rec_earlier",
                deviceName = "Watch",
                samples = listOf(DomainHeartRateSample(time = tEarlier, beatsPerMinute = 65)),
            )

        val result =
            HeartRateMapper.mapToInputs(
                listOf(recordLater, recordEarlier),
                listOf(session1, session2),
                emptyList(),
            )

        val rows = result.flatMap { it.rows }.associateBy { it.timestampMs }
        assertEquals("sleep_late", rows[tLater.toEpochMilli()]?.sessionId)
        assertEquals("sleep_early", rows[tEarlier.toEpochMilli()]?.sessionId)
    }

    @Test
    fun `mapToInputs handles out-of-order samples within a single record across distinct sessions`() {
        val s1Start = Instant.parse("2026-05-10T00:00:00Z").toEpochMilli()
        val s1End = Instant.parse("2026-05-10T02:00:00Z").toEpochMilli()
        val s2Start = Instant.parse("2026-05-10T02:30:00Z").toEpochMilli()
        val s2End = Instant.parse("2026-05-10T04:00:00Z").toEpochMilli()

        val session1 = sleepSession.copy(id = "sleep_early", startTime = s1Start, endTime = s1End)
        val session2 = sleepSession.copy(id = "sleep_late", startTime = s2Start, endTime = s2End)

        val tLater = Instant.parse("2026-05-10T03:00:00Z")
        val tEarlier = Instant.parse("2026-05-10T01:00:00Z")

        val record =
            DomainHeartRateRecord(
                id = "rec_unordered_samples",
                deviceName = "Watch",
                samples =
                    listOf(
                        DomainHeartRateSample(time = tLater, beatsPerMinute = 60),
                        DomainHeartRateSample(time = tEarlier, beatsPerMinute = 65),
                    ),
            )

        val result =
            HeartRateMapper.mapToInputs(
                listOf(record),
                listOf(session1, session2),
                emptyList(),
            )

        val rows = result.flatMap { it.rows }.associateBy { it.timestampMs }
        assertEquals("sleep_late", rows[tLater.toEpochMilli()]?.sessionId)
        assertEquals("sleep_early", rows[tEarlier.toEpochMilli()]?.sessionId)
    }

    @Test
    fun `mapToInputs generates unique IDs per sample using record id and timestamp`() {
        val t1 = Instant.parse("2026-05-10T02:00:00Z")
        val t2 = Instant.parse("2026-05-10T02:01:00Z")
        val s1 = DomainHeartRateSample(time = t1, beatsPerMinute = 60)
        val s2 = DomainHeartRateSample(time = t2, beatsPerMinute = 62)
        val record =
            DomainHeartRateRecord(
                id = "rec_1",
                deviceName = "Watch",
                samples = listOf(s1, s2),
            )

        val result = HeartRateMapper.mapToInputs(listOf(record), emptyList(), emptyList())

        assertEquals(1, result.size)
        val rows = result[0].rows
        assertEquals(2, rows.size)
        val ids = rows.map { it.id }.toSet()
        assertEquals(setOf("rec_1_${t1.toEpochMilli()}", "rec_1_${t2.toEpochMilli()}"), ids)
    }

    @Test
    fun `mapToInputs classifies sample outside any session as RESTING`() {
        val ts = Instant.parse("2026-05-09T14:00:00Z")
        val sample = DomainHeartRateSample(time = ts, beatsPerMinute = 72)
        val record =
            DomainHeartRateRecord(
                id = "rec_r",
                deviceName = "Watch",
                samples = listOf(sample),
            )

        val result = HeartRateMapper.mapToInputs(listOf(record), listOf(sleepSession), emptyList())

        assertEquals(1, result.size)
        val rows = result[0].rows
        assertEquals(1, rows.size)
        assertEquals("RESTING", rows[0].recordType)
        assertNull(rows[0].sessionId)
    }

    @Test
    fun `mapToInputs classifies sample inside workout as EXERCISE`() {
        val workoutStartMs = Instant.parse("2026-05-10T10:00:00Z").toEpochMilli()
        val workoutEndMs = Instant.parse("2026-05-10T11:00:00Z").toEpochMilli()
        val workoutSession =
            app.readylytics.health.core.model.domain.sync.WorkoutInput(
                id = "workout_1",
                startTime = workoutStartMs,
                endTime = workoutEndMs,
                exerciseType = "RUNNING",
                durationMinutes = 60,
                zone1Minutes = 0f,
                zone2Minutes = 0f,
                zone3Minutes = 0f,
                zone4Minutes = 0f,
                zone5Minutes = 0f,
                trimp = 80.0f,
                avgHr = 150f,
                deviceName = "Watch",
            )

        val ts = Instant.parse("2026-05-10T10:30:00Z")
        val sample = DomainHeartRateSample(time = ts, beatsPerMinute = 155)
        val record =
            DomainHeartRateRecord(
                id = "rec_w",
                deviceName = "Watch",
                samples = listOf(sample),
            )

        val result = HeartRateMapper.mapToInputs(listOf(record), emptyList(), listOf(workoutSession))

        assertEquals(1, result.size)
        val rows = result[0].rows
        assertEquals(1, rows.size)
        assertEquals("EXERCISE", rows[0].recordType)
        assertEquals("workout_1", rows[0].sessionId)
    }

    @Test
    fun `mapToInputs prioritizes SLEEP over WORKOUT when timestamps overlap`() {
        // Sleep session: 22:00 to 06:00
        // Overlapping workout: 05:00 to 06:00
        val workoutStartMs = Instant.parse("2026-05-10T05:00:00Z").toEpochMilli()
        val workoutEndMs = Instant.parse("2026-05-10T06:00:00Z").toEpochMilli()
        val workoutSession =
            app.readylytics.health.core.model.domain.sync.WorkoutInput(
                id = "workout_overlap",
                startTime = workoutStartMs,
                endTime = workoutEndMs,
                exerciseType = "RUNNING",
                durationMinutes = 60,
                zone1Minutes = 0f,
                zone2Minutes = 0f,
                zone3Minutes = 0f,
                zone4Minutes = 0f,
                zone5Minutes = 0f,
                trimp = 50.0f,
                avgHr = 130f,
                deviceName = "Watch",
            )

        val ts = Instant.parse("2026-05-10T05:30:00Z")
        val sample = DomainHeartRateSample(time = ts, beatsPerMinute = 80)
        val record =
            DomainHeartRateRecord(
                id = "rec_overlap",
                deviceName = "Watch",
                samples = listOf(sample),
            )

        val result = HeartRateMapper.mapToInputs(listOf(record), listOf(sleepSession), listOf(workoutSession))

        assertEquals(1, result.size)
        val rows = result[0].rows
        assertEquals(1, rows.size)
        assertEquals("SLEEP", rows[0].recordType)
        assertEquals("sleep_1", rows[0].sessionId)
    }
}


