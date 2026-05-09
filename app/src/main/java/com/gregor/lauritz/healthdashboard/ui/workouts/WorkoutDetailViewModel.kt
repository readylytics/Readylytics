package com.gregor.lauritz.healthdashboard.ui.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.healthconnect.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject

data class HeartRatePoint(
    val timestamp: Instant,
    val bpm: Int,
)

data class WorkoutDetailUiState(
    val workout: WorkoutRecordEntity? = null,
    val hrSamples: List<HeartRatePoint> = emptyList(),
    val hrChartData: List<Pair<Double, Double>> = emptyList(),
    val durationMinutes: Int = 0,
    val hrr1Min: Int? = null,
    val hrr2Min: Int? = null,
    val hrr3Min: Int? = null,
    val totalPai: Float? = null,
    val paiDailyBreakdown: List<Pair<String, Float>> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class WorkoutDetailViewModel
    @Inject
    constructor(
        private val workoutDao: WorkoutDao,
        private val hcRepo: HealthConnectRepository,
        private val heartRateDao: HeartRateDao,
        private val dailySummaryDao: DailySummaryDao,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(WorkoutDetailUiState())
        val uiState = _uiState.asStateFlow()

        private fun computeChartData(
            samples: List<HeartRatePoint>,
            workoutStart: Long,
            workoutEnd: Long,
        ): Pair<List<Pair<Double, Double>>, Int> {
            val workoutStartInstant = Instant.ofEpochMilli(workoutStart)
            val workoutEndInstant = Instant.ofEpochMilli(workoutEnd)
            val workoutSamples = samples.filter { it.timestamp in workoutStartInstant..workoutEndInstant }
            val durationMinutes = ChronoUnit.MINUTES.between(workoutStartInstant, workoutEndInstant)
                .toInt()
                .coerceAtLeast(1)

            val chartData = workoutSamples
                .groupBy {
                    (ChronoUnit.SECONDS.between(workoutStartInstant, it.timestamp) / 60L).toInt()
                }
                .toSortedMap()
                .map { (minute, points) ->
                    minute.toDouble() to points.map { it.bpm.toDouble() }.average()
                }

            return chartData to durationMinutes
        }

        fun loadWorkout(workoutId: String) {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }
                val workout = workoutDao.getById(workoutId)
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
                    heartRateDao
                        .getByTimeRange(start.toEpochMilli(), endPlus3Min.toEpochMilli())
                        .map { HeartRatePoint(Instant.ofEpochMilli(it.timestampMs), it.beatsPerMinute) }
                val allSamples =
                    (hcSamples + dbSamples)
                        .distinctBy { it.timestamp }
                        .sortedBy { it.timestamp }

                val (chartData, durationMinutes) = computeChartData(allSamples, workout.startTime, workout.endTime)

                val workoutEndInstant = Instant.ofEpochMilli(workout.endTime)
                val endHr = allSamples.lastOrNull { it.timestamp <= workoutEndInstant }?.bpm

                val workoutDate = start.atZone(ZoneId.systemDefault()).toLocalDate()
                val midnight = workoutDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val summary = dailySummaryDao.getByDate(midnight)

                val sevenDaysAgo = workoutDate.minusDays(6).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val summaries = dailySummaryDao.getSince(sevenDaysAgo)
                val summaryByDate = summaries.associateBy { it.dateMidnightMs }
                val paiBreakdown = (6 downTo 0).map { daysBack ->
                    val day = workoutDate.minusDays(daysBack.toLong())
                    val dayMs = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val label = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    val pai = summaryByDate[dayMs]?.paiScore ?: 0f
                    label to pai
                }

                var hr1Min: Int? = null
                var hr2Min: Int? = null
                var hr3Min: Int? = null

                if (endHr != null) {
                    val recoverySamples = allSamples.filter { it.timestamp > workoutEndInstant }
                    
                    fun findRecoveryHr(minutes: Int): Int? {
                        val target = workoutEndInstant.plus(minutes.toLong(), ChronoUnit.MINUTES)
                        val toleranceSeconds = ScoringConstants.Workout.HRR_TOLERANCE_SECONDS
                        return recoverySamples
                            .filter { sample -> 
                                val diff = java.time.Duration.between(sample.timestamp, target).abs().seconds
                                diff <= toleranceSeconds
                            }
                            .minByOrNull { sample -> 
                                java.time.Duration.between(sample.timestamp, target).abs().toMillis()
                            }?.bpm
                    }

                    hr1Min = findRecoveryHr(1)
                    hr2Min = findRecoveryHr(2)
                    hr3Min = findRecoveryHr(3)
                }

                _uiState.update {
                    it.copy(
                        workout = workout,
                        hrSamples = allSamples,
                        hrChartData = chartData,
                        durationMinutes = durationMinutes,
                        hrr1Min = if (endHr != null && hr1Min != null) endHr - hr1Min else null,
                        hrr2Min = if (endHr != null && hr2Min != null) endHr - hr2Min else null,
                        hrr3Min = if (endHr != null && hr3Min != null) endHr - hr3Min else null,
                        totalPai = summary?.totalPai,
                        paiDailyBreakdown = paiBreakdown,
                        isLoading = false,
                    )
                }
            }
        }
    }
