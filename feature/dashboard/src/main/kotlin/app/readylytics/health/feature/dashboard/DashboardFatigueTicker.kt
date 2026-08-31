package app.readylytics.health.feature.dashboard

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emits the current minute bucket, re-triggering the dashboard's card build so live residual
 * fatigue keeps decaying while the user is looking at it.
 *
 * Without a tick source the dashboard's `combine` only re-runs when one of its data flows emits, so
 * a dashboard left open with no incoming sync would keep displaying the value captured when it was
 * opened. The emitted bucket also feeds `FatigueCacheKey`, so the high-frequency data flows can
 * still be served from the memo instead of re-running the unbounded workout scan.
 *
 * Injected rather than inlined so tests can substitute a finite flow: an unbounded `delay` loop
 * never lets `StandardTestDispatcher` reach idle, which would hang every `advanceUntilIdle()`.
 *
 * The flow is cold and unbounded; the dashboard's `SharingStarted.WhileSubscribed` stops it shortly
 * after the UI goes away and restarts it — with a fresh bucket — on resubscribe.
 */
@Singleton
class DashboardFatigueTicker
    @Inject
    constructor(
        private val clock: Clock,
    ) {
        fun minuteBuckets(): Flow<Long> =
            flow {
                while (true) {
                    emit(clock.millis() / BUCKET_MILLIS)
                    delay(TICK_MILLIS)
                }
            }

        private companion object {
            const val BUCKET_MILLIS = 60_000L
            const val TICK_MILLIS = 60_000L
        }
    }
