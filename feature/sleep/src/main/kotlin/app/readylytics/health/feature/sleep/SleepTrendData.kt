package app.readylytics.health.feature.sleep

import app.readylytics.health.core.model.data.preferences.scoringZone
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayPolicy
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDaySegment
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepTrendDay
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepTrendDayAssembler
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.PeriodAverageSummary
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.common.aggregateByRange
import app.readylytics.health.core.ui.common.padBucketsToRange
import java.time.LocalDate
import java.time.ZoneId

internal data class SleepScoringPrefs(
    val scoringZoneId: ZoneId,
    val coreMergeGapMinutes: Int,
    val supplementalCutoffMinutesOfDay: Int,
    val minimumCountedSleepSegmentMinutes: Int,
    val supplementalArchitectureCoveragePercent: Int,
    val goalSleepHours: Float,
)

internal fun UserPreferences.toSleepScoringPrefs() =
    SleepScoringPrefs(
        scoringZoneId = scoringZone(),
        coreMergeGapMinutes = coreMergeGapMinutes,
        supplementalCutoffMinutesOfDay = supplementalCutoffMinutesOfDay,
        minimumCountedSleepSegmentMinutes = minimumCountedSleepSegmentMinutes,
        supplementalArchitectureCoveragePercent = supplementalArchitectureCoveragePercent,
        goalSleepHours = goalSleepHours,
    )

internal data class SleepTrendData(
    val startOffsetPoints: List<DailyDataPoint>,
    val durationSpanPoints: List<DailyDataPoint>,
    val actualDurationPoints: List<DailyDataPoint>,
    val trendDays: List<SleepTrendDay>,
    val startOffsetSummary: PeriodAverageSummary? = null,
    val durationSpanSummary: PeriodAverageSummary? = null,
    val actualDurationSummary: PeriodAverageSummary? = null,
)

internal fun SleepSessionData.toSleepDaySegment(): SleepDaySegment {
    val normalizedDurationMinutes =
        if (durationMinutes > 0) {
            durationMinutes
        } else {
            ((endTime - startTime) / 60_000L).toInt()
        }
    return SleepDaySegment(
        stableId = id,
        startTimeMs = startTime,
        endTimeMs = endTime,
        durationMinutes = normalizedDurationMinutes,
        lightSleepMinutes = lightSleepMinutes,
        deepSleepMinutes = deepSleepMinutes,
        remSleepMinutes = remSleepMinutes,
        awakeMinutes = awakeMinutes,
        efficiency = efficiency,
        startZoneOffsetSeconds = startZoneOffsetSeconds,
        endZoneOffsetSeconds = endZoneOffsetSeconds,
        sourcePackageName = deviceName,
    )
}

internal fun buildSleepTrendData(
    sessions: List<SleepSessionData>,
    range: TimeRange,
    rangeStart: LocalDate,
    prefs: SleepScoringPrefs,
): SleepTrendData {
    val scoringZoneId = prefs.scoringZoneId
    val policy =
        SleepDayPolicy(
            coreMergeGapMinutes = prefs.coreMergeGapMinutes,
            supplementalCutoffMinutesOfDay = prefs.supplementalCutoffMinutesOfDay,
            minimumCountedSleepSegmentMinutes = prefs.minimumCountedSleepSegmentMinutes,
            supplementalArchitectureCoveragePercent = prefs.supplementalArchitectureCoveragePercent,
            scoringZoneId = scoringZoneId,
        )
    val trendDays =
        SleepTrendDayAssembler.assemble(
            segments = sessions.map(SleepSessionData::toSleepDaySegment),
            rangeStart = rangeStart,
            rangeDays = range.days,
            policy = policy,
        )

    val (startPoints, spanPoints, actualPoints) = assembleTrendPoints(trendDays, scoringZoneId)
    val trendEndDate = rangeStart.plusDays(range.days.toLong() - 1)

    val (paddedStart, startSummary) =
        aggregateAndPadPoints(startPoints, range, rangeStart, trendEndDate)
    val (paddedSpan, spanSummary) =
        aggregateAndPadPoints(spanPoints, range, rangeStart, trendEndDate)
    val (paddedDuration, durationSummary) =
        aggregateAndPadPoints(actualPoints, range, rangeStart, trendEndDate)

    return SleepTrendData(
        startOffsetPoints = paddedStart,
        durationSpanPoints = paddedSpan,
        actualDurationPoints = paddedDuration,
        trendDays = trendDays,
        startOffsetSummary = startSummary,
        durationSpanSummary = spanSummary,
        actualDurationSummary = durationSummary,
    )
}

private fun assembleTrendPoints(
    trendDays: List<SleepTrendDay>,
    scoringZoneId: ZoneId,
): Triple<List<DailyDataPoint>, List<DailyDataPoint>, List<DailyDataPoint>> {
    val startOffsetPoints = mutableListOf<DailyDataPoint>()
    val durationSpanPoints = mutableListOf<DailyDataPoint>()
    val actualDurationPoints = mutableListOf<DailyDataPoint>()

    trendDays.forEachIndexed { dayOffset, trendDay ->
        val coreStartTimeMs = trendDay.coreStartTimeMs
        val coreEndTimeMs = trendDay.coreEndTimeMs

        if (coreStartTimeMs != null && coreEndTimeMs != null) {
            val baselineMs =
                trendDay.scoreDay
                    .minusDays(1)
                    .atTime(12, 0)
                    .atZone(scoringZoneId)
                    .toInstant()
                    .toEpochMilli()
            val startOffset = (coreStartTimeMs - baselineMs) / 3_600_000f
            val endOffset = (coreEndTimeMs - baselineMs) / 3_600_000f
            val span = endOffset - startOffset
            val actualDuration = trendDay.totalDurationMinutes!! / 60f

            startOffsetPoints.add(DailyDataPoint(dayOffset, startOffset))
            durationSpanPoints.add(DailyDataPoint(dayOffset, span))
            actualDurationPoints.add(DailyDataPoint(dayOffset, actualDuration))
        } else {
            startOffsetPoints.add(DailyDataPoint(dayOffset, null))
            durationSpanPoints.add(DailyDataPoint(dayOffset, null))
            actualDurationPoints.add(DailyDataPoint(dayOffset, null))
        }
    }
    return Triple(startOffsetPoints, durationSpanPoints, actualDurationPoints)
}

private fun aggregateAndPadPoints(
    points: List<DailyDataPoint>,
    range: TimeRange,
    rangeStart: LocalDate,
    trendEndDate: LocalDate,
): Pair<List<DailyDataPoint>, PeriodAverageSummary?> {
    val (bucketed, summary) =
        points.aggregateByRange(
            range.granularity,
            rangeStart,
            trendEndDate,
            range.days,
            valueDecimalPlaces = 1,
        )
    val padded =
        bucketed.padBucketsToRange(
            range.granularity,
            rangeStart,
            trendEndDate,
        )
    return Pair(padded, summary)
}
