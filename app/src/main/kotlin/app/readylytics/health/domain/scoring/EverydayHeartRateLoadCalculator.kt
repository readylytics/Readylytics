package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.heartrate.HrZoneClassifier
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.scoring.ComputeWorkoutTrimpUseCase.HeartRateSample
import kotlin.math.roundToInt


/** Half-open interval `[start, end)` — `end` is exclusive. */
data class LongInterval(
    val start: Long,
    val end: Long,
)

data class EverydayHrLoadInput(
    val dayStartMs: Long,
    val dayEndMs: Long,
    val hrSamples: List<HeartRateSample>,
    val sleepIntervalsMs: List<LongInterval>,
    val workoutIntervalsMs: List<LongInterval>,
    val workoutOnlyTrimp: Float,
    val rhrBaseline: Float,
    val hrMax: Float,
    val prefs: UserPreferences,
)

data class EverydayHrLoadResult(
    val nonWorkoutTrimp: Float,
    val totalEverydayTrimp: Float,
    val validBucketCount: Int,
    val coverageMinutes: Int,
    val valid: Boolean,
    val confidence: LoadCoverageConfidence,
)

/**
 * Derives an "everyday heart-rate load" TRIMP value from a day's raw HR samples, excluding
 * sleep/workout windows and bucketing the remaining waking samples into 1-minute windows.
 *
 * Pure Kotlin, zero Android dependencies, deterministic (no wall-clock/random usage).
 */
object EverydayHeartRateLoadCalculator {
    private const val BUCKET_MS = 60_000L
    private const val MIN_PLAUSIBLE_BPM = 30
    private const val MAX_PLAUSIBLE_BPM = 230
    private const val LOW_CONFIDENCE_MAX_MINUTES = 179
    private const val MEDIUM_CONFIDENCE_MAX_MINUTES = 479
    private const val VALID_MIN_COVERAGE_MINUTES = 180

    fun calculate(input: EverydayHrLoadInput): EverydayHrLoadResult {
        val totalBuckets = ((input.dayEndMs - input.dayStartMs) / BUCKET_MS).toInt()

        // 1. Filter implausible samples, then bucket into 1-minute windows.
        val bucketSums = mutableMapOf<Int, Float>()
        val bucketCounts = mutableMapOf<Int, Int>()
        for (sample in input.hrSamples) {
            if (sample.bpm < MIN_PLAUSIBLE_BPM || sample.bpm > MAX_PLAUSIBLE_BPM) continue
            val diff = sample.timestamp.toEpochMilli() - input.dayStartMs
            if (diff < 0) continue
            val bucketIndex = (diff / BUCKET_MS).toInt()
            if (bucketIndex >= totalBuckets) continue
            bucketSums[bucketIndex] = (bucketSums[bucketIndex] ?: 0f) + sample.bpm
            bucketCounts[bucketIndex] = (bucketCounts[bucketIndex] ?: 0) + 1
        }

        var nonWorkoutTrimp = 0f
        var validBucketCount = 0
        var coverageMinutes = 0

        for (bucketIndex in bucketSums.keys.sorted()) {
            val bucketStartMs = input.dayStartMs + bucketIndex * BUCKET_MS
            val bucketEndMs = bucketStartMs + BUCKET_MS

            val isExcluded =
                input.sleepIntervalsMs.any { overlaps(bucketStartMs, bucketEndMs, it) } ||
                    input.workoutIntervalsMs.any { overlaps(bucketStartMs, bucketEndMs, it) }
            if (isExcluded) continue

            val count = bucketCounts[bucketIndex] ?: continue
            if (count <= 0) continue

            coverageMinutes++

            val avgBpm = bucketSums.getValue(bucketIndex) / count
            val zone = HrZoneClassifier.classify(avgBpm.roundToInt(), input.prefs)
            if (zone == 0) continue

            validBucketCount++
            nonWorkoutTrimp +=
                RasCalculator.calculateDailyTrimp(
                    durationMinutes = 1f,
                    hrAvg = avgBpm,
                    rhrBaseline = input.rhrBaseline,
                    hrMax = input.hrMax,
                    gender = input.prefs.gender,
                    trimpModel = input.prefs.trimpModel,
                    banisterMultiplier = input.prefs.banisterMultiplier,
                    chengBeta = input.prefs.chengBeta,
                    itrimB = input.prefs.itrimB,
                    ltBpm = input.prefs.zone3MaxBpm.toFloat(),
                )
        }

        val totalEverydayTrimp = input.workoutOnlyTrimp + nonWorkoutTrimp

        return EverydayHrLoadResult(
            nonWorkoutTrimp = nonWorkoutTrimp,
            totalEverydayTrimp = totalEverydayTrimp,
            validBucketCount = validBucketCount,
            coverageMinutes = coverageMinutes,
            valid = coverageMinutes >= VALID_MIN_COVERAGE_MINUTES,
            confidence = confidenceFor(coverageMinutes),
        )
    }

    private fun overlaps(
        rangeStartMs: Long,
        rangeEndMs: Long,
        interval: LongInterval,
    ): Boolean = rangeStartMs < interval.end && interval.start < rangeEndMs

    private fun confidenceFor(coverageMinutes: Int): LoadCoverageConfidence =
        when {
            coverageMinutes == 0 -> LoadCoverageConfidence.NONE
            coverageMinutes <= LOW_CONFIDENCE_MAX_MINUTES -> LoadCoverageConfidence.LOW
            coverageMinutes <= MEDIUM_CONFIDENCE_MAX_MINUTES -> LoadCoverageConfidence.MEDIUM
            else -> LoadCoverageConfidence.HIGH
        }
}
