package app.readylytics.health.domain.vitals

data class VitalsChartConfiguration(
    val chartId: VitalsChartId,
    val isVisible: Boolean,
    val position: Int,
)