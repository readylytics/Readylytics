package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.database.data.local.reconstructTimestampedSamples
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

@Singleton
class ScoringHeartRateDataLoader
    @Inject
    constructor(
        private val heartRateDao: HeartRateDao,
        private val minuteBucketDao: MinuteBucketDao,
    ) {
        suspend fun loadExerciseHrSamples(workouts: List<WorkoutRecordEntity>): List<HeartRateRecordEntity> {
            if (workouts.isEmpty()) return emptyList()
            return fetchExerciseHrInRange(workouts.minOf { it.startTime }, workouts.maxOf { it.endTime })
        }

        private suspend fun fetchExerciseHrInRange(startMs: Long, endMs: Long): List<HeartRateRecordEntity> =
            heartRateDao.getByTypeAndTimeRange(RecordType.EXERCISE.name, startMs, endMs)

        suspend fun loadWorkoutSamplesWithQuality(
            workout: WorkoutRecordEntity,
            hotSamples: List<HeartRateRecordEntity>,
        ): LoadedWorkoutSamples {
            val hot = hotSamples.filter { it.timestampMs in workout.startTime..workout.endTime }
            val warm = fetchWorkoutSamplesFromBuckets(workout)
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

        private suspend fun fetchWorkoutSamplesFromBuckets(
            workout: WorkoutRecordEntity,
        ): List<HeartRateRecordEntity> {
            val samples =
                minuteBucketDao
                    .getBucketsForSession("EXERCISE", workout.id)
                    .reconstructTimestampedSamples()
            if (samples.isEmpty) return emptyList()
            return buildList(samples.size) {
                samples.forEachIndexed { _, timestampMs, bpm ->
                    add(
                        HeartRateRecordEntity(
                            sourceRecordRef = 0L,
                            timestampMs = timestampMs,
                            beatsPerMinute = bpm,
                            recordType = RecordType.EXERCISE.name,
                            sessionId = workout.id,
                        ),
                    )
                }
            }
        }

        suspend fun loadMergedMinuteBuckets(dayStartMs: Long, dayEndMs: Long): List<HrMinuteBucketRow> {
            val hot = queryHotMinuteBuckets(dayStartMs, dayEndMs)
            val warm = queryWarmMinuteBuckets(dayStartMs, dayEndMs)
            return when {
                warm.isEmpty() -> hot
                hot.isEmpty() -> warm
                else -> mergeMinuteBuckets(hot, warm)
            }
        }

        private suspend fun queryHotMinuteBuckets(dayStartMs: Long, dayEndMs: Long): List<HrMinuteBucketRow> =
            heartRateDao.getMinuteBuckets(dayStartMs, dayEndMs)

        private suspend fun queryWarmMinuteBuckets(dayStartMs: Long, dayEndMs: Long): List<HrMinuteBucketRow> =
            minuteBucketDao.getMinuteBuckets(dayStartMs, dayEndMs)
    }

private fun mergeMinuteBuckets(
    hot: List<HrMinuteBucketRow>,
    warm: List<HrMinuteBucketRow>,
): List<HrMinuteBucketRow> {
    val acc = LinkedHashMap<Int, Pair<Double, Int>>()
    fun add(row: HrMinuteBucketRow) {
        val prev = acc[row.bucketIndex]
        acc[row.bucketIndex] =
            if (prev == null) {
                row.avgBpm * row.sampleCount to row.sampleCount
            } else {
                (prev.first + row.avgBpm * row.sampleCount) to (prev.second + row.sampleCount)
            }
    }
    hot.forEach(::add)
    warm.forEach(::add)
    return acc.entries
        .sortedBy { it.key }
        .map { (idx, value) -> HrMinuteBucketRow(idx, value.first / value.second, value.second) }
}
