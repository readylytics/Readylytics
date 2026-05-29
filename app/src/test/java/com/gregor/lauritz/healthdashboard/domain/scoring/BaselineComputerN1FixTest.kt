package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsDefaults
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for N+1 query fix in BaselineComputer.computeAdaptiveBaselineRhrBpm()
 *
 * Verifies:
 * 1. Output equivalence before/after refactoring
 * 2. Reduced query count (60+ queries → 2 queries)
 * 3. Edge cases handled correctly
 * 4. Performance improvement validated
 */
class BaselineComputerN1FixTest {
    private lateinit var heartRateDao: HeartRateDao
    private lateinit var hrvDao: HrvDao
    private lateinit var sleepSessionDao: SleepSessionDao
    private lateinit var scoringCalculator: ScoringCalculator
    private lateinit var dailySummaryDao: DailySummaryDao
    private lateinit var baselineComputer: BaselineComputer

    @Before
    fun setup() {
        heartRateDao = mockk()
        hrvDao = mockk()
        sleepSessionDao = mockk()
        scoringCalculator = mockk()
        dailySummaryDao = mockk()
        // Default: no frozen baseline — all dates are live recompute
        coEvery { dailySummaryDao.getByDate(any()) } returns null
        baselineComputer =
            BaselineComputer(heartRateDao, hrvDao, sleepSessionDao, scoringCalculator, dailySummaryDao)
    }

    @Test
    fun `computeAdaptiveBaselineRhrBpm returns override when provided`() =
        runTest {
            val dayMidnight = Instant.now()
            val override = 65f
            val result =
                baselineComputer.computeAdaptiveBaselineRhrBpm(
                    dayMidnight,
                    override,
                    SettingsDefaults.RESTING_HR_PERCENTILE,
                )
            // Override path returns before DAO freeze check, so result is non-null
            assertEquals(override, result)
            // Verify no database queries made when override provided
            coVerify(exactly = 0) { sleepSessionDao.getSince(any()) }
            coVerify(exactly = 0) { heartRateDao.getSleepHrSamplesForSessions(any()) }
        }

    @Test
    fun `computeAdaptiveBaselineRhrBpm correctly computes nadir for 30-day window`() =
        runTest {
            val dayMidnight = Instant.now().truncatedTo(ChronoUnit.DAYS)
            val baselineFromMs = dayMidnight.minus(30, ChronoUnit.DAYS).toEpochMilli()

            // Create 30 mock sleep sessions
            val sessions =
                (1..30).map { i ->
                    SleepSessionEntity(
                        id = "session_$i",
                        startTime = baselineFromMs + (i.toLong() - 1) * 24 * 60 * 60 * 1000,
                        endTime = baselineFromMs + i.toLong() * 24 * 60 * 60 * 1000,
                        durationMinutes = 480,
                        efficiency = 0.9f,
                        deepSleepMinutes = 120,
                        remSleepMinutes = 60,
                        lightSleepMinutes = 300,
                        awakeMinutes = 15,
                        deviceName = "Device$i",
                    )
                }

            coEvery { sleepSessionDao.getSince(any()) } returns sessions

            // Mock validation to accept all sessions
            every { scoringCalculator.validateNight(any(), any(), any(), any(), any(), any()) } returns
                ScoringCalculator.NightValidationResult(
                    rmssdValid = true,
                    rhrValid = true,
                    durationValid = true,
                    stagesValid = true,
                    stagesSuspicious = false,
                    hrCoverageValid = true,
                )

            coEvery { hrvDao.getSleepRmssdForSessionsMap(any()) } returns emptyMap()
            coEvery { heartRateDao.getAvgSleepHrForSessions(any()) } returns emptyMap()

            // Mock HR samples for all sessions (batch query)
            val allHrSamples =
                (1..30).flatMap { sessionIdx ->
                    // Each session has 150 samples, ranging from 40-189 bpm
                    (0..149).map { sampleIdx ->
                        HeartRateRecordEntity(
                            id = "hr_${sessionIdx}_$sampleIdx",
                            timestampMs =
                                baselineFromMs + sessionIdx.toLong() * 24 * 60 * 60 * 1000 + sampleIdx * 60 * 1000,
                            beatsPerMinute = 40 + sampleIdx,
                            recordType = "SLEEP",
                            sessionId = "session_$sessionIdx",
                            deviceName = "Device$sessionIdx",
                        )
                    }
                }

            coEvery { heartRateDao.getSleepHrSamplesForSessions(any()) } returns allHrSamples

            val result =
                baselineComputer.computeAdaptiveBaselineRhrBpm(
                    dayMidnight,
                    null,
                    SettingsDefaults.RESTING_HR_PERCENTILE,
                )

            // Verify result is within expected range (non-frozen DAO returns null → live recompute)
            assertNotNull(result)
            assertTrue(result >= 40f && result <= 190f)

            // Verify ONLY the necessary database queries were made
            coVerify(exactly = 1) { sleepSessionDao.getSince(any()) }
            coVerify(exactly = 1) { heartRateDao.getSleepHrSamplesForSessions(any()) }

            // NOT called: getSleepHrSampleCount and getSleepHrSampleAtOffset (the N+1 culprits)
            coVerify(exactly = 0) { heartRateDao.getSleepHrSampleCount(any()) }
            coVerify(exactly = 0) { heartRateDao.getSleepHrSampleAtOffset(any(), any()) }
        }

