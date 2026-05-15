package com.gregor.lauritz.healthdashboard.domain.model

enum class RecoveryFlag {
    OVERREACHING,
    ILLNESS_ONSET,
    NADIR_DELAYED,
    CALIBRATING,
    HRV_MISSING,
    STAGES_MISSING,
}

/**
 * Domain object describing the full output of the readiness pipeline.
 *
 * Bundles headline scores, recovery flags, contributors, and diagnostics so
 * downstream consumers (UI, persistence, debug overlay) can reason about the
 * derivation rather than reading scattered raw columns.
 */
data class ReadinessResult(
    val readinessScore: Float?,
    val sleepScore: Float?,
    val loadScore: Float?,
    val sRest: Float?,
    val recoveryFlags: Set<RecoveryFlag>,
    val contributors: Contributors,
    val diagnostics: Diagnostics,
    /** Why readiness was capped below the raw weighted score. NONE = no cap. */
    val cappingReason: ReadinessCappingReason = ReadinessCappingReason.NONE,
    /** Actionable training guidance for the next 24h. Always present. */
    val recommendation: ReadinessRecommendation? = null,
    /** One-line human-readable cap explanation for the UI. Null when not capped. */
    val capExplanation: String? = null,
) {
    /**
     * Tracks which underlying factors drove the readiness score so the UI/debug
     * surface can explain the result.
     */
    data class Contributors(
        val hrvScore: Float? = null,
        val rhrScore: Float? = null,
        val durationScore: Float? = null,
        val architectureScore: Float? = null,
        val loadContribution: Float? = null,
    )

    /**
     * Complete metadata describing the readiness computation: z-scores, baseline
     * mu/sigma, and quality flags.
     */
    data class Diagnostics(
        val zLnHrv: Float? = null,
        val zRhr: Float? = null,
        val lnSigma: Float? = null,
        val rollingMu: Float? = null,
        val rhrDeltaBpm: Float? = null,
        val isCalibrating: Boolean = false,
        val stagesSuspicious: Boolean = false,
        val lateNadir: Boolean = false,
        val hrvMissing: Boolean = false,
        val timezoneJump: Boolean = false,
        val configHashCode: Int? = null,
        val phaseName: String? = null,
    )

    companion object {
        val EMPTY: ReadinessResult =
            ReadinessResult(
                readinessScore = null,
                sleepScore = null,
                loadScore = null,
                sRest = null,
                recoveryFlags = emptySet(),
                contributors = Contributors(),
                diagnostics = Diagnostics(),
                cappingReason = ReadinessCappingReason.NONE,
                recommendation = null,
                capExplanation = null,
            )
    }
}
