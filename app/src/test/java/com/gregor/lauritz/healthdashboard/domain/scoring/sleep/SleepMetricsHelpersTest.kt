package com.gregor.lauritz.healthdashboard.domain.scoring.sleep

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepHrSample
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringCalculator
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private const val DELTA = 0.5f

class CurrentNightHrvResolverTest {
    private val hrvDao = mockk<HrvDao>()
    private val dailySummaryDao = mockk<DailySummaryDao>()
    private val resolver = CurrentNightHrvResolver(hrvDao, dailySummaryDao)

    @Test
    fun `resolve_sessionHrvPresent_returnsSessionSamples`() =
        runTest {
            val session = mockSession(id = "1")
            val dayMidnight = Instant.parse("2026-05-16T00:00:00Z")
            val samples = listOf(30f, 35f, 40f)

            coEvery { hrvDao.getSleepRmssdForSession("1") } returns samples
            coEvery { hrvDao.getRmssdInTimeRange(any(), any()) } returns emptyList()

            val result = resolver.resolve(session, dayMidnight)

            assertEquals(samples, result.samples)
            assertEquals(35f, result.mean, DELTA)
        }

    @Test
    fun `resolve_sessionHrvEmpty_fallsBackToTimeRange`() =
        runTest {
            val session = mockSession(id = "1", startTime = 1000L, endTime = 5000L)
            val dayMidnight = Instant.parse("2026-05-16T00:00:00Z")
            val samples = listOf(28f, 32f)

            coEvery { hrvDao.getSleepRmssdForSession("1") } returns emptyList()
            coEvery { hrvDao.getRmssdInTimeRange(1000L, 5000L) } returns samples

            val result = resolver.resolve(session, dayMidnight)

            assertEquals(samples, result.samples)
            assertEquals(30f, result.mean, DELTA)
        }

    @Test
    fun `resolve_timeRangeEmpty_fallsBackToRollingMean`() =
        runTest {
            val session = mockSession(id = "1")
            val dayMidnight = Instant.parse("2026-05-16T00:00:00Z")
            val dayMidnightMs = dayMidnight.toEpochMilli()

            coEvery { hrvDao.getSleepRmssdForSession("1") } returns emptyList()
            coEvery { hrvDao.getRmssdInTimeRange(any(), any()) } returns emptyList()
            coEvery {
                dailySummaryDao.getSince(dayMidnightMs - 7 * 86400000)
            } returns
                listOf(
                    mockDailySummary(dateMidnightMs = dayMidnightMs - 86400000, nocturnalHrv = 32),
                    mockDailySummary(dateMidnightMs = dayMidnightMs - 172800000, nocturnalHrv = 28),
                )

            val result = resolver.resolve(session, dayMidnight)

            assertEquals(emptyList<Float>(), result.samples)
            assertEquals(30f, result.mean, DELTA)
        }

    @Test
    fun `resolve_allEmpty_returnsMeanZero`() =
        runTest {
            val session = mockSession(id = "1")
            val dayMidnight = Instant.parse("2026-05-16T00:00:00Z")

            coEvery { hrvDao.getSleepRmssdForSession("1") } returns emptyList()
            coEvery { hrvDao.getRmssdInTimeRange(any(), any()) } returns emptyList()
            coEvery { dailySummaryDao.getSince(any()) } returns emptyList()

            val result = resolver.resolve(session, dayMidnight)

            assertEquals(emptyList<Float>(), result.samples)
            assertEquals(0f, result.mean, DELTA)
        }
}

class WakeWindowHrCollectorTest {
    private val heartRateDao = mockk<HeartRateDao>()
    private val sleepSessionDao = mockk<SleepSessionDao>()
    private val collector = WakeWindowHrCollector(heartRateDao, sleepSessionDao)

