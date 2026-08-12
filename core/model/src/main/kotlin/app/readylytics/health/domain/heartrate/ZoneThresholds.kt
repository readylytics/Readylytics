package app.readylytics.health.domain.heartrate

import app.readylytics.health.domain.model.DomainHeartRateSample

data class WorkoutMetrics(
    val durationMinutes: Int,
    val zoneMinutes: FloatArray,
    val trimp: Float,
    val avgHr: Float,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as WorkoutMetrics
        if (durationMinutes != other.durationMinutes) return false
        if (!zoneMinutes.contentEquals(other.zoneMinutes)) return false
        if (trimp != other.trimp) return false
        if (avgHr != other.avgHr) return false
        return true
    }

    override fun hashCode(): Int {
        var result = durationMinutes
        result = 31 * result + zoneMinutes.contentHashCode()
        result = 31 * result + trimp.hashCode()
        result = 31 * result + avgHr.hashCode()
        return result
    }
}

object ZoneThresholds {
    private val ZONE_WEIGHTS = floatArrayOf(1f, 2f, 3f, 4f, 5f)

    fun zoneThresholds(
        z1Min: Int = 95,
        z1Max: Int = 114,
        z2Max: Int = 133,
        z3Max: Int = 152,
        z4Max: Int = 171,
    ): IntArray =
        intArrayOf(
            z1Min,
            z1Max,
            z2Max,
            z3Max,
            z4Max,
        )

    private fun zoneIndex(
        bpm: Int,
        thresholds: IntArray,
    ): Int =
        when {
            bpm < thresholds[0] -> -1
            bpm < thresholds[1] -> 0
            bpm < thresholds[2] -> 1
            bpm < thresholds[3] -> 2
            bpm < thresholds[4] -> 3
            else -> 4
        }

    fun computeMetrics(
        startMs: Long,
        endMs: Long,
        hrSamples: List<DomainHeartRateSample>,
        thresholds: IntArray,
    ): WorkoutMetrics {
        val zoneMinutes = FloatArray(5)

        val sessionSamples =
            hrSamples
                .filter { it.time.toEpochMilli() in startMs..endMs }
                .sortedBy { it.time.toEpochMilli() }

        sessionSamples.forEachIndexed { index, sample ->
            val nextMs =
                if (index < sessionSamples.lastIndex) {
                    sessionSamples[index + 1].time.toEpochMilli()
                } else {
                    endMs
                }
            val durationMinutes = (nextMs - sample.time.toEpochMilli()) / 60_000f
            val zone = zoneIndex(sample.beatsPerMinute, thresholds)
            if (zone >= 0) {
                zoneMinutes[zone] += durationMinutes
            }
        }

        val durationMinutes = ((endMs - startMs) / 60_000L).toInt()

        val avgHr =
            if (sessionSamples.isNotEmpty()) {
                sessionSamples.map { it.beatsPerMinute }.average().toFloat()
            } else {
                0f
            }

        val trimp = zoneMinutes.indices.sumOf { (zoneMinutes[it] * ZONE_WEIGHTS[it]).toDouble() }.toFloat()

        return WorkoutMetrics(durationMinutes, zoneMinutes, trimp, avgHr)
    }
}
