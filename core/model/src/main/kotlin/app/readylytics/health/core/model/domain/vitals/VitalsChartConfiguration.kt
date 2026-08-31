package app.readylytics.health.core.model.domain.vitals

import app.readylytics.health.core.model.domain.layout.ReorderableItem
import kotlinx.serialization.Serializable

@Serializable
data class VitalsChartConfiguration(
    val chartId: VitalsChartId,
    override val isVisible: Boolean,
    override val position: Int,
) : ReorderableItem<VitalsChartId> {
    override val id: VitalsChartId get() = chartId
}
