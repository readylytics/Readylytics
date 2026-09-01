package app.readylytics.health.core.model.domain.sync.mappers

import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.SleepSessionInput
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import app.readylytics.health.core.model.domain.sync.link.SessionLinkSweep
import app.readylytics.health.core.model.domain.sync.link.SessionSpan

object HeartRateMapper {
    fun mapToInputs(
        records: List<DomainHeartRateRecord>,
        sleepSessions: List<SleepSessionInput>,
        workoutSessions: List<WorkoutInput>,
    ): List<HeartRateInput> {
        val totalSamples = records.sumOf { it.samples.size }
        if (totalSamples == 0) return emptyList()

        val sleepSpans = sleepSessions.map { SessionSpan(it.id, it.startTime, it.endTime) }
        val workoutSpans = workoutSessions.map { SessionSpan(it.id, it.startTime, it.endTime) }
        val sweep = SessionLinkSweep(sleepSpans, workoutSpans)

        val flatSamples = ArrayList<HeartRateInput>(totalSamples)
        for (record in records) {
            val devName = record.deviceName
            val recId = record.id
            for (sample in record.samples) {
                val sampleMs = sample.time.toEpochMilli()
                flatSamples.add(
                    HeartRateInput(
                        id = "${recId}_$sampleMs",
                        timestampMs = sampleMs,
                        beatsPerMinute = sample.beatsPerMinute,
                        recordType = "RESTING",
                        sessionId = null,
                        deviceName = devName,
                    ),
                )
            }
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
