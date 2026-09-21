package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.scoring.WorkoutHrQuality
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DailyTrimpInputRevisionTest {
    private val loader = mockk<ScoringDayDataLoader>()
    private val hrLoader = mockk<ScoringHeartRateDataLoader>()
    private val computer =
        DailyTrimpComputer(loader, hrLoader, ComputeDailyTrimpUseCase(ComputeWorkoutTrimpUseCase()), mockk())
    private val context =
        mockk<ScoringDayContext>(relaxed = true) {
            every { prefs } returns UserPreferences()
            every { initialBaselines.hrMax } returns 190f
            every { initialBaselines.frozenHrMax } returns 190f
            every { initialBaselines.rhrBaselineValue } returns 55f
        }

    @Test
    fun `deleted HR invalidates a previously computed workout`() =
        runTest {
            val workout = workout()
            val original = score(workout, samples())
            assertNotNull(original.dailyTrimpRaw)
            val cached = cached(workout, original)

            val afterDeletion = score(cached, emptyList())

            assertNull(afterDeletion.dailyTrimpRaw)
            assertNull(afterDeletion.workoutModelTrimpUpdates.single().modelTrimp)
            assertNotEquals(
                cached.modelTrimpSourceRevision,
                afterDeletion.workoutModelTrimpUpdates.single().sourceRevision,
            )
        }

    @Test
    fun `workout edits invalidate prior zero duration result without HR`() =
        runTest {
            val workout = workout().copy(endTime = 0L)
            val cached = cached(workout, score(workout, emptyList()))

            val afterEdit = score(cached.copy(endTime = 60_000L), emptyList())

            assertNull(afterEdit.dailyTrimpRaw)
            assertNotEquals(cached.modelTrimpSourceRevision, afterEdit.workoutModelTrimpUpdates.single().sourceRevision)
        }

    private suspend fun score(
        workout: WorkoutRecordEntity,
        samples: List<HeartRateRecordEntity>,
    ): DailyTrimpComputer.ProcessedWorkoutDay {
        coEvery { loader.loadWorkouts(any(), any()) } returns listOf(workout)
        coEvery { hrLoader.loadExerciseHrSamples(any()) } returns samples
        coEvery { hrLoader.loadWorkoutSamplesWithQuality(any(), any()) } returns
            LoadedWorkoutSamples(
                samples,
                if (samples.isEmpty()) WorkoutHrQuality.UNAVAILABLE else WorkoutHrQuality.RAW,
            )
        return computer.processWorkouts(context)
    }

    private fun cached(
        workout: WorkoutRecordEntity,
        result: DailyTrimpComputer.ProcessedWorkoutDay,
    ): WorkoutRecordEntity {
        val update = result.workoutModelTrimpUpdates.single()
        return workout.copy(
            modelTrimp = update.modelTrimp,
            modelTrimpSourceRevision = update.sourceRevision,
            modelTrimpSnapshotId = update.scoringSnapshotId,
            modelTrimpAlgorithmRevision = update.algorithmRevision,
            modelTrimpQuality = update.quality.name,
        )
    }

    private fun workout() =
        WorkoutRecordEntity(
            id = "workout",
            startTime = 0L,
            endTime = 120_000L,
            exerciseType = "RUNNING",
            durationMinutes = 2,
            zone1Minutes = 0f,
            zone2Minutes = 0f,
            zone3Minutes = 0f,
            zone4Minutes = 0f,
            zone5Minutes = 0f,
            trimp = 5f,
            avgHr = 130f,
        )

    private fun samples() =
        listOf(0L, 60_000L, 120_000L).map { time ->
            HeartRateRecordEntity(
                sourceRecordRef = 1L,
                timestampMs = time,
                beatsPerMinute = 130,
                recordType = "EXERCISE",
                sessionId = "workout",
            )
        }
}
