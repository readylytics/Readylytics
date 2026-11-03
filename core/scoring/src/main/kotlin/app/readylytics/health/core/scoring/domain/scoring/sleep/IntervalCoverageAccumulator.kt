package app.readylytics.health.core.scoring.domain.scoring.sleep

import app.readylytics.health.core.model.domain.model.HeartRateRecord
import kotlin.math.min

object IntervalCoverageAccumulator {
    /**
     * Longest inter-sample gap that still counts as covered. Sized above the sparsest common
     * sleep-HR cadence (about 10 minutes) so those devices are not penalised, yet far below any
     * real sampling dropout, so two boundary samples cannot span a whole night.
     */
    const val DEFAULT_MAX_GAP_MS: Long = 15 * 60 * 1000L
    private const val BASE_ALLOWANCE_MS: Long = 60_000L // 1 minute

    fun calculateCoverageMs(
        sessionStartMs: Long,
        sessionEndMs: Long,
        hrRecords: List<HeartRateRecord>,
        maxGapMs: Long = DEFAULT_MAX_GAP_MS,
    ): Long {
        val sleepDurationMs = sessionEndMs - sessionStartMs
        val timestamps =
            if (sleepDurationMs > 0) sortedTimestampsInRange(hrRecords, sessionStartMs, sessionEndMs) else LongArray(0)
        if (timestamps.isEmpty()) return 0L

        var coverageMs = BASE_ALLOWANCE_MS
        for (i in 1 until timestamps.size) {
            coverageMs += min(timestamps[i] - timestamps[i - 1], maxGapMs)
        }
        return coverageMs.coerceAtMost(sleepDurationMs)
    }

    private fun sortedTimestampsInRange(
        hrRecords: List<HeartRateRecord>,
        startMs: Long,
        endMs: Long,
    ): LongArray {
        var count = 0
        val buffer = LongArray(hrRecords.size)
        for (record in hrRecords) {
            if (record.timestampMs in startMs..endMs) buffer[count++] = record.timestampMs
        }
        return buffer.copyOf(count).also { it.sort() }
    }
}
