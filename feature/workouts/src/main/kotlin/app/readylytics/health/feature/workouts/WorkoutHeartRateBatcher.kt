package app.readylytics.health.feature.workouts

import app.readylytics.health.domain.repository.HeartRateRecordData
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.WorkoutData
import app.readylytics.health.domain.scoring.ComputeWorkoutTrimpUseCase.HeartRateSample
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
    for (cluster in clusterWorkoutsBySpan(workouts)) {
        val spanStart = cluster.minOf { it.startTime }
        val spanEnd = cluster.maxOf { it.endTime }
        val samples = heartRateRepository.getByTimeRange(spanStart, spanEnd)
        for (workout in cluster) {
            result[workout.id] =
                sliceSamplesForWorkout(samples, workout).map {
                    HeartRateSample(timestamp = Instant.ofEpochMilli(it.timestampMs), bpm = it.beatsPerMinute)
                }
        }
    }
    return result
}
