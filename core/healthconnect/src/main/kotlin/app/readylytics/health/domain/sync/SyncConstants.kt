package app.readylytics.health.domain.sync

/**
 * How far back a foreground (UI-blocking) sync/recompute will widen inline before escalating to
 * the durable historical resync worker instead. This is a foreground-cost guard, not a
 * correctness bound: changes older than this still recompute correctly via the resync worker.
 *
 * Shared by [DailySyncUseCase] (absorbing recent out-of-window Health Connect changes) and
 * [ForegroundSyncController] (capping the app-open catch-up window, HC-007) so the two places that
 * decide "is this cheap enough to run inline" never drift apart.
 */
const val MAX_INLINE_RECOMPUTE_DAYS = 7

/**
 * Window used by [app.readylytics.health.domain.sync.HealthDataRefresh.refreshAffectedWindow]
 * callers today (HC-009): every scoring-relevant settings change currently triggers this same
 * fixed foreground refresh, regardless of whether the setting actually invalidates the whole
 * retention-bounded history. SCORE-007 (WP-26) replaces this for historical-scope settings
 * (TRIMP model/params, HR zones, hrMax source, RHR/HRV overrides, physiology profile) with a full
 * recompute; this constant remains the recent-window default for everything else.
 */
const val SETTINGS_REFRESH_WINDOW_DAYS = 8
