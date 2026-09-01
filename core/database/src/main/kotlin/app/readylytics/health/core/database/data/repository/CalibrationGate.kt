package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.model.domain.model.SleepSession
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer

class CalibrationGate(
    private val baselineComputer: BaselineComputer,
) {
    suspend fun isCalibrated(
        context: ScoringDayContext,
        prefetchedSessions: List<SleepSession>?,
        hasSession: Boolean,
    ): Boolean =
        context.dailySummary?.baselineCalculatedAtDate != null ||
            baselineComputer
                .computeHrvWindowsBetween(
                    fromMs = context.dayMidnightMs,
                    toMs = context.nextDayMidnightMs,
                    zoneId = context.zoneId,
                    sleepDayPolicy = context.sleepDayPolicy,
                    prefetchedSessions = prefetchedSessions,
                )?.validHistoricalDayCount
                ?.plus(if (hasSession) 1 else 0)
                ?.let { it >= ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION }
                ?: false
}
