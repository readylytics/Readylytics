package com.gregor.lauritz.healthdashboard.data.healthconnect

import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import com.gregor.lauritz.healthdashboard.data.local.entity.HrvRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity

object HrvMapper {
    fun mapToEntities(
        records: List<HeartRateVariabilityRmssdRecord>,
        sleepSessions: List<SleepSessionEntity>,
    ): List<HrvRecordEntity> =
        records.map { record ->
            val sampleMs = record.time.toEpochMilli()
            val sleepSession = sleepSessions.firstOrNull { sampleMs in it.startTime..it.endTime }
            HrvRecordEntity(
                id = record.metadata.id,
                timestampMs = sampleMs,
                rmssdMs = record.heartRateVariabilityMillis.toFloat(),
                recordType = if (sleepSession != null) "SLEEP" else "RESTING",
                sessionId = sleepSession?.id,
            )
        }
}