    @Test
    fun `collect_historicRecordsPresent_computesBaselineAndRatio`() =
        runTest {
            val session = mockSession(id = "1", endTime = 10000L)
            val dayMidnight = Instant.parse("2026-05-16T00:00:00Z")

            coEvery { sleepSessionDao.getSince(any()) } returns
                listOf(
                    mockSession(id = "1", endTime = 10000L),
                    mockSession(id = "2", endTime = 9000L),
                )
            coEvery { heartRateDao.getSleepHrProjectionForSessions(any()) } returns
                listOf(
                    SleepHrSample(sessionId = "1", beatsPerMinute = 55),
                    SleepHrSample(sessionId = "1", beatsPerMinute = 60),
                    SleepHrSample(sessionId = "2", beatsPerMinute = 50),
                )

            val result = collector.collect(session, dayMidnight)

            assertEquals(55, result.currentRestingHr)
            assertEquals(50, result.restingHrBaseline)
            assertEquals(1.1f, result.restingHrRatio!!, DELTA)
        }

    @Test
    fun `collect_noHistoricRecords_baselineNull`() =
        runTest {
            val session = mockSession(id = "1")
            val dayMidnight = Instant.parse("2026-05-16T00:00:00Z")

            coEvery { sleepSessionDao.getSince(any()) } returns listOf(session)
            coEvery { heartRateDao.getSleepHrProjectionForSessions(any()) } returns emptyList()

            val result = collector.collect(session, dayMidnight)

            assertNull(result.currentRestingHr)
            assertNull(result.restingHrBaseline)
            assertNull(result.restingHrRatio)
        }

    @Test
    fun `collect_currentWindowEmpty_currentRestingHrNull`() =
        runTest {
            val session = mockSession(id = "1", endTime = 10000L)
            val dayMidnight = Instant.parse("2026-05-16T00:00:00Z")

            coEvery { sleepSessionDao.getSince(any()) } returns
                listOf(
                    mockSession(id = "1", endTime = 10000L),
                    mockSession(id = "2", endTime = 9000L),
                )
            coEvery { heartRateDao.getSleepHrProjectionForSessions(any()) } returns
                listOf(
                    SleepHrSample(sessionId = "2", beatsPerMinute = 50),
                )

            val result = collector.collect(session, dayMidnight)

            assertNull(result.currentRestingHr)
            assertEquals(50, result.restingHrBaseline)
        }

    @Test
    fun `collect_variousPercentileSettings_selectsCorrectBpmValue`() =
        runTest {
            val session = mockSession(id = "1", endTime = 10000L)
            val dayMidnight = Instant.parse("2026-05-16T00:00:00Z")

            coEvery { sleepSessionDao.getSince(any()) } returns listOf(session)

            // Provide 10 heart rate records for the current window: bpm values from 50 to 59
            val records =
                (0..9).map { i ->
                    SleepHrSample(sessionId = "1", beatsPerMinute = 50 + i)
                }
            coEvery { heartRateDao.getSleepHrProjectionForSessions(any()) } returns records

            // Test default percentile = 5.
            // S = 10. index = ((5 / 100.0) * (10 - 1)).toInt() = (0.05 * 9).toInt() = 0.
            // Expected: 50.
            val resultDefault = collector.collect(session, dayMidnight, percentile = 5)
            assertEquals(50, resultDefault.currentRestingHr)

            // Test percentile = 12.
            // index = ((12 / 100.0) * 9).toInt() = (1.08).toInt() = 1.
            // Expected: 51.
            val resultTwelve = collector.collect(session, dayMidnight, percentile = 12)
            assertEquals(51, resultTwelve.currentRestingHr)

            // Test percentile = 15.
            // index = ((15 / 100.0) * 9).toInt() = (1.35).toInt() = 1.
            // Expected: 51.
            val resultFifteen = collector.collect(session, dayMidnight, percentile = 15)
            assertEquals(51, resultFifteen.currentRestingHr)
        }
}

class SleepNadirAnalyzerTest {
    private val heartRateDao = mockk<HeartRateDao>()
    private val scoringCalculator = mockk<ScoringCalculator>()
    private val analyzer = SleepNadirAnalyzer(heartRateDao, scoringCalculator)

