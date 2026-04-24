package com.gregor.lauritz.healthdashboard.data.healthconnect

import androidx.health.connect.client.records.HeartRateRecord
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity

object HeartRateMapper {
    fun mapToEntities(
        records: List<HeartRateRecord>,
        sleepSessions: List<SleepSessionEntity>,
        workoutSessions: List<WorkoutRecordEntity>,
    ): List<HeartRateRecordEntity> {
        val sortedSleep = sleepSessions.sortedBy { it.startTime }
        val sortedWorkouts = workoutSessions.sortedBy { it.startTime }

        var sleepIdx = 0
        var workoutIdx = 0

        return records.flatMap { record ->
            record.samples.map { sample ->
                val sampleMs = sample.time.toEpochMilli()

                // Efficiently find matching sleep session (assuming samples are somewhat ordered)
                while (sleepIdx < sortedSleep.size && sortedSleep[sleepIdx].endTime < sampleMs) {
                    sleepIdx++
                }
                val sleepSession =
                    if (sleepIdx < sortedSleep.size && sampleMs in sortedSleep[sleepIdx].startTime..sortedSleep[sleepIdx].endTime) {
                        sortedSleep[sleepIdx]
                    } else {
                        null
                    }

                // Efficiently find matching workout session
                while (workoutIdx < sortedWorkouts.size && sortedWorkouts[workoutIdx].endTime < sampleMs) {
                    workoutIdx++
                }
                val workoutSession =
                    if (workoutIdx < sortedWorkouts.size && sampleMs in sortedWorkouts[workoutIdx].startTime..sortedWorkouts[workoutIdx].endTime) {
                        sortedWorkouts[workoutIdx]
                    } else {
                        null
                    }

                val (recordType, sessionId) =
                    when {
                        sleepSession != null -> "SLEEP" to sleepSession.id
                        workoutSession != null -> "EXERCISE" to workoutSession.id
                        else -> "RESTING" to null
                    }
                HeartRateRecordEntity(
                    id = "${record.metadata.id}_$sampleMs",
                    timestampMs = sampleMs,
                    beatsPerMinute = sample.beatsPerMinute.toInt(),
                    recordType = recordType,
                    sessionId = sessionId,
                )
            }
        }
    }
}
