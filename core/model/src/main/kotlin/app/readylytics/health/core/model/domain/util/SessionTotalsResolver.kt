package app.readylytics.health.core.model.domain.util

import app.readylytics.health.domain.model.DomainIntervalTotal
import java.time.Instant

/**
 * Attributes Health Connect interval totals (`DistanceRecord`, `ElevationGainedRecord`) to the
 * exercise session they belong to.
 *
 * An `ExerciseSessionRecord` carries no distance of its own — the recording app writes that as a
 * separate record covering the same time span. Integrating the GPS polyline instead systematically
 * under-reads (chord-vs-arc plus track smoothing), which is why a workout shows a shorter distance
 * here than in the app that recorded it.
 *
 * Attribution is deliberately strict: only records written by the **same package** as the session
 * count. Several apps can cover one window (a phone writing step-derived distance alongside a watch
 * writing GPS distance), and summing across them would silently double-count. When the session's
 * writer stored nothing, the caller gets `null` and falls back to the route-derived value.
 */
object SessionTotalsResolver {
    /**
     * Sum of every [totals] entry written by [sessionOrigin] whose midpoint lies inside
     * `[sessionStart, sessionEnd]`, or `null` when that writer contributed none.
     *
     * Midpoint containment (rather than full containment) keeps a record that straddles a session
     * boundary attributed to exactly one session, so re-syncing a different window cannot change
     * the result.
     */
    fun totalFor(
        sessionStart: Instant,
        sessionEnd: Instant,
        sessionOrigin: String,
        totals: List<DomainIntervalTotal>,
    ): Double? {
        if (totals.isEmpty()) return null
        val startMs = sessionStart.toEpochMilli()
        val endMs = sessionEnd.toEpochMilli()
        var sum = 0.0
        var matched = false
        for (total in totals) {
            if (total.originPackage != sessionOrigin) continue
            if (!total.value.isFinite() || total.value < 0.0) continue
            val midpointMs = total.startTime.toEpochMilli() / 2 + total.endTime.toEpochMilli() / 2
            if (midpointMs < startMs || midpointMs > endMs) continue
            sum += total.value
            matched = true
        }
        return if (matched) sum else null
    }
}
