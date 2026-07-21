package app.readylytics.health.domain.sync.mappers

import app.readylytics.health.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.domain.sync.WorkoutInput

object WorkoutMapper {
    fun mapExerciseSession(session: DomainExerciseSessionRecord): WorkoutInput {
        val durationMinutes = ((session.endTime.toEpochMilli() - session.startTime.toEpochMilli()) / 60_000L).toInt()
        return WorkoutInput(
            id = session.id,
            startTime = session.startTime.toEpochMilli(),
            endTime = session.endTime.toEpochMilli(),
            exerciseType = session.exerciseType,
            durationMinutes = durationMinutes,
            zone1Minutes = 0f,
            zone2Minutes = 0f,
            zone3Minutes = 0f,
            zone4Minutes = 0f,
            zone5Minutes = 0f,
            trimp = 0f,
            avgHr = 0f,
            deviceName = session.deviceName,
        )
    }
}
