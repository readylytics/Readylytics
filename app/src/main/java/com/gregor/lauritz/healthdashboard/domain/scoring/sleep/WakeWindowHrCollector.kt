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

        suspend fun collect(
            session: SleepSessionEntity,
            dayMidnight: Instant,
            beforeMs: Long,
            afterMs: Long,
        ): WakeHrResult {
            val baselineFrom = dayMidnight.minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS).toEpochMilli()
            val sessions = sleepSessionDao.getSince(baselineFrom)

            // Batch-fetch all HR records covering every session's wake window in one query
            val batchWindowStart = (sessions.minOfOrNull { it.endTime } ?: session.endTime) - beforeMs
            val batchWindowEnd = (sessions.maxOfOrNull { it.endTime } ?: session.endTime) + afterMs

            val allWakeHrRecords = heartRateDao.getByTimeRange(batchWindowStart, batchWindowEnd)

            val currentRestingHr =
                allWakeHrRecords
                    .filter { it.timestampMs in (session.endTime - beforeMs)..(session.endTime + afterMs) }
                    .minOfOrNull { it.beatsPerMinute }

            val historicRestingHrs =
                sessions
                    .filter { it.id != session.id }
                    .mapNotNull { s ->
                        val start = s.endTime - beforeMs
                        val end = s.endTime + afterMs
                        allWakeHrRecords
                            .filter { it.timestampMs in start..end }
                            .minOfOrNull { it.beatsPerMinute }
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
