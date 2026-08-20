package app.readylytics.health.feature.workouts

import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase.HeartRateSample
import app.readylytics.health.domain.repository.HeartRateRecordData
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.WorkoutData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

class WorkoutHeartRateBatcherTest {
    private fun workout(
        id: String,
        startTime: Long,
        endTime: Long,
    ) = WorkoutData(
        id = id,
        startTime = startTime,
        endTime = endTime,
        exerciseType = "running",
        durationMinutes = ((endTime - startTime) / 60_000L).toInt(),
        zone1Minutes = 0f,
        zone2Minutes = 0f,
        zone3Minutes = 0f,
        zone4Minutes = 0f,
        zone5Minutes = 0f,
        trimp = 0f,
        avgHr = 0f,
    )

    private fun sample(
        timestampMs: Long,
        bpm: Int = 120,
        id: String = "hr-$timestampMs",
    ) = HeartRateRecordData(
        id = id,
        timestampMs = timestampMs,
        beatsPerMinute = bpm,
        recordType = "WORKOUT",
    )

    // -- clusterWorkoutsBySpan --

    @Test
    fun `clustering an empty list returns no clusters`() {
        assertEquals(emptyList<List<WorkoutData>>(), clusterWorkoutsBySpan(emptyList()))
    }

    @Test
    fun `single workout forms its own cluster`() {
        val w = workout("w1", 1_000L, 2_000L)
        val clusters = clusterWorkoutsBySpan(listOf(w))
        assertEquals(listOf(listOf(w)), clusters)
    }

    @Test
    fun `close-together workouts merge into one cluster`() {
        val dayMs = TimeUnit.DAYS.toMillis(1)
        val w1 = workout("w1", 0L, dayMs)
        val w2 = workout("w2", dayMs * 2, dayMs * 3)
        val w3 = workout("w3", dayMs * 5, dayMs * 6)

        val clusters = clusterWorkoutsBySpan(listOf(w3, w1, w2), spanGuardMs = dayMs * 10)

        assertEquals(1, clusters.size)
        assertEquals(listOf(w1, w2, w3), clusters.single())
    }

    @Test
    fun `workouts whose combined span exceeds the guard split into separate clusters`() {
        val dayMs = TimeUnit.DAYS.toMillis(1)
        val guard = dayMs * 45
        val w1 = workout("w1", 0L, dayMs)
        val w2 = workout("w2", guard + dayMs, guard + dayMs * 2)

        val clusters = clusterWorkoutsBySpan(listOf(w1, w2), spanGuardMs = guard)

        assertEquals(2, clusters.size)
        assertEquals(listOf(w1), clusters[0])
        assertEquals(listOf(w2), clusters[1])
    }

    @Test
    fun `overlapping workouts land in the same cluster`() {
        val w1 = workout("w1", 0L, 10_000L)
        val w2 = workout("w2", 5_000L, 15_000L)

        val clusters = clusterWorkoutsBySpan(listOf(w1, w2))

        assertEquals(1, clusters.size)
        assertEquals(setOf(w1, w2), clusters.single().toSet())
    }

    // -- sliceSamplesForWorkout --

    @Test
    fun `slicing includes samples exactly at the inclusive boundaries`() {
        val w = workout("w1", 1_000L, 2_000L)
        val samples = listOf(sample(999L), sample(1_000L), sample(1_500L), sample(2_000L), sample(2_001L))

        val sliced = sliceSamplesForWorkout(samples, w)

        assertEquals(listOf(1_000L, 1_500L, 2_000L), sliced.map { it.timestampMs })
    }

    @Test
    fun `slicing an empty sample list returns empty`() {
        val w = workout("w1", 1_000L, 2_000L)
        assertTrue(sliceSamplesForWorkout(emptyList(), w).isEmpty())
    }

    @Test
    fun `slicing excludes samples with no overlap`() {
        val w = workout("w1", 1_000L, 2_000L)
        val samples = listOf(sample(0L), sample(3_000L))

        assertTrue(sliceSamplesForWorkout(samples, w).isEmpty())
    }

    @Test
    fun `overlapping workouts each slice their own shared samples`() {
        val w1 = workout("w1", 0L, 10_000L)
        val w2 = workout("w2", 5_000L, 15_000L)
        val samples = listOf(sample(1_000L), sample(7_000L), sample(12_000L))

        val slicedW1 = sliceSamplesForWorkout(samples, w1)
        val slicedW2 = sliceSamplesForWorkout(samples, w2)

        assertEquals(listOf(1_000L, 7_000L), slicedW1.map { it.timestampMs })
        assertEquals(listOf(7_000L, 12_000L), slicedW2.map { it.timestampMs })
    }

