package com.gregor.lauritz.healthdashboard.ui.sleep

import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionRepository
import com.gregor.lauritz.healthdashboard.domain.repository.SleepStageData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SleepDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var sleepSessionDao: SleepSessionDao
    private lateinit var selectedDateRepository: SelectedDateRepository
    private lateinit var sleepSessionRepository: SleepSessionRepository
    private lateinit var viewModel: SleepDetailViewModel

    @Before
    fun setup() {
        sleepSessionDao = mockk()
        selectedDateRepository = mockk()
        sleepSessionRepository = mockk()
    }

    @Test
    fun whenSessionIsNull_emitsEmptyState() =
        testScope.runTest {
            // Given: no session for today
            val date = LocalDate.now()
            every { selectedDateRepository.selectedDate } returns flowOf(date)
            coEvery {
                sleepSessionDao.observeFirstSessionEndingInRange(any(), any())
            } returns flowOf(null)

            // When: create viewModel
            viewModel =
                SleepDetailViewModel(
                    sleepSessionDao = sleepSessionDao,
                    selectedDateRepository = selectedDateRepository,
                    sleepSessionRepository = sleepSessionRepository,
                )

            advanceUntilIdle()

            // Then: uiState has null session and empty stages
            val state = viewModel.uiState.value
            assertNull(state.session)
            assertEquals(0, state.stageTimeline.size)
            assertEquals(0f, state.deepSleepPercent)
            assertEquals(date, state.selectedDate)
        }

    @Test
    fun whenSessionExists_emitsStateWithStages() =
        testScope.runTest {
            // Given: session with 3 stages (deep=30, light=40, rem=20, awake=10)
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

            every { selectedDateRepository.selectedDate } returns flowOf(date)
            coEvery {
                sleepSessionDao.observeFirstSessionEndingInRange(any(), any())
            } returns flowOf(session)
            coEvery {
                sleepSessionRepository.observeSessionStages("session1")
            } returns flowOf(stages)

            // When: create viewModel
            viewModel =
                SleepDetailViewModel(
                    sleepSessionDao = sleepSessionDao,
                    selectedDateRepository = selectedDateRepository,
                    sleepSessionRepository = sleepSessionRepository,
                )

            advanceUntilIdle()

            // Then: state contains session and stages with calculated percentages
            val state = viewModel.uiState.value
            assertEquals(session, state.session)
            assertEquals(stages, state.stageTimeline)
            assertEquals(30f, state.deepSleepPercent) // 30 / 100 * 100
            assertEquals(40f, state.lightSleepPercent) // 40 / 100 * 100
            assertEquals(20f, state.remSleepPercent) // 20 / 100 * 100
            assertEquals(10f, state.awakePercent) // 10 / 100 * 100
        }

    @Test
    fun whenTotalMinutesIsZero_percentagesAreZero() =
        testScope.runTest {
            // Given: session with 0 minutes total
            val date = LocalDate.now()
            val session =
                createMockSession(
                    id = "session1",
                    deepMinutes = 0,
                    lightMinutes = 0,
                    remMinutes = 0,
                    awakeMinutes = 0,
                )

            every { selectedDateRepository.selectedDate } returns flowOf(date)
            coEvery {
                sleepSessionDao.observeFirstSessionEndingInRange(any(), any())
            } returns flowOf(session)
            coEvery {
                sleepSessionRepository.observeSessionStages("session1")
            } returns flowOf(emptyList())

            // When: create viewModel
            viewModel =
                SleepDetailViewModel(
                    sleepSessionDao = sleepSessionDao,
                    selectedDateRepository = selectedDateRepository,
                    sleepSessionRepository = sleepSessionRepository,
                )

            advanceUntilIdle()

            // Then: all percentages are 0
            val state = viewModel.uiState.value
            assertEquals(0f, state.deepSleepPercent)
            assertEquals(0f, state.lightSleepPercent)
            assertEquals(0f, state.remSleepPercent)
            assertEquals(0f, state.awakePercent)
        }

    @Test
    fun buildDetailUiState_calculatesPercentagesCorrectly() {
        // Given: session with deep=25, light=50, rem=15, awake=10 (total=100)
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

        // When: call buildDetailUiState
        val state = createViewModel().buildDetailUiState(session, stages, date)

        // Then: percentages calculated correctly
        assertEquals(25f, state.deepSleepPercent)
        assertEquals(50f, state.lightSleepPercent)
        assertEquals(15f, state.remSleepPercent)
        assertEquals(10f, state.awakePercent)
    }

    @Test
    fun buildDetailUiState_presentsAllRequiredFields() {
        // Given: session and stages
        val session = createMockSession(id = "test", deepMinutes = 50, lightMinutes = 50)
        val stages = listOf(SleepStageData("DEEP", 0, 3_000_000, 50))
        val date = LocalDate.of(2026, 5, 19)

        // When: call buildDetailUiState
        val state = createViewModel().buildDetailUiState(session, stages, date)

        // Then: all fields are populated
        assertEquals(session, state.session)
        assertEquals(stages, state.stageTimeline)
        assertEquals(date, state.selectedDate)
        assertEquals(50f, state.deepSleepPercent)
        assertEquals(session.sleepScore, state.sleepScore)
    }

    // Helper functions
    private fun createViewModel(): SleepDetailViewModel {
        every { selectedDateRepository.selectedDate } returns flowOf(LocalDate.now())
        coEvery {
            sleepSessionDao.observeFirstSessionEndingInRange(any(), any())
        } returns flowOf(null)

        return SleepDetailViewModel(
            sleepSessionDao = sleepSessionDao,
            selectedDateRepository = selectedDateRepository,
            sleepSessionRepository = sleepSessionRepository,
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
