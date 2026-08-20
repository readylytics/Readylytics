package app.readylytics.health.domain.service


import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.util.toMidnightEpochMilli
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject

/**
 * Resolves the current 14-day trailing body-temperature baseline for one date at a time —
 * mirrors how [app.readylytics.health.core.scoring.domain.scoring.HrvBaselineProvider] is consumed (a single
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
        @OptIn(ExperimentalCoroutinesApi::class)
        fun observeBaseline(date: LocalDate): Flow<Float?> =
            userPreferencesReader.userPreferences
                .map { it.scoringZone() }
                .distinctUntilChanged()
                .flatMapLatest { zoneId ->
                    val fromMs = date.minusDays(14).toMidnightEpochMilli(zoneId)
                    dailySummaryRepository.observeSince(fromMs).map { summaries ->
                        calculator.calculateBaseline(
                            summaries.filter { it.date.isBefore(date) }.mapNotNull { it.avgSleepingBodyTemp },
                        )
                    }
                }.distinctUntilChanged()

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
