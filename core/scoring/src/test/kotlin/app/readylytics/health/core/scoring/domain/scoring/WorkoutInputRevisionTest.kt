package app.readylytics.health.core.scoring.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant

class WorkoutInputRevisionTest {
    private val samples = listOf(sample(0L, 120), sample(60_000L, 130))

    @Test
    fun `source order and out of range samples do not alter revision`() {
        assertEquals(revision(samples), revision(samples.reversed() + sample(120_001L, 160)))
    }

    @Test
    fun `deleting changing or duplicating HR changes revision`() {
        assertNotEquals(revision(samples), revision(emptyList()))
        assertNotEquals(revision(samples), revision(samples.drop(1)))
        assertNotEquals(revision(samples), revision(listOf(sample(0L, 121), samples.last())))
        assertNotEquals(revision(samples), revision(samples + samples.first()))
    }

    @Test
    fun `workout edits change revision with no samples`() {
        val original = revision(emptyList())
        assertNotEquals(original, WorkoutInputRevision.compute("id", 1L, 120_000L, "RUNNING", null, emptyList()))
        assertNotEquals(original, WorkoutInputRevision.compute("id", 0L, 130_000L, "RUNNING", null, emptyList()))
        assertNotEquals(original, WorkoutInputRevision.compute("id", 0L, 120_000L, "CYCLING", null, emptyList()))
        assertNotEquals(original, WorkoutInputRevision.compute("id", 0L, 120_000L, "RUNNING", "watch", emptyList()))
    }

    private fun revision(samples: List<ComputeWorkoutTrimpUseCase.HeartRateSample>) =
        WorkoutInputRevision.compute("id", 0L, 120_000L, "RUNNING", null, samples)

    private fun sample(
        time: Long,
        bpm: Int,
    ) = ComputeWorkoutTrimpUseCase.HeartRateSample(Instant.ofEpochMilli(time), bpm)
}
