package app.readylytics.health.domain.sleep

import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.dashboard.ModeSpec
import app.readylytics.health.domain.dashboard.resolveRequestedMode

/**
 * Sleep layout items that are mode-switchable, mirroring the dashboard [DashboardCardCatalog].
 * Full-width top cards (architecture bar, stages timeline, HR chart) and the value-only metric
 * cards (nap duration, nap count) have no spec.
 */
object SleepCardCatalog {
    fun topCardSpec(id: SleepTopCardId): ModeSpec? = topCardSpecs[id]

    fun metricCardSpec(id: SleepMetricCardId): ModeSpec? = metricCardSpecs[id]

    fun requestedTopCardMode(configuration: SleepTopCardConfiguration): DashboardCardDisplayMode =
        topCardSpec(configuration.cardId)?.resolveRequestedMode(configuration.requestedDisplayMode)
            ?: DashboardCardDisplayMode.VALUE

    fun requestedMetricCardMode(configuration: SleepMetricCardConfiguration): DashboardCardDisplayMode =
        metricCardSpec(configuration.cardId)?.resolveRequestedMode(configuration.requestedDisplayMode)
            ?: DashboardCardDisplayMode.VALUE

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

    private val topCardSpecs: Map<SleepTopCardId, ModeSpec> =
        mapOf(
            SleepTopCardId.SLEEP_SCORE to ModeSpec(DashboardCardDisplayMode.GAUGE, ALL_MODES),
            SleepTopCardId.SLEEP_DURATION_GAUGE to ModeSpec(DashboardCardDisplayMode.GAUGE, ALL_MODES),
        )

    private val metricCardSpecs: Map<SleepMetricCardId, ModeSpec> =
        mapOf(
            SleepMetricCardId.CIRCADIAN_CONSISTENCY to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            SleepMetricCardId.SLEEP_EFFICIENCY to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            SleepMetricCardId.DEEP_SLEEP to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            SleepMetricCardId.REM_SLEEP to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
        )
}
