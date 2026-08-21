package app.readylytics.health.core.model.domain.service

import javax.inject.Inject

/**
 * Pure-Kotlin trailing-average baseline for the Body Temperature "possible illness" insight.
 * Deliberately independent of [app.readylytics.health.core.scoring.domain.scoring] baseline machinery
 * (HRV/RHR use a log-normal EWMA coupled to scoring formulas) — this is a plain average over an
 * already-cached display field, never read by any score computation.
 */
class BodyTemperatureBaselineCalculator
    @Inject
    constructor() {
        /**
         * @param nonNullValuesInTrailingWindow the non-null `avgSleepingBodyTemp` values found in the
         *   [BASELINE_WINDOW_DAYS]-calendar-day window immediately before the target date. Fewer than
         *   [BASELINE_WINDOW_DAYS] entries (whether from gaps or insufficient history) means the window
         *   isn't fully calibrated yet.
         * @return the average, or `null` if calibrating.
         */
        fun calculateBaseline(nonNullValuesInTrailingWindow: List<Float>): Float? {
            if (nonNullValuesInTrailingWindow.size < BASELINE_WINDOW_DAYS) return null
            return nonNullValuesInTrailingWindow.average().toFloat()
        }

        companion object {
            const val BASELINE_WINDOW_DAYS = 14
        }
    }
