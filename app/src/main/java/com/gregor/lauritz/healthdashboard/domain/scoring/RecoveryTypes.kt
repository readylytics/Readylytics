package com.gregor.lauritz.healthdashboard.domain.scoring

enum class RecoveryFlag {
    OVERREACHING,
    ILLNESS_ONSET,
    NADIR_DELAYED,
    CALIBRATING,
    HRV_MISSING,
    STAGES_MISSING,
}

data class ReadinessDiagnostics(
    val zLnHrv: Float?,
    val zRhr: Float?,
    val lnSigma: Float?,
    val rollingMu: Float?,
)
