package com.gregor.lauritz.healthdashboard.domain.sync

import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.model.Result
import com.gregor.lauritz.healthdashboard.domain.util.RetentionBounds
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Full historical resync triggered from the Settings "Resync Health Connect data" button (via
 * [com.gregor.lauritz.healthdashboard.workers.HealthResyncWorker]). Resolves how far back to go from
 * the user's data-retention setting ([RetentionBounds]) and delegates the heavy lifting — chunked
 * Health Connect re-fetch + walk-forward recompute — to [HealthSyncUseCase.resyncRange]. No scoring
 * math is altered.
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
