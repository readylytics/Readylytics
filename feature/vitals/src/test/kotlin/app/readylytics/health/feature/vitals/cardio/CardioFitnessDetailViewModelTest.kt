package app.readylytics.health.feature.vitals.cardio

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.scoring.domain.cardio.CooperNormsClassifier
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class CardioFitnessDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private val fixedClock =
        Clock.fixed(Instant.parse("2026-01-01T10:30:00Z"), ZoneId.of("Pacific/Honolulu"))
    private val scoringZone = ZoneId.of("Pacific/Kiritimati")

    private lateinit var dailySummaryRepository: DailySummaryRepository
    private lateinit var settingsRepo: UserPreferencesReader
    private lateinit var cooperNormsClassifier: CooperNormsClassifier

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        dailySummaryRepository =
            mockk {
                every { observeSince(any()) } returns MutableStateFlow(emptyList())
            }
        settingsRepo =
            mockk {
                every { userPreferences } returns
                    MutableStateFlow(UserPreferences(scoringZoneId = scoringZone.id))
            }
        cooperNormsClassifier = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `cardio detail seven-day range starts Dec 27 and queries 2025-12-26T10 00 00Z`() =
        runTest(testDispatcher) {
            val viewModel =
                CardioFitnessDetailViewModel(
                    dailySummaryRepository = dailySummaryRepository,
                    settingsRepo = settingsRepo,
                    cooperNormsClassifier = cooperNormsClassifier,
                    clock = fixedClock,
                    ioDispatcher = testDispatcher,
                )

            viewModel.uiState.first { !it.isLoading }

            // In Kiritimati (UTC+14), 2026-01-01T10:30:00Z is 2026-01-02T00:30:00.
            // 7-day range begins on 2025-12-27.
            // Midnight on 2025-12-27 in Pacific/Kiritimati is 2025-12-26T10:00:00Z.
            val expectedFromMs = Instant.parse("2025-12-26T10:00:00Z").toEpochMilli()
            verify { dailySummaryRepository.observeSince(expectedFromMs) }
        }
}
