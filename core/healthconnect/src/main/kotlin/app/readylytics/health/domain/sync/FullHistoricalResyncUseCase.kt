package app.readylytics.health.domain.sync

import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.preferences.scoringZone
import app.readylytics.health.domain.util.RetentionBounds
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

internal fun resolveScoringToday(
    prefs: UserPreferences,
    now: Instant = Instant.now(),
): LocalDate = now.atZone(prefs.scoringZone()).toLocalDate()

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
    ) {
        suspend fun execute(
            recomputeOnly: Boolean = false,
            onProgress: ((phase: ResyncPhase, current: Int, total: Int) -> Unit)? = null,
        ): Result<Unit> {
            val prefs = settingsRepo.userPreferences.first()
            val today = resolveScoringToday(prefs)
            val startDate = RetentionBounds.resolveResyncStartDate(prefs, today)
            return if (recomputeOnly) {
                healthSyncUseCase.recomputeRange(startDate = startDate, endDate = today, onProgress = onProgress)
            } else {
                healthSyncUseCase.resyncRange(startDate = startDate, endDate = today, onProgress = onProgress)
            }
        }
    }
