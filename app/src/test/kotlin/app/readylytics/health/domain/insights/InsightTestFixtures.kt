package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.ReadinessResult
import app.readylytics.health.domain.model.RecoveryFlag
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import java.time.LocalDate

internal fun dailySummary(
    date: LocalDate = LocalDate.of(2026, 6, 12),
    recoveryFlags: Set<RecoveryFlag> = emptySet(),
    strainRatio: Float? = null,
    sleepDurationMinutes: Int? = null,
    lateNadir: Boolean = false,
    rhrDeltaBpm: Float? = null,
    zLnHrv: Float? = null,
    zRhr: Float? = null,
    avgSleepingSpo2: Float? = null,
    bloodPressureSystolic: Int? = null,
    paiScore: Float? = null,
    totalPai: Float? = null,
    stepCount: Int? = null,
    weightKg: Float? = null,
    totalTrimp: Float? = null,
    readinessScore: Float? = null,
): DailySummary =
    DailySummary(
        date = date,
        recoveryFlags = recoveryFlags,
        strainRatio = strainRatio,
        sleepDurationMinutes = sleepDurationMinutes,
        zLnHrv = zLnHrv,
        zRhr = zRhr,
        avgSleepingSpo2 = avgSleepingSpo2,
        bloodPressureSystolic = bloodPressureSystolic,
        paiScore = paiScore,
        totalPai = totalPai,
        stepCount = stepCount,
        weightKg = weightKg,
        totalTrimp = totalTrimp,
        readinessScore = readinessScore,
        readinessResult =
            ReadinessResult.EMPTY.copy(
                diagnostics =
                    ReadinessResult.EMPTY.diagnostics.copy(
                        lateNadir = lateNadir,
                        rhrDeltaBpm = rhrDeltaBpm,
                    ),
            ),
    )

internal fun circadianReady(
    latestBedtimeOffsetMinutes: Int = 0,
    medianBedtimeMinutes: Int = 1410,
): CircadianConsistencyResult.Ready =
    CircadianConsistencyResult.Ready(
        score = 80f,
        medianBedtimeMinutes = medianBedtimeMinutes,
        medianWakeMinutes = 420,
        thresholdMinutes = 30,
        latestBedtimeOffsetMinutes = latestBedtimeOffsetMinutes,
    )
