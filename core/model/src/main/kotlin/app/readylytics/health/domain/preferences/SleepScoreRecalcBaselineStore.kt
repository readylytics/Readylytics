package app.readylytics.health.domain.preferences

import app.readylytics.health.domain.scoring.SleepScoreWeightProfile
import kotlinx.coroutines.flow.Flow

/**
 * Snapshot of the three sleep-scoring inputs at the moment the "Recalculate scores" button last
 * applied them to history. The button is enabled only while the current inputs differ from this
 * baseline (so it stays off when nothing has changed since the last historical recompute).
 */
data class SleepScoreRecalcBaseline(
    val weightProfile: SleepScoreWeightProfile,
    val goalSleepHours: Float,
    val hypersomniaOnsetPercent: Int,
)

interface SleepScoreRecalcBaselineStore {
    /** Current baseline; `null` until the first historical sleep-score recompute. */
    val baseline: Flow<SleepScoreRecalcBaseline?>

    suspend fun markRecalced(
        weightProfile: SleepScoreWeightProfile,
        goalSleepHours: Float,
        hypersomniaOnsetPercent: Int,
    )
}