    @Test
    fun `analyze_nadirInLastThird_isLateTrue`() =
        runTest {
            val session =
                mockSession(
                    id = "1",
                    startTime = 1000L,
                    durationMinutes = 480,
                    endZoneOffsetSeconds = 0,
                )
            val historical = listOf(mockSession(endZoneOffsetSeconds = 0, endTime = 500L))

            coEvery { heartRateDao.getMinHrTimestamp("1") } returns 2000L
            coEvery {
                scoringCalculator.isLateNadir(2000L, 1000L, 480)
            } returns true

            val result = analyzer.analyze(session, historical)

            assertTrue(result.isLateNadir)
            assertFalse(result.isTimezoneJump)
        }

    @Test
    fun `analyze_timezoneJump_suppressesLateNadir`() =
        runTest {
            val session =
                mockSession(
                    id = "1",
                    startTime = 1000L,
                    durationMinutes = 480,
                    endZoneOffsetSeconds = 3600,
                )
            val historical = listOf(mockSession(endZoneOffsetSeconds = 0, endTime = 500L))

            coEvery { heartRateDao.getMinHrTimestamp("1") } returns 2000L
            coEvery {
                scoringCalculator.isLateNadir(2000L, 1000L, 480)
            } returns true

            val result = analyzer.analyze(session, historical)

            assertFalse(result.isLateNadir)
            assertTrue(result.isTimezoneJump)
        }

    @Test
    fun `analyze_noPreviousSession_noTimezoneJump`() =
        runTest {
            val session = mockSession(id = "1", endZoneOffsetSeconds = 3600)

            coEvery { heartRateDao.getMinHrTimestamp("1") } returns null

            val result = analyzer.analyze(session, emptyList())

            assertFalse(result.isLateNadir)
            assertFalse(result.isTimezoneJump)
        }
}

class HrCoverageValidatorTest {
    private val validator = HrCoverageValidator()

    @Test
    fun `isValid_coverageAbove70pct_returnsTrue`() {
        val sessionStart = 1000L
        val sessionEnd = 10000L
        val durationMinutes = 150
        val records =
            listOf(
                mockHeartRateRecord(timestampMs = 2000L),
                mockHeartRateRecord(timestampMs = 8000L),
            )

        val result = validator.isValid(sessionStart, sessionEnd, durationMinutes, records)

        assertTrue(result)
    }

    @Test
    fun `isValid_coverageBelow70pct_returnsFalse`() {
        val sessionStart = 1000L
        val sessionEnd = 100000L
        val durationMinutes = 1500
        val records =
            listOf(
                mockHeartRateRecord(timestampMs = 2000L),
            )

        val result = validator.isValid(sessionStart, sessionEnd, durationMinutes, records)

        assertFalse(result)
    }
}

private fun mockSession(
    id: String = "1",
    startTime: Long = 1000L,
    endTime: Long = 10000L,
    durationMinutes: Int = 150,
    endZoneOffsetSeconds: Int? = 0,
): SleepSessionEntity =
    SleepSessionEntity(
        id = id,
        startTime = startTime,
        endTime = endTime,
        durationMinutes = durationMinutes,
        deepSleepMinutes = 90,
        remSleepMinutes = 30,
        lightSleepMinutes = 20,
        awakeMinutes = 10,
        efficiency = 0.9f,
        endZoneOffsetSeconds = endZoneOffsetSeconds,
    )

private fun mockHeartRateRecord(
    timestampMs: Long = 1000L,
    bpm: Int = 60,
    sessionId: String? = null,
): HeartRateRecordEntity =
    HeartRateRecordEntity(
        id = "hr_$timestampMs",
        beatsPerMinute = bpm,
        timestampMs = timestampMs,
        recordType = "SLEEP",
        sessionId = sessionId,
    )

private fun mockDailySummary(
    dateMidnightMs: Long = 0L,
    nocturnalHrv: Int? = null,
): DailySummaryEntity =
    DailySummaryEntity(
        dateMidnightMs = dateMidnightMs,
        nocturnalHrv = nocturnalHrv,
    )
