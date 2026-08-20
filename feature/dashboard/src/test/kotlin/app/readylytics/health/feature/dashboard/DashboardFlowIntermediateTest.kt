package app.readylytics.health.feature.dashboard

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.CardManagementDelegate
import app.readylytics.health.core.model.domain.sync.ForegroundSyncGateway
import app.readylytics.health.core.model.domain.sync.RecalcProgress
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyRepository
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.InsightDismissalRepository
import app.readylytics.health.domain.service.BodyTemperatureBaselineProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardFlowIntermediateTest {
    @Test
    fun `basic inputs refresh body temperature baseline without changing selected date`() =
        runTest {
            val selectedDate = LocalDate.of(2026, 8, 8)
            val selectedDateFlow = MutableStateFlow(selectedDate)
            val bodyTemperatureBaseline = MutableStateFlow<Float?>(null)
            val dailySummaryRepository =
                mockk<DailySummaryRepository> {
                    every { observeSince(any()) } returns MutableStateFlow(emptyList())
                    every { observeByDate(any()) } returns MutableStateFlow(null)
                }
            val settingsRepository =
                mockk<UserPreferencesReader> {
                    every { userPreferences } returns MutableStateFlow(UserPreferences())
                }
            val circadianRepository =
                mockk<CircadianConsistencyRepository> {
                    every { resultFor(any()) } returns flowOf(CircadianConsistencyResult.Calibrating)
                }
            val insightDismissalRepository =
                mockk<InsightDismissalRepository> {
                    every { observeForDate(any()) } returns flowOf(emptySet())
                }
            val bodyTemperatureBaselineProvider =
                mockk<BodyTemperatureBaselineProvider> {
                    coEvery { getBaseline(any()) } returns null
                    every { observeBaseline(selectedDate) } returns bodyTemperatureBaseline
                }
            val inputs = mutableListOf<DashboardBasicInputs>()

            val collector =
                backgroundScope.launch {
                    createDashboardBasicInputsFlow(
                        selectedDate = selectedDateFlow,
                        dailySummaryRepository = dailySummaryRepository,
                        settingsRepository = settingsRepository,
                        circadianRepository = circadianRepository,
                        insightDismissalRepository = insightDismissalRepository,
                        bodyTemperatureBaselineProvider = bodyTemperatureBaselineProvider,
                    ).collect(inputs::add)
                }
            runCurrent()

            bodyTemperatureBaseline.value = 36.5f
            runCurrent()

            assertEquals(36.5f, inputs.last().bodyTempBaseline)
            assertEquals(selectedDate, selectedDateFlow.value)
            collector.cancel()
        }

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
                    coEvery { hasStepsPermission() } returns false
                    coEvery { hasWeightPermission() } returns false
                    coEvery { hasBodyFatPermission() } returns false
                    coEvery { hasBloodPressurePermission() } returns false
                    coEvery { hasOxygenSaturationPermission() } returns false
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
                    coEvery { hasStepsPermission() } returns false
                    coEvery { hasWeightPermission() } returns false
                    coEvery { hasBodyFatPermission() } returns false
                    coEvery { hasBloodPressurePermission() } returns false
                    coEvery { hasOxygenSaturationPermission() } returns false
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

    @Test
    fun `card state flow excludes the body temperature card from pending config when permission is denied`() =
        runTest {
            // Simulates "Reset to defaults" in card management: pendingConfigs is populated from
            // SettingsDefaults.DEFAULT_DASHBOARD_CARDS, which includes BODY_TEMPERATURE regardless
            // of whether the user has granted the Health Connect permission. The pending list must
            // be gated the same way the live cardConfiguration is, or the management sheet leaks
            // the card and a subsequent Save would persist it with isVisible = true.
            val cardConfigRepository =
                mockk<CardConfigurationRepository> {
                    every { dashboardCardConfigurations() } returns
                        flowOf(listOf(CardConfiguration(CardId.SLEEP_SCORE, isVisible = true, position = 0)))
                }
            val healthConnectRepository =
                mockk<HealthConnectRepository> {
                    coEvery { hasBodyTemperaturePermission() } returns false
                    coEvery { hasStepsPermission() } returns false
                    coEvery { hasWeightPermission() } returns false
                    coEvery { hasBodyFatPermission() } returns false
                    coEvery { hasBloodPressurePermission() } returns false
                    coEvery { hasOxygenSaturationPermission() } returns false
                }
            val cardManagementDelegate =
                mockCardManagementDelegate(
                    initialPendingConfigs =
                        listOf(
                            CardConfiguration(CardId.SLEEP_SCORE, isVisible = true, position = 0),
                            CardConfiguration(CardId.BODY_TEMPERATURE, isVisible = true, position = 17),
                        ),
                )
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

            assertTrue(result.pendingConfiguration.orEmpty().none { it.cardId == CardId.BODY_TEMPERATURE })
            assertTrue(result.pendingConfiguration.orEmpty().any { it.cardId == CardId.SLEEP_SCORE })
        }

    @Test
    fun `createDashboardRealtimeStateFlow combines isSyncing and recalcProgress`() =
        runTest {
            val isSyncing = MutableStateFlow(false)
            val recalcProgress = MutableStateFlow<RecalcProgress?>(null)
            val gateway =
                mockk<ForegroundSyncGateway> {
                    every { this@mockk.isSyncing } returns isSyncing
                    every { this@mockk.recalcProgress } returns recalcProgress
                }

            val emissions = mutableListOf<DashboardRealtimeState>()
            val job =
                backgroundScope.launch {
                    createDashboardRealtimeStateFlow(gateway).collect(emissions::add)
                }
            runCurrent()

            assertEquals(DashboardRealtimeState(isSyncing = false, recalcProgress = null), emissions.last())

            isSyncing.value = true
            recalcProgress.value = RecalcProgress(ResyncPhase.INGEST, current = 2, total = 0)
            runCurrent()

            assertEquals(
                DashboardRealtimeState(
                    isSyncing = true,
                    recalcProgress = RecalcProgress(ResyncPhase.INGEST, current = 2, total = 0),
                ),
                emissions.last(),
            )
            job.cancel()
        }

    private fun mockCardManagementDelegate(
        initialPendingConfigs: List<CardConfiguration>? = null,
    ): CardManagementDelegate =
        mockk<CardManagementDelegate> {
            every { isManagingCards } returns MutableStateFlow(false)
            every { pendingConfigs } returns MutableStateFlow(initialPendingConfigs)
        }
}
