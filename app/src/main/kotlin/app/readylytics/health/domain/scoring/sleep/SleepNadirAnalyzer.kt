package app.readylytics.health.domain.scoring.sleep

import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.domain.scoring.ScoringCalculator
import app.readylytics.health.domain.scoring.ScoringConstants
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class SleepNadirAnalyzer
    @Inject
    constructor(
        private val heartRateDao: HeartRateDao,
        private val scoringCalculator: ScoringCalculator,
    ) {
        data class NadirContext(
            val isLateNadir: Boolean,
            val isTimezoneJump: Boolean,
        )

        suspend fun analyze(
            session: SleepSessionEntity,
            historicalSessions: List<SleepSessionEntity>,
        ): NadirContext {
            val currentOffset = session.endZoneOffsetSeconds
            val previousSession = historicalSessions.maxByOrNull { it.endTime }
            val previousOffset = previousSession?.endZoneOffsetSeconds
            val isTimezoneJump =
                currentOffset != null &&
                    previousOffset != null &&
                    abs(currentOffset - previousOffset) >= ScoringConstants.TIMEZONE_JUMP_THRESHOLD_SECONDS

            val minHrTimestamp = heartRateDao.getMinHrTimestamp(session.id)
            val isLateNadirRaw =
                minHrTimestamp != null &&
                    scoringCalculator.isLateNadir(minHrTimestamp, session.startTime, session.durationMinutes)
            val isLateNadir = isLateNadirRaw && !isTimezoneJump

            return NadirContext(isLateNadir, isTimezoneJump)
        }
    }
