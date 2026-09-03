package app.readylytics.health.core.database.data.local

internal data class TimestampedSamples(
    val timestampsMs: LongArray,
    val bpmValues: IntArray,
) {
    val size: Int get() = timestampsMs.size
    val isEmpty: Boolean get() = size == 0

    inline fun forEachIndexed(action: (index: Int, timestampMs: Long, bpm: Int) -> Unit) {
        for (i in 0 until size) {
            action(i, timestampsMs[i], bpmValues[i])
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TimestampedSamples
        if (!timestampsMs.contentEquals(other.timestampsMs)) return false
        if (!bpmValues.contentEquals(other.bpmValues)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = timestampsMs.contentHashCode()
        result = 31 * result + bpmValues.contentHashCode()
        return result
    }
}
