package app.readylytics.health.core.scoring.domain.scoring.sleep

import app.readylytics.health.core.model.domain.model.HeartRateRecord
import kotlin.math.min

object IntervalCoverageAccumulator {
    const val DEFAULT_MAX_GAP_MS: Long = 5 * 60 * 1000L // 5 minutes
    private const val SINGLE_SAMPLE_ALLOWANCE_MS: Long = 60_000L // 1 minute

    fun calculateCoverageMs(
        sessionStartMs: Long,
        sessionEndMs: Long,
        hrRecords: List<HeartRateRecord>,
        maxGapMs: Long = DEFAULT_MAX_GAP_MS,
    ): Long {
        val sleepDurationMs = sessionEndMs - sessionStartMs
        if (sleepDurationMs <= 0) return 0L

        val filtered =
            hrRecords
                .filter { it.timestampMs in sessionStartMs..sessionEndMs }
                .sortedBy { it.timestampMs }

        return if (filtered.isEmpty()) {
            0L
        } else {
            val coverageMs =
                if (filtered.size > 1) {
                    filtered.zipWithNext { current, next ->
                        min(next.timestampMs - current.timestampMs, maxGapMs)
                    }.sum()
                } else {
                    0L
                }
            (coverageMs + SINGLE_SAMPLE_ALLOWANCE_MS).coerceAtMost(sleepDurationMs)
        }
    }
}
