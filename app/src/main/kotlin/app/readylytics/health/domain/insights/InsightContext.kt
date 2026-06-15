package app.readylytics.health.domain.insights

import app.readylytics.health.data.preferences.UserPreferences
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
    // Current local time of day in minutes since midnight (0..1439), only
    // meaningful when `today` is the actual current day. Defaults to the end
    // of the day so time-of-day gating is a no-op for past days.
    val nowMinutesOfDay: Int = 1439,
    // User's selected load-source modes, used by rules to select strain/load/PAI
    // values via LoadSourceSelector instead of reading legacy DailySummary columns.
    val prefs: UserPreferences = UserPreferences(),
)
