package com.gregor.lauritz.healthdashboard.ui.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.healthconnect.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

        fun loadWorkout(workoutId: String) {
            viewModelScope.launch {
                val workout = workoutDao.getById(workoutId) ?: return@launch

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

                // Calculate HRR
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

                if (endHr != null) {
                    val hrByMinute = (1..3).map { minute ->
                        val to = workoutEndInstant.plus(minute.toLong(), ChronoUnit.MINUTES)
                        allSamples.lastOrNull { it.timestamp > workoutEndInstant && it.timestamp <= to }?.bpm
                    }
                    val (hr1Min, hr2Min, hr3Min) = hrByMinute
                    _uiState.value =
                        WorkoutDetailUiState(
                            workout = workout,
                            hrSamples = allSamples,
                            hrr1Min = hr1Min?.let { endHr - it },
                            hrr2Min = hr2Min?.let { endHr - it },
                            hrr3Min = hr3Min?.let { endHr - it },
                            totalPai = summary?.totalPai,
                            paiDailyBreakdown = paiBreakdown,
                            isLoading = false,
                        )
                } else {
                    _uiState.value =
                        WorkoutDetailUiState(
                            workout = workout,
                            hrSamples = allSamples,
                            totalPai = summary?.totalPai,
                            paiDailyBreakdown = paiBreakdown,
                            isLoading = false,
                        )
                }
            }
        }
    }
