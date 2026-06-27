package app.readylytics.health.domain.security

import app.readylytics.health.domain.audit.AuditEvent
import app.readylytics.health.domain.audit.AuditTrailRepository
import kotlinx.coroutines.CancellationException
import java.time.Instant
import javax.inject.Inject

class DatabaseKeyRotator
    @Inject
    constructor(
        private val rekeyDatabase: DatabaseRekeyer,
        private val persistNewKeyMetadata: KeyMetadataPersister,
        private val auditTrailRepository: AuditTrailRepository,
    ) {
        suspend fun rotate() {
            try {
                rekeyDatabase.invoke()
                persistNewKeyMetadata.invoke()
                auditTrailRepository.append(
                    AuditEvent(
                        type = AuditEvent.Type.KEY_ROTATED,
                        occurredAt = Instant.now(),
                        detail = null,
                    ),
                )
            } catch (t: Throwable) {
                try {
                    auditTrailRepository.append(
                        AuditEvent(
                            type = AuditEvent.Type.KEY_ROTATION_FAILED,
                            occurredAt = Instant.now(),
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
