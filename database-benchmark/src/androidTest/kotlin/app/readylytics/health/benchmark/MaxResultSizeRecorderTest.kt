package app.readylytics.health.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deliberately not `@LargeTest`: this is fast and carries no timing sensitivity, so it runs in the
 * routine `connectedDebugAndroidTest` sweep and gates PRs like any other correctness test.
 */
@RunWith(AndroidJUnit4::class)
class MaxResultSizeRecorderTest {
    @Test
    fun recordsLargestResultAndItsLabel() =
        runBlocking {
            val recorder = MaxResultSizeRecorder()
            recorder.record("small") { List(3) { it } }
            recorder.record("large") { List(41) { it } }
            recorder.record("medium") { List(7) { it } }

            assertEquals(41, recorder.maxSize)
            assertEquals(51L, recorder.totalRows)
            assertEquals("large", recorder.largestLabel)
        }

    /**
     * Review Focus 4: statement count and result-set size measure different things. Three calls
     * returning 3, 41 and 7 rows are three statements but a 41-row maximum — a fixture where the two
     * instruments MUST disagree, so a future change cannot quietly replace one with the other.
     */
    @Test
    fun statementCountAndResultSizeAreDifferentInstruments() =
        runBlocking {
            val recorder = MaxResultSizeRecorder()
            val callback = CountingQueryCallback()
            repeat(3) { callback.onQuery("SELECT 1", emptyList()) }
            recorder.record("a") { List(3) { it } }
            recorder.record("b") { List(41) { it } }
            recorder.record("c") { List(7) { it } }

            assertEquals(3L, callback.statementCount)
            assertEquals(41, recorder.maxSize)
            assertNotEquals(callback.statementCount, recorder.maxSize.toLong())
        }

    @Test
    fun resetClearsAllState() =
        runBlocking {
            val recorder = MaxResultSizeRecorder()
            recorder.record("x") { List(9) { it } }
            recorder.reset()
            assertEquals(0, recorder.maxSize)
            assertEquals(0L, recorder.totalRows)
            assertEquals(null, recorder.largestLabel)
        }
}
