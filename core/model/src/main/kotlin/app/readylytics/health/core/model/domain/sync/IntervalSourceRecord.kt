package app.readylytics.health.core.model.domain.sync

/**
 * Domain representation of interval metadata stored in the source record table.
 */
data class IntervalSourceRecord(
    val sourceId: String,
    val kind: IntervalKind,
    val startMs: Long,
    val endExclusiveMs: Long,
    val originPackage: String? = null,
    val lastModifiedMs: Long? = null,
)
