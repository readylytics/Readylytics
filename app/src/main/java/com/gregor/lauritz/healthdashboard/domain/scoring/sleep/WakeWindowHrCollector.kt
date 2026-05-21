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
            val index = ((percentile / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
            return sorted[index]
        }

        suspend fun collect(
            session: SleepSessionEntity,
            dayMidnight: Instant,
            beforeMs: Long,
            afterMs: Long,
            percentile: Int = 5,
        ): WakeHrResult {
            val baselineFrom = dayMidnight.minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS).toEpochMilli()
            val sessions = sleepSessionDao.getSince(baselineFrom)

            // Batch-fetch all HR records in one query covering all session wake windows.
            // Trade-off: loads entire range (30d + margins) rather than individual windows,
            // but single DB query + single O(R*S) pass is more efficient than N individual queries.
            val batchWindowStart = (sessions.minOfOrNull { it.endTime } ?: session.endTime) - beforeMs
            val batchWindowEnd = (sessions.maxOfOrNull { it.endTime } ?: session.endTime) + afterMs

            val allWakeHrRecords = heartRateDao.getByTimeRange(batchWindowStart, batchWindowEnd)

            val currentRestingHr =
                allWakeHrRecords
                    .filter { it.timestampMs in (session.endTime - beforeMs)..(session.endTime + afterMs) }
                    .map { it.beatsPerMinute }
                    .getPercentile(percentile)

            val historicSessions = sessions.filter { it.id != session.id }
            val historicRestingHrs =
                if (historicSessions.isEmpty()) {
                    emptyList()
                } else {
                    val sessionWindows =
                        historicSessions.map { s ->
                            s.id to
                                (s.endTime - beforeMs to s.endTime + afterMs)
                        }
                    val sessionHrValues = mutableMapOf<String, MutableList<Int>>()
                    for ((sessionId, _) in sessionWindows) {
                        sessionHrValues[sessionId] = mutableListOf()
                    }
                    // O(R * S) single-pass algorithm: iterate records once, check all windows.
                    // Preferred over nested filtering which requires O(S) separate record scans.
                    for (record in allWakeHrRecords) {
                        for ((sessionId, window) in sessionWindows) {
                            if (record.timestampMs in window.first..window.second) {
                                sessionHrValues[sessionId]!!.add(record.beatsPerMinute)
                            }
                        }
                    }
                    sessionHrValues.values.mapNotNull { hrs -> hrs.getPercentile(percentile) }
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
