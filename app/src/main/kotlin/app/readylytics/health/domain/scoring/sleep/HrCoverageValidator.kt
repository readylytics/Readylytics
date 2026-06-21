package app.readylytics.health.domain.scoring.sleep

import app.readylytics.health.domain.model.HeartRateRecordEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HrCoverageValidator
    @Inject
    constructor() {
        fun isValid(
            sessionStartMs: Long,
            sessionEndMs: Long,
            durationMinutes: Int,
            hrRecords: List<HeartRateRecordEntity>,
        ): Boolean {
            val filtered = hrRecords.filter { it.timestampMs in sessionStartMs..sessionEndMs }
            if (filtered.isEmpty()) return false

            val sleepDurationMs = sessionEndMs - sessionStartMs
            val coverageMs =
                if (filtered.size > 1) {
                    filtered.zipWithNext { current, next -> next.timestampMs - current.timestampMs }.sum()
                } else {
                    0L
                }
            val totalCoverage = (coverageMs + if (filtered.isNotEmpty()) 60000L else 0L).coerceAtMost(sleepDurationMs)
            val coveragePercent = (totalCoverage.toFloat() / sleepDurationMs.toFloat()) * 100f
            return coveragePercent >= 70f
        }
    }
