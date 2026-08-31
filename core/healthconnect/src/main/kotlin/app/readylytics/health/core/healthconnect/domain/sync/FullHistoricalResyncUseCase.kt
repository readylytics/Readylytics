package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.sync.*
import app.readylytics.health.core.model.domain.util.RetentionBounds
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

internal fun resolveScoringToday(
    prefs: UserPreferences,
    now: Instant,
): LocalDate = RetentionBounds.resolveHistoricalWindow(prefs, now).endDate

/**
 * Historical resync triggered from [app.readylytics.health.workers.HealthResyncWorker], covering
 * both durable entry points that share that worker's single unique WorkManager slot: the Settings
 * "Resync Health Connect data" button (full resync) and a historical-scope settings change
 * (SCORE-007's recompute-only pass, e.g. a TRIMP model/parameter or HR-zone change). Resolves how
 * far back to go from the user's data-retention setting ([RetentionBounds]) either way.
 *
 * The full-resync path delegates the heavy lifting — chunked Health Connect re-fetch +
 * walk-forward recompute — to [HealthSyncUseCase.resyncRange]; the recompute-only path skips
 * re-ingestion via [HealthSyncUseCase.recomputeRange]. Both own durable phase checkpoints so worker
 * retries can resume instead of restarting. No scoring math is altered here.
 */
@Singleton
class FullHistoricalResyncUseCase
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val healthSyncUseCase: HealthSyncUseCase,
        private val clock: Clock,
    ) {
        suspend fun execute(
            recomputeOnly: Boolean = false,
            rangeOverride: ScoreInvalidation.AffectedRange? = null,
            onProgress: ((phase: ResyncPhase, current: Int, total: Int) -> Unit)? = null,
        ): Result<Unit> {
            val prefs = settingsRepo.userPreferences.first()
            val historicalWindow = RetentionBounds.resolveHistoricalWindow(prefs, clock.instant())
            // rangeOverride only narrows a recompute-only pass -- a full resync must always cover the
            // whole retention window regardless. Clamp to the retention window in case retention
            // shrank between when the range was computed (worker enqueue time) and now (worker run
            // time).
            val startDate =
                rangeOverride?.takeIf { recomputeOnly }?.start?.coerceAtLeast(historicalWindow.startDate)
                    ?: historicalWindow.startDate
            val endDate =
                rangeOverride?.takeIf { recomputeOnly }?.endInclusive?.coerceAtMost(historicalWindow.endDate)
                    ?: historicalWindow.endDate
            return if (recomputeOnly) {
                healthSyncUseCase.recomputeRange(startDate = startDate, endDate = endDate, onProgress = onProgress)
            } else {
                healthSyncUseCase.resyncRange(
                    startDate = historicalWindow.startDate,
                    endDate = historicalWindow.endDate,
                    onProgress = onProgress,
                )
            }
        }
    }
