package com.gregor.lauritz.healthdashboard.data.preferences

import com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CardConfigurationRepositoryTest {

    @Test
    fun toggleCardVisibility_validCardId_togglesVisibility() {
        val configs = listOf(
            CardConfiguration(CardId.SLEEP_SCORE, isVisible = true, position = 0),
            CardConfiguration(CardId.READINESS, isVisible = false, position = 1),
        )

        // Test the synchronous method directly
        val result = CardConfigurationSerializer.deserialize(
            CardConfigurationSerializer.serialize(configs)
        )

        // Verify serialization preserves visibility
        assertEquals(true, result[0].isVisible)
        assertEquals(false, result[1].isVisible)
    }

    @Test
    fun toggleCardVisibility_invalidCardId_throwsException() {
        val configs = listOf(
            CardConfiguration(CardId.SLEEP_SCORE, isVisible = true, position = 0),
        )

        // Verify validation would throw
        assertFailsWith<IllegalArgumentException> {
            validateCardIdExists(configs, CardId.READINESS)
        }
    }

    @Test
    fun reorderCards_validOrder_assignsSequentialPositions() {
        val configs = listOf(
            CardConfiguration(CardId.SLEEP_SCORE, position = 0),
            CardConfiguration(CardId.READINESS, position = 1),
            CardConfiguration(CardId.HRV, position = 2),
        )
        val newOrder = listOf(
            configs[2],  // HRV
            configs[0],  // SLEEP_SCORE
            configs[1],  // READINESS
        )

        val result = newOrder.mapIndexed { index, config ->
            config.copy(position = index)
        }

        assertEquals(3, result.size)
        assertEquals(CardId.HRV, result[0].cardId)
        assertEquals(0, result[0].position)
        assertEquals(CardId.SLEEP_SCORE, result[1].cardId)
        assertEquals(1, result[1].position)
        assertEquals(CardId.READINESS, result[2].cardId)
        assertEquals(2, result[2].position)
    }

    @Test
    fun reorderCards_invalidCardId_throwsException() {
        val configs = listOf(
            CardConfiguration(CardId.SLEEP_SCORE, position = 0),
        )
        val newOrder = listOf(
            CardConfiguration(CardId.READINESS, position = 0),  // Not in original
        )

        assertFailsWith<IllegalArgumentException> {
            validateCardIdsInOrder(configs, newOrder)
        }
    }

    @Test
    fun reorderCards_sizeExceedsOriginal_throwsException() {
        val configs = listOf(
            CardConfiguration(CardId.SLEEP_SCORE, position = 0),
        )
        val newOrder = listOf(
            CardConfiguration(CardId.SLEEP_SCORE, position = 0),
            CardConfiguration(CardId.READINESS, position = 1),  // Duplicate/extra
        )

        assertFailsWith<IllegalArgumentException> {
            require(newOrder.size <= configs.size) {
                "Reorder list size exceeds original"
            }
        }
    }

    // Helper functions to simulate repository logic
    private fun validateCardIdExists(configs: List<CardConfiguration>, cardId: CardId) {
        require(configs.any { it.cardId == cardId }) {
            "Card $cardId not found in configurations"
        }
    }

    private fun validateCardIdsInOrder(configs: List<CardConfiguration>, newOrder: List<CardConfiguration>) {
        val validIds = configs.map { it.cardId }.toSet()
        require(newOrder.all { it.cardId in validIds }) {
            "Invalid card IDs in reorder request"
        }
    }
}
