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
            if (seriesStart.isAfter(seriesEnd)) return result
            // Densify once into a primitive array covering [seriesStart - LOOKBACK, seriesEnd], so each
            // value's anchored recurrence reads array slots instead of boxed LocalDate map lookups.
            // The arithmetic and its order are identical to ewmaAt, so results stay bit-identical.
            val base = seriesStart.minusDays(ScoringConstants.LOAD_LOOKBACK_DAYS)
            val baseEpochDay = base.toEpochDay()
            val seriesLength = (seriesEnd.toEpochDay() - base.toEpochDay() + 1).toInt()
            val trimp = DoubleArray(seriesLength)
            for ((day, value) in dailyTrimpByDate) {
                val index = day.toEpochDay() - baseEpochDay
                if (index in 0 until seriesLength) trimp[index.toInt()] = value.toDouble()
            }
            val alpha = emaAlpha(windowDays)
            val lookback = ScoringConstants.LOAD_LOOKBACK_DAYS.toInt()
            var date = seriesStart
            var dateIndex = lookback
            while (!date.isAfter(seriesEnd)) {
                var ewma = 0.0
                for (i in (dateIndex - lookback)..dateIndex) {
                    ewma = trimp[i] * alpha + ewma * (1.0 - alpha)
                }
                result[date] = ewma.toFloat()
                date = date.plusDays(1)
                dateIndex++
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
            val alpha = emaAlpha(windowDays)
            var ewma = 0.0
            var day = date.minusDays(ScoringConstants.LOAD_LOOKBACK_DAYS)
            while (!day.isAfter(date)) {
                val trimp = dailyTrimpByDate[day]?.toDouble() ?: 0.0
                ewma = trimp * alpha + ewma * (1.0 - alpha)
                day = day.plusDays(1)
            }
            return ewma.toFloat()
        }

        private fun emaAlpha(windowDays: Long): Double = 2.0 / (windowDays + 1.0)
    }
