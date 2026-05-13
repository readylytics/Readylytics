package com.gregor.lauritz.healthdashboard.domain.scoring

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
