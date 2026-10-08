package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.repository.ReadRetryScope
import app.readylytics.health.core.model.domain.util.logD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Window failure budget; in-flight reservations prevent concurrent reads overspending it. */
internal class ReadRetryBudget(
    private val policy: HealthConnectRetryPolicy = HealthConnectRetryPolicy(),
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) : ReadRetryScope {
    private val mutex = Mutex()
    private var inFlight = 0
    private var changed = CompletableDeferred<Unit>()
    private var lastFailure: Exception? = null

    @Volatile
    var attemptsUsed: Int = 0
        private set

    override suspend fun <T> execute(
        label: String,
        block: suspend () -> T,
    ): T {
        while (true) {
            reserveAttempt()
            val result =
                try {
                    block()
                } catch (e: CancellationException) {
                    releaseAttempt(null)
                    throw e
                } catch (e: Exception) {
                    val attempt = releaseAttempt(e)
                    if (!policy.shouldRetry(e, attempt)) throw e
                    val delayMs = policy.delayForAttempt(attempt)
                    logD("ReadRetryBudget") { "$label failed (window attempt $attempt); backing off ${delayMs}ms" }
                    delayFn(delayMs)
                    continue
                }
            releaseAttempt(null)
            return result
        }
    }

    private suspend fun reserveAttempt() {
        while (true) {
            val waiter = mutex.withLock {
                val failure = lastFailure
                if (failure != null && !policy.shouldRetry(failure, attemptsUsed)) throw failure
                if (attemptsUsed + inFlight < policy.maxAttempts) {
                    inFlight++
                    null
                } else {
                    changed
                }
            } ?: return
            waiter.await()
        }
    }

    // Release even when a sibling cancels this read while its SDK call is in flight.
    private suspend fun releaseAttempt(failure: Exception?): Int = withContext(NonCancellable) {
        mutex.withLock {
            inFlight--
            // Permission failures are translated to Denied by the repository, without
            // consuming sibling reads' transient-failure budget.
            if (failure != null && policy.shouldRetry(failure, 0)) {
                attemptsUsed++
                lastFailure = failure
            }
            changed.complete(Unit)
            changed = CompletableDeferred()
            attemptsUsed
        }
    }
}
