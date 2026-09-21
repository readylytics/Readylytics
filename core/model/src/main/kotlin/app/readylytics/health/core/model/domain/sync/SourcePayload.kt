package app.readylytics.health.core.model.domain.sync

data class SourceMetadata(
    val sourceId: String,
    val recordType: String,
    val originPackage: String?,
    val startMs: Long,
    val endExclusiveMs: Long,
    val lastModifiedMs: Long? = null,
) {
    constructor(
        sourceId: String,
        startMs: Long,
        endExclusiveMs: Long,
    ) : this(
        sourceId = sourceId,
        recordType = "UNKNOWN",
        originPackage = null,
        startMs = startMs,
        endExclusiveMs = endExclusiveMs,
        lastModifiedMs = null,
    )
}

data class SourcePayload<T>(
    val source: SourceMetadata,
    val rows: List<T>,
)
