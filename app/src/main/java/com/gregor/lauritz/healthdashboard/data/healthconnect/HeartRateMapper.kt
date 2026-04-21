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
    ): List<HeartRateRecordEntity> =
        records.flatMap { record ->
            record.samples.map { sample ->
                val sampleMs = sample.time.toEpochMilli()
                val sleepSession = sleepSessions.firstOrNull { sampleMs in it.startTime..it.endTime }
                val workoutSession = workoutSessions.firstOrNull { sampleMs in it.startTime..it.endTime }
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
