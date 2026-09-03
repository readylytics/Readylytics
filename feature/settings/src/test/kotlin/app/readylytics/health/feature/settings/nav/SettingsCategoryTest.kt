package app.readylytics.health.feature.settings.nav

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCategoryTest {
    @Test
    fun `every category has exactly eight distinct entries`() {
        assertEquals(8, SettingsCategoryId.entries.size)
        assertEquals(8, SettingsCategoryId.entries.toSet().size)
    }

    @Test
    fun `every category has a distinct title and subtitle resource`() {
        val titleRes = SettingsCategoryId.entries.map { it.titleRes }
        val subtitleRes = SettingsCategoryId.entries.map { it.subtitleRes }
        assertEquals(titleRes.size, titleRes.toSet().size)
        assertEquals(subtitleRes.size, subtitleRes.toSet().size)
        assertTrue(titleRes.none { it == 0 })
        assertTrue(subtitleRes.none { it == 0 })
    }

    @Test
    fun `SettingsCategoryId serializes and deserializes via kotlinx serialization`() {
        for (category in SettingsCategoryId.entries) {
            val encoded = Json.encodeToString(category)
            val decoded = Json.decodeFromString<SettingsCategoryId>(encoded)
            assertEquals(category, decoded)
        }
    }
}
