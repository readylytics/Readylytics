package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsDefaults
import com.gregor.lauritz.healthdashboard.domain.scoring.strategies.LoadScoringStrategy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ln

class ComputeHistoricalBaselinesUseCase(
    private val baselineComputer: BaselineComputer,
    private val loadScoringStrategy: LoadScoringStrategy,
) {
    suspend fun computeHistoricalBaselines(allDailySummaries: List<DailySummaryEntity>): List<DailySummaryEntity> {
        val baseline30thDay = LocalDate.now().minusDays(30)
        return allDailySummaries.mapNotNull { summary ->
            val date = summary.dateMidnightMs.toLocalDate()
            if (date < baseline30thDay || summary.baselineCalculatedAtDate != null) {
                return@mapNotNull null
            }
            val dayMidnightInstant = summary.dateMidnightMs.toInstant()
            val dayMidnightMs = dayMidnightInstant.toEpochMilli()
            val dayEndMs = dayMidnightInstant.plus(1, ChronoUnit.DAYS).toEpochMilli() - 1

            // Use bounded window queries for point-in-time correctness (no look-ahead)
            val hrvWindows =
                baselineComputer.computeHrvWindowsBetween(dayMidnightMs, dayEndMs, excludeSessionId = null)
                    ?: return@mapNotNull null
            val rhrBpm =
                baselineComputer.computeAdaptiveBaselineRhrBpmBetween(
                    dayMidnightMs,
                    dayEndMs,
                    percentile = SettingsDefaults.RESTING_HR_PERCENTILE,
                )

            if (hrvWindows.muHistory.isEmpty()) {
                return@mapNotNull null
            }

            // Compute mu/sigma in ln-space to match live pipeline (LoadScoringStrategy.kt:29-61)
            val lnMuHistory = hrvWindows.muHistory.map { ln(it.coerceAtLeast(0.001f)) }
            val lnSigmaHistory = hrvWindows.sigmaHistory.map { ln(it.coerceAtLeast(0.001f)) }

            val hrvMu = lnMuHistory.average().toFloat()
            val sigmaPrior = PhysiologyProfile.GENERAL.lnSigmaPrior
            val hrvSigma = loadScoringStrategy.hrvSigma(lnSigmaHistory, sigmaPrior)

            summary.copy(
                hrvMuMssd = hrvMu,
                hrvSigmaMssd = hrvSigma,
                rhrBpm = rhrBpm,
                baselineCalculatedAtDate = date,
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
