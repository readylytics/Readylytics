package app.readylytics.health.feature.settings.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightIndexResolutionTest {
    private val ids = listOf("a", "b", "c")

    @Test
    fun `null highlight id resolves to -1`() {
        assertEquals(-1, resolveHighlightIndex(ids, null))
    }

    @Test
    fun `unknown highlight id resolves to -1`() {
        assertEquals(-1, resolveHighlightIndex(ids, "missing"))
    }

    @Test
    fun `known highlight id resolves to its index`() {
        assertEquals(1, resolveHighlightIndex(ids, "b"))
    }
}
