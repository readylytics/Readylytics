package app.readylytics.health.domain.workouts.detail

import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkoutDetailDefaultsTest {
    @Test
    fun `defaults contain every item exactly once`() {
        val ids = SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS.map { it.itemId }
        assertEquals(WorkoutDetailItemId.entries.size, ids.size)
        assertEquals(WorkoutDetailItemId.entries.toSet(), ids.toSet())
    }

    @Test
    fun `default positions are contiguous from zero and all items visible`() {
        val defaults = SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS
        assertEquals(defaults.indices.toList(), defaults.map { it.position })
        assertTrue(defaults.all { it.isVisible })
    }

    @Test
    fun `full width items are a subset of all items and exclude metric tiles`() {
        assertTrue(WorkoutDetailItemCatalog.FULL_WIDTH_ITEMS.all { it in WorkoutDetailItemId.entries })
        assertTrue(WorkoutDetailItemId.TRAINING_LOAD !in WorkoutDetailItemCatalog.FULL_WIDTH_ITEMS)
        assertTrue(WorkoutDetailItemId.ROUTE_CONTOUR in WorkoutDetailItemCatalog.FULL_WIDTH_ITEMS)
    }
}
