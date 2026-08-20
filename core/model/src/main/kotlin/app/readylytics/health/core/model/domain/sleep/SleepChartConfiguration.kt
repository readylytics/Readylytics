package app.readylytics.health.core.model.domain.sleep

import app.readylytics.health.core.model.domain.layout.ReorderableItem
import kotlinx.serialization.Serializable

@Serializable
data class SleepChartConfiguration(
    val chartId: SleepChartId,
    override val isVisible: Boolean = true,
    override val position: Int = 0,
) : ReorderableItem<SleepChartId> {
    override val id: SleepChartId get() = chartId
}
