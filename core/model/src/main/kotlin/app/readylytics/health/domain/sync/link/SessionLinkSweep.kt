package app.readylytics.health.domain.sync.link

import app.readylytics.health.domain.model.RecordType

/**
 * Stateful, single-pass equivalent of [SessionLinker.resolve] for samples visited in non-decreasing
 * `sampleMs` order (e.g. Room's `getKeysetPage` ascending `(timestampMs, id)` pagination). Each span
 * enters and leaves the active window at most once across a full sweep, so a full reconcile pass
 * runs in roughly O(samples + sessions) rather than [SessionLinker]'s O(samples × sessions).
 *
 * Must stay behaviorally identical to [SessionLinker.resolve] for the same inputs -- that method is
 * the oracle [SessionLinkSweepPropertyTest] checks this class against. Not thread-safe; callers must
 * never pass a `sampleMs` smaller than a previous call's.
 */
class SessionLinkSweep(
    sleepSessions: List<SessionSpan>,
    workoutSessions: List<SessionSpan>,
) {
    private val sleepCursor = SpanCursor(sleepSessions)
    private val workoutCursor = SpanCursor(workoutSessions)

    fun resolve(sampleMs: Long): SampleLink {
        sleepCursor.advanceTo(sampleMs)?.let { return SampleLink(RecordType.SLEEP.name, it.id) }
        workoutCursor.advanceTo(sampleMs)?.let { return SampleLink(RecordType.EXERCISE.name, it.id) }
        return SampleLink(RecordType.RESTING.name, null)
    }

    /**
     * Tracks one span type's sweep state: a pointer into the spans sorted by (startTime, id), and
     * the subset currently active (started, not yet expired). Spans are appended to [active] in
     * sorted order and only ever removed, so the remaining elements stay in sorted order -- the
     * earliest-(startTime, id) survivor is always the front.
     */
    private class SpanCursor(spans: List<SessionSpan>) {
        private val sorted = spans.sortedWith(compareBy({ it.startTime }, { it.id }))
        private var nextIndex = 0
        private val active = ArrayDeque<SessionSpan>()

        /** Earliest-(startTime, id) span containing [sampleMs], or null. */
        fun advanceTo(sampleMs: Long): SessionSpan? {
            while (nextIndex < sorted.size && sorted[nextIndex].startTime <= sampleMs) {
                active.addLast(sorted[nextIndex])
                nextIndex++
            }
            active.removeAll { it.endTime < sampleMs }
            return active.firstOrNull()
        }
    }
}
