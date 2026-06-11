package com.gregor.lauritz.healthdashboard.domain.dashboard

import com.gregor.lauritz.healthdashboard.domain.model.InsightType
import com.gregor.lauritz.healthdashboard.domain.model.RecoveryFlag

data class DerivedInsights(
    val active: Set<InsightType>,
    val visibleQueue: List<InsightType>,
    val current: InsightType?,
    val dismissedCount: Int,
)

object InsightDeriver {
    private val displayPriority =
        listOf(
            InsightType.LATE_NADIR,
            InsightType.SICK_INDICATOR,
            InsightType.OVERREACHING,
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
