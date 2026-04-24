package com.gregor.lauritz.healthdashboard.data.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity
import kotlin.math.roundToInt

object WorkoutMapper {
    private val ZONE_WEIGHTS = floatArrayOf(1f, 2f, 3f, 4f, 5f)

    fun zoneThresholds(
        maxHr: Int,
        z1p: Float = 0.60f,
        z2p: Float = 0.70f,
        z3p: Float = 0.80f,
        z4p: Float = 0.90f
    ): IntArray =
        intArrayOf(
            (maxHr * z1p).toInt(),
            (maxHr * z2p).toInt(),
            (maxHr * z3p).toInt(),
            (maxHr * z4p).toInt(),
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
        thresholds: IntArray,
    ): WorkoutRecordEntity {
        val zoneMinutes = FloatArray(5)

        val sessionSamples =
            hrSamples
                .filter { it.timestampMs in session.startTime.toEpochMilli()..session.endTime.toEpochMilli() }
                .sortedBy { it.timestampMs }

        sessionSamples.forEachIndexed { index, sample ->
            val nextMs = if (index < sessionSamples.lastIndex) {
                sessionSamples[index + 1].timestampMs
            } else {
                session.endTime.toEpochMilli()
            }
            val durationMinutes = (nextMs - sample.timestampMs) / 60_000f
            val zone = zoneIndex(sample.beatsPerMinute, thresholds)
            zoneMinutes[zone] += durationMinutes
        }

        val trimp =
            zoneMinutes
                .zip(
                    ZONE_WEIGHTS.toList(),
                ).sumOf { (minutes, weight) -> (minutes * weight).toDouble() }
                .toFloat()
        val durationMinutes = ((session.endTime.toEpochMilli() - session.startTime.toEpochMilli()) / 60_000L).toInt()

        val avgHr = if (sessionSamples.isNotEmpty()) {
            sessionSamples.map { it.beatsPerMinute }.average().roundToInt()
        } else {
            0
        }

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
            avgHr = avgHr,
        )
    }
}
