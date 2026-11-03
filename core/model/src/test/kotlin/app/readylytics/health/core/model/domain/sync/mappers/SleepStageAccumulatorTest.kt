package app.readylytics.health.core.model.domain.sync.mappers

import app.readylytics.health.core.model.domain.model.DomainSleepSessionRecord
import app.readylytics.health.core.model.domain.model.DomainSleepStage
import app.readylytics.health.core.model.domain.model.DomainSleepStageType
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.Test

class SleepStageAccumulatorTest {

    @Test
    fun `toMinutes rounds to nearest minute using 30-second boundary`() {
        assertEquals(0, SleepStageAccumulator.toMinutes(0L))
        assertEquals(0, SleepStageAccumulator.toMinutes(29_999L))
        assertEquals(1, SleepStageAccumulator.toMinutes(30_000L))
        assertEquals(1, SleepStageAccumulator.toMinutes(59_999L))
        assertEquals(1, SleepStageAccumulator.toMinutes(60_000L))
        assertEquals(1, SleepStageAccumulator.toMinutes(89_999L))
        assertEquals(2, SleepStageAccumulator.toMinutes(90_000L))
    }

    @Test
    fun `sub-minute segments sum without truncation loss`() {
        val start = Instant.parse("2026-01-01T23:00:00Z")
        // 4 consecutive 30-second segments of deep sleep = 120 seconds = 2 minutes
        val stages = listOf(
            DomainSleepStage(start, start.plusSeconds(30), DomainSleepStageType.DEEP),
            DomainSleepStage(start.plusSeconds(30), start.plusSeconds(60), DomainSleepStageType.DEEP),
            DomainSleepStage(start.plusSeconds(60), start.plusSeconds(90), DomainSleepStageType.DEEP),
            DomainSleepStage(start.plusSeconds(90), start.plusSeconds(120), DomainSleepStageType.DEEP),
        )
        val session = DomainSleepSessionRecord(
            id = "test-sub-minute",
            startTime = start,
            endTime = start.plusSeconds(120),
            startZoneOffsetSeconds = null,
            endZoneOffsetSeconds = null,
            deviceName = "Watch",
            stages = stages,
        )

        val breakdown = SleepStageAccumulator.accumulate(session)

        assertEquals(2, breakdown.deepMinutes)
        assertEquals(0, breakdown.remMinutes)
        assertEquals(0, breakdown.lightMinutes)
        assertEquals(2, breakdown.totalSleepMinutes)
        assertEquals(2, breakdown.durationMinutes)
    }

    @Test
    fun `stage-less session falls back to timeInBedMinutes for duration`() {
        val start = Instant.parse("2026-01-01T23:00:00Z")
        val end = Instant.parse("2026-01-02T07:00:00Z")
        val session = DomainSleepSessionRecord(
            id = "test-stageless",
            startTime = start,
            endTime = end,
            startZoneOffsetSeconds = null,
            endZoneOffsetSeconds = null,
            deviceName = "Watch",
            stages = emptyList(),
        )

        val breakdown = SleepStageAccumulator.accumulate(session)

        assertEquals(480, breakdown.timeInBedMinutes)
        assertEquals(480, breakdown.durationMinutes)
        assertEquals(100f, breakdown.efficiency)
        assertEquals(0, breakdown.deepMinutes)
        assertEquals(0, breakdown.remMinutes)
        assertEquals(0, breakdown.lightMinutes)
        assertEquals(0, breakdown.awakeMinutes)
    }

    @Test
    fun `negative stage duration clamps to zero`() {
        val start = Instant.parse("2026-01-01T23:00:00Z")
        val stages = listOf(
            DomainSleepStage(start.plusSeconds(60), start, DomainSleepStageType.DEEP),
        )
        val session = DomainSleepSessionRecord(
            id = "test-negative-duration",
            startTime = start,
            endTime = start.plusSeconds(3600),
            startZoneOffsetSeconds = null,
            endZoneOffsetSeconds = null,
            deviceName = "Watch",
            stages = stages,
        )

        val breakdown = SleepStageAccumulator.accumulate(session)

        assertEquals(0, breakdown.deepMinutes)
        assertEquals(0, breakdown.totalSleepMinutes)
        assertEquals(0, breakdown.durationMinutes)
        assertEquals(0f, breakdown.efficiency)
    }

