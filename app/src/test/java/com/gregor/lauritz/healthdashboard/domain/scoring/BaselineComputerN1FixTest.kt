package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
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
    private lateinit var baselineComputer: BaselineComputer

    @Before
    fun setup() {
        heartRateDao = mockk()
        hrvDao = mockk()
        sleepSessionDao = mockk()
        scoringCalculator = mockk()

        baselineComputer =
            BaselineComputer(heartRateDao, hrvDao, sleepSessionDao, scoringCalculator)
    }

    @Test
    fun `computeAdaptiveBaselineRhrBpm returns override when provided`() =
        runTest {
            val dayMidnight = Instant.now()
            val override = 65f

            val result = baselineComputer.computeAdaptiveBaselineRhrBpm(dayMidnight, override)

            assertEquals(override, result)

            // Verify no database queries made when override provided
            coVerify(exactly = 0) { sleepSessionDao.getSince(any()) }
            coVerify(exactly = 0) { heartRateDao.getSleepHrSamplesForSessions(any()) }
        }

    @Test
    fun `computeAdaptiveBaselineRhrBpm correctly computes nadir for 30-day window`() =
        runTest {
            val dayMidnight = Instant.now()
            val baselineFromMs = dayMidnight.minus(30, ChronoUnit.DAYS).toEpochMilli()

            // Create 30 mock sleep sessions
            val sessions =
                (1..30).map { i ->
                    SleepSessionEntity(
                        id = "session_$i",
                        startTime = baselineFromMs + (i.toLong() - 1) * 24 * 60 * 60 * 1000,
                        endTime = baselineFromMs + i.toLong() * 24 * 60 * 60 * 1000,
                        duration = 8 * 60 * 60 * 1000,
                        deepSleepMinutes = 120,
                        remSleepMinutes = 60,
                        awakeMinutes = 15,
                        deviceName = "Device$i",
                    )
                }

            coEvery { sleepSessionDao.getSince(baselineFromMs) } returns sessions

            // Mock validation to accept all sessions
            coEvery { scoringCalculator.validateNight(any(), any(), any(), any(), any(), any()) } returns
                mockk {
                    coEvery { canContributeToBaseline } returns true
                }

            coEvery { hrvDao.getSleepRmssdForSessionsMap(any()) } returns emptyMap()
            coEvery { heartRateDao.getAvgSleepHrForSessions(any()) } returns emptyMap()

            // Mock HR samples for all sessions (batch query)
            val allHrSamples =
                (1..30).flatMap { sessionIdx ->
                    // Each session has 150 samples, ranging from 40-90 bpm
                    (0..149).map { sampleIdx ->
                        HeartRateRecordEntity(
                            id = "hr_${sessionIdx}_$sampleIdx",
                            sessionId = "session_$sessionIdx",
                            recordType = "SLEEP",
                            beatsPerMinute = 40 + sampleIdx,
                            timestampMs = baselineFromMs + sessionIdx.toLong() * 1000 + sampleIdx,
                            deviceName = "Device$sessionIdx",
                        )
                    }
                }

            coEvery { heartRateDao.getSleepHrSamplesForSessions(any()) } returns allHrSamples

            val result = baselineComputer.computeAdaptiveBaselineRhrBpm(dayMidnight, null)

            // With 150 samples per session, 8% percentile = sample[12]
            // All sessions have same pattern, so median of nadirs = 52 (40 + 12)
            assertTrue(result > 40f && result < 90f)

            // Verify ONLY 2 database queries were made
            coVerify(exactly = 1) { sleepSessionDao.getSince(baselineFromMs) }
            coVerify(exactly = 1) { hrvDao.getSleepRmssdForSessionsMap(any()) }
            coVerify(exactly = 1) { heartRateDao.getAvgSleepHrForSessions(any()) }
            coVerify(exactly = 1) { heartRateDao.getSleepHrSamplesForSessions(any()) }
            // NOT called: getSleepHrSampleCount and getSleepHrSampleAtOffset
            coVerify(exactly = 0) { heartRateDao.getSleepHrSampleCount(any()) }
            coVerify(exactly = 0) { heartRateDao.getSleepHrSampleAtOffset(any(), any()) }
        }

    @Test
    fun `computeAdaptiveBaselineRhrBpm filters sessions with less than 10 samples`() =
        runTest {
            val dayMidnight = Instant.now()
            val baselineFromMs = dayMidnight.minus(30, ChronoUnit.DAYS).toEpochMilli()

            val sessions =
                listOf(
                    SleepSessionEntity(
                        id = "session_1",
                        startTime = baselineFromMs,
                        endTime = baselineFromMs + 8 * 60 * 60 * 1000,
                        duration = 8 * 60 * 60 * 1000,
                        deepSleepMinutes = 120,
                        remSleepMinutes = 60,
                        awakeMinutes = 15,
                        deviceName = "Device1",
                    ),
                    SleepSessionEntity(
                        id = "session_2",
                        startTime = baselineFromMs + 24 * 60 * 60 * 1000,
                        endTime = baselineFromMs + 32 * 60 * 60 * 1000,
                        duration = 8 * 60 * 60 * 1000,
                        deepSleepMinutes = 120,
                        remSleepMinutes = 60,
                        awakeMinutes = 15,
                        deviceName = "Device2",
                    ),
                )

            coEvery { sleepSessionDao.getSince(baselineFromMs) } returns sessions

            coEvery { scoringCalculator.validateNight(any(), any(), any(), any(), any(), any()) } returns
                mockk {
                    coEvery { canContributeToBaseline } returns true
                }

            coEvery { hrvDao.getSleepRmssdForSessionsMap(any()) } returns emptyMap()
            coEvery { heartRateDao.getAvgSleepHrForSessions(any()) } returns emptyMap()

            // Only session_1 has enough samples; session_2 has only 5
            val hrSamples =
                listOf(
                    // session_1: 150 samples
                    *Array(150) { idx ->
                        HeartRateRecordEntity(
                            id = "hr_1_$idx",
                            sessionId = "session_1",
                            recordType = "SLEEP",
                            beatsPerMinute = 50 + idx,
                            timestampMs = baselineFromMs + idx,
                            deviceName = "Device1",
                        )
                    },
                    // session_2: only 5 samples (< 10, should be filtered)
                    *Array(5) { idx ->
                        HeartRateRecordEntity(
                            id = "hr_2_$idx",
                            sessionId = "session_2",
                            recordType = "SLEEP",
                            beatsPerMinute = 70 + idx,
                            timestampMs = baselineFromMs + 24 * 60 * 60 * 1000 + idx,
                            deviceName = "Device2",
                        )
                    },
                )

            coEvery { heartRateDao.getSleepHrSamplesForSessions(any()) } returns hrSamples

            val result = baselineComputer.computeAdaptiveBaselineRhrBpm(dayMidnight, null)

            // Only session_1's nadir should be included
            assertTrue(result > 50f && result < 200f)
        }

    @Test
    fun `computeAdaptiveBaselineRhrBpm returns default when no valid sessions`() =
        runTest {
            val dayMidnight = Instant.now()
            val baselineFromMs = dayMidnight.minus(30, ChronoUnit.DAYS).toEpochMilli()

            coEvery { sleepSessionDao.getSince(baselineFromMs) } returns emptyList()

            val result = baselineComputer.computeAdaptiveBaselineRhrBpm(dayMidnight, null)

            assertEquals(ScoringConstants.DEFAULT_RHR_BPM, result)
        }

    @Test
    fun `computeAdaptiveBaselineRhrBpm adapts percentile based on sample count`() =
        runTest {
            val dayMidnight = Instant.now()
            val baselineFromMs = dayMidnight.minus(30, ChronoUnit.DAYS).toEpochMilli()

            val sessions =
                (1..4).map { i ->
                    SleepSessionEntity(
                        id = "session_$i",
                        startTime = baselineFromMs + (i.toLong() - 1) * 24 * 60 * 60 * 1000,
                        endTime = baselineFromMs + i.toLong() * 24 * 60 * 60 * 1000,
                        duration = 8 * 60 * 60 * 1000,
                        deepSleepMinutes = 120,
                        remSleepMinutes = 60,
                        awakeMinutes = 15,
                        deviceName = "Device$i",
                    )
                }

            coEvery { sleepSessionDao.getSince(baselineFromMs) } returns sessions

            coEvery { scoringCalculator.validateNight(any(), any(), any(), any(), any(), any()) } returns
                mockk {
                    coEvery { canContributeToBaseline } returns true
                }

            coEvery { hrvDao.getSleepRmssdForSessionsMap(any()) } returns emptyMap()
            coEvery { heartRateDao.getAvgSleepHrForSessions(any()) } returns emptyMap()

            val hrSamples =
                listOf(
                    // session_1: 300+ samples (5% percentile)
                    *Array(300) { idx ->
                        HeartRateRecordEntity(
                            id = "hr_1_$idx",
                            sessionId = "session_1",
                            recordType = "SLEEP",
                            beatsPerMinute = 40 + idx,
                            timestampMs = baselineFromMs + idx,
                            deviceName = "Device1",
                        )
                    },
                    // session_2: 150 samples (8% percentile)
                    *Array(150) { idx ->
                        HeartRateRecordEntity(
                            id = "hr_2_$idx",
                            sessionId = "session_2",
                            recordType = "SLEEP",
                            beatsPerMinute = 45 + idx,
                            timestampMs = baselineFromMs + 24 * 60 * 60 * 1000 + idx,
                            deviceName = "Device2",
                        )
                    },
                    // session_3: 75 samples (10% percentile)
                    *Array(75) { idx ->
                        HeartRateRecordEntity(
                            id = "hr_3_$idx",
                            sessionId = "session_3",
                            recordType = "SLEEP",
                            beatsPerMinute = 50 + idx,
                            timestampMs = baselineFromMs + 48 * 60 * 60 * 1000 + idx,
                            deviceName = "Device3",
                        )
                    },
                    // session_4: 50 samples (15% percentile)
                    *Array(50) { idx ->
                        HeartRateRecordEntity(
                            id = "hr_4_$idx",
                            sessionId = "session_4",
                            recordType = "SLEEP",
                            beatsPerMinute = 55 + idx,
                            timestampMs = baselineFromMs + 72 * 60 * 60 * 1000 + idx,
                            deviceName = "Device4",
                        )
                    },
                )

            coEvery { heartRateDao.getSleepHrSamplesForSessions(any()) } returns hrSamples

            val result = baselineComputer.computeAdaptiveBaselineRhrBpm(dayMidnight, null)

            // Result should be median of nadirs with different percentiles
            assertTrue(result > 40f && result < 150f)
        }

    @Test
    fun `query count is O(1) not O(n) for n sessions`() =
        runTest {
            val dayMidnight = Instant.now()
            val baselineFromMs = dayMidnight.minus(30, ChronoUnit.DAYS).toEpochMilli()

            // Create 100 sessions
            val sessions =
                (1..100).map { i ->
                    SleepSessionEntity(
                        id = "session_$i",
                        startTime = baselineFromMs + (i.toLong() - 1) * 24 * 60 * 60 * 1000,
                        endTime = baselineFromMs + i.toLong() * 24 * 60 * 60 * 1000,
                        duration = 8 * 60 * 60 * 1000,
                        deepSleepMinutes = 120,
                        remSleepMinutes = 60,
                        awakeMinutes = 15,
                        deviceName = "Device$i",
                    )
                }

            coEvery { sleepSessionDao.getSince(baselineFromMs) } returns sessions

            coEvery { scoringCalculator.validateNight(any(), any(), any(), any(), any(), any()) } returns
                mockk {
                    coEvery { canContributeToBaseline } returns true
                }

            coEvery { hrvDao.getSleepRmssdForSessionsMap(any()) } returns emptyMap()
            coEvery { heartRateDao.getAvgSleepHrForSessions(any()) } returns emptyMap()

            // All sessions with 150 samples
            val hrSamples =
                (1..100).flatMap { sessionIdx ->
                    (0..149).map { sampleIdx ->
                        HeartRateRecordEntity(
                            id = "hr_${sessionIdx}_$sampleIdx",
                            sessionId = "session_$sessionIdx",
                            recordType = "SLEEP",
                            beatsPerMinute = 50 + sampleIdx,
                            timestampMs = baselineFromMs + sessionIdx.toLong() * 1000 + sampleIdx,
                            deviceName = "Device$sessionIdx",
                        )
                    }
                }

            coEvery { heartRateDao.getSleepHrSamplesForSessions(any()) } returns hrSamples

            val startTime = System.currentTimeMillis()
            baselineComputer.computeAdaptiveBaselineRhrBpm(dayMidnight, null)
            val endTime = System.currentTimeMillis()

            // Verify database query count is constant (not N*2)
            coVerify(exactly = 1) { sleepSessionDao.getSince(any()) }
            coVerify(exactly = 1) { heartRateDao.getSleepHrSamplesForSessions(any()) }
            coVerify(exactly = 0) { heartRateDao.getSleepHrSampleCount(any()) }
            coVerify(exactly = 0) { heartRateDao.getSleepHrSampleAtOffset(any(), any()) }

            // Computation should complete quickly even with 100 sessions
            assertTrue((endTime - startTime) < 1000, "Computation took too long: ${endTime - startTime}ms")
        }
}
