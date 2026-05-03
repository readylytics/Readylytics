package com.gregor.lauritz.healthdashboard.data.preferences

import com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId

object CardConfigurationMapper {
    fun toDomain(proto: CardConfigurationProto): CardConfiguration {
        return CardConfiguration(
            cardId = CardId.valueOf(proto.cardId),
            isVisible = proto.isVisible,
            position = proto.position
        )
    }

    fun toProto(domain: CardConfiguration): CardConfigurationProto {
        return CardConfigurationProto.newBuilder()
            .setCardId(domain.cardId.name)
            .setIsVisible(domain.isVisible)
            .setPosition(domain.position)
            .build()
    }
}
