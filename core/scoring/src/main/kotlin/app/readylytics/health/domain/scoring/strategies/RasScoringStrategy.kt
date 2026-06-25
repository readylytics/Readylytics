package app.readylytics.health.domain.scoring.strategies

import app.readylytics.health.domain.scoring.ScoringConstants
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RasScoringStrategy
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

        fun computeCtlEmaSeries(
            dailyTrimpByDate: Map<LocalDate, Float>,
            rangeStart: LocalDate,
            rangeEnd: LocalDate,
            windowDays: Long = ScoringConstants.CHRONIC_DAYS,
        ): Map<LocalDate, Float> = computeEmaSeries(dailyTrimpByDate, windowDays, rangeStart, rangeEnd)

        fun computeAtlEmaWithDecay(
            dailyTrimpByDate: Map<LocalDate, Float>,
            rangeEnd: LocalDate,
            windowDays: Long = ScoringConstants.ACUTE_DAYS,
        ): Float = computeEmaWithDecay(dailyTrimpByDate, windowDays, rangeEnd)

        fun computeAtlEmaSeries(
            dailyTrimpByDate: Map<LocalDate, Float>,
            rangeStart: LocalDate,
            rangeEnd: LocalDate,
            windowDays: Long = ScoringConstants.ACUTE_DAYS,
        ): Map<LocalDate, Float> = computeEmaSeries(dailyTrimpByDate, windowDays, rangeStart, rangeEnd)

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

        private fun computeEmaSeries(
            dailyTrimpByDate: Map<LocalDate, Float>,
            windowDays: Long,
            seriesStart: LocalDate,
            seriesEnd: LocalDate,
        ): Map<LocalDate, Float> {
            val result = mutableMapOf<LocalDate, Float>()
            if (dailyTrimpByDate.isEmpty()) {
                var date = seriesStart
                while (!date.isAfter(seriesEnd)) {
                    result[date] = ScoringConstants.DEFAULT_FITNESS_LEVEL
                    date = date.plusDays(1)
                }
                return result
            }
            if (dailyTrimpByDate.size == 1) {
                val singleValue = dailyTrimpByDate.values.first()
                var date = seriesStart
                while (!date.isAfter(seriesEnd)) {
                    result[date] = singleValue
                    date = date.plusDays(1)
                }
                return result
            }

            val earliestDataDate = dailyTrimpByDate.keys.minOrNull() ?: seriesEnd
            val defaultStart = seriesEnd.minusDays(windowDays - 1)
            val calcStart = if (earliestDataDate.isBefore(defaultStart)) earliestDataDate else defaultStart

            val alpha = 2.0 / (windowDays + 1)
            var ewma = dailyTrimpByDate[calcStart]?.toDouble() ?: 0.0

            if (!calcStart.isBefore(seriesStart) && !calcStart.isAfter(seriesEnd)) {
                result[calcStart] = ewma.toFloat()
            }

            var date = calcStart.plusDays(1)
            while (!date.isAfter(seriesEnd)) {
                val trimp = dailyTrimpByDate[date]?.toDouble() ?: 0.0
                ewma = trimp * alpha + ewma * (1.0 - alpha)
                if (!date.isBefore(seriesStart)) {
                    result[date] = ewma.toFloat()
                }
                date = date.plusDays(1)
            }

            var fillDate = seriesStart
            while (fillDate.isBefore(calcStart) && !fillDate.isAfter(seriesEnd)) {
                result[fillDate] = ScoringConstants.DEFAULT_FITNESS_LEVEL
                fillDate = fillDate.plusDays(1)
            }

            return result
        }
    }
