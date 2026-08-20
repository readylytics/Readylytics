package app.readylytics.health.domain.preferences

import app.readylytics.health.data.preferences.UserPreferences
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
}
