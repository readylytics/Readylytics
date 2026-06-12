package app.readylytics.health.domain.dashboard

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.RecoveryFlag

data class DerivedInsights(
    val active: Set<InsightType>,
    val visibleQueue: List<InsightType>,
    val current: InsightType?,
    val dismissedCount: Int,
)

object InsightDeriver {
    private val displayPriority =
        listOf(
            InsightType.SICK_INDICATOR,
            InsightType.OVERREACHING,
            InsightType.WORKOUT_IMPACT,
            InsightType.REST_DAY_SUCCESS,
            InsightType.REST_DAY_NO_IMPACT,
            InsightType.LATE_NADIR,
        )

    fun derive(
        recoveryFlags: Set<RecoveryFlag>?,
        dismissedTypes: Set<InsightType>,
    ): DerivedInsights {
        val flags = recoveryFlags ?: emptySet()
        val active = flags.mapNotNull { InsightType.fromRecoveryFlag(it) }.toSet()
        val visibleQueue = displayPriority.filter { it in active && it !in dismissedTypes }
        val dismissedCount = active.intersect(dismissedTypes).size
        return DerivedInsights(
            active = active,
            visibleQueue = visibleQueue,
            current = visibleQueue.firstOrNull(),
            dismissedCount = dismissedCount,
        )
    }
}
