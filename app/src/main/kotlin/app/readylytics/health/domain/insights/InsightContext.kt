package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.scoring.CircadianConsistencyResult

/**
 * Snapshot of a user's biometric state passed to [InsightRule]s. Pure Kotlin,
 * zero Android dependencies.
 */
data class InsightContext(
    val today: DailySummary,
    val circadianResult: CircadianConsistencyResult,
    val goalSleepMinutes: Int,
    val stepGoal: Int = 10000,
    val recentDays: List<DailySummary> = emptyList(),
)
