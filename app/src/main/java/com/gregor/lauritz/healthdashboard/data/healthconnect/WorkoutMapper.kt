package com.gregor.lauritz.healthdashboard.data.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity
import kotlin.math.roundToInt

object WorkoutMapper {
    private val ZONE_WEIGHTS = floatArrayOf(1f, 2f, 3f, 4f, 5f)

    fun zoneThresholds(
        z1Min: Int = 95,
        z1Max: Int = 114,
        z2Max: Int = 133,
        z3Max: Int = 152,
        z4Max: Int = 171
    ): IntArray =
        intArrayOf(
            z1Min,
            z1Max,
            z2Max,
            z3Max,
            z4Max,
        )

    private fun zoneIndex(
        bpm: Int,
        thresholds: IntArray,
    ): Int =
        when {
            bpm < thresholds[0] -> -1 // Below Zone 1 Min
            bpm < thresholds[1] -> 0
            bpm < thresholds[2] -> 1
            bpm < thresholds[3] -> 2
            bpm < thresholds[4] -> 3
            else -> 4
        }

    fun mapExerciseSession(
        session: ExerciseSessionRecord,
        hrSamples: List<HeartRateRecordEntity>,
        thresholds: IntArray,
        trimp: Float = 0f
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
            if (zone >= 0) {
                zoneMinutes[zone] += durationMinutes
            }
        }

        val durationMinutes = ((session.endTime.toEpochMilli() - session.startTime.toEpochMilli()) / 60_000L).toInt()

        val avgHr = if (sessionSamples.isNotEmpty()) {
            sessionSamples.map { it.beatsPerMinute }.average().toFloat()
        } else {
            0f
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
