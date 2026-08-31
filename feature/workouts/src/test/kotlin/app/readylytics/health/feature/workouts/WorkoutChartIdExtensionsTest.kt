package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.workouts.WorkoutChartId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

    @Test
    fun `RESIDUAL_FATIGUE_CURVE has valid display name and is hidden by default`() {
        assertEquals(
            R.string.chart_residual_fatigue_curve_title,
            WorkoutChartId.RESIDUAL_FATIGUE_CURVE.displayNameResId,
        )
        val defaultChart =
            SettingsDefaults.DEFAULT_WORKOUT_CHARTS.firstOrNull {
                it.chartId ==
                    WorkoutChartId.RESIDUAL_FATIGUE_CURVE
            }
        assertNotNull(defaultChart)
        assertFalse(requireNotNull(defaultChart).isVisible)
    }
}
