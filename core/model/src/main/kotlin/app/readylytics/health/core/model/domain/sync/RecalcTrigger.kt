package app.readylytics.health.core.model.domain.sync

/**
 * Why a durable historical resync / recompute was enqueued. Carried through the resync worker's
 * input data so a large recalculation can be attributed to its cause in diagnostics.
 *
 * [isUnexpected] marks triggers that should never cause a large recalculation in normal use. When
 * one of them resolves to more than a few days, the worker records a diagnostic that is offered
 * for sharing on the next app start. User-initiated and expected triggers (a manual resync, a
 * settings change, an app update's scoring-version bump, returning after a long absence) are never
 * reported.
 */
enum class RecalcTrigger(
    val isUnexpected: Boolean,
) {
    USER_RESYNC(isUnexpected = false),
    SETTINGS_CHANGE(isUnexpected = false),
    STARTUP_SCORING_VERSION(isUnexpected = false),
    CATCH_UP_CAP(isUnexpected = false),
    STARTUP_TRIMP_BACKFILL(isUnexpected = true),
    STARTUP_PENDING_DIRTY(isUnexpected = true),
    PERIODIC_SYNC_ESCALATION(isUnexpected = true),
    FOREGROUND_SYNC_ESCALATION(isUnexpected = true),
    ;

    companion object {
        /** Decodes a stored name; work enqueued by an older build (no name) maps to [SETTINGS_CHANGE]. */
        fun fromName(name: String?): RecalcTrigger = entries.firstOrNull { it.name == name } ?: SETTINGS_CHANGE
    }
}
