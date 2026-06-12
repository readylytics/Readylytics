package app.readylytics.health.domain.sync.link

import app.readylytics.health.domain.model.RecordType

/**
 * A session's identity and time bounds, independent of its concrete entity type
 * (sleep session or workout).
 */
data class SessionSpan(
    val id: String,
    val startTime: Long,
    val endTime: Long,
)

/** The (recordType, sessionId) a sample should be tagged with. */
data class SampleLink(
    val recordType: String,
    val sessionId: String?,
)

/**
 * Resolves which session (if any) a timestamped sample belongs to.
 *
 * Pure function of (sampleMs, sleepSessions, workoutSessions): the result does not depend on
 * processing order, prior tagging, or which Health Connect fetch window the sample came from.
 * This is the shared source of truth for [app.readylytics.health.data.healthconnect.HeartRateMapper]
 * and [app.readylytics.health.data.healthconnect.HrvMapper] during ingestion, and for
 * [SessionLinkReconciler] during the post-ingestion reconcile pass.
 *
 * Precedence: sleep > workout > resting. For overlapping sessions of the same type, the one with
 * the earliest (startTime, id) wins.
 */
object SessionLinker {
    fun resolve(
        sampleMs: Long,
        sleepSessions: List<SessionSpan>,
        workoutSessions: List<SessionSpan>,
    ): SampleLink {
        findContaining(sampleMs, sleepSessions)?.let {
            return SampleLink(RecordType.SLEEP.name, it.id)
        }
        findContaining(sampleMs, workoutSessions)?.let {
            return SampleLink(RecordType.EXERCISE.name, it.id)
        }
        return SampleLink(RecordType.RESTING.name, null)
    }

    private fun findContaining(
        sampleMs: Long,
        sessions: List<SessionSpan>,
    ): SessionSpan? =
        sessions
            .asSequence()
            .filter { sampleMs in it.startTime..it.endTime }
            .minWithOrNull(compareBy({ it.startTime }, { it.id }))
}
