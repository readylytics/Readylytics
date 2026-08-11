package app.readylytics.health.domain.model

import app.readylytics.health.domain.scoring.ScoringConstants

enum class LoadContext {
    BELOW_TYPICAL,
    SWEET_SPOT,
    ELEVATED,
    HIGH,
    UNKNOWN
}

fun Float?.toLoadContext(): LoadContext {
    if (this == null || this.isNaN() || this < 0.0f) return LoadContext.UNKNOWN
    // Coarser, direction-preserving ladder than Float.strainRatioStatus() (MetricStatus) —
    // intentional: the AI prompt needs "too low vs too high," not a single "poor" bucket for both.
    return when {
        this < BELOW_TYPICAL_MAX -> LoadContext.BELOW_TYPICAL
        this <= ScoringConstants.Strain.SR_SWEET_SPOT_MAX -> LoadContext.SWEET_SPOT
        this <= ELEVATED_MAX -> LoadContext.ELEVATED
        else -> LoadContext.HIGH
    }
}

private const val BELOW_TYPICAL_MAX = 0.8f
private const val ELEVATED_MAX = 1.5f
