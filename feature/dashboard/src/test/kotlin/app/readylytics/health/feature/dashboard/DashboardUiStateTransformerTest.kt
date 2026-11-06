package app.readylytics.health.feature.dashboard

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.InsightType
import app.readylytics.health.core.model.domain.model.RecoveryFlag
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.feature.dashboard.usecase.GetCurrentResidualFatigueUseCase
import app.readylytics.health.feature.dashboard.usecase.GetDashboardDataUseCase
import app.readylytics.health.feature.dashboard.usecase.LiveResidualFatigue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DashboardUiStateTransformerTest {
    private val getDashboardDataUseCase: GetDashboardDataUseCase = mockk()
    private val getCurrentResidualFatigueUseCase: GetCurrentResidualFatigueUseCase = mockk()
    private val useCases: DashboardUseCases =
        DashboardUseCases(
            getDashboardData = getDashboardDataUseCase,
            observeDashboardStrainIncrease = mockk(),
            observeDashboardRasIncrease = mockk(),
            getDailyPromptData = mockk(),
            getCurrentResidualFatigue = getCurrentResidualFatigueUseCase,
        )
    private val fixedInstant = Instant.parse("2026-07-29T12:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    private lateinit var transformer: DashboardUiStateTransformer

    @Before
    fun setUp() {
        transformer = DashboardUiStateTransformer(useCases, clock)
    }

    @Test
    fun `resolveDashboardSleepSessionSummary maps sleep session data correctly`() {
        val session =
            SleepSessionData(
                id = "session_1",
                deviceName = "Test Device",
                startTime = 1_000L,
                endTime = 5_000L,
                durationMinutes = 60,
                efficiency = 0.92f,
                deepSleepMinutes = 20,
                lightSleepMinutes = 30,
                remSleepMinutes = 10,
                awakeMinutes = 5,
            )

        val summary = transformer.resolveDashboardSleepSessionSummary(session)
        assertNotNull(summary)
        assertEquals(0.92f, summary?.efficiency)
        assertEquals(1_000L, summary?.startTime)
        assertEquals(5_000L, summary?.endTime)

        assertNull(transformer.resolveDashboardSleepSessionSummary(null))
    }

    @Test
    fun `deriveInsights evaluates insights and dismissed types`() {
        val selectedDate = LocalDate.of(2026, 7, 29)
        val summary =
            DailySummary(
                date = selectedDate,
                recoveryFlags = setOf(RecoveryFlag.NADIR_DELAYED),
            )
        val basicInputs =
            DashboardBasicInputs(
                selectedDate = selectedDate,
                summary = summary,
                userPreferences = UserPreferences(scoringZoneId = "UTC"),
                circadianResult = CircadianConsistencyResult.MissingData,
                rasSummaries = listOf(summary),
                dismissedInsightTypes = setOf(InsightType.LATE_NADIR),
            )

        val derived = transformer.deriveInsights(basicInputs, selectedDate)
        assertEquals(1, derived.dismissedCount)
        assertTrue(derived.visibleQueue.none { it == InsightType.LATE_NADIR })
    }

    @Test
    fun `deriveInsights returns empty derived insights when summary is null`() {
        val selectedDate = LocalDate.of(2026, 7, 29)
        val basicInputs =
            DashboardBasicInputs(
                selectedDate = selectedDate,
                summary = null,
                userPreferences = UserPreferences(scoringZoneId = "UTC"),
                circadianResult = null,
                rasSummaries = emptyList(),
            )

        val derived = transformer.deriveInsights(basicInputs, selectedDate)
        assertTrue(derived.active.isEmpty())
        assertNull(derived.current)
        assertEquals(0, derived.dismissedCount)
    }

    @Test
    fun `resolveYesterdayMetrics resolves previous day metrics`() {
        val selectedDate = LocalDate.of(2026, 7, 29)
        val yesterday = selectedDate.minusDays(1)
        val yesterdaySummary =
            DailySummary(
                date = yesterday,
                sleepScore = 82f,
                readinessWorkoutOnly = 78f,
            )
        val basicInputs =
            DashboardBasicInputs(
                selectedDate = selectedDate,
                summary = DailySummary(date = selectedDate),
                userPreferences = UserPreferences(scoringZoneId = "UTC"),
                circadianResult = null,
                rasSummaries = listOf(yesterdaySummary),
            )

        val yesterdayMetrics = transformer.resolveYesterdayMetrics(basicInputs, selectedDate)
        assertNotNull(yesterdayMetrics)
        assertEquals(82, yesterdayMetrics?.sleepScoreRounded)
        assertEquals(78, yesterdayMetrics?.readinessRounded)

        val emptyInputs = basicInputs.copy(rasSummaries = emptyList())
        assertNull(transformer.resolveYesterdayMetrics(emptyInputs, selectedDate))
    }

    @Test
    fun `transformToUiState builds state and caches residual fatigue`() =
        runTest {
            val selectedDate = LocalDate.of(2026, 7, 29)
            val summary = DailySummary(date = selectedDate)
            val prefs = UserPreferences(scoringZoneId = "UTC")
            val basicInputs =
                DashboardBasicInputs(
                    selectedDate = selectedDate,
                    summary = summary,
                    userPreferences = prefs,
                    circadianResult = CircadianConsistencyResult.MissingData,
                    rasSummaries = listOf(summary),
                )
            val cardState =
                DashboardCardState(
                    isManagingCards = false,
                    cardConfiguration = emptyList(),
                    lastSleepSession = null,
                    pendingConfiguration = null,
                )

            val mockCards =
                GetDashboardDataUseCase.DashboardCards(
                    cardDataMap = emptyMap(),
                    rasDailyBreakdown = emptyList(),
                )
            every {
                getDashboardDataUseCase.invoke(
                    summary = summary,
                    prefs = prefs,
                    date = selectedDate,
                    lastSleepSession = null,
                    rasSummaries = listOf(summary),
                    circadianResult = CircadianConsistencyResult.MissingData,
                    heartRateSummary = null,
                    todayStrainIncrease = null,
                    todayRasIncrease = null,
                    bodyTempBaseline = null,
                    liveResidualFatigue = LiveResidualFatigue.NotApplicable,
                )
            } returns Result.success(mockCards)

            coEvery {
                getCurrentResidualFatigueUseCase.invoke(selectedDate, any())
            } returns LiveResidualFatigue.NotApplicable

            val state1 = transformer.transformToUiState(basicInputs, cardState, minuteBucket = 100L)
            assertEquals(selectedDate, state1.selectedDate)
            assertNull(state1.errorMessage)

            // Second call with same minuteBucket should hit the fatigue memo cache
            val state2 = transformer.transformToUiState(basicInputs, cardState, minuteBucket = 100L)
            assertEquals(selectedDate, state2.selectedDate)

            coVerify(exactly = 1) {
                getCurrentResidualFatigueUseCase.invoke(selectedDate, any())
            }
        }
}
