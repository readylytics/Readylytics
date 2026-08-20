package app.readylytics.health.core.model.domain.util

import app.readylytics.health.domain.preferences.UserPreferences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

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

    /** Fixed hot/warm tier boundary: raw 1s samples stay hot for 90 days, then roll up to buckets. */
    const val HOT_TIER_WINDOW_DAYS = 90L

    /**
     * Epoch-millis cutoff below which heart-rate raw samples are eligible for hot→warm rollup.
     * A fixed 90-day window, independent of the user's retention setting: retention governs the
     * warm→cold deletion bound, rollup governs the hot→warm aggregate bound.
     */
    fun resolveHotTierCutoffMs(
        now: Instant = Instant.now(),
    ): Long = now.minus(HOT_TIER_WINDOW_DAYS, ChronoUnit.DAYS).toEpochMilli()

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
