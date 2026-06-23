package app.readylytics.health.domain.sync

import app.readylytics.health.domain.util.logD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Retries [block] with bounded exponential backoff. Used to ride out transient Health Connect
 * rate-limit / IO failures during a chunked resync. Cancellation is never swallowed.
 */
internal suspend fun <T> retryWithBackoff(
    maxAttempts: Int = 4,
    initialDelayMs: Long = 1_000,
    block: suspend () -> T,
): T {
    var attempt = 0
    var delayMs = initialDelayMs
    while (true) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            attempt++
            if (attempt >= maxAttempts) throw e
            logD("RetryWithBackoff") { "Operation failed (attempt $attempt), backing off ${delayMs}ms" }
            delay(delayMs)
            delayMs *= 2
        }
    }
}
