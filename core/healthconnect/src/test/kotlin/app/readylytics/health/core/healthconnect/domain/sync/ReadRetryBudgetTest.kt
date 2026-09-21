package app.readylytics.health.core.healthconnect.domain.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ReadRetryBudgetTest {
    @Test
    fun attemptsAreSharedAcrossEveryReadInOneWindow() =
        runTest {
            val budget = ReadRetryBudget(delayFn = {})
            var firstReadCalls = 0
            var secondReadCalls = 0

            // Read 1 is continuously transient-failing: it alone exhausts the whole window's
            // 5-attempt budget (HealthConnectRetryPolicy's default maxAttempts), so it makes exactly
            // 5 calls before the budget gives up on it.
            runCatching {
                budget.execute("read-1") {
                    firstReadCalls++
                    throw IOException("boom")
                }
            }
            assertEquals(5, firstReadCalls)

            // Read 2 starts with the window's budget already spent. A per-read budget would give it
            // a fresh 5 attempts (10 total); the shared window budget instead replays read-1's last
            // failure immediately, without calling read-2's block at all.
            assertThrows(IOException::class.java) {
                kotlinx.coroutines.runBlocking {
                    budget.execute("read-2") {
                        secondReadCalls++
                        throw IOException("boom")
                    }
                }
            }
            assertEquals(0, secondReadCalls)

            // 5 attempts total for the window, not 5 per read.
            assertEquals(5, firstReadCalls + secondReadCalls)
        }

    @Test
    fun aSuccessfulReadConsumesOnlyItsOwnFailedAttempts() =
        runTest {
            val budget = ReadRetryBudget(delayFn = {})
            var calls = 0

            val result =
                budget.execute("read-1") {
                    calls++
                    if (calls < 3) throw IOException("boom") else "ok"
                }

            assertEquals("ok", result)
            assertEquals(3, calls)
            assertEquals(2, budget.attemptsUsed)
        }

    @Test
    fun nonTransientFailuresAreNotRetried() =
        runTest {
            val budget = ReadRetryBudget(delayFn = {})
            var calls = 0

            assertThrows(IllegalStateException::class.java) {
                kotlinx.coroutines.runBlocking {
                    budget.execute("read-1") {
                        calls++
                        error("not transient")
                    }
                }
            }
            assertEquals(1, calls)
        }
}
