package app.readylytics.health.domain.audit

import kotlinx.coroutines.flow.Flow

interface AuditTrailRepository {
    suspend fun append(event: AuditEvent)

    fun observeRecent(limit: Int = 100): Flow<List<AuditEvent>>
}
