package app.readylytics.health.core.ui.common

/**
 * Returns the element of this list whose [timeOf] is closest to [targetMs], or `null` when the list
 * is empty. On a tie the earlier element wins, and among elements with equal times the first one —
 * the element `minByOrNull { abs(timeOf(it) - targetMs) }` picks, in O(log n) instead of O(n).
 *
 * The list must be sorted ascending by [timeOf].
 */
fun <T> List<T>.nearestByTime(
    targetMs: Long,
    timeOf: (T) -> Long,
): T? {
    if (isEmpty()) return null
    val firstAtOrAfter = lowerBoundIndex(targetMs, timeOf)
    val nearestMs =
        when (firstAtOrAfter) {
            0 -> timeOf(first())
            size -> timeOf(last())
            else -> {
                val beforeMs = timeOf(this[firstAtOrAfter - 1])
                val afterMs = timeOf(this[firstAtOrAfter])
                if (targetMs - beforeMs <= afterMs - targetMs) beforeMs else afterMs
            }
        }
    return this[lowerBoundIndex(nearestMs, timeOf)]
}

/** Index of the first element whose time is `>= targetMs`, or [size] when there is none. */
private fun <T> List<T>.lowerBoundIndex(
    targetMs: Long,
    timeOf: (T) -> Long,
): Int {
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (timeOf(this[mid]) < targetMs) low = mid + 1 else high = mid
    }
    return low
}
