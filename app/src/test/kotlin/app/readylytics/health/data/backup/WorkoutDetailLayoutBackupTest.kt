package app.readylytics.health.data.backup

import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.domain.workouts.detail.WorkoutDetailItemId
import app.readylytics.health.domain.workouts.detail.WorkoutLayoutType
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals

class WorkoutDetailLayoutBackupTest {
    private val writer = Json { encodeDefaults = true }
    private val reader = Json { ignoreUnknownKeys = true }

    @Test
    fun `workout detail layouts survive a json round trip`() {
        val layout =
            SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS.map { item ->
                if (item.itemId == WorkoutDetailItemId.RAS) item.copy(isVisible = false) else item
            }
        val backup =
            UserPreferencesBackup(
                workoutDetailLayouts = mapOf(WorkoutLayoutType.RUNNING.name to layout),
            )

        val encoded = writer.encodeToString(UserPreferencesBackup.serializer(), backup)
        val decoded = reader.decodeFromString(UserPreferencesBackup.serializer(), encoded)

        assertEquals(backup, decoded)
    }

    @Test
    fun `a backup written before this feature decodes with a null layout map`() {
        val decoded =
            reader.decodeFromString(UserPreferencesBackup.serializer(), """{"goalSleepHours":8.0}""")

        assertEquals(null, decoded.workoutDetailLayouts)
    }
}
