package app.readylytics.health.domain.security

import app.readylytics.health.domain.audit.AuditEvent
import app.readylytics.health.domain.audit.AuditTrailRepository
import java.time.Instant
import javax.inject.Inject

class DatabaseKeyRotator
    @Inject
    constructor(
        private val rekeyDatabase: suspend () -> Unit,
        private val persistNewKeyMetadata: suspend () -> Unit,
        private val auditTrailRepository: AuditTrailRepository,
    ) {
        suspend fun rotate() {
            try {
                rekeyDatabase()
                persistNewKeyMetadata()
                auditTrailRepository.append(
                    AuditEvent(
                        type = AuditEvent.Type.KEY_ROTATED,
                        occurredAt = Instant.now(),
                        detail = null,
                    ),
                )
            } catch (t: Throwable) {
                auditTrailRepository.append(
                    AuditEvent(
                        type = AuditEvent.Type.KEY_ROTATION_FAILED,
                        occurredAt = Instant.now(),
                        detail = t::class.simpleName,
                    ),
                )
                throw t
            }
        }
    }
