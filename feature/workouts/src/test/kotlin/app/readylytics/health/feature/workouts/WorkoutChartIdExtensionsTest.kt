package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.workouts.WorkoutChartId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WorkoutChartIdExtensionsTest {
    @Test
    fun `every WorkoutChartId has a non-zero displayNameResId`() {
        WorkoutChartId.entries.forEach { id ->
            val resId = id.displayNameResId
            assertNotEquals(0, resId)
        }
    }

    @Test
    fun `TRAINING_MIX maps to training_mix_title`() {
        assertEquals(R.string.training_mix_title, WorkoutChartId.TRAINING_MIX.displayNameResId)
    }
}
