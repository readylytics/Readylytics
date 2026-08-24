package app.readylytics.health.core.scoring.domain.scoring.sleep

import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayAggregator
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepTrendDayAssembler

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import org.junit.Test

class SleepTrendDayAssemblerTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun `total uses core cluster and supplemental blocks while core interval stays main sleep`() {
        val coreStart = at(2026, 8, 1, 23, 0)
        val coreEnd = at(2026, 8, 2, 7, 0)
        val firstNapStart = at(2026, 8, 2, 13, 0)
        val secondNapStart = at(2026, 8, 2, 14, 0)
        val segments =
            listOf(
                segment("core", coreStart, coreEnd),
                segment("nap-1", firstNapStart, at(2026, 8, 2, 13, 35)),
                segment("nap-2", secondNapStart, at(2026, 8, 2, 14, 30)),
            )

        val days = SleepTrendDayAssembler.assemble(segments, LocalDate.of(2026, 8, 2), 1, policy())

        assertEquals(545, days.single().totalDurationMinutes)
        assertEquals(coreStart.toInstant().toEpochMilli(), days.single().coreStartTimeMs)
        assertEquals(coreEnd.toInstant().toEpochMilli(), days.single().coreEndTimeMs)
        assertEquals(
            listOf(firstNapStart.toInstant().toEpochMilli(), secondNapStart.toInstant().toEpochMilli()),
            days.single().naps.map { it.startTimeMs },
        )
        assertEquals(listOf(35, 30), days.single().naps.map { it.durationMinutes })
    }

    @Test
    fun `emits session before visible range when aggregator assigns it to first scoring day`() {
        val segment = segment("overnight", at(2026, 7, 31, 23, 30), at(2026, 8, 1, 7, 0))

        val result = SleepTrendDayAssembler.assemble(listOf(segment), LocalDate.of(2026, 8, 1), 1, policy())

        assertEquals(1, result.size)
        assertEquals(450, result.single().totalDurationMinutes)
    }

    @Test
    fun `assigns qualifying segment at cutoff to following score day`() {
        val beforeCutoff = segment("before", at(2026, 8, 1, 13, 59), at(2026, 8, 1, 14, 29))
        val atCutoff = segment("at-cutoff", at(2026, 8, 1, 15, 0), at(2026, 8, 1, 15, 30))

        val result = SleepTrendDayAssembler.assemble(
            listOf(beforeCutoff, atCutoff),
            LocalDate.of(2026, 8, 1),
            2,
            policy(minimumCountedSleepSegmentMinutes = 30),
        )

        assertEquals(30, result[0].totalDurationMinutes)
        assertEquals(30, result[1].totalDurationMinutes)
        assertEquals(atCutoff.startTimeMs, result[1].coreStartTimeMs)
    }

    @Test
    fun `delegates overlap canonicalization to aggregator`() {
        val winner = segment("winner", at(2026, 8, 1, 23, 0), at(2026, 8, 2, 7, 0), light = 100)
        val loser = segment("loser", at(2026, 8, 1, 23, 0), at(2026, 8, 2, 7, 0), light = 10)

        val result = SleepTrendDayAssembler.assemble(listOf(loser, winner), LocalDate.of(2026, 8, 2), 1, policy())

        assertEquals(480, result.single().totalDurationMinutes)
        assertEquals(winner.startTimeMs, result.single().coreStartTimeMs)
        assertEquals(emptyList(), result.single().naps)
    }

    @Test
    fun `single daytime segment supplies selected core cluster`() {
        val nap = segment("daytime", at(2026, 8, 1, 12, 0), at(2026, 8, 1, 13, 0))

        val result = SleepTrendDayAssembler.assemble(listOf(nap), LocalDate.of(2026, 8, 1), 1, policy())

        assertEquals(nap.startTimeMs, result.single().coreStartTimeMs)
        assertEquals(nap.endTimeMs, result.single().coreEndTimeMs)
        assertEquals(60, result.single().totalDurationMinutes)
    }

    @Test
    fun `emits empty days and sorts naps by start time`() {
        val later = segment("z", at(2026, 8, 2, 13, 0), at(2026, 8, 2, 13, 30))
        val earlier = segment("b", at(2026, 8, 2, 12, 0), at(2026, 8, 2, 12, 30))
        val core = segment("core", at(2026, 8, 1, 23, 0), at(2026, 8, 2, 7, 0))

        val result = SleepTrendDayAssembler.assemble(
            listOf(later, earlier, core),
            LocalDate.of(2026, 8, 1),
            3,
            policy(),
        )

        assertEquals(
            listOf(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 3)),
            result.map { it.scoreDay },
        )
        assertEquals(emptyList(), result[0].naps)
        assertEquals(null, result[2].coreStartTimeMs)
        assertEquals(listOf(earlier.startTimeMs, later.startTimeMs), result[1].naps.map { it.startTimeMs })
    }

    @Test
    fun `uses stable id tie break for equal start segments before projection`() {
        val napB = segment("nap-b", at(2026, 8, 2, 13, 0), at(2026, 8, 2, 13, 30))
        val napA = segment("nap-a", at(2026, 8, 2, 13, 0), at(2026, 8, 2, 13, 30))
        val reversedSegments = listOf(napB, napA)

        val aggregate = SleepDayAggregator.aggregate(reversedSegments, policy()).aggregates.single()
        val result = SleepTrendDayAssembler.assemble(reversedSegments, LocalDate.of(2026, 8, 2), 1, policy())

        assertEquals("nap-a", aggregate.coreCluster.stableSessionTieBreakId)
        assertEquals(emptyList(), result.single().naps)
        assertEquals(30, result.single().totalDurationMinutes)
    }

    @Test
    fun `emits dayOffset matching list index for populated and gap days`() {
        val core = segment("core", at(2026, 8, 1, 23, 0), at(2026, 8, 2, 7, 0))

        val result = SleepTrendDayAssembler.assemble(listOf(core), LocalDate.of(2026, 8, 1), 3, policy())

        assertEquals(listOf(0, 1, 2), result.map { it.dayOffset })
        assertEquals(0, result[0].dayOffset)
        assertEquals(LocalDate.of(2026, 8, 1), result[0].scoreDay)
        assertEquals(2, result[2].dayOffset)
        assertEquals(null, result[2].coreStartTimeMs)
    }

    private fun policy(minimumCountedSleepSegmentMinutes: Int = 30): SleepDayPolicy =
        SleepDayPolicy(90, 15 * 60, minimumCountedSleepSegmentMinutes, 70, berlin)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, berlin)

    private fun segment(
        id: String,
        start: ZonedDateTime,
        end: ZonedDateTime,
        light: Int = 0,
        durationMinutes: Int = Duration.between(start, end).toMinutes().toInt(),
    ): SleepDaySegment =
        SleepDaySegment(
            stableId = id,
            startTimeMs = start.toInstant().toEpochMilli(),
            endTimeMs = end.toInstant().toEpochMilli(),
            durationMinutes = durationMinutes,
            lightSleepMinutes = light,
        )
}
