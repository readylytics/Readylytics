package app.readylytics.health.domain.scoring

import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.preferences.SettingsRepository
import javax.inject.Inject

/**
 * One-time bootstrap for existing users' `rasSourceMode` preference.
 *
 * If `ras_source_mode` was never explicitly set and pre-existing workout-only TRIMP history
 * exists, persists [LoadSourceMode.WORKOUT_ONLY] so existing users keep their prior behavior.
 * Otherwise persists [LoadSourceMode.EVERYDAY_HEART_RATE] explicitly so the proto field is no
 * longer unset and this bootstrap never re-runs.
 */
class RasSourceModeBootstrapUseCase
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val dailySummaryDao: DailySummaryDao,
    ) {
        suspend operator fun invoke() {
            val hasWorkoutOnlyHistory = dailySummaryDao.hasAnyWorkoutOnlyTrimpData()
            settingsRepo.bootstrapRasSourceModeIfUnset(hasWorkoutOnlyHistory)
        }
    }
