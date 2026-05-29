package com.gregor.lauritz.healthdashboard.domain.scoring.sleep

import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants
import com.gregor.lauritz.healthdashboard.domain.util.median
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class WakeWindowHrCollector
    @Inject
    constructor(
        private val heartRateDao: HeartRateDao,
        private val sleepSessionDao: SleepSessionDao,
    ) {
        data class WakeHrResult(
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
        ): WakeHrResult {
            val baselineFrom = dayMidnight.minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS).toEpochMilli()
            val sessions = sleepSessionDao.getSince(baselineFrom)
            val sessionIds = (sessions.map { it.id } + session.id).distinct()

            // Fetch all sleep HR samples for all these sessions batched
            val allHrRecords = heartRateDao.getSleepHrSamplesForSessions(sessionIds)
            val samplesBySession = allHrRecords.groupBy { it.sessionId }

            fun List<com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity>?.getPercentileValue(
                percentile: Int,
            ): Int? {
                if (this == null || isEmpty()) return null
                if (size == 1) return first().beatsPerMinute
                // The records are already ordered by beatsPerMinute ASC because of getSleepHrSamplesForSessions
                val index = Math.round((percentile / 100.0) * (size - 1)).toInt().coerceIn(0, size - 1)
                return this[index].beatsPerMinute
            }

            val currentRestingHr = samplesBySession[session.id].getPercentileValue(percentile)

            val historicSessions = sessions.filter { it.id != session.id }
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

            return WakeHrResult(currentRestingHr, restingHrBaseline, restingHrRatio)
        }
    }
