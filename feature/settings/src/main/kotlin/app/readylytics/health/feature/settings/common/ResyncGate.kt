package app.readylytics.health.feature.settings.common

/**
 * Settings controls are frozen while a historical resync/recompute is running so the user can't
 * stack a second pass or mutate inputs mid-recompute. Every settings section takes the gate through
 * this helper instead of duplicating `!isResyncing` at each call site.
 */
fun resyncGateEnabled(isResyncing: Boolean): Boolean = !isResyncing
