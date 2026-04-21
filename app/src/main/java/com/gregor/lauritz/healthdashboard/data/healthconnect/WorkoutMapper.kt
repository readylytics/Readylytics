package com.gregor.lauritz.healthdashboard.data.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity

object WorkoutMapper {
    private val ZONE_WEIGHTS = floatArrayOf(1f, 2f, 3f, 4f, 5f)

    fun zoneThresholds(maxHr: Int): IntArray =
        intArrayOf(
            (maxHr * 0.65).toInt(),
            (maxHr * 0.75).toInt(),
            (maxHr * 0.85).toInt(),
            (maxHr * 0.92).toInt(),
        )

    private fun zoneIndex(
        bpm: Int,
        thresholds: IntArray,
    ): Int =
        when {
            bpm < thresholds[0] -> 0
            bpm < thresholds[1] -> 1
            bpm < thresholds[2] -> 2
            bpm < thresholds[3] -> 3
            else -> 4
        }

    fun mapExerciseSession(
        session: ExerciseSessionRecord,
        hrSamples: List<HeartRateRecordEntity>,
        maxHrBpm: Int,
    ): WorkoutRecordEntity {
        val thresholds = zoneThresholds(maxHrBpm)
        val zoneMinutes = FloatArray(5)

        val sessionSamples =
            hrSamples
                .filter { it.timestampMs in session.startTime.toEpochMilli()..session.endTime.toEpochMilli() }
                .sortedBy { it.timestampMs }

        for (i in 0 until sessionSamples.size - 1) {
            val durationMinutes = (sessionSamples[i + 1].timestampMs - sessionSamples[i].timestampMs) / 60_000f
            val zone = zoneIndex(sessionSamples[i].beatsPerMinute, thresholds)
            zoneMinutes[zone] += durationMinutes
        }

        val trimp =
            zoneMinutes
                .zip(
                    ZONE_WEIGHTS.toList(),
                ).sumOf { (minutes, weight) -> (minutes * weight).toDouble() }
                .toFloat()
        val durationMinutes = ((session.endTime.toEpochMilli() - session.startTime.toEpochMilli()) / 60_000L).toInt()

        return WorkoutRecordEntity(
            id = session.metadata.id,
            startTime = session.startTime.toEpochMilli(),
            endTime = session.endTime.toEpochMilli(),
            exerciseType = session.exerciseType.toString(),
            durationMinutes = durationMinutes,
            zone1Minutes = zoneMinutes[0],
            zone2Minutes = zoneMinutes[1],
            zone3Minutes = zoneMinutes[2],
            zone4Minutes = zoneMinutes[3],
            zone5Minutes = zoneMinutes[4],
            trimp = trimp,
        )
    }
}
