package app.readylytics.health.core.healthconnect.domain.sync

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
 * Window used by [app.readylytics.health.core.healthconnect.domain.sync.HealthDataRefresh.refreshAffectedWindow]
 * callers today (HC-009): every scoring-relevant settings change currently triggers this same
 * fixed foreground refresh, regardless of whether the setting actually invalidates the whole
 * retention-bounded history. SCORE-007 (WP-26) replaces this for historical-scope settings
 * (TRIMP model/params, HR zones, hrMax source, RHR/HRV overrides, physiology profile) with a full
 * recompute; this constant remains the recent-window default for everything else.
 */
const val SETTINGS_REFRESH_WINDOW_DAYS = 8

/**
 * B′: default ingest budget (ms) for the today segment of the daily sync. Matches the
 * coordinator's own default so the today segment stays on the original 3-minute bound.
 */
const val DEFAULT_DAILY_INGEST_BUDGET_MS = 3 * 60_000L

/**
 * B′: ingest budget (ms) for the overnight back-day reach-back segment of the daily sync. The
 * back-day segment covers the previous evening's pre-midnight HR/HRV and is typically denser than
 * today's partial day, so it gets a longer budget than the coordinator's 3-minute default. Each
 * segment is still a bounded foreground transaction; a timeout here does not widen the window.
 */
const val BACK_DAY_INGEST_BUDGET_MS = 5 * 60_000L

/**
 * B: extended ingest budget (ms) used for the single retry of a daily-sync segment whose first
 * read attempt timed out. Ingestion is idempotent (upsert by HC record id), so re-running the same
 * segment is safe regardless of how far the first attempt got before timing out.
 */
const val EXTENDED_DAILY_INGEST_BUDGET_MS = 10 * 60_000L
