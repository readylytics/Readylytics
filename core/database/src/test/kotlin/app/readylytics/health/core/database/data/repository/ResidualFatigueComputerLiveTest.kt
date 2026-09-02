package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.FatigueWorkoutInput
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.ZoneId
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResidualFatigueComputerLiveTest {
    private val dataLoader = mockk<ScoringDayDataLoader>()
    private val useCase = ComputeResidualFatigueUseCase()
    private val computer = ResidualFatigueComputer(dataLoader, useCase)
    private val zoneId = ZoneId.of("UTC")
    private val prefs =
        UserPreferences(
            scoringZoneId = zoneId.id,
            residualFatigueHalfLifeHours = 24f,
            residualFatigueGain = 1f,
        )

    @Test
    fun `computeLive decays through the exact instant passed, not next-day midnight`() =
        runTest {
            val workoutEndMs = 1_700_000_000_000L
            val nowMs = workoutEndMs + 3 * 3_600_000L // 3 hours after the workout, mid-afternoon

            coEvery { dataLoader.loadCanonicalFatigueInputsThrough(nowMs) } returns
                listOf(FatigueWorkoutInput(workoutId = "w1", endTimeMs = workoutEndMs, trimp = 100f))
            coEvery {
                dataLoader.loadUnbackfilledCountThrough(
                    retentionStartMs = any(),
                    evaluationTimeMs = nowMs,
                )
            } returns 0

            val result = computer.computeLive(nowMs, prefs)

            val expected = (100f * 2.0.pow(-3.0 / 24.0)).toFloat()
            assertEquals(expected, requireNotNull(result), 0.01f)
        }

    @Test
    fun `computeLive returns null when retained history has an unbackfilled gap`() =
        runTest {
            val nowMs = 1_700_000_000_000L
            coEvery {
                dataLoader.loadUnbackfilledCountThrough(
                    retentionStartMs = any(),
                    evaluationTimeMs = nowMs,
                )
            } returns 1

            val result = computer.computeLive(nowMs, prefs)

            assertNull(result)
        }
}
