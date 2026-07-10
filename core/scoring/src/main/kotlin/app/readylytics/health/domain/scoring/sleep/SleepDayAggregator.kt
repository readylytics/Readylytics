package app.readylytics.health.domain.scoring.sleep

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

object SleepDayAggregator {
    private const val MILLIS_PER_MINUTE = 60_000L

    fun aggregate(
        segments: List<SleepDaySegment>,
        policy: SleepDayPolicy,
    ): SleepDayAggregationResult {
        if (segments.isEmpty()) {
            return SleepDayAggregationResult(aggregates = emptyList(), rejectedSegments = emptyList())
        }

        val canonicalized = canonicalizeOverlaps(segments, policy)
        val groupedByDay = linkedMapOf<LocalDate, MutableList<SleepDaySegment>>()
        val rejectedByDay = linkedMapOf<LocalDate, MutableList<RejectedSleepSegment>>()

        canonicalized.rejectedSegments.forEach { rejected ->
            rejectedByDay.getOrPut(rejected.scoreDay) { mutableListOf() }.add(rejected)
        }

        canonicalized.segments.forEach { segment ->
            val scoreDay = scoreDayFor(segment, policy)
            if (segment.durationMinutes < policy.minimumCountedSleepSegmentMinutes) {
                val rejected =
                    RejectedSleepSegment(
                        segment = segment,
                        reason = RejectedSleepSegmentReason.BELOW_MINIMUM_DURATION,
                        scoreDay = scoreDay,
                    )
                rejectedByDay.getOrPut(scoreDay) { mutableListOf() }.add(rejected)
            } else {
                groupedByDay.getOrPut(scoreDay) { mutableListOf() }.add(segment)
            }
        }

        val aggregates =
            groupedByDay
                .mapNotNull { (scoreDay, daySegments) ->
                    buildAggregate(
                        scoreDay = scoreDay,
                        daySegments = daySegments,
                        rejectedSegments = rejectedByDay[scoreDay].orEmpty(),
                        policy = policy,
                    )
                }.sortedBy { it.scoreDay }

        val allRejected =
            rejectedByDay.values
                .flatten()
                .sortedWith(compareBy(RejectedSleepSegment::scoreDay, { it.segment.startTimeMs }, { it.segment.stableId }))

        return SleepDayAggregationResult(
            aggregates = aggregates,
            rejectedSegments = allRejected,
        )
    }

    fun aggregateForScoreDay(
        scoreDay: LocalDate,
        segments: List<SleepDaySegment>,
        policy: SleepDayPolicy,
    ): SleepDayAggregate? = aggregate(segments, policy).aggregates.firstOrNull { it.scoreDay == scoreDay }

    private fun buildAggregate(
        scoreDay: LocalDate,
        daySegments: List<SleepDaySegment>,
        rejectedSegments: List<RejectedSleepSegment>,
        policy: SleepDayPolicy,
    ): SleepDayAggregate? {
        if (daySegments.isEmpty()) return null

        val sortedSegments =
            daySegments.sortedWith(
                compareBy<SleepDaySegment>({ it.startTimeMs }, { it.endTimeMs }, { it.stableId }),
            )
        val clusters = buildClusters(sortedSegments, policy)
        val coreCluster = selectCoreCluster(clusters)
        val coreIds = coreCluster.segments.map { it.stableId }.toSet()
        val supplementalBlocks =
            sortedSegments
                .filterNot { it.stableId in coreIds }
                .map { segment ->
                    SupplementalSleepBlock(
                        segment = segment,
                        architectureEligible = isSupplementalArchitectureEligible(segment, policy),
                        stageCoverageTrackedMinutes = segment.trackedStageMinutes,
                    )
                }

        val architectureTotals =
            supplementalBlocks
                .filter { it.architectureEligible }
                .fold(coreCluster.stageTotals) { totals, block -> totals + block.segment.stageTotals }

        return SleepDayAggregate(
            scoreDay = scoreDay,
            policy = policy,
            coreCluster = coreCluster,
            supplementalBlocks = supplementalBlocks,
            rejectedSegments = rejectedSegments.sortedBy { it.segment.startTimeMs },
            totalDurationMinutes = coreCluster.totalDurationMinutes + supplementalBlocks.sumOf { it.durationMinutes },
            architectureTotals = architectureTotals,
            recoveryWindow = coreCluster.recoveryWindow,
        )
    }

    private fun buildClusters(
        segments: List<SleepDaySegment>,
        policy: SleepDayPolicy,
    ): List<SleepCluster> {
        if (segments.isEmpty()) return emptyList()

        val clusters = mutableListOf<MutableList<SleepDaySegment>>()
        var currentCluster = mutableListOf(segments.first())

        for (segment in segments.drop(1)) {
            val previous = currentCluster.last()
            val gapMs = segment.startTimeMs - previous.endTimeMs
            if (gapMs <= policy.coreMergeGapMinutes.toLong() * MILLIS_PER_MINUTE) {
                currentCluster.add(segment)
            } else {
                clusters.add(currentCluster)
                currentCluster = mutableListOf(segment)
            }
        }
        clusters.add(currentCluster)

        return clusters.map(::toCluster)
    }

    private fun toCluster(segments: List<SleepDaySegment>): SleepCluster =
        SleepCluster(
            segments = segments,
            totalDurationMinutes = segments.sumOf { it.durationMinutes },
            stageTotals = segments.fold(SleepStageTotals()) { totals, segment -> totals + segment.stageTotals },
            startTimeMs = segments.minOf { it.startTimeMs },
            endTimeMs = segments.maxOf { it.endTimeMs },
            stableSessionTieBreakId = segments.minOf { it.stableId },
        )

