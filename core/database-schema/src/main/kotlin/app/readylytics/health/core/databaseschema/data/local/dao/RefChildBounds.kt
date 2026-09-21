package app.readylytics.health.core.databaseschema.data.local.dao

data class RefChildBounds(
    val sourceRecordRef: Long,
    val minTimestampMs: Long,
    val maxTimestampMs: Long,
)
