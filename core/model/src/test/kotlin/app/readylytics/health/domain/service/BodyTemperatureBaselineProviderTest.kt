package app.readylytics.health.domain.service

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.data.preferences.UserPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BodyTemperatureBaselineProviderTest {
    private val dailySummaryRepository = mockk<DailySummaryRepository>()
    private val userPreferencesReader =
        mockk<UserPreferencesReader> {
            every { userPreferences } returns flowOf(UserPreferences())
        }
    private val provider =
        BodyTemperatureBaselineProvider(
            dailySummaryRepository = dailySummaryRepository,
            userPreferencesReader = userPreferencesReader,
            calculator = BodyTemperatureBaselineCalculator(),
        )

    @Test
    fun `returns null when fewer than 14 days in the window have a value`() =
        runTest {
            val target = LocalDate.of(2026, 3, 1)
            coEvery { dailySummaryRepository.getSince(any()) } returns
                (1..13).map { day ->
                    DailySummary(date = target.minusDays(day.toLong()), avgSleepingBodyTemp = 36.5f)
                }

            assertNull(provider.getBaseline(target))
        }

    @Test
    fun `averages the 14 days before the target date, excluding the target date itself`() =
        runTest {
            val target = LocalDate.of(2026, 3, 1)
            val history =
                (1..14).map { day ->
                    DailySummary(date = target.minusDays(day.toLong()), avgSleepingBodyTemp = 36.0f + day * 0.01f)
                } +
                    // A same-day-as-target reading must be excluded even if getSince's range includes it.
                    listOf(DailySummary(date = target, avgSleepingBodyTemp = 99f))
            coEvery { dailySummaryRepository.getSince(any()) } returns history

            val expected = (1..14).map { 36.0f + it * 0.01f }.average().toFloat()
            assertEquals(expected, provider.getBaseline(target)!!, 0.001f)
        }
}
