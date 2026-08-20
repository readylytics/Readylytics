package app.readylytics.health.core.scoring.domain.scoring.sleep

import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayAggregator
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepTrendDay
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepTrendDayAssembler

import java.time.LocalDate

object SleepTrendDayAssembler {
    fun assemble(
        segments: List<SleepDaySegment>,
        rangeStart: LocalDate,
        rangeDays: Int,
        policy: SleepDayPolicy,
    ): List<SleepTrendDay> {
        val aggregatesByDay =
            SleepDayAggregator
                .aggregate(segments, policy)
                .aggregates
                .associateBy { it.scoreDay }

        return (0 until rangeDays).map { offset ->
            val scoreDay = rangeStart.plusDays(offset.toLong())
            val aggregate = aggregatesByDay[scoreDay]
            if (aggregate == null) {
                SleepTrendDay(
                    dayOffset = offset,
                    scoreDay = scoreDay,
                    coreStartTimeMs = null,
                    coreEndTimeMs = null,
                    totalDurationMinutes = null,
                    naps = emptyList(),
                )
            } else {
                SleepTrendDay(
                    dayOffset = offset,
                    scoreDay = scoreDay,
                    coreStartTimeMs = aggregate.coreCluster.startTimeMs,
                    coreEndTimeMs = aggregate.coreCluster.endTimeMs,
                    totalDurationMinutes = aggregate.totalDurationMinutes,
                    naps =
                        aggregate.supplementalBlocks
                            .sortedWith(compareBy({ it.segment.startTimeMs }, { it.segment.stableId }))
                            .map { block ->
                                SleepTrendNap(
                                    startTimeMs = block.segment.startTimeMs,
                                    endTimeMs = block.segment.endTimeMs,
                                    durationMinutes = block.durationMinutes,
                                )
                            },
                )
            }
        }
    }
}
