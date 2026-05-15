package com.gregor.lauritz.healthdashboard.domain.model

/**
 * Why the readiness score was capped below its raw weighted value.
 * Surfaced via [ReadinessResult.cappingReason] so the UI can render an
 * actionable explanation instead of just a low number.
 *
 * Citations:
 * - Le Meur Y et al. *A multidisciplinary approach to overreaching detection in endurance trained athletes*. J Appl Physiol 2013.
 * - Bellenger CR et al. *Monitoring athletic training status through autonomic heart rate regulation*. Front Physiol 2017.
 * - Mishra T et al. *Pre-symptomatic detection of COVID-19 from smartwatch data*. Nat Biomed Eng 2020;4:1208–1220.
 * - Quer G et al. *Wearable sensor data and self-reported symptoms for COVID-19 detection*. Nat Med 2021;27:73-77.
 * - Gabbett TJ. *The training-injury prevention paradox*. Br J Sports Med 2016;50:273-280.
 */
enum class ReadinessCappingReason {
    /** No cap applied; readiness reflects the raw weighted score. */
    NONE,

    /**
     * Functional overreaching detected: HRV elevated ≥1.5σ above mu AND RHR
     * depressed ≤-2σ below mu for two consecutive nights. Indicates parasympathetic
     * over-drive secondary to accumulated training load.
     */
    OVERREACHING_CONSECUTIVE,

    /**
     * Illness onset signature: HRV depressed AND RHR elevated for two
     * consecutive nights. Common pre-symptomatic pattern for viral infection
     * and overtraining-induced immune suppression.
     */
    ILLNESS_ONSET_CONSECUTIVE,

    /**
     * Acute:chronic workload ratio (Strain Ratio) above 2.0 — the "danger zone"
     * for injury and non-functional overreaching per Gabbett 2016.
     */
    EXTREME_LOAD,
}
