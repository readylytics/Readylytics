package app.readylytics.health.data.healthconnect

import app.readylytics.health.domain.model.DomainSleepSessionRecord
import app.readylytics.health.domain.model.DomainSleepStage
import app.readylytics.health.domain.model.DomainSleepStageType
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals

class SleepDataMapperTest {
    @Test
    fun `stage-less session falls back to the raw session span for duration`() {
        // HC-006/OD-2: a stage-less HC sleep session (no per-stage breakdown at all) previously
        // mapped to durationMinutes = 0, which throws building SleepDaySegment downstream.
        val start = Instant.parse("2026-01-01T23:00:00Z")
        val end = Instant.parse("2026-01-02T07:00:00Z")
        val session =
            DomainSleepSessionRecord(
                id = "stageless",
                startTime = start,
                endTime = end,
                startZoneOffsetSeconds = null,
                endZoneOffsetSeconds = null,
                deviceName = "TestDevice",
                stages = emptyList(),
            )

        val entity = SleepDataMapper.mapSleepSession(session)

        assertEquals(480, entity.durationMinutes, "Falls back to the raw 8h session span")
        assertEquals(100f, entity.efficiency, "Efficiency is 100% of the fallback span, not 0%")
        assertEquals(0, entity.deepSleepMinutes)
        assertEquals(0, entity.remSleepMinutes)
        assertEquals(0, entity.lightSleepMinutes)
        assertEquals(0, entity.awakeMinutes)
    }

    @Test
    fun `session with real stages is unaffected by the stage-less fallback`() {
        val start = Instant.parse("2026-01-01T23:00:00Z")
        val end = Instant.parse("2026-01-02T07:00:00Z")
        val deepStart = start
        val deepEnd = start.plusSeconds(90 * 60L)
        val remStart = deepEnd
        val remEnd = remStart.plusSeconds(90 * 60L)
        val lightStart = remEnd
        val lightEnd = end

        val session =
            DomainSleepSessionRecord(
                id = "normal",
                startTime = start,
                endTime = end,
                startZoneOffsetSeconds = null,
                endZoneOffsetSeconds = null,
                deviceName = "TestDevice",
                stages =
                    listOf(
                        DomainSleepStage(deepStart, deepEnd, DomainSleepStageType.DEEP),
                        DomainSleepStage(remStart, remEnd, DomainSleepStageType.REM),
                        DomainSleepStage(lightStart, lightEnd, DomainSleepStageType.LIGHT),
                    ),
            )

        val entity = SleepDataMapper.mapSleepSession(session)

        assertEquals(90, entity.deepSleepMinutes)
        assertEquals(90, entity.remSleepMinutes)
        assertEquals(300, entity.lightSleepMinutes)
        assertEquals(480, entity.durationMinutes, "Duration is the sum of real stage minutes, same as before")
        assertEquals(100f, entity.efficiency)
    }
}
