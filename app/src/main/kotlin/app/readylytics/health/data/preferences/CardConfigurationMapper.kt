package app.readylytics.health.data.preferences

import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode

object CardConfigurationMapper {
    fun toDomain(proto: CardConfigurationProto): CardConfiguration? {
        val cardId =
            try {
                CardId.valueOf(proto.cardId)
            } catch (_: IllegalArgumentException) {
                // Backward-compat: proto stored "PAI_DAILY" before RAS rename
                if (proto.cardId == "PAI_DAILY") CardId.RAS_DAILY else return null
            }
        return CardConfiguration(
            cardId = cardId,
            isVisible = proto.isVisible,
            position = proto.position,
            requestedDisplayMode = parseDisplayMode(proto.requestedDisplayMode),
        )
    }

    fun toProto(domain: CardConfiguration): CardConfigurationProto =
        CardConfigurationProto
            .newBuilder()
            .setCardId(domain.cardId.name)
            .setIsVisible(domain.isVisible)
            .setPosition(domain.position)
            .setRequestedDisplayMode(domain.requestedDisplayMode?.name.orEmpty())
            .build()

    // Additive, tolerant parsing: blank (missing proto field) and unknown mode
    // names (e.g. from a future app version) both decode to null instead of
    // throwing, so old and new persisted proto data both remain loadable.
    private fun parseDisplayMode(value: String): DashboardCardDisplayMode? =
        value.takeIf(String::isNotBlank)?.let { stored ->
            runCatching { DashboardCardDisplayMode.valueOf(stored) }.getOrNull()
        }
}
