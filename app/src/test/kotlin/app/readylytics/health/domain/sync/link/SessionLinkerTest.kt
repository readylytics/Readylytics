package app.readylytics.health.domain.sync.link

import app.readylytics.health.domain.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionLinkerTest {
    @Test
    fun `sample inside sleep span links to SLEEP and session id`() {
        val sleep = SessionSpan(id = "sleep_1", startTime = 1_000L, endTime = 2_000L)

        val link = SessionLinker.resolve(1_500L, listOf(sleep), emptyList())

        assertEquals(RecordType.SLEEP.name, link.recordType)
        assertEquals("sleep_1", link.sessionId)
    }

    @Test
    fun `sample inside workout span links to EXERCISE and session id`() {
        val workout = SessionSpan(id = "workout_1", startTime = 1_000L, endTime = 2_000L)

        val link = SessionLinker.resolve(1_500L, emptyList(), listOf(workout))

        assertEquals(RecordType.EXERCISE.name, link.recordType)
        assertEquals("workout_1", link.sessionId)
    }

    @Test
    fun `sample outside all spans links to RESTING with no session id`() {
        val sleep = SessionSpan(id = "sleep_1", startTime = 1_000L, endTime = 2_000L)
        val workout = SessionSpan(id = "workout_1", startTime = 3_000L, endTime = 4_000L)

        val link = SessionLinker.resolve(2_500L, listOf(sleep), listOf(workout))

        assertEquals(RecordType.RESTING.name, link.recordType)
        assertEquals(null, link.sessionId)
    }

    @Test
    fun `sleep takes precedence over workout when both overlap`() {
        val sleep = SessionSpan(id = "sleep_1", startTime = 1_000L, endTime = 2_000L)
        val workout = SessionSpan(id = "workout_1", startTime = 1_000L, endTime = 2_000L)

        val link = SessionLinker.resolve(1_500L, listOf(sleep), listOf(workout))

        assertEquals(RecordType.SLEEP.name, link.recordType)
        assertEquals("sleep_1", link.sessionId)
    }

    @Test
    fun `overlapping sleep sessions tiebreak by earliest start then id`() {
        val later = SessionSpan(id = "sleep_b", startTime = 1_500L, endTime = 3_000L)
        val earlier = SessionSpan(id = "sleep_a", startTime = 1_000L, endTime = 2_500L)

        val link = SessionLinker.resolve(2_000L, listOf(later, earlier), emptyList())

        assertEquals("sleep_a", link.sessionId)
    }

    @Test
    fun `overlapping sleep sessions with same start tiebreak by id`() {
        val sessionB = SessionSpan(id = "sleep_b", startTime = 1_000L, endTime = 2_000L)
        val sessionA = SessionSpan(id = "sleep_a", startTime = 1_000L, endTime = 2_000L)

        val link = SessionLinker.resolve(1_500L, listOf(sessionB, sessionA), emptyList())

        assertEquals("sleep_a", link.sessionId)
    }

    @Test
    fun `sample exactly at session start time is contained`() {
        val sleep = SessionSpan(id = "sleep_1", startTime = 1_000L, endTime = 2_000L)

        val link = SessionLinker.resolve(1_000L, listOf(sleep), emptyList())

        assertEquals(RecordType.SLEEP.name, link.recordType)
        assertEquals("sleep_1", link.sessionId)
    }

    @Test
    fun `sample exactly at session end time is contained`() {
        val sleep = SessionSpan(id = "sleep_1", startTime = 1_000L, endTime = 2_000L)

        val link = SessionLinker.resolve(2_000L, listOf(sleep), emptyList())

        assertEquals(RecordType.SLEEP.name, link.recordType)
        assertEquals("sleep_1", link.sessionId)
    }

    @Test
    fun `sample one millisecond past session end is not contained`() {
        val sleep = SessionSpan(id = "sleep_1", startTime = 1_000L, endTime = 2_000L)

        val link = SessionLinker.resolve(2_001L, listOf(sleep), emptyList())

        assertEquals(RecordType.RESTING.name, link.recordType)
        assertEquals(null, link.sessionId)
    }

    @Test
    fun `resolve is deterministic across repeated calls with same input`() {
        val sleep = SessionSpan(id = "sleep_1", startTime = 1_000L, endTime = 2_000L)
        val workout = SessionSpan(id = "workout_1", startTime = 5_000L, endTime = 6_000L)

        val first = SessionLinker.resolve(1_500L, listOf(sleep), listOf(workout))
        val second = SessionLinker.resolve(1_500L, listOf(sleep), listOf(workout))

        assertEquals(first, second)
    }
}
