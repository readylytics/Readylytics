package app.readylytics.health.domain.sync.mappers

import app.readylytics.health.domain.model.DomainHeartRateRecord
import app.readylytics.health.domain.sync.HeartRateInput
import app.readylytics.health.domain.sync.SleepSessionInput
import app.readylytics.health.domain.sync.WorkoutInput
import app.readylytics.health.domain.sync.link.SessionLinkSweep
import app.readylytics.health.domain.sync.link.SessionSpan

object HeartRateMapper {
    fun mapToInputs(
        records: List<DomainHeartRateRecord>,
        sleepSessions: List<SleepSessionInput>,
        workoutSessions: List<WorkoutInput>,
    ): List<HeartRateInput> {
        val sleepSpans = sleepSessions.map { SessionSpan(it.id, it.startTime, it.endTime) }
        val workoutSpans = workoutSessions.map { SessionSpan(it.id, it.startTime, it.endTime) }
        val sweep = SessionLinkSweep(sleepSpans, workoutSpans)

        val allSamples =
            records
                .flatMap { record -> record.samples.map { sample -> record to sample } }
                .sortedBy { (_, sample) -> sample.time.toEpochMilli() }

        return allSamples.map { (record, sample) ->
            val sampleMs = sample.time.toEpochMilli()
            val link = sweep.resolve(sampleMs)

            HeartRateInput(
                id = "${record.id}_$sampleMs",
                timestampMs = sampleMs,
                beatsPerMinute = sample.beatsPerMinute,
                recordType = link.recordType,
                sessionId = link.sessionId,
                deviceName = record.deviceName,
            )
        }
    }
}
