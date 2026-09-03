package app.readylytics.health.feature.settings.search

import app.readylytics.health.feature.settings.nav.SettingsCategoryId
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSearchMatchingTest {
    private val items =
        listOf(
            ResolvedSearchItem("sleep_goal", SettingsCategoryId.SLEEP, "Sleep goal", listOf("hours", "bedtime")),
            ResolvedSearchItem(
                "training_ras_scaling",
                SettingsCategoryId.TRAINING,
                "RAS scaling factor",
                listOf("resting autonomic stress"),
            ),
            ResolvedSearchItem("display_week_start_day", SettingsCategoryId.DISPLAY, "Week start day", emptyList()),
        )

    @Test
    fun `blank query returns no results`() {
        assertEquals(emptyList<ResolvedSearchItem>(), matchSettingsItems(items, ""))
        assertEquals(emptyList<ResolvedSearchItem>(), matchSettingsItems(items, "   "))
    }

    @Test
    fun `query matches label case-insensitively`() {
        val result = matchSettingsItems(items, "week START")
        assertEquals(listOf("display_week_start_day"), result.map { it.id })
    }

    @Test
    fun `query matches a keyword even when the label does not contain it`() {
        val result = matchSettingsItems(items, "autonomic")
        assertEquals(listOf("training_ras_scaling"), result.map { it.id })
    }

    @Test
    fun `query matching multiple items returns all of them`() {
        val result = matchSettingsItems(items, "s")
        assertEquals(
            setOf("sleep_goal", "training_ras_scaling", "display_week_start_day"),
            result.map { it.id }.toSet(),
        )
    }

    @Test
    fun `query matching nothing returns empty list`() {
        assertEquals(emptyList<ResolvedSearchItem>(), matchSettingsItems(items, "xyz-no-match"))
    }
}
