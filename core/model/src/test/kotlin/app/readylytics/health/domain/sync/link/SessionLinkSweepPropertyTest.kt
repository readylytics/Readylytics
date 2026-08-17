package app.readylytics.health.domain.sync.link

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Property test: [SessionLinkSweep] must produce identical results to [SessionLinker.resolve] (the
 * reference O(sessions)-per-sample implementation) for any monotonically increasing sample sequence,
 * across randomized overlapping session configurations. This is the correctness gate for PERF-001's
 * sweep-based reconcile optimization -- [SessionLinker.resolve] is the oracle, not a duplicate to be
 * removed.
 */
class SessionLinkSweepPropertyTest {
    @Test
    fun `sweep matches SessionLinker resolve across randomized overlapping spans`() {
        val random = Random(SEED)
        repeat(CONFIGURATIONS) { configIndex ->
            val sleepSpans = randomSpans(random, prefix = "sleep_$configIndex")
            val workoutSpans = randomSpans(random, prefix = "workout_$configIndex")
            val samples = randomMonotonicSamples(random)

            val sweep = SessionLinkSweep(sleepSpans, workoutSpans)
            for (sampleMs in samples) {
                val expected = SessionLinker.resolve(sampleMs, sleepSpans, workoutSpans)
                val actual = sweep.resolve(sampleMs)
                assertEquals(
                    "mismatch at sampleMs=$sampleMs with sleep=$sleepSpans workout=$workoutSpans",
                    expected,
                    actual,
                )
            }
        }
    }

    @Test
    fun `sweep matches SessionLinker resolve with no sessions at all`() {
        val sweep = SessionLinkSweep(emptyList(), emptyList())
        for (sampleMs in listOf(0L, 1L, 1_000_000L)) {
            assertEquals(SessionLinker.resolve(sampleMs, emptyList(), emptyList()), sweep.resolve(sampleMs))
        }
    }

    @Test
    fun `sweep matches SessionLinker resolve with deeply nested overlapping spans`() {
        // Adversarial case for the sweep's active-window pruning: a long-running outer span plus
        // several short-lived inner spans that start after and expire before it, in interleaved
        // order -- exercises pruning of spans that aren't at the front of the active window.
        val outer = SessionSpan(id = "sleep_outer", startTime = 0L, endTime = 100_000L)
        val innerA = SessionSpan(id = "sleep_inner_a", startTime = 100L, endTime = 200L)
        val innerB = SessionSpan(id = "sleep_inner_b", startTime = 300L, endTime = 400L)
        val sleepSpans = listOf(outer, innerA, innerB)

        val sweep = SessionLinkSweep(sleepSpans, emptyList())
        for (sampleMs in listOf(50L, 150L, 250L, 350L, 500L, 100_000L, 100_001L)) {
            assertEquals(
                "mismatch at sampleMs=$sampleMs",
                SessionLinker.resolve(sampleMs, sleepSpans, emptyList()),
                sweep.resolve(sampleMs),
            )
        }
    }

    private fun randomSpans(
        random: Random,
        prefix: String,
    ): List<SessionSpan> {
        val count = random.nextInt(0, MAX_SPANS_PER_TYPE + 1)
        return (0 until count).map { i ->
            val start = random.nextLong(0L, TIME_RANGE_MS)
            val end = start + random.nextLong(1L, MAX_SPAN_DURATION_MS)
            SessionSpan(id = "${prefix}_$i", startTime = start, endTime = end)
        }
    }

    private fun randomMonotonicSamples(random: Random): List<Long> {
        var t = 0L
        return (0 until SAMPLES_PER_CONFIGURATION).map {
            t += random.nextLong(0L, MAX_SAMPLE_STEP_MS)
            t
        }
    }

    private companion object {
        const val SEED = 20260720L
        const val CONFIGURATIONS = 200
        const val SAMPLES_PER_CONFIGURATION = 500
        const val MAX_SPANS_PER_TYPE = 8
        const val TIME_RANGE_MS = 200_000L
        const val MAX_SPAN_DURATION_MS = 20_000L
        const val MAX_SAMPLE_STEP_MS = 500L
    }
}
