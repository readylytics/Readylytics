package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.model.domain.repository.ScoringRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class GetCurrentResidualFatigueUseCaseTest {
    private val scoringRepository = mockk<ScoringRepository>()
    private val zoneId = ZoneId.of("UTC")
    private val fixedInstant = Instant.parse("2026-08-31T15:00:00Z")
    private val clock = Clock.fixed(fixedInstant, zoneId)
    private val useCase = GetCurrentResidualFatigueUseCase(scoringRepository, clock)

    @Test
    fun `returns the live value for today`() =
        runTest {
            coEvery { scoringRepository.computeCurrentResidualFatigue(fixedInstant.toEpochMilli()) } returns 97.8f

            val result = useCase(LocalDate.of(2026, 8, 31), zoneId)

            assertEquals(97.8f, result)
            coVerify { scoringRepository.computeCurrentResidualFatigue(fixedInstant.toEpochMilli()) }
        }

    @Test
    fun `returns null for a past day without querying the repository`() =
        runTest {
            val result = useCase(LocalDate.of(2026, 8, 30), zoneId)

            assertNull(result)
            coVerify(exactly = 0) { scoringRepository.computeCurrentResidualFatigue(any()) }
        }

    @Test
    fun `returns null for a future day without querying the repository`() =
        runTest {
            val result = useCase(LocalDate.of(2026, 9, 1), zoneId)

            assertNull(result)
            coVerify(exactly = 0) { scoringRepository.computeCurrentResidualFatigue(any()) }
        }
}
