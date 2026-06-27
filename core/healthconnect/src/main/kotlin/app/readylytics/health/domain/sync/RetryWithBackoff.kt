package app.readylytics.health.domain.sync

import app.readylytics.health.domain.util.logD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Retries [block] with bounded exponential backoff. Used to ride out transient Health Connect
 * rate-limit / IO failures during a chunked resync. Cancellation is never swallowed.
 */
internal suspend fun <T> retryWithBackoff(
    policy: HealthConnectRetryPolicy = HealthConnectRetryPolicy(),
    delayFn: suspend (Long) -> Unit = { delay(it) },
    block: suspend () -> T,
): T {
    var attempt = 1
    while (true) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!policy.shouldRetry(e, attempt)) throw e
            val delayMs = policy.delayForAttempt(attempt)
            logD("RetryWithBackoff") { "Health Connect read failed (attempt $attempt), backing off ${delayMs}ms" }
            delayFn(delayMs)
            attempt++
        }
    }
}
