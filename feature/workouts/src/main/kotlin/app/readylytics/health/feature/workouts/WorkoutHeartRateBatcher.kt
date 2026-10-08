package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.repository.HeartRateRecordData
import app.readylytics.health.core.model.domain.repository.HeartRateRepository
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase.HeartRateSample
import kotlinx.coroutines.yield
import java.time.Instant
import java.util.concurrent.TimeUnit

// Bounds a single batched getByTimeRange query on a wide/sparse display range (e.g. the 180-day
// tab): workouts are clustered so no one fetch's [minStart, maxEnd] span exceeds this, which caps
// how much continuous everyday-HR data one dragnet query can pull in, while still collapsing the
// common dense-cadence case (workouts close together in time) into a single query per cluster.
internal val CLUSTER_SPAN_GUARD_MS = TimeUnit.DAYS.toMillis(45)

/**
 * Greedily groups [workouts] (sorted by start time) so each cluster's own start-to-end span stays
 * within [spanGuardMs]. Overlapping or closely-spaced workouts land in the same cluster.
 */
internal fun clusterWorkoutsBySpan(
    workouts: List<WorkoutData>,
    spanGuardMs: Long = CLUSTER_SPAN_GUARD_MS,
): List<List<WorkoutData>> {
    if (workouts.isEmpty()) return emptyList()
    val sorted = workouts.sortedBy { it.startTime }
    val clusters = mutableListOf<MutableList<WorkoutData>>()
    var clusterStart = sorted[0].startTime
    var clusterEnd = sorted[0].endTime
    var current = mutableListOf(sorted[0])
    for (workout in sorted.drop(1)) {
        val candidateEnd = maxOf(clusterEnd, workout.endTime)
        if (candidateEnd - clusterStart <= spanGuardMs) {
            current.add(workout)
            clusterEnd = candidateEnd
        } else {
            clusters.add(current)
            current = mutableListOf(workout)
            clusterStart = workout.startTime
            clusterEnd = workout.endTime
        }
    }
    clusters.add(current)
    return clusters
}

/**
 * Slices [sortedSamples] (ascending by timestamp, as returned by
 * [HeartRateRepository.getByTimeRange]) down to the inclusive `[workout.startTime,
 * workout.endTime]` sub-range for [workout], matching the DAO's own `>= AND <=` bounds. Since the
 * batched fetch and a narrower per-workout fetch share the same ORDER BY, this sublist is
 * order-identical to what a per-workout query would return -- including overlapping workouts,
 * which each independently slice their own (possibly overlapping) sub-range.
 */
internal fun sliceSamplesForWorkout(
    sortedSamples: List<HeartRateRecordData>,
    workout: WorkoutData,
): List<HeartRateRecordData> {
    val startIdx = sortedSamples.lowerBoundAtLeast(workout.startTime)
    val endIdx = sortedSamples.upperBoundAtMost(workout.endTime)
    return if (startIdx <= endIdx) sortedSamples.subList(startIdx, endIdx + 1) else emptyList()
}

/** Index of the first element with timestampMs >= [value] (size if none). */
private fun List<HeartRateRecordData>.lowerBoundAtLeast(value: Long): Int {
    var lo = 0
    var hi = size
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (this[mid].timestampMs < value) lo = mid + 1 else hi = mid
    }
    return lo
}

/** Index of the last element with timestampMs <= [value] (-1 if none). */
private fun List<HeartRateRecordData>.upperBoundAtMost(value: Long): Int {
    var lo = 0
    var hi = size
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (this[mid].timestampMs <= value) lo = mid + 1 else hi = mid
    }
    return lo - 1
}

/**
 * Batches [HeartRateRepository.getByTimeRange] across all [workouts]: one fetch per
 * [clusterWorkoutsBySpan] cluster instead of one per workout, then partitions each cluster's
 * samples per workout via [sliceSamplesForWorkout]. Output is element-identical to running
 * getByTimeRange once per workout.
 */
internal suspend fun fetchHeartRateSamplesByWorkout(
    workouts: List<WorkoutData>,
    heartRateRepository: HeartRateRepository,
): Map<String, List<HeartRateSample>> {
    if (workouts.isEmpty()) return emptyMap()
    val result = mutableMapOf<String, List<HeartRateSample>>()
    val pending = ArrayDeque(clusterWorkoutsBySpan(workouts))
    while (pending.isNotEmpty()) {
        yield()
        val cluster = pending.removeFirst()
        val spanStart = cluster.minOf { it.startTime }
        val spanEnd = cluster.maxOf { it.endTime }
        val count = heartRateRepository.countInRangeOfType(RecordType.EXERCISE.name, spanStart, spanEnd)
        if (count > MAX_CLUSTER_SAMPLES && cluster.size > 1) {
            val middle = cluster.size / 2
            pending.addFirst(cluster.subList(middle, cluster.size))
            pending.addFirst(cluster.subList(0, middle))
        } else if (count > MAX_CLUSTER_SAMPLES) {
            val workout = cluster.single()
            val samples = ArrayList<HeartRateSample>()
            heartRateRepository.forEachByTimeRangeOfTypePage(
                RecordType.EXERCISE.name,
                spanStart,
                spanEnd,
                MAX_CLUSTER_SAMPLES,
            ) { page ->
                samples.addAll(sliceSamplesForWorkout(page, workout).map { it.toWorkoutSample() })
            }
            result[workout.id] = samples
        } else {
            val samples = heartRateRepository.getByTimeRangeOfType(RecordType.EXERCISE.name, spanStart, spanEnd)
            for (workout in cluster) {
                result[workout.id] = sliceSamplesForWorkout(samples, workout).map { it.toWorkoutSample() }
            }
        }
    }
    return result
}

internal const val MAX_CLUSTER_SAMPLES = 50_000

private fun HeartRateRecordData.toWorkoutSample() =
    HeartRateSample(timestamp = Instant.ofEpochMilli(timestampMs), bpm = beatsPerMinute)
