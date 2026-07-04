package app.readylytics.health.domain.sync

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
}
