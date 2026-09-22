package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.sync.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RetryWithBackoffTest {
    @Test
    fun retriesTransientFailureThenReturnsValue() = runTest {
        var calls = 0
        val delays = mutableListOf<Long>()

        val result = retryWithBackoff(
            policy = HealthConnectRetryPolicy(maxAttempts = 3, initialDelayMs = 10, maxDelayMs = 10, jitterRatio = 0.0),
            delayFn = { delays += it },
        ) {
            calls++
            if (calls == 1) throw IOException("network")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, calls)
        assertEquals(listOf(10L), delays)
    }

    @Test
    fun neverSwallowsCancellation() = runTest {
        assertFailsWith<CancellationException> {
            retryWithBackoff { throw CancellationException("cancel") }
        }
    }

    // Regression for the maxAttempts^2 amplification this task fixes: two nested
    // retryWithBackoff invocations sharing one budget must draw from the same attempt count,
    // not each get their own independent maxAttempts.
    @Test
    fun retryWithBackoffSharedBudgetDoesNotExceedBudgetAcrossTwoNestedInvocations() = runTest {
        val budget = ReadRetryBudget(delayFn = {})
        var firstCalls = 0
        var secondCalls = 0

        runCatching {
            retryWithBackoff(budget = budget) {
                firstCalls++
                throw IOException("boom")
            }
        }
        assertFailsWith<IOException> {
            retryWithBackoff(budget = budget) {
                secondCalls++
                throw IOException("boom")
            }
        }

        // 5 total calls for the shared budget (HealthConnectRetryPolicy's default maxAttempts),
        // not 5 for the first invocation plus another independent 5 for the second (10).
        assertEquals(5, firstCalls)
        assertEquals(0, secondCalls)
        assertEquals(5, firstCalls + secondCalls)
    }
}
