package app.readylytics.health.data.audit

import app.readylytics.health.data.local.dao.AuditEventDao
import app.readylytics.health.data.local.entity.AuditEventEntity
import app.readylytics.health.domain.audit.AuditEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals

class RoomAuditTrailRepositoryTest {
    private val dao = FakeAuditEventDao()
    private val repository = RoomAuditTrailRepository(dao)

    @Test
    fun appendPersistsMetadataOnlyEventAndObserveRecentMapsToDomain() =
        runTest {
            val first =
                AuditEvent(
                    type = AuditEvent.Type.RESTORE_STARTED,
                    occurredAt = Instant.ofEpochMilli(1_000),
                    detail = null,
                )
            val second =
                AuditEvent(
                    type = AuditEvent.Type.RESTORE_FAILED,
                    occurredAt = Instant.ofEpochMilli(2_000),
                    detail = "IllegalStateException",
                )

            repository.append(first)
            repository.append(second)

            val recent = repository.observeRecent(limit = 1).first()

            assertEquals(listOf(second.copy(id = 2)), recent)
            assertEquals("restore_started", dao.inserted[0].type)
            assertEquals(1_000, dao.inserted[0].occurredAtEpochMs)
            assertEquals(null, dao.inserted[0].detail)
        }

    private class FakeAuditEventDao : AuditEventDao {
        private val events = MutableStateFlow<List<AuditEventEntity>>(emptyList())
        private var nextId = 1L

        val inserted: List<AuditEventEntity>
            get() = events.value

        override suspend fun insert(event: AuditEventEntity) {
            val id = event.id.takeUnless { it == 0L } ?: nextId++
            events.value = events.value + event.copy(id = id)
        }

        override fun observeRecent(limit: Int): Flow<List<AuditEventEntity>> =
            events.map { current ->
                current
                    .sortedWith(compareByDescending<AuditEventEntity> { it.occurredAtEpochMs }.thenByDescending { it.id })
                    .take(limit)
            }
    }
}
