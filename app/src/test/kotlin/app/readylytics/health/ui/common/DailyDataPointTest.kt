package app.readylytics.health.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-level guard for the trend-chart data model's Compose-stability contract.
 *
 * [DailyDataPoint] is `@Immutable` and relied upon — via *structural* equality — so that a chart
 * fed a re-emitted-but-equal dataset skips recomposition. These pure-JVM assertions fail fast if a
 * regression breaks that structural equality (e.g. dropping `data class`, or adding a
 * non-constructor / mutable field), and they exercise [padToRange]'s gap-filling logic and
 * boundaries. The device-level recomposition-count test lives in `androidTest`; this is the
 * guard that CI's `testDebugUnitTest` actually runs.
 */
class DailyDataPointTest {
    @Test
    fun `structurally equal points are equal (recomposition-skip contract)`() {
        assertEquals(DailyDataPoint(dayOffset = 3, value = 12.5f), DailyDataPoint(dayOffset = 3, value = 12.5f))
        val point = DailyDataPoint(dayOffset = 7, value = null)
        assertEquals(point, point.copy())
        assertEquals(point.hashCode(), point.copy().hashCode())
    }

    @Test
    fun `points differing in any field are not equal`() {
        assertNotEquals(DailyDataPoint(3, 12.5f), DailyDataPoint(4, 12.5f))
        assertNotEquals(DailyDataPoint(3, 12.5f), DailyDataPoint(3, 12.6f))
        assertNotEquals(DailyDataPoint(3, 1f), DailyDataPoint(3, null))
    }

    @Test
    fun `padToRange fills missing offsets with null-value points in ascending order`() {
        val sparse = listOf(DailyDataPoint(0, 10f), DailyDataPoint(2, 30f))

        val padded = sparse.padToRange(days = 4)

        assertEquals(4, padded.size)
        assertEquals(listOf(0, 1, 2, 3), padded.map { it.dayOffset })
        assertEquals(10f, padded[0].value)
        assertNull(padded[1].value)
        assertEquals(30f, padded[2].value)
        assertNull(padded[3].value)
    }

    @Test
    fun `padToRange on empty input yields an all-null range`() {
        val padded = emptyList<DailyDataPoint>().padToRange(days = 3)

        assertEquals(listOf(0, 1, 2), padded.map { it.dayOffset })
        assertTrue(padded.all { it.value == null })
    }

    @Test
    fun `padToRange drops offsets outside the range and zero days yields empty`() {
        // Offset 5 is outside 0 until 3, so it is not carried into the padded range.
        val padded = listOf(DailyDataPoint(1, 5f), DailyDataPoint(5, 99f)).padToRange(days = 3)
        assertEquals(listOf(0, 1, 2), padded.map { it.dayOffset })
        assertEquals(5f, padded[1].value)

        assertEquals(emptyList<DailyDataPoint>(), listOf(DailyDataPoint(0, 1f)).padToRange(days = 0))
    }
}
