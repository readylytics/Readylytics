package com.gregor.lauritz.healthdashboard.domain.scoring.strategies

import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaiScoringStrategy
    @Inject
    constructor() {
        fun computeStrainRatio(
            atl: Float,
            ctl: Float,
        ): Float = if (ctl > 0f) atl / ctl else 0f

        fun computeCtlEma(
            dailyTrimpList: List<Float>,
            seedFitnessLevel: Float = ScoringConstants.DEFAULT_FITNESS_LEVEL,
            windowDays: Long = ScoringConstants.CHRONIC_DAYS,
        ): Float = computeEma(dailyTrimpList, seedFitnessLevel, windowDays)

        fun computeAtlEma(
            dailyTrimpList: List<Float>,
            seedFatigueLevel: Float = ScoringConstants.DEFAULT_FITNESS_LEVEL,
            windowDays: Long = ScoringConstants.ACUTE_DAYS,
        ): Float = computeEma(dailyTrimpList, seedFatigueLevel, windowDays)

        private fun computeEma(
            data: List<Float>,
            seed: Float,
            windowDays: Long,
        ): Float {
            val n = data.size
            if (n < ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION) return seed

            val sma = data.take(ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION).average().toFloat()
            if (n <= ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION) return sma

            val alpha = 2f / (windowDays + 1f)
            var currentEma = sma

            for (i in ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION until data.size) {
                currentEma = (data[i] * alpha) + (currentEma * (1f - alpha))
            }

            return currentEma
        }

        fun computeCtlEmaWithDecay(
            dailyTrimpByDate: Map<LocalDate, Float>,
            rangeEnd: LocalDate,
            windowDays: Long = ScoringConstants.CHRONIC_DAYS,
        ): Float = computeEmaWithDecay(dailyTrimpByDate, windowDays, rangeEnd)

        fun computeAtlEmaWithDecay(
            dailyTrimpByDate: Map<LocalDate, Float>,
            rangeEnd: LocalDate,
            windowDays: Long = ScoringConstants.ACUTE_DAYS,
        ): Float = computeEmaWithDecay(dailyTrimpByDate, windowDays, rangeEnd)

        private fun computeEmaWithDecay(
            dailyTrimpByDate: Map<LocalDate, Float>,
            windowDays: Long,
            rangeEnd: LocalDate,
        ): Float {
            if (dailyTrimpByDate.isEmpty()) return ScoringConstants.DEFAULT_FITNESS_LEVEL
            if (dailyTrimpByDate.size == 1) return dailyTrimpByDate.values.first()

            val earliestDataDate = dailyTrimpByDate.keys.minOrNull() ?: rangeEnd
            val defaultStart = rangeEnd.minusDays(windowDays - 1)
            val rangeStart = if (earliestDataDate.isBefore(defaultStart)) earliestDataDate else defaultStart

            val alpha = 2.0 / (windowDays + 1)
            var ewma = dailyTrimpByDate[rangeStart]?.toDouble() ?: 0.0
            var date = rangeStart.plusDays(1)
            while (!date.isAfter(rangeEnd)) {
                val trimp = dailyTrimpByDate[date]?.toDouble() ?: 0.0
                ewma = trimp * alpha + ewma * (1.0 - alpha)
                date = date.plusDays(1)
            }
            return ewma.toFloat()
        }
    }
