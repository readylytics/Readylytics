package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.repository.ScoringHistoryRepository

/**
 * One-time bootstrap for existing users' `rasSourceMode` preference.
 *
 * If `ras_source_mode` was never explicitly set and pre-existing workout-only TRIMP history
 * exists, persists [LoadSourceMode.WORKOUT_ONLY] so existing users keep their prior behavior.
 * Otherwise persists [LoadSourceMode.EVERYDAY_HEART_RATE] explicitly so the proto field is no
 * longer unset and this bootstrap never re-runs.
 */
class RasSourceModeBootstrapUseCase
    constructor(
        private val settingsRepo: SettingsRepository,
        private val scoringHistoryRepository: ScoringHistoryRepository,
    ) {
        suspend operator fun invoke() {
            val hasWorkoutOnlyHistory = scoringHistoryRepository.hasAnyWorkoutOnlyTrimpData()
            settingsRepo.bootstrapRasSourceModeIfUnset(hasWorkoutOnlyHistory)
        }
    }
