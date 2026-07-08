package app.readylytics.health.domain.sync

import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.preferences.scoringZone
import app.readylytics.health.domain.util.logD
import kotlinx.coroutines.flow.first
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
        private val settingsRepo: SettingsRepository,
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
            syncMutex.withLock {
                val prefs = settingsRepo.userPreferences.first()
                if (prefs.lastSyncTimestamp > 0L) {
                    logD("HealthSyncUseCase") {
                        "Catch-up sync skipped: lastSyncTimestamp is already set (${prefs.lastSyncTimestamp})"
                    }
                    return@withLock Result.success(Unit)
                }
                val zoneId = prefs.scoringZone()
                val today = LocalDate.now(zoneId)
                val startDate = today.minusDays(365)
                resyncRangeUseCase.run(
                    startDate = startDate,
                    endDate = today,
                    chunkDays = 30,
                    onProgress = onProgress,
                )
            }

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
