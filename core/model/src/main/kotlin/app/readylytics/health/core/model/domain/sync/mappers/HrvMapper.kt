package app.readylytics.health.core.model.domain.sync.mappers

import app.readylytics.health.core.model.domain.model.DomainHrvRecord
import app.readylytics.health.core.model.domain.sync.HrvInput
import app.readylytics.health.core.model.domain.sync.SleepSessionInput
import app.readylytics.health.core.model.domain.sync.link.SessionLinkSweep
import app.readylytics.health.core.model.domain.sync.link.SessionSpan

object HrvMapper {
    fun mapToInputs(
        records: List<DomainHrvRecord>,
        sleepSessions: List<SleepSessionInput>,
    ): List<HrvInput> {
        if (records.isEmpty()) return emptyList()

        val sleepSpans = sleepSessions.map { SessionSpan(it.id, it.startTime, it.endTime) }
        val sweep = SessionLinkSweep(sleepSpans, emptyList())

        val flatSamples = ArrayList<HrvInput>(records.size)
        for (record in records) {
            val sampleMs = record.time.toEpochMilli()
            flatSamples.add(
                HrvInput(
                    id = "${record.id}_$sampleMs",
                    timestampMs = sampleMs,
                    rmssdMs = record.rmssdMs,
                    recordType = "RESTING",
                    sessionId = null,
                    deviceName = record.deviceName,
                ),
            )
        }

        flatSamples.sortBy { it.timestampMs }

        for (i in flatSamples.indices) {
            val item = flatSamples[i]
            val link = sweep.resolve(item.timestampMs)
            flatSamples[i] =
                item.copy(
                    recordType = link.recordType,
                    sessionId = link.sessionId,
                )
        }

        return flatSamples
    }
}
