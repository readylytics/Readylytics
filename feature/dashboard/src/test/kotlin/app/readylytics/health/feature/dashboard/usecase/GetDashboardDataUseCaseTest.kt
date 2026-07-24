package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.GetWorkoutMetricsUseCase
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.model.getOrNull
import app.readylytics.health.domain.util.DomainLogSink
import app.readylytics.health.domain.util.DomainLogger
import app.readylytics.health.domain.util.LogContext
import app.readylytics.health.domain.util.LogLevel
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class GetDashboardDataUseCaseTest {
    private lateinit var getWorkoutMetricsUseCase: GetWorkoutMetricsUseCase
    private lateinit var resourceProvider: app.readylytics.health.domain.util.ResourceProvider
    private lateinit var useCase: GetDashboardDataUseCase

    @Before
    fun setUp() {
        getWorkoutMetricsUseCase = mockk(relaxed = true)
        resourceProvider = mockk(relaxed = true)
        useCase =
            GetDashboardDataUseCase(
                resourceProvider = resourceProvider,
                getWorkoutMetricsUseCase = getWorkoutMetricsUseCase,
            )
    }

    @After
    fun tearDown() {
        DomainLogger.installSink(
            object : DomainLogSink {
                override fun log(
                    level: LogLevel,
                    tag: String,
                    message: String,
                    throwable: Throwable?,
                    context: LogContext,
                ) = Unit
            },
        )
    }

    @Test
    fun invoke_validInputs_succeeds() {
        val summary = mockk<DailySummary>(relaxed = true)
        val prefs = mockk<UserPreferences>(relaxed = true)
        val result =
            useCase(
                summary = summary,
                prefs = prefs,
                date = LocalDate.now(),
                lastSleepSession = null,
                rasSummaries = emptyList(),
            )
        assert(result.isSuccess) { "Should succeed with valid inputs" }
    }

    @Test
    fun `invoke logs the throwable before returning CARD_GENERATION_ERROR`() {
        // HC-008: the failure path must not silently drop the exception that caused it.
        val loggedThrowables = mutableListOf<Throwable?>()
        DomainLogger.installSink(
            object : DomainLogSink {
                override fun log(
                    level: LogLevel,
                    tag: String,
                    message: String,
                    throwable: Throwable?,
                    context: LogContext,
                ) {
                    if (level == LogLevel.ERROR) loggedThrowables += throwable
                }
            },
        )
        val summary = mockk<DailySummary>(relaxed = true)
        val prefs = mockk<UserPreferences>(relaxed = true)
        every { resourceProvider.getString(any()) } throws RuntimeException("boom")

        val result =
            useCase(
                summary = summary,
                prefs = prefs,
                date = LocalDate.now(),
                lastSleepSession = null,
                rasSummaries = emptyList(),
            )

        assert(result is Result.Failure) { "Should fail when card building throws" }
        assertEquals("CARD_GENERATION_ERROR", (result as Result.Failure).code)
        assertNotNull("The triggering exception must be logged", loggedThrowables.firstOrNull())
    }

    @Test
    fun invoke_nullSummary_succeeds() {
        val prefs = mockk<UserPreferences>(relaxed = true)
        val result =
            useCase(
                summary = null,
                prefs = prefs,
                date = LocalDate.now(),
                lastSleepSession = null,
                rasSummaries = emptyList(),
            )
        assert(result.isSuccess) { "Should handle null summary" }
    }

    @Test
    fun invoke_returnsCardDataMap() {
        val summary = mockk<DailySummary>(relaxed = true)
        val prefs = mockk<UserPreferences>(relaxed = true)
        val result =
            useCase(
                summary = summary,
                prefs = prefs,
                date = LocalDate.now(),
                lastSleepSession = null,
                rasSummaries = emptyList(),
            )
        assert(result.getOrNull()?.cardDataMap != null) { "Should return card data map" }
    }

    @Test
    fun invoke_scoreCardsUseCanonicalRoundedDisplayValues() {
        val summary =
            DailySummary(
                date = LocalDate.of(2026, 6, 9),
                sleepScore = 79.5f,
                // Default strainLoadSourceMode is WORKOUT_ONLY.
                readinessWorkoutOnly = 72.5f,
                strainRatioWorkoutOnly = 0.365f,
            )
        val prefs = UserPreferences()
        every { getWorkoutMetricsUseCase(summary, any()) } answers {
            GetWorkoutMetricsUseCase(resourceProvider)(summary, secondArg())
        }

        val result =
            useCase(
                summary = summary,
                prefs = prefs,
                date = summary.date,
                lastSleepSession = null,
                rasSummaries = emptyList(),
            )

        val cards = result.getOrNull()?.cardDataMap.orEmpty()
        assertEquals("80", cards[CardId.SLEEP_SCORE]?.value)
        assertEquals("73", cards[CardId.READINESS]?.value)
        assertEquals("0.37", cards[CardId.STRAIN_RATIO]?.value)
    }

    @Test
    fun invoke_withOptimalSpo2_returnsOptimalSpo2Card() {
        val summary =
            mockk<DailySummary>(relaxed = true) {
                every { avgSleepingSpo2 } returns 98.5f
            }
        val prefs = mockk<UserPreferences>(relaxed = true)
        val result =
            useCase(
                summary = summary,
                prefs = prefs,
                date = LocalDate.now(),
                lastSleepSession = null,
                rasSummaries = emptyList(),
            )
        val card = result.getOrNull()?.cardDataMap?.get(CardId.OXYGEN_SATURATION)
        assert(card != null)
        assertEquals("99", card?.value)
        assertEquals(MetricStatus.OPTIMAL, card?.status)
    }

    @Test
    fun invoke_withNormalSpo2_returnsNormalSpo2Card() {
        val summary =
            mockk<DailySummary>(relaxed = true) {
                every { avgSleepingSpo2 } returns 95.2f
            }
        val prefs = mockk<UserPreferences>(relaxed = true)
        val result =
            useCase(
                summary = summary,
                prefs = prefs,
                date = LocalDate.now(),
                lastSleepSession = null,
                rasSummaries = emptyList(),
            )
        val card = result.getOrNull()?.cardDataMap?.get(CardId.OXYGEN_SATURATION)
        assert(card != null)
        assertEquals("95", card?.value)
        assertEquals(MetricStatus.NEUTRAL, card?.status)
    }

    @Test
    fun invoke_withWarningSpo2_returnsWarningSpo2Card() {
        val summary =
            mockk<DailySummary>(relaxed = true) {
                every { avgSleepingSpo2 } returns 91.6f
            }
        val prefs = mockk<UserPreferences>(relaxed = true)
        val result =
            useCase(
                summary = summary,
                prefs = prefs,
                date = LocalDate.now(),
                lastSleepSession = null,
                rasSummaries = emptyList(),
            )
        val card = result.getOrNull()?.cardDataMap?.get(CardId.OXYGEN_SATURATION)
        assert(card != null)
        assertEquals("92", card?.value)
        assertEquals(MetricStatus.WARNING, card?.status)
    }

    @Test
    fun invoke_withPoorSpo2_returnsPoorSpo2Card() {
        val summary =
            mockk<DailySummary>(relaxed = true) {
                every { avgSleepingSpo2 } returns 88.0f
            }
        val prefs = mockk<UserPreferences>(relaxed = true)
        val result =
            useCase(
                summary = summary,
                prefs = prefs,
                date = LocalDate.now(),
                lastSleepSession = null,
                rasSummaries = emptyList(),
            )
        val card = result.getOrNull()?.cardDataMap?.get(CardId.OXYGEN_SATURATION)
        assert(card != null)
        assertEquals("88", card?.value)
        assertEquals(MetricStatus.POOR, card?.status)
    }

    @Test
    fun invoke_withNullSpo2_returnsCalibratingSpo2Card() {
        val summary =
            mockk<DailySummary>(relaxed = true) {
                every { avgSleepingSpo2 } returns null
            }
        val prefs = mockk<UserPreferences>(relaxed = true)
        val result =
            useCase(
                summary = summary,
                prefs = prefs,
                date = LocalDate.now(),
                lastSleepSession = null,
                rasSummaries = emptyList(),
            )
        val card = result.getOrNull()?.cardDataMap?.get(CardId.OXYGEN_SATURATION)
        assert(card != null)
        assertEquals("—", card?.value)
        assertEquals(MetricStatus.CALIBRATING, card?.status)
    }
}
