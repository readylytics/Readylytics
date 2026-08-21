package app.readylytics.health.core.model.domain.scoring

/**
 * User-selectable emphasis for the sleep score. Degraded weights apply when stage data is
 * missing or implausible: architecture and fragmentation drop out and the surviving terms
 * are renormalized, so the composite still spans 0..100.
 */
enum class SleepScoreWeightProfile(
    val durationWeight: Float,
    val architectureWeight: Float,
    val restorationWeight: Float,
    val fragmentationWeight: Float,
) {
    BALANCED(0.40f, 0.20f, 0.25f, 0.15f),
    DURATION_FOCUSED(0.50f, 0.15f, 0.20f, 0.15f),
    RECOVERY_FOCUSED(0.30f, 0.15f, 0.40f, 0.15f),
    ARCHITECTURE_FOCUSED(0.30f, 0.35f, 0.20f, 0.15f),
    CONTINUITY_FOCUSED(0.30f, 0.15f, 0.25f, 0.30f),
    ;

    private val degradedTotal: Float get() = durationWeight + restorationWeight

    val degradedDurationWeight: Float get() = durationWeight / degradedTotal

    val degradedRestorationWeight: Float get() = restorationWeight / degradedTotal

    companion object {
        val DEFAULT = BALANCED
    }
}
