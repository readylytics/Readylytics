package app.readylytics.health.core.scoring.domain.scoring.strategies

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
        ): Float = ewmaAt(dailyTrimpByDate, windowDays, rangeEnd)

        private fun computeEmaSeries(
            dailyTrimpByDate: Map<LocalDate, Float>,
            windowDays: Long,
            seriesStart: LocalDate,
            seriesEnd: LocalDate,
        ): Map<LocalDate, Float> {
            val result = LinkedHashMap<LocalDate, Float>()
            var date = seriesStart
            while (!date.isAfter(seriesEnd)) {
                result[date] = ewmaAt(dailyTrimpByDate, windowDays, date)
                date = date.plusDays(1)
            }
            return result
        }

        /**
         * Zero-initialised calendar-day EWMA of [date], anchored at `date - LOAD_LOOKBACK_DAYS`.
         * Anchoring per value (not per map) makes the result independent of how much older data
         * the caller holds, so stored, workouts and dashboard tiers agree. Days without an entry
         * count as zero TRIMP; entries before the anchor are ignored.
         */
        private fun ewmaAt(
            dailyTrimpByDate: Map<LocalDate, Float>,
            windowDays: Long,
            date: LocalDate,
        ): Float {
            val alpha = 2.0 / (windowDays + 1.0)
            var ewma = 0.0
            var day = date.minusDays(ScoringConstants.LOAD_LOOKBACK_DAYS)
            while (!day.isAfter(date)) {
                val trimp = dailyTrimpByDate[day]?.toDouble() ?: 0.0
                ewma = trimp * alpha + ewma * (1.0 - alpha)
                day = day.plusDays(1)
            }
            return ewma.toFloat()
        }
    }
