package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.display.MetricFormatter
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.LoadSourceSelector
import app.readylytics.health.core.model.domain.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.HeartRateRepository
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.scoring.CanonicalWorkoutResult
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.model.domain.scoring.WorkoutHrQuality
import app.readylytics.health.core.model.domain.scoring.WorkoutScoringIdentity
import app.readylytics.health.core.model.domain.sync.HistoricalRunIdentity
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase.HeartRateSample
import app.readylytics.health.core.scoring.domain.util.HeartRateFormulas
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

class GetWorkoutDisplayMetricsUseCase
    @Inject
    constructor(
        private val dailySummaryRepository: DailySummaryRepository,
        private val heartRateRepository: HeartRateRepository,
        private val settingsRepo: SettingsRepository,
        private val computeWorkoutLoadMetricsUseCase: ComputeWorkoutLoadMetricsUseCase,
        private val canonicalWorkoutResolver: CanonicalWorkoutResolver,
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

            val trimpByDate = resolveTrimpByDate(historicalSummaries, workoutDate, zoneId, prefs)
            val hrSamples =
                samples ?: heartRateRepository
                    .getByTimeRange(workout.startTime, workout.endTime)
                    .map {
                        HeartRateSample(
                            timestamp = Instant.ofEpochMilli(it.timestampMs),
                            bpm = it.beatsPerMinute,
                        )
                    }

            val frozenHrMax = summary?.hrMax ?: HeartRateFormulas.resolveMaxHeartRate(prefs)
            val rhrBaseline = summary?.rhrBpm ?: prefs.rhrBaselineOverride ?: ScoringConstants.DEFAULT_RHR_BPM
            val scoringSnapshotId = HistoricalRunIdentity.computeSnapshotId(prefs, frozenHrMax)
            val identity =
                WorkoutScoringIdentity(
                    sourceRevision = workout.modelTrimpSourceRevision ?: 0L,
                    scoringSnapshotId = scoringSnapshotId,
                    algorithmRevision = SettingsDefaults.CURRENT_SCORING_VERSION,
                )
            val scoringContext =
                WorkoutScoringContext(
                    prefs = prefs,
                    rhrBaseline = rhrBaseline,
                    frozenHrMax = frozenHrMax,
                    identity = identity,
                )

            val canonicalInput = buildCanonicalInput(workout, hrSamples, scoringContext)
            val canonicalResult = canonicalWorkoutResolver.resolve(canonicalInput)

            val loadMetrics =
                computeWorkoutLoadMetricsUseCase.execute(
                    workout = workout,
                    workoutDate = workoutDate,
                    canonicalResult = canonicalResult,
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

        private suspend fun resolveTrimpByDate(
            historicalSummaries: List<DailySummary>?,
            workoutDate: LocalDate,
            zoneId: ZoneId,
            prefs: UserPreferences,
        ): Map<LocalDate, Float> {
            val resolvedSummaries =
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
            return resolvedSummaries.associate {
                it.date to (LoadSourceSelector.selectTrimp(it, prefs.strainLoadSourceMode) ?: 0f)
            }
        }

        private fun hasCompleteModelTrimpMetadata(workout: WorkoutData): Boolean {
            val hasRevision = workout.modelTrimpQuality != null && workout.modelTrimpSourceRevision != null
            val hasSnapshot = workout.modelTrimpSnapshotId != null && workout.modelTrimpAlgorithmRevision != null
            return hasRevision && hasSnapshot
        }

        private fun extractPriorResult(workout: WorkoutData): CanonicalWorkoutResult? {
            if (!hasCompleteModelTrimpMetadata(workout)) return null
            val qualityStr = checkNotNull(workout.modelTrimpQuality)
            val parsedQuality =
                runCatching { WorkoutHrQuality.valueOf(qualityStr) }
                    .getOrDefault(WorkoutHrQuality.UNAVAILABLE)
            return CanonicalWorkoutResult(
                workoutId = workout.id,
                endTimeMs = workout.endTime,
                trimp = workout.modelTrimp,
                quality = parsedQuality,
                sourceRevision = checkNotNull(workout.modelTrimpSourceRevision),
                scoringSnapshotId = checkNotNull(workout.modelTrimpSnapshotId),
                algorithmRevision = checkNotNull(workout.modelTrimpAlgorithmRevision),
            )
        }

        private fun buildCanonicalInput(
            workout: WorkoutData,
            hrSamples: List<HeartRateSample>,
            scoringContext: WorkoutScoringContext,
        ): CanonicalWorkoutInput =
            CanonicalWorkoutInput(
                workoutId = workout.id,
                startMs = workout.startTime,
                endMs = workout.endTime,
                samples = hrSamples,
                quality = WorkoutHrQuality.RAW,
                context = scoringContext,
                prior = extractPriorResult(workout),
            )
    }

data class WorkoutDisplayMetrics(
    val preciseTrimp: Float?,
    val computedTrimp: Int?,
    val trimpDisplay: String,
    val gainedStrain: Float?,
    val gainedStrainDisplay: String,
    val classification: WorkoutLoadClassification?,
)
