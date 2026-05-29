package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.domain.scoring.strategies.LoadScoringStrategy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

            // TODO: Pass current day's session ID to excludeSessionId parameter to match live pipeline
            // For now, pass null (known limitation — session is included in baseline window)
            // computeHrvWindows / computeAdaptiveBaselineRhrBpm return null when baseline is frozen (US-B6).
            // The outer guard (summary.baselineCalculatedAtDate != null) already skips frozen rows,
            // so null here is unexpected but handled defensively.
            val hrvWindows =
                baselineComputer.computeHrvWindows(dayMidnightInstant, excludeSessionId = null)
                    ?: return@mapNotNull null
            val rhrBpm = baselineComputer.computeAdaptiveBaselineRhrBpm(dayMidnightInstant, rhrBaselineOverride = null)

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
