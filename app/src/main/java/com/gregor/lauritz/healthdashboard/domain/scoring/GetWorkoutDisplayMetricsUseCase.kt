package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.display.MetricFormatter
import com.gregor.lauritz.healthdashboard.domain.repository.DailySummaryRepository
import com.gregor.lauritz.healthdashboard.domain.repository.HeartRateRepository
import com.gregor.lauritz.healthdashboard.domain.repository.WorkoutData
import com.gregor.lauritz.healthdashboard.domain.scoring.ComputeWorkoutTrimpUseCase.HeartRateSample
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

class GetWorkoutDisplayMetricsUseCase
    @Inject
    constructor(
        private val dailySummaryRepository: DailySummaryRepository,
        private val heartRateRepository: HeartRateRepository,
        private val settingsRepo: SettingsRepository,
        private val computeWorkoutLoadMetricsUseCase: ComputeWorkoutLoadMetricsUseCase,
    ) {
        suspend fun execute(
            workout: WorkoutData,
            samples: List<HeartRateSample>? = null,
        ): WorkoutDisplayMetrics {
            val zoneId = ZoneId.systemDefault()
            val workoutDate = Instant.ofEpochMilli(workout.startTime).atZone(zoneId).toLocalDate()
            val midnight = workoutDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val summary = dailySummaryRepository.getByDate(midnight)

            val fortyTwoDaysAgo =
                workoutDate
                    .minusDays(ScoringConstants.CHRONIC_DAYS)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()

            val historicalSummaries = dailySummaryRepository.getSince(fortyTwoDaysAgo)
            val trimpByDate = historicalSummaries.associate { it.date to (it.totalTrimp ?: 0f) }

            val prefs = settingsRepo.userPreferences.first()

            val hrSamples =
                samples ?: heartRateRepository
                    .getByTimeRange(workout.startTime, workout.endTime)
                    .map {
                        HeartRateSample(
                            timestamp = Instant.ofEpochMilli(it.timestampMs),
                            bpm = it.beatsPerMinute,
                        )
                    }

            val loadMetrics =
                computeWorkoutLoadMetricsUseCase.execute(
                    workout = workout,
                    workoutDate = workoutDate,
                    samples = hrSamples,
                    prefs = prefs,
                    restingHrBaseline = summary?.rhrBpm,
                    trimpByDate = trimpByDate,
                )

            return WorkoutDisplayMetrics(
                preciseTrimp = loadMetrics.preciseTrimp,
                computedTrimp = loadMetrics.roundedTrimp,
                trimpDisplay = MetricFormatter.formatTrimp(loadMetrics.preciseTrimp),
                gainedStrain = loadMetrics.roundedGainedStrain,
                gainedStrainDisplay = loadMetrics.gainedStrainDisplay,
            )
        }
    }

data class WorkoutDisplayMetrics(
    val preciseTrimp: Float,
    val computedTrimp: Int,
    val trimpDisplay: String,
    val gainedStrain: Float,
    val gainedStrainDisplay: String,
)
