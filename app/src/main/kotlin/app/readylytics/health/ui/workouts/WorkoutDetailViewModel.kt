package app.readylytics.health.ui.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.domain.model.LoadSourceSelector
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.WorkoutData
import app.readylytics.health.domain.repository.WorkoutRepository
import app.readylytics.health.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.domain.scoring.RasCalculator
import app.readylytics.health.ui.workouts.mappers.ChartDataMapper
import app.readylytics.health.ui.workouts.mappers.DailyRasBreakdownMapper
import app.readylytics.health.ui.workouts.mappers.RecoveryMetricsMapper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class HeartRatePoint(
    val timestamp: Instant,
    val bpm: Int,
)

data class WorkoutDetailUiState(
    val workout: WorkoutData? = null,
    val hrSamples: List<HeartRatePoint> = emptyList(),
    val hrChartData: List<Pair<Double, Double>> = emptyList(),
    val durationMinutes: Int = 0,
    val hrr1Min: Int? = null,
    val hrr2Min: Int? = null,
    val hrr3Min: Int? = null,
    val totalRas: Float? = null,
    val rasDailyBreakdown: List<Pair<String, Float>> = emptyList(),
    val computedTrimp: Int? = null,
    val gainedStrain: Float? = null,
    val gainedStrainDisplay: String = "—",
    val ras: Float? = null,
    val isLoading: Boolean = true,
)

@HiltViewModel
class WorkoutDetailViewModel
    @Inject
    constructor(
        private val workoutRepository: WorkoutRepository,
        private val hcRepo: HealthConnectRepository,
        private val heartRateRepository: HeartRateRepository,
        private val dailySummaryRepository: DailySummaryRepository,
        private val settingsRepo: UserPreferencesReader,
        private val getWorkoutDisplayMetricsUseCase: GetWorkoutDisplayMetricsUseCase,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(WorkoutDetailUiState())
        val uiState = _uiState.asStateFlow()

        fun loadWorkout(workoutId: String) {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }
                val workout = workoutRepository.getById(workoutId)
                if (workout == null) {
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }

                val start = Instant.ofEpochMilli(workout.startTime)
                val end = Instant.ofEpochMilli(workout.endTime)
                val endPlus3Min = end.plus(3, ChronoUnit.MINUTES)

                val hcSamples =
                    hcRepo
                        .readHeartRateSamples(start, endPlus3Min)
                        .asSequence()
                        .flatMap { record ->
                            record.samples.map { HeartRatePoint(it.time, it.beatsPerMinute) }
                        }.toList()
                val dbSamples =
                    heartRateRepository
                        .getByTimeRange(start.toEpochMilli(), endPlus3Min.toEpochMilli())
                        .map { HeartRatePoint(Instant.ofEpochMilli(it.timestampMs), it.beatsPerMinute) }
                val allSamples =
                    (hcSamples + dbSamples)
                        .distinctBy { it.timestamp }
                        .sortedBy { it.timestamp }

                val (chartData, durationMinutes) =
                    ChartDataMapper.mapToChartData(allSamples, workout.startTime, workout.endTime)

                val workoutEndInstant = Instant.ofEpochMilli(workout.endTime)
                val endHr = allSamples.lastOrNull { it.timestamp <= workoutEndInstant }?.bpm

                val workoutDate = start.atZone(ZoneId.systemDefault()).toLocalDate()
                val midnight = workoutDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val summary = dailySummaryRepository.getByDate(midnight)

                val thirtyDaysAgo =
                    workoutDate
                        .minusDays(30)
                        .atStartOfDay(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                val thirtyDaySummaries = dailySummaryRepository.getSince(thirtyDaysAgo)

                val prefs = settingsRepo.userPreferences.first()
                val rasBreakdown =
                    DailyRasBreakdownMapper.mapDailyBreakdown(workoutDate, thirtyDaySummaries, prefs.rasSourceMode)

                val recoveryMetrics = RecoveryMetricsMapper.mapRecoveryMetrics(allSamples, workout.endTime, endHr)

                val workoutSamples = dbSamples.filter { it.timestamp <= workoutEndInstant }
                val displayMetrics =
                    getWorkoutDisplayMetricsUseCase.execute(
                        workout = workout,
                        samples =
                            workoutSamples.map {
                                app.readylytics.health.domain.scoring.ComputeWorkoutTrimpUseCase
                                    .HeartRateSample(
                                        it.timestamp,
                                        it.bpm,
                                    )
                            },
                    )

                _uiState.update {
                    it.copy(
                        workout = workout,
                        hrSamples = allSamples,
                        hrChartData = chartData,
                        durationMinutes = durationMinutes,
                        hrr1Min = recoveryMetrics.hrr1Min,
                        hrr2Min = recoveryMetrics.hrr2Min,
                        hrr3Min = recoveryMetrics.hrr3Min,
                        totalRas = summary?.let { LoadSourceSelector.selectTotalRas(it, prefs.rasSourceMode) },
                        rasDailyBreakdown = rasBreakdown,
                        computedTrimp = displayMetrics.computedTrimp.takeIf { trimp -> trimp > 0 },
                        gainedStrain = displayMetrics.gainedStrain,
                        gainedStrainDisplay = displayMetrics.gainedStrainDisplay,
                        ras = RasCalculator.calculateDailyRas(displayMetrics.preciseTrimp, prefs.rasScalingFactor),
                        isLoading = false,
                    )
                }
            }
        }
    }
