package app.readylytics.health.feature.sleep

import app.readylytics.health.domain.repository.HeartRateRecordData

internal const val SLEEP_HR_GAP_THRESHOLD_MS = 10 * 60 * 1000L // 10 minutes

internal object SleepHrChartHelper {
    fun splitIntoSegments(
        samples: List<HeartRateRecordData>,
        gapThresholdMs: Long,
    ): List<List<HeartRateRecordData>> {
        if (samples.isEmpty()) return emptyList()
        val segments = mutableListOf<MutableList<HeartRateRecordData>>()
        var current = mutableListOf(samples[0])
        for (i in 1 until samples.size) {
            if (samples[i].timestampMs - samples[i - 1].timestampMs > gapThresholdMs) {
                segments.add(current)
                current = mutableListOf(samples[i])
            } else {
                current.add(samples[i])
            }
        }
        segments.add(current)
        return segments
    }
}
