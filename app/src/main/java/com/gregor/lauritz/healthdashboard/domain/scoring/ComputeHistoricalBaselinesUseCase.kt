package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
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
    private val sleepSessionDao: SleepSessionDao,
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

            val date = summary.dateMidnightMs.toLocalDate()
            val dayMidnightInstant = summary.dateMidnightMs.toInstant()
            val dayMidnightMs = dayMidnightInstant.toEpochMilli()
            val nextDayMidnightMs = dayMidnightInstant.plus(1, ChronoUnit.DAYS).toEpochMilli()
            val dayEndMs = nextDayMidnightMs - 1

            // Exclude the sleep session recorded for this day from its own HRV baseline window,
            // mirroring the live sync path (ScoringRepositoryImpl.computeDailySummary). The session
            // is the day's measurement, not part of the baseline training data; including it here
            // produced a frozen backfill baseline that diverged from the live recompute.
            val sessionForDay = sleepSessionDao.getSessionEndingInRange(dayMidnightMs, nextDayMidnightMs)

            val hrvWindows =
                baselineComputer.computeHrvWindowsBetween(dayMidnightMs, dayEndMs, excludeSessionId = sessionForDay?.id)
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
