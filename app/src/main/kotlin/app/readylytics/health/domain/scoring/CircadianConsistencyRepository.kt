package app.readylytics.health.domain.scoring

import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.data.preferences.scoringZone
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.repository.SleepSessionData
import app.readylytics.health.domain.repository.SleepSessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val MIN_BASELINE_SESSIONS = 3
private const val NAP_THRESHOLD_MINUTES = 180

sealed class CircadianConsistencyResult {
    data object Calibrating : CircadianConsistencyResult()

    data object MissingData : CircadianConsistencyResult()

    data class Ready(
        val score: Float,
        val medianBedtimeMinutes: Int,
        val medianWakeMinutes: Int,
        val thresholdMinutes: Int,
    ) : CircadianConsistencyResult()
}

fun CircadianConsistencyResult.toStatus(): MetricStatus =
    when (this) {
        is CircadianConsistencyResult.Calibrating -> MetricStatus.CALIBRATING
        is CircadianConsistencyResult.MissingData -> MetricStatus.NO_DATA
        is CircadianConsistencyResult.Ready ->
            when {
                score >= 80f -> MetricStatus.OPTIMAL
                score >= 60f -> MetricStatus.NEUTRAL
                score >= 40f -> MetricStatus.WARNING
                else -> MetricStatus.POOR
            }
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
        private val sleepSessionRepository: SleepSessionRepository,
        private val settingsRepo: SettingsRepository,
    ) {
        @OptIn(ExperimentalCoroutinesApi::class)
        fun resultFor(anchorDate: LocalDate): Flow<CircadianConsistencyResult> =
            settingsRepo.userPreferences.flatMapLatest { prefs ->
                val anchorMs =
                    anchorDate
                        .plusDays(1)
                        .atStartOfDay(prefs.scoringZone())
                        .toInstant()
                        .toEpochMilli()
                val fromMs = anchorMs - 60L * 24 * 60 * 60 * 1000L
                sleepSessionRepository.observeSince(fromMs).map { sessions ->
                    val filtered = sessions.filter { it.endTime < anchorMs }
                    compute(filtered, prefs, anchorDate)
                }
            }

        private fun compute(
            sessions: List<SleepSessionData>,
            prefs: UserPreferences,
            anchorDate: LocalDate,
        ): CircadianConsistencyResult {
            val threshold = prefs.consistencyThresholdMinutes
            val evalCount = prefs.consistencyEvaluationDays
            val baselineCount = prefs.consistencyBaselineDays
            val zone = prefs.scoringZone()

            val validSessions =
                sessions
                    .filter { it.durationMinutes >= NAP_THRESHOLD_MINUTES }
                    .sortedByDescending { it.endTime }

            val baselineSessions = validSessions.take(baselineCount)

            if (baselineSessions.size < MIN_BASELINE_SESSIONS) {
                return CircadianConsistencyResult.Calibrating
            }

            val startOfDayMs =
                anchorDate
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli()
            val latestSessionEndTime = validSessions.firstOrNull()?.endTime
            if (latestSessionEndTime == null || latestSessionEndTime < startOfDayMs) {
                return CircadianConsistencyResult.MissingData
            }

            val medianBed = baselineSessions.map { normalizeMinutes(it.startTime, zone) }.median()
            val medianWake = baselineSessions.map { normalizeMinutes(it.endTime, zone) }.median()

            val evalSessions = validSessions.take(evalCount)
            if (evalSessions.isEmpty()) {
                return CircadianConsistencyResult.Calibrating
            }

            val dailyScores =
                evalSessions.map { session ->
                    val bedDev = abs(normalizeMinutes(session.startTime, zone) - medianBed).toFloat()
                    val wakeDev = abs(normalizeMinutes(session.endTime, zone) - medianWake).toFloat()
                    val bedScore = scoreDeviation(bedDev, threshold.toFloat())
                    val wakeScore = scoreDeviation(wakeDev, threshold.toFloat())
                    (bedScore + wakeScore) / 2f
                }

            return CircadianConsistencyResult.Ready(
                score = dailyScores.average().toFloat(),
                medianBedtimeMinutes = medianBed,
                medianWakeMinutes = medianWake,
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
        private fun normalizeMinutes(
            epochMs: Long,
            zone: ZoneId,
        ): Int {
            val zdt = Instant.ofEpochMilli(epochMs).atZone(zone)
            val minutes = zdt.hour * 60 + zdt.minute
            return if (minutes < 12 * 60) minutes + 1440 else minutes
        }

        private fun List<Int>.median(): Int {
            val sorted = sorted()
            val mid = size / 2
            return if (size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
        }
    }
