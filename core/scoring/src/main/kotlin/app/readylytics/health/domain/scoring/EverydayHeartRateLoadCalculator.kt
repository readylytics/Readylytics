package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.heartrate.HrZoneClassifier
import app.readylytics.health.domain.model.HrMinuteBucketRow
import app.readylytics.health.domain.preferences.UserPreferences
import kotlin.math.roundToInt


/** Half-open interval `[start, end)` — `end` is exclusive. */
data class LongInterval(
    val start: Long,
    val end: Long,
)

data class EverydayHrLoadInput(
    val dayStartMs: Long,
    val dayEndMs: Long,
    val hrBuckets: List<HrMinuteBucketRow>,
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
 * Derives an "everyday heart-rate load" TRIMP value from a day's already-bucketed, already
 * plausibility-filtered 1-minute HR averages (PERF-006/WP-21: `HeartRateDao.getMinuteBuckets`
 * performs both the 30-230 bpm plausibility filter and the per-minute averaging in SQL), excluding
 * any bucket overlapping a sleep or workout window.
 *
 * Pure Kotlin, zero Android dependencies, deterministic (no wall-clock/random usage).
 */
object EverydayHeartRateLoadCalculator {
    private const val BUCKET_MS = 60_000L
    private const val LOW_CONFIDENCE_MAX_MINUTES = 179
    private const val MEDIUM_CONFIDENCE_MAX_MINUTES = 479
    private const val VALID_MIN_COVERAGE_MINUTES = 180

    fun calculate(input: EverydayHrLoadInput): EverydayHrLoadResult {
        val totalBuckets = ((input.dayEndMs - input.dayStartMs) / BUCKET_MS).toInt()

        var nonWorkoutTrimp = 0f
        var validBucketCount = 0
        var coverageMinutes = 0

        for (bucket in input.hrBuckets) {
            if (bucket.sampleCount <= 0) continue
            if (bucket.bucketIndex < 0 || bucket.bucketIndex >= totalBuckets) continue

            val bucketStartMs = input.dayStartMs + bucket.bucketIndex * BUCKET_MS
            val bucketEndMs = bucketStartMs + BUCKET_MS

            val isExcluded =
                input.sleepIntervalsMs.any { overlaps(bucketStartMs, bucketEndMs, it) } ||
                    input.workoutIntervalsMs.any { overlaps(bucketStartMs, bucketEndMs, it) }
            if (isExcluded) continue

            coverageMinutes++

            val avgBpm = bucket.avgBpm.toFloat()
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
