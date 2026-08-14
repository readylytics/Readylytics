package app.readylytics.health.domain.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

private data class FakeConfig(
    override val id: String,
    override val isVisible: Boolean = true,
    override val position: Int = 0,
) : ReorderableItem<String>

class LayoutDefaultsMergerTest {
    @Test
    fun `returns stored unchanged when no defaults are missing`() {
        val stored = listOf(FakeConfig("a", position = 0), FakeConfig("b", position = 1))
        val result =
            LayoutDefaultsMerger.mergeWithDefaults(
                stored = stored,
                defaults = listOf(FakeConfig("a"), FakeConfig("b")),
                withPosition = { config, pos -> config.copy(position = pos) },
            )
        assertSame(stored, result)
    }

    @Test
    fun `appends missing defaults renumbered after the highest stored position`() {
        val stored = listOf(FakeConfig("a", position = 0), FakeConfig("b", position = 5))
        val defaults = listOf(FakeConfig("a"), FakeConfig("b"), FakeConfig("c"), FakeConfig("d"))
        val result =
            LayoutDefaultsMerger.mergeWithDefaults(
                stored = stored,
                defaults = defaults,
                withPosition = { config, pos -> config.copy(position = pos) },
            )
        assertEquals(listOf("a", "b", "c", "d"), result.map { it.id })
        assertEquals(6, result.first { it.id == "c" }.position)
        assertEquals(7, result.first { it.id == "d" }.position)
    }

    @Test
    fun `when stored is empty all defaults are appended starting at position 0`() {
        val defaults = listOf(FakeConfig("a"), FakeConfig("b"))
        val result =
            LayoutDefaultsMerger.mergeWithDefaults(
                stored = emptyList(),
                defaults = defaults,
                withPosition = { config, pos -> config.copy(position = pos) },
            )
        assertEquals(0, result.first { it.id == "a" }.position)
        assertEquals(1, result.first { it.id == "b" }.position)
    }

    @Test
    fun `preserves existing stored entries and their positions untouched`() {
        val stored = listOf(FakeConfig("a", isVisible = false, position = 3))
        val result =
            LayoutDefaultsMerger.mergeWithDefaults(
                stored = stored,
                defaults = listOf(FakeConfig("a"), FakeConfig("b")),
                withPosition = { config, pos -> config.copy(position = pos) },
            )
        val preserved = result.first { it.id == "a" }
        assertEquals(false, preserved.isVisible)
        assertEquals(3, preserved.position)
    }
}
