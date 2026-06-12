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
): DailySummary =
    DailySummary(
        date = date,
        recoveryFlags = recoveryFlags,
        strainRatio = strainRatio,
        sleepDurationMinutes = sleepDurationMinutes,
        readinessResult =
            ReadinessResult.EMPTY.copy(
                diagnostics = ReadinessResult.EMPTY.diagnostics.copy(lateNadir = lateNadir),
            ),
    )

internal fun circadianReady(latestBedtimeOffsetMinutes: Int): CircadianConsistencyResult.Ready =
    CircadianConsistencyResult.Ready(
        score = 80f,
        medianBedtimeMinutes = 1410,
        medianWakeMinutes = 420,
        thresholdMinutes = 30,
        latestBedtimeOffsetMinutes = latestBedtimeOffsetMinutes,
    )