    private fun selectCoreCluster(clusters: List<SleepCluster>): SleepCluster =
        clusters.maxWithOrNull(::compareCoreClusters)
            ?: error("selectCoreCluster requires at least one cluster")

    private fun canonicalizeOverlaps(
        segments: List<SleepDaySegment>,
        policy: SleepDayPolicy,
    ): CanonicalizedSegments {
        val sorted =
            segments.sortedWith(
                compareBy<SleepDaySegment>({ it.startTimeMs }, { it.endTimeMs }, { it.stableId }),
            )
        if (sorted.isEmpty()) {
            return CanonicalizedSegments(segments = emptyList(), rejectedSegments = emptyList())
        }

        val kept = mutableListOf<SleepDaySegment>()
        val rejected = mutableListOf<RejectedSleepSegment>()
        var overlapGroup = mutableListOf(sorted.first())
        var currentMaxEnd = sorted.first().endTimeMs

        fun flushGroup() {
            val winner = selectCanonicalSegment(overlapGroup)
            kept.add(winner)
            overlapGroup
                .filterNot { it.stableId == winner.stableId }
                .forEach { loser ->
                    rejected.add(
                        RejectedSleepSegment(
                            segment = loser,
                            reason = RejectedSleepSegmentReason.OVERLAP_CANONICALIZED_OUT,
                            scoreDay = scoreDayFor(loser, policy),
                        ),
                    )
                }
        }

        for (segment in sorted.drop(1)) {
            if (segment.startTimeMs < currentMaxEnd) {
                overlapGroup.add(segment)
                currentMaxEnd = maxOf(currentMaxEnd, segment.endTimeMs)
            } else {
                flushGroup()
                overlapGroup = mutableListOf(segment)
                currentMaxEnd = segment.endTimeMs
            }
        }
        flushGroup()

        return CanonicalizedSegments(
            segments = kept,
            rejectedSegments = rejected,
        )
    }

    private fun selectCanonicalSegment(group: List<SleepDaySegment>): SleepDaySegment {
        val preferred = group.filter { it.isFromSelectedSource }
        val candidates = if (preferred.isNotEmpty()) preferred else group
        return candidates.maxWithOrNull(::compareCanonicalSegments)
            ?: error("selectCanonicalSegment requires a non-empty group")
    }

    private fun compareCoverage(
        left: SleepDaySegment,
        right: SleepDaySegment,
    ): Int {
        val leftScore = left.trackedStageMinutes.toLong() * right.durationMinutes.toLong()
        val rightScore = right.trackedStageMinutes.toLong() * left.durationMinutes.toLong()
        return compareValues(leftScore, rightScore)
    }

    private fun scoreDayFor(
        segment: SleepDaySegment,
        policy: SleepDayPolicy,
    ): LocalDate {
        val localStart = localDateTimeForStart(segment, policy.scoringZoneId)
        val startMinutesOfDay = localStart.hour * 60 + localStart.minute
        return if (startMinutesOfDay < policy.supplementalCutoffMinutesOfDay) {
            localStart.toLocalDate()
        } else {
            localStart.toLocalDate().plusDays(1)
        }
    }

    private fun isSupplementalArchitectureEligible(
        segment: SleepDaySegment,
        policy: SleepDayPolicy,
    ): Boolean {
        if (!segment.hasNonAwakeStage) return false
        return segment.trackedStageMinutes.toLong() * 100L >=
            segment.durationMinutes.toLong() * policy.supplementalArchitectureCoveragePercent.toLong()
    }

    private fun localDateTimeForStart(
        segment: SleepDaySegment,
        scoringZoneId: ZoneId,
    ): LocalDateTime {
        val instant = Instant.ofEpochMilli(segment.startTimeMs)
        val offset = segment.startZoneOffsetSeconds ?: segment.endZoneOffsetSeconds
        return if (offset != null) {
            LocalDateTime.ofInstant(instant, ZoneOffset.ofTotalSeconds(offset))
        } else {
            LocalDateTime.ofInstant(instant, scoringZoneId)
        }
    }

    private fun compareCoreClusters(
        left: SleepCluster,
        right: SleepCluster,
    ): Int =
        compareValues(left.totalDurationMinutes, right.totalDurationMinutes).takeIf { it != 0 }
            ?: compareValues(left.endTimeMs, right.endTimeMs).takeIf { it != 0 }
            ?: compareValues(right.startTimeMs, left.startTimeMs).takeIf { it != 0 }
            ?: compareLexicographicallySmaller(left.stableSessionTieBreakId, right.stableSessionTieBreakId)

    private fun compareCanonicalSegments(
        left: SleepDaySegment,
        right: SleepDaySegment,
    ): Int =
        compareValues(left.durationMinutes, right.durationMinutes).takeIf { it != 0 }
            ?: compareCoverage(left, right).takeIf { it != 0 }
            ?: compareLexicographicallySmaller(left.sourcePackageName.orEmpty(), right.sourcePackageName.orEmpty()).takeIf { it != 0 }
            ?: compareLexicographicallySmaller(left.stableId, right.stableId)

    private fun compareLexicographicallySmaller(
        left: String,
        right: String,
    ): Int =
        when {
            left == right -> 0
            left < right -> 1
            else -> -1
        }

    private data class CanonicalizedSegments(
        val segments: List<SleepDaySegment>,
        val rejectedSegments: List<RejectedSleepSegment>,
    )
}
