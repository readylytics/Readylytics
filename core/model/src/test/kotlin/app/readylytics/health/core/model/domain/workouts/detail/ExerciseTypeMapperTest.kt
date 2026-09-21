package app.readylytics.health.core.model.domain.workouts.detail

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExerciseTypeMapperTest {
    @Test
    fun `numeric health connect ids resolve to their exercise type`() {
        // The bug this guards: badminton is EXERCISE_TYPE_BADMINTON = 2, and the stored value is
        // the string "2". It used to fall through every table and render as the literal "2".
        assertEquals(ExerciseType.BADMINTON, ExerciseTypeMapper.fromRaw("2"))
        assertEquals(ExerciseType.RUNNING, ExerciseTypeMapper.fromRaw("56"))
        assertEquals(ExerciseType.TENNIS, ExerciseTypeMapper.fromRaw("76"))
        assertEquals(ExerciseType.SOCCER, ExerciseTypeMapper.fromRaw("64"))
        assertEquals(ExerciseType.OTHER_WORKOUT, ExerciseTypeMapper.fromRaw("0"))
    }

    @Test
    fun `symbolic and free-text names resolve case-insensitively`() {
        assertEquals(ExerciseType.BADMINTON, ExerciseTypeMapper.fromRaw("EXERCISE_TYPE_BADMINTON"))
        assertEquals(ExerciseType.BADMINTON, ExerciseTypeMapper.fromRaw("  badminton  "))
        assertEquals(ExerciseType.RUNNING, ExerciseTypeMapper.fromRaw("Running"))
        assertEquals(ExerciseType.RUNNING_TREADMILL, ExerciseTypeMapper.fromRaw("EXERCISE_TYPE_RUNNING_TREADMILL"))
        assertEquals(ExerciseType.STRENGTH_TRAINING, ExerciseTypeMapper.fromRaw("strength"))
        assertEquals(ExerciseType.BIKING, ExerciseTypeMapper.fromRaw("cycling"))
    }

    @Test
    fun `unknown and blank input falls back to the catch-all type`() {
        assertEquals(ExerciseType.OTHER_WORKOUT, ExerciseTypeMapper.fromRaw("999"))
        assertEquals(ExerciseType.OTHER_WORKOUT, ExerciseTypeMapper.fromRaw("-1"))
        assertEquals(ExerciseType.OTHER_WORKOUT, ExerciseTypeMapper.fromRaw("Kitesurfing"))
        assertEquals(ExerciseType.OTHER_WORKOUT, ExerciseTypeMapper.fromRaw(""))
        assertEquals(ExerciseType.OTHER_WORKOUT, ExerciseTypeMapper.fromRaw("   "))
    }

    @Test
    fun `health connect ids are unique across the table`() {
        val byId = ExerciseType.entries.groupBy { it.hcId }.filterValues { it.size > 1 }
        assertTrue(byId.isEmpty(), "Duplicate Health Connect ids: $byId")
    }

    @Test
    fun `every type has a non-blank canonical name`() {
        ExerciseType.entries.forEach { type ->
            assertTrue(type.canonicalName.isNotBlank(), "${type.name} has a blank canonical name")
            assertTrue(
                type.canonicalName.toIntOrNull() == null,
                "${type.name} canonical name must not be a bare id",
            )
        }
    }

    @Test
    fun `every type round-trips through its own id and name`() {
        ExerciseType.entries.forEach { type ->
            assertEquals(type, ExerciseTypeMapper.fromRaw(type.hcId.toString()))
            assertEquals(type, ExerciseTypeMapper.fromRaw("EXERCISE_TYPE_${type.name}"))
        }
    }

    @Test
    fun `layout grouping is preserved for the previously mapped ids`() {
        // Regression fence on collapsing WorkoutLayoutTypeMapper onto ExerciseType: these are the
        // ids the old hand-written table covered and their groups must not move.
        val expected =
            mapOf(
                "56" to WorkoutLayoutType.RUNNING,
                "79" to WorkoutLayoutType.WALKING,
                "8" to WorkoutLayoutType.CYCLING,
                "73" to WorkoutLayoutType.SWIMMING,
                "74" to WorkoutLayoutType.SWIMMING,
                "70" to WorkoutLayoutType.STRENGTH,
                "37" to WorkoutLayoutType.HIKING,
                "83" to WorkoutLayoutType.YOGA,
                "48" to WorkoutLayoutType.PILATES,
                "25" to WorkoutLayoutType.ELLIPTICAL,
                "54" to WorkoutLayoutType.ROWING,
                "68" to WorkoutLayoutType.STAIRS,
                "69" to WorkoutLayoutType.STAIRS,
                "36" to WorkoutLayoutType.HIIT,
            )
        expected.forEach { (raw, layout) ->
            assertEquals(layout, WorkoutLayoutTypeMapper.fromExerciseType(raw), "layout for id $raw")
        }
    }

    @Test
    fun `ungrouped types still render a real name`() {
        // Badminton has no bespoke detail layout, but it must not lose its label because of that.
        val badminton = ExerciseTypeMapper.fromRaw("2")
        assertEquals(WorkoutLayoutType.OTHER, badminton.layoutType)
        assertEquals("Badminton", badminton.canonicalName)
        assertNotNull(ExerciseType.entries.firstOrNull { it.hcId == 2 })
    }
}
