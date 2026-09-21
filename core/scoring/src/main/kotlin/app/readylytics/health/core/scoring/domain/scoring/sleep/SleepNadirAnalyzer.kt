package app.readylytics.health.core.scoring.domain.scoring.sleep

import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepNadirAnalyzer

import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
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

        /**
         * WP-14/C4: derives nadir-timing/timezone-jump exclusively from [core] -- the core
         * (overnight) cluster's own window and offset evidence -- never from the whole-day,
         * nap-inclusive session or from an arbitrary "most recent" historical session that could
         * itself be a supplemental nap.
         */
        suspend fun analyze(
            core: CoreRecoveryInput,
            minHrTimestamp: Long?,
        ): NadirContext {
            val currentOffset = core.endZoneOffsetSeconds
            val previousOffset = core.previousCoreEndZoneOffsetSeconds
            val isTimezoneJump =
                currentOffset != null &&
                    previousOffset != null &&
                    abs(currentOffset - previousOffset) >= ScoringConstants.TIMEZONE_JUMP_THRESHOLD_SECONDS

            val isLateNadirRaw =
                minHrTimestamp != null &&
                    scoringCalculator.isLateNadir(
                        minHrTimestamp,
                        core.window.startTimeMs,
                        core.window.coreSleepDurationMinutes,
                    )
            val isLateNadir = isLateNadirRaw && !isTimezoneJump

            return NadirContext(isLateNadir, isTimezoneJump)
        }
    }
