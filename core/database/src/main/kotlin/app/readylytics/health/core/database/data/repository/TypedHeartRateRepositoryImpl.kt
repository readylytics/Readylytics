package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.database.data.local.AuthoritativeHeartRateReader
import app.readylytics.health.core.database.data.local.rangeInOfType
import app.readylytics.health.core.database.data.local.countInRangeOfType
import app.readylytics.health.core.database.data.local.typePagesInRange
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.model.domain.repository.HeartRateRecordData
import app.readylytics.health.core.model.domain.repository.TypedHeartRateRepository

class TypedHeartRateRepositoryImpl(
    private val authoritativeReader: AuthoritativeHeartRateReader
) : TypedHeartRateRepository {

    override suspend fun getByTimeRangeOfType(
        recordType: String, startMs: Long, endMs: Long
    ): List<HeartRateRecordData> =
        authoritativeReader.rangeInOfType(recordType, startMs, endMs).mergedSamples().map { mapToDomain(it) }

    override suspend fun countInRangeOfType(recordType: String, startMs: Long, endMs: Long): Int =
        authoritativeReader.countInRangeOfType(recordType, startMs, endMs)

    override suspend fun forEachByTimeRangeOfTypePage(
        recordType: String,
        startMs: Long,
        endMs: Long,
        limit: Int,
        onPage: suspend (List<HeartRateRecordData>) -> Unit,
    ) = authoritativeReader.typePagesInRange(recordType, startMs, endMs, limit) { page ->
        onPage(page.map { mapToDomain(it) })
    }
}

internal fun mapToDomain(entity: HeartRateRecordEntity): HeartRateRecordData =
    HeartRateRecordData(
        id = "${entity.sourceRecordRef}:${entity.timestampMs}",
        timestampMs = entity.timestampMs,
        beatsPerMinute = entity.beatsPerMinute,
        recordType = entity.recordType,
        sessionId = entity.sessionId,
        deviceName = entity.deviceName,
    )
