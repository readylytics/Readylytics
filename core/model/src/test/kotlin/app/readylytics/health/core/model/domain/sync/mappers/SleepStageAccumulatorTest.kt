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
    fun `efficiency is clamped to 100 percent when stage durations exceed time in bed`() {
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
        assertEquals(120, breakdown.durationMinutes)
        assertEquals(100f, breakdown.efficiency)
    }
}

