package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyRepository

import app.readylytics.health.core.model.domain.scoring.ScoringConstants

import app.readylytics.health.core.model.domain.circadian.CircadianThresholdDefaults
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.model.circadianConsistencyStatus
import app.readylytics.health.core.model.domain.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.model.domain.repository.SleepSessionRepository
import app.readylytics.health.core.model.domain.security.EncryptionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

sealed class CircadianConsistencyResult {
    data object Calibrating : CircadianConsistencyResult()

    data object MissingData : CircadianConsistencyResult()

    data class Ready(
        val score: Float,
        val medianBedtimeMinutes: Int,
        val medianWakeMinutes: Int,
        val thresholdMinutes: Int,
        val latestBedtimeOffsetMinutes: Int,
    ) : CircadianConsistencyResult()
}

fun CircadianConsistencyResult.toStatus(): MetricStatus =
    when (this) {
        is CircadianConsistencyResult.Calibrating -> MetricStatus.CALIBRATING
        is CircadianConsistencyResult.MissingData -> MetricStatus.NO_DATA
        is CircadianConsistencyResult.Ready -> score.circadianConsistencyStatus()
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
        private val encryptionManager: EncryptionManager,
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
                val fromMs =
                    anchorMs - ScoringConstants.CIRCADIAN_CONSISTENCY_WINDOW_DAYS.toLong() * 24 * 60 * 60 * 1000L
                sleepSessionRepository.observeSince(fromMs).map { sessions ->
                    val filtered = sessions.filter { it.endTime < anchorMs }
                    compute(filtered, prefs, anchorDate)
                }
            }

        suspend fun scoreFor(
            anchorDate: LocalDate,
            prefs: UserPreferences,
            prefetchedSessions: List<SleepSessionData>? = null,
        ): Float? {
            val result = resultForOnce(anchorDate, prefs, prefetchedSessions)
            return (result as? CircadianConsistencyResult.Ready)?.score
        }

        suspend fun resultForOnce(
            anchorDate: LocalDate,
            prefs: UserPreferences,
            prefetchedSessions: List<SleepSessionData>? = null,
        ): CircadianConsistencyResult {
            val anchorMs =
                anchorDate
                    .plusDays(1)
                    .atStartOfDay(prefs.scoringZone())
                    .toInstant()
                    .toEpochMilli()
            val fromMs =
                anchorMs - ScoringConstants.CIRCADIAN_CONSISTENCY_WINDOW_DAYS.toLong() * 24 * 60 * 60 * 1000L
            val sessions =
                prefetchedSessions?.filter { it.startTime >= fromMs && it.endTime < anchorMs }
                    ?: sleepSessionRepository.getSince(fromMs).filter { it.endTime < anchorMs }
            return compute(sessions, prefs, anchorDate)
        }

        private fun compute(
            sessions: List<SleepSessionData>,
            prefs: UserPreferences,
            anchorDate: LocalDate,
        ): CircadianConsistencyResult {
            val threshold =
                CircadianThresholdDefaults.resolveThreshold(prefs.physiologyProfile, resolveOverride(prefs))
            val evalCount = prefs.consistencyEvaluationDays
            val baselineCount = prefs.consistencyBaselineDays
            val zone = prefs.scoringZone()

            val validSessions = CircadianWakeBaseline.qualifyingSessions(sessions)

            val baseline =
                CircadianWakeBaseline.resolveFromQualifying(validSessions, baselineCount, zone)
                    ?: return CircadianConsistencyResult.Calibrating

            val startOfDayMs =
                anchorDate
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli()
            val latestSessionEndTime = validSessions.firstOrNull()?.endTime
            if (latestSessionEndTime == null || latestSessionEndTime < startOfDayMs) {
                return CircadianConsistencyResult.MissingData
            }

            val medianBed = baseline.medianBedtimeMinutes
            val medianWake = baseline.medianWakeMinutes

            val evalSessions = validSessions.take(evalCount)
            if (evalSessions.isEmpty()) {
                return CircadianConsistencyResult.Calibrating
            }

            val dailyScores =
                evalSessions.map { session ->
                    val bedMinute = CircadianTimeMath.minuteOfDay(session.startTime, zone)
                    val wakeMinute = CircadianTimeMath.minuteOfDay(session.endTime, zone)
                    val bedDev = CircadianTimeMath.circularDeviationMinutes(bedMinute, medianBed).toFloat()
                    val wakeDev = CircadianTimeMath.circularDeviationMinutes(wakeMinute, medianWake).toFloat()
                    val bedScore = scoreDeviation(bedDev, threshold.toFloat())
                    val wakeScore = scoreDeviation(wakeDev, threshold.toFloat())
                    (bedScore + wakeScore) / 2f
                }

            val firstBedMinute = CircadianTimeMath.minuteOfDay(evalSessions.first().startTime, zone)
            val latestBedtimeOffsetMinutes = CircadianTimeMath.circularSignedOffsetMinutes(firstBedMinute, medianBed)

            return CircadianConsistencyResult.Ready(
                score = dailyScores.average().toFloat(),
                medianBedtimeMinutes = medianBed,
                medianWakeMinutes = medianWake,
                thresholdMinutes = threshold,
                latestBedtimeOffsetMinutes = latestBedtimeOffsetMinutes,
            )
        }

        // Resolves the effective user override fed into [CircadianThresholdDefaults.resolveThreshold].
        // Priority: the encrypted `circadianThresholdOverride` (the live user knob), else a legacy
        // non-default flat `consistencyThresholdMinutes` (preserved so users who tuned the old slider
        // keep their intent), else null → the profile default applies.
        private fun resolveOverride(prefs: UserPreferences): Int? {
            val decrypted =
                prefs.circadianThresholdOverride?.let { encrypted ->
                    runCatching { encryptionManager.decrypt(encrypted)?.toInt() }.getOrNull()
                }
            return decrypted
                ?: prefs.consistencyThresholdMinutes.takeIf {
                    it != SettingsDefaults.CONSISTENCY_THRESHOLD_MINUTES
                }
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
    }
