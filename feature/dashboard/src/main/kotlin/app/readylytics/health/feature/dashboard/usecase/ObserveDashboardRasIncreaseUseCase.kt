package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.di.DefaultDispatcher
import app.readylytics.health.domain.model.LoadSourceSelector
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.preferences.scoringZone
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.scoring.calculateDailyRasIncrease
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

class ObserveDashboardRasIncreaseUseCase
    @Inject
    constructor(
        private val dailySummaryRepository: DailySummaryRepository,
        @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    ) {
        @OptIn(ExperimentalCoroutinesApi::class)
        operator fun invoke(
            selectedDate: Flow<LocalDate>,
            preferences: Flow<UserPreferences>,
        ): Flow<Float?> =
            combine(selectedDate, preferences) { date, prefs -> date to prefs }
                .flatMapLatest { (date, prefs) ->
                    val zoneId = prefs.scoringZone()
                    val lookbackStart = date.minusDays(60)
                    val fetchFromMs =
                        lookbackStart
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli()

                    dailySummaryRepository
                        .observeSince(fetchFromMs)
                        .mapLatest { summaries ->
                            val todaySummary = summaries.firstOrNull { it.date == date }

                            val earliestDate =
                                summaries.minByOrNull { it.date }?.date
                            val dataTenureDays =
                                earliestDate?.let { ChronoUnit.DAYS.between(it, date).toInt() + 1 } ?: 0

                            val todayRas =
                                todaySummary?.let {
                                    LoadSourceSelector.selectDailyRas(
                                        it,
                                        prefs.rasSourceMode,
                                    )
                                }

                            calculateDailyRasIncrease(
                                dataTenureDays = dataTenureDays,
                                todayRas = todayRas,
                            )
                        }
                }.flowOn(defaultDispatcher)
    }
