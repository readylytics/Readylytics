package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.scoring.BuildConfig
import java.time.Instant
import java.time.LocalDate

internal fun logDebugScoringMetrics(
    targetDate: LocalDate,
    dayMidnight: Instant,
    dayEndMs: Long,
    frozenBaseline: Boolean,
    isCalibrating: Boolean,
    hrvMuHistorySize: Int,
    rhrValuesSize: Int,
    sessionId: String,
    currentHrvMean: Float?,
    currentNocturnalRhr: Int?,
    durationMinutes: Int,
    loadScore: Float,
    frozenHrvMu: Float?,
    frozenHrvSigma: Float?,
    activeHrvMu: Float?,
    activeHrvSigma: Float?,
    frozenRhr: Float?,
    effectiveRhrSigma: Float?,
    zLnHrv: Float?,
    zRhr: Float?,
    sRest: Float?,
    sleepScore: Float?,
    readinessScore: Float?,
    recoveryFlags: String?,
) {
    if (!BuildConfig.DEBUG) return

    val debugPayload =
        """
        {
            "targetDate": "$targetDate",
            "dayMidnightMs": ${dayMidnight.toEpochMilli()},
            "dayEndMs": $dayEndMs,
            "frozenBaseline": $frozenBaseline,
            "isCalibrating": $isCalibrating,
            "windows": {
                "hrvMuHistorySize": $hrvMuHistorySize,
                "rhrValuesSize": $rhrValuesSize
            },
            "inputs": {
                "sessionId": "$sessionId",
                "currentHrvMean": $currentHrvMean,
                "currentNocturnalRhr": $currentNocturnalRhr,
                "durationMinutes": $durationMinutes,
                "loadScore": $loadScore
            },
            "baselines": {
                "frozenHrvMu": $frozenHrvMu,
                "frozenHrvSigma": $frozenHrvSigma,
                "activeHrvMu": $activeHrvMu,
                "activeHrvSigma": $activeHrvSigma,
                "frozenRhr": $frozenRhr,
                "effectiveRhrSigma": $effectiveRhrSigma
            },
            "scores": {
                "zHrv": $zLnHrv,
                "zRhr": $zRhr,
                "sRest": $sRest,
                "sleepScore": $sleepScore,
                "readinessScore": $readinessScore,
                "recoveryFlags": "$recoveryFlags"
            }
        }
        """.trimIndent()
    logD("ScoringDebug") { "\n$debugPayload" }
}