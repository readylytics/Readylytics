package app.readylytics.health.domain.sleep

import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode

/**
 * Which visualization modes a sleep layout item supports, plus its legacy default.
 *
 * Mirrors [app.readylytics.health.domain.dashboard.DashboardCardSpec] for the sleep-specific
 * config types ([SleepTopCardConfiguration] / [SleepMetricCardConfiguration]). The full-width
 * top cards (architecture bar, stages timeline, HR chart) and the value-only metric cards
 * (nap duration, nap count) have no spec — they are not mode-switchable.
 */
data class SleepCardSpec(
    val legacyDefaultMode: DashboardCardDisplayMode,
    val supportedModes: List<DashboardCardDisplayMode>,
)

object SleepCardCatalog {
    fun topCardSpec(id: SleepTopCardId): SleepCardSpec? = topCardSpecs[id]

    fun metricCardSpec(id: SleepMetricCardId): SleepCardSpec? = metricCardSpecs[id]

    fun requestedTopCardMode(configuration: SleepTopCardConfiguration): DashboardCardDisplayMode {
        val spec = topCardSpec(configuration.cardId) ?: return DashboardCardDisplayMode.VALUE
        val requested = configuration.requestedDisplayMode
        return if (requested != null && spec.supportedModes.contains(requested)) {
            requested
        } else {
            spec.legacyDefaultMode
        }
    }

    fun requestedMetricCardMode(configuration: SleepMetricCardConfiguration): DashboardCardDisplayMode {
        val spec = metricCardSpec(configuration.cardId) ?: return DashboardCardDisplayMode.VALUE
        val requested = configuration.requestedDisplayMode
        return if (requested != null && spec.supportedModes.contains(requested)) {
            requested
        } else {
            spec.legacyDefaultMode
        }
    }

    fun applyGlobalTopCardMode(
        configurations: List<SleepTopCardConfiguration>,
        mode: DashboardCardDisplayMode,
    ): List<SleepTopCardConfiguration> =
        configurations.map { config ->
            val supported = topCardSpec(config.cardId)?.supportedModes.orEmpty()
            if (mode in supported) config.copy(requestedDisplayMode = mode) else config
        }

    fun applyGlobalMetricCardMode(
        configurations: List<SleepMetricCardConfiguration>,
        mode: DashboardCardDisplayMode,
    ): List<SleepMetricCardConfiguration> =
        configurations.map { config ->
            val supported = metricCardSpec(config.cardId)?.supportedModes.orEmpty()
            if (mode in supported) config.copy(requestedDisplayMode = mode) else config
        }

    fun resetTopCardModes(configurations: List<SleepTopCardConfiguration>): List<SleepTopCardConfiguration> =
        configurations.map { it.copy(requestedDisplayMode = null) }

    fun resetMetricCardModes(configurations: List<SleepMetricCardConfiguration>): List<SleepMetricCardConfiguration> =
        configurations.map { it.copy(requestedDisplayMode = null) }

    private val ALL_MODES =
        listOf(
            DashboardCardDisplayMode.GAUGE,
            DashboardCardDisplayMode.BAR,
            DashboardCardDisplayMode.VALUE,
        )

    // Only the two gauge top cards are mode-switchable; the remaining top cards are full-width
    // charts with no display-mode concept.
    private val topCardSpecs: Map<SleepTopCardId, SleepCardSpec> =
        mapOf(
            SleepTopCardId.SLEEP_SCORE to SleepCardSpec(DashboardCardDisplayMode.GAUGE, ALL_MODES),
            SleepTopCardId.SLEEP_DURATION_GAUGE to SleepCardSpec(DashboardCardDisplayMode.GAUGE, ALL_MODES),
        )

    // Percentage-based metric cards are mode-switchable; nap duration/count stay value-only.
    private val metricCardSpecs: Map<SleepMetricCardId, SleepCardSpec> =
        mapOf(
            SleepMetricCardId.CIRCADIAN_CONSISTENCY to SleepCardSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            SleepMetricCardId.SLEEP_EFFICIENCY to SleepCardSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            SleepMetricCardId.DEEP_SLEEP to SleepCardSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            SleepMetricCardId.REM_SLEEP to SleepCardSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
        )
}
