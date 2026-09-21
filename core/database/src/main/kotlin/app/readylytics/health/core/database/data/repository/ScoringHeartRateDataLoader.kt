package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.database.data.local.AuthoritativeHeartRateReader
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.domain.model.HrMinuteBucketRow
import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.scoring.WorkoutHrQuality
import javax.inject.Inject
import javax.inject.Singleton

data class LoadedWorkoutSamples(
    val samples: List<HeartRateRecordEntity>,
    val quality: WorkoutHrQuality,
)

/**
 * Scoring-side heart-rate loader for workout metrics and the everyday-HR load calculator.
 *
 * WP-17 Step 3: every read here goes through [AuthoritativeHeartRateReader], so the hot and warm
 * contributions it concatenates are guaranteed to describe disjoint minutes. Before that, a minute
 * quarantined by the OD-1 legacy rule (raw rows deliberately retained below the hot/warm cutoff
 * alongside a `LEGACY_*` coverage row) would have been counted twice -- once as raw samples and
 * again as its warm projection -- inflating both the per-minute mean and the TRIMP that minute
 * contributes. The merge below is retained as a defensive weighted identity; with the coverage
 * predicate applied it reduces to a plain union.
 */
@Singleton
class ScoringHeartRateDataLoader
    @Inject
    constructor(
        private val authoritativeReader: AuthoritativeHeartRateReader,
    ) {
        /**
         * Fixture convenience: assembles the reader from the two DAOs a Room-backed test already
         * has, so the many existing call sites across the test suite keep compiling without each
         * one re-deriving the collaborator graph. Production DI always uses the `@Inject`
         * constructor above and injects the shared singleton. Same rationale as
         * `ScoringHistoryRepositoryImpl`'s defaulted `scoringCalculator`.
         */
        constructor(
            heartRateDao: HeartRateDao,
            minuteBucketDao: MinuteBucketDao,
        ) : this(AuthoritativeHeartRateReader(heartRateDao, minuteBucketDao))

        suspend fun loadExerciseHrSamples(workouts: List<WorkoutRecordEntity>): List<HeartRateRecordEntity> {
            if (workouts.isEmpty()) return emptyList()
            return authoritativeReader.rawByTypeInRange(
                RecordType.EXERCISE.name,
                workouts.minOf { it.startTime },
                workouts.maxOf { it.endTime },
            )
        }

        suspend fun loadWorkoutSamplesWithQuality(
            workout: WorkoutRecordEntity,
            hotSamples: List<HeartRateRecordEntity>,
        ): LoadedWorkoutSamples {
            val hot = hotSamples.filter { it.timestampMs in workout.startTime..workout.endTime }
            val warm = authoritativeReader.warmSessionSamples(RecordType.EXERCISE.name, workout.id)
            val quality =
                when {
                    warm.isNotEmpty() -> WorkoutHrQuality.WARM_APPROXIMATE
                    hot.isNotEmpty() -> WorkoutHrQuality.RAW
                    else -> WorkoutHrQuality.UNAVAILABLE
                }
            val samples = if (warm.isEmpty()) hot else (hot + warm).sortedBy { it.timestampMs }
            return LoadedWorkoutSamples(samples = samples, quality = quality)
        }

        suspend fun loadWorkoutSamples(
            workout: WorkoutRecordEntity,
            hotSamples: List<HeartRateRecordEntity>,
        ): List<HeartRateRecordEntity> = loadWorkoutSamplesWithQuality(workout, hotSamples).samples

        suspend fun loadMergedMinuteBuckets(dayStartMs: Long, dayEndMs: Long): List<HrMinuteBucketRow> =
            authoritativeReader.minuteBuckets(dayStartMs, dayEndMs)
    }
