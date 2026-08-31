package app.readylytics.health.core.model.domain.workouts.detail

import org.junit.Test
import kotlin.test.assertEquals

class WorkoutLayoutTypeMapperTest {
    @Test
    fun `numeric health connect ids map to their group`() {
        assertEquals(WorkoutLayoutType.RUNNING, WorkoutLayoutTypeMapper.fromExerciseType("56"))
        assertEquals(WorkoutLayoutType.WALKING, WorkoutLayoutTypeMapper.fromExerciseType("79"))
        assertEquals(WorkoutLayoutType.CYCLING, WorkoutLayoutTypeMapper.fromExerciseType("8"))
        assertEquals(WorkoutLayoutType.SWIMMING, WorkoutLayoutTypeMapper.fromExerciseType("73"))
        assertEquals(WorkoutLayoutType.SWIMMING, WorkoutLayoutTypeMapper.fromExerciseType("74"))
        assertEquals(WorkoutLayoutType.STRENGTH, WorkoutLayoutTypeMapper.fromExerciseType("70"))
        assertEquals(WorkoutLayoutType.HIKING, WorkoutLayoutTypeMapper.fromExerciseType("37"))
        assertEquals(WorkoutLayoutType.YOGA, WorkoutLayoutTypeMapper.fromExerciseType("83"))
        assertEquals(WorkoutLayoutType.PILATES, WorkoutLayoutTypeMapper.fromExerciseType("48"))
        assertEquals(WorkoutLayoutType.ELLIPTICAL, WorkoutLayoutTypeMapper.fromExerciseType("25"))
        assertEquals(WorkoutLayoutType.ROWING, WorkoutLayoutTypeMapper.fromExerciseType("54"))
        assertEquals(WorkoutLayoutType.STAIRS, WorkoutLayoutTypeMapper.fromExerciseType("68"))
        assertEquals(WorkoutLayoutType.STAIRS, WorkoutLayoutTypeMapper.fromExerciseType("69"))
        assertEquals(WorkoutLayoutType.HIIT, WorkoutLayoutTypeMapper.fromExerciseType("36"))
    }

    @Test
    fun `string names map case-insensitively and tolerate whitespace and prefix`() {
        assertEquals(WorkoutLayoutType.RUNNING, WorkoutLayoutTypeMapper.fromExerciseType("Running"))
        assertEquals(WorkoutLayoutType.RUNNING, WorkoutLayoutTypeMapper.fromExerciseType("  RUNNING  "))
        assertEquals(WorkoutLayoutType.RUNNING, WorkoutLayoutTypeMapper.fromExerciseType("EXERCISE_TYPE_RUNNING"))
        assertEquals(WorkoutLayoutType.CYCLING, WorkoutLayoutTypeMapper.fromExerciseType("cycling"))
    }

    @Test
    fun `unknown and blank input falls back to OTHER`() {
        assertEquals(WorkoutLayoutType.OTHER, WorkoutLayoutTypeMapper.fromExerciseType("999"))
        assertEquals(WorkoutLayoutType.OTHER, WorkoutLayoutTypeMapper.fromExerciseType("Kitesurfing"))
        assertEquals(WorkoutLayoutType.OTHER, WorkoutLayoutTypeMapper.fromExerciseType(""))
    }
}
