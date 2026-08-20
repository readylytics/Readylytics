package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.ReadinessDiagnostics

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
