package app.readylytics.health.data.healthconnect

import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.domain.model.DomainHeartRateRecord
import app.readylytics.health.domain.model.DomainRestingHeartRateRecord
import app.readylytics.health.domain.model.RecordType

object HeartRateMapper {
    fun mapToEntities(
        records: List<DomainHeartRateRecord>,
        sleepSessions: List<SleepSessionEntity>,
        workoutSessions: List<WorkoutRecordEntity>,
    ): List<HeartRateRecordEntity> {
        val sortedSleep = sleepSessions.sortedWith(compareBy({ it.startTime }, { it.id }))
        val sortedWorkouts = workoutSessions.sortedWith(compareBy({ it.startTime }, { it.id }))

        // Flatten + sort chronologically before session matching.
        // Samsung delivers HeartRateRecord batches out of order; the forward-only
        // index breaks if samples from different records interleave in time.
        val allSamples =
            records
                .flatMap { record -> record.samples.map { sample -> record to sample } }
                .sortedBy { (_, sample) -> sample.time.toEpochMilli() }

        var sleepIdx = 0
        var workoutIdx = 0

        return allSamples.map { (record, sample) ->
            val sampleMs = sample.time.toEpochMilli()

            while (sleepIdx < sortedSleep.size && sortedSleep[sleepIdx].endTime < sampleMs) {
                sleepIdx++
            }
            val sleepSession =
                if (sleepIdx < sortedSleep.size &&
                    sampleMs >= sortedSleep[sleepIdx].startTime &&
                    sampleMs <= sortedSleep[sleepIdx].endTime
                ) {
                    sortedSleep[sleepIdx]
                } else {
                    null
                }

            while (workoutIdx < sortedWorkouts.size && sortedWorkouts[workoutIdx].endTime < sampleMs) {
                workoutIdx++
            }
            val workoutSession =
                if (workoutIdx < sortedWorkouts.size &&
                    sampleMs >= sortedWorkouts[workoutIdx].startTime &&
                    sampleMs <= sortedWorkouts[workoutIdx].endTime
                ) {
                    sortedWorkouts[workoutIdx]
                } else {
                    null
                }

            val (recordType, sessionId) =
                when {
                    sleepSession != null -> RecordType.SLEEP.name to sleepSession.id
                    workoutSession != null -> RecordType.EXERCISE.name to workoutSession.id
                    else -> RecordType.RESTING.name to null
                }
            HeartRateRecordEntity(
                id = "${record.id}_$sampleMs",
                timestampMs = sampleMs,
                beatsPerMinute = sample.beatsPerMinute,
                recordType = recordType,
                sessionId = sessionId,
                deviceName = record.deviceName,
            )
        }
    }

    fun mapRestingToEntities(
        records: List<DomainRestingHeartRateRecord>,
        sleepSessions: List<SleepSessionEntity>,
    ): List<HeartRateRecordEntity> {
        val sortedSleep = sleepSessions.sortedWith(compareBy({ it.startTime }, { it.id }))
        val sortedRecords = records.sortedBy { it.time.toEpochMilli() }
        var sleepIdx = 0

        return sortedRecords.map { record ->
            val timestampMs = record.time.toEpochMilli()

            while (sleepIdx < sortedSleep.size && sortedSleep[sleepIdx].endTime < timestampMs) {
                sleepIdx++
            }
            val sleepSession =
                if (sleepIdx < sortedSleep.size &&
                    timestampMs in sortedSleep[sleepIdx].startTime..sortedSleep[sleepIdx].endTime
                ) {
                    sortedSleep[sleepIdx]
                } else {
                    null
                }

            val (recordType, sessionId) =
                if (sleepSession != null) RecordType.SLEEP.name to sleepSession.id else RecordType.RESTING.name to null

            HeartRateRecordEntity(
                id = record.id,
                timestampMs = timestampMs,
                beatsPerMinute = record.beatsPerMinute,
                recordType = recordType,
                sessionId = sessionId,
                deviceName = record.deviceName,
            )
        }
    }
}
