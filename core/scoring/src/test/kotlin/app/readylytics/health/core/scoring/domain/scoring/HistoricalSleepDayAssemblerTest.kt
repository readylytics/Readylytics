package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.model.SleepHrSample
import app.readylytics.health.core.model.domain.model.SleepSession
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayPolicy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoricalSleepDayAssemblerTest {
    private val repository = mockk<ScoringHistoryRepository>()
    private val scoringCalculator = mockk<ScoringCalculator>()
    private val assembler = HistoricalSleepDayAssembler(repository, scoringCalculator)

    @org.junit.Before
    fun setup() {
        every {
            scoringCalculator.validateNight(any(), any(), any(), any(), any(), any())
        } returns ScoringCalculator.NightValidationResult(
            rmssdValid = true,
            rhrValid = true,
            durationValid = true,
            stagesValid = true,
            stagesSuspicious = false,
            hrCoverageValid = true,
        )
    }

    @Test
    fun `buildHistoricalSleepDays uses provided zoneId when sleepDayPolicy is null`() =
        runTest {
            // Session ending at 2026-06-01 02:00:00 UTC
            // In UTC+13 (Pacific/Tongatapu), local time is 2026-06-01 15:00:00 -> scoreDay 2026-06-01
            // In UTC-8 (America/Los_Angeles), local time is 2026-05-31 19:00:00 -> scoreDay 2026-05-31
            val endInstant = Instant.parse("2026-06-01T02:00:00Z")
            val session =
                SleepSession(
                    id = "s1",
                    startTime = endInstant.minus(8, ChronoUnit.HOURS).toEpochMilli(),
                    endTime = endInstant.toEpochMilli(),
                    durationMinutes = 480,
                    efficiency = 0.95f,
                    deepSleepMinutes = 90,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 270,
                    awakeMinutes = 30,
                )

            every {
                scoringCalculator.validateNight(any(), any(), any(), any(), any(), any())
            } returns ScoringCalculator.NightValidationResult(
                rmssdValid = true,
                rhrValid = true,
                durationValid = true,
                stagesValid = true,
                stagesSuspicious = false,
                hrCoverageValid = true,
            )

            coEvery { repository.getSleepRmssdForSessionsMap(listOf("s1")) } returns mapOf("s1" to listOf(45f))
            coEvery { repository.getSleepHrProjectionForSessions(listOf("s1")) } returns
                (50..65).map { SleepHrSample("s1", it) }

            val zoneUtcPlus13 = ZoneId.of("Pacific/Tongatapu")
            val zoneUtcMinus8 = ZoneId.of("America/Los_Angeles")

            val daysPlus13 =
                assembler.buildHistoricalSleepDays(
                    sessions = listOf(session),
                    percentile = 10,
                    zoneId = zoneUtcPlus13,
                    sleepDayPolicy = null,
                )
            val daysMinus8 =
                assembler.buildHistoricalSleepDays(
                    sessions = listOf(session),
                    percentile = 10,
                    zoneId = zoneUtcMinus8,
                    sleepDayPolicy = null,
                )

            assertEquals(LocalDate.of(2026, 6, 1), daysPlus13.single().scoreDay)
            assertEquals(LocalDate.of(2026, 5, 31), daysMinus8.single().scoreDay)
        }

    @Test
    fun `buildHistoricalSleepDays produces identical scoreDay regardless of system default timezone`() =
        runTest {
            val endInstant = Instant.parse("2026-06-01T02:00:00Z")
            val session =
                SleepSession(
                    id = "s1",
                    startTime = endInstant.minus(8, ChronoUnit.HOURS).toEpochMilli(),
                    endTime = endInstant.toEpochMilli(),
                    durationMinutes = 480,
                    efficiency = 0.95f,
                    deepSleepMinutes = 90,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 270,
                    awakeMinutes = 30,
                )

            every {
                scoringCalculator.validateNight(any(), any(), any(), any(), any(), any())
            } returns ScoringCalculator.NightValidationResult(
                rmssdValid = true,
                rhrValid = true,
                durationValid = true,
                stagesValid = true,
                stagesSuspicious = false,
                hrCoverageValid = true,
            )

            coEvery { repository.getSleepRmssdForSessionsMap(listOf("s1")) } returns mapOf("s1" to listOf(45f))
            coEvery { repository.getSleepHrProjectionForSessions(listOf("s1")) } returns
                (50..65).map { SleepHrSample("s1", it) }

            val scoringZone = ZoneId.of("America/Los_Angeles")
            val originalTz = TimeZone.getDefault()

            try {
                TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Tongatapu")) // UTC+13
                val resultWithSystemUtc13 =
                    assembler.buildHistoricalSleepDays(
                        sessions = listOf(session),
                        percentile = 10,
                        zoneId = scoringZone,
                        sleepDayPolicy = null,
                    )

                TimeZone.setDefault(TimeZone.getTimeZone("America/New_York")) // UTC-5
                val resultWithSystemUtcMinus5 =
                    assembler.buildHistoricalSleepDays(
                        sessions = listOf(session),
                        percentile = 10,
                        zoneId = scoringZone,
                        sleepDayPolicy = null,
                    )

                assertEquals(LocalDate.of(2026, 5, 31), resultWithSystemUtc13.single().scoreDay)
                assertEquals(resultWithSystemUtc13.single().scoreDay, resultWithSystemUtcMinus5.single().scoreDay)
                assertEquals(
                    resultWithSystemUtc13.single().rhrPercentileBpm,
                    resultWithSystemUtcMinus5.single().rhrPercentileBpm,
                )
            } finally {
                TimeZone.setDefault(originalTz)
            }
        }

    @Test
    fun `buildHistoricalSleepDays with policy derives aggregate correctly`() =
        runTest {
            val scoringZone = ZoneId.of("UTC")
            val policy =
                SleepDayPolicy(
                    coreMergeGapMinutes = 120,
                    supplementalCutoffMinutesOfDay = 18 * 60,
                    minimumCountedSleepSegmentMinutes = 30,
                    supplementalArchitectureCoveragePercent = 80,
                    scoringZoneId = scoringZone,
                )

            val session1 =
                SleepSession(
                    id = "s1",
                    startTime = 0,
                    endTime = 28800000, // 8h
                    durationMinutes = 480,
                    efficiency = 0.9f,
                    deepSleepMinutes = 90,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 270,
                    awakeMinutes = 30,
                )

            coEvery { repository.getSleepRmssdForSessionsMap(listOf("s1")) } returns
                mapOf("s1" to listOf(40f, 50f, 60f))
            coEvery { repository.getSleepHrProjectionForSessions(listOf("s1")) } returns
                (50..59).map { SleepHrSample("s1", it) }

            val days =
                assembler.buildHistoricalSleepDays(
                    sessions = listOf(session1),
                    percentile = 10,
                    zoneId = scoringZone,
                    sleepDayPolicy = policy,
                )

            val day = days.single()
            assertEquals(LocalDate.of(1970, 1, 1), day.scoreDay)
            assertEquals(50f, day.hrvMean)
            assertEquals(51f, day.nadirBpm)
            assertEquals(51, day.rhrPercentileBpm)
        }

    @Test
    fun `buildHistoricalSleepDays with no valid HR samples keeps nadir null`() =
        runTest {
            val scoringZone = ZoneId.of("UTC")
            val session =
                SleepSession(
                    id = "s1",
                    startTime = 0,
                    endTime = 28800000,
                    durationMinutes = 480,
                    efficiency = 0.9f,
                    deepSleepMinutes = 90,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 270,
                    awakeMinutes = 30,
                )

            coEvery { repository.getSleepRmssdForSessionsMap(listOf("s1")) } returns emptyMap()
            coEvery { repository.getSleepHrProjectionForSessions(listOf("s1")) } returns emptyList()

            val days =
                assembler.buildHistoricalSleepDays(
                    sessions = listOf(session),
                    percentile = 10,
                    zoneId = scoringZone,
                    sleepDayPolicy = null,
                )

            val day = days.single()
            assertNull(day.nadirBpm)
            assertNull(day.rhrPercentileBpm)
        }

    private fun stubNightValidation(
        rmssd: Float,
        rhr: Float,
        duration: Int,
        deep: Int,
        rem: Int,
        isValid: Boolean,
    ) {
        every {
            scoringCalculator.validateNight(
                rmssdMs = rmssd,
                rhrBpm = rhr,
                durationMinutes = duration,
                deepMinutes = deep,
                remMinutes = rem,
            )
        } returns ScoringCalculator.NightValidationResult(
            rmssdValid = isValid,
            rhrValid = true,
            durationValid = isValid,
            stagesValid = isValid,
            stagesSuspicious = false,
            hrCoverageValid = isValid,
        )
    }

    @Test
    fun `filterValidBaselineSessions filters invalid sessions`() =
        runTest {
            val sessionValid =
                SleepSession(
                    id = "s_valid",
                    startTime = 0,
                    endTime = 28800000,
                    durationMinutes = 480,
                    efficiency = 0.9f,
                    deepSleepMinutes = 90,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 270,
                    awakeMinutes = 30,
                )
            val sessionInvalid =
                SleepSession(
                    id = "s_invalid",
                    startTime = 30000000,
                    endTime = 33600000,
                    durationMinutes = 60,
                    efficiency = 0.5f,
                    deepSleepMinutes = 0,
                    remSleepMinutes = 0,
                    lightSleepMinutes = 60,
                    awakeMinutes = 0,
                )

            coEvery { repository.getSleepRmssdForSessionsMap(listOf("s_valid", "s_invalid")) } returns
                mapOf("s_valid" to listOf(50f), "s_invalid" to listOf(20f))
            coEvery { repository.getAvgSleepHrForSessions(listOf("s_valid", "s_invalid")) } returns
                mapOf("s_valid" to 55, "s_invalid" to 75)

            stubNightValidation(50f, 55f, 480, 90, 90, isValid = true)
            stubNightValidation(20f, 75f, 60, 0, 0, isValid = false)

            val validIds = assembler.filterValidBaselineSessions(listOf(sessionValid, sessionInvalid))
            assertEquals(listOf("s_valid"), validIds)
        }
}
