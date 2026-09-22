package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.util.logD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 *
 * `HealthIngestionCoordinator.fetchBulkRecords` shares one [ReadRetryBudget] instance across its 9
 * bulk reads, which run concurrently as sibling `async` children of one `coroutineScope`; each
 * `hcRepo.readXxx(...)` call internally hops onto an IO dispatcher, so their catch blocks can
 * genuinely execute on different OS threads at the same time. [attemptsUsed] and [lastFailure] are
 * therefore mutated under [mutex] -- a bare `AtomicInteger`/`AtomicReference` pair would still race
 * between the two updates, since a "check budget, then update both fields" step must be atomic as a
 * unit, not just each field individually.
 */
internal class ReadRetryBudget(
    private val policy: HealthConnectRetryPolicy = HealthConnectRetryPolicy(),
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()

    @Volatile
    var attemptsUsed: Int = 0
        private set

    @Volatile
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
                val delayMs = recordFailureOrRethrow(label, e)
                delayFn(delayMs)
            }
        }
    }

    /**
     * If a prior read in this window already spent the shared budget, replay that failure for
     * [label] immediately instead of letting this read pay for one more doomed attempt. Guarded by
     * [mutex] so this check-and-throw is atomic with respect to [recordFailureOrRethrow]'s
     * concurrent state updates from other reads.
     */
    private suspend fun rethrowIfBudgetAlreadySpent(label: String) {
        val priorFailure =
            mutex.withLock {
                lastFailure?.takeUnless { policy.shouldRetry(it, attemptsUsed) }
            } ?: return
        logD("ReadRetryBudget") { "$label skipped; window retry budget already spent" }
        throw priorFailure
    }

    /**
     * Atomically records [e] as this window's latest failure and returns the backoff delay for the
     * next attempt, or rethrows [e] immediately once the shared budget is exhausted. The
     * increment-then-decide step runs under [mutex] because concurrently-failing sibling reads share
     * this same counter -- a racing, unsynchronized `attemptsUsed++` could lose an update and let
     * the real total number of Health Connect calls exceed the policy's intended cap.
     */
    private suspend fun recordFailureOrRethrow(
        label: String,
        e: Exception,
    ): Long {
        val delayMs =
            mutex.withLock {
                attemptsUsed++
                lastFailure = e
                if (policy.shouldRetry(e, attemptsUsed)) policy.delayForAttempt(attemptsUsed) else null
            } ?: throw e
        logD("ReadRetryBudget") { "$label failed (window attempt $attemptsUsed); backing off ${delayMs}ms" }
        return delayMs
    }
}
