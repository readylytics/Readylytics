package app.readylytics.health.core.ui.common

import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NearestByTimeTest {
    private data class Sample(
        val timeMs: Long,
        val id: Int,
    )

    private fun samples(vararg times: Long) = times.mapIndexed { index, timeMs -> Sample(timeMs, index) }

    private fun List<Sample>.nearestId(targetMs: Long): Int? = nearestByTime(targetMs) { it.timeMs }?.id

    @Test
    fun `empty list has no nearest element`() {
        assertNull(emptyList<Sample>().nearestId(10L))
    }

    @Test
    fun `single element is always the nearest`() {
        val list = samples(100L)

        assertEquals(0, list.nearestId(0L))
        assertEquals(0, list.nearestId(100L))
        assertEquals(0, list.nearestId(1_000L))
    }

    @Test
    fun `target before the first or after the last element picks that end`() {
        val list = samples(10L, 20L, 30L)

        assertEquals(0, list.nearestId(-5L))
        assertEquals(2, list.nearestId(500L))
    }

    @Test
    fun `target between two elements picks the closer one`() {
        val list = samples(10L, 20L, 40L)

        assertEquals(0, list.nearestId(14L))
        assertEquals(1, list.nearestId(16L))
        assertEquals(2, list.nearestId(31L))
    }

    @Test
    fun `equidistant neighbours pick the earlier element`() {
        val list = samples(10L, 20L, 30L)

        assertEquals(0, list.nearestId(15L))
        assertEquals(1, list.nearestId(25L))
    }

    @Test
    fun `elements with equal times pick the first of them`() {
        val list = samples(10L, 10L, 10L, 20L, 20L)

        assertEquals(0, list.nearestId(10L))
        assertEquals(0, list.nearestId(12L))
        assertEquals(3, list.nearestId(20L))
        assertEquals(3, list.nearestId(19L))
        // Equidistant between a run of 10s and a run of 20s: the earlier run, first element.
        assertEquals(0, list.nearestId(15L))
    }

    @Test
    fun `matches a linear minByOrNull scan on random sorted lists`() {
        val random = Random(seed = 42)
        repeat(300) {
            val times = List(random.nextInt(1, 60)) { random.nextLong(0, 200) }.sorted()
            val list = times.mapIndexed { index, timeMs -> Sample(timeMs, index) }
            repeat(40) {
                val target = random.nextLong(-20, 220)

                val expected = list.minByOrNull { abs(it.timeMs - target) }
                assertEquals(expected, list.nearestByTime(target) { it.timeMs }, "times=$times target=$target")
            }
        }
    }
}
