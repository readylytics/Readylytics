package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.scoring.strategies.LoadScoringStrategy
import com.gregor.lauritz.healthdashboard.domain.util.HeartRateFormulas
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ln

class ComputeHistoricalBaselinesUseCase(
    private val baselineComputer: BaselineComputer,
    private val loadScoringStrategy: LoadScoringStrategy,
) {
    suspend fun computeHistoricalBaselines(
        allDailySummaries: List<DailySummaryEntity>,
        prefs: UserPreferences,
    ): List<DailySummaryEntity> {
        val profile = prefs.physiologyProfile
        val hrMax = HeartRateFormulas.resolveMaxHeartRate(prefs)
        val paiScalingFactor = prefs.paiScalingFactor
        val sigmaPrior = profile.lnSigmaPrior

        return allDailySummaries.mapNotNull { summary ->
            if (summary.baselineCalculatedAtDate != null) return@mapNotNull null

            val date = summary.dateMidnightMs.toLocalDate()
            val dayMidnightInstant = summary.dateMidnightMs.toInstant()
            val dayMidnightMs = dayMidnightInstant.toEpochMilli()
            val dayEndMs = dayMidnightInstant.plus(1, ChronoUnit.DAYS).toEpochMilli() - 1

            val hrvWindows =
                baselineComputer.computeHrvWindowsBetween(dayMidnightMs, dayEndMs, excludeSessionId = null)
                    ?: return@mapNotNull null

            if (hrvWindows.muHistory.isEmpty()) return@mapNotNull null

            val rhrBpm =
                baselineComputer.computeAdaptiveBaselineRhrBpmBetween(
                    dayMidnightMs,
                    dayEndMs,
                    percentile = prefs.restingHrPercentile,
                )

            val lnMuHistory = hrvWindows.muHistory.map { ln(it.coerceAtLeast(0.001f)) }
            val lnSigmaHistory = hrvWindows.sigmaHistory.map { ln(it.coerceAtLeast(0.001f)) }

            val hrvMu = lnMuHistory.average().toFloat()
            val hrvSigma = loadScoringStrategy.hrvSigma(lnSigmaHistory, sigmaPrior)

            summary.copy(
                hrvMuMssd = hrvMu,
                hrvSigmaMssd = hrvSigma,
                rhrBpm = rhrBpm,
                hrMax = hrMax,
                snapshotProfile = profile.name,
                paiScalingFactor = paiScalingFactor,
                hrvSigmaPrior = sigmaPrior,
                baselineCalculatedAtDate = date,
                baselineObservationCount = hrvWindows.muHistory.size,
                baselineVersion = 1,
            )
        }
    }

    private fun Long.toLocalDate(): LocalDate =
        Instant
            .ofEpochMilli(this)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()

    private fun Long.toInstant(): Instant = Instant.ofEpochMilli(this)
}
