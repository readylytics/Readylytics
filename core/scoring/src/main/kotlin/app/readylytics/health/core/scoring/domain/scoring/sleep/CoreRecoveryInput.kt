package app.readylytics.health.core.scoring.domain.scoring.sleep

/**
 * WP-14/C4: the narrow evidence recovery-timing/fragmentation scoring is allowed to see.
 *
 * [SleepDayAggregate] intentionally mixes two different notions of "this night's sleep":
 * [SleepDayAggregate.totalDurationMinutes]/[SleepDayAggregate.architectureTotals] are nap-inclusive
 * (a supplemental nap correctly adds to duration/architecture scoring), while
 * [SleepDayAggregate.coreCluster]/[SleepDayAggregate.recoveryWindow] describe only the core
 * (overnight) sleep. Nadir-timing and fragmentation must derive exclusively from the latter -- this
 * type exists so that call path is impossible to get wrong: it owns only core session IDs, the core
 * recovery window, and the offset evidence ([endZoneOffsetSeconds]/[previousCoreEndZoneOffsetSeconds])
 * needed for timezone-jump suppression, and carries no nap-inclusive field at all.
 */
data class CoreRecoveryInput(
    val sessionIds: Set<String>,
    val window: RecoveryWindow,
    val endZoneOffsetSeconds: Int?,
    val previousCoreEndZoneOffsetSeconds: Int?,
) {
    companion object {
        /**
         * Builds strictly from [SleepDayAggregate.coreCluster]/[SleepDayAggregate.recoveryWindow] --
         * never from the synthetic, nap-inclusive total-duration session -- so adding or removing a
         * nap can move [SleepDayAggregate.totalDurationMinutes] without ever moving what this class
         * exposes. [previousCoreEndZoneOffsetSeconds] must already be resolved by the caller from the
         * nearest PRIOR canonical core (never a nap); this factory does not search for it.
         */
        fun from(
            aggregate: SleepDayAggregate,
            previousCoreEndZoneOffsetSeconds: Int?,
        ): CoreRecoveryInput {
            val segments = aggregate.coreCluster.segments
            return CoreRecoveryInput(
                sessionIds = segments.map { it.stableId }.toSet(),
                window = aggregate.recoveryWindow,
                endZoneOffsetSeconds = latestSegmentEndOffset(segments),
                previousCoreEndZoneOffsetSeconds = previousCoreEndZoneOffsetSeconds,
            )
        }

        /**
         * Degenerate single-session core for callers that have not run [SleepDayAggregator] --
         * the raw-session fallback when no [SleepDayAggregate] is available, and morning-anchored
         * callers that already operate on a single, wake-time-bounded session.
         */
        fun fromSingleSession(
            sessionId: String,
            startTimeMs: Long,
            endTimeMs: Long,
            coreSleepDurationMinutes: Int,
            endZoneOffsetSeconds: Int?,
            previousCoreEndZoneOffsetSeconds: Int?,
        ): CoreRecoveryInput =
            CoreRecoveryInput(
                sessionIds = setOf(sessionId),
                window =
                    RecoveryWindow(
                        startTimeMs = startTimeMs,
                        endTimeMs = endTimeMs,
                        clockDurationMinutes = ((endTimeMs - startTimeMs) / MILLIS_PER_MINUTE).toInt(),
                        coreSleepDurationMinutes = coreSleepDurationMinutes,
                    ),
                endZoneOffsetSeconds = endZoneOffsetSeconds,
                previousCoreEndZoneOffsetSeconds = previousCoreEndZoneOffsetSeconds,
            )

        /**
         * The core cluster's own end-of-window offset: the chronologically-last segment's
         * [SleepDaySegment.endZoneOffsetSeconds], tie-broken by [SleepDaySegment.stableId] -- the
         * same (endTime, stableId) ordering [SleepDayAggregator] itself sorts segments by.
         */
        private fun latestSegmentEndOffset(segments: List<SleepDaySegment>): Int? =
            segments
                .sortedWith(compareBy({ it.endTimeMs }, { it.stableId }))
                .lastOrNull()
                ?.endZoneOffsetSeconds

        private const val MILLIS_PER_MINUTE = 60_000L
    }
}
