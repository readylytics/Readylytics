package app.readylytics.health.domain.sync

import app.readylytics.health.domain.model.Result
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public entry point for the two health-sync flows. A thin facade that owns the shared [syncMutex]
 * — so the foreground daily sync and the historical resync stay mutually serialized — and delegates
 * the actual work to [DailySyncUseCase] and [ResyncRangeUseCase].
 *
 * The two flows are deliberately separate (see `.claude/CLAUDE.md` two-flow contract): pull-to-
 * refresh is current-day-only via [sync]; the Settings "Resync Health Connect data" full historical
 * rebuild runs via [resyncRange]. Both recompute exclusively through `ScoringRepository`; no scoring
 * math lives here.
 */
@Singleton
class HealthSyncUseCase
    @Inject
    constructor(
        private val dailySyncUseCase: DailySyncUseCase,
        private val resyncRangeUseCase: ResyncRangeUseCase,
    ) {
        private val syncMutex = Mutex()

        /**
         * Runs the foreground sync / recalculation over a recent [windowDays] window.
         *
         * @param onProgress optional reactive hook invoked as the walk-forward recompute advances,
         *   reporting (completedDays, totalDays) so the UI can surface determinate progress instead
         *   of a silent spinner. Invoked off the main thread.
         */
        suspend fun sync(
            windowDays: Int = 8,
            onProgress: ((current: Int, total: Int) -> Unit)? = null,
        ): Result<Unit> =
            syncMutex.withLock {
                dailySyncUseCase.run(windowDays, onProgress)
            }

        suspend fun catchUpSync(onProgress: ((current: Int, total: Int) -> Unit)? = null): Result<Unit> =
            sync(windowDays = 365, onProgress = onProgress)

        /**
         * Full historical resync over [startDate]..[endDate] (inclusive), bounded by the caller from
         * the user's data-retention setting. See [ResyncRangeUseCase] for the chunking, checkpoint/
         * resume, and chunk-independent reconcile→walk-forward contract.
         *
         * @param onProgress reports (completed, total) across both the ingestion and recompute phases.
         */
        suspend fun resyncRange(
            startDate: LocalDate,
            endDate: LocalDate,
            chunkDays: Int = 30,
            onProgress: ((current: Int, total: Int) -> Unit)? = null,
        ): Result<Unit> =
            syncMutex.withLock {
                resyncRangeUseCase.run(startDate, endDate, chunkDays, onProgress)
            }
    }
