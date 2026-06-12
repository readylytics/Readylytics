package app.readylytics.health.data.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.WorkoutRecordEntity

object WorkoutMapper {
    private val ZONE_WEIGHTS = floatArrayOf(1f, 2f, 3f, 4f, 5f)

    fun zoneThresholds(
        z1Min: Int = 95,
        z1Max: Int = 114,
        z2Max: Int = 133,
        z3Max: Int = 152,
        z4Max: Int = 171,
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

    /**
     * Pure HR-derived metrics for a session window: zone minutes, TRIMP, average HR, duration.
     *
     * Shared by ingestion ([mapExerciseSession]) and the post-ingestion
     * [app.readylytics.health.domain.sync.link.SessionLinkReconciler], so a workout's
     * metrics are a pure function of (startMs, endMs, hrSamples) regardless of which ingestion
     * chunk originally supplied the samples.
     */
    data class WorkoutMetrics(
        val durationMinutes: Int,
        val zoneMinutes: FloatArray,
        val trimp: Float,
        val avgHr: Float,
    )

    fun computeMetrics(
        startMs: Long,
        endMs: Long,
        hrSamples: List<HeartRateRecordEntity>,
        thresholds: IntArray,
    ): WorkoutMetrics {
        val zoneMinutes = FloatArray(5)

        val sessionSamples =
            hrSamples
                .filter { it.timestampMs in startMs..endMs }
                .sortedBy { it.timestampMs }

        sessionSamples.forEachIndexed { index, sample ->
            val nextMs =
                if (index < sessionSamples.lastIndex) {
                    sessionSamples[index + 1].timestampMs
                } else {
                    endMs
                }
            val durationMinutes = (nextMs - sample.timestampMs) / 60_000f
            val zone = zoneIndex(sample.beatsPerMinute, thresholds)
            if (zone >= 0) {
                zoneMinutes[zone] += durationMinutes
            }
        }

        val durationMinutes = ((endMs - startMs) / 60_000L).toInt()

        val avgHr =
            if (sessionSamples.isNotEmpty()) {
                sessionSamples.map { it.beatsPerMinute }.average().toFloat()
            } else {
                0f
            }

        val trimp = zoneMinutes.indices.sumOf { (zoneMinutes[it] * ZONE_WEIGHTS[it]).toDouble() }.toFloat()

        return WorkoutMetrics(durationMinutes, zoneMinutes, trimp, avgHr)
    }

    fun mapExerciseSession(
        session: ExerciseSessionRecord,
        hrSamples: List<HeartRateRecordEntity>,
        thresholds: IntArray,
    ): WorkoutRecordEntity {
        val metrics =
            computeMetrics(
                session.startTime.toEpochMilli(),
                session.endTime.toEpochMilli(),
                hrSamples,
                thresholds,
            )

        return WorkoutRecordEntity(
            id = session.metadata.id,
            startTime = session.startTime.toEpochMilli(),
            endTime = session.endTime.toEpochMilli(),
            exerciseType = session.exerciseType.toString(),
            durationMinutes = metrics.durationMinutes,
            zone1Minutes = metrics.zoneMinutes[0],
            zone2Minutes = metrics.zoneMinutes[1],
            zone3Minutes = metrics.zoneMinutes[2],
            zone4Minutes = metrics.zoneMinutes[3],
            zone5Minutes = metrics.zoneMinutes[4],
            trimp = metrics.trimp,
            avgHr = metrics.avgHr,
            deviceName = DeviceLabel.from(session.metadata.device, session.metadata.dataOrigin),
        )
    }
}
