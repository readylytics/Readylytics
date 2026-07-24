package app.readylytics.health.domain.scoring.sleep

import app.readylytics.health.domain.model.SleepSession
import app.readylytics.health.domain.scoring.ScoringCalculator
import app.readylytics.health.domain.scoring.ScoringConstants
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class SleepNadirAnalyzer
    @Inject
    constructor(
        private val scoringCalculator: ScoringCalculator,
    ) {
        data class NadirContext(
            val isLateNadir: Boolean,
            val isTimezoneJump: Boolean,
        )

        suspend fun analyze(
            session: SleepSession,
            historicalSessions: List<SleepSession>,
            minHrTimestamp: Long?,
        ): NadirContext {
            val currentOffset = session.endZoneOffsetSeconds
            val previousSession = historicalSessions.maxByOrNull { it.endTime }
            val previousOffset = previousSession?.endZoneOffsetSeconds
            val isTimezoneJump =
                currentOffset != null &&
                    previousOffset != null &&
                    abs(currentOffset - previousOffset) >= ScoringConstants.TIMEZONE_JUMP_THRESHOLD_SECONDS

            val isLateNadirRaw =
                minHrTimestamp != null &&
                    scoringCalculator.isLateNadir(minHrTimestamp, session.startTime, session.durationMinutes)
            val isLateNadir = isLateNadirRaw && !isTimezoneJump

            return NadirContext(isLateNadir, isTimezoneJump)
        }
    }
