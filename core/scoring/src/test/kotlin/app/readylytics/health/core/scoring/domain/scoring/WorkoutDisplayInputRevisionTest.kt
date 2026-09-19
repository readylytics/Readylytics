package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.HeartRateRecordData
import app.readylytics.health.core.model.domain.repository.HeartRateRepository
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.scoring.CanonicalWorkoutResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutDisplayInputRevisionTest {
    private val summaries = mockk<DailySummaryRepository>()
    private val heartRate = mockk<HeartRateRepository>()
    private val metrics = mockk<ComputeWorkoutLoadMetricsUseCase>()
    private val results = mutableListOf<CanonicalWorkoutResult>()
    private val useCase =
        GetWorkoutDisplayMetricsUseCase(
            summaries,
            heartRate,
            mockk(),
            metrics,
            CanonicalWorkoutResolver(ComputeWorkoutTrimpUseCase()),
        )

    @Test
    fun `display does not reuse TRIMP after workout HR is deleted`() =
        runTest {
            val workout = workout()
            val samples =
                listOf(0L, 60_000L, 120_000L).map { time ->
                    HeartRateRecordData(
                        id = "hr-$time",
                        timestampMs = time,
                        beatsPerMinute = 130,
                        recordType = "EXERCISE",
                    )
                }
            prepare()
            coEvery { heartRate.getByTimeRange(any(), any()) } returns samples
            display(workout)
            val prior = results.last()
            assertNotNull(prior.trimp)
            coEvery { heartRate.getByTimeRange(any(), any()) } returns emptyList()

            display(cached(workout, prior))

            assertNull(results.last().trimp)
            assertNotEquals(prior.sourceRevision, results.last().sourceRevision)
        }

    @Test
    fun `display invalidates zero duration cached TRIMP when workout gains duration`() =
        runTest {
            val workout = workout().copy(endTime = 0L)
            prepare()
            coEvery { heartRate.getByTimeRange(any(), any()) } returns emptyList()
            display(workout)
            val prior = results.last()

            display(cached(workout, prior).copy(endTime = 120_000L))

            assertNull(results.last().trimp)
            assertNotEquals(prior.sourceRevision, results.last().sourceRevision)
        }

    private fun prepare() {
        coEvery { summaries.getByDate(any()) } returns null
        every { metrics.execute(any(), any(), any(), any()) } answers {
            results += thirdArg<CanonicalWorkoutResult>()
            ComputeWorkoutLoadMetricsUseCase.WorkoutLoadMetrics(null, null, null, null, "", null)
        }
    }

    private suspend fun display(workout: WorkoutData) =
        useCase.execute(
            workout,
            preferences = UserPreferences(),
            historicalSummaries = emptyList(),
        )

    private fun cached(
        workout: WorkoutData,
        prior: CanonicalWorkoutResult,
    ) = workout.copy(
        modelTrimp = prior.trimp,
        modelTrimpSourceRevision = prior.sourceRevision,
        modelTrimpSnapshotId = prior.scoringSnapshotId,
        modelTrimpAlgorithmRevision = prior.algorithmRevision,
        modelTrimpQuality = prior.quality.name,
    )

    private fun workout() =
        WorkoutData(
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
}
