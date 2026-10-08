package app.readylytics.health.core.model.domain.preferences

import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.scoring.SleepScoreWeightProfile
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface SettingsRepository {
    val userPreferences: Flow<UserPreferences>
    suspend fun bootstrapRasSourceModeIfUnset(hasWorkoutOnlyHistory: Boolean)
    suspend fun updateMaxHeartRate(bpm: Int)
    suspend fun migrateDeviceSelectionIfNeeded()
    suspend fun updateLastSyncTimestamp(timestamp: Long)
    suspend fun updateBirthday(date: LocalDate)
    suspend fun updateScoringVersion(version: Int)

    /** WP-17 (HC-102): see `user_preferences.proto` field 100's doc comment. */
    suspend fun updateSelectedWorkoutRepairCompleted(completed: Boolean)

    /**
     * Records the sleep-scoring inputs (weight profile, goal hours, oversleep onset) applied by the
     * last successful historical recompute. Read back via [UserPreferences.lastRecalc*]; the Sleep
     * Settings "Recalculate scores" button enables only while the live inputs differ from it.
     */
    suspend fun updateSleepScoreRecalcBaseline(
        weightProfile: SleepScoreWeightProfile,
        goalSleepHours: Float,
        hypersomniaOnsetPercent: Int,
    )
    
    suspend fun updateTrainingReadinessConfig(
        config: app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig
    )
}
