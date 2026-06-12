package app.readylytics.health.domain.dashboard

import app.readylytics.health.domain.insights.InsightFinding
import app.readylytics.health.domain.insights.InsightParams
import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.RecoveryFlag

data class DerivedInsights(
    val active: Set<InsightType>,
    val visibleQueue: List<InsightType>,
    val current: InsightType?,
    val currentParams: InsightParams,
    val dismissedCount: Int,
)

object InsightDeriver {
    private val displayPriority =
        listOf(
            InsightType.HRV_DROP_LOW_SPO2,
            InsightType.SICK_INDICATOR,
            InsightType.HIGH_STRAIN_SLEEP_DEFICIT,
            InsightType.OVERREACHING,
            InsightType.WORKOUT_IMPACT,
            InsightType.REST_DAY_SUCCESS,
            InsightType.CIRCADIAN_SHIFT_RECOVERY_MISS,
            InsightType.REST_DAY_NO_IMPACT,
            InsightType.LATE_NADIR_SHORT_SLEEP,
            InsightType.LATE_NADIR_ELEVATED_RHR,
            InsightType.LATE_NADIR,
            InsightType.BP_ELEVATED_HIGH_STRAIN,
            InsightType.PAI_DEPLETION_HIGH_STRAIN,
            InsightType.HRV_DECLINE_STREAK,
            InsightType.STEP_SHORTFALL,
            InsightType.PAI_WEEKLY_UNDERPERFORMANCE,
            InsightType.WEIGHT_DRIFT_TRAINING_LOAD,
            InsightType.RECOVERY_HRV_MISSING,
            InsightType.RECOVERY_STAGES_MISSING,
        )

    // When a more specific, causal insight fires, it replaces the generic
    // flag-derived insight that would otherwise describe the same outcome.
    private val suppresses: Map<InsightType, InsightType> =
        mapOf(
            InsightType.CIRCADIAN_SHIFT_RECOVERY_MISS to InsightType.REST_DAY_NO_IMPACT,
            InsightType.HIGH_STRAIN_SLEEP_DEFICIT to InsightType.SICK_INDICATOR,
            InsightType.LATE_NADIR_SHORT_SLEEP to InsightType.LATE_NADIR,
            InsightType.HRV_DROP_LOW_SPO2 to InsightType.SICK_INDICATOR,
            InsightType.LATE_NADIR_ELEVATED_RHR to InsightType.LATE_NADIR,
        )

    fun derive(
        recoveryFlags: Set<RecoveryFlag>?,
        engineFindings: List<InsightFinding> = emptyList(),
        dismissedTypes: Set<InsightType>,
    ): DerivedInsights {
        val flags = recoveryFlags ?: emptySet()
        val flagDerived = flags.mapNotNull { InsightType.fromRecoveryFlag(it) }.toSet()
        val engineDerived = engineFindings.map { it.type }.toSet()

        val suppressed = engineDerived.mapNotNull { suppresses[it] }.toSet()
        val active = (flagDerived - suppressed) + engineDerived

        val visibleQueue = displayPriority.filter { it in active && it !in dismissedTypes }
        val dismissedCount = active.intersect(dismissedTypes).size
        val current = visibleQueue.firstOrNull()
        val paramsByType = engineFindings.associate { it.type to it.params }

        return DerivedInsights(
            active = active,
            visibleQueue = visibleQueue,
            current = current,
            currentParams = current?.let { paramsByType[it] } ?: InsightParams.None,
            dismissedCount = dismissedCount,
        )
    }
}
