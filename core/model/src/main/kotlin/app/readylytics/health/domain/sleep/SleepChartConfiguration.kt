package app.readylytics.health.domain.sleep

import kotlinx.serialization.Serializable

@Serializable
data class SleepChartConfiguration(
    val chartId: SleepChartId,
    val isVisible: Boolean = true,
    val position: Int = 0,
)
