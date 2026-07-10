package app.readylytics.health.domain.scoring.sleep

import java.time.LocalDate
import java.time.ZoneId

data class SleepDayPolicy(
    val coreMergeGapMinutes: Int,
    val supplementalCutoffMinutesOfDay: Int,
    val minimumCountedSleepSegmentMinutes: Int,
    val supplementalArchitectureCoveragePercent: Int,
    val scoringZoneId: ZoneId,
) {
    init {
        require(coreMergeGapMinutes >= 0) { "coreMergeGapMinutes must be >= 0" }
        require(supplementalCutoffMinutesOfDay in 0..1439) {
            "supplementalCutoffMinutesOfDay must be in 0..1439"
        }
        require(minimumCountedSleepSegmentMinutes >= 0) {
            "minimumCountedSleepSegmentMinutes must be >= 0"
        }
        require(supplementalArchitectureCoveragePercent in 0..100) {
            "supplementalArchitectureCoveragePercent must be in 0..100"
        }
    }
}

data class SleepDaySegment(
    val stableId: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMinutes: Int,
    val lightSleepMinutes: Int = 0,
    val deepSleepMinutes: Int = 0,
    val remSleepMinutes: Int = 0,
    val awakeMinutes: Int = 0,
    val efficiency: Float? = null,
    val startZoneOffsetSeconds: Int? = null,
    val endZoneOffsetSeconds: Int? = null,
    val sourcePackageName: String? = null,
    val isFromSelectedSource: Boolean = false,
) {
    init {
        require(stableId.isNotBlank()) { "stableId must not be blank" }
        require(endTimeMs > startTimeMs) { "endTimeMs must be greater than startTimeMs" }
        require(durationMinutes > 0) { "durationMinutes must be > 0" }
        require(lightSleepMinutes >= 0) { "lightSleepMinutes must be >= 0" }
        require(deepSleepMinutes >= 0) { "deepSleepMinutes must be >= 0" }
        require(remSleepMinutes >= 0) { "remSleepMinutes must be >= 0" }
        require(awakeMinutes >= 0) { "awakeMinutes must be >= 0" }
    }

    val stageTotals: SleepStageTotals
        get() =
            SleepStageTotals(
                lightMinutes = lightSleepMinutes,
                deepMinutes = deepSleepMinutes,
                remMinutes = remSleepMinutes,
                awakeMinutes = awakeMinutes,
            )

    val trackedStageMinutes: Int
        get() = lightSleepMinutes + deepSleepMinutes + remSleepMinutes + awakeMinutes

    val hasNonAwakeStage: Boolean
        get() = (lightSleepMinutes + deepSleepMinutes + remSleepMinutes) > 0
}

data class SleepStageTotals(
    val lightMinutes: Int = 0,
    val deepMinutes: Int = 0,
    val remMinutes: Int = 0,
    val awakeMinutes: Int = 0,
) {
    init {
        require(lightMinutes >= 0) { "lightMinutes must be >= 0" }
        require(deepMinutes >= 0) { "deepMinutes must be >= 0" }
        require(remMinutes >= 0) { "remMinutes must be >= 0" }
        require(awakeMinutes >= 0) { "awakeMinutes must be >= 0" }
    }

    val trackedMinutes: Int
        get() = lightMinutes + deepMinutes + remMinutes + awakeMinutes

    operator fun plus(other: SleepStageTotals): SleepStageTotals =
        SleepStageTotals(
            lightMinutes = lightMinutes + other.lightMinutes,
            deepMinutes = deepMinutes + other.deepMinutes,
            remMinutes = remMinutes + other.remMinutes,
            awakeMinutes = awakeMinutes + other.awakeMinutes,
        )
}

data class RecoveryWindow(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val clockDurationMinutes: Int,
    val coreSleepDurationMinutes: Int,
) {
    init {
        require(endTimeMs >= startTimeMs) { "endTimeMs must be >= startTimeMs" }
        require(clockDurationMinutes >= 0) { "clockDurationMinutes must be >= 0" }
        require(coreSleepDurationMinutes >= 0) { "coreSleepDurationMinutes must be >= 0" }
    }
}

data class SleepCluster(
    val segments: List<SleepDaySegment>,
    val totalDurationMinutes: Int,
    val stageTotals: SleepStageTotals,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val stableSessionTieBreakId: String,
) {
    init {
        require(segments.isNotEmpty()) { "segments must not be empty" }
        require(endTimeMs >= startTimeMs) { "endTimeMs must be >= startTimeMs" }
        require(totalDurationMinutes >= 0) { "totalDurationMinutes must be >= 0" }
        require(stableSessionTieBreakId.isNotBlank()) { "stableSessionTieBreakId must not be blank" }
    }

    val recoveryWindow: RecoveryWindow
        get() =
            RecoveryWindow(
                startTimeMs = startTimeMs,
                endTimeMs = endTimeMs,
                clockDurationMinutes = ((endTimeMs - startTimeMs) / MILLIS_PER_MINUTE).toInt(),
                coreSleepDurationMinutes = totalDurationMinutes,
            )

    companion object {
        private const val MILLIS_PER_MINUTE = 60_000L
    }
}

data class SupplementalSleepBlock(
    val segment: SleepDaySegment,
    val architectureEligible: Boolean,
    val stageCoverageTrackedMinutes: Int,
) {
    val durationMinutes: Int
        get() = segment.durationMinutes
}

enum class RejectedSleepSegmentReason {
    BELOW_MINIMUM_DURATION,
    OVERLAP_CANONICALIZED_OUT,
}

data class RejectedSleepSegment(
    val segment: SleepDaySegment,
    val reason: RejectedSleepSegmentReason,
    val scoreDay: LocalDate,
)

data class SleepDayAggregate(
    val scoreDay: LocalDate,
    val policy: SleepDayPolicy,
    val coreCluster: SleepCluster,
    val supplementalBlocks: List<SupplementalSleepBlock>,
    val rejectedSegments: List<RejectedSleepSegment>,
    val totalDurationMinutes: Int,
    val architectureTotals: SleepStageTotals,
    val recoveryWindow: RecoveryWindow,
) {
    val supplementalSleepDurationMinutes: Int
        get() = supplementalBlocks.sumOf { it.durationMinutes }
}

data class SleepDayAggregationResult(
    val aggregates: List<SleepDayAggregate>,
    val rejectedSegments: List<RejectedSleepSegment>,
)
