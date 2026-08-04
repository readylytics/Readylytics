package app.readylytics.health.core.ui.components.metriccard

data class UniversalMetricCardSpec(
    val supportedModes: List<UniversalCardDisplayMode>,
    val usesDeltaPill: Boolean = false,
)
