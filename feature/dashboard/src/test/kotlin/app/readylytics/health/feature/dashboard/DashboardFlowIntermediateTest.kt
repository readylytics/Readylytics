package app.readylytics.health.feature.dashboard

import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.CardManagementDelegate
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HealthConnectRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardFlowIntermediateTest {
    @Test
    fun `card state flow excludes the body temperature card when permission is not granted`() =
        runTest {
            val cardConfigRepository =
                mockk<CardConfigurationRepository> {
                    every { dashboardCardConfigurations() } returns
                        flowOf(
                            listOf(
                                CardConfiguration(CardId.SLEEP_SCORE, isVisible = true, position = 0),
                                CardConfiguration(CardId.BODY_TEMPERATURE, isVisible = true, position = 17),
                            ),
                        )
                }
            val healthConnectRepository =
                mockk<HealthConnectRepository> {
                    coEvery { hasBodyTemperaturePermission() } returns false
                }
            val cardManagementDelegate = mockCardManagementDelegate()
            val dailySummaryRepository =
                mockk<DailySummaryRepository> {
                    every { observeFirstSessionEndingInRange(any(), any()) } returns flowOf(null)
                }

            val result =
                createDashboardCardStateFlow(
                    selectedDate = flowOf(LocalDate.now()),
                    cardManagementDelegate = cardManagementDelegate,
                    cardConfigRepository = cardConfigRepository,
                    dailySummaryRepository = dailySummaryRepository,
                    healthConnectRepository = healthConnectRepository,
                ).first()

            assertTrue(result.cardConfiguration.none { it.cardId == CardId.BODY_TEMPERATURE })
            assertTrue(result.cardConfiguration.any { it.cardId == CardId.SLEEP_SCORE })
        }

    @Test
    fun `card state flow includes the body temperature card when permission is granted`() =
        runTest {
            val cardConfigRepository =
                mockk<CardConfigurationRepository> {
                    every { dashboardCardConfigurations() } returns
                        flowOf(listOf(CardConfiguration(CardId.BODY_TEMPERATURE, isVisible = true, position = 17)))
                }
            val healthConnectRepository =
                mockk<HealthConnectRepository> {
                    coEvery { hasBodyTemperaturePermission() } returns true
                }
            val cardManagementDelegate = mockCardManagementDelegate()
            val dailySummaryRepository =
                mockk<DailySummaryRepository> {
                    every { observeFirstSessionEndingInRange(any(), any()) } returns flowOf(null)
                }

            val result =
                createDashboardCardStateFlow(
                    selectedDate = flowOf(LocalDate.now()),
                    cardManagementDelegate = cardManagementDelegate,
                    cardConfigRepository = cardConfigRepository,
                    dailySummaryRepository = dailySummaryRepository,
                    healthConnectRepository = healthConnectRepository,
                ).first()

            assertTrue(result.cardConfiguration.any { it.cardId == CardId.BODY_TEMPERATURE })
        }

    private fun mockCardManagementDelegate(): CardManagementDelegate =
        mockk<CardManagementDelegate> {
            every { isManagingCards } returns MutableStateFlow(false)
            every { pendingConfigs } returns MutableStateFlow(null)
        }
}
