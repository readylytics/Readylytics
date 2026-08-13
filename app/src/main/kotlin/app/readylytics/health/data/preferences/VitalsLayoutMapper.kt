package app.readylytics.health.data.preferences

import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.domain.vitals.VitalsChartId

object VitalsLayoutMapper {
    fun toCardDomain(proto: VitalsCardConfigurationProto): CardConfiguration? {
        val cardId =
            try {
                CardId.valueOf(proto.cardId)
            } catch (_: IllegalArgumentException) {
                return null
            }
        return CardConfiguration(
            cardId = cardId,
            isVisible = proto.isVisible,
            position = proto.position,
            requestedDisplayMode = parseDisplayMode(proto.requestedDisplayMode),
        )
    }

    fun toCardProto(domain: CardConfiguration): VitalsCardConfigurationProto =
        VitalsCardConfigurationProto
            .newBuilder()
            .setCardId(domain.cardId.name)
            .setIsVisible(domain.isVisible)
            .setPosition(domain.position)
            .setRequestedDisplayMode(domain.requestedDisplayMode?.name.orEmpty())
            .build()

    fun toChartDomain(proto: VitalsChartConfigurationProto): VitalsChartConfiguration? {
        val chartId =
            try {
                VitalsChartId.valueOf(proto.chartId)
            } catch (_: IllegalArgumentException) {
                return null
            }
        return VitalsChartConfiguration(
            chartId = chartId,
            isVisible = proto.isVisible,
            position = proto.position,
        )
    }

    fun toChartProto(domain: VitalsChartConfiguration): VitalsChartConfigurationProto =
        VitalsChartConfigurationProto
            .newBuilder()
            .setChartId(domain.chartId.name)
            .setIsVisible(domain.isVisible)
            .setPosition(domain.position)
            .build()

    // Additive, tolerant parsing: blank (missing proto field) and unknown mode
    // names (e.g. from a future app version) both decode to null instead of
    // throwing, so old and new persisted proto data both remain loadable.
    private fun parseDisplayMode(value: String): DashboardCardDisplayMode? =
        value.takeIf(String::isNotBlank)?.let { stored ->
            runCatching { DashboardCardDisplayMode.valueOf(stored) }.getOrNull()
        }
}
