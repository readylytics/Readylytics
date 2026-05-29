package com.gregor.lauritz.healthdashboard.ui.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.model.getOrNull
import com.gregor.lauritz.healthdashboard.domain.repository.DailySummaryRepository
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.domain.repository.HeartRateRepository
import com.gregor.lauritz.healthdashboard.domain.repository.WorkoutData
import com.gregor.lauritz.healthdashboard.domain.repository.WorkoutRepository
import com.gregor.lauritz.healthdashboard.ui.workouts.mappers.ChartDataMapper
import com.gregor.lauritz.healthdashboard.ui.workouts.mappers.DailyPaiBreakdownMapper
import com.gregor.lauritz.healthdashboard.ui.workouts.mappers.RecoveryMetricsMapper
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
import kotlin.math.roundToInt

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
    val totalPai: Float? = null,
    val paiDailyBreakdown: List<Pair<String, Float>> = emptyList(),
    val computedTrimp: Int? = null,
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
        private val settingsRepo: SettingsRepository,
        private val computeWorkoutTrimpUseCase:
            com.gregor.lauritz.healthdashboard.domain.scoring.ComputeWorkoutTrimpUseCase,
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
                            record.samples.map { HeartRatePoint(it.time, it.beatsPerMinute.toInt()) }
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

                val sevenDaysAgo =
                    workoutDate
                        .minusDays(6)
                        .atStartOfDay(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                val summaries = dailySummaryRepository.getSince(sevenDaysAgo)
                val paiBreakdown = DailyPaiBreakdownMapper.mapDailyBreakdown(workoutDate, summaries)

                val recoveryMetrics = RecoveryMetricsMapper.mapRecoveryMetrics(allSamples, workout.endTime, endHr)

                val prefs = settingsRepo.userPreferences.first()
                val workoutSamples = allSamples.filter { it.timestamp <= workoutEndInstant }

                val computedTrimpResult =
                    computeWorkoutTrimpUseCase.execute(
                        workoutStartTime = workout.startTime,
                        workoutEndTime = workout.endTime,
                        workoutAvgHr = workout.avgHr,
                        samples =
                            workoutSamples.map {
                                com.gregor.lauritz.healthdashboard.domain.scoring.ComputeWorkoutTrimpUseCase
                                    .HeartRateSample(
                                        it.timestamp,
                                        it.bpm,
                                    )
                            },
                        prefs = prefs,
                        restingHrBaseline = summary?.restingHrBaseline?.toFloat(),
                    )
                val computedTrimp = computedTrimpResult.getOrNull() ?: 0f

                _uiState.update {
                    it.copy(
                        workout = workout,
                        hrSamples = allSamples,
                        hrChartData = chartData,
                        durationMinutes = durationMinutes,
                        hrr1Min = recoveryMetrics.hrr1Min,
                        hrr2Min = recoveryMetrics.hrr2Min,
                        hrr3Min = recoveryMetrics.hrr3Min,
                        totalPai = summary?.totalPai,
                        paiDailyBreakdown = paiBreakdown,
                        computedTrimp = computedTrimp.roundToInt().takeIf { it > 0 },
                        isLoading = false,
                    )
                }
            }
        }
    }
