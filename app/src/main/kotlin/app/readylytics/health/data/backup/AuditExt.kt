package app.readylytics.health.data.backup

import app.readylytics.health.core.model.domain.util.logW
import app.readylytics.health.domain.audit.AuditEvent
import app.readylytics.health.domain.audit.AuditTrailRepository
import kotlinx.coroutines.CancellationException

internal suspend fun AuditTrailRepository.appendBestEffort(
    tag: String,
    event: AuditEvent,
) {
    try {
        append(event)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logW(tag, e) { "Failed to append ${event.type.storageKey} audit event" }
    }
}
