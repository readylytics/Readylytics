package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.util.logD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Retries [block] with bounded exponential backoff. Used to ride out transient Health Connect
 * rate-limit / IO failures for callers that own an independent retry scope (e.g. the Changes-token
 * path in `HealthChangeSynchronizerImpl`). Cancellation is never swallowed.
 *
 * When [budget] is supplied, retry accounting delegates to it entirely -- [policy] and [delayFn]
 * are then ignored in favor of the budget's own -- so several `retryWithBackoff` calls sharing one
 * [ReadRetryBudget] draw from the same window-scoped attempt count instead of each getting an
 * independent `maxAttempts`.
 */
internal suspend fun <T> retryWithBackoff(
    policy: HealthConnectRetryPolicy = HealthConnectRetryPolicy(),
    delayFn: suspend (Long) -> Unit = { delay(it) },
    budget: ReadRetryBudget? = null,
    block: suspend () -> T,
): T {
    if (budget != null) return budget.execute("retryWithBackoff", block)
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
