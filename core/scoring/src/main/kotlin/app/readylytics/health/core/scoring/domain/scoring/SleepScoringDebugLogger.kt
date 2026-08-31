package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.scoring.BuildConfig

internal fun logDebugScoringMetrics(snapshot: DebugScoringSnapshot) {
    if (!BuildConfig.DEBUG) return

    val debugPayload =
        """
        {
            "targetDate": "${snapshot.targetDate}",
            "dayMidnightMs": ${snapshot.dayMidnight.toEpochMilli()},
            "dayEndMs": ${snapshot.dayEndMs},
            "frozenBaseline": ${snapshot.frozenBaseline},
            "isCalibrating": ${snapshot.isCalibrating},
            "windows": {
                "hrvMuHistorySize": ${snapshot.hrvMuHistorySize},
                "rhrValuesSize": ${snapshot.rhrValuesSize}
            },
            "inputs": {
                "sessionId": "${snapshot.sessionId}",
                "currentHrvMean": ${snapshot.currentHrvMean},
                "currentNocturnalRhr": ${snapshot.currentNocturnalRhr},
                "durationMinutes": ${snapshot.durationMinutes},
                "loadScore": ${snapshot.loadScore}
            },
            "baselines": {
                "frozenHrvMu": ${snapshot.frozenHrvMu},
                "frozenHrvSigma": ${snapshot.frozenHrvSigma},
                "activeHrvMu": ${snapshot.activeHrvMu},
                "activeHrvSigma": ${snapshot.activeHrvSigma},
                "frozenRhr": ${snapshot.frozenRhr},
                "effectiveRhrSigma": ${snapshot.effectiveRhrSigma}
            },
            "scores": {
                "zHrv": ${snapshot.zLnHrv},
                "zRhr": ${snapshot.zRhr},
                "sRest": ${snapshot.sRest},
                "sleepScore": ${snapshot.sleepScore},
                "readinessScore": ${snapshot.readinessScore},
                "recoveryFlags": "${snapshot.recoveryFlags}"
            }
        }
        """.trimIndent()
    logD("ScoringDebug") { "\n$debugPayload" }
}
