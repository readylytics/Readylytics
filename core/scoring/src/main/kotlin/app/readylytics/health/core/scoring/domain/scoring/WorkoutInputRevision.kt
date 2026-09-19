package app.readylytics.health.core.scoring.domain.scoring

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Independent identity of the current inputs, never of the cached TRIMP being validated. */
object WorkoutInputRevision {
    fun compute(
        workoutId: String,
        startMs: Long,
        endMs: Long,
        exerciseType: String,
        deviceName: String?,
        samples: List<ComputeWorkoutTrimpUseCase.HeartRateSample>,
    ): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        // Length-prefix strings so separators in imported ids/device names cannot alias inputs.
        digest.addString("workout-input-v1")
        digest.addString(workoutId)
        digest.addLong(startMs)
        digest.addLong(endMs)
        digest.addString(exerciseType)
        digest.addString(deviceName)
        samples
            .filter { it.timestamp.toEpochMilli() in startMs..endMs }
            .sortedWith(compareBy({ it.timestamp }, { it.bpm }))
            .forEach {
                digest.addLong(it.timestamp.toEpochMilli())
                digest.addLong(it.bpm.toLong())
            }
        // The existing persisted revision is a Long; retain 64 bits of the input digest.
        return ByteBuffer.wrap(digest.digest()).long
    }

    private fun MessageDigest.addString(value: String?) {
        val bytes = value?.toByteArray(Charsets.UTF_8)
        addLong(bytes?.size?.toLong() ?: -1L)
        if (bytes != null) update(bytes)
    }

    private fun MessageDigest.addLong(value: Long) {
        update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
    }
}
