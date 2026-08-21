package app.readylytics.health.core.model.domain.audit

import kotlinx.coroutines.flow.Flow

interface AuditTrailRepository {
    suspend fun append(event: AuditEvent)

    fun observeRecent(limit: Int = 100): Flow<List<AuditEvent>>
}
