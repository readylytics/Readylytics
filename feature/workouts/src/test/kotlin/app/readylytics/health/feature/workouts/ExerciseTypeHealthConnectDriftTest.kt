package app.readylytics.health.feature.workouts

import androidx.health.connect.client.records.ExerciseSessionRecord
import app.readylytics.health.core.model.domain.workouts.detail.ExerciseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Pins [ExerciseType] to the Health Connect library rather than to a hand-maintained subset.
 *
 * The badminton bug ("2" rendered as the headline) existed because the only id table in the app
 * covered 14 of Health Connect's exercise types. Reflecting over the library's own constants means
 * a missing or misnumbered entry fails here instead of reaching a user's screen.
 */
class ExerciseTypeHealthConnectDriftTest {
    private val healthConnectTypes: Map<String, Int> =
        ExerciseSessionRecord::class.java.declaredFields
            .filter { field ->
                Modifier.isStatic(field.modifiers) &&
                    field.name.startsWith(EXERCISE_TYPE_PREFIX) &&
                    field.type == Int::class.javaPrimitiveType
            }.associate { field ->
                field.isAccessible = true
                field.name.removePrefix(EXERCISE_TYPE_PREFIX) to field.getInt(null)
            }

    @Test
    fun reflectionFindsTheHealthConnectConstants() {
        assertTrue(
            "Expected to reflect over the Health Connect exercise types, found ${healthConnectTypes.size}",
            healthConnectTypes.size >= MIN_EXPECTED_TYPES,
        )
        assertEquals(2, healthConnectTypes["BADMINTON"])
    }

    @Test
    fun everyHealthConnectTypeIsInTheTable() {
        val known = ExerciseType.entries.associateBy { it.name }
        val missing = healthConnectTypes.keys.filterNot { known.containsKey(it) }.sorted()
        assertTrue("ExerciseType is missing Health Connect types: $missing", missing.isEmpty())
    }

    @Test
    fun everyTableEntryUsesTheHealthConnectId() {
        val mismatched =
            ExerciseType.entries.mapNotNull { type ->
                val hcId = healthConnectTypes[type.name] ?: return@mapNotNull "${type.name} (not a Health Connect type)"
                if (hcId != type.hcId) "${type.name} is ${type.hcId}, Health Connect says $hcId" else null
            }
        assertTrue("ExerciseType ids disagree with Health Connect: $mismatched", mismatched.isEmpty())
    }

    @Test
    fun everyTypeHasItsOwnStringResource() {
        val resIds = ExerciseType.entries.associateWith { it.displayNameResId }
        resIds.forEach { (type, resId) ->
            assertTrue("${type.name} has no string resource", resId != 0)
        }
        val duplicates =
            resIds.entries
                .groupBy { it.value }
                .filterValues { it.size > 1 }
                .values
                .map { group -> group.map { it.key.name } }
        assertTrue("Exercise types share a string resource: $duplicates", duplicates.isEmpty())
    }

    private companion object {
        const val EXERCISE_TYPE_PREFIX = "EXERCISE_TYPE_"

        /** Health Connect 1.1.0 declares 61; guards against reflection silently finding nothing. */
        const val MIN_EXPECTED_TYPES = 50
    }
}
