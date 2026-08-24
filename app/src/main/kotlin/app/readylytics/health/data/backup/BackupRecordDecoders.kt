package app.readylytics.health.data.backup

import app.readylytics.health.core.databaseschema.data.local.dao.SourceRecordDao
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import kotlinx.serialization.json.Json

internal suspend fun decodeHeartRateRecord(
    json: Json,
    row: String,
    schemaVersion: Int,
    sourceRecordDao: SourceRecordDao,
): HeartRateRecordEntity =
    when {
        schemaVersion >= BackupSchemaPolicy.SOURCE_REF_FORMAT_MIN_VERSION ->
            json.decodeFromString<HeartRateRecordEntity>(row)
        schemaVersion >= BackupSchemaPolicy.CURRENT_RECORD_FORMAT_MIN_VERSION -> {
            val backup = json.decodeFromString<SourceRecordIdHeartRateRecordBackup>(row)
            HeartRateRecordEntity(
                sourceRecordRef =
                    sourceRecordDao.getOrCreateSourceRef(
                        sourceRecordId = backup.sourceRecordId.substringBefore('_'),
                        recordType = "HEART_RATE",
                        createdAtMs = backup.timestampMs,
                    ),
                timestampMs = backup.timestampMs,
                beatsPerMinute = backup.beatsPerMinute,
                recordType = backup.recordType,
                sessionId = backup.sessionId,
                deviceName = backup.deviceName,
            )
        }
        else -> {
            val backup = json.decodeFromString<LegacyHeartRateRecordBackup>(row)
            HeartRateRecordEntity(
                sourceRecordRef =
                    sourceRecordDao.getOrCreateSourceRef(
                        sourceRecordId = backup.toSourceRecordId(),
                        recordType = "HEART_RATE",
                        createdAtMs = backup.timestampMs,
                    ),
                timestampMs = backup.timestampMs,
                beatsPerMinute = backup.beatsPerMinute,
                recordType = backup.recordType,
                sessionId = backup.sessionId,
                deviceName = backup.deviceName,
            )
        }
    }

internal suspend fun decodeHrvRecord(
    json: Json,
    row: String,
    schemaVersion: Int,
    sourceRecordDao: SourceRecordDao,
): HrvRecordEntity =
    when {
        schemaVersion >= BackupSchemaPolicy.SOURCE_REF_FORMAT_MIN_VERSION ->
            json.decodeFromString<HrvRecordEntity>(row)
        schemaVersion >= BackupSchemaPolicy.CURRENT_RECORD_FORMAT_MIN_VERSION -> {
            val backup = json.decodeFromString<SourceRecordIdHrvRecordBackup>(row)
            HrvRecordEntity(
                sourceRecordRef =
                    sourceRecordDao.getOrCreateSourceRef(
                        sourceRecordId = backup.sourceRecordId.substringBefore('_'),
                        recordType = "HRV",
                        createdAtMs = backup.timestampMs,
                    ),
                timestampMs = backup.timestampMs,
                rmssdMs = backup.rmssdMs,
                recordType = backup.recordType,
                sessionId = backup.sessionId,
                deviceName = backup.deviceName,
            )
        }
        else -> {
            val backup = json.decodeFromString<LegacyHrvRecordBackup>(row)
            HrvRecordEntity(
                sourceRecordRef =
                    sourceRecordDao.getOrCreateSourceRef(
                        sourceRecordId = backup.toSourceRecordId(),
                        recordType = "HRV",
                        createdAtMs = backup.timestampMs,
                    ),
                timestampMs = backup.timestampMs,
                rmssdMs = backup.rmssdMs,
                recordType = backup.recordType,
                sessionId = backup.sessionId,
                deviceName = backup.deviceName,
            )
        }
    }
