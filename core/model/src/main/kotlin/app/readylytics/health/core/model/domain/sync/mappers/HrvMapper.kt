package app.readylytics.health.core.model.domain.sync.mappers

import app.readylytics.health.core.model.domain.model.DomainHrvRecord
import app.readylytics.health.core.model.domain.sync.HrvInput
import app.readylytics.health.core.model.domain.sync.SleepSessionInput
import app.readylytics.health.core.model.domain.sync.SourceMetadata
import app.readylytics.health.core.model.domain.sync.SourcePayload
import app.readylytics.health.core.model.domain.sync.link.SessionLinkSweep
import app.readylytics.health.core.model.domain.sync.link.SessionSpan

object HrvMapper {
    fun mapToInputs(
        records: List<DomainHrvRecord>,
        sleepSessions: List<SleepSessionInput>,
    ): List<SourcePayload<HrvInput>> {
        if (records.isEmpty()) return emptyList()

        val sleepSpans = sleepSessions.map { SessionSpan(it.id, it.startTime, it.endTime) }
        val sweep = SessionLinkSweep(sleepSpans, emptyList())

        return records.sortedWith(compareBy<DomainHrvRecord> { it.time }.thenBy { it.id }).map { record ->
            val sampleMs = record.time.toEpochMilli()
            val meta =
                SourceMetadata(
                    sourceId = record.id,
                    recordType = "HRV",
                    originPackage = record.originPackage,
                    startMs = sampleMs,
                    endExclusiveMs = sampleMs + 1,
                    lastModifiedMs = record.lastModifiedTime?.toEpochMilli(),
                )
            val link = sweep.resolve(sampleMs)
            val row =
                HrvInput(
                    id = "${record.id}_$sampleMs",
                    timestampMs = sampleMs,
                    rmssdMs = record.rmssdMs,
                    recordType = link.recordType,
                    sessionId = link.sessionId,
                    deviceName = record.deviceName,
                    sourceId = record.id,
                )
            SourcePayload(meta, listOf(row))
        }
    }
}
