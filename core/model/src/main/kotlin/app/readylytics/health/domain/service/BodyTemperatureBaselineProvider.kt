package app.readylytics.health.domain.service

import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.preferences.scoringZone
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.util.toMidnightEpochMilli
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject

/**
 * Resolves the current 14-day trailing body-temperature baseline for one date at a time —
 * mirrors how [app.readylytics.health.domain.scoring.HrvBaselineProvider] is consumed (a single
 * current value keyed off the target date), but with a plain trailing average instead of scoring's
 * log-normal EWMA. Never touches the domain.scoring package.
 */
class BodyTemperatureBaselineProvider
    @Inject
    constructor(
        private val dailySummaryRepository: DailySummaryRepository,
        private val userPreferencesReader: UserPreferencesReader,
        private val calculator: BodyTemperatureBaselineCalculator,
    ) {
        suspend fun getBaseline(date: LocalDate): Float? {
            val zoneId = userPreferencesReader.userPreferences.first().scoringZone()
            val windowStartMs =
                date
                    .minusDays(BodyTemperatureBaselineCalculator.BASELINE_WINDOW_DAYS.toLong())
                    .toMidnightEpochMilli(zoneId)
            val values =
                dailySummaryRepository
                    .getSince(windowStartMs)
                    .filter { it.date.isBefore(date) }
                    .mapNotNull { it.avgSleepingBodyTemp }
            return calculator.calculateBaseline(values)
        }
    }
