package app.readylytics.health.domain.util

import app.readylytics.health.data.preferences.UserPreferences
import java.time.LocalDate
import java.time.ZoneId

/**
 * Single source of truth for translating the user's data-retention preference into a date bound.
 *
 * The retention setting (`retentionDaysEnabled` + `retentionDays`, default 365) governs both the
 * periodic [app.readylytics.health.workers.DataCleanupWorker] (what to delete) and the
 * full historical resync (how far back to re-fetch/recompute). "Unlimited" retention is modelled by
 * `retentionDaysEnabled == false`; in that case the resync walks back [ABSOLUTE_MAX_DAYS] (a bounded
 * stand-in for "everything", matching the retention validator's maximum) so the loop always terminates.
 */
object RetentionBounds {
    /** Upper bound for an "unlimited" (retention-disabled) resync window, in days. */
    const val ABSOLUTE_MAX_DAYS = 3650L

    /**
     * Inclusive start date for a full historical resync: `today - retentionDays` when retention is
     * enabled, otherwise `today - [ABSOLUTE_MAX_DAYS]`.
     */
    fun resolveResyncStartDate(
        prefs: UserPreferences,
        today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
    ): LocalDate =
        if (prefs.retentionDaysEnabled) {
            today.minusDays(prefs.retentionDays.toLong())
        } else {
            today.minusDays(ABSOLUTE_MAX_DAYS)
        }

    /**
     * Epoch-millis cutoff (start-of-day) below which data may be deleted, or null when retention is
     * disabled (keep everything). Mirrors the logic the cleanup worker previously inlined.
     */
    fun resolveRetentionCutoffMs(
        prefs: UserPreferences,
        today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        if (!prefs.retentionDaysEnabled) return null
        return today
            .minusDays(prefs.retentionDays.toLong())
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
    }
}
