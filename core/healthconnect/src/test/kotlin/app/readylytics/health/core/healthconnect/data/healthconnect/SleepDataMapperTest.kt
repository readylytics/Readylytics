package app.readylytics.health.core.healthconnect.data.healthconnect

import app.readylytics.health.core.model.domain.model.DomainSleepSessionRecord
import app.readylytics.health.core.model.domain.model.DomainSleepStage
import app.readylytics.health.core.model.domain.model.DomainSleepStageType
import app.readylytics.health.core.model.domain.model.SleepStageType
import app.readylytics.health.core.model.domain.sync.mappers.SleepDataMapper
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

    @Test
    fun `mapSleepSession and mapSleepSessionStages preserve sub-minute segments via unbiased rounding`() {
        val start = Instant.parse("2026-01-01T23:00:00Z")
        val stages =
            listOf(
                DomainSleepStage(start, start.plusSeconds(45), DomainSleepStageType.LIGHT),
                DomainSleepStage(start.plusSeconds(45), start.plusSeconds(90), DomainSleepStageType.LIGHT),
            )
        val session =
            DomainSleepSessionRecord(
                id = "sub-min-mapper",
                startTime = start,
                endTime = start.plusSeconds(90),
                startZoneOffsetSeconds = null,
                endZoneOffsetSeconds = null,
                deviceName = "Watch",
                stages = stages,
            )

        val sessionInput = SleepDataMapper.mapSleepSession(session)
        val stageInputs = SleepDataMapper.mapSleepSessionStages(session)

        // 45s + 45s = 90s = 2 minutes total light sleep
        assertEquals(2, sessionInput.lightSleepMinutes)
        assertEquals(2, sessionInput.durationMinutes)
        assertEquals(2, stageInputs.size)
        // Each 45s segment rounds to 1 minute instead of truncating to 0
        assertEquals(1, stageInputs[0].durationMinutes)
        assertEquals(1, stageInputs[1].durationMinutes)
    }

    @Test
    fun `stored stage rows sum to the session stage minutes`() {
        val start = Instant.parse("2026-01-01T23:00:00Z")
        val stages =
            (0 until 40).map { i ->
                DomainSleepStage(
                    start.plusSeconds(i * 90L),
                    start.plusSeconds((i + 1) * 90L),
                    if (i % 2 == 0) DomainSleepStageType.DEEP else DomainSleepStageType.REM,
                )
            }
        val session =
            DomainSleepSessionRecord(
                id = "sum-check",
                startTime = start,
                endTime = start.plusSeconds(40 * 90L),
                startZoneOffsetSeconds = null,
                endZoneOffsetSeconds = null,
                deviceName = "Watch",
                stages = stages,
            )

        val sessionInput = SleepDataMapper.mapSleepSession(session)
        val rows = SleepDataMapper.mapSleepSessionStages(session)

        fun rowMinutes(type: SleepStageType) =
            rows.filter { it.stageType == type.value }.sumOf { it.durationMinutes }
        assertEquals(sessionInput.deepSleepMinutes, rowMinutes(SleepStageType.DEEP))
        assertEquals(sessionInput.remSleepMinutes, rowMinutes(SleepStageType.REM))
    }
}
