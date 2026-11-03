package app.readylytics.health.core.scoring.domain.scoring.sleep

import app.readylytics.health.core.model.domain.model.HeartRateRecord
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HrCoverageValidator
    @Inject
    constructor() {
        fun isValid(
            sessionStartMs: Long,
            sessionEndMs: Long,
            hrRecords: List<HeartRateRecord>,
        ): Boolean {
            val sleepDurationMs = sessionEndMs - sessionStartMs
            if (sleepDurationMs <= 0) return false

            val totalCoverage =
                IntervalCoverageAccumulator.calculateCoverageMs(
                    sessionStartMs = sessionStartMs,
                    sessionEndMs = sessionEndMs,
                    hrRecords = hrRecords,
                )
            val coveragePercent = (totalCoverage.toFloat() / sleepDurationMs.toFloat()) * 100f
            return coveragePercent >= 70f
        }
    }
