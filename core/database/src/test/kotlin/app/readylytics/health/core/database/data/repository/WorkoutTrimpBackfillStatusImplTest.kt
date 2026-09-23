package app.readylytics.health.core.database.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The startup self-heal gate must only fire for never-evaluated workouts. A workout without HR
 * samples is evaluated to `modelTrimp = NULL` / quality `UNAVAILABLE`, which no recompute can
 * change; counting it would re-enqueue a full retained-history recompute on every cold start.
 */
@RunWith(AndroidJUnit4::class)
class WorkoutTrimpBackfillStatusImplTest {
    private lateinit var database: HealthDatabase
    private lateinit var status: WorkoutTrimpBackfillStatusImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, HealthDatabase::class.java).allowMainThreadQueries().build()
        status = WorkoutTrimpBackfillStatusImpl(database.workoutDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun evaluatedUnavailableWorkoutDoesNotTripGate() =
        runBlocking {
            database.workoutDao().upsertAll(
                listOf(workout("no-hr", RETENTION_START_MS + DAY_MS, modelTrimp = null, quality = "UNAVAILABLE")),
            )

            assertFalse(status.hasUnbackfilledWorkouts(RETENTION_START_MS))
        }

    @Test
    fun neverEvaluatedWorkoutTripsGate() =
        runBlocking {
            database.workoutDao().upsertAll(
                listOf(workout("fresh", RETENTION_START_MS + DAY_MS, modelTrimp = null, quality = null)),
            )

            assertTrue(status.hasUnbackfilledWorkouts(RETENTION_START_MS))
        }

    @Test
    fun backfilledWorkoutDoesNotTripGate() =
        runBlocking {
            database.workoutDao().upsertAll(
                listOf(workout("done", RETENTION_START_MS + DAY_MS, modelTrimp = 42f, quality = "RAW")),
            )

            assertFalse(status.hasUnbackfilledWorkouts(RETENTION_START_MS))
        }

    @Test
    fun neverEvaluatedWorkoutBeforeRetentionStartDoesNotTripGate() =
        runBlocking {
            database.workoutDao().upsertAll(
                listOf(workout("expired", RETENTION_START_MS - DAY_MS, modelTrimp = null, quality = null)),
            )

            assertFalse(status.hasUnbackfilledWorkouts(RETENTION_START_MS))
        }

    private fun workout(
        id: String,
        startMs: Long,
        modelTrimp: Float?,
        quality: String?,
    ) = WorkoutRecordEntity(
        id = id,
        startTime = startMs,
        endTime = startMs + HOUR_MS,
        exerciseType = "RUNNING",
        durationMinutes = 60,
        zone1Minutes = 0f,
        zone2Minutes = 0f,
        zone3Minutes = 0f,
        zone4Minutes = 0f,
        zone5Minutes = 0f,
        trimp = 0f,
        avgHr = 0f,
        modelTrimp = modelTrimp,
        modelTrimpQuality = quality,
    )

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
        const val RETENTION_START_MS = 1_750_000_000_000L
    }
}
