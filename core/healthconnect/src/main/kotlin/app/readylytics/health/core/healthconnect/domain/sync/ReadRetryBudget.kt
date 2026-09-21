package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.util.logD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * PERF-001/HC-005: one bounded retry budget for every Health Connect read of one ingest window.
 * Before this, `HistoricalIngestPhase` wrapped `ingestWindow` in `retryWithBackoff` while
 * `fetchBulkRecords` wrapped each of its nine reads in another, so a provider under quota pressure
 * could be hit up to `maxAttempts^2` times for one window. Attempts are counted per window and
 * shared by every read inside it; the outer retry belongs to WorkManager's EXPONENTIAL backoff,
 * which is durable, observable and already configured.
 *
 * Once the window's shared attempt count is spent, the failure that spent it is remembered
 * ([lastFailure]) and replayed immediately -- without invoking [block] -- for every subsequent
 * [execute] call in the same window; a window whose budget is gone stops making Health Connect
 * calls entirely rather than letting each remaining read pay for one more doomed attempt.
 */
internal class ReadRetryBudget(
    private val policy: HealthConnectRetryPolicy = HealthConnectRetryPolicy(),
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {
    var attemptsUsed: Int = 0
        private set
    private var lastFailure: Exception? = null

    suspend fun <T> execute(
        label: String,
        block: suspend () -> T,
    ): T {
        rethrowIfBudgetAlreadySpent(label)
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attemptsUsed++
                lastFailure = e
                if (!policy.shouldRetry(e, attemptsUsed)) throw e
                val delayMs = policy.delayForAttempt(attemptsUsed)
                logD("ReadRetryBudget") { "$label failed (window attempt $attemptsUsed); backing off ${delayMs}ms" }
                delayFn(delayMs)
            }
        }
    }

    /**
     * If a prior read in this window already spent the shared budget, replay that failure for
     * [label] immediately instead of letting this read pay for one more doomed attempt.
     */
    private fun rethrowIfBudgetAlreadySpent(label: String) {
        val priorFailure = lastFailure ?: return
        if (policy.shouldRetry(priorFailure, attemptsUsed)) return
        logD("ReadRetryBudget") { "$label skipped; window retry budget already spent" }
        throw priorFailure
    }
}
