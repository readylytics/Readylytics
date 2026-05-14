package com.gregor.lauritz.healthdashboard.domain.model

/**
 * Centralized physiological constants for max HR estimation and RHR defaults.
 * These constants are foundational inputs for heart rate zone calculations,
 * TRIMP computations, and recovery monitoring.
 */
object PhysiologyConstants {
    // Max Heart Rate Calculation — REF: Karvonen 1957; well-validated for general populations
    // Formula: HRmax = 220 - age. Accuracy ~±10-15 bpm for sedentary to moderate fitness.
    // Note: Age-adjusted formulas (Ruan 2020: 206.9 - 0.67*age) show better accuracy for active populations.
    const val MAX_HR_KARVONEN_INTERCEPT = 220

    // Resting Heart Rate (RHR) Defaults
    // DEFAULT: Safe middle ground for untrained individuals; clinically normal range 60-100 bpm
    // REF: Task Force 1996 Circulation; Plazak 2011 J Clin Med Res
    const val DEFAULT_RHR_BPM = 60

    // Valid RHR input bounds — REF: ScoringConstants validation rules
    const val MINIMUM_RHR_BPM = 30
    const val MAXIMUM_RHR_BPM = 100

    // Heart Rate Reserve (HRR) — REF: Karvonen 1957; foundation for HR zone thresholds
    // HRR = HRmax - RHR; enables normalization across age/fitness levels
    const val MINIMUM_VALID_HRR = 5 // bpm; pathologically low HRR suggests data quality issue

    // Heart Rate Zones as % of HRR — REF: Karvonen zone model; Stegeman 2006
    // Zone thresholds based on lactate threshold (~85% HRmax) and anaerobic threshold research
    object Zones {
        // Zone 1: 50–60% HRR — active recovery, parasympathetic dominant
        const val ZONE1_UPPER_PERCENT = 0.50f

        // Zone 2: 60–70% HRR — aerobic base, fat oxidation peak
        const val ZONE2_UPPER_PERCENT = 0.60f

        // Zone 3: 70–85% HRR — tempo/threshold, lactate production increases
        const val ZONE3_UPPER_PERCENT = 0.70f

        // Zone 4: 85–95% HRR — lactate threshold zone, sustained high intensity
        const val ZONE4_UPPER_PERCENT = 0.85f

        // Zone 5: 95%+ HRR — VO2max and above, anaerobic work
        // No upper constant; unbounded zone
    }
}
