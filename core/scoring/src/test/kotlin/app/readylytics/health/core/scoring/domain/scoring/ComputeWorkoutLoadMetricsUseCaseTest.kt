package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.scoring.CanonicalWorkoutResult
import app.readylytics.health.core.model.domain.scoring.WorkoutHrQuality
import app.readylytics.health.core.model.domain.scoring.WorkoutIntensityLevel
import app.readylytics.health.core.model.domain.scoring.WorkoutLoadLevel
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComputeWorkoutLoadMetricsUseCaseTest {
    private val scoringCalculator = mockk<ScoringCalculator>()
    private val workoutLoadClassifier = WorkoutLoadClassifier()
    private val useCase =
        ComputeWorkoutLoadMetricsUseCase(
            scoringCalculator,
            workoutLoadClassifier,
        )

    @Test
    fun `uses stored workout trimp and returns rounded display values`() {
        val workout =
            WorkoutData(
                id = "run-1",
                startTime = 1_000L,
                endTime = 3_601_000L,
                exerciseType = "RUNNING",
                durationMinutes = 60,
                zone1Minutes = 0f,
                zone2Minutes = 10f,
                zone3Minutes = 20f,
                zone4Minutes = 30f,
                zone5Minutes = 0f,
                trimp = 115.6f,
                avgHr = 134f,
            )
        val workoutDate = LocalDate.of(2026, 6, 9)
        val trimpByDate = mapOf(workoutDate to 115.6f)

        every { scoringCalculator.computeAtlEmaWithDecay(any(), workoutDate, any()) } returnsMany listOf(2f, 1f)
        every { scoringCalculator.computeCtlEmaWithDecay(any(), workoutDate, any()) } returnsMany listOf(1f, 1f)
        every { scoringCalculator.computeStrainRatio(any(), any()) } returnsMany listOf(1.365f, 1.0f)

        val canonicalResult =
            CanonicalWorkoutResult(
                workoutId = workout.id,
                endTimeMs = workout.endTime,
                trimp = 115.6f,
                quality = WorkoutHrQuality.RAW,
                sourceRevision = 0L,
                scoringSnapshotId = "test-snap",
                algorithmRevision = 1,
            )

        val result =
            useCase.execute(
                workout = workout,
                workoutDate = workoutDate,
                canonicalResult = canonicalResult,
                trimpByDate = trimpByDate,
            )

        assertEquals(115.6f, result.preciseTrimp)
        assertEquals(116, result.roundedTrimp)
        assertEquals(0.365f, result.preciseGainedStrain)
        assertEquals(0.37f, result.roundedGainedStrain)
        assertEquals("0.37", result.gainedStrainDisplay)
        assertEquals(WorkoutLoadLevel.MODERATE, result.classification?.baseLoad)
        assertEquals(WorkoutIntensityLevel.HARD, result.classification?.intensity)
        assertEquals(WorkoutLoadLevel.HARD, result.classification?.finalLoad)
    }

    @Test
    fun `leaves intensity unavailable when avg hr is missing`() {
        val workout =
            WorkoutData(
                id = "run-2",
                startTime = 1_000L,
                endTime = 3_601_000L,
                exerciseType = "RUNNING",
                durationMinutes = 60,
                zone1Minutes = 0f,
                zone2Minutes = 10f,
                zone3Minutes = 20f,
                zone4Minutes = 30f,
                zone5Minutes = 0f,
                trimp = 50f,
                avgHr = 0f,
            )
        val workoutDate = LocalDate.of(2026, 6, 9)

        every { scoringCalculator.computeAtlEmaWithDecay(any(), workoutDate, any()) } returnsMany listOf(2f, 1f)
        every { scoringCalculator.computeCtlEmaWithDecay(any(), workoutDate, any()) } returnsMany listOf(1f, 1f)
        every { scoringCalculator.computeStrainRatio(any(), any()) } returnsMany listOf(1.365f, 1.0f)

        val canonicalResult =
            CanonicalWorkoutResult(
                workoutId = workout.id,
                endTimeMs = workout.endTime,
                trimp = 50f,
                quality = WorkoutHrQuality.RAW,
                sourceRevision = 0L,
                scoringSnapshotId = "test-snap",
                algorithmRevision = 1,
            )

        val result =
            useCase.execute(
                workout = workout,
                workoutDate = workoutDate,
                canonicalResult = canonicalResult,
                trimpByDate = mapOf(workoutDate to 50f),
            )

        assertEquals(WorkoutLoadLevel.LIGHT, result.classification?.finalLoad)
        assertEquals(null, result.classification?.intensity)
    }

    @Test
    fun `returns unavailable display metrics when canonical trimp is null`() {
        val workout =
            WorkoutData(
                id = "run-3",
                startTime = 1_000L,
                endTime = 3_601_000L,
                exerciseType = "RUNNING",
                durationMinutes = 60,
                zone1Minutes = 0f,
                zone2Minutes = 10f,
                zone3Minutes = 20f,
                zone4Minutes = 30f,
                zone5Minutes = 0f,
                trimp = 50f,
                avgHr = 140f,
            )
        val workoutDate = LocalDate.of(2026, 6, 9)

        val canonicalResult =
            CanonicalWorkoutResult(
                workoutId = workout.id,
                endTimeMs = workout.endTime,
                trimp = null,
                quality = WorkoutHrQuality.UNAVAILABLE,
                sourceRevision = 0L,
                scoringSnapshotId = "test-snap",
                algorithmRevision = 1,
            )

        val result =
            useCase.execute(
                workout = workout,
                workoutDate = workoutDate,
                canonicalResult = canonicalResult,
                trimpByDate = mapOf(workoutDate to 50f),
            )

        assertNull(result.preciseTrimp)
        assertNull(result.roundedTrimp)
        assertNull(result.preciseGainedStrain)
        assertNull(result.roundedGainedStrain)
        assertEquals("—", result.gainedStrainDisplay)
        assertNull(result.classification)
    }
}
