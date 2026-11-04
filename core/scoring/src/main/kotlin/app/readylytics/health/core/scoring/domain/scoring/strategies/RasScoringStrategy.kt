package app.readylytics.health.core.scoring.domain.scoring.strategies

import app.readylytics.health.core.scoring.domain.scoring.strategies.RasScoringStrategy

import app.readylytics.health.core.model.domain.scoring.ScoringConstants
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
        ): Float = computeEmaSeries(dailyTrimpByDate, windowDays, rangeEnd, rangeEnd)[rangeEnd] ?: 0f

        private fun computeEmaSeries(
            dailyTrimpByDate: Map<LocalDate, Float>,
            windowDays: Long,
            seriesStart: LocalDate,
            seriesEnd: LocalDate,
        ): Map<LocalDate, Float> {
            if (seriesStart.isAfter(seriesEnd)) return emptyMap()

            val result = LinkedHashMap<LocalDate, Float>()
            if (dailyTrimpByDate.isEmpty()) {
                var date = seriesStart
                while (!date.isAfter(seriesEnd)) {
                    result[date] = 0f
                    date = date.plusDays(1)
                }
                return result
            }

            val earliestDataDate = dailyTrimpByDate.keys.minOrNull() ?: seriesStart
            val calcStart = if (earliestDataDate.isBefore(seriesStart)) earliestDataDate else seriesStart

            val alpha = 2.0 / (windowDays + 1.0)
            var ewma = 0.0

            var date = calcStart
            while (!date.isAfter(seriesEnd)) {
                val trimp = dailyTrimpByDate[date]?.toDouble() ?: 0.0
                ewma = trimp * alpha + ewma * (1.0 - alpha)
                if (!date.isBefore(seriesStart)) {
                    result[date] = ewma.toFloat()
                }
                date = date.plusDays(1)
            }

            return result
        }
    }
