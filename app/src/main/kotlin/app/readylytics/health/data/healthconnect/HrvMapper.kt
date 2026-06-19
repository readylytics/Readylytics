package app.readylytics.health.data.healthconnect

import app.readylytics.health.data.local.entity.HrvRecordEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.domain.model.DomainHrvRecord
import app.readylytics.health.domain.model.RecordType

object HrvMapper {
    fun mapToEntities(
        records: List<DomainHrvRecord>,
        sleepSessions: List<SleepSessionEntity>,
    ): List<HrvRecordEntity> {
        val sortedSleep = sleepSessions.sortedWith(compareBy({ it.startTime }, { it.id }))
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
                id = "${record.id}_$sampleMs",
                timestampMs = sampleMs,
                rmssdMs = record.rmssdMs,
                recordType = if (sleepSession != null) RecordType.SLEEP.name else RecordType.RESTING.name,
                sessionId = sleepSession?.id,
                deviceName = record.deviceName,
            )
        }
    }
}
