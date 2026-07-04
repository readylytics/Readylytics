package app.readylytics.health.domain.sync

import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.util.RetentionBounds
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Full historical resync triggered from the Settings "Resync Health Connect data" button (via
 * [app.readylytics.health.workers.HealthResyncWorker]). Resolves how far back to go from
 * the user's data-retention setting ([RetentionBounds]) and delegates the heavy lifting — chunked
 * Health Connect re-fetch + walk-forward recompute — to [HealthSyncUseCase.resyncRange]. That path
 * owns durable phase checkpoints so worker retries can resume instead of restarting. No scoring math
 * is altered.
 */
@Singleton
class FullHistoricalResyncUseCase
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val healthSyncUseCase: HealthSyncUseCase,
    ) {
        suspend fun execute(onProgress: ((current: Int, total: Int) -> Unit)? = null): Result<Unit> {
            val prefs = settingsRepo.userPreferences.first()
            val today = LocalDate.now(ZoneId.systemDefault())
            val startDate = RetentionBounds.resolveResyncStartDate(prefs, today)
            return healthSyncUseCase.resyncRange(startDate = startDate, endDate = today, onProgress = onProgress)
        }
    }
