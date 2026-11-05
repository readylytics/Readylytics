package app.readylytics.health.domain.security

import app.readylytics.health.core.model.domain.audit.AuditEvent
import app.readylytics.health.core.model.domain.audit.AuditTrailRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertFailsWith

class DatabaseKeyRotatorTest {
    private val clock = Clock.fixed(Instant.parse("2026-03-04T05:06:07Z"), ZoneOffset.UTC)

    @Test
    fun rotateRecordsSuccessAuditEventAfterRekeySucceeds() =
        runTest {
            val audit = FakeAuditTrailRepository()
            val rotator =
                DatabaseKeyRotator(
                    rekeyDatabase = { },
                    persistNewKeyMetadata = { },
                    auditTrailRepository = audit,
                    clock = clock,
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
                    clock = clock,
                )

            assertFailsWith<IllegalStateException> { rotator.rotate() }

            assertEquals(listOf(AuditEvent.Type.KEY_ROTATION_FAILED), audit.events.map { it.type })
        }

    @Test
    fun rotateStampsKeyRotatedEventWithInjectedClockInstant() =
        runTest {
            val audit = FakeAuditTrailRepository()
            val rotator =
                DatabaseKeyRotator(
                    rekeyDatabase = { },
                    persistNewKeyMetadata = { },
                    auditTrailRepository = audit,
                    clock = clock,
                )

            rotator.rotate()

            assertEquals(listOf(clock.instant()), audit.events.map { it.occurredAt })
        }

    @Test
    fun failedRotateStampsKeyRotationFailedEventWithInjectedClockInstant() =
        runTest {
            val audit = FakeAuditTrailRepository()
            val rotator =
                DatabaseKeyRotator(
                    rekeyDatabase = { error("boom") },
                    persistNewKeyMetadata = { },
                    auditTrailRepository = audit,
                    clock = clock,
                )

            assertFailsWith<IllegalStateException> { rotator.rotate() }

            assertEquals(listOf(clock.instant()), audit.events.map { it.occurredAt })
        }

    private class FakeAuditTrailRepository : AuditTrailRepository {
        val events = mutableListOf<AuditEvent>()

        override suspend fun append(event: AuditEvent) {
            events += event
        }

        override fun observeRecent(limit: Int): Flow<List<AuditEvent>> = flowOf(events)
    }
}