    @Test
    fun `duration and efficiency are capped when stage durations exceed time in bed`() {
        val start = Instant.parse("2026-01-01T23:00:00Z")
        val stages = listOf(
            DomainSleepStage(start, start.plusSeconds(3600), DomainSleepStageType.DEEP),
            DomainSleepStage(start, start.plusSeconds(3600), DomainSleepStageType.REM),
        )
        val session = DomainSleepSessionRecord(
            id = "test-overlapping-stages",
            startTime = start,
            endTime = start.plusSeconds(3600),
            startZoneOffsetSeconds = null,
            endZoneOffsetSeconds = null,
            deviceName = "Watch",
            stages = stages,
        )

        val breakdown = SleepStageAccumulator.accumulate(session)

        assertEquals(60, breakdown.timeInBedMinutes)
        assertEquals(120, breakdown.totalSleepMinutes)
        assertEquals(60, breakdown.durationMinutes, "duration is capped at time in bed")
        assertEquals(100f, breakdown.efficiency)
    }

    private fun session(
        vararg stages: DomainSleepStage,
        end: Instant,
        start: Instant,
    ) = DomainSleepSessionRecord(
        id = "s",
        startTime = start,
        endTime = end,
        startZoneOffsetSeconds = null,
        endZoneOffsetSeconds = null,
        deviceName = "Watch",
        stages = stages.toList(),
    )

    @Test
    fun `toMinutes clamps negative durations to zero`() {
        assertEquals(0, SleepStageAccumulator.toMinutes(-120_000L))
    }

    @Test
    fun `allocated segment minutes sum to the rounded per-type total`() {
        val start = Instant.parse("2026-01-01T23:00:00Z")
        // 100 deep segments of 90 s = 150 min total; per-segment rounding would give 200.
        val stages = (0 until 100).map { i ->
            DomainSleepStage(
                start.plusSeconds(i * 90L),
                start.plusSeconds((i + 1) * 90L),
                DomainSleepStageType.DEEP,
            )
        }
        val s = session(*stages.toTypedArray(), start = start, end = start.plusSeconds(100 * 90L))

        val minutes = SleepStageAccumulator.allocateSegmentMinutes(s)

        assertEquals(SleepStageAccumulator.accumulate(s).deepMinutes, minutes.sum())
        assertEquals(150, minutes.sum())
    }

    @Test
    fun `allocated segment minutes are tracked independently per stage type`() {
        val start = Instant.parse("2026-01-01T23:00:00Z")
        val s = session(
            DomainSleepStage(start, start.plusSeconds(90), DomainSleepStageType.DEEP),
            DomainSleepStage(start.plusSeconds(90), start.plusSeconds(180), DomainSleepStageType.REM),
            DomainSleepStage(start.plusSeconds(180), start.plusSeconds(270), DomainSleepStageType.DEEP),
            DomainSleepStage(start.plusSeconds(270), start.plusSeconds(360), DomainSleepStageType.REM),
            start = start,
            end = start.plusSeconds(360),
        )

        val minutes = SleepStageAccumulator.allocateSegmentMinutes(s)

        val breakdown = SleepStageAccumulator.accumulate(s)
        assertEquals(breakdown.deepMinutes, minutes[0] + minutes[2])
        assertEquals(breakdown.remMinutes, minutes[1] + minutes[3])
    }

    @Test
    fun `duration never exceeds time in bed`() {
        val start = Instant.parse("2026-01-01T23:00:00Z")
        // 3 x 90 s stages in a 270 s session: each rounds up (2+2+2=6) vs 4.5 -> TIB 5 min.
        val s = session(
            DomainSleepStage(start, start.plusSeconds(90), DomainSleepStageType.DEEP),
            DomainSleepStage(start.plusSeconds(90), start.plusSeconds(180), DomainSleepStageType.REM),
            DomainSleepStage(start.plusSeconds(180), start.plusSeconds(270), DomainSleepStageType.LIGHT),
            start = start,
            end = start.plusSeconds(270),
        )

        val breakdown = SleepStageAccumulator.accumulate(s)

        assertEquals(5, breakdown.timeInBedMinutes)
        assertEquals(5, breakdown.durationMinutes)
    }
}
