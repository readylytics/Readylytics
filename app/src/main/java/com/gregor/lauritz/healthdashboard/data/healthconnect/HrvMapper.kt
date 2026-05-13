package com.gregor.lauritz.healthdashboard.data.healthconnect

import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import com.gregor.lauritz.healthdashboard.data.local.entity.HrvRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.domain.model.RecordType

object HrvMapper {
    fun mapToEntities(
        records: List<HeartRateVariabilityRmssdRecord>,
        sleepSessions: List<SleepSessionEntity>,
    ): List<HrvRecordEntity> {
        val sortedSleep = sleepSessions.sortedBy { it.startTime }
        val sortedRecords = records.sortedBy { it.time.toEpochMilli() }
        var sleepIdx = 0

        return sortedRecords.map { record ->
            val sampleMs = record.time.toEpochMilli()

            while (sleepIdx < sortedSleep.size && sortedSleep[sleepIdx].endTime < sampleMs) {
                sleepIdx++
            }
            val sleepSession =
                if (sleepIdx < sortedSleep.size &&
                    sampleMs in sortedSleep[sleepIdx].startTime..sortedSleep[sleepIdx].endTime
                ) {
                    sortedSleep[sleepIdx]
                } else {
                    null
                }

            HrvRecordEntity(
                id = "${record.metadata.id}_$sampleMs",
                timestampMs = sampleMs,
                rmssdMs = record.heartRateVariabilityMillis.toFloat(),
                recordType = if (sleepSession != null) RecordType.SLEEP.name else RecordType.RESTING.name,
                sessionId = sleepSession?.id,
            )
        }
    }
}
