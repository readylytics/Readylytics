package com.gregor.lauritz.healthdashboard.data.healthconnect

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity
import com.gregor.lauritz.healthdashboard.domain.model.RecordType

object HeartRateMapper {
    fun mapToEntities(
        records: List<HeartRateRecord>,
        sleepSessions: List<SleepSessionEntity>,
        workoutSessions: List<WorkoutRecordEntity>,
    ): List<HeartRateRecordEntity> {
        val sortedSleep = sleepSessions.sortedBy { it.startTime }
        val sortedWorkouts = workoutSessions.sortedBy { it.startTime }

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
                id = "${record.metadata.id}_$sampleMs",
                timestampMs = sampleMs,
                beatsPerMinute = sample.beatsPerMinute.toInt(),
                recordType = recordType,
                sessionId = sessionId,
                deviceName = DeviceLabel.from(record.metadata.device),
            )
        }
    }

    fun mapRestingToEntities(
        records: List<RestingHeartRateRecord>,
        sleepSessions: List<SleepSessionEntity>,
    ): List<HeartRateRecordEntity> {
        val sortedSleep = sleepSessions.sortedBy { it.startTime }
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
                id = "RESTING_${record.metadata.id}_$timestampMs",
                timestampMs = timestampMs,
                beatsPerMinute = record.beatsPerMinute.toInt(),
                recordType = recordType,
                sessionId = sessionId,
                deviceName = DeviceLabel.from(record.metadata.device),
            )
        }
    }
}
