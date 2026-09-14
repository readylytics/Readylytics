package app.readylytics.health.core.model.domain.sync

enum class IntervalKind {
    DISTANCE,
    ELEVATION_GAINED,
}

/**
 * Represents a change to an interval record (distance or elevation gained).
 *
 * [oldStartMs] and [oldEndExclusiveMs] represent the previous half-open time range `[start, end)`
 * if known or moving/deleting. [newStartMs] and [newEndExclusiveMs] represent the new range
 * for insertions/updates (null for deletions).
 */
data class IntervalChange(
    val sourceId: String,
    val kind: IntervalKind,
    val oldStartMs: Long? = null,
    val oldEndExclusiveMs: Long? = null,
    val newStartMs: Long? = null,
    val newEndExclusiveMs: Long? = null,
    val originPackage: String? = null,
)

/**
 * Returns true if half-open intervals `[aStart, aEnd)` and `[bStart, bEnd)` overlap.
 * Touching boundaries (e.g. `aEnd == bStart`) do not overlap.
 */
fun overlaps(
    aStart: Long,
    aEnd: Long,
    bStart: Long,
    bEnd: Long,
): Boolean = aStart < bEnd && bStart < aEnd
