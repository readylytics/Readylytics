package app.readylytics.health.feature.workouts.mappers

import app.readylytics.health.feature.workouts.HeartRatePoint
import java.time.Instant
import java.time.temporal.ChronoUnit

object ChartDataMapper {
    fun mapToChartData(
        samples: List<HeartRatePoint>,
        workoutStart: Long,
        workoutEnd: Long,
    ): Pair<List<Pair<Double, Double>>, Int> {
        val workoutStartInstant = Instant.ofEpochMilli(workoutStart)
        val workoutEndInstant = Instant.ofEpochMilli(workoutEnd)
        val workoutSamples = samples.filter { it.timestamp in workoutStartInstant..workoutEndInstant }
        val durationMinutes =
            ChronoUnit.MINUTES
                .between(
                    workoutStartInstant,
                    workoutEndInstant,
                ).toInt()
                .coerceAtLeast(1)

        val chartData =
            workoutSamples
                .groupBy {
                    (ChronoUnit.SECONDS.between(workoutStartInstant, it.timestamp) / 60L).toInt()
                }.toSortedMap()
                .map { (minute, points) ->
                    minute.toDouble() to points.map { it.bpm.toDouble() }.average()
                }

        return chartData to durationMinutes
    }
}
