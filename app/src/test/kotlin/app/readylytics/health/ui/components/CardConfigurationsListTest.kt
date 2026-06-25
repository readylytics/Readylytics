package app.readylytics.health.ui.components

import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit-level guard for the `@Immutable` stability wrapper that lets [ReorderableCardGrid] skip
 * recomposition on equal data. [CardConfigurationsList] wraps `List<CardConfiguration>` so that a
 * re-emitted-but-equal list compares *structurally* equal and Compose can skip readers. If this
 * stops being a structural `data class` wrapper (or wraps a non-structural collection), that
 * recomposition guarantee silently regresses — these pure-JVM assertions fail fast if it does.
 */
class CardConfigurationsListTest {
    private fun cfg(
        id: CardId,
        position: Int,
    ) = CardConfiguration(cardId = id, isVisible = true, position = position)

    @Test
    fun `wrappers over structurally equal item lists are equal`() {
        val a = CardConfigurationsList(listOf(cfg(CardId.INSIGHTS, 0), cfg(CardId.STEPS, 1)))
        val b = CardConfigurationsList(listOf(cfg(CardId.INSIGHTS, 0), cfg(CardId.STEPS, 1)))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `wrappers over differing item lists are not equal`() {
        val a = CardConfigurationsList(listOf(cfg(CardId.INSIGHTS, 0)))
        val differentPosition = CardConfigurationsList(listOf(cfg(CardId.INSIGHTS, 1)))
        val differentOrder =
            CardConfigurationsList(listOf(cfg(CardId.STEPS, 1), cfg(CardId.INSIGHTS, 0)))

        assertNotEquals(a, differentPosition)
        assertNotEquals(
            CardConfigurationsList(listOf(cfg(CardId.INSIGHTS, 0), cfg(CardId.STEPS, 1))),
            differentOrder,
        )
    }
}
