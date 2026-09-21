package app.readylytics.health.core.model.domain.sync

/**
 * Provides pure time-boundary calculations for health data processing intervals.
 */
fun completeMinuteCutoff(cutoffMs: Long): Long = Math.floorDiv(cutoffMs, 60_000L) * 60_000L
