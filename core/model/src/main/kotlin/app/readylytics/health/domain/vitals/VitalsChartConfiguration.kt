package app.readylytics.health.domain.vitals

import kotlinx.serialization.Serializable

@Serializable
data class VitalsChartConfiguration(
    val chartId: VitalsChartId,
    val isVisible: Boolean,
    val position: Int,
)