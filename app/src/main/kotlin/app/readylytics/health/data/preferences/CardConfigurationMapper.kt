package app.readylytics.health.data.preferences

import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId

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
        )
    }

    fun toProto(domain: CardConfiguration): CardConfigurationProto =
        CardConfigurationProto
            .newBuilder()
            .setCardId(domain.cardId.name)
            .setIsVisible(domain.isVisible)
            .setPosition(domain.position)
            .build()
}
