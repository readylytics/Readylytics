package com.gregor.lauritz.healthdashboard.data.preferences

import com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId

object CardConfigurationMapper {
    fun toDomain(proto: CardConfigurationProto): CardConfiguration? {
        val cardId =
            try {
                CardId.valueOf(proto.cardId)
            } catch (e: IllegalArgumentException) {
                return null
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
