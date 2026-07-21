package app.readylytics.health.domain.sync.mappers

import app.readylytics.health.domain.model.DomainHrvRecord
import app.readylytics.health.domain.sync.HrvInput
import app.readylytics.health.domain.sync.SleepSessionInput
import app.readylytics.health.domain.sync.link.SessionLinkSweep
import app.readylytics.health.domain.sync.link.SessionSpan

object HrvMapper {
    fun mapToInputs(
        records: List<DomainHrvRecord>,
        sleepSessions: List<SleepSessionInput>,
    ): List<HrvInput> {
        val sleepSpans = sleepSessions.map { SessionSpan(it.id, it.startTime, it.endTime) }
        val sweep = SessionLinkSweep(sleepSpans, emptyList())
        val sortedRecords = records.sortedBy { it.time.toEpochMilli() }

        return sortedRecords.map { record ->
            val sampleMs = record.time.toEpochMilli()
            val link = sweep.resolve(sampleMs)

            HrvInput(
                id = "${record.id}_$sampleMs",
                timestampMs = sampleMs,
                rmssdMs = record.rmssdMs,
                recordType = link.recordType,
                sessionId = link.sessionId,
                deviceName = record.deviceName,
            )
        }
    }
}
