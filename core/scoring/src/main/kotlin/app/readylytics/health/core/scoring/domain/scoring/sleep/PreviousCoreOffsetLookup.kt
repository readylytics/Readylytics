package app.readylytics.health.core.scoring.domain.scoring.sleep

import java.time.LocalDate

/**
 * WP-14/C4 (fix round 1): the nearest prior canonical core cluster's end-of-window travel offset.
 *
 * Scoped to [SleepDayAggregate.coreCluster] only -- never [SleepDayAggregate.supplementalBlocks] --
 * across aggregates whose [SleepDayAggregate.scoreDay] is strictly before [currentScoreDay] and whose
 * core cluster ends at/before [currentCoreStartTimeMs]. Ties are broken by the cluster's own
 * [SleepCluster.stableSessionTieBreakId], matching [SleepDayAggregator]'s own deterministic
 * ordering, so a same-day-or-later nap can never be mistaken for the previous night's core.
 *
 * Shared by the daily pipeline (`ReadinessSummaryCoordinator.resolveSleepAggregation`, which already
 * has a full-window [SleepDayAggregate] for "current" and passes its `scoreDay`/`coreCluster.startTimeMs`
 * straight through) and the morning-anchored recommendation path (`MorningRecoveryLoader`, which has no
 * pre-existing [SleepDayAggregate] of its own and aggregates its wake-time-bounded session history
 * purely to classify which of those sessions are canonical cores versus naps before calling this).
 *
 * Deliberately independent of `ComputeSleepMetricsUseCase`'s frozen-baseline gate, which on a frozen
 * replay short-circuits its own HRV/RHR history window to empty -- neither caller sources
 * [aggregates] from that gate, so travel-day (timezone-jump) suppression keeps working on frozen
 * days too.
 */
fun findPreviousCoreEndZoneOffsetSeconds(
    aggregates: List<SleepDayAggregate>,
    currentScoreDay: LocalDate,
    currentCoreStartTimeMs: Long,
): Int? {
    val previousCore =
        aggregates
            .asSequence()
            .filter { it.scoreDay < currentScoreDay }
            .map { it.coreCluster }
            .filter { it.endTimeMs <= currentCoreStartTimeMs }
            .maxWithOrNull(compareBy({ it.endTimeMs }, { it.stableSessionTieBreakId }))
            ?: return null
    return latestCoreSegmentEndOffset(previousCore)
}

private fun latestCoreSegmentEndOffset(cluster: SleepCluster): Int? =
    cluster.segments
        .sortedWith(compareBy({ it.endTimeMs }, { it.stableId }))
        .lastOrNull()
        ?.endZoneOffsetSeconds
