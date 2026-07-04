package app.readylytics.health.core.ui.components

data class DayTimelineScale(
    val startMs: Long,
    val endExclusiveMs: Long,
) {
    val durationMs: Long get() = endExclusiveMs - startMs

    fun fraction(timestampMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return ((timestampMs - startMs).toDouble() / durationMs.toDouble()).toFloat()
    }
}
