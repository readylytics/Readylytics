package app.readylytics.health.domain.security

import app.readylytics.health.core.model.domain.audit.AuditEvent
import app.readylytics.health.core.model.domain.audit.AuditTrailRepository
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

class DatabaseKeyRotator
    @Inject
    constructor(
        private val rekeyDatabase: DatabaseRekeyer,
        private val persistNewKeyMetadata: KeyMetadataPersister,
        private val auditTrailRepository: AuditTrailRepository,
        private val clock: Clock,
    ) {
        suspend fun rotate() {
            try {
                rekeyDatabase.invoke()
                persistNewKeyMetadata.invoke()
                auditTrailRepository.append(
                    AuditEvent(
                        type = AuditEvent.Type.KEY_ROTATED,
                        occurredAt = Instant.now(clock),
                        detail = null,
                    ),
                )
            } catch (t: Throwable) {
                try {
                    auditTrailRepository.append(
                        AuditEvent(
                            type = AuditEvent.Type.KEY_ROTATION_FAILED,
                            occurredAt = Instant.now(clock),
                            detail = t::class.simpleName,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                throw t
            }
        }
    }