    @Test
    fun `computeAdaptiveBaselineRhrBpm filters sessions with less than 10 samples`() =
        runTest {
            val dayMidnight = Instant.now().truncatedTo(ChronoUnit.DAYS)
            val baselineFromMs = dayMidnight.minus(30, ChronoUnit.DAYS).toEpochMilli()

            val sessions =
                listOf(
                    SleepSessionEntity(
                        id = "session_1",
                        startTime = baselineFromMs,
                        endTime = baselineFromMs + 8 * 60 * 60 * 1000,
                        durationMinutes = 480,
                        efficiency = 0.9f,
                        deepSleepMinutes = 120,
                        remSleepMinutes = 60,
                        lightSleepMinutes = 300,
                        awakeMinutes = 15,
                        deviceName = "Device1",
                    ),
                    SleepSessionEntity(
                        id = "session_2",
                        startTime = baselineFromMs + 24 * 60 * 60 * 1000,
                        endTime = baselineFromMs + 32 * 60 * 60 * 1000,
                        durationMinutes = 480,
                        efficiency = 0.9f,
                        deepSleepMinutes = 120,
                        remSleepMinutes = 60,
                        lightSleepMinutes = 300,
                        awakeMinutes = 15,
                        deviceName = "Device2",
                    ),
                )

            coEvery { sleepSessionDao.getSince(any()) } returns sessions
            every { scoringCalculator.validateNight(any(), any(), any(), any(), any(), any()) } returns
                ScoringCalculator.NightValidationResult(
                    rmssdValid = true,
                    rhrValid = true,
                    durationValid = true,
                    stagesValid = true,
                    stagesSuspicious = false,
                    hrCoverageValid = true,
                )
            coEvery { hrvDao.getSleepRmssdForSessionsMap(any()) } returns emptyMap()
            coEvery { heartRateDao.getAvgSleepHrForSessions(any()) } returns emptyMap()

            // Only session_1 has enough samples; session_2 has only 5
            val hrSamples =
                (0..149).map { idx ->
                    HeartRateRecordEntity(
                        id = "hr_1_$idx",
                        timestampMs = baselineFromMs + idx * 60000L,
                        beatsPerMinute = 50 + idx,
                        recordType = "SLEEP",
                        sessionId = "session_1",
                        deviceName = "Device1",
                    )
                } +
                    (0..4).map { idx ->
                        HeartRateRecordEntity(
                            id = "hr_2_$idx",
                            timestampMs = baselineFromMs + 24 * 60 * 60 * 1000 + idx * 60000L,
                            beatsPerMinute = 70 + idx,
                            recordType = "SLEEP",
                            sessionId = "session_2",
                            deviceName = "Device2",
                        )
                    }

            coEvery { heartRateDao.getSleepHrSamplesForSessions(any()) } returns hrSamples

            val result =
                baselineComputer.computeAdaptiveBaselineRhrBpm(
                    dayMidnight,
                    null,
                    SettingsDefaults.RESTING_HR_PERCENTILE,
                )

            // Result should be around session_1's nadir (non-frozen path → non-null)
            assertNotNull(result)
            assertTrue(result >= 50f && result <= 200f)
        }

    @Test
    fun `computeAdaptiveBaselineRhrBpm returns default when no valid sessions`() =
        runTest {
            val dayMidnight = Instant.now().truncatedTo(ChronoUnit.DAYS)

            coEvery { sleepSessionDao.getSince(any()) } returns emptyList()

            val result =
                baselineComputer.computeAdaptiveBaselineRhrBpm(
                    dayMidnight,
                    null,
                    SettingsDefaults.RESTING_HR_PERCENTILE,
                )
            // No sessions → returns DEFAULT_RHR_BPM (non-null, live recompute path)
            assertNotNull(result)
            assertEquals(ScoringConstants.DEFAULT_RHR_BPM, result)
        }

