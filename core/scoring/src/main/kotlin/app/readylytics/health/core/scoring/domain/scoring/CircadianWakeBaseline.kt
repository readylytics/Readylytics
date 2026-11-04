package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.repository.SleepSessionData
import java.time.ZoneId

/**
 * Shared baseline-selection and median routine behind the user's "usual" bedtime and wake time.
 *
 * Extracted from [CircadianConsistencyRepository] so the circadian consistency score and the
 * morning workout-recommendation anchor derive the same habitual clock times from the same rules:
 * naps shorter than [NAP_THRESHOLD_MINUTES] never count, the most recent `baselineCount` qualifying
 * sessions form the baseline, and fewer than [MIN_BASELINE_SESSIONS] of them means *no* baseline
 * rather than a substituted default.
 *
 * Callers control the history that is offered. [CircadianConsistencyRepository] passes everything
 * before its own anchor; the recommendation anchor passes only sessions ending before the target
 * date starts, so the day being scored can never define its own "usual" wake time.
 */
object CircadianWakeBaseline {
    /** A baseline needs at least this many qualifying sessions to be meaningful. */
    const val MIN_BASELINE_SESSIONS = 3

    /** Sessions shorter than this are naps, not nights, and never contribute to the baseline. */
    const val NAP_THRESHOLD_MINUTES = 180

    /**
     * Habitual bedtime and wake time as clock minutes in `[0, 1440)`, both produced by
     * [CircadianTimeMath.circularMedianMinutes] so they are correct across midnight and noon.
     */
    data class Baseline(
        val medianBedtimeMinutes: Int,
        val medianWakeMinutes: Int,
    )

    /**
     * Nights eligible for the baseline, most recent first. Naps are dropped; the ordering is what
     * makes "the most recent N sessions" well defined.
     */
    fun qualifyingSessions(sessions: List<SleepSessionData>): List<SleepSessionData> =
        sessions
            .filter { it.durationMinutes >= NAP_THRESHOLD_MINUTES }
            .sortedByDescending { it.endTime }

    /**
     * Resolves the habitual bed/wake times from the most recent [baselineCount] qualifying sessions
     * in [sessions], or null when fewer than [MIN_BASELINE_SESSIONS] of them exist.
     */
    fun resolve(
        sessions: List<SleepSessionData>,
        baselineCount: Int,
        zone: ZoneId,
    ): Baseline? = resolveFromQualifying(qualifyingSessions(sessions), baselineCount, zone)

    /**
     * [resolve] for callers that already hold the [qualifyingSessions] list and would otherwise
     * filter and sort it twice.
     */
    fun resolveFromQualifying(
        qualifyingSessions: List<SleepSessionData>,
        baselineCount: Int,
        zone: ZoneId,
    ): Baseline? {
        val baselineSessions = qualifyingSessions.take(baselineCount)
        if (baselineSessions.size < MIN_BASELINE_SESSIONS) return null
        return Baseline(
            medianBedtimeMinutes =
                CircadianTimeMath.circularMedianMinutes(
                    baselineSessions.map { CircadianTimeMath.minuteOfDay(it.startTime, zone) },
                ),
            medianWakeMinutes =
                CircadianTimeMath.circularMedianMinutes(
                    baselineSessions.map { CircadianTimeMath.minuteOfDay(it.endTime, zone) },
                ),
        )
    }
}
