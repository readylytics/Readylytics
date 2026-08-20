package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutLoadMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase

import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.model.domain.scoring.ScoringConstants

import app.readylytics.health.core.model.domain.display.MetricFormatter
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.LoadSourceSelector
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.HeartRateRepository
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase.HeartRateSample
import kotlinx.coroutines.flow.first
import java.time.Instant
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
            preferences: UserPreferences? = null,
            historicalSummaries: List<DailySummary>? = null,
        ): WorkoutDisplayMetrics {
            val prefs = preferences ?: settingsRepo.userPreferences.first()
            val zoneId = prefs.scoringZone()
            val workoutDate = Instant.ofEpochMilli(workout.startTime).atZone(zoneId).toLocalDate()
            val midnight = workoutDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val summary = dailySummaryRepository.getByDate(midnight)

            // A caller-supplied window is clamped to the same 42-day span the self-fetch path
            // would have used. Callers hold wider windows (48/71/131 days) than this workout
            // needs, and the ATL/CTL EMA treats the earliest key in `trimpByDate` as an
            // effective start-of-history anchor -- so an unclamped wider list would yield a
            // different gainedStrain for the same workout depending on which screen asked.
            val resolvedHistoricalSummaries =
                historicalSummaries?.filter {
                    !it.date.isBefore(workoutDate.minusDays(ScoringConstants.CHRONIC_DAYS))
                } ?: run {
                    val fortyTwoDaysAgo =
                        workoutDate
                            .minusDays(ScoringConstants.CHRONIC_DAYS)
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli()
                    dailySummaryRepository.getSince(fortyTwoDaysAgo)
                }
            val trimpByDate =
                resolvedHistoricalSummaries.associate {
                    it.date to (LoadSourceSelector.selectTrimp(it, prefs.strainLoadSourceMode) ?: 0f)
                }

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
                classification = loadMetrics.classification,
            )
        }
    }

data class WorkoutDisplayMetrics(
    val preciseTrimp: Float,
    val computedTrimp: Int,
    val trimpDisplay: String,
    val gainedStrain: Float,
    val gainedStrainDisplay: String,
    val classification: WorkoutLoadClassification?,
)
