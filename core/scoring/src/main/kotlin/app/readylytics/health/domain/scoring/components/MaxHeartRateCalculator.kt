package app.readylytics.health.domain.scoring.components

import app.readylytics.health.domain.model.PhysiologyConstants
import kotlin.math.max

/**
 * Centralized max heart rate calculator.
 * Single source of truth for all max HR estimates across the app.
 *
 * REF: Karvonen 1957; validated for general populations (±10-15 bpm accuracy)
 */
object MaxHeartRateCalculator {
    /**
     * Estimate max heart rate using Karvonen formula: HRmax = 220 - age
     *
     * Valid for: sedentary to moderate fitness populations
     * Accuracy: ±10-15 bpm typical range
     * Limitations: overestimates active individuals, underestimates very fit athletes
     *
     * @param ageYears Age in years (typically 18-99)
     * @return Estimated max heart rate (bpm)
     */
    fun calculateMaxHeartRate(ageYears: Int): Int {
        val estimatedMaxHr = PhysiologyConstants.MAX_HR_KARVONEN_INTERCEPT - ageYears
        // Floor at minimum valid HRR to prevent invalid zones below resting HR
        return max(estimatedMaxHr, PhysiologyConstants.MINIMUM_RHR_BPM + PhysiologyConstants.MINIMUM_VALID_HRR)
    }
}
