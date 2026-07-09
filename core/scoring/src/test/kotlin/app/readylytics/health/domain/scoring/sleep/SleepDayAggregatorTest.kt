package app.readylytics.health.domain.scoring.sleep

import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SleepDayAggregatorTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun preservesMonophasicBehaviorForSingleOvernightSession() {
        val session =
            segment(
                id = "core",
                start = at(2026, 7, 8, 23, 0),
                end = at(2026, 7, 9, 7, 0),
                light = 240,
                deep = 90,
                rem = 120,
                awake = 30,
            )

        val result = SleepDayAggregator.aggregate(listOf(session), policy())

        assertEquals(1, result.aggregates.size)
        assertTrue(result.rejectedSegments.isEmpty())

        val aggregate = result.aggregates.single()
        assertEquals(LocalDate.of(2026, 7, 9), aggregate.scoreDay)
        assertEquals(480, aggregate.totalDurationMinutes)
        assertTrue(aggregate.supplementalBlocks.isEmpty())
        assertEquals(listOf("core"), aggregate.coreCluster.segments.map { it.stableId })
        assertEquals(SleepStageTotals(lightMinutes = 240, deepMinutes = 90, remMinutes = 120, awakeMinutes = 30), aggregate.architectureTotals)
        assertEquals(session.startTimeMs, aggregate.recoveryWindow.startTimeMs)
        assertEquals(session.endTimeMs, aggregate.recoveryWindow.endTimeMs)
        assertEquals(480, aggregate.recoveryWindow.clockDurationMinutes)
        assertEquals(480, aggregate.recoveryWindow.coreSleepDurationMinutes)
    }

    @Test
    fun groupsCoreClusterCountsEligibleSupplementalAndSeversExactCutoffToNextDay() {
        val coreFirst =
            segment(
                id = "core-1",
                start = at(2026, 7, 8, 23, 0),
                end = at(2026, 7, 9, 2, 30),
                light = 90,
                deep = 60,
                rem = 60,
            )
        val coreSecond =
            segment(
                id = "core-2",
                start = at(2026, 7, 9, 3, 15),
                end = at(2026, 7, 9, 7, 15),
                light = 150,
                deep = 30,
                rem = 45,
                awake = 15,
            )
        val supplemental =
            segment(
                id = "nap-pass",
                start = at(2026, 7, 9, 13, 0),
                end = at(2026, 7, 9, 14, 0),
                light = 30,
                deep = 12,
            )
        val tooShort =
            segment(
                id = "nap-short",
                start = at(2026, 7, 9, 14, 20),
                end = at(2026, 7, 9, 14, 40),
                durationMinutes = 20,
                light = 20,
            )
        val exactCutoff =
            segment(
                id = "cutoff-core",
                start = at(2026, 7, 9, 15, 0),
                end = at(2026, 7, 9, 16, 0),
                light = 40,
                deep = 10,
            )

        val result =
            SleepDayAggregator.aggregate(
                listOf(coreFirst, coreSecond, supplemental, tooShort, exactCutoff),
                policy(coreMergeGapMinutes = 90, minimumCountedSleepSegmentMinutes = 30),
            )

        assertEquals(listOf(LocalDate.of(2026, 7, 9), LocalDate.of(2026, 7, 10)), result.aggregates.map { it.scoreDay })

        val july9 = result.aggregates.first()
        assertEquals(listOf("core-1", "core-2"), july9.coreCluster.segments.map { it.stableId })
        assertEquals(450, july9.coreCluster.totalDurationMinutes)
        assertEquals(510, july9.totalDurationMinutes)
        assertEquals(495, july9.recoveryWindow.clockDurationMinutes)
        assertEquals(450, july9.recoveryWindow.coreSleepDurationMinutes)
        assertEquals(listOf("nap-pass"), july9.supplementalBlocks.map { it.segment.stableId })
        assertEquals(
            SleepStageTotals(lightMinutes = 270, deepMinutes = 102, remMinutes = 105, awakeMinutes = 15),
            july9.architectureTotals,
        )
        assertEquals(listOf("nap-short"), july9.rejectedSegments.map { it.segment.stableId })

        val july10 = result.aggregates.last()
        assertEquals(listOf("cutoff-core"), july10.coreCluster.segments.map { it.stableId })
    }

    @Test
    fun canonicalizesOverlapWithoutSelectedSourceUsingLockedFallbackOrder() {
        val durationWinner =
            coreIdFor(
                segment(
                    id = "long-low-coverage",
                    start = at(2026, 7, 8, 23, 0),
                    end = at(2026, 7, 9, 7, 1),
                    durationMinutes = 481,
                    packageName = "z.long",
                    light = 20,
                ),
                segment(
                    id = "short-rich-coverage",
                    start = at(2026, 7, 8, 23, 0),
                    end = at(2026, 7, 9, 7, 0),
                    durationMinutes = 480,
                    packageName = "a.rich",
                    light = 240,
                    deep = 120,
                    rem = 120,
                ),
            )
        assertEquals("long-low-coverage", durationWinner)

        val coverageWinner =
            coreIdFor(
                segment(
                    id = "equal-low-coverage",
                    start = at(2026, 7, 8, 23, 0),
                    end = at(2026, 7, 9, 7, 0),
                    packageName = "z.low",
                    light = 180,
                ),
                segment(
                    id = "equal-high-coverage",
                    start = at(2026, 7, 8, 23, 0),
                    end = at(2026, 7, 9, 7, 0),
                    packageName = "a.high",
                    light = 240,
                    deep = 30,
                    rem = 30,
                ),
            )
        assertEquals("equal-high-coverage", coverageWinner)

        val packageWinner =
            coreIdFor(
                segment(
                    id = "pkg-z",
                    start = at(2026, 7, 8, 23, 0),
                    end = at(2026, 7, 9, 7, 0),
                    packageName = "zzz.pkg",
                    light = 240,
                    deep = 30,
                    rem = 30,
                ),
                segment(
                    id = "pkg-a",
                    start = at(2026, 7, 8, 23, 0),
                    end = at(2026, 7, 9, 7, 0),
                    packageName = "aaa.pkg",
                    light = 240,
                    deep = 30,
                    rem = 30,
                ),
            )
        assertEquals("pkg-a", packageWinner)
    }

    @Test
    fun coreClusterTieBreaksRemainDeterministicAcrossInputPermutations() {
        val earlyOne = segment(id = "early-1", start = at(2026, 7, 9, 0, 0), end = at(2026, 7, 9, 2, 0), light = 120)
        val earlyTwo = segment(id = "early-2", start = at(2026, 7, 9, 2, 30), end = at(2026, 7, 9, 4, 30), light = 120)
        val lateOne = segment(id = "late-1", start = at(2026, 7, 9, 5, 30), end = at(2026, 7, 9, 7, 30), light = 120)
        val lateTwo = segment(id = "late-2", start = at(2026, 7, 9, 8, 0), end = at(2026, 7, 9, 10, 0), light = 120)

        val permutations =
            listOf(
                listOf(earlyOne, earlyTwo, lateOne, lateTwo),
                listOf(lateTwo, lateOne, earlyTwo, earlyOne),
                listOf(earlyTwo, lateTwo, earlyOne, lateOne),
                listOf(lateOne, earlyOne, lateTwo, earlyTwo),
            )

        permutations.forEach { order ->
            val aggregate =
                SleepDayAggregator.aggregate(
                    order,
                    policy(coreMergeGapMinutes = 45),
                ).aggregates.single()
            assertEquals(listOf("late-1", "late-2"), aggregate.coreCluster.segments.map { it.stableId })
            assertEquals(LocalDate.of(2026, 7, 9), aggregate.scoreDay)
        }
    }

    @Test
    fun usesPolicyTimezoneForDstSensitiveBoundaryWhenOffsetsAreMissing() {
        val beforeCutoff =
            segment(
                id = "dst-before-cutoff",
                start = at(2026, 3, 29, 14, 0),
                end = at(2026, 3, 29, 14, 30),
                includeOffsets = false,
                light = 30,
            )
        val atCutoff =
            segment(
                id = "dst-at-cutoff",
                start = at(2026, 3, 29, 15, 0),
                end = at(2026, 3, 29, 15, 45),
                includeOffsets = false,
                light = 45,
            )

        val result = SleepDayAggregator.aggregate(listOf(beforeCutoff, atCutoff), policy())

        assertEquals(listOf(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 3, 30)), result.aggregates.map { it.scoreDay })
        assertEquals("dst-before-cutoff", result.aggregates.first().coreCluster.stableSessionTieBreakId)
        assertEquals("dst-at-cutoff", result.aggregates.last().coreCluster.stableSessionTieBreakId)
    }

    @Test
    fun supplementalArchitectureThresholdUsesIntegerMath() {
        val core =
            segment(
                id = "core",
                start = at(2026, 7, 8, 23, 0),
                end = at(2026, 7, 9, 7, 0),
                light = 240,
                deep = 90,
                rem = 120,
                awake = 30,
            )
        val pass =
            segment(
                id = "pass-70-percent",
                start = at(2026, 7, 9, 13, 0),
                end = at(2026, 7, 9, 14, 0),
                light = 42,
            )
        val fail =
            segment(
                id = "fail-69-point-49-percent",
                start = at(2026, 7, 9, 14, 10),
                end = at(2026, 7, 9, 15, 9),
                durationMinutes = 59,
                light = 41,
            )

        val aggregate = assertNotNull(SleepDayAggregator.aggregateForScoreDay(LocalDate.of(2026, 7, 9), listOf(core, pass, fail), policy()))
        val eligibility = aggregate.supplementalBlocks.associate { it.segment.stableId to it.architectureEligible }

        assertEquals(true, eligibility["pass-70-percent"])
        assertEquals(false, eligibility["fail-69-point-49-percent"])
        assertEquals(
            SleepStageTotals(lightMinutes = 282, deepMinutes = 90, remMinutes = 120, awakeMinutes = 30),
            aggregate.architectureTotals,
        )
    }

    @Test
    fun supplementalWithoutStagesFallsBackToDurationOnly() {
        val core =
            segment(
                id = "core",
                start = at(2026, 7, 8, 23, 0),
                end = at(2026, 7, 9, 7, 0),
                light = 240,
                deep = 90,
                rem = 120,
                awake = 30,
            )
        val noStages =
            segment(
                id = "nap-no-stages",
                start = at(2026, 7, 9, 13, 0),
                end = at(2026, 7, 9, 13, 45),
            )

        val aggregate = assertNotNull(SleepDayAggregator.aggregateForScoreDay(LocalDate.of(2026, 7, 9), listOf(core, noStages), policy()))
        val supplemental = aggregate.supplementalBlocks.single()

        assertEquals(525, aggregate.totalDurationMinutes)
        assertFalse(supplemental.architectureEligible)
        assertEquals(core.stageTotals, aggregate.architectureTotals)
    }

    private fun coreIdFor(vararg segments: SleepDaySegment): String =
        assertNotNull(SleepDayAggregator.aggregate(segments.toList(), policy()).aggregates.singleOrNull()).coreCluster.stableSessionTieBreakId

    private fun policy(
        coreMergeGapMinutes: Int = 90,
        cutoffMinutesOfDay: Int = 15 * 60,
        minimumCountedSleepSegmentMinutes: Int = 30,
        supplementalArchitectureCoveragePercent: Int = 70,
    ): SleepDayPolicy =
        SleepDayPolicy(
            coreMergeGapMinutes = coreMergeGapMinutes,
            supplementalCutoffMinutesOfDay = cutoffMinutesOfDay,
            minimumCountedSleepSegmentMinutes = minimumCountedSleepSegmentMinutes,
            supplementalArchitectureCoveragePercent = supplementalArchitectureCoveragePercent,
            scoringZoneId = berlin,
        )

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): ZonedDateTime = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, berlin)

    private fun segment(
        id: String,
        start: ZonedDateTime,
        end: ZonedDateTime,
        durationMinutes: Int = Duration.between(start, end).toMinutes().toInt(),
        light: Int = 0,
        deep: Int = 0,
        rem: Int = 0,
        awake: Int = 0,
        packageName: String = id,
        includeOffsets: Boolean = true,
    ): SleepDaySegment =
        SleepDaySegment(
            stableId = id,
            startTimeMs = start.toInstant().toEpochMilli(),
            endTimeMs = end.toInstant().toEpochMilli(),
            durationMinutes = durationMinutes,
            lightSleepMinutes = light,
            deepSleepMinutes = deep,
            remSleepMinutes = rem,
            awakeMinutes = awake,
            startZoneOffsetSeconds = if (includeOffsets) start.offset.totalSeconds else null,
            endZoneOffsetSeconds = if (includeOffsets) end.offset.totalSeconds else null,
            sourcePackageName = packageName,
        )
}
