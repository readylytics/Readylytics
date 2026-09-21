package app.readylytics.health.core.model.domain.repository

import java.util.TreeMap

/**
 * Wearable-reported VO2 Max readings (timestamp -> value), fetched once for the duration of one
 * walk-forward (daily sync or resync recompute) and shared across every day it recomputes, instead
 * of each day independently re-querying its own 30-day trailing lookback window
 * (`BodyMetricsDataLoader.loadLatestVo2Max`).
 *
 * [vo2MaxByTimestampMs] is a [TreeMap] so `lowerEntry(Vo2MaxKey(endExclusiveMs, ""))` finds, in
 * O(log n), the most recent reading strictly before a given day's cutoff -- the same "latest in range"
 * a per-day query returned -- and the caller then checks that entry's timestamp against that day's
 * own lookback floor to reproduce the exact bounded-window semantics the per-day queries used.
 */
data class Vo2MaxKey(
    val timestampMs: Long,
    val id: String,
) : Comparable<Vo2MaxKey> {
    override fun compareTo(other: Vo2MaxKey): Int {
        val timeCmp = this.timestampMs.compareTo(other.timestampMs)
        if (timeCmp != 0) return timeCmp
        return this.id.compareTo(other.id)
    }
}

data class WalkForwardVo2MaxContext(
    val vo2MaxByTimestampMs: TreeMap<Vo2MaxKey, Float>,
)