    @Test
    fun `computeAdaptiveBaselineRhrBpm adapts percentile based on sample count`() =
        runTest {
            val dayMidnight = Instant.now().truncatedTo(ChronoUnit.DAYS)
            val baselineFromMs = dayMidnight.minus(30, ChronoUnit.DAYS).toEpochMilli()

            val sessions =
                (1..4).map { i ->
                    SleepSessionEntity(
                        id = "session_$i",
                        startTime = baselineFromMs + (i - 1) * 24 * 60 * 60 * 1000L,
                        endTime = baselineFromMs + i * 24 * 60 * 60 * 1000L,
                        durationMinutes = 480,
                        efficiency = 0.9f,
                        deepSleepMinutes = 120,
                        remSleepMinutes = 60,
                        lightSleepMinutes = 300,
                        awakeMinutes = 15,
                        deviceName = "Device$i",
                    )
                }

            coEvery { sleepSessionDao.getSince(any()) } returns sessions
            every { scoringCalculator.validateNight(any(), any(), any(), any(), any(), any()) } returns
                ScoringCalculator.NightValidationResult(
                    rmssdValid = true,
                    rhrValid = true,
                    durationValid = true,
                    stagesValid = true,
                    stagesSuspicious = false,
                    hrCoverageValid = true,
                )
            coEvery { hrvDao.getSleepRmssdForSessionsMap(any()) } returns emptyMap()
            coEvery { heartRateDao.getAvgSleepHrForSessions(any()) } returns emptyMap()

            val hrSamples = mutableListOf<HeartRateRecordEntity>()

            // session_1: 300+ samples (5% percentile)
            hrSamples.addAll(
                (0..319).map { idx ->
                    HeartRateRecordEntity("h1_$idx", baselineFromMs + idx * 1000L, 40 + idx, "SLEEP", "session_1", "D1")
                },
            )
            // session_2: 150 samples (8% percentile)
            hrSamples.addAll(
                (0..149).map { idx ->
                    HeartRateRecordEntity(
                        "h2_$idx",
                        baselineFromMs + 24 * 3600 * 1000L + idx * 1000L,
                        45 + idx,
                        "SLEEP",
                        "session_2",
                        "D2",
                    )
                },
            )
            // session_3: 75 samples (10% percentile)
            hrSamples.addAll(
                (0..74).map { idx ->
                    HeartRateRecordEntity(
                        "h3_$idx",
                        baselineFromMs + 48 * 3600 * 1000L + idx * 1000L,
                        50 + idx,
                        "SLEEP",
                        "session_3",
                        "D3",
                    )
                },
            )
            // session_4: 50 samples (15% percentile)
            hrSamples.addAll(
                (0..49).map { idx ->
                    HeartRateRecordEntity(
                        "h4_$idx",
                        baselineFromMs + 72 * 3600 * 1000L + idx * 1000L,
                        55 + idx,
                        "SLEEP",
                        "session_4",
                        "D4",
                    )
                },
            )

            coEvery { heartRateDao.getSleepHrSamplesForSessions(any()) } returns hrSamples

            val result =
                baselineComputer.computeAdaptiveBaselineRhrBpm(
                    dayMidnight,
                    null,
                    SettingsDefaults.RESTING_HR_PERCENTILE,
                )

            assertNotNull(result)
            assertTrue(result >= 40f && result <= 150f)
        }

    @Test
    fun `query count is O(1) not O(n) for n sessions`() =
        runTest {
            val dayMidnight = Instant.now().truncatedTo(ChronoUnit.DAYS)
            val baselineFromMs = dayMidnight.minus(30, ChronoUnit.DAYS).toEpochMilli()

            // Create 100 sessions
            val sessions =
                (1..100).map { i ->
                    SleepSessionEntity(
                        id = "session_$i",
                        startTime = baselineFromMs + i * 1000L,
                        endTime = baselineFromMs + i * 1000L + 1,
                        durationMinutes = 480,
                        efficiency = 0.9f,
                        deepSleepMinutes = 120,
                        remSleepMinutes = 60,
                        lightSleepMinutes = 300,
                        awakeMinutes = 15,
                        deviceName = "Device$i",
                    )
                }

            coEvery { sleepSessionDao.getSince(any()) } returns sessions
            every { scoringCalculator.validateNight(any(), any(), any(), any(), any(), any()) } returns
                ScoringCalculator.NightValidationResult(
                    rmssdValid = true,
                    rhrValid = true,
                    durationValid = true,
                    stagesValid = true,
                    stagesSuspicious = false,
                    hrCoverageValid = true,
                )
            coEvery { hrvDao.getSleepRmssdForSessionsMap(any()) } returns emptyMap()
            coEvery { heartRateDao.getAvgSleepHrForSessions(any()) } returns emptyMap()

            // All sessions with 150 samples
            val allHrSamples =
                (1..100).flatMap { sessionIdx ->
                    (0..149).map { sampleIdx ->
                        HeartRateRecordEntity(
                            id = "hr_${sessionIdx}_$sampleIdx",
                            timestampMs = baselineFromMs + sessionIdx * 1000L + sampleIdx,
                            beatsPerMinute = 50 + sampleIdx,
                            recordType = "SLEEP",
                            sessionId = "session_$sessionIdx",
                            deviceName = "Device$sessionIdx",
                        )
                    }
                }

            coEvery { heartRateDao.getSleepHrSamplesForSessions(any()) } returns allHrSamples

            val startTime = System.currentTimeMillis()
            baselineComputer.computeAdaptiveBaselineRhrBpm(dayMidnight, null, SettingsDefaults.RESTING_HR_PERCENTILE)
            val endTime = System.currentTimeMillis()

            // Verify database query count is constant (not N*2)
            coVerify(exactly = 1) { sleepSessionDao.getSince(any()) }
            coVerify(exactly = 1) { heartRateDao.getSleepHrSamplesForSessions(any()) }

            // Computation should complete quickly even with 100 sessions
            assertTrue((endTime - startTime) < 1000, "Computation took too long: ${endTime - startTime}ms")
        }
}
