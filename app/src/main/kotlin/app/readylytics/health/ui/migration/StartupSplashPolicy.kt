package app.readylytics.health.ui.migration

import app.readylytics.health.core.model.domain.migration.DatabaseReadiness

/**
 * Whether the system splash should stay up during launch, before the app's own content takes over
 * the keep-on-screen condition.
 *
 * [DatabaseReadiness.Checking] is only the controller's placeholder until the first readiness probe
 * finishes on IO -- normally a few dozen milliseconds. Dismissing the splash during that window
 * rendered the "Updating your health database" card for one or two frames on every cold start.
 * Holding the splash instead hides the probe; [maxWaitMs] bounds it, so a genuinely slow probe still
 * falls through to the card's progress indicator rather than trapping the user on the splash.
 */
internal fun shouldKeepStartupSplash(
    isKeyValidationComplete: Boolean,
    readiness: DatabaseReadiness,
    elapsedMs: Long,
    maxWaitMs: Long,
): Boolean {
    val pending = !isKeyValidationComplete || readiness == DatabaseReadiness.Checking
    return pending && elapsedMs < maxWaitMs
}
