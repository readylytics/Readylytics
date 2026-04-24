package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.ui.components.MetricStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val MIN_BASELINE_SESSIONS = 3
private const val NAP_THRESHOLD_MINUTES = 180

data class CircadianConsistencyResult(
    val score: Float?,
    val medianBedtimeMinutes: Int?,
    val medianWakeMinutes: Int?,
    val isCalibrating: Boolean,
    val thresholdMinutes: Int,
)

fun CircadianConsistencyResult.toStatus(): MetricStatus =
    when {
        isCalibrating || score == null -> MetricStatus.CALIBRATING
        score >= 80f -> MetricStatus.OPTIMAL
        score >= 60f -> MetricStatus.NEUTRAL
        score >= 40f -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }

fun Int.toTimeString(): String {
    val normalizedMinutes = this % 1440
    val hours = normalizedMinutes / 60
    val minutes = normalizedMinutes % 60
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
}

@Singleton
class CircadianConsistencyRepository
    @Inject
    constructor(
        private val sleepSessionDao: SleepSessionDao,
        private val prefsRepo: UserPreferencesRepository,
    ) {
        val result: Flow<CircadianConsistencyResult> = run {
            val fromMs = System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000L
            combine(
                sleepSessionDao.observeSince(fromMs),
                prefsRepo.userPreferences,
            ) { sessions, prefs -> compute(sessions, prefs) }
        }

        private fun compute(
            sessions: List<SleepSessionEntity>,
            prefs: UserPreferences,
        ): CircadianConsistencyResult {
            val threshold = prefs.consistencyThresholdMinutes
            val evalCount = prefs.consistencyEvaluationDays
            val baselineCount = prefs.consistencyBaselineDays

            val validSessions =
                sessions
                    .filter { it.durationMinutes >= NAP_THRESHOLD_MINUTES }
                    .sortedByDescending { it.endTime }

            val baselineSessions = validSessions.take(baselineCount)

            if (baselineSessions.size < MIN_BASELINE_SESSIONS) {
                return CircadianConsistencyResult(
                    score = null,
                    medianBedtimeMinutes = null,
                    medianWakeMinutes = null,
                    isCalibrating = true,
                    thresholdMinutes = threshold,
                )
            }

            val medianBed = baselineSessions.map { normalizeMinutes(it.startTime) }.median()
            val medianWake = baselineSessions.map { normalizeMinutes(it.endTime) }.median()

            val evalSessions = validSessions.take(evalCount)
            if (evalSessions.isEmpty()) {
                return CircadianConsistencyResult(
                    score = null,
                    medianBedtimeMinutes = medianBed,
                    medianWakeMinutes = medianWake,
                    isCalibrating = true,
                    thresholdMinutes = threshold,
                )
            }

            val dailyScores =
                evalSessions.map { session ->
                    val bedDev = abs(normalizeMinutes(session.startTime) - medianBed).toFloat()
                    val wakeDev = abs(normalizeMinutes(session.endTime) - medianWake).toFloat()
                    val bedScore = scoreDeviation(bedDev, threshold.toFloat())
                    val wakeScore = scoreDeviation(wakeDev, threshold.toFloat())
                    (bedScore + wakeScore) / 2f
                }

            return CircadianConsistencyResult(
                score = dailyScores.average().toFloat(),
                medianBedtimeMinutes = medianBed,
                medianWakeMinutes = medianWake,
                isCalibrating = false,
                thresholdMinutes = threshold,
            )
        }

        private fun scoreDeviation(
            deviation: Float,
            threshold: Float,
        ): Float =
            when {
                deviation <= threshold -> 100f
                deviation <= threshold + 60f -> 100f * (1f - (deviation - threshold) / 60f)
                else -> 0f
            }

        // Times before noon are treated as "past midnight" (e.g., 1:00 AM → 25:00)
        // so that sorting and median work correctly across the midnight boundary.
        private fun normalizeMinutes(epochMs: Long): Int {
            val zdt = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
            val minutes = zdt.hour * 60 + zdt.minute
            return if (minutes < 12 * 60) minutes + 1440 else minutes
        }

        private fun List<Int>.median(): Int {
            val sorted = sorted()
            val mid = size / 2
            return if (size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
        }
    }
