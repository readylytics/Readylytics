package com.gregor.lauritz.healthdashboard.domain.sync

import com.gregor.lauritz.healthdashboard.data.local.dao.BloodPressureRecordDao
import com.gregor.lauritz.healthdashboard.data.local.dao.BodyFatRecordDao
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.OxygenSaturationRecordDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepStageDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WeightRecordDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.model.HealthDataType
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.domain.repository.ScoringRepository
import com.gregor.lauritz.healthdashboard.domain.repository.TransactionRunner
import com.gregor.lauritz.healthdashboard.domain.sync.link.SessionLinkReconciler
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HealthSyncUseCaseTest {
    private val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
    private val sleepDao = mockk<SleepSessionDao>(relaxed = true)
    private val sleepStageDao = mockk<SleepStageDao>(relaxed = true)
    private val heartRateDao = mockk<HeartRateDao>(relaxed = true)
    private val hrvDao = mockk<HrvDao>(relaxed = true)
    private val workoutDao = mockk<WorkoutDao>(relaxed = true)
    private val dailySummaryDao = mockk<DailySummaryDao>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val scoringRepository = mockk<ScoringRepository>(relaxed = true)
    private val transactionRunner = mockk<TransactionRunner>(relaxed = true)
    private val weightRecordDao = mockk<WeightRecordDao>(relaxed = true)
    private val bodyFatRecordDao = mockk<BodyFatRecordDao>(relaxed = true)
    private val bloodPressureRecordDao = mockk<BloodPressureRecordDao>(relaxed = true)
    private val oxygenSaturationRecordDao = mockk<OxygenSaturationRecordDao>(relaxed = true)
    private val sessionLinkReconciler = mockk<SessionLinkReconciler>(relaxed = true)

    private lateinit var useCase: HealthSyncUseCase

    @Before
    fun setup() {
        coEvery { transactionRunner.runInTransaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }

        useCase =
            HealthSyncUseCase(
                hcRepo = hcRepo,
                sleepSessionDao = sleepDao,
                sleepStageDao = sleepStageDao,
                heartRateDao = heartRateDao,
                hrvDao = hrvDao,
                workoutDao = workoutDao,
                weightRecordDao = weightRecordDao,
                bodyFatRecordDao = bodyFatRecordDao,
                bloodPressureRecordDao = bloodPressureRecordDao,
                dailySummaryDao = dailySummaryDao,
                settingsRepo = settingsRepo,
                scoringRepository = scoringRepository,
                transactionRunner = transactionRunner,
                oxygenSaturationRecordDao = oxygenSaturationRecordDao,
                sessionLinkReconciler = sessionLinkReconciler,
            )
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
    }

    @Test
    fun `sync processes days in chronological order`() =
        runTest {
            val windowDays = 3
            val today = LocalDate.now(ZoneId.systemDefault())
            val day0 = today.minusDays(2)
            val day1 = today.minusDays(1)
            val day2 = today

            coEvery { scoringRepository.computeDailySummary(any()) } returns DailySummaryEntity(dateMidnightMs = 0L)

            useCase.sync(windowDays = windowDays)

            coVerifyOrder {
                scoringRepository.computeDailySummary(day0)
                dailySummaryDao.upsert(any())
                scoringRepository.computeDailySummary(day1)
                dailySummaryDao.upsert(any())
                scoringRepository.computeDailySummary(day2)
                dailySummaryDao.upsert(any())
            }
        }

    @Test
    fun `sync clears frozen baselines for scoring window before recomputing days`() =
        runTest {
            val windowDays = 2
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(zoneId)
            val windowStartMs =
                today
                    .minusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val windowEndExclusiveMs =
                today
                    .plusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()

            coEvery { scoringRepository.computeDailySummary(any()) } returns DailySummaryEntity(dateMidnightMs = 0L)
            coJustRun { dailySummaryDao.clearFrozenBaselinesBetween(any(), any()) }

            useCase.sync(windowDays = windowDays)

            coVerifyOrder {
                dailySummaryDao.clearFrozenBaselinesBetween(windowStartMs, windowEndExclusiveMs)
                scoringRepository.computeDailySummary(today.minusDays(1))
                dailySummaryDao.upsert(any())
                scoringRepository.computeDailySummary(today)
                dailySummaryDao.upsert(any())
            }
        }

    @Test
    fun `sync reconciles ingested overlap before scoring days`() =
        runTest {
            val windowDays = 1
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(zoneId)
            val ingestStartMs =
                today
                    .minusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val windowEndExclusiveMs =
                today
                    .plusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()

            coEvery { scoringRepository.computeDailySummary(any()) } returns DailySummaryEntity(dateMidnightMs = 0L)
            coJustRun { dailySummaryDao.clearFrozenBaselinesBetween(any(), any()) }
            coJustRun { sessionLinkReconciler.reconcile(any(), any(), any()) }

            useCase.sync(windowDays = windowDays)

            coVerifyOrder {
                sessionLinkReconciler.reconcile(
                    startMs = ingestStartMs,
                    endMs = windowEndExclusiveMs - 1,
                    zoneThresholds = any(),
                )
                dailySummaryDao.clearFrozenBaselinesBetween(
                    today.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                    windowEndExclusiveMs,
                )
                scoringRepository.computeDailySummary(today)
                dailySummaryDao.upsert(any())
            }
        }

    @Test
    fun `sync fetches and upserts all heart-related record types`() =
        runTest {
            coEvery { scoringRepository.computeDailySummary(any()) } returns DailySummaryEntity(dateMidnightMs = 0L)

            // Mock non-empty returns to ensure mapping logic is triggered
            coEvery { hcRepo.readHeartRateSamples(any(), any()) } returns listOf(mockk(relaxed = true))
            coEvery { hcRepo.readHrvSamples(any(), any()) } returns listOf(mockk(relaxed = true))
            coEvery { hcRepo.readSteps(any(), any()) } returns 0L

            useCase.sync()

            coVerify {
                hcRepo.readHeartRateSamples(any(), any())
                hcRepo.readHrvSamples(any(), any())
                hcRepo.readSteps(any(), any())
                heartRateDao.upsertAll(any())
                hrvDao.upsertAll(any())
            }
        }

    @Test
    fun `daily sync windowDays 1 fetches samples from yesterday to cover cross-midnight sleep`() =
        runTest {
            coEvery { scoringRepository.computeDailySummary(any()) } returns DailySummaryEntity(dateMidnightMs = 0L)

            val hrvFromSlot = slot<Instant>()
            val hrFromSlot = slot<Instant>()
            coEvery { hcRepo.readHrvSamples(capture(hrvFromSlot), any()) } returns emptyList()
            coEvery { hcRepo.readHeartRateSamples(capture(hrFromSlot), any()) } returns emptyList()

            useCase.sync(windowDays = 1)

            // Last night's sleep session begins the previous evening (before midnight); the
            // ingestion fetch must reach back one extra day so its pre-midnight HR/HRV samples
            // are captured. windowDays = 1 => fetch from yesterday 00:00, not today 00:00.
            val zoneId = ZoneId.systemDefault()
            val yesterdayMidnight =
                LocalDate
                    .now(zoneId)
                    .minusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
            assertEquals(yesterdayMidnight, hrvFromSlot.captured)
            assertEquals(yesterdayMidnight, hrFromSlot.captured)
        }

    @Test
    fun `resyncRange first chunk fetches samples from startDate minus 1 to cover cross-midnight sleep`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 2)

            val sleepFromSlot = slot<Instant>()
            val hrvFromSlot = slot<Instant>()
            val hrFromSlot = slot<Instant>()
            coEvery { hcRepo.readSleepSessions(capture(sleepFromSlot), any()) } returns emptyList()
            coEvery { hcRepo.readHrvSamples(capture(hrvFromSlot), any()) } returns emptyList()
            coEvery { hcRepo.readHeartRateSamples(capture(hrFromSlot), any()) } returns emptyList()
            coEvery { scoringRepository.computeDailySummary(any()) } returns DailySummaryEntity(dateMidnightMs = 0L)

            useCase.resyncRange(startDate, endDate)

            // The first chunk of resyncRange must reach back one extra day to capture
            // overnight sleep sessions that began the previous evening.
            val reachBackMidnight =
                startDate
                    .minusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
            assertEquals(reachBackMidnight, sleepFromSlot.captured)
            assertEquals(reachBackMidnight, hrvFromSlot.captured)
            assertEquals(reachBackMidnight, hrFromSlot.captured)
        }

    @Test
    fun `resyncRange each chunk fetches samples from previous day to cover cross-midnight sleep`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 7, 2)
            val chunkDays = 30

            val hrvFromInstants = mutableListOf<Instant>()
            val hrFromInstants = mutableListOf<Instant>()
            coEvery { hcRepo.readHrvSamples(capture(hrvFromInstants), any()) } returns emptyList()
            coEvery { hcRepo.readHeartRateSamples(capture(hrFromInstants), any()) } returns emptyList()
            coEvery { scoringRepository.computeDailySummary(any()) } returns DailySummaryEntity(dateMidnightMs = 0L)

            useCase.resyncRange(startDate, endDate, chunkDays = chunkDays)

            val secondChunkStart = startDate.plusDays(chunkDays.toLong())
            val expectedSecondChunkReadStart =
                secondChunkStart
                    .minusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
            assertEquals(expectedSecondChunkReadStart, hrvFromInstants[1])
            assertEquals(expectedSecondChunkReadStart, hrFromInstants[1])
        }

    @Test
    fun `resyncRange caps sleep and workout interval reads to chunk end`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val startDate = LocalDate.of(2024, 6, 1)
            val chunkDays = 30
            val firstChunkEnd =
                startDate
                    .plusDays(chunkDays.toLong())
                    .atStartOfDay(zoneId)
                    .toInstant()

            val sleepToInstants = mutableListOf<Instant>()
            val workoutToInstants = mutableListOf<Instant>()
            val hrvToInstants = mutableListOf<Instant>()
            val hrToInstants = mutableListOf<Instant>()
            coEvery { hcRepo.readSleepSessions(any(), capture(sleepToInstants)) } returns emptyList()
            coEvery { hcRepo.readExerciseSessions(any(), capture(workoutToInstants)) } returns emptyList()
            coEvery { hcRepo.readHrvSamples(any(), capture(hrvToInstants)) } returns emptyList()
            coEvery { hcRepo.readHeartRateSamples(any(), capture(hrToInstants)) } returns emptyList()
            coEvery { scoringRepository.computeDailySummary(any()) } returns DailySummaryEntity(dateMidnightMs = 0L)

            useCase.resyncRange(
                startDate = startDate,
                endDate = LocalDate.of(2024, 7, 2),
                chunkDays = chunkDays,
            )

            assertEquals(firstChunkEnd, sleepToInstants[0])
            assertEquals(firstChunkEnd, workoutToInstants[0])
            assertEquals(firstChunkEnd, hrvToInstants[0])
            assertEquals(firstChunkEnd, hrToInstants[0])
        }

    @Test
    fun `resyncRange filters selected step device using raw step records`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(
                    UserPreferences(
                        deviceByDataType = mapOf(HealthDataType.STEPS.name to "Watch"),
                    ),
                )
            coEvery { scoringRepository.computeDailySummary(any()) } returns DailySummaryEntity(dateMidnightMs = 0L)

            useCase.resyncRange(
                startDate = LocalDate.of(2024, 6, 1),
                endDate = LocalDate.of(2024, 6, 1),
            )

            coVerify(exactly = 1) { hcRepo.readStepsRecords(any(), any()) }
            coVerify(exactly = 0) { hcRepo.readSteps(any(), any()) }
        }

    @Test
    fun `resyncRange retries selected step device raw record fetch`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(
                    UserPreferences(
                        deviceByDataType = mapOf(HealthDataType.STEPS.name to "Watch"),
                    ),
                )
            coEvery { scoringRepository.computeDailySummary(any()) } returns DailySummaryEntity(dateMidnightMs = 0L)
            coEvery { hcRepo.readStepsRecords(any(), any()) } throws RuntimeException("rate limited") andThen
                emptyList()

            useCase.resyncRange(
                startDate = LocalDate.of(2024, 6, 1),
                endDate = LocalDate.of(2024, 6, 1),
            )

            coVerify(exactly = 2) { hcRepo.readStepsRecords(any(), any()) }
        }

    @Test
    fun `resyncRange writes zero steps for selected device days without raw step records`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(
                    UserPreferences(
                        deviceByDataType = mapOf(HealthDataType.STEPS.name to "Watch"),
                    ),
                )
            coEvery { hcRepo.readStepsRecords(any(), any()) } returns emptyList()
            coEvery { scoringRepository.computeDailySummary(any()) } returns
                DailySummaryEntity(
                    dateMidnightMs = 0L,
                    stepCount = 999,
                )
            val summarySlot = slot<DailySummaryEntity>()
            coJustRun { dailySummaryDao.upsert(capture(summarySlot)) }

            useCase.resyncRange(
                startDate = LocalDate.of(2024, 6, 1),
                endDate = LocalDate.of(2024, 6, 1),
            )

            assertEquals(0, summarySlot.captured.stepCount)
        }

    @Test
    fun `resyncRange progress reports calendar days not internal two phase steps`() =
        runTest {
            coEvery { scoringRepository.computeDailySummary(any()) } returns DailySummaryEntity(dateMidnightMs = 0L)
            val progress = mutableListOf<Pair<Int, Int>>()

            useCase.resyncRange(
                startDate = LocalDate.of(2024, 6, 1),
                endDate = LocalDate.of(2024, 6, 3),
            ) { current, total ->
                progress += current to total
            }

            assertEquals(3, progress.last().first)
            assertEquals(3, progress.last().second)
            assert(progress.all { (_, total) -> total == 3 })
        }

    @Test
    fun `resyncRange clears frozen baselines only for requested range before walk-forward recompute`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 3)
            val clearFromMs = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val clearToExclusiveMs =
                endDate
                    .plusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()

            coEvery { scoringRepository.computeDailySummary(any()) } returns DailySummaryEntity(dateMidnightMs = 0L)
            coJustRun { dailySummaryDao.clearFrozenBaselinesBetween(any(), any()) }

            useCase.resyncRange(startDate = startDate, endDate = endDate)

            coVerifyOrder {
                dailySummaryDao.clearFrozenBaselinesBetween(clearFromMs, clearToExclusiveMs)
                scoringRepository.computeDailySummary(startDate)
                dailySummaryDao.upsert(any())
                scoringRepository.computeDailySummary(startDate.plusDays(1))
                dailySummaryDao.upsert(any())
                scoringRepository.computeDailySummary(endDate)
                dailySummaryDao.upsert(any())
            }
        }
}
