package app.readylytics.health.core.model.domain.repository

interface TypedHeartRateRepository {
    suspend fun getByTimeRangeOfType(recordType: String, startMs: Long, endMs: Long): List<HeartRateRecordData>
    suspend fun countInRangeOfType(recordType: String, startMs: Long, endMs: Long): Int
    suspend fun forEachByTimeRangeOfTypePage(
        recordType: String,
        startMs: Long,
        endMs: Long,
        limit: Int,
        onPage: suspend (List<HeartRateRecordData>) -> Unit,
    )
}
