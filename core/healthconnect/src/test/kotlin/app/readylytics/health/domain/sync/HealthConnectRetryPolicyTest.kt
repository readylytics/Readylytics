package app.readylytics.health.domain.sync

import java.io.IOException
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthConnectRetryPolicyTest {
    @Test
    fun retriesIoAndRateLimitFailuresOnly() {
        val policy = HealthConnectRetryPolicy()

        assertTrue(policy.shouldRetry(IOException("network"), attempt = 1))
        assertTrue(policy.shouldRetry(IllegalStateException("rate limit exceeded"), attempt = 1))
        assertFalse(policy.shouldRetry(IllegalArgumentException("bad request"), attempt = 1))
    }

    @Test
    fun stopsAtMaxAttempts() {
        val policy = HealthConnectRetryPolicy(maxAttempts = 3)

        assertTrue(policy.shouldRetry(IOException("network"), attempt = 1))
        assertTrue(policy.shouldRetry(IOException("network"), attempt = 2))
        assertFalse(policy.shouldRetry(IOException("network"), attempt = 3))
    }
}
