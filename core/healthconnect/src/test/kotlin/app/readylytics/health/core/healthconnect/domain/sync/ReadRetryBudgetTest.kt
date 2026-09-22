package app.readylytics.health.core.healthconnect.domain.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

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
                runBlocking {
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
                runBlocking {
                    budget.execute("read-1") {
                        calls++
                        error("not transient")
                    }
                }
            }
            assertEquals(1, calls)
        }

    // Regression for a review finding on this task: HealthIngestionCoordinator.fetchBulkRecords
    // shares one ReadRetryBudget across 9 concurrent `async` reads (each internally hopping onto an
    // IO dispatcher via `withContext`), so their catch blocks can genuinely run on different OS
    // threads at once. runTest's virtual-time dispatcher is single-threaded and cannot reproduce a
    // real data race, so this uses a real multi-threaded dispatcher (Dispatchers.Default) instead.
    // Every launched read always fails, so attemptsUsed must end up exactly equal to the externally
    // (atomically) counted number of failed block() invocations -- a racing, non-atomic
    // `attemptsUsed++` would lose updates under contention and leave attemptsUsed strictly lower
    // than the true failure count, which would in turn let shouldRetry keep permitting more Health
    // Connect calls than the policy intends.
    @Test
    fun attemptsUsedNeverLosesAConcurrentUpdateAcrossRealThreads() {
        val budget = ReadRetryBudget(delayFn = {})
        val totalFailedCalls = AtomicInteger(0)

        runBlocking(Dispatchers.Default) {
            coroutineScope {
                repeat(CONCURRENT_READS) { i ->
                    launch {
                        runCatching {
                            budget.execute("read-$i") {
                                totalFailedCalls.incrementAndGet()
                                throw IOException("boom")
                            }
                        }
                    }
                }
            }
        }

        assertEquals(totalFailedCalls.get(), budget.attemptsUsed)
    }

    companion object {
        private const val CONCURRENT_READS = 50
    }
}
