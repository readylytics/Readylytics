package app.readylytics.health.core.scoring.domain.scoring.sleep

import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SleepNadirAnalyzerTest {
    private val calculator = mockk<ScoringCalculator>()
    private val analyzer = SleepNadirAnalyzer(calculator)

    @Test
    fun `analyze passes clockDurationMinutes instead of coreSleepDurationMinutes`() =
        runTest {
            val startMs = 1_000_000L
            // 8 hours in bed (480 minutes clock duration), but only 6 hours total sleep (360 minutes)
            val endMs = startMs + 480 * 60_000L
            val core =
                CoreRecoveryInput.fromSingleSession(
                    sessionId = "session-1",
                    startTimeMs = startMs,
                    endTimeMs = endMs,
                    coreSleepDurationMinutes = 360,
                    endZoneOffsetSeconds = 0,
                    previousCoreEndZoneOffsetSeconds = 0,
                )
            val minHrTimestamp = startMs + 300 * 60_000L

            every { calculator.isLateNadir(minHrTimestamp, startMs, 480) } returns false

            val result = analyzer.analyze(core, minHrTimestamp)

            assertFalse(result.isLateNadir)
            assertFalse(result.isTimezoneJump)
            verify(exactly = 1) {
                calculator.isLateNadir(minHrTimestamp, startMs, 480)
            }
        }

    @Test
    fun `nadir between 67 percent sleep time and 67 percent clock time is not late`() =
        runTest {
            // Real calculator behavior: 8h in bed (480m clock), 6.5h sleep (390m).
            // 67% of sleep time = 261.3m from start.
            // 67% of clock time = 321.6m from start.
            // A nadir at 280m is after 67% of sleep time, but BEFORE 67% of clock time.
            val startMs = 0L
            val endMs = 480 * 60_000L
            val core =
                CoreRecoveryInput.fromSingleSession(
                    sessionId = "session-real",
                    startTimeMs = startMs,
                    endTimeMs = endMs,
                    coreSleepDurationMinutes = 390,
                    endZoneOffsetSeconds = 0,
                    previousCoreEndZoneOffsetSeconds = 0,
                )
            val minHrTimestamp = startMs + 280 * 60_000L

            every { calculator.isLateNadir(minHrTimestamp, startMs, 480) } returns false

            val result = analyzer.analyze(core, minHrTimestamp)

            assertFalse(result.isLateNadir)
        }

    @Test
    fun `timezone jump suppresses late nadir`() =
        runTest {
            val startMs = 0L
            val endMs = 480 * 60_000L
            val core =
                CoreRecoveryInput.fromSingleSession(
                    sessionId = "session-tz",
                    startTimeMs = startMs,
                    endTimeMs = endMs,
                    coreSleepDurationMinutes = 480,
                    endZoneOffsetSeconds = 7200,
                    previousCoreEndZoneOffsetSeconds = 0, // 2-hour jump >= 3600s threshold
                )
            val minHrTimestamp = startMs + 350 * 60_000L

            every { calculator.isLateNadir(minHrTimestamp, startMs, 480) } returns true

            val result = analyzer.analyze(core, minHrTimestamp)

            assertFalse(result.isLateNadir)
            assertTrue(result.isTimezoneJump)
        }
}
