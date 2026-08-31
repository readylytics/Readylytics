package app.readylytics.health.core.model.domain.dashboard

/**
 * Visualization modes a layout item supports, plus its legacy default. Shared by the
 * dashboard/vitals catalog ([DashboardCardCatalog]) and the sleep catalog ([SleepCardCatalog]).
 */
data class ModeSpec(
    val legacyDefaultMode: DashboardCardDisplayMode,
    val supportedModes: List<DashboardCardDisplayMode>,
)

/**
 * Resolve a card's effective mode: an explicit [requested] mode wins when the card supports it,
 * otherwise the card's legacy default applies.
 */
fun ModeSpec.resolveRequestedMode(requested: DashboardCardDisplayMode?): DashboardCardDisplayMode =
    if (requested != null && supportedModes.contains(requested)) requested else legacyDefaultMode
