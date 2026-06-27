package app.readylytics.health.domain.security

import app.readylytics.health.domain.audit.AuditEvent
import app.readylytics.health.domain.audit.AuditTrailRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DatabaseKeyRotatorTest {
    @Test
    fun rotateRecordsSuccessAuditEventAfterRekeySucceeds() =
        runTest {
            val audit = FakeAuditTrailRepository()
            val rotator =
                DatabaseKeyRotator(
                    rekeyDatabase = { },
                    persistNewKeyMetadata = { },
                    auditTrailRepository = audit,
                )

            rotator.rotate()

            assertEquals(listOf(AuditEvent.Type.KEY_ROTATED), audit.events.map { it.type })
        }

    @Test
    fun rotateRecordsFailureAuditEventWhenRekeyFails() =
        runTest {
            val audit = FakeAuditTrailRepository()
            val rotator =
                DatabaseKeyRotator(
                    rekeyDatabase = { error("boom") },
                    persistNewKeyMetadata = { },
                    auditTrailRepository = audit,
                )

            try {
                rotator.rotate()
                error("Expected exception was not thrown")
            } catch (e: IllegalStateException) {
                // expected
            }

            assertEquals(listOf(AuditEvent.Type.KEY_ROTATION_FAILED), audit.events.map { it.type })
        }

    private class FakeAuditTrailRepository : AuditTrailRepository {
        val events = mutableListOf<AuditEvent>()

        override suspend fun append(event: AuditEvent) {
            events += event
        }

        override fun observeRecent(limit: Int): Flow<List<AuditEvent>> = flowOf(events)
    }
}
