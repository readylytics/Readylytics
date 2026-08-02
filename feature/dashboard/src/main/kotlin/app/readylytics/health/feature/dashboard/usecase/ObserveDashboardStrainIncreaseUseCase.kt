package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.di.DefaultDispatcher
import app.readylytics.health.domain.model.LoadSourceSelector
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.preferences.scoringZone
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.WorkoutRepository
import app.readylytics.health.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.domain.scoring.LoadSourceMode
import app.readylytics.health.domain.scoring.ScoringCalculator
import app.readylytics.health.domain.scoring.ScoringConstants
import app.readylytics.health.domain.scoring.calculateDailyStrainIncrease
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

class ObserveDashboardStrainIncreaseUseCase
    @Inject
    constructor(
        private val workoutRepository: WorkoutRepository,
        private val dailySummaryRepository: DailySummaryRepository,
        private val getWorkoutDisplayMetricsUseCase: GetWorkoutDisplayMetricsUseCase,
        private val scoringCalculator: ScoringCalculator,
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
                    val displayStartDate = date.minusDays(6)
                    val fetchFromMs =
                        displayStartDate
                            .minusDays(ScoringConstants.CHRONIC_DAYS)
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli()
                    val selectedDayStartMs =
                        date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                    val selectedDayEndMs =
                        date
                            .plusDays(1)
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli()

                    combine(
                        workoutRepository.observeSince(fetchFromMs),
                        dailySummaryRepository.observeSince(fetchFromMs),
                    ) { workouts, summaries -> workouts to summaries }
                        .mapLatest { (workouts, summaries) ->
                            val earliestDate =
                                LoadSourceSelector.selectEarliestDataDate(
                                    workouts = workouts,
                                    summaries = summaries,
                                    mode = prefs.strainLoadSourceMode,
                                    zoneId = zoneId,
                                )
                            val dataTenureDays =
                                earliestDate?.let { ChronoUnit.DAYS.between(it, date).toInt() + 1 } ?: 0

                            when (prefs.strainLoadSourceMode) {
                                LoadSourceMode.WORKOUT_ONLY -> {
                                    val workoutOnlyGains =
                                        workouts
                                            .filter {
                                                it.startTime in selectedDayStartMs until selectedDayEndMs
                                            }.map { workout ->
                                                getWorkoutDisplayMetricsUseCase
                                                    .execute(
                                                        workout = workout,
                                                        preferences = prefs,
                                                        historicalSummaries = summaries,
                                                    ).gainedStrain
                                            }
                                    calculateDailyStrainIncrease(
                                        dataTenureDays = dataTenureDays,
                                        loadSourceMode = prefs.strainLoadSourceMode,
                                        workoutOnlyGains = workoutOnlyGains,
                                        strainRatioWithDay = null,
                                        strainRatioWithoutDay = null,
                                    )
                                }

                                LoadSourceMode.EVERYDAY_HEART_RATE -> {
                                    val trimpByDate =
                                        summaries.associate { summary ->
                                            summary.date to
                                                (
                                                    LoadSourceSelector.selectTrimp(
                                                        summary,
                                                        prefs.strainLoadSourceMode,
                                                    ) ?: 0f
                                                )
                                        }
                                    val ctlSeries =
                                        scoringCalculator.computeCtlEmaSeries(
                                            trimpByDate,
                                            displayStartDate,
                                            date,
                                        )
                                    val atlSeries =
                                        scoringCalculator.computeAtlEmaSeries(
                                            trimpByDate,
                                            displayStartDate,
                                            date,
                                        )
                                    val strainRatioWithDay =
                                        scoringCalculator.computeStrainRatio(
                                            atlSeries[date] ?: ScoringConstants.DEFAULT_FITNESS_LEVEL,
                                            ctlSeries[date] ?: ScoringConstants.DEFAULT_FITNESS_LEVEL,
                                        )
                                    val trimpByDateWithoutDay =
                                        trimpByDate.toMutableMap().apply { put(date, 0f) }
                                    val strainRatioWithoutDay =
                                        scoringCalculator.computeStrainRatio(
                                            scoringCalculator.computeAtlEmaWithDecay(
                                                trimpByDateWithoutDay,
                                                date,
                                            ),
                                            scoringCalculator.computeCtlEmaWithDecay(
                                                trimpByDateWithoutDay,
                                                date,
                                            ),
                                        )
                                    calculateDailyStrainIncrease(
                                        dataTenureDays = dataTenureDays,
                                        loadSourceMode = prefs.strainLoadSourceMode,
                                        workoutOnlyGains = emptyList(),
                                        strainRatioWithDay = strainRatioWithDay,
                                        strainRatioWithoutDay = strainRatioWithoutDay,
                                    )
                                }
                            }
                        }
                }.flowOn(defaultDispatcher)
    }
