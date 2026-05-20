package com.gregor.lauritz.healthdashboard.ui.sleep

import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionRepository
import com.gregor.lauritz.healthdashboard.domain.repository.SleepStageData
import com.gregor.lauritz.healthdashboard.domain.util.TimezoneProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SleepDetailViewModelTest {
    // A single UnconfinedTestDispatcher serves as both Main and ioDispatcher.
    // This ensures flowOn(ioDispatcher) stays on the test scheduler, preventing
    // any coroutines from leaking onto real thread pools between tests.
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var sleepSessionDao: SleepSessionDao
    private lateinit var selectedDateRepository: SelectedDateRepository
    private lateinit var sleepSessionRepository: SleepSessionRepository
    private lateinit var timezoneProvider: TimezoneProvider

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        sleepSessionDao = mockk()
        selectedDateRepository = mockk()
        sleepSessionRepository = mockk()
        timezoneProvider = mockk()
        every { timezoneProvider.timezone } returns flowOf(ZoneId.systemDefault())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun whenSessionIsNull_emitsEmptyState() =
        runTest(testDispatcher) {
            val date = LocalDate.now()
            every { selectedDateRepository.selectedDate } returns MutableStateFlow(date)
            coEvery {
                sleepSessionDao.observeFirstSessionEndingInRange(any(), any())
            } returns flowOf(null)

            val viewModel = createViewModel()
            val state =
                viewModel.uiState
                    .filter { it.selectedDate == date }
                    .first()

            assertNull(state.session)
            assertEquals(0, state.stageTimeline.size)
            assertEquals(0f, state.deepSleepPercent)
            assertEquals(date, state.selectedDate)
        }

    @Test
    fun whenSessionExists_emitsStateWithStages() =
        runTest(testDispatcher) {
            val date = LocalDate.now()
            val session =
                createMockSession(
                    id = "session1",
                    deepMinutes = 30,
                    lightMinutes = 40,
                    remMinutes = 20,
                    awakeMinutes = 10,
                )
            val stages =
                listOf(
                    SleepStageData("DEEP", 0, 1_800_000, 30),
                    SleepStageData("LIGHT", 1_800_000, 4_200_000, 40),
                    SleepStageData("REM", 4_200_000, 5_400_000, 20),
                    SleepStageData("AWAKE", 5_400_000, 6_000_000, 10),
                )

            every { selectedDateRepository.selectedDate } returns MutableStateFlow(date)
            coEvery {
                sleepSessionDao.observeFirstSessionEndingInRange(any(), any())
            } returns flowOf(session)
            coEvery {
                sleepSessionRepository.observeSessionStages("session1")
            } returns flowOf(stages)

            val viewModel = createViewModel()
            val state =
                viewModel.uiState
                    .filter { it.session != null }
                    .first()

            assertEquals(session, state.session)
            assertEquals(stages, state.stageTimeline)
            org.junit.Assert.assertEquals(30f, state.deepSleepPercent, 0.01f)
            org.junit.Assert.assertEquals(40f, state.lightSleepPercent, 0.01f)
            org.junit.Assert.assertEquals(20f, state.remSleepPercent, 0.01f)
            org.junit.Assert.assertEquals(10f, state.awakePercent, 0.01f)
        }

    @Test
    fun whenTotalMinutesIsZero_percentagesAreZero() =
        runTest(testDispatcher) {
            val date = LocalDate.now()
            val session =
                createMockSession(
                    id = "session1",
                    deepMinutes = 0,
                    lightMinutes = 0,
                    remMinutes = 0,
                    awakeMinutes = 0,
                )

            every { selectedDateRepository.selectedDate } returns MutableStateFlow(date)
            coEvery {
                sleepSessionDao.observeFirstSessionEndingInRange(any(), any())
            } returns flowOf(session)
            coEvery {
                sleepSessionRepository.observeSessionStages("session1")
            } returns flowOf(emptyList())

            val viewModel = createViewModel()
            val state =
                viewModel.uiState
                    .filter { it.session != null }
                    .first()

            assertEquals(0f, state.deepSleepPercent)
            assertEquals(0f, state.lightSleepPercent)
            assertEquals(0f, state.remSleepPercent)
            assertEquals(0f, state.awakePercent)
        }

    @Test
    fun buildDetailUiState_calculatesPercentagesCorrectly() {
        val session =
            createMockSession(
                id = "test",
                deepMinutes = 25,
                lightMinutes = 50,
                remMinutes = 15,
                awakeMinutes = 10,
            )
        val stages =
            listOf(
                SleepStageData("DEEP", 0, 1_500_000, 25),
                SleepStageData("LIGHT", 1_500_000, 4_500_000, 50),
            )
        val date = LocalDate.now()

        val state = createHelper().buildDetailUiState(session, stages, date)

        org.junit.Assert.assertEquals(25f, state.deepSleepPercent, 0.01f)
        org.junit.Assert.assertEquals(50f, state.lightSleepPercent, 0.01f)
        org.junit.Assert.assertEquals(15f, state.remSleepPercent, 0.01f)
        org.junit.Assert.assertEquals(10f, state.awakePercent, 0.01f)
    }

    @Test
    fun buildDetailUiState_presentsAllRequiredFields() {
        val session = createMockSession(id = "test", deepMinutes = 50, lightMinutes = 50)
        val stages = listOf(SleepStageData("DEEP", 0, 3_000_000, 50))
        val date = LocalDate.of(2026, 5, 19)

        val state = createHelper().buildDetailUiState(session, stages, date)

        assertEquals(session, state.session)
        assertEquals(stages, state.stageTimeline)
        assertEquals(date, state.selectedDate)
        org.junit.Assert.assertEquals(50f, state.deepSleepPercent, 0.01f)
        assertEquals(session.sleepScore, state.sleepScore)
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    /** Creates a ViewModel that passes testDispatcher as ioDispatcher — no real threads. */
    private fun createViewModel(): SleepDetailViewModel =
        SleepDetailViewModel(
            sleepSessionDao = sleepSessionDao,
            selectedDateRepository = selectedDateRepository,
            sleepSessionRepository = sleepSessionRepository,
            timezoneProvider = timezoneProvider,
            ioDispatcher = testDispatcher,
        )

    /** For pure-function tests that don't need a live flow. */
    private fun createHelper(): SleepDetailViewModel {
        every { selectedDateRepository.selectedDate } returns MutableStateFlow(LocalDate.now())
        coEvery {
            sleepSessionDao.observeFirstSessionEndingInRange(any(), any())
        } returns flowOf(null)
        return SleepDetailViewModel(
            sleepSessionDao = sleepSessionDao,
            selectedDateRepository = selectedDateRepository,
            sleepSessionRepository = sleepSessionRepository,
            timezoneProvider = timezoneProvider,
            ioDispatcher = testDispatcher,
        )
    }

    private fun createMockSession(
        id: String = "test",
        deepMinutes: Int = 0,
        lightMinutes: Int = 0,
        remMinutes: Int = 0,
        awakeMinutes: Int = 0,
    ): SleepSessionEntity =
        SleepSessionEntity(
            id = id,
            startTime = 0,
            endTime = 0,
            durationMinutes = deepMinutes + lightMinutes + remMinutes + awakeMinutes,
            efficiency = 100f,
            deepSleepMinutes = deepMinutes,
            lightSleepMinutes = lightMinutes,
            remSleepMinutes = remMinutes,
            awakeMinutes = awakeMinutes,
            sleepScore = 85f,
            startZoneOffsetSeconds = null,
            endZoneOffsetSeconds = null,
            deviceName = "Test Device",
        )
}
