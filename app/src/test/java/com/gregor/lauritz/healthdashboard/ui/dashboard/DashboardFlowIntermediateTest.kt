package com.gregor.lauritz.healthdashboard.ui.dashboard

import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.preferences.CardConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardManagementDelegate
import com.gregor.lauritz.healthdashboard.domain.dashboard.DailySummaryRepository
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyResult
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DashboardFlowIntermediateTest {
    private lateinit var dailySummaryRepository: DailySummaryRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var circadianRepository: CircadianConsistencyRepository
    private lateinit var cardManagementDelegate: CardManagementDelegate
    private lateinit var cardConfigRepository: CardConfigurationRepository
    private lateinit var foregroundSyncController: ForegroundSyncController

    @Before
    fun setup() {
        dailySummaryRepository = mockk()
        settingsRepository = mockk()
        circadianRepository = mockk()
        cardManagementDelegate = mockk()
        cardConfigRepository = mockk()
        foregroundSyncController = mockk()
    }

    @Test
    fun `createDashboardBasicInputsFlow emits basicInputs for current date`() =
        runTest {
            val today = LocalDate.now()
            val summary =
                DailySummary(
                    date = today,
                    stepCount = 10000,
                    isCalibrating = false,
                )
            val prefs =
                UserPreferences(
                    stepGoal = 8000,
                    sleepGoalMinutes = 480,
                )
            val circadian =
                CircadianConsistencyResult(
                    consistencyScore = 85f,
                    timestamp = today.atStartOfDay().toInstant(),
                )

            coEvery {
                dailySummaryRepository.observeSince(any())
            } returns flowOf(listOf(summary))
            coEvery {
                settingsRepository.userPreferences
            } returns flowOf(prefs)
            coEvery {
                circadianRepository.resultFor(today)
            } returns flowOf(circadian)

            val selectedDateFlow = flowOf(today)
            val basicInputsFlow =
                createDashboardBasicInputsFlow(
                    selectedDateFlow,
                    dailySummaryRepository,
                    settingsRepository,
                    circadianRepository,
                )

            var emittedInputs: DashboardBasicInputs? = null
            basicInputsFlow.collect { inputs ->
                emittedInputs = inputs
            }

            assertNotNull(emittedInputs)
            assertEquals(today, emittedInputs!!.selectedDate)
            assertEquals(summary, emittedInputs!!.summary)
            assertEquals(prefs, emittedInputs!!.userPreferences)
            assertEquals(circadian, emittedInputs!!.circadianResult)
        }

    @Test
    fun `createDashboardBasicInputsFlow emits basicInputs for historical date`() =
        runTest {
            val historicalDate = LocalDate.now().minusDays(5)
            val summary =
                DailySummary(
                    dateMs = historicalDate.atStartOfDay().toInstant().toEpochMilli(),
                    stepCount = 5000,
                    isCalibrating = false,
                    dataQuality = 1.0f,
                )
            val prefs = UserPreferences(stepGoal = 8000)
            val circadian =
                CircadianConsistencyResult(
                    consistencyScore = 75f,
                    timestamp = historicalDate.atStartOfDay().toInstant(),
                )

            coEvery {
                dailySummaryRepository.observeByDate(any())
            } returns flowOf(summary)
            coEvery {
                settingsRepository.userPreferences
            } returns flowOf(prefs)
            coEvery {
                circadianRepository.resultFor(historicalDate)
            } returns flowOf(circadian)

            val selectedDateFlow = flowOf(historicalDate)
            val basicInputsFlow =
                createDashboardBasicInputsFlow(
                    selectedDateFlow,
                    dailySummaryRepository,
                    settingsRepository,
                    circadianRepository,
                )

            var emittedInputs: DashboardBasicInputs? = null
            basicInputsFlow.collect { inputs ->
                emittedInputs = inputs
            }

            assertNotNull(emittedInputs)
            assertEquals(historicalDate, emittedInputs!!.selectedDate)
            assertEquals(summary, emittedInputs!!.summary)
        }

    @Test
    fun `createDashboardBasicInputsFlow includes 7-day PAI breakdown`() =
        runTest {
            val today = LocalDate.now()
            val paiSummaries =
                listOf(
                    DailySummary(
                        date = today.minusDays(6),
                        stepCount = 1000,
                        isCalibrating = false,
                    ),
                    DailySummary(
                        date = today,
                        stepCount = 10000,
                        isCalibrating = false,
                    ),
                )

            coEvery {
                dailySummaryRepository.observeSince(any())
            } returns flowOf(listOf(paiSummaries[0]), listOf(paiSummaries[1]))
            coEvery {
                settingsRepository.userPreferences
            } returns flowOf(UserPreferences())
            coEvery {
                circadianRepository.resultFor(today)
            } returns flowOf(null)

            val selectedDateFlow = flowOf(today)
            val basicInputsFlow =
                createDashboardBasicInputsFlow(
                    selectedDateFlow,
                    dailySummaryRepository,
                    settingsRepository,
                    circadianRepository,
                )

            var emittedInputs: DashboardBasicInputs? = null
            basicInputsFlow.collect { inputs ->
                emittedInputs = inputs
            }

            assertNotNull(emittedInputs)
            assertTrue(emittedInputs!!.paiSummaries.isNotEmpty())
        }

    @Test
    fun `createDashboardCardStateFlow emits cardState`() =
        runTest {
            val today = LocalDate.now()
            val session =
                SleepSessionEntity(
                    id = "session_1",
                    startTime = today.atStartOfDay().toInstant().toEpochMilli(),
                    endTime = today.atStartOfDay().toInstant().toEpochMilli() + 8 * 60 * 60 * 1000,
                    durationMinutes = 480,
                    efficiency = 0.85f,
                    deepSleepMinutes = 120,
                    remSleepMinutes = 60,
                    lightSleepMinutes = 180,
                    awakeMinutes = 15,
                    deviceName = "TestDevice",
                )
            val cardConfig =
                CardConfiguration(
                    cardId = CardId.SLEEP_SCORE,
                    isVisible = true,
                    position = 0,
                )

            every {
                cardManagementDelegate.isManagingCards
            } returns MutableStateFlow(false)
            coEvery {
                cardConfigRepository.dashboardCardConfigurations()
            } returns flowOf(listOf(cardConfig))
            coEvery {
                dailySummaryRepository.observeFirstSessionEndingInRange(any(), any())
            } returns flowOf(session)

            val selectedDateFlow = flowOf(today)
            val cardStateFlow =
                createDashboardCardStateFlow(
                    selectedDateFlow,
                    cardManagementDelegate,
                    cardConfigRepository,
                    dailySummaryRepository,
                )

            var emittedState: DashboardCardState? = null
            cardStateFlow.collect { state ->
                emittedState = state
            }

            assertNotNull(emittedState)
            assertEquals(false, emittedState!!.isManagingCards)
            assertEquals(session, emittedState!!.lastSleepSession)
            assertEquals(1, emittedState!!.cardConfiguration.size)
        }

    @Test
    fun `createDashboardCardStateFlow handles null sleep session`() =
        runTest {
            val today = LocalDate.now()
            val cardConfig =
                CardConfiguration(
                    cardId = CardId.SLEEP_SCORE,
                    isVisible = true,
                    position = 0,
                )

            every {
                cardManagementDelegate.isManagingCards
            } returns MutableStateFlow(false)
            coEvery {
                cardConfigRepository.dashboardCardConfigurations()
            } returns flowOf(listOf(cardConfig))
            coEvery {
                dailySummaryRepository.observeFirstSessionEndingInRange(any(), any())
            } returns flowOf(null)

            val selectedDateFlow = flowOf(today)
            val cardStateFlow =
                createDashboardCardStateFlow(
                    selectedDateFlow,
                    cardManagementDelegate,
                    cardConfigRepository,
                    dailySummaryRepository,
                )

            var emittedState: DashboardCardState? = null
            cardStateFlow.collect { state ->
                emittedState = state
            }

            assertNotNull(emittedState)
            assertEquals(null, emittedState!!.lastSleepSession)
        }

    @Test
    fun `createDashboardRealtimeStateFlow emits sync state`() =
        runTest {
            coEvery {
                foregroundSyncController.isSyncing
            } returns flowOf(true)

            val realtimeStateFlow = createDashboardRealtimeStateFlow(foregroundSyncController)

            var emittedState: DashboardRealtimeState? = null
            realtimeStateFlow.collect { state ->
                emittedState = state
            }

            assertNotNull(emittedState)
            assertEquals(true, emittedState!!.isSyncing)
        }

    @Test
    fun `combineDashboardInputs aggregates all three flows`() =
        runTest {
            val basicInputs =
                DashboardBasicInputs(
                    selectedDate = LocalDate.now(),
                    summary = null,
                    userPreferences = UserPreferences(),
                    circadianResult = null,
                    paiSummaries = emptyList(),
                )
            val cardState =
                DashboardCardState(
                    isManagingCards = false,
                    cardConfiguration = emptyList(),
                    lastSleepSession = null,
                )
            val realtimeState = DashboardRealtimeState(isSyncing = false)

            val basicInputsFlow = flowOf(basicInputs)
            val cardStateFlow = flowOf(cardState)
            val realtimeStateFlow = flowOf(realtimeState)

            val combinedFlow =
                combineDashboardInputs(
                    basicInputsFlow,
                    cardStateFlow,
                    realtimeStateFlow,
                )

            var emittedCombined: DashboardCombinedInputs? = null
            combinedFlow.collect { combined ->
                emittedCombined = combined
            }

            assertNotNull(emittedCombined)
            assertEquals(basicInputs, emittedCombined!!.basicInputs)
            assertEquals(cardState, emittedCombined!!.cardState)
            assertEquals(realtimeState, emittedCombined!!.realtimeState)
        }
}
