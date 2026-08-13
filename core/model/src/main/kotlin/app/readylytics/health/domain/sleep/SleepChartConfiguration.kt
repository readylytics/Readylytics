package app.readylytics.health.domain.sleep

import app.readylytics.health.domain.layout.ReorderableItem
import kotlinx.serialization.Serializable

@Serializable
data class SleepChartConfiguration(
    val chartId: SleepChartId,
    override val isVisible: Boolean = true,
    override val position: Int = 0,
) : ReorderableItem<SleepChartId> {
    override val id: SleepChartId get() = chartId
}
