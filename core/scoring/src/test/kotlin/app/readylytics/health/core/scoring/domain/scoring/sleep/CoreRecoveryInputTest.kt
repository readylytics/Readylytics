package app.readylytics.health.core.scoring.domain.scoring.sleep

import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * WP-14/C4: [CoreRecoveryInput] must be built strictly from [SleepDayAggregate.coreCluster]/
 * [SleepDayAggregate.recoveryWindow] -- never the synthetic, nap-inclusive total-duration session
 * -- so recovery-timing/fragmentation results are identical whether or not a same-day supplemental
 * nap is present, while [SleepDayAggregate.totalDurationMinutes] correctly does grow with the nap.
 *
 * Per the brief: "Bind these aggregates from the fixture before checking SleepNadirAnalyzer/
 * modifier outputs; aggregate equality alone does not test the repaired call path" -- so every
 * scenario here feeds the built [CoreRecoveryInput] through the real [SleepNadirAnalyzer], not just
 * comparing aggregate fields.
 */
class CoreRecoveryInputTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val scoreDay = LocalDate.of(2026, 7, 8)

    @Test
    fun `nap-inclusive total duration grows but the core recovery window does not`() {
        val core = coreSegment()
        val nap = napSegment()

        val withoutNap = aggregateFor(listOf(core))
        val withNap = aggregateFor(listOf(core, nap))

        assertEquals(withoutNap.recoveryWindow, withNap.recoveryWindow)
        assertEquals(withoutNap.totalDurationMinutes + 60, withNap.totalDurationMinutes)
    }

    @Test
    fun `nadir and timezone-jump results are identical with and without a same-day nap`() =
        runTest {
            val core = coreSegment()
            val nap = napSegment()
            // Same offset as the core's own end -- i.e. no travel -- so this test isolates the
            // with-nap/without-nap comparison from timezone-jump suppression.
            val previousOffset = core.endZoneOffsetSeconds

            val withoutNapCore = CoreRecoveryInput.from(aggregateFor(listOf(core)), previousOffset)
            val withNapCore = CoreRecoveryInput.from(aggregateFor(listOf(core, nap)), previousOffset)

            // The core cluster/window/offset evidence must be identical -- a nap must not be able
            // to move any of it.
            assertEquals(withoutNapCore, withNapCore)

            val minHrTimestamp = core.startTimeMs + Duration.ofHours(5).toMillis()
            val scoringCalculator = fakeScoringCalculator(returnsLate = true)
            val analyzer = SleepNadirAnalyzer(scoringCalculator)

            val withoutNapResult = analyzer.analyze(withoutNapCore, minHrTimestamp)
            val withNapResult = analyzer.analyze(withNapCore, minHrTimestamp)

            assertEquals(withoutNapResult, withNapResult)
            assertTrue(withoutNapResult.isLateNadir)
            assertFalse(withoutNapResult.isTimezoneJump)
        }

    @Test
    fun `a split core across allowed merge segments matches an equivalent single merged core`() =
        runTest {
            val merged =
                coreSegment(id = "core-merged", start = at(0, 0), end = at(6, 0), light = 200, deep = 100, rem = 60)
            val splitFirst =
                coreSegment(id = "core-a", start = at(0, 0), end = at(3, 0), light = 100, deep = 50, rem = 30)
            val splitSecond =
                // Back-to-back (zero gap, well under the allowed coreMergeGapMinutes) so the
                // aggregator merges these into one cluster with the exact same window/duration as
                // `merged` above -- only the segment count/IDs differ.
                coreSegment(id = "core-b", start = at(3, 0), end = at(6, 0), light = 100, deep = 50, rem = 30)

            val mergedCore = CoreRecoveryInput.from(aggregateFor(listOf(merged)), null)
            val splitCore = CoreRecoveryInput.from(aggregateFor(listOf(splitFirst, splitSecond)), null)

            assertEquals(mergedCore.window, splitCore.window)
            assertEquals(setOf("core-a", "core-b"), splitCore.sessionIds)

            val scoringCalculator = fakeScoringCalculator(returnsLate = false)
            val analyzer = SleepNadirAnalyzer(scoringCalculator)
            val minHrTimestamp = at(0, 0).toInstant().toEpochMilli()

            assertEquals(
                analyzer.analyze(mergedCore, minHrTimestamp),
                analyzer.analyze(splitCore, minHrTimestamp),
            )
        }

    @Test
    fun `overlap canonicalization keeps only the winning segment's id in the core`() {
        val loser =
            coreSegment(id = "low-coverage", start = at(0, 0), end = at(6, 0), light = 60, packageName = "z.low")
        val winner =
            coreSegment(
                id = "high-coverage",
                start = at(0, 0),
                end = at(6, 0),
                light = 200,
                deep = 100,
                rem = 60,
                packageName = "a.high",
            )

        val aggregate = aggregateFor(listOf(loser, winner))
        val core = CoreRecoveryInput.from(aggregate, null)

        assertEquals(setOf("high-coverage"), core.sessionIds)
    }

    @Test
    fun `stage-less core night resolves without stage minutes`() =
        runTest {
            val bare = coreSegment(id = "bare", start = at(0, 0), end = at(6, 0))
            val core = CoreRecoveryInput.from(aggregateFor(listOf(bare)), null)

            assertEquals(360, core.window.coreSleepDurationMinutes)

            val scoringCalculator = fakeScoringCalculator(returnsLate = false)
            val analyzer = SleepNadirAnalyzer(scoringCalculator)

            val result = analyzer.analyze(core, minHrTimestamp = null)

            assertFalse(result.isLateNadir)
            assertFalse(result.isTimezoneJump)
        }

    @Test
    fun `a timezone offset change beyond the threshold suppresses late-nadir via the jump flag`() =
        runTest {
            val core =
                coreSegment(id = "core", start = at(0, 0), end = at(6, 0), offsetSeconds = 7200)
            val previousOffsetSeconds = 0 // 2h jump >= the 1h threshold

            val coreInput = CoreRecoveryInput.from(aggregateFor(listOf(core)), previousOffsetSeconds)
            val scoringCalculator = fakeScoringCalculator(returnsLate = true)
            val analyzer = SleepNadirAnalyzer(scoringCalculator)

            val result = analyzer.analyze(coreInput, minHrTimestamp = core.startTimeMs + 1)

            assertTrue(result.isTimezoneJump)
            assertFalse(result.isLateNadir)
        }

    // ---- fixtures ----

    private fun aggregateFor(segments: List<SleepDaySegment>): SleepDayAggregate =
        assertNotNull(SleepDayAggregator.aggregateForScoreDay(scoreDay, segments, policy()))

    private fun coreSegment(
        id: String = "core",
        start: ZonedDateTime = at(0, 0),
        end: ZonedDateTime = at(6, 0),
        light: Int = 0,
        deep: Int = 0,
        rem: Int = 0,
        awake: Int = 0,
        packageName: String = id,
        offsetSeconds: Int? = null,
    ): SleepDaySegment =
        SleepDaySegment(
            stableId = id,
            startTimeMs = start.toInstant().toEpochMilli(),
            endTimeMs = end.toInstant().toEpochMilli(),
            durationMinutes = Duration.between(start, end).toMinutes().toInt(),
            lightSleepMinutes = light,
            deepSleepMinutes = deep,
            remSleepMinutes = rem,
            awakeMinutes = awake,
            startZoneOffsetSeconds = offsetSeconds ?: start.offset.totalSeconds,
            endZoneOffsetSeconds = offsetSeconds ?: end.offset.totalSeconds,
            sourcePackageName = packageName,
        )

    private fun napSegment(
        id: String = "nap",
        start: ZonedDateTime = at(15, 0),
        end: ZonedDateTime = at(16, 0),
        light: Int = 60,
    ): SleepDaySegment =
        SleepDaySegment(
            stableId = id,
            startTimeMs = start.toInstant().toEpochMilli(),
            endTimeMs = end.toInstant().toEpochMilli(),
            durationMinutes = Duration.between(start, end).toMinutes().toInt(),
            lightSleepMinutes = light,
            startZoneOffsetSeconds = start.offset.totalSeconds,
            endZoneOffsetSeconds = end.offset.totalSeconds,
            sourcePackageName = id,
        )

    private fun at(
        hour: Int,
        minute: Int,
    ): ZonedDateTime =
        ZonedDateTime.of(scoreDay.year, scoreDay.monthValue, scoreDay.dayOfMonth, hour, minute, 0, 0, berlin)

    private fun policy(): SleepDayPolicy =
        SleepDayPolicy(
            coreMergeGapMinutes = 90,
            // Matches the shipped default (SettingsDefaults.SUPPLEMENTAL_CUTOFF_MINUTES_OF_DAY):
            // 20:00, well after the 15:00-16:00 nap fixture, so the nap stays supplemental to the
            // SAME scoreDay as the core rather than rolling into its own next-day aggregate.
            supplementalCutoffMinutesOfDay = 1200,
            minimumCountedSleepSegmentMinutes = 30,
            supplementalArchitectureCoveragePercent = 70,
            scoringZoneId = berlin,
        )

    private fun fakeScoringCalculator(returnsLate: Boolean): ScoringCalculator {
        val calculator = mockk<ScoringCalculator>()
        every { calculator.isLateNadir(any(), any(), any()) } returns returnsLate
        return calculator
    }
}