    // -- fetchHeartRateSamplesByWorkout --

    @Test
    fun `no workouts issues no queries`() =
        runTest {
            val repo = mockk<HeartRateRepository>()

            val result = fetchHeartRateSamplesByWorkout(emptyList(), repo)

            assertTrue(result.isEmpty())
            coVerify(exactly = 0) { repo.getByTimeRange(any(), any()) }
        }

    @Test
    fun `matches naive per-workout fetch for a single workout`() =
        runTest {
            val w = workout("w1", 1_000L, 2_000L)
            val repo =
                mockk<HeartRateRepository> {
                    coEvery { getByTimeRange(1_000L, 2_000L) } returns listOf(sample(1_500L, bpm = 140))
                }

            val result = fetchHeartRateSamplesByWorkout(listOf(w), repo)

            assertEquals(
                listOf(HeartRateSample(timestamp = Instant.ofEpochMilli(1_500L), bpm = 140)),
                result["w1"],
            )
            coVerify(exactly = 1) { repo.getByTimeRange(any(), any()) }
        }

    @Test
    fun `batches close-together workouts into a single query with correct per-workout partitioning`() =
        runTest {
            val dayMs = TimeUnit.DAYS.toMillis(1)
            val w1 = workout("w1", 0L, dayMs)
            val w2 = workout("w2", dayMs * 2, dayMs * 2 + 1_000L)
            val w3 = workout("w3", dayMs * 4, dayMs * 4 + 1_000L)
            val allSamples =
                listOf(
                    sample(dayMs / 2, bpm = 100),
                    sample(dayMs * 2 + 500L, bpm = 150),
                    sample(dayMs * 4 + 500L, bpm = 160),
                    sample(dayMs * 10, bpm = 999), // outside every workout, must be dropped
                )
            val repo =
                mockk<HeartRateRepository> {
                    coEvery { getByTimeRange(0L, dayMs * 4 + 1_000L) } returns allSamples
                }

            val result = fetchHeartRateSamplesByWorkout(listOf(w1, w2, w3), repo)

            assertEquals(listOf(100), result.getValue("w1").map { it.bpm })
            assertEquals(listOf(150), result.getValue("w2").map { it.bpm })
            assertEquals(listOf(160), result.getValue("w3").map { it.bpm })
            coVerify(exactly = 1) { repo.getByTimeRange(any(), any()) }
        }

    @Test
    fun `workouts far apart issue one query per cluster instead of one dragnet query`() =
        runTest {
            val dayMs = TimeUnit.DAYS.toMillis(1)
            val guard = dayMs * 45
            val w1 = workout("w1", 0L, dayMs)
            val w2 = workout("w2", guard + dayMs, guard + dayMs * 2)
            val repo =
                mockk<HeartRateRepository> {
                    coEvery { getByTimeRange(0L, dayMs) } returns listOf(sample(dayMs / 2, bpm = 111))
                    coEvery { getByTimeRange(guard + dayMs, guard + dayMs * 2) } returns
                        listOf(sample(guard + dayMs + 500L, bpm = 222))
                }

            val result = fetchHeartRateSamplesByWorkout(listOf(w1, w2), repo)

            assertEquals(listOf(111), result.getValue("w1").map { it.bpm })
            assertEquals(listOf(222), result.getValue("w2").map { it.bpm })
            coVerify(exactly = 2) { repo.getByTimeRange(any(), any()) }
        }

    @Test
    fun `overlapping workouts share samples from the same cluster fetch`() =
        runTest {
            val w1 = workout("w1", 0L, 10_000L)
            val w2 = workout("w2", 5_000L, 15_000L)
            val repo =
                mockk<HeartRateRepository> {
                    coEvery { getByTimeRange(0L, 15_000L) } returns
                        listOf(sample(1_000L, bpm = 100), sample(7_000L, bpm = 110), sample(12_000L, bpm = 120))
                }

            val result = fetchHeartRateSamplesByWorkout(listOf(w1, w2), repo)

            assertEquals(listOf(100, 110), result.getValue("w1").map { it.bpm })
            assertEquals(listOf(110, 120), result.getValue("w2").map { it.bpm })
            coVerify(exactly = 1) { repo.getByTimeRange(any(), any()) }
        }
}
