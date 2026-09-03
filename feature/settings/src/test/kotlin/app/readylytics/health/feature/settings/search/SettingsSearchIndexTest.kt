package app.readylytics.health.feature.settings.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchIndexTest {
    @Test
    fun `every search item id is unique`() {
        val ids = allSettingsSearchItems.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `there are exactly 39 searchable items across all categories`() {
        assertEquals(39, allSettingsSearchItems.size)
    }

    @Test
    fun `no search item has a blank label resource`() {
        assertTrue(allSettingsSearchItems.all { it.labelRes != 0 })
    }
}
