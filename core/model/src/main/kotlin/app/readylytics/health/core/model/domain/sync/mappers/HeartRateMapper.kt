package app.readylytics.health.core.model.domain.sync.mappers

import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.SleepSessionInput
import app.readylytics.health.core.model.domain.sync.SourceMetadata
import app.readylytics.health.core.model.domain.sync.SourcePayload
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import app.readylytics.health.core.model.domain.sync.link.SessionLinkSweep
import app.readylytics.health.core.model.domain.sync.link.SessionSpan
import java.time.Instant

object HeartRateMapper {
    fun mapToInputs(
        records: List<DomainHeartRateRecord>,
        sleepSessions: List<SleepSessionInput>,
        workoutSessions: List<WorkoutInput>,
    ): List<SourcePayload<HeartRateInput>> {
        if (records.isEmpty()) return emptyList()

        val sleepSpans = sleepSessions.map { SessionSpan(it.id, it.startTime, it.endTime) }
        val workoutSpans = workoutSessions.map { SessionSpan(it.id, it.startTime, it.endTime) }
        val sweep = SessionLinkSweep(sleepSpans, workoutSpans)

        return records.map { record ->
            val devName = record.deviceName
            val recId = record.id
            val startMs =
                if (record.startTime != Instant.EPOCH) {
                    record.startTime.toEpochMilli()
                } else {
                    record.samples.minOfOrNull { it.time.toEpochMilli() } ?: 0L
                }
            val endMs =
                if (record.endTime != Instant.EPOCH) {
                    maxOf(startMs + 1, record.endTime.toEpochMilli())
                } else {
                    record.samples.maxOfOrNull { it.time.toEpochMilli() + 1 } ?: (startMs + 1)
                }
            val meta =
                SourceMetadata(
                    sourceId = recId,
                    recordType = "HEART_RATE",
                    originPackage = record.originPackage,
                    startMs = startMs,
                    endExclusiveMs = endMs,
                    lastModifiedMs = record.lastModifiedTime?.toEpochMilli(),
                )

            val samples =
                record.samples.map { sample ->
                    val sampleMs = sample.time.toEpochMilli()
                    val link = sweep.resolve(sampleMs)
                    HeartRateInput(
                        id = "${recId}_$sampleMs",
                        timestampMs = sampleMs,
                        beatsPerMinute = sample.beatsPerMinute,
                        recordType = link.recordType,
                        sessionId = link.sessionId,
                        deviceName = devName,
                        sourceId = recId,
                    )
                }.sortedBy { it.timestampMs }

            SourcePayload(meta, samples)
        }
    }
}
