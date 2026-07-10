package app.readylytics.health.domain.scoring.sleep

import app.readylytics.health.domain.model.SleepSessionEntity
import app.readylytics.health.domain.persistence.SleepHrSample
import app.readylytics.health.domain.repository.ScoringHistoryRepository
import app.readylytics.health.domain.scoring.ScoringConstants
import app.readylytics.health.domain.util.median
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class SleepPercentileRhrCalculator
    @Inject
    constructor(
        private val scoringHistoryRepository: ScoringHistoryRepository,
    ) {
        data class SleepPercentileRhrResult(
            val currentRestingHr: Int?,
            val restingHrBaseline: Int?,
            val restingHrRatio: Float?,
        )

        private fun List<Int>.getPercentile(percentile: Int): Int? {
            if (isEmpty()) return null
            if (size == 1) return first()
            val sorted = sorted()
            val index = Math.round((percentile / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
            return sorted[index]
        }

        suspend fun collect(
            session: SleepSessionEntity,
            dayMidnight: Instant,
            percentile: Int = 5,
            currentSessionIds: Set<String> = setOf(session.id),
        ): SleepPercentileRhrResult {
            val baselineFrom = dayMidnight.minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS).toEpochMilli()
            val sessions =
                scoringHistoryRepository.getSleepSessionsBetween(
                    baselineFrom,
                    dayMidnight.plus(1, ChronoUnit.DAYS).toEpochMilli() - 1,
                )
            val effectiveCurrentSessionIds =
                currentSessionIds
                    .ifEmpty { setOf(session.id) }
            val sessionIds = (sessions.map { it.id } + effectiveCurrentSessionIds).distinct()

            // Fetch all sleep HR samples for all these sessions batched using a lightweight projection
            val allHrRecords = scoringHistoryRepository.getSleepHrProjectionForSessions(sessionIds)
            val samplesBySession = allHrRecords.groupBy { it.sessionId }

            fun List<SleepHrSample>?.getPercentileValue(percentile: Int): Int? {
                if (this == null || isEmpty()) return null
                if (size == 1) return first().beatsPerMinute
                // The records are already ordered by beatsPerMinute ASC because of getSleepHrProjectionForSessions
                val index = Math.round((percentile / 100.0) * (size - 1)).toInt().coerceIn(0, size - 1)
                return this[index].beatsPerMinute
            }

            val currentRestingHr =
                effectiveCurrentSessionIds
                    .sorted()
                    .flatMap { sessionId -> samplesBySession[sessionId].orEmpty() }
                    .sortedWith(compareBy(SleepHrSample::beatsPerMinute))
                    .getPercentileValue(percentile)

            val historicSessions = sessions.filterNot { it.id in effectiveCurrentSessionIds }
            val historicRestingHrs =
                historicSessions.mapNotNull { s ->
                    samplesBySession[s.id].getPercentileValue(percentile)
                }

            val restingHrBaseline =
                if (historicRestingHrs.isNotEmpty()) {
                    historicRestingHrs.median().roundToInt()
                } else {
                    null
                }

            val restingHrRatio =
                if (currentRestingHr != null && restingHrBaseline != null && restingHrBaseline > 0) {
                    currentRestingHr.toFloat() / restingHrBaseline.toFloat()
                } else {
                    null
                }

            return SleepPercentileRhrResult(currentRestingHr, restingHrBaseline, restingHrRatio)
        }
    }
