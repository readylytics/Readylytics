package app.readylytics.health.data.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import app.readylytics.health.data.healthconnect.toDomain
import app.readylytics.health.domain.model.DomainExerciseSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class HealthConnectRecordConvertersTest {
    @Test
    fun testExerciseSessionRecordToDomain() {
        val startTime = Instant.parse("2026-07-21T09:00:00Z")
        val endTime = Instant.parse("2026-07-21T10:00:00Z")
        val record = ExerciseSessionRecord(
            startTime = startTime,
            endTime = endTime,
            startZoneOffset = null,
            endZoneOffset = null,
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            title = "Morning Run",
            notes = null,
            metadata = Metadata.manualEntryWithId(id = "test-id")
        )
        val domain: DomainExerciseSessionRecord = record.toDomain()
        assertEquals("test-id", domain.id)
        assertEquals(startTime, domain.startTime)
        assertEquals(endTime, domain.endTime)
    }
}
