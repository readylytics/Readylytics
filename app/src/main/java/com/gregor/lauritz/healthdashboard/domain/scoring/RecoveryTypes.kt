package com.gregor.lauritz.healthdashboard.domain.scoring

enum class RecoveryFlag {
    OVERREACHING,
    ILLNESS_ONSET,
    NADIR_DELAYED,
    CALIBRATING,
    HRV_MISSING,
    STAGES_MISSING,
}

@Deprecated(
    message = "Use ReadinessResult.Diagnostics instead.",
    replaceWith = ReplaceWith("ReadinessResult.Diagnostics"),
)
data class ReadinessDiagnostics(
    val zLnHrv: Float?,
    val zRhr: Float?,
    val lnSigma: Float?,
    val rollingMu: Float?,
)

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
    )

    companion object {
        val EMPTY: ReadinessResult = ReadinessResult(
            readinessScore = null,
            sleepScore = null,
            loadScore = null,
            sRest = null,
            recoveryFlags = emptySet(),
            contributors = Contributors(),
            diagnostics = Diagnostics(),
        )
    }
}
