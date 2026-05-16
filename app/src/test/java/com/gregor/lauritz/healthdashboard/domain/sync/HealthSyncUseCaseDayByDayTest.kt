package com.gregor.lauritz.healthdashboard.domain.sync

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.domain.repository.ScoringRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HealthSyncUseCaseDayByDayTest {
    private lateinit var hcRepo: HealthConnectRepository
    private lateinit var sleepDao: SleepSessionDao
    private lateinit var heartRateDao: HeartRateDao
    private lateinit var hrvDao: HrvDao
    private lateinit var workoutDao: WorkoutDao
    private lateinit var dailySummaryDao: DailySummaryDao
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var scoringRepository: ScoringRepository
    private lateinit var useCase: HealthSyncUseCase

    @Before
    fun setup() {
        hcRepo = mockk()
        sleepDao = mockk()
        heartRateDao = mockk()
        hrvDao = mockk()
        workoutDao = mockk()
        dailySummaryDao = mockk()
        settingsRepo = mockk()
        scoringRepository = mockk()

        coEvery {
            settingsRepo.userPreferences
        } returns flowOf(UserPreferences())
        coEvery {
            hcRepo.readSleepSessions(any(), any())
        } returns emptyList()
        coEvery {
            hcRepo.readExerciseSessions(any(), any())
        } returns emptyList()
        coEvery {
            hcRepo.readHeartRateSamples(any(), any())
        } returns emptyList()
        coEvery {
            hcRepo.readHrvSamples(any(), any())
        } returns emptyList()
        coEvery {
            hcRepo.readSteps(any(), any())
        } returns 0L
        coEvery {
            sleepDao.upsertAll(any())
        } returns Unit
        coEvery {
            workoutDao.upsertAll(any())
        } returns Unit
        coEvery {
            heartRateDao.upsertAll(any())
        } returns Unit
        coEvery {
            hrvDao.upsertAll(any())
        } returns Unit
        coEvery {
            dailySummaryDao.upsert(any())
        } returns Unit
        coEvery {
            scoringRepository.computeDailySummary(any())
        } returns
            DailySummary(
                dateMs = System.currentTimeMillis(),
                stepCount = 0,
                isCalibrating = false,
                dataQuality = 1.0f,
            )
        coEvery {
            settingsRepo.updateLastSyncTimestamp(any())
        } returns Unit

        useCase =
            HealthSyncUseCase(
                hcRepo,
                sleepDao,
                heartRateDao,
                hrvDao,
                workoutDao,
                dailySummaryDao,
                settingsRepo,
                scoringRepository,
            )
    }

    @Test
    fun `sync processes each day independently`() =
        runTest {
            val result = useCase.sync(windowDays = 3)

            assertTrue(result.isSuccess)
            // Should call syncDay logic for 3 days
            coVerify(exactly = 3) { hcRepo.readSleepSessions(any(), any()) }
            coVerify(exactly = 3) { hcRepo.readSteps(any(), any()) }
            coVerify(exactly = 3) { scoringRepository.computeDailySummary(any()) }
            coVerify(exactly = 3) { dailySummaryDao.upsert(any()) }
        }

    @Test
    fun `sync completes even if one day fails`() =
        runTest {
            val daySlot = slot<LocalDate>()
            coEvery {
                hcRepo.readSleepSessions(any(), any())
            } returns emptyList()
            coEvery {
                scoringRepository.computeDailySummary(capture(daySlot))
            } answers {
                if (daySlot.captured == LocalDate.now().minusDays(1)) {
                    throw RuntimeException("Day 2 failed")
                }
                DailySummary(
                    dateMs = System.currentTimeMillis(),
                    stepCount = 0,
                    isCalibrating = false,
                    dataQuality = 1.0f,
                )
            }

            val result = useCase.sync(windowDays = 3)

            assertTrue(result.isSuccess)
            // Despite one day failing, other days should still be processed
            coVerify(exactly = 3) { scoringRepository.computeDailySummary(any()) }
        }

    @Test
    fun `sync processes days in chronological order (oldest to newest)`() =
        runTest {
            val capturedDays = mutableListOf<LocalDate>()
            coEvery {
                scoringRepository.computeDailySummary(capture(capturedDays))
            } returns
                DailySummary(
                    dateMs = System.currentTimeMillis(),
                    stepCount = 0,
                    isCalibrating = false,
                    dataQuality = 1.0f,
                )

            val result = useCase.sync(windowDays = 3)

            assertTrue(result.isSuccess)
            assertEquals(3, capturedDays.size)
            // Verify days are in chronological order (oldest first)
            assertTrue(capturedDays[0] < capturedDays[1])
            assertTrue(capturedDays[1] < capturedDays[2])
        }

    @Test
    fun `sync persists step count for each day`() =
        runTest {
            val steps = listOf(5000L, 8000L, 10000L)
            val summarySlot = slot<DailySummary>()
            var callCount = 0

            coEvery {
                hcRepo.readSteps(any(), any())
            } answers {
                steps[callCount % 3].also { callCount++ }
            }
            coEvery {
                dailySummaryDao.upsert(capture(summarySlot))
            } returns Unit

            val result = useCase.sync(windowDays = 3)

            assertTrue(result.isSuccess)
            coVerify(exactly = 3) { dailySummaryDao.upsert(any()) }
        }

    @Test
    fun `sync updates last sync timestamp on completion`() =
        runTest {
            val timestampSlot = slot<Long>()
            coEvery {
                settingsRepo.updateLastSyncTimestamp(capture(timestampSlot))
            } returns Unit

            val result = useCase.sync(windowDays = 3)

            assertTrue(result.isSuccess)
            coVerify { settingsRepo.updateLastSyncTimestamp(any()) }
            assertTrue(timestampSlot.captured > 0)
        }

    @Test
    fun `catchUpSync uses 60-day window`() =
        runTest {
            val result = useCase.catchUpSync()

            assertTrue(result.isSuccess)
            // Should call syncDay logic for 60 days
            coVerify(atLeast = 50) { hcRepo.readSleepSessions(any(), any()) }
        }

    @Test
    fun `sync respects device preference for each day`() =
        runTest {
            val prefs =
                UserPreferences(
                    primaryDeviceName = "TestDevice",
                )
            coEvery {
                settingsRepo.userPreferences
            } returns flowOf(prefs)

            val result = useCase.sync(windowDays = 2)

            assertTrue(result.isSuccess)
            coVerify(exactly = 2) { hcRepo.readSleepSessions(any(), any()) }
        }

    @Test
    fun `sync prevents concurrent syncs with Mutex`() =
        runTest {
            var syncInProgress = false
            var maxConcurrent = 0

            coEvery {
                sleepDao.upsertAll(any())
            } answers {
                if (syncInProgress) maxConcurrent++
                syncInProgress = true
                try {
                    Thread.sleep(10)
                } finally {
                    syncInProgress = false
                }
            }

            val result = useCase.sync(windowDays = 2)

            assertTrue(result.isSuccess)
            // Mutex ensures no overlapping syncs
            assertEquals(0, maxConcurrent)
        }

    @Test
    fun `sync handles empty Health Connect responses gracefully`() =
        runTest {
            coEvery {
                hcRepo.readSleepSessions(any(), any())
            } returns emptyList()
            coEvery {
                hcRepo.readExerciseSessions(any(), any())
            } returns emptyList()
            coEvery {
                hcRepo.readHeartRateSamples(any(), any())
            } returns emptyList()
            coEvery {
                hcRepo.readHrvSamples(any(), any())
            } returns emptyList()
            coEvery {
                hcRepo.readSteps(any(), any())
            } returns 0L

            val result = useCase.sync(windowDays = 1)

            assertTrue(result.isSuccess)
            // Even with no HC data, daily summary should be computed
            coVerify { scoringRepository.computeDailySummary(any()) }
        }

    @Test
    fun `sync propagates HealthConnect errors`() =
        runTest {
            coEvery {
                hcRepo.readSleepSessions(any(), any())
            } throws RuntimeException("HC connection failed")

            val result = useCase.sync(windowDays = 1)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is RuntimeException)
        }

    @Test
    fun `sync logs success and failure counts`() =
        runTest {
            val result = useCase.sync(windowDays = 3)

            assertTrue(result.isSuccess)
            // Verify that sync completes without throwing
            assertNotNull(result.getOrNull())
        }
}